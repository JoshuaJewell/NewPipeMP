/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Everything needed to study the model off the device (spec section 7.6): its parameters,
// every decision with the features it was scored on and how it turned out, and the
// tracks involved. Tab-separated tables and one JSON file, in a zip.
object AdaptiveShuffleExport {

    fun write(engine: AdaptiveShuffleEngine, store: AdaptiveShuffleStore, out: OutputStream, nowMillis: Long) {
        val snapshot = engine.snapshot()
        val events = store.getAllEventsOrdered()
        val decoded = events.associate { e ->
            e.id to runCatching { RawInputsCodec.decode(e.rawInputsBlob) }.getOrNull()
        }
        val candidates = events.associate { e ->
            e.id to (e.candidateStreamIdsBlob?.let { runCatching { RawInputsCodec.decodeStreamIds(it).toList() }.getOrNull() } ?: emptyList())
        }
        val trackIds = HashSet<Long>()
        events.forEach { trackIds += it.streamId }
        candidates.values.forEach { trackIds += it }
        decoded.values.forEach { d -> d?.rejected?.let { trackIds += it.streamId } }
        snapshot.offsets.forEach { trackIds += it.trackId }
        val tracks = store.getTracks(trackIds).associateBy { it.streamId }

        ZipOutputStream(out).use { zip ->
            fun entry(name: String, body: (Appendable) -> Unit) {
                zip.putNextEntry(ZipEntry(name))
                val w = OutputStreamWriter(zip, StandardCharsets.UTF_8)
                body(w)
                w.flush()
                zip.closeEntry()
            }
            entry("README.txt") { it.append(README) }
            entry("model.json") { writeModel(it, snapshot, engine, nowMillis) }
            entry("events.tsv") { writeEvents(it, events, decoded, candidates) }
            entry("offsets.tsv") { w ->
                w.append("stream_id\toffset\tweighted_listens\ttitle\tuploader\n")
                for (o in snapshot.offsets.sortedByDescending { it.offset }) {
                    val t = tracks[o.trackId]
                    w.append("${o.trackId}\t${o.offset}\t${o.weightedListens}\t${clean(t?.title)}\t${clean(t?.uploader)}\n")
                }
            }
            entry("tracks.tsv") { w ->
                w.append("stream_id\tservice_id\turl\ttitle\tuploader\tduration_s\tuser_rating\n")
                for (t in tracks.values.sortedBy { it.streamId }) {
                    w.append("${t.streamId}\t${t.serviceId}\t${clean(t.url)}\t${clean(t.title)}\t${clean(t.uploader)}\t${t.durationSeconds}\t${t.userRating ?: ""}\n")
                }
            }
        }
    }

    private fun writeModel(w: Appendable, s: AdaptiveShuffleEngine.Snapshot, engine: AdaptiveShuffleEngine, nowMillis: Long) {
        val report = engine.armComparison()
        w.append("{\n")
        w.append("  \"exported_at_ms\": $nowMillis,\n")
        w.append("  \"schema_hash\": ${json(FeatureSchema.schemaHash)},\n")
        w.append("  \"model_form\": ${json(FeatureSchema.MODEL_FORM)},\n")
        w.append("  \"events_ingested\": ${s.nEvents},\n")
        w.append("  \"model_updates\": ${s.updates},\n")
        w.append("  \"config\": {\n")
        val config = listOf(
            "ridge_lambda" to AdaptiveShuffleConfig.RIDGE_LAMBDA,
            "track_offset_lambda" to AdaptiveShuffleConfig.TRACK_OFFSET_LAMBDA,
            "exploration_alpha" to AdaptiveShuffleConfig.EXPLORATION_ALPHA,
            "discount_gamma" to AdaptiveShuffleConfig.DISCOUNT_GAMMA,
            "reward_slope" to AdaptiveShuffleConfig.REWARD_SLOPE,
            "reward_offset" to AdaptiveShuffleConfig.REWARD_OFFSET,
            "reward_early_skip_extra" to AdaptiveShuffleConfig.REWARD_EARLY_SKIP_EXTRA,
            "reward_rewind_density" to AdaptiveShuffleConfig.REWARD_REWIND_DENSITY,
            "user_pick_weight" to AdaptiveShuffleConfig.USER_PICK_WEIGHT,
            "pairwise_weight" to AdaptiveShuffleConfig.PAIRWISE_WEIGHT,
            "pairwise_margin" to AdaptiveShuffleConfig.PAIRWISE_MARGIN,
            "candidate_sample_cap" to AdaptiveShuffleConfig.CANDIDATE_SAMPLE_CAP.toDouble()
        )
        w.append(config.joinToString(",\n") { (k, v) -> "    ${json(k)}: $v" }).append("\n  },\n")
        w.append("  \"features\": [\n")
        val scaled = s.scaler.associateBy { it.index }
        w.append(
            FeatureSchema.names.indices.joinToString(",\n") { i ->
                val sc = scaled[i]
                val scaling = if (sc == null) "null" else "{\"count\": ${sc.count}, \"mean\": ${sc.mean}, \"std\": ${sc.std}}"
                "    {\"index\": $i, \"name\": ${json(FeatureSchema.names[i])}, \"weight\": ${s.theta[i]}, \"scaling\": $scaling}"
            }
        ).append("\n  ],\n")
        fun arm(a: ArmComparison.Arm?): String = if (a == null) {
            "null"
        } else {
            "{\"listens\": ${a.listens}, \"mean_reward\": ${a.meanReward}, \"mean_completion\": ${a.meanCompletion}, " +
                "\"completed_share\": ${a.completedShare}, \"early_skip_share\": ${a.earlySkipShare}, \"reward_variance\": ${a.rewardVariance}}"
        }
        w.append("  \"comparison\": {\"since_ms\": ${report.since ?: "null"}, \"model\": ${arm(report.model)}, ")
        w.append("\"random\": ${arm(report.baseline)}, \"lift\": ${report.rewardLift ?: "null"}, ")
        w.append("\"lift_half_width\": ${report.rewardLiftHalfWidth ?: "null"}, \"verdict\": ${json(report.verdict.name)}}\n")
        w.append("}\n")
    }

