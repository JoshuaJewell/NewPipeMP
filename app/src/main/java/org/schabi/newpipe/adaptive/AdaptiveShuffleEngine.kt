/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.util.concurrent.ConcurrentHashMap
import org.schabi.newpipe.database.adaptive.AdaptiveShufflePendingEventEntity

// Owns the persisted LinUCB model, the feature scaler, and the in-memory snapshots
// of in-flight tracks. Single instance per process, held by the player service.
class AdaptiveShuffleEngine(
    private val repository: AdaptiveShuffleRepository,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private var linucb: LinUCB = LinUCB(FeatureSchema.DIMENSION)
    private var scaler: FeatureScaler = FeatureScaler(FeatureSchema.scaledIndices)
    private var nEvents: Long = 0L
    private var initialised: Boolean = false
    private val snapshots = ConcurrentHashMap<Long, PlaybackSnapshot>()

    sealed class InitialiseResult {
        object Fresh : InitialiseResult()
        object Loaded : InitialiseResult()
        object SchemaMismatch : InitialiseResult()
        object AlreadyInitialised : InitialiseResult()
    }

    data class DecisionResult(
        val chosen: CandidateItem,
        val score: LinUCB.Score,
        val pendingEventId: Long
    )

    fun initialise(): InitialiseResult = synchronized(lock) {
        if (initialised) return@synchronized InitialiseResult.AlreadyInitialised
        val loaded = repository.loadModel()
        if (loaded == null) {
            initialised = true
            return@synchronized InitialiseResult.Fresh
        }
        if (loaded.featureSchemaHash != FeatureSchema.schemaHash) {
            return@synchronized InitialiseResult.SchemaMismatch
        }
        linucb = loaded.linucb
        scaler = loaded.scaler
        nEvents = loaded.nEvents
        initialised = true
        InitialiseResult.Loaded
    }

    fun chooseNext(candidates: List<CandidateItem>, context: DecisionContext): DecisionResult {
        require(candidates.isNotEmpty()) { "candidates must not be empty" }
        return synchronized(lock) {
            val rawVectors = Array(candidates.size) { i -> FeatureExtractor.buildRawVector(context, candidates[i]) }
            val scaledVectors = Array(candidates.size) { i -> scaler.transform(rawVectors[i]) }
            var bestIdx = 0
            var bestScore = linucb.score(scaledVectors[0])
            for (i in 1 until candidates.size) {
                val s = linucb.score(scaledVectors[i])
                if (s.total > bestScore.total) {
                    bestScore = s
                    bestIdx = i
                }
            }
            val chosen = candidates[bestIdx]
            val event = AdaptiveShufflePendingEventEntity(
                streamId = chosen.streamId,
                chosenAt = clock(),
                featureVectorBlob = RawInputsCodec.encodeFeatureVector(scaledVectors[bestIdx]),
                rawInputsBlob = RawInputsCodec.encode(context, chosen),
                predictedReward = bestScore.predictedReward,
                uncertainty = bestScore.uncertainty,
                candidateCount = candidates.size
            )
            val eventId = repository.insertPending(event)
            DecisionResult(chosen, bestScore, eventId)
        }
    }

    fun captureSnapshot(streamId: Long, currentRestartCount: Int) {
        snapshots[streamId] = PlaybackSnapshot(streamId, clock(), currentRestartCount)
    }

    fun discardSnapshot(streamId: Long) {
        snapshots.remove(streamId)
    }

    fun resolveOutcome(
        streamId: Long,
        progressMillis: Long,
        durationMillis: Long,
        currentRestartCount: Int
    ): Double? {
        val snapshot = snapshots.remove(streamId) ?: return null
        val restartDelta = (currentRestartCount - snapshot.restartCountAtStart).coerceAtLeast(0)
        val reward = RewardCalculator.computeReward(progressMillis, durationMillis, restartDelta)
        synchronized(lock) {
            val eventId = repository.findLatestUnresolvedForStream(streamId) ?: return reward
            val event = repository.getRecent(50).firstOrNull { it.id == eventId } ?: return reward
            val featureVector = RawInputsCodec.decodeFeatureVector(event.featureVectorBlob)
            repository.resolvePending(eventId, reward, clock())
            linucb.update(featureVector, reward)
            scaler.observe(featureVector)
            nEvents += 1L
            repository.saveModel(linucb, scaler, nEvents, clock())
        }
        return reward
    }

    fun rebuildFromPending(): Int = synchronized(lock) {
        val freshLinucb = LinUCB(FeatureSchema.DIMENSION)
        val freshScaler = FeatureScaler(FeatureSchema.scaledIndices)
        var count = 0L
        val resolved = repository.getAllResolvedOrdered()
        for (e in resolved) {
            val reward = e.reward ?: continue
            val raw = try {
                RawInputsCodec.decode(e.rawInputsBlob)
            } catch (t: Throwable) {
                continue
            }
            val rawV = FeatureExtractor.buildRawVector(raw.context, raw.item)
            freshScaler.observe(rawV)
            val scaledV = freshScaler.transform(rawV)
            freshLinucb.update(scaledV, reward)
            count += 1L
        }
        linucb = freshLinucb
        scaler = freshScaler
        nEvents = count
        initialised = true
        repository.saveModel(linucb, scaler, nEvents, clock())
        resolved.size
    }

    fun theta(): DoubleArray = synchronized(lock) { linucb.theta() }
    fun numEvents(): Long = synchronized(lock) { nEvents }
    fun snapshotCount(): Int = snapshots.size

    fun replaceWith(linucb: LinUCB, scaler: FeatureScaler, nEvents: Long) {
        synchronized(lock) {
            this.linucb = linucb
            this.scaler = scaler
            this.nEvents = nEvents
            initialised = true
            repository.saveModel(linucb, scaler, nEvents, clock())
        }
    }

    data class RecentDecision(
        val chosenAt: Long,
        val streamId: Long,
        val streamTitle: String?,
        val predictedReward: Double,
        val uncertainty: Double,
        val reward: Double?,
        val exploit: Boolean
    )

    fun recentDecisions(limit: Int = 20): List<RecentDecision> {
        val rows = repository.getRecent(limit)
        return rows.map { event ->
            RecentDecision(
                chosenAt = event.chosenAt,
                streamId = event.streamId,
                streamTitle = repository.getStreamTitle(event.streamId),
                predictedReward = event.predictedReward,
                uncertainty = event.uncertainty,
                reward = event.reward,
                exploit = event.predictedReward > event.uncertainty
            )
        }
    }
}
