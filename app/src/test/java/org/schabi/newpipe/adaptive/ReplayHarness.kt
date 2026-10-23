/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Staged replay of a decision log (spec section 7.2). Each stage is a configuration of
// the pipeline as it stood after one fix, applied cumulatively, so a regression can be
// pinned on the stage that introduced it.
//
// The log records only the track that was picked, never the candidates it beat, so two
// things are measured differently:
//   - Prediction: progressive validation. Each logged event is predicted before the
//     model learns from it, and the predictions are rank-correlated with what happened.
//   - Repetition: the stage's selection rule is re-run at each logged decision against
//     the library seen in the log so far, and the section 7.1 metrics are computed over
//     those simulated picks. The model still learns only from logged outcomes, since a
//     simulated pick has none.
internal object ReplayHarness {

    // The v2 schema's cold-start completion rate, as that build had it.
    private const val LEGACY_COLD_COMPLETION = 0.5

    const val CAVEAT = "Historical events were chosen by the old policy; replay is biased toward " +
        "configurations that resemble it. Replay filters gross regressions; it does not show live improvement."

    data class Stage(
        val label: String,
        // Fix 0, D1: use the logged queue origin. Off forces OTHER, as dev hard-coded it.
        val deriveOrigin: Boolean,
        // Fix 0, D3: the scaler observes raw vectors. Off reproduces it observing scaled ones.
        val scalerObservesRaw: Boolean,
        // Fix 1: recency window and uploader spacing before sampling.
        val candidateFilter: Boolean,
        // Fix 2: schema v3. Off uses the 27-feature v2 schema.
        val schemaV3: Boolean,
        // Fix 3: continuous reward. Off uses the v1 step reward.
        val continuousReward: Boolean,
        // Fix 4: Thompson sampling. Off uses UCB.
        val thompson: Boolean,
        val discount: Double = 1.0,
        // Spec section 6.6: per-track offsets on top of the shared weights.
        val hybrid: Boolean = false
    )

    val STAGES: List<Stage> = listOf(
        Stage("baseline (dev @ 5a9b0f0)", false, false, false, false, false, false),
        Stage("+Fix 0 (origin, raw scaler)", true, true, false, false, false, false),
        Stage("+Fix 1 (recency, spacing)", true, true, true, false, false, false),
        Stage("+Fix 2+3 (schema v3, reward)", true, true, true, true, true, false),
        Stage("+Fix 4 (Thompson)", true, true, true, true, true, true),
        // What ships by default: Thompson is behind a toggle that starts off, and the
        // evidence discount was added outside the plan.
        Stage(
            "shipped defaults (UCB, gamma ${AdaptiveShuffleConfig.DISCOUNT_GAMMA})",
            true,
            true,
            true,
            true,
            true,
            false,
            AdaptiveShuffleConfig.DISCOUNT_GAMMA
        ),
        Stage(
            "+hybrid (per-track offsets)",
            true,
            true,
            true,
            true,
            true,
            false,
            AdaptiveShuffleConfig.DISCOUNT_GAMMA,
            hybrid = true
        )
    )

    // The two model forms behind one face, so every stage replays the same way.
    private class ReplayModel(dimension: Int, discount: Double, hybrid: Boolean) {
        private val linear = if (hybrid) null else LinUCB(dimension, discount = discount)
        private val withOffsets = if (hybrid) HybridLinUCB(dimension, discount = discount) else null

        // Per-track features only exist in the current schema (the v2 one has no recovery).
        private fun u(x: DoubleArray) = if (x.size == FeatureSchema.DIMENSION) FeatureExtractor.trackFeatures(x) else null

        fun predicted(x: DoubleArray, track: Long): Double = linear?.score(x)?.predictedReward ?: withOffsets!!.score(x, track, u(x)).predictedReward

        fun total(x: DoubleArray, track: Long): Double = linear?.score(x)?.total ?: withOffsets!!.score(x, track, u(x)).total

        fun draw(rng: Random): (DoubleArray, Long) -> Double {
            linear?.let { m ->
                val theta = m.sampleTheta(rng)
                return { x, _ -> LinUCB.dot(theta, x) }
            }
            val d = withOffsets!!.sample(rng)
            return { x, track -> d.value(x, track, u(x)) }
        }

        fun update(x: DoubleArray, reward: Double, track: Long) {
            linear?.update(x, reward) ?: withOffsets!!.update(x, reward, track, u(x))
        }
    }

    data class Event(
        val chosenAt: Long,
        val context: DecisionContext,
        val item: CandidateItem,
        val completionRatio: Double,
        val restartDelta: Int
    )

