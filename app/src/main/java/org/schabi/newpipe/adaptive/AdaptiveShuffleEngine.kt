/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
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
    private var model: HybridLinUCB = freshModel()
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
        val score: HybridLinUCB.Score,
        val pendingEventId: Long,
        // True when the pick is also the candidate with the highest mean prediction, i.e.
        // the model chose what it already believed best. False means exploration chose it.
        val greedy: Boolean,
        val chooser: Chooser = Chooser.MODEL
    ) {
        // Drawn uniformly at random rather than by the model (spec section 7.5).
        val baseline: Boolean get() = chooser == Chooser.BASELINE
    }

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

    private fun freshModel(): HybridLinUCB = HybridLinUCB(FeatureSchema.DIMENSION, discount = discount)

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
        model = try {
            HybridLinUCB.fromByteArray(loaded.linucbBlob, discount = discount)
        } catch (t: RuntimeException) {
            return@synchronized InitialiseResult.SchemaMismatch
        }
        scaler = FeatureScaler.fromByteArray(loaded.scalerBlob)
        nEvents = loaded.nEvents
        initialised = true
        InitialiseResult.Loaded
    }

    fun chooseNext(
        candidates: List<CandidateItem>,
        context: DecisionContext,
        mode: SelectionMode = SelectionMode.UCB,
        baseline: Boolean = false
    ): DecisionResult {
        require(candidates.isNotEmpty()) { "candidates must not be empty" }
        return synchronized(lock) {
            val scaledVectors = Array(candidates.size) { i ->
                scaler.transform(FeatureExtractor.buildRawVector(context, candidates[i]))
            }
            val scores = Array(candidates.size) { i ->
                model.score(scaledVectors[i], candidates[i].streamId, FeatureExtractor.trackFeatures(scaledVectors[i]))
            }
            val greedyIdx = scores.indices.maxByOrNull { scores[it].predictedReward } ?: 0
            // A baseline pick is still scored, so its prediction can be compared with its
            // outcome, and still trains the model: a uniform pick is unbiased evidence.
            val chosenIdx = if (baseline) {
                rng.nextInt(candidates.size)
            } else {
                when (mode) {
                    SelectionMode.UCB -> scores.indices.maxByOrNull { scores[it].total } ?: 0

                    SelectionMode.THOMPSON -> {
                        val draw = model.sample(rng)
                        scaledVectors.indices.maxByOrNull {
                            draw.value(scaledVectors[it], candidates[it].streamId, FeatureExtractor.trackFeatures(scaledVectors[it]))
                        } ?: 0
                    }
                }
            }
            val chosen = candidates[chosenIdx]
            val greedy = chosenIdx == greedyIdx
            val chooser = if (baseline) Chooser.BASELINE else Chooser.MODEL
            val event = AdaptiveShufflePendingEventEntity(
                streamId = chosen.streamId,
                chosenAt = clock(),
                featureVectorBlob = RawInputsCodec.encodeFeatureVector(scaledVectors[chosenIdx]),
                rawInputsBlob = RawInputsCodec.encode(context, chosen, greedy = greedy, chooser = chooser),
                predictedReward = scores[chosenIdx].predictedReward,
                uncertainty = scores[chosenIdx].uncertainty,
                candidateCount = candidates.size,
                candidateStreamIdsBlob = RawInputsCodec.encodeStreamIds(candidates.map { it.streamId })
            )
            val eventId = store.insertPending(event)
            DecisionResult(chosen, scores[chosenIdx], eventId, greedy, chooser)
        }
    }

    // How the model scores each candidate now, without deciding anything or recording it.
    fun predict(candidates: List<CandidateItem>, context: DecisionContext): List<HybridLinUCB.Score> = synchronized(lock) {
        candidates.map { c ->
            val x = scaler.transform(FeatureExtractor.buildRawVector(context, c))
            model.score(x, c.streamId, FeatureExtractor.trackFeatures(x))
        }
    }

    // A track's score taken apart (spec section 7.6): each shared feature's part, weight
    // times the track's scaled value (for a scaled feature, 0 is the average track, so the
    // part is how far this track's value moves it), then the track's own offset and own
    // recovery. They add up to predicted.
    data class Breakdown(
        val features: List<Pair<String, Double>>,
        val ownOffset: Double,
        val ownRecovery: Double,
        val daysSincePlayed: Double?,
        val predicted: Double,
        val uncertainty: Double
    )

    fun explain(candidate: CandidateItem, context: DecisionContext): Breakdown = synchronized(lock) {
        val x = scaler.transform(FeatureExtractor.buildRawVector(context, candidate))
        val u = FeatureExtractor.trackFeatures(x)
        val theta = model.theta()
        val phi = model.coefficients(candidate.streamId)
        val score = model.score(x, candidate.streamId, u)
        Breakdown(
            features = FeatureSchema.names.indices.map { FeatureSchema.names[it] to theta[it] * x[it] },
            ownOffset = phi[0] * u[0],
            ownRecovery = phi[1] * u[1],
            daysSincePlayed = candidate.daysSinceLastAccess,
            predicted = score.predictedReward,
            uncertainty = score.uncertainty
        )
    }

    // How much each feature's scaled value typically varies, over recent decisions as the
    // scaler now sees them. A weight times this is the effect of a typical difference in
    // that feature, which compares across features where raw weights do not.
    fun featureSpread(rowsToRead: Int = 500): DoubleArray {
        val rows = store.getRecentResolved(rowsToRead)
        val vectors = synchronized(lock) {
            rows.mapNotNull { e -> rawVectorFor(e.rawInputsBlob)?.let { scaler.transform(it) } }
        }
        val d = FeatureSchema.DIMENSION
        if (vectors.size < 2) return DoubleArray(d)
        return DoubleArray(d) { i ->
            val mean = vectors.sumOf { it[i] } / vectors.size
            kotlin.math.sqrt(vectors.sumOf { (it[i] - mean) * (it[i] - mean) } / (vectors.size - 1))
        }
    }

    data class Snapshot(
        val theta: DoubleArray,
        val offsets: List<HybridLinUCB.TrackOffset>,
        val scaler: List<FeatureScaler.Summary>,
        val nEvents: Long,
        val updates: Long
    )

    // The model's state, for export.
    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(model.theta(), model.offsets(), scaler.summary(), nEvents, model.numUpdates())
    }

    // The listener chose this track themselves (a tap in the queue, or "play next"). It
    // is recorded like a decision, so its listen trains the model at reduced weight, and
    // if it displaced one of the model's picks, the preference between the two is learnt
    // too once the listen shows the choice was meant (spec section 6.7).
    fun recordUserPick(chosen: CandidateItem, context: DecisionContext, displaced: CandidateItem?): DecisionResult = synchronized(lock) {
        val scaled = scaler.transform(FeatureExtractor.buildRawVector(context, chosen))
        val score = model.score(scaled, chosen.streamId, FeatureExtractor.trackFeatures(scaled))
        val event = AdaptiveShufflePendingEventEntity(
            streamId = chosen.streamId,
            chosenAt = clock(),
            featureVectorBlob = RawInputsCodec.encodeFeatureVector(scaled),
            rawInputsBlob = RawInputsCodec.encode(
                context,
                chosen,
                greedy = null,
                chooser = Chooser.USER,
                rejected = displaced?.takeIf { it.streamId != chosen.streamId }
            ),
            predictedReward = score.predictedReward,
            uncertainty = score.uncertainty,
            candidateCount = 1,
            candidateStreamIdsBlob = RawInputsCodec.encodeStreamIds(listOf(chosen.streamId))
        )
        DecisionResult(chosen, score, store.insertPending(event), greedy = false, chooser = Chooser.USER)
    }

    // The candidate a pending decision chose, as it was scored.
    fun decisionItem(eventId: Long): CandidateItem? = synchronized(lock) {
        val event = store.getPendingById(eventId) ?: return@synchronized null
        try {
            RawInputsCodec.decode(event.rawInputsBlob).item
        } catch (t: RuntimeException) {
            null
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

    // Position-only form, for callers without a listen trace.
    fun resolveOutcome(
        streamId: Long,
        progressMillis: Long,
        durationMillis: Long,
        currentRestartCount: Int
    ): Resolution? {
        val snapshot = snapshots[streamId] ?: return null
        val completionRatio = RewardCalculator.completionRatio(progressMillis, durationMillis) ?: return null
        val restartDelta = (currentRestartCount - snapshot.restartCountAtStart).coerceAtLeast(0)
        return resolveOutcome(streamId, RawInputsCodec.Outcome(completionRatio, restartDelta))
    }

    fun resolveOutcome(streamId: Long, outcome: RawInputsCodec.Outcome): Resolution? {
        snapshots.remove(streamId) ?: return null
        val reward = RewardCalculator.fromOutcome(outcome)
        val resolution = Resolution(reward, outcome.completionRatio, outcome.restartDelta)
        synchronized(lock) {
            val eventId = store.findLatestUnresolvedForStream(streamId) ?: return resolution
            val event = store.getPendingById(eventId) ?: return resolution
            val updatedRaw = try {
                RawInputsCodec.withOutcome(event.rawInputsBlob, outcome)
            } catch (t: IllegalArgumentException) {
                event.rawInputsBlob
            }
            store.resolvePending(eventId, reward, updatedRaw, clock())
            val decoded = try {
                RawInputsCodec.decode(event.rawInputsBlob)
            } catch (t: RuntimeException) {
                null
            }
            // The model is updated with the scaled vector it scored at decision time; the
            // scaler learns from the raw vector, so its statistics stay on the raw scale.
            val scaledVector = RawInputsCodec.decodeFeatureVector(event.featureVectorBlob)
            if (scaledVector.size == model.dimension) {
                val repeats = store.countResolvedForStreamBetween(
                    streamId,
                    event.chosenAt - AdaptiveShuffleConfig.REPEAT_WINDOW_MILLIS,
                    event.chosenAt
                )
                model.update(
                    scaledVector,
                    reward,
                    streamId,
                    FeatureExtractor.trackFeatures(scaledVector),
                    evidenceWeight(decoded?.chooser ?: Chooser.MODEL, repeats)
                )
                decoded?.let { scaler.observe(FeatureExtractor.buildRawVector(it.context, it.item)) }
                decoded?.let { applyPreference(model, scaler, it, outcome) }
                nEvents += 1L
                persist()
            }
        }
        return resolution
    }

    // How much one resolved decision counts (spec section 6.7).
    private fun evidenceWeight(chooser: Chooser, earlierListensInWindow: Int): Double {
        val base = if (chooser == Chooser.USER) AdaptiveShuffleConfig.USER_PICK_WEIGHT else 1.0
        return base / (1 + earlierListensInWindow.coerceAtLeast(0))
    }

    // A user pick that displaced a model pick says the listener preferred one to the
    // other in the same moment. In a linear model the predicted difference is the weights
    // applied to the difference of the two feature vectors; the context cancels in it, so
    // only what differs between the tracks is learnt. Applied only once the chosen track
    // was given a fair hearing: tapping a track and skipping it at once was not a choice.
    private fun applyPreference(model: HybridLinUCB, scaler: FeatureScaler, d: RawInputsCodec.Decoded, outcome: RawInputsCodec.Outcome) {
        val other = d.rejected ?: return
        if (d.chooser != Chooser.USER || outcome.completionRatio < AdaptiveShuffleConfig.EARLY_SKIP_BELOW) return
        val a = scaler.transform(FeatureExtractor.buildRawVector(d.context, d.item))
        val b = scaler.transform(FeatureExtractor.buildRawVector(d.context, other))
        // Clipped: the displaced track was never listened to, so the scaler may not have
        // seen values like its own, and an unclipped difference of hundreds of standard
        // deviations would pin the weight in a single update.
        val clip = AdaptiveShuffleConfig.PAIRWISE_CLIP
        val diff = DoubleArray(a.size) { (a[it] - b[it]).coerceIn(-clip, clip) }
        if (diff.all { abs(it) < 1e-12 }) return
        model.update(diff, AdaptiveShuffleConfig.PAIRWISE_MARGIN, null, weight = AdaptiveShuffleConfig.PAIRWISE_WEIGHT)
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
        val freshModel = freshModel()
        val freshScaler = FeatureScaler(FeatureSchema.scaledIndices)
        var ingested = 0
        var fromStored = 0
        var fromTelemetry = 0
        var carried = 0
        var skipped = 0
        val resolved = store.getAllResolvedOrdered()
        val timesByStream = resolved.groupBy { it.streamId }.mapValues { (_, es) -> es.map { it.chosenAt }.sorted() }
        for (e in resolved) {
            val storedReward = e.reward ?: continue
            val decoded = try {
                RawInputsCodec.decode(e.rawInputsBlob)
            } catch (t: RuntimeException) {
                skipped += 1
                continue
            }
            var outcome = decoded.outcome ?: outcomeFromColumn(e, storedReward)?.also {
                // The on-device v1.1 build kept the ratio in a column; fold it into the blob.
                store.updateRawInputs(e.id, RawInputsCodec.withOutcome(e.rawInputsBlob, it))
            }
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
            val reward = outcome?.let { RewardCalculator.fromOutcome(it) } ?: storedReward
            val rawV = FeatureExtractor.buildRawVector(decoded.context, decoded.item)
            val repeats = countIn(
                timesByStream.getValue(e.streamId),
                e.chosenAt - AdaptiveShuffleConfig.REPEAT_WINDOW_MILLIS,
                e.chosenAt
            )
            freshScaler.observe(rawV)
            val x = freshScaler.transform(rawV)
            freshModel.update(x, reward, e.streamId, FeatureExtractor.trackFeatures(x), evidenceWeight(decoded.chooser, repeats))
            if (outcome != null) applyPreference(freshModel, freshScaler, decoded, outcome)
            ingested += 1
        }
        model = freshModel
        scaler = freshScaler
        nEvents = ingested.toLong()
        initialised = true
        persist()
        RebuildReport(resolved.size, ingested, fromStored, fromTelemetry, carried, skipped)
    }

    // The on-device v1.1 build stored the completion ratio in a column, and its reward was
    // this build's fromRatio, so the reward's remainder over fromRatio(ratio, 0) is the
    // rewind bonus, from which the restart delta follows.
    private fun outcomeFromColumn(e: AdaptiveShufflePendingEventEntity, storedReward: Double): RawInputsCodec.Outcome? {
        val ratio = e.completionRatio ?: return null
        val bonus = storedReward - RewardCalculator.fromRatio(ratio, 0)
        val delta = (bonus / AdaptiveShuffleConfig.REWARD_REWIND_DENSITY).roundToInt()
            .coerceIn(0, AdaptiveShuffleConfig.REWIND_CAP.toInt())
        return RawInputsCodec.Outcome(ratio, delta)
    }

    // Times sorted ascending; how many fall in [from, to).
    private fun countIn(times: List<Long>, from: Long, to: Long): Int {
        fun lowerBound(x: Long): Int {
            var lo = 0
            var hi = times.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (times[mid] < x) lo = mid + 1 else hi = mid
            }
            return lo
        }
        return lowerBound(to) - lowerBound(from)
    }

    // Used by Bootstrap: a prior built from weighted synthetic examples.
    fun replaceWith(model: HybridLinUCB, scaler: FeatureScaler, nEvents: Long) {
        synchronized(lock) {
            this.model = model
            this.scaler = scaler
            this.nEvents = nEvents
            initialised = true
            persist()
        }
    }

    fun newModel(): HybridLinUCB = freshModel()

    private fun persist() {
        store.saveModel(model.toByteArray(), scaler.toByteArray(), nEvents, FeatureSchema.schemaHash, clock())
    }

    fun streamTitle(streamId: Long): String? = store.getStreamTitle(streamId)

    fun theta(): DoubleArray = synchronized(lock) { model.theta() }

    data class TrackOffset(val streamId: Long, val title: String?, val offset: Double, val weightedListens: Double)

    // The tracks whose own listens move them furthest from what their features predict,
    // most liked and least liked, among tracks with enough listens to mean something.
    fun trackOffsets(limit: Int = 5, minListens: Double = 2.0): Pair<List<TrackOffset>, List<TrackOffset>> {
        val all = synchronized(lock) { model.offsets() }.filter { it.weightedListens >= minListens }
        fun named(o: HybridLinUCB.TrackOffset) = TrackOffset(o.trackId, store.getStreamTitle(o.trackId), o.offset, o.weightedListens)
        val liked = all.filter { it.offset > 0.0 }.sortedByDescending { it.offset }.take(limit).map(::named)
        val disliked = all.filter { it.offset < 0.0 }.sortedBy { it.offset }.take(limit).map(::named)
        return liked to disliked
    }
    fun numEvents(): Long = synchronized(lock) { nEvents }
    fun snapshotCount(): Int = snapshots.size

    data class RecentDecision(
        val chosenAt: Long,
        val streamId: Long,
        val streamTitle: String?,
        val predictedReward: Double,
        val uncertainty: Double,
        val reward: Double?,
        val exploit: Boolean,
        val chooser: Chooser = Chooser.MODEL
    ) {
        val baseline: Boolean get() = chooser == Chooser.BASELINE
    }

    fun recentDecisions(limit: Int = 20): List<RecentDecision> {
        val rows = store.getRecent(limit)
        return rows.map { event ->
            val decoded = try {
                RawInputsCodec.decode(event.rawInputsBlob)
            } catch (t: RuntimeException) {
                null
            }
            val greedy = decoded?.greedy
            RecentDecision(
                chosenAt = event.chosenAt,
                streamId = event.streamId,
                streamTitle = store.getStreamTitle(event.streamId),
                predictedReward = event.predictedReward,
                uncertainty = event.uncertainty,
                reward = event.reward,
                // Older events lack the greedy flag; fall back to the v1 heuristic.
                exploit = greedy ?: (event.predictedReward > event.uncertainty),
                chooser = decoded?.chooser ?: Chooser.MODEL
            )
        }
    }

    // Computed over decisions that actually played (resolved ones). Enough rows are read
    // to cover both the decision window and the session window.
    fun repetitionMetrics(rowsToRead: Int = 500): RepetitionMetrics.Report {
        val rows = store.getRecentResolved(rowsToRead)
        val recent = rows.sortedByDescending { it.chosenAt }.take(AdaptiveShuffleConfig.METRICS_WINDOW)
        // Candidates are looked up only for the window the shares are computed over.
        val candidates = recent.associate { e ->
            e.id to (e.candidateStreamIdsBlob?.let { b -> runCatching { RawInputsCodec.decodeStreamIds(b).toList() }.getOrNull() } ?: emptyList())
        }
        val uploaders = store.getStreamUploaders(candidates.values.flatten().toSet())
        val served = rows.map { e ->
            val uploader = try {
                RawInputsCodec.decode(e.rawInputsBlob).item.uploader
            } catch (t: RuntimeException) {
                null
            }
            val offered = candidates[e.id]?.takeIf { it.isNotEmpty() }?.map { uploaders[it] }
            RepetitionMetrics.Served(e.chosenAt, e.streamId, uploader, offered)
        }
        return RepetitionMetrics.compute(served)
    }

    // Model picks against baseline picks, over every resolved decision since the first
    // baseline pick (spec section 7.5).
    fun armComparison(): ArmComparison.Report {
        val rows = store.getAllResolvedOrdered()
        val listens = rows.mapNotNull { e ->
            val decoded = try {
                RawInputsCodec.decode(e.rawInputsBlob)
            } catch (t: RuntimeException) {
                return@mapNotNull null
            }
            val ratio = e.completionRatio ?: decoded.outcome?.completionRatio ?: return@mapNotNull null
            val reward = e.reward ?: return@mapNotNull null
            // The listener's own picks belong to neither arm.
            if (decoded.chooser == Chooser.USER) return@mapNotNull null
            ArmComparison.Listen(e.chosenAt, decoded.baseline, reward, ratio)
        }
        return ArmComparison.compute(listens)
    }
}
