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
    private val rng: Random = Random()
) {
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

    // Picks the index, in playQueue.streams, that should play next. Returns null when no
    // change is required (no remaining items, or nothing could be scored).
    @JvmOverloads
    fun pickNextIndex(
        playQueue: PlayQueue,
        samplingCap: Int = AdaptiveShuffleConfig.CANDIDATE_SAMPLE_CAP
    ): Int? {
        val current = playQueue.index
        val all = playQueue.streams
        if (current < 0 || current >= all.size) return null

        val currentItem = all[current]
        val currentStreamId = repository.getStreamForUrl(currentItem.serviceId, currentItem.url)?.uid
        discardUnplayedDecision(currentStreamId)
        noteNowPlaying(currentItem)

        if (current + 1 >= all.size) return null
        val tail = all.subList(current + 1, all.size)
        val entries = tail.mapIndexed { i, item ->
            CandidateFilter.TailEntry(i, CandidateFilter.key(item.serviceId, item.url), item.uploader)
        }
        val filtered = CandidateFilter.filter(entries, recentKeys.toList(), recentUploaders.toList())
        val sampled = CandidateFilter.sample(filtered.eligible, samplingCap, rng)
        if (sampled.isEmpty()) return null

        val sampledItems = sampled.map { it.tailIndex to tail[it.tailIndex] }
        val candidates = materialiser.materialise(sampledItems.map { it.second }, previousStreamId)
        if (candidates.isEmpty()) return null
        val context = buildContext(originOf(playQueue))
        val decision = engine.chooseNext(candidates, context, selectionMode())
        rememberScore(decision.score, decision.greedy)
        val chosenStreamId = decision.chosen.streamId

        // Materialisation can drop items, so locate the chosen one among the sampled
        // subset to recover its play-queue index.
        for ((tailIdx, item) in sampledItems) {
            val stream = repository.getStreamForUrl(item.serviceId, item.url)
            if (stream != null && stream.uid == chosenStreamId) {
                val absoluteIdx = current + 1 + tailIdx
                engine.captureSnapshot(chosenStreamId, repository.getRestartCount(chosenStreamId))
                previousUploader = item.uploader
                previousStreamId = chosenStreamId
                previousAutoQueued = item.isAutoQueued
                lastDecision = chosenStreamId to decision.pendingEventId
                val now = System.currentTimeMillis()
                resolutionDecisionTimestamps[chosenStreamId] = now
                telemetry?.logDecision(
                    chosenStreamId = chosenStreamId,
                    candidateCount = candidates.size,
                    predictedReward = decision.score.predictedReward,
                    uncertainty = decision.score.uncertainty,
                    greedy = decision.greedy,
                    nowMillis = now
                )
                return absoluteIdx
            }
        }
        return null
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