    data class Report(
        val stage: Stage,
        val events: Int,
        val scoredEvents: Int,
        // Spearman between predicted reward and the reward this stage trains on.
        val spearmanReward: Double,
        // Spearman between predicted reward and completion ratio. Unlike the reward,
        // this target is the same in every stage, so this column compares across them.
        val spearmanCompletion: Double,
        val repetition: RepetitionMetrics.Report
    )

    fun run(
        events: List<Event>,
        stage: Stage,
        seed: Long = 1L,
        warmUp: Int = 50
    ): Report {
        val sorted = events.sortedBy { it.chosenAt }
        val features = if (stage.schemaV3) V3 else LegacyV2
        val model = ReplayModel(features.dimension, stage.discount, stage.hybrid)
        val scaler = FeatureScaler(features.scaledIndices)
        val rng = Random(seed)

        val predicted = ArrayList<Double>()
        val rewards = ArrayList<Double>()
        val ratios = ArrayList<Double>()

        // Library as seen in the log: latest snapshot per track, and when it last played.
        val library = LinkedHashMap<Long, CandidateItem>()
        val lastPlayedAt = HashMap<Long, Long>()
        val served = ArrayList<RepetitionMetrics.Served>()
        val recentKeys = ArrayDeque<String>()
        val recentUploaders = ArrayDeque<String?>()

        for ((i, e) in sorted.withIndex()) {
            val context = if (stage.deriveOrigin) e.context else e.context.copy(queueOrigin = QueueOrigin.OTHER)

            // Selection, simulated against the library known at this point.
            library[e.item.streamId] = e.item
            val pick = simulatePick(
                stage, features, model, scaler, context, e.chosenAt,
                library, lastPlayedAt, served, recentKeys, recentUploaders, rng
            )
            served += RepetitionMetrics.Served(e.chosenAt, pick.streamId, pick.uploader)
            recentKeys.addFirst(pick.streamId.toString())
            while (recentKeys.size > AdaptiveShuffleConfig.RECENCY_EXCLUSION_MAX) recentKeys.removeLast()
            recentUploaders.addFirst(pick.uploader)
            while (recentUploaders.size > AdaptiveShuffleConfig.UPLOADER_SPACING) recentUploaders.removeLast()

            // Prediction, then learning, from the logged event.
            val raw = features.raw(context, e.item)
            val x = scaler.transform(raw)
            val reward = if (stage.continuousReward) {
                RewardCalculator.fromRatio(e.completionRatio, e.restartDelta)
            } else {
                RewardCalculator.legacyStepReward(e.completionRatio, e.restartDelta)
            }
            if (i >= warmUp) {
                predicted += model.predicted(x, e.item.streamId)
                rewards += reward
                ratios += e.completionRatio
            }
            model.update(x, reward, e.item.streamId)
            scaler.observe(if (stage.scalerObservesRaw) raw else x)
            lastPlayedAt[e.item.streamId] = e.chosenAt
        }

        val p = predicted.toDoubleArray()
        return Report(
            stage = stage,
            events = sorted.size,
            scoredEvents = p.size,
            spearmanReward = if (p.size > 1) spearman(p, rewards.toDoubleArray()) else Double.NaN,
            spearmanCompletion = if (p.size > 1) spearman(p, ratios.toDoubleArray()) else Double.NaN,
            repetition = RepetitionMetrics.compute(served)
        )
    }

    private fun simulatePick(
        stage: Stage,
        features: FeatureSet,
        model: ReplayModel,
        scaler: FeatureScaler,
        loggedContext: DecisionContext,
        now: Long,
        library: Map<Long, CandidateItem>,
        lastPlayedAt: Map<Long, Long>,
        served: List<RepetitionMetrics.Served>,
        recentKeys: List<String>,
        recentUploaders: List<String?>,
        rng: Random
    ): CandidateItem {
        val items = library.values.toList()
        val entries = items.mapIndexed { idx, it -> CandidateFilter.TailEntry(idx, it.streamId.toString(), it.uploader) }
        val eligible = if (stage.candidateFilter) {
            CandidateFilter.filter(entries, recentKeys, recentUploaders).eligible
        } else {
            entries
        }
        val sampled = CandidateFilter.sample(eligible, AdaptiveShuffleConfig.CANDIDATE_SAMPLE_CAP, rng)

        // The previous track is the simulated one, not the logged one.
        val previous = served.lastOrNull()
        val context = loggedContext.copy(previousUploader = previous?.uploader, previousStreamId = previous?.streamId)
        val candidates = sampled.map { entry ->
            val item = items[entry.tailIndex]
            val last = lastPlayedAt[item.streamId]
            item.copy(
                daysSinceLastAccess = if (last != null) (now - last) / DAY_MILLIS else item.daysSinceLastAccess,
                // Relative to a previous track the simulation did not play; unknown here.
                playlistCoMembershipWithPrev = 0
            )
        }
        val vectors = candidates.map { scaler.transform(features.raw(context, it)) }
        val chosen = if (stage.thompson) {
            val draw = model.draw(rng)
            vectors.indices.maxByOrNull { draw(vectors[it], candidates[it].streamId) }!!
        } else {
            vectors.indices.maxByOrNull { model.total(vectors[it], candidates[it].streamId) }!!
        }
        return candidates[chosen]
    }