    private fun writeEvents(
        w: Appendable,
        events: List<org.schabi.newpipe.database.adaptive.AdaptiveShufflePendingEventEntity>,
        decoded: Map<Long, RawInputsCodec.Decoded?>,
        candidates: Map<Long, List<Long>>
    ) {
        w.append("id\tstream_id\tchosen_at_ms\tresolved\tresolved_at_ms\tchooser\tgreedy\tpredicted\tuncertainty\treward\t")
        w.append("completion_ratio\trestarts\tpartial_rewinds\treturned\tcandidate_count\tdisplaced_stream_id\tcandidate_ids\t")
        w.append(FeatureSchema.names.joinToString("\t") { "x_$it" }).append('\n')
        for (e in events) {
            val d = decoded[e.id]
            val o = d?.outcome
            val raw = d?.let { runCatching { FeatureExtractor.buildRawVector(it.context, it.item) }.getOrNull() }
            w.append("${e.id}\t${e.streamId}\t${e.chosenAt}\t${if (e.resolved) 1 else 0}\t${e.resolvedAt ?: ""}\t")
            w.append("${d?.chooser?.name?.lowercase() ?: ""}\t${d?.greedy?.let { if (it) 1 else 0 } ?: ""}\t")
            w.append("${e.predictedReward}\t${e.uncertainty}\t${e.reward ?: ""}\t")
            w.append("${o?.completionRatio ?: e.completionRatio ?: ""}\t${o?.restartDelta ?: ""}\t${o?.partialRewinds ?: ""}\t")
            w.append("${o?.returned?.let { if (it) 1 else 0 } ?: ""}\t${e.candidateCount}\t${d?.rejected?.streamId ?: ""}\t")
            w.append(candidates[e.id].orEmpty().joinToString(","))
            for (i in FeatureSchema.names.indices) w.append('\t').append(raw?.get(i)?.toString() ?: "")
            w.append('\n')
        }
    }

    private fun clean(s: String?): String = s?.replace('\t', ' ')?.replace('\n', ' ')?.replace('\r', ' ') ?: ""

    private fun json(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private val README = """
        Adaptive shuffle export (NewPipeMP)

        model.json   the model as it stands: per feature its learnt weight (in scaled units) and,
                     for scaled features, the running count, mean and standard deviation used to
                     scale it (scaled = clip((raw - mean) / std, -5, 5)); the configuration; and the
                     model-against-random comparison.
        events.tsv   every decision, oldest first, resolved or not.
                     chooser: model, baseline (uniform random among the candidates) or user.
                     greedy: 1 if the pick was also the highest mean prediction.
                     predicted, uncertainty: the score at decision time.
                     reward: under the reward in use when it resolved; recompute from
                     completion_ratio, restarts, partial_rewinds and returned for any other.
                     candidate_ids: the tracks the pick was made from (for a baseline pick, each
                     was equally likely, which is what makes those rows usable to evaluate any
                     other policy offline).
                     x_*: the raw (unscaled) features of the chosen track at decision time.
        offsets.tsv  each track's own learnt offset above or below what its features predict,
                     with the weighted number of listens behind it.
        tracks.tsv   every track named in the other files.

        Times are milliseconds since 1970 (UTC). Empty cells are unknown values.
    """.trimIndent() + "\n"
}
