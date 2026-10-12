/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.time.LocalDateTime
import java.util.Random
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
    private val clock: () -> Long = System::currentTimeMillis
) {
    // Every method here runs on AdaptiveShuffleHolder.SCHEDULER, a single thread, so
    // resolutions and picks happen in the order the player asked for them.
    private val session = ShuffleSession()
    private var lastActivityAt = 0L

    private val resolutionDecisionTimestamps = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val recentScores = ArrayDeque<LinUCB.Score>()
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

    fun onTrackResolution(streamId: Long, progressMillis: Long, durationMillis: Long) {
        val currentRestart = repository.getRestartCount(streamId)
        val resolution = engine.resolveOutcome(streamId, progressMillis, durationMillis, currentRestart)
            ?: return
        previousCompleted = resolution.completionRatio >= AdaptiveShuffleConfig.COMPLETION_AT_OR_ABOVE
        recordSkipObservation(resolution.completionRatio < AdaptiveShuffleConfig.EARLY_SKIP_BELOW)
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
        val currentStreamId = repository.getStreamForUrl(currentItem.serviceId, currentItem.url)?.uid
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

        val keys = all.map { CandidateFilter.key(it.serviceId, it.url) }
        session.notePlayed(keys[current])
        val pool = session.candidateIndices(keys, current, repeatAll)
        if (pool.isEmpty()) return null
        val entries = pool.map { i -> CandidateFilter.TailEntry(i, keys[i], all[i].uploader) }
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
        val decision = engine.chooseNext(pairs.map { it.second }, context, selectionMode())
        rememberScore(decision.score, decision.greedy)
        val chosenStreamId = decision.chosen.streamId
        val chosenItem = pairs.first { it.second === decision.chosen }.first

        engine.captureSnapshot(chosenStreamId, repository.getRestartCount(chosenStreamId))
        previousUploader = chosenItem.uploader
        previousStreamId = chosenStreamId
        previousAutoQueued = chosenItem.isAutoQueued
        lastDecision = chosenStreamId to decision.pendingEventId
        resolutionDecisionTimestamps[chosenStreamId] = now
        telemetry?.logDecision(
            chosenStreamId = chosenStreamId,
            candidateCount = pairs.size,
            predictedReward = decision.score.predictedReward,
            uncertainty = decision.score.uncertainty,
            greedy = decision.greedy,
            nowMillis = now
        )
        return chosenItem
    }

    // The player left a track. judged is false when the listen says nothing about the
    // track (see ListenJudge); its decision, if the engine made one, is then dropped.
    fun onTrackLeft(streamId: Long, progressMillis: Long, durationMillis: Long, judged: Boolean) {
        lastActivityAt = clock()
        if (judged) {
            onTrackResolution(streamId, progressMillis, durationMillis)
        } else {
            engine.abandon(streamId)
            resolutionDecisionTimestamps.remove(streamId)
        }
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
        recentUploaders.addFirst(item.uploader)
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
        for (row in rows.take(AdaptiveShuffleConfig.UPLOADER_SPACING)) recentUploaders.addLast(row.uploader)
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

    private fun rememberScore(score: LinUCB.Score, greedy: Boolean) {
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
        return DecisionContext(
            hourOfDay = now.hour,
            dayOfWeek = (now.dayOfWeek.value - 1).coerceIn(0, 6),
            sessionPosition = sessionPosition,
            recentSkipRate = recentSkipRate(),
            previousAutoQueued = previousAutoQueued,
            previousCompleted = previousCompleted,
            previousUploader = previousUploader,
            previousStreamId = previousStreamId,
            queueOrigin = origin
        )
    }

    companion object {
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