    fun format(reports: List<Report>): String {
        val sb = StringBuilder()
        sb.append("Adaptive shuffle staged replay (spec section 7.2)\n")
        sb.append("CAVEAT: ").append(CAVEAT).append('\n')
        reports.firstOrNull()?.let {
            sb.append("events: ${it.events}, scored after warm-up: ${it.scoredEvents}\n")
        }
        sb.append(
            String.format(
                "%-36s %8s %8s %7s %8s %8s  %s\n",
                "stage",
                "rho(rew)",
                "rho(cr)",
                "uniq100",
                "topUpl",
                "topTrk",
                "top-5 session recurrence"
            )
        )
        for (r in reports) {
            val m = r.repetition
            val recurrence = m.recurringTracks.joinToString(" ") { String.format("%.2f", it.sessionShare) }
            sb.append(
                String.format(
                    "%-36s %8.3f %8.3f %7d %8.3f %8.3f  %s\n",
                    r.stage.label,
                    r.spearmanReward,
                    r.spearmanCompletion,
                    m.uniqueTracks,
                    m.topUploaderShare,
                    m.topTrackShare,
                    recurrence
                )
            )
        }
        sb.append("rho(rew) uses each stage's own reward, so only rho(cr) compares across stages.\n")
        sb.append("Repetition columns are over ${reports.firstOrNull()?.repetition?.windowSize ?: 0} simulated picks; ")
        sb.append("recurrence is over the last ${reports.firstOrNull()?.repetition?.sessionsConsidered ?: 0} sessions.\n")
        sb.append("CAVEAT: ").append(CAVEAT).append('\n')
        return sb.toString()
    }

    // Reads an export of resolved decisions, one per line, tab separated:
    //   chosen_at  stream_id  reward  hex(raw_inputs_blob)
    // which is what this produces from a NewPipe database export:
    //   sqlite3 -separator $'\t' newpipe.db "SELECT chosen_at, stream_id, reward,
    //     hex(raw_inputs_blob), completion_ratio FROM adaptive_shuffle_pending_events
    //     WHERE resolved = 1 ORDER BY chosen_at" > events.tsv
    // Only events with a stored outcome can be replayed under every reward, so run
    // "Rebuild from history" on the device first: it writes recovered outcomes back.
    data class Loaded(val events: List<Event>, val withoutOutcome: Int, val undecodable: Int)

