/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import org.schabi.newpipe.database.adaptive.AdaptiveShufflePendingEventEntity

enum class SelectionMode { UCB, THOMPSON }

// Owns the persisted model, the feature scaler, and the in-memory snapshots of in-flight
// tracks. Single instance per process, held by AdaptiveShuffleHolder.
class AdaptiveShuffleEngine(
    private val store: AdaptiveShuffleStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val rng: Random = Random(),
    private val discount: Double = AdaptiveShuffleConfig.DISCOUNT_GAMMA
) {
    private val lock = Any()
    private var linucb: LinUCB = freshModel()
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
        // Mean prediction and uncertainty of the chosen candidate, whichever rule chose it.
        val score: LinUCB.Score,
        val pendingEventId: Long,
        // True when the pick is also the candidate with the highest mean prediction, i.e.
        // the model chose what it already believed best. False means exploration chose it.
        val greedy: Boolean
    )

    data class RebuildReport(
        val resolvedEvents: Int,
        val ingested: Int,
        // Rescored under the current reward from an outcome stored in the event.
        val fromStoredOutcome: Int,
        // Outcome recovered from the telemetry log and written back into the event.
        val fromTelemetry: Int,
        // No outcome available; the reward recorded at the time is used as-is.
        val carriedOver: Int,
        // Undecodable raw inputs (e.g. v1 blobs).
        val skipped: Int
    )

    private fun freshModel(): LinUCB = LinUCB(FeatureSchema.DIMENSION, discount = discount)

    fun initialise(): InitialiseResult = synchronized(lock) {
        if (initialised) return@synchronized InitialiseResult.AlreadyInitialised
        val loaded = store.loadModel()
        if (loaded == null) {
            initialised = true
            return@synchronized InitialiseResult.Fresh
        }
        if (loaded.featureSchemaHash != FeatureSchema.schemaHash) {
            return@synchronized InitialiseResult.SchemaMismatch
        }
        linucb = LinUCB.fromByteArray(loaded.linucbBlob, discount = discount)
        scaler = FeatureScaler.fromByteArray(loaded.scalerBlob)
        nEvents = loaded.nEvents
        initialised = true
        InitialiseResult.Loaded
    }

    fun chooseNext(
        candidates: List<CandidateItem>,
        context: DecisionContext,
        mode: SelectionMode = SelectionMode.UCB
    ): DecisionResult {
        require(candidates.isNotEmpty()) { "candidates must not be empty" }
        return synchronized(lock) {
            val scaledVectors = Array(candidates.size) { i ->
                scaler.transform(FeatureExtractor.buildRawVector(context, candidates[i]))
            }
            val scores = Array(candidates.size) { i -> linucb.score(scaledVectors[i]) }
            val greedyIdx = scores.indices.maxByOrNull { scores[it].predictedReward } ?: 0
            val chosenIdx = when (mode) {
                SelectionMode.UCB -> scores.indices.maxByOrNull { scores[it].total } ?: 0
                SelectionMode.THOMPSON -> {
                    val theta = linucb.sampleTheta(rng)
                    scaledVectors.indices.maxByOrNull { LinUCB.dot(theta, scaledVectors[it]) } ?: 0
                }
            }
            val chosen = candidates[chosenIdx]
            val greedy = chosenIdx == greedyIdx
            val event = AdaptiveShufflePendingEventEntity(
                streamId = chosen.streamId,
                chosenAt = clock(),
                featureVectorBlob = RawInputsCodec.encodeFeatureVector(scaledVectors[chosenIdx]),
                rawInputsBlob = RawInputsCodec.encode(context, chosen, greedy = greedy),
                predictedReward = scores[chosenIdx].predictedReward,
                uncertainty = scores[chosenIdx].uncertainty,
                candidateCount = candidates.size
            )
            val eventId = store.insertPending(event)
            DecisionResult(chosen, scores[chosenIdx], eventId, greedy)
        }
    }

    fun captureSnapshot(streamId: Long, currentRestartCount: Int) {
        snapshots[streamId] = PlaybackSnapshot(streamId, clock(), currentRestartCount)
    }

    fun discardSnapshot(streamId: Long) {
        snapshots.remove(streamId)
    }

    // A decision whose track never played (the user skipped past it before it started).
    // It carries no reward signal, and left in place it would show as "awaiting" forever
    // and could be wrongly resolved by a later, unrelated play of the same track.
    fun discardDecision(eventId: Long, streamId: Long) {
        synchronized(lock) {
            val event = store.getPendingById(eventId) ?: return
            if (event.resolved) return
            store.deletePending(eventId)
        }
        snapshots.remove(streamId)
    }

    // A track that played but whose listen says nothing (left during the shuffle start
    // grace, removed from the queue, playback closed): forget its decision instead of
    // resolving it. Does nothing unless the engine chose the track.
    fun abandon(streamId: Long) {
        if (snapshots.remove(streamId) == null) return
        synchronized(lock) {
            val eventId = store.findLatestUnresolvedForStream(streamId) ?: return
            store.deletePending(eventId)
        }
    }

    fun abandonAll() {
        for (streamId in snapshots.keys.toList()) abandon(streamId)
    }

    data class Resolution(val reward: Double, val completionRatio: Double, val restartDelta: Int)

    fun resolveOutcome(
        streamId: Long,
        progressMillis: Long,
        durationMillis: Long,
        currentRestartCount: Int
    ): Resolution? {
        val snapshot = snapshots.remove(streamId) ?: return null
        val completionRatio = RewardCalculator.completionRatio(progressMillis, durationMillis) ?: return null
        val restartDelta = (currentRestartCount - snapshot.restartCountAtStart).coerceAtLeast(0)
        val reward = RewardCalculator.fromRatio(completionRatio, restartDelta)
        synchronized(lock) {
            val eventId = store.findLatestUnresolvedForStream(streamId) ?: return Resolution(reward, completionRatio, restartDelta)
            val event = store.getPendingById(eventId) ?: return Resolution(reward, completionRatio, restartDelta)
            val outcome = RawInputsCodec.Outcome(completionRatio, restartDelta)
            val updatedRaw = try {
                RawInputsCodec.withOutcome(event.rawInputsBlob, outcome)
            } catch (t: IllegalArgumentException) {
                event.rawInputsBlob
            }
            store.resolvePending(eventId, reward, updatedRaw, clock())
            // The model is updated with the scaled vector it scored at decision time; the
            // scaler learns from the raw vector, so its statistics stay on the raw scale.
            val scaledVector = RawInputsCodec.decodeFeatureVector(event.featureVectorBlob)
            if (scaledVector.size == linucb.dimension) {
                linucb.update(scaledVector, reward)
                rawVectorFor(event.rawInputsBlob)?.let { scaler.observe(it) }
                nEvents += 1L
                persist()
            }
        }
        return Resolution(reward, completionRatio, restartDelta)
    }

    private fun rawVectorFor(rawInputsBlob: ByteArray): DoubleArray? = try {
        val decoded = RawInputsCodec.decode(rawInputsBlob)
        FeatureExtractor.buildRawVector(decoded.context, decoded.item)
    } catch (t: RuntimeException) {
        null
    }

    // Replays every resolved decision under the current schema and reward. Where an event
    // lacks a stored outcome, the telemetry index (if given) is used to recover one and
    // the recovered outcome is written back, so the next rebuild no longer needs it.
    fun rebuildFromPending(telemetry: OutcomeRecovery.Index? = null): RebuildReport = synchronized(lock) {
        val freshLinucb = freshModel()
        val freshScaler = FeatureScaler(FeatureSchema.scaledIndices)
        var ingested = 0
        var fromStored = 0
        var fromTelemetry = 0
        var carried = 0
        var skipped = 0
        val resolved = store.getAllResolvedOrdered()
        for (e in resolved) {
            val storedReward = e.reward ?: continue
            val decoded = try {
                RawInputsCodec.decode(e.rawInputsBlob)
            } catch (t: RuntimeException) {
                skipped += 1
                continue
            }
            var outcome = decoded.outcome
            if (outcome != null) {
                fromStored += 1
            } else {
                val match = telemetry?.find(e.streamId, e.resolvedAt ?: e.chosenAt, storedReward)
                if (match != null) {
                    outcome = OutcomeRecovery.outcomeFrom(match)
                    store.updateRawInputs(e.id, RawInputsCodec.withOutcome(e.rawInputsBlob, outcome))
                    fromTelemetry += 1
                } else {
                    carried += 1
                }
            }
            val reward = outcome?.let { RewardCalculator.fromRatio(it.completionRatio, it.restartDelta) }
                ?: storedReward
            val rawV = FeatureExtractor.buildRawVector(decoded.context, decoded.item)
            freshScaler.observe(rawV)
            freshLinucb.update(freshScaler.transform(rawV), reward)
            ingested += 1
        }
        linucb = freshLinucb
        scaler = freshScaler
        nEvents = ingested.toLong()
        initialised = true
        persist()
        RebuildReport(resolved.size, ingested, fromStored, fromTelemetry, carried, skipped)
    }

    // Used by Bootstrap: a prior built from weighted synthetic examples.
    fun replaceWith(linucb: LinUCB, scaler: FeatureScaler, nEvents: Long) {
        synchronized(lock) {
            this.linucb = linucb
            this.scaler = scaler
            this.nEvents = nEvents
            initialised = true
            persist()
        }
    }

    fun newModel(): LinUCB = freshModel()

    private fun persist() {
        store.saveModel(linucb.toByteArray(), scaler.toByteArray(), nEvents, FeatureSchema.schemaHash, clock())
    }

    fun streamTitle(streamId: Long): String? = store.getStreamTitle(streamId)

    fun theta(): DoubleArray = synchronized(lock) { linucb.theta() }
    fun numEvents(): Long = synchronized(lock) { nEvents }
    fun snapshotCount(): Int = snapshots.size

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
        val rows = store.getRecent(limit)
        return rows.map { event ->
            val greedy = try {
                RawInputsCodec.decode(event.rawInputsBlob).greedy
            } catch (t: RuntimeException) {
                null
            }
            RecentDecision(
                chosenAt = event.chosenAt,
                streamId = event.streamId,
                streamTitle = store.getStreamTitle(event.streamId),
                predictedReward = event.predictedReward,
                uncertainty = event.uncertainty,
                reward = event.reward,
                // Older events lack the greedy flag; fall back to the v1 heuristic.
                exploit = greedy ?: (event.predictedReward > event.uncertainty)
            )
        }
    }

    // Computed over decisions that actually played (resolved ones). Enough rows are read
    // to cover both the decision window and the session window.
    fun repetitionMetrics(rowsToRead: Int = 500): RepetitionMetrics.Report {
        val rows = store.getRecentResolved(rowsToRead)
        val served = rows.map { e ->
            val uploader = try {
                RawInputsCodec.decode(e.rawInputsBlob).item.uploader
            } catch (t: RuntimeException) {
                null
            }
            RepetitionMetrics.Served(e.chosenAt, e.streamId, uploader)
        }
        return RepetitionMetrics.compute(served)
    }
}
