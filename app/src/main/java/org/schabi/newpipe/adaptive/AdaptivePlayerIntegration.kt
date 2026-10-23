/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.time.LocalDateTime
import java.util.Random
import kotlin.math.roundToInt
import org.schabi.newpipe.player.playqueue.ChannelTabPlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueueItem
import org.schabi.newpipe.player.playqueue.PlaylistPlayQueue
import org.schabi.newpipe.player.playqueue.SinglePlayQueue

// Glue between the player and the AdaptiveShuffleEngine. The engine speaks in
// CandidateItem/DecisionContext; this class translates from PlayQueue/PlayQueueItem,
// applies the hard candidate rules (recency, uploader spacing), and owns the session
// state used for context features and the now-playing badge.
class AdaptivePlayerIntegration(
    val engine: AdaptiveShuffleEngine,
    private val materialiser: CandidateMaterialiser,
    private val repository: AdaptiveShuffleRepository,
    private val telemetry: TelemetryLog? = null,
    private val selectionMode: () -> SelectionMode = { SelectionMode.UCB },
    private val rng: Random = Random(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val conditions: () -> ListeningConditions = { ListeningConditions.UNKNOWN },
    // Share of picks drawn at random instead, to compare the model against (ArmComparison).
    private val baselineShare: () -> Double = { 0.0 }
) {
    // Every method here runs on AdaptiveShuffleHolder.SCHEDULER, a single thread, so
    // resolutions and picks happen in the order the player asked for them.
    private val session = ShuffleSession()
    private var lastActivityAt = 0L

    private val resolutionDecisionTimestamps = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val recentScores = ArrayDeque<HybridLinUCB.Score>()
    private var lastGreedy: Boolean = true
    private var sessionPosition: Int = 0
    private var previousUploader: String? = null
    private var previousStreamId: Long? = null
    private var previousAutoQueued: Boolean = false
    private var previousCompleted: Boolean = false
    private val skipWindow = ArrayDeque<Boolean>()
    private val skipWindowSize = 10

    // Most recent first. Seeded from stream_history so exclusions survive restarts and
    // new queues; within a single queue pass, played items already fall behind the index.
    private val recentKeys = ArrayDeque<String>()
    private val recentUploaders = ArrayDeque<String?>()
    private var recencySeeded = false

    // The decision whose track is expected to play next. If the user skips the current
    // track before it starts, that decision is discarded as never having played.
    private var lastDecision: Pair<Long, Long>? = null // (streamId, pendingEventId)

    // The track the listener last chose themselves (a queue tap, "play next"), as a
    // CandidateFilter key, until it starts playing (spec section 6.7).
    private var pendingUserChoice: String? = null

    fun ensureInitialised() {
        if (engine.numEvents() == 0L) {
            val result = engine.initialise()
            if (result is AdaptiveShuffleEngine.InitialiseResult.SchemaMismatch) {
                engine.rebuildFromPending(telemetryIndex())
            }
        }
    }

    fun telemetryIndex(): OutcomeRecovery.Index? {
        val lines = telemetry?.readLines() ?: return null
        val index = OutcomeRecovery.Index(OutcomeRecovery.parseResolutions(lines.asSequence()))
        return if (index.isEmpty()) null else index
    }

    // A listen ended, provisionally judged (the player may still find the listener came
    // straight back to it). The context of the next picks follows it at once.
    fun noteListenEnded(completionRatio: Double) {
        previousCompleted = completionRatio >= AdaptiveShuffleConfig.COMPLETION_AT_OR_ABOVE
        recordSkipObservation(completionRatio < AdaptiveShuffleConfig.EARLY_SKIP_BELOW)
    }

    private fun onTrackResolution(streamId: Long, outcome: RawInputsCodec.Outcome): Boolean {
        val resolution = engine.resolveOutcome(streamId, outcome) ?: return false
        val now = System.currentTimeMillis()
        val decisionTime = resolutionDecisionTimestamps.remove(streamId) ?: now
        telemetry?.logResolution(
            streamId,
            resolution.completionRatio,
            resolution.reward,
            resolution.restartDelta,
            now - decisionTime,
            now
        )
        return true
    }

    // Picks the queue item that should play after the current one, or null when there is
    // nothing left to pick. The caller moves it into place (ShuffleSession.placementTarget)
    // and looks it up by identity, since the queue may change while this runs.
    @JvmOverloads
    fun pickNext(
        playQueue: PlayQueue,
        repeatAll: Boolean = false,
        samplingCap: Int = AdaptiveShuffleConfig.CANDIDATE_SAMPLE_CAP
    ): PlayQueueItem? {
        val all = ArrayList(playQueue.streams)
        val current = playQueue.index
        if (current < 0 || current >= all.size) return null

        val now = clock()
        val currentItem = all[current]
        val currentRow = repository.getStreamForUrl(currentItem.serviceId, currentItem.url)
        val currentStreamId = currentRow?.uid
        // A track the listener chose: record it, and the pick it displaced, before that
        // pick is discarded below. Its context is the one it was chosen in.
        if (pendingUserChoice != null && pendingUserChoice == CandidateFilter.key(currentItem.serviceId, currentItem.url)) {
            pendingUserChoice = null
            // Tapping the model's own pick is not a choice against it.
            if (lastDecision?.first != currentStreamId) recordUserPick(playQueue, currentItem, currentStreamId, now)
        }
        // Before any reset, which forgets the last decision without discarding it.
        discardUnplayedDecision(currentStreamId)
        if (session.begin(playQueue)) {
            resetSession()
        } else if (lastActivityAt > 0L && now - lastActivityAt > AdaptiveShuffleConfig.SESSION_GAP_MILLIS) {
            // Same queue, but picked up again after a long pause: a new listening session
            // for the context features.
            resetSession()
        }
        lastActivityAt = now
        noteNowPlaying(currentItem)
        // The pick follows the track now playing, so that is its previous track, whoever
        // chose it. Taken from the streams row, as the candidates' uploaders are.
        previousUploader = currentRow?.uploader
        previousStreamId = currentStreamId
        previousAutoQueued = currentItem.isAutoQueued

        val keys = all.map { CandidateFilter.key(it.serviceId, it.url) }
        session.notePlayed(keys[current])
        val pool = session.candidateIndices(keys, current, repeatAll)
        if (pool.isEmpty()) return null
        val entries = pool.map { i -> CandidateFilter.TailEntry(i, keys[i], uploaderKey(all[i])) }
        val filtered = CandidateFilter.filter(entries, recentKeys.toList(), recentUploaders.toList())
        val sampled = CandidateFilter.sample(filtered.eligible, samplingCap, rng)
        if (sampled.isEmpty()) return null
        val sampledItems = sampled.map { all[it.tailIndex] }

        val pairs = materialiser.materialisePairs(sampledItems, previousStreamId)
        if (pairs.isEmpty()) {
            // Nothing could be scored (no database rows could be made). Still shuffle:
            // falling through would play the playlist's own next track.
            return sampledItems[rng.nextInt(sampledItems.size)]
        }
        val context = buildContext(originOf(playQueue))
        val baseline = rng.nextDouble() < baselineShare()
        val decision = engine.chooseNext(pairs.map { it.second }, context, selectionMode(), baseline)
        rememberScore(decision.score, decision.greedy)
        val chosenStreamId = decision.chosen.streamId
        val chosenItem = pairs.first { it.second === decision.chosen }.first

        engine.captureSnapshot(chosenStreamId, repository.getRestartCount(chosenStreamId))
        lastDecision = chosenStreamId to decision.pendingEventId
        resolutionDecisionTimestamps[chosenStreamId] = now
        telemetry?.logDecision(
            chosenStreamId = chosenStreamId,
            candidateCount = pairs.size,
            predictedReward = decision.score.predictedReward,
            uncertainty = decision.score.uncertainty,
            greedy = decision.greedy,
            baseline = decision.baseline,
            nowMillis = now
        )
        return chosenItem
    }

    // The player recorded a listen. outcome is null when the listen says nothing about the
    // track (see ListenJudge); its decision, if one chose it, is then dropped. streamId is
    // the row playback recorded the listen against; the queue item's own row (serviceId
    // and url) is tried too, should a decision have been made under it.
    fun onTrackLeft(streamId: Long, serviceId: Int, url: String, outcome: RawInputsCodec.Outcome?) {
        lastActivityAt = clock()
        val alias = repository.getStreamForUrl(serviceId, url)?.uid?.takeIf { it != streamId }
        if (outcome != null) {
            if (!onTrackResolution(streamId, outcome)) alias?.let { onTrackResolution(it, outcome) }
        } else {
            for (id in listOfNotNull(streamId, alias)) {
                engine.abandon(id)
                resolutionDecisionTimestamps.remove(id)
            }
        }
    }

    // How the model rates a track now, for showing beside the listener's own rating.
    // predicted: its mean predicted reward; ownOffset: the part that is this track's own
    // (HybridLinUCB). rating: 1 to 10 by where the prediction falls among a sample of the
    // queue scored in the same context, so 8 means "in the model's top 20% of this queue
    // right now"; null when the queue is too small to compare against.
    data class Standing(
        val predicted: Double,
        val ownOffset: Double,
        val rating: Int?,
        val comparedWith: Int,
        val breakdown: AdaptiveShuffleEngine.Breakdown
    )

    fun standingOf(item: PlayQueueItem, queue: PlayQueue?): Standing? {
        val key = CandidateFilter.key(item.serviceId, item.url)
        val others = queue?.streams.orEmpty().filter { CandidateFilter.key(it.serviceId, it.url) != key }
        val sample = CandidateFilter.sample(others, STANDING_SAMPLE, rng)
        val pairs = materialiser.materialisePairs(listOf(item) + sample, previousStreamId)
        val mine = pairs.indexOfFirst { it.first === item }
        if (mine < 0) return null
        val context = buildContext(queue?.let { originOf(it) } ?: QueueOrigin.OTHER)
        val scores = engine.predict(pairs.map { it.second }, context)
        val own = scores[mine]
        val rest = scores.filterIndexed { i, _ -> i != mine }
        val rating = if (rest.size >= STANDING_MIN_COMPARED) {
            val below = rest.count { it.predictedReward < own.predictedReward }.toDouble() / rest.size
            1 + (below * 9.0).roundToInt()
        } else {
            null
        }
        return Standing(own.predictedReward, own.offset, rating, rest.size, engine.explain(pairs[mine].second, context))
    }

    // Writes AdaptiveShuffleExport's zip.
    fun exportTo(out: java.io.OutputStream) = AdaptiveShuffleExport.write(engine, repository, out, clock())

    // The listener chose a track themselves. It is recorded as their pick when it starts.
    fun onUserChose(serviceId: Int, url: String) {
        pendingUserChoice = CandidateFilter.key(serviceId, url)
    }

    private fun recordUserPick(playQueue: PlayQueue, item: PlayQueueItem, streamId: Long?, now: Long) {
        // The model's pick waiting to play, if this displaced it.
        val displaced = lastDecision?.takeIf { it.first != streamId }?.let { engine.decisionItem(it.second) }
        val chosen = materialiser.materialisePairs(listOf(item), previousStreamId).firstOrNull()?.second ?: return
        val pick = engine.recordUserPick(chosen, buildContext(originOf(playQueue)), displaced)
        engine.captureSnapshot(chosen.streamId, repository.getRestartCount(chosen.streamId))
        resolutionDecisionTimestamps[chosen.streamId] = now
        telemetry?.logDecision(
            chosenStreamId = chosen.streamId,
            candidateCount = 1,
            predictedReward = pick.score.predictedReward,
            uncertainty = pick.score.uncertainty,
            greedy = false,
            nowMillis = now,
            chooser = Chooser.USER
        )
    }

    // The item could not be loaded. It is excluded from picks for the rest of the process
    // and any decision that chose it is dropped, so it is neither retried every session
    // nor counted as a listen.
    fun onUnplayable(serviceId: Int, url: String) {
        session.markUnplayable(CandidateFilter.key(serviceId, url))
        val streamId = repository.getStreamForUrl(serviceId, url)?.uid ?: return
        engine.abandon(streamId)
        resolutionDecisionTimestamps.remove(streamId)
        val decided = lastDecision
        if (decided != null && decided.first == streamId) {
            engine.discardDecision(decided.second, streamId)
            lastDecision = null
        }
    }

    // Shuffle was turned off: the pick waiting to play next will not be played as one.
    fun onShuffleDisabled() {
        lastDecision?.let { (streamId, eventId) ->
            engine.discardDecision(eventId, streamId)
            resolutionDecisionTimestamps.remove(streamId)
        }
        lastDecision = null
    }

    // The player was closed. The track in flight and the pre-pick behind it will never
    // resolve, so drop them rather than leave them awaiting forever.
    fun onPlaybackStopped() {
        lastDecision?.let { (streamId, eventId) -> engine.discardDecision(eventId, streamId) }
        lastDecision = null
        pendingUserChoice = null
        engine.abandonAll()
        resolutionDecisionTimestamps.clear()
    }

    // If the previous decision's track is not the one now playing, the user moved past
    // it before it started: drop it rather than leave an orphan that never resolves.
    private fun discardUnplayedDecision(currentStreamId: Long?) {
        val (decidedStreamId, eventId) = lastDecision ?: return
        if (currentStreamId != decidedStreamId) {
            engine.discardDecision(eventId, decidedStreamId)
            resolutionDecisionTimestamps.remove(decidedStreamId)
        }
        lastDecision = null
    }

    private fun noteNowPlaying(item: PlayQueueItem) {
        seedRecencyIfNeeded()
        val key = CandidateFilter.key(item.serviceId, item.url)
        if (recentKeys.firstOrNull() == key) return
        recentKeys.remove(key)
        recentKeys.addFirst(key)
        while (recentKeys.size > AdaptiveShuffleConfig.RECENCY_EXCLUSION_MAX) recentKeys.removeLast()
        recentUploaders.addFirst(uploaderKey(item))
        while (recentUploaders.size > AdaptiveShuffleConfig.UPLOADER_SPACING) recentUploaders.removeLast()
    }

    private fun seedRecencyIfNeeded() {
        if (recencySeeded) return
        recencySeeded = true
        val rows = try {
            repository.recentlyPlayed(AdaptiveShuffleConfig.RECENCY_EXCLUSION_MAX)
        } catch (t: RuntimeException) {
            emptyList()
        }
        for (row in rows) recentKeys.addLast(CandidateFilter.key(row.serviceId, row.url))
        for (row in rows.take(AdaptiveShuffleConfig.UPLOADER_SPACING)) {
            recentUploaders.addLast(CandidateFilter.uploaderKey(row.uploaderUrl, row.uploader))
        }
    }

    fun confidenceFromLastDecision(): Double {
        val window = recentScores.toList()
        if (window.isEmpty()) return 0.5
        val maxUnc = window.maxOf { it.uncertainty }.coerceAtLeast(1e-8)
        val last = window.last()
        val normalised = (last.uncertainty / maxUnc).coerceIn(0.0, 1.0)
        return 1.0 - normalised
    }

    // Exploit means the pick was also the candidate with the highest mean prediction.
    fun lastDecisionWasExploit(): Boolean = lastGreedy

    fun resetSession() {
        sessionPosition = 0
        previousUploader = null
        previousStreamId = null
        previousAutoQueued = false
        previousCompleted = false
        skipWindow.clear()
        lastDecision = null
    }

    private fun rememberScore(score: HybridLinUCB.Score, greedy: Boolean) {
        recentScores.addLast(score)
        while (recentScores.size > AdaptiveShuffleConfig.CONFIDENCE_NORMALISATION_WINDOW) {
            recentScores.removeFirst()
        }
        lastGreedy = greedy
        sessionPosition += 1
    }

    private fun recordSkipObservation(isEarlySkip: Boolean) {
        skipWindow.addLast(isEarlySkip)
        while (skipWindow.size > skipWindowSize) skipWindow.removeFirst()
    }

    private fun recentSkipRate(): Double {
        if (skipWindow.isEmpty()) return AdaptiveShuffleConfig.COLD_START_SKIP_RATIO
        return skipWindow.count { it }.toDouble() / skipWindow.size
    }

    private fun buildContext(origin: QueueOrigin): DecisionContext {
        val now = LocalDateTime.now()
        val listening = conditions()
        return DecisionContext(
            hourOfDay = now.hour,
            dayOfWeek = (now.dayOfWeek.value - 1).coerceIn(0, 6),
            sessionPosition = sessionPosition,
            recentSkipRate = recentSkipRate(),
            previousAutoQueued = previousAutoQueued,
            previousCompleted = previousCompleted,
            previousUploader = previousUploader,
            previousStreamId = previousStreamId,
            queueOrigin = origin,
            screenOn = listening.screenOn,
            outputRoute = listening.outputRoute
        )
    }

    companion object {
        private const val STANDING_SAMPLE = 200
        private const val STANDING_MIN_COMPARED = 10

        private fun uploaderKey(item: PlayQueueItem): String? = CandidateFilter.uploaderKey(item.uploaderUrl, item.uploader)

        // Local playlists and history lists are played as a SinglePlayQueue holding many
        // items, so a SinglePlayQueue counts as a playlist once it holds more than one
        // track the user (not autoplay) put there.
        @JvmStatic
        fun originOf(queue: PlayQueue): QueueOrigin = when (queue) {
            is PlaylistPlayQueue -> QueueOrigin.PLAYLIST

            is ChannelTabPlayQueue -> QueueOrigin.CHANNEL

            is SinglePlayQueue -> {
                val userChosen = queue.streams.count { !it.isAutoQueued }
                if (userChosen > 1) QueueOrigin.PLAYLIST else QueueOrigin.SINGLE
            }

            else -> QueueOrigin.OTHER
        }
    }
}