    fun load(file: File): Loaded {
        val events = ArrayList<Event>()
        var withoutOutcome = 0
        var undecodable = 0
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val cols = line.trim().split('\t')
            if (cols.size < 4) {
                undecodable += 1
                return@forEachLine
            }
            val decoded = try {
                RawInputsCodec.decode(hexToBytes(cols[3]))
            } catch (t: RuntimeException) {
                undecodable += 1
                return@forEachLine
            }
            // An optional fifth column holds the completion_ratio column the on-device
            // v1.1 build wrote instead of a stored outcome; the reward's remainder over
            // fromRatio(ratio, 0) gives the restart delta, as the engine's rebuild does.
            val outcome = decoded.outcome ?: cols.getOrNull(4)?.toDoubleOrNull()?.let { ratio ->
                val reward = cols[2].toDoubleOrNull() ?: return@let null
                val delta = ((reward - RewardCalculator.fromRatio(ratio, 0)) / AdaptiveShuffleConfig.REWARD_REWIND_DENSITY)
                    .roundToInt().coerceIn(0, AdaptiveShuffleConfig.REWIND_CAP.toInt())
                RawInputsCodec.Outcome(ratio, delta)
            }
            if (outcome == null) {
                withoutOutcome += 1
                return@forEachLine
            }
            events += Event(cols[0].toLong(), decoded.context, decoded.item, outcome.completionRatio, outcome.restartDelta)
        }
        return Loaded(events, withoutOutcome, undecodable)
    }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    private const val DAY_MILLIS = 86_400_000.0

    interface FeatureSet {
        val dimension: Int
        val scaledIndices: IntArray
        fun raw(context: DecisionContext, item: CandidateItem): DoubleArray
    }

    private object V3 : FeatureSet {
        override val dimension = FeatureSchema.DIMENSION
        override val scaledIndices = FeatureSchema.scaledIndices
        override fun raw(context: DecisionContext, item: CandidateItem) = FeatureExtractor.buildRawVector(context, item)
    }

    // The 27-feature v2 extractor as of dev @ 5a9b0f0, kept so the baseline replays the
    // schema it actually ran. Do not update it to follow the live schema.
    object LegacyV2 : FeatureSet {
        override val dimension = 27
        override val scaledIndices = intArrayOf(4, 12, 14, 17)

        override fun raw(context: DecisionContext, item: CandidateItem): DoubleArray {
            val v = DoubleArray(dimension)
            val hourRadians = 2.0 * PI * context.hourOfDay / 24.0
            v[0] = sin(hourRadians)
            v[1] = cos(hourRadians)
            val dowRadians = 2.0 * PI * context.dayOfWeek / 7.0
            v[2] = sin(dowRadians)
            v[3] = cos(dowRadians)
            v[4] = ln(1.0 + context.sessionPosition.coerceAtLeast(0))
            v[5] = context.recentSkipRate
            v[6] = if (context.previousAutoQueued) 1.0 else 0.0
            v[7] = if (context.previousCompleted) 1.0 else 0.0
            v[8] = if (context.queueOrigin == QueueOrigin.SINGLE) 1.0 else 0.0
            v[9] = if (context.queueOrigin == QueueOrigin.PLAYLIST) 1.0 else 0.0
            v[10] = if (context.queueOrigin == QueueOrigin.CHANNEL) 1.0 else 0.0
            v[11] = if (context.queueOrigin == QueueOrigin.OTHER) 1.0 else 0.0
            v[12] = ln(1.0 + item.durationSeconds.coerceAtLeast(0L))
            v[13] = item.userRating?.let { (it - 3.0) / 2.0 } ?: 0.0
            v[14] = ln(1.0 + item.lifetimePlayCount.coerceAtLeast(0))
            val plays = item.lifetimePlayCount
            if (plays < AdaptiveShuffleConfig.COLD_START_MIN_PLAYS) {
                v[15] = AdaptiveShuffleConfig.COLD_START_SKIP_RATIO
                v[16] = LEGACY_COLD_COMPLETION
            } else {
                v[15] = item.lifetimeEarlySkips / plays.toDouble()
                v[16] = item.lifetimeCompletions / plays.toDouble()
            }
            val days = item.daysSinceLastAccess ?: AdaptiveShuffleConfig.NEVER_PLAYED_DAYS_DEFAULT
            v[17] = ln(1.0 + days.coerceAtLeast(0.0))
            val isAudio = item.streamType == StreamTypeFeature.AUDIO || item.streamType == StreamTypeFeature.LIVE_AUDIO
            val isLive = item.streamType == StreamTypeFeature.LIVE_VIDEO || item.streamType == StreamTypeFeature.LIVE_AUDIO
            v[18] = if (isAudio) 1.0 else 0.0
            v[19] = if (isLive) 1.0 else 0.0
            val prev = context.previousUploader
            v[20] = if (prev != null && item.uploader != null && prev == item.uploader) 1.0 else 0.0
            v[21] = item.uploaderAvgRating?.let { ((it - 3.0) / 2.0).coerceIn(-1.0, 1.0) } ?: 0.0
            v[22] = item.uploaderCompletionRate ?: LEGACY_COLD_COMPLETION
            v[23] = item.uploaderSkipRate ?: AdaptiveShuffleConfig.COLD_START_SKIP_RATIO
            v[24] = ln(1.0 + item.playlistCoMembershipWithPrev.coerceAtLeast(0))
            v[25] = if (item.isDownloadedOffline) 1.0 else 0.0
            v[26] = 1.0
            return v
        }
    }

    fun spearman(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size && a.isNotEmpty())
        return pearson(ranks(a), ranks(b))
    }

    private fun ranks(xs: DoubleArray): DoubleArray {
        val n = xs.size
        val order = (0 until n).sortedBy { xs[it] }
        val out = DoubleArray(n)
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && xs[order[j + 1]] == xs[order[i]]) j += 1
            val avg = (i + j) / 2.0 + 1.0
            for (k in i..j) out[order[k]] = avg
            i = j + 1
        }
        return out
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            val ax = a[i] - ma
            val bx = b[i] - mb
            num += ax * bx
            da += ax * ax
            db += bx * bx
        }
        val denom = sqrt(da * db)
        return if (denom == 0.0) 0.0 else num / denom
    }
}
