/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.time.LocalDateTime
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueueItem

// Glue between the player and the AdaptiveShuffleEngine. The engine speaks in
// CandidateItem/DecisionContext; this class translates from PlayQueue/PlayQueueItem
// and owns the recent-decision window used for the now-playing confidence badge.
class AdaptivePlayerIntegration(
    val engine: AdaptiveShuffleEngine,
    private val materialiser: CandidateMaterialiser,
    private val repository: AdaptiveShuffleRepository,
    private val telemetry: TelemetryLog? = null
) {
    private val resolutionDecisionTimestamps = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val recentScores = ArrayDeque<LinUCB.Score>()
    private var sessionPosition: Int = 0
    private var previousUploader: String? = null
    private var previousStreamId: Long? = null
    private var previousAutoQueued: Boolean = false
    private var previousCompleted: Boolean = false
    private val skipWindow = ArrayDeque<Boolean>()
    private val skipWindowSize = 10

    fun ensureInitialised() {
        if (engine.numEvents() == 0L) {
            val result = engine.initialise()
            if (result is AdaptiveShuffleEngine.InitialiseResult.SchemaMismatch) {
                engine.rebuildFromPending()
            }
        }
    }

    fun onTrackResolution(streamId: Long, progressMillis: Long, durationMillis: Long) {
        val currentRestart = repository.getRestartCount(streamId)
        val reward = engine.resolveOutcome(streamId, progressMillis, durationMillis, currentRestart)
        if (reward != null) {
            previousCompleted = reward >= AdaptiveShuffleConfig.REWARD_COMPLETION - 0.001
            recordSkipObservation(reward < 0.0 && progressMillis < (durationMillis * AdaptiveShuffleConfig.EARLY_SKIP_BELOW).toLong())
            val now = System.currentTimeMillis()
            val ratio = if (durationMillis > 0) progressMillis.toDouble() / durationMillis else 0.0
            val decisionTime = resolutionDecisionTimestamps.remove(streamId) ?: now
            telemetry?.logResolution(streamId, ratio, reward, now - decisionTime, now)
        }
    }

    // Picks the index, in playQueue.streams, that should play next. Returns null when no
    // change is required (either no remaining items or the queue is too short to score).
    fun pickNextIndex(playQueue: PlayQueue, queueOrigin: QueueOrigin, samplingCap: Int = 30): Int? {
        val current = playQueue.index
        val all = playQueue.streams
        if (current + 1 >= all.size) return null
        val tail = all.subList(current + 1, all.size)
        val sampledItems = sampleFromTail(tail, samplingCap)
        if (sampledItems.isEmpty()) return null
        val candidates = materialiser.materialise(sampledItems.map { it.second }, previousStreamId)
        if (candidates.isEmpty()) return null
        val context = buildContext(queueOrigin)
        val decision = engine.chooseNext(candidates, context)
        rememberScore(decision.score)
        val chosenStreamId = decision.chosen.streamId
        // Find the original tail index. Materialisation can drop items, so iterate over
        // the sampled subset to locate the originating play-queue index.
        for ((originalTailIdx, item) in sampledItems) {
            val stream = repository.getStreamForUrl(item.serviceId, item.url)
            if (stream != null && stream.uid == chosenStreamId) {
                val absoluteIdx = current + 1 + originalTailIdx
                engine.captureSnapshot(chosenStreamId, repository.getRestartCount(chosenStreamId))
                previousUploader = item.uploader
                previousStreamId = chosenStreamId
                previousAutoQueued = item.isAutoQueued
                val now = System.currentTimeMillis()
                resolutionDecisionTimestamps[chosenStreamId] = now
                telemetry?.logDecision(
                    chosenStreamId = chosenStreamId,
                    candidateCount = candidates.size,
                    predictedReward = decision.score.predictedReward,
                    uncertainty = decision.score.uncertainty,
                    nowMillis = now
                )
                return absoluteIdx
            }
        }
        return null
    }

    fun confidenceFromLastDecision(): Double {
        val window = recentScores.toList()
        if (window.isEmpty()) return 0.5
        val maxUnc = window.maxOf { it.uncertainty }.coerceAtLeast(1e-8)
        val last = window.last()
        val normalised = (last.uncertainty / maxUnc).coerceIn(0.0, 1.0)
        return 1.0 - normalised
    }

    fun lastDecisionWasExploit(): Boolean {
        val last = recentScores.lastOrNull() ?: return true
        return last.predictedReward > last.uncertainty
    }

    fun resetSession() {
        sessionPosition = 0
        previousUploader = null
        previousStreamId = null
        previousAutoQueued = false
        previousCompleted = false
        skipWindow.clear()
    }

    private fun rememberScore(score: LinUCB.Score) {
        recentScores.addLast(score)
        while (recentScores.size > AdaptiveShuffleConfig.CONFIDENCE_NORMALISATION_WINDOW) {
            recentScores.removeFirst()
        }
        sessionPosition += 1
    }

    private fun recordSkipObservation(isEarlySkip: Boolean) {
        skipWindow.addLast(isEarlySkip)
        while (skipWindow.size > skipWindowSize) skipWindow.removeFirst()
    }

    private fun recentSkipRate(): Double {
        if (skipWindow.isEmpty()) return 0.2
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

    private fun sampleFromTail(
        tail: List<PlayQueueItem>,
        cap: Int
    ): List<Pair<Int, PlayQueueItem>> {
        if (tail.size <= cap) return tail.mapIndexed { i, it -> i to it }
        // Reservoir-style sample without replacement.
        val indices = IntArray(tail.size) { it }
        val rng = java.util.Random()
        for (i in indices.indices) {
            val j = i + rng.nextInt(indices.size - i)
            val tmp = indices[i]
            indices[i] = indices[j]
            indices[j] = tmp
        }
        return (0 until cap).map { indices[it] to tail[indices[it]] }
    }
}
