/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import kotlin.math.abs

// Recovers completion ratios for decisions made before RawInputsCodec v3 stored them.
// Pending events from that era hold only the scalar step reward; the telemetry log holds
// the completion ratio for each resolution. Joining the two by stream and time, then
// inverting the step reward, gives back the restart delta, so the decision can be
// re-scored under the continuous reward (spec section 6.4).
object OutcomeRecovery {

    data class TelemetryResolution(
        val streamId: Long,
        val timestamp: Long,
        val completionRatio: Double,
        val reward: Double
    )

    private val streamIdPattern = Regex("\"stream_id\":(-?\\d+)")
    private val timestampPattern = Regex("\"timestamp\":(-?\\d+)")
    private val ratioPattern = Regex("\"completion_ratio\":(-?[0-9.eE+-]+)")
    private val rewardPattern = Regex("\"reward\":(-?[0-9.eE+-]+)")

    // Tolerant line parser: anything that is not a well-formed resolution line is skipped.
    fun parseResolutions(lines: Sequence<String>): List<TelemetryResolution> = lines.mapNotNull { line ->
        if (!line.contains("\"event\":\"resolution\"")) return@mapNotNull null
        val id = streamIdPattern.find(line)?.groupValues?.get(1)?.toLongOrNull()
        val ts = timestampPattern.find(line)?.groupValues?.get(1)?.toLongOrNull()
        val ratio = ratioPattern.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
        val reward = rewardPattern.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
        if (id == null || ts == null || ratio == null || reward == null || ratio.isNaN()) {
            null
        } else {
            TelemetryResolution(id, ts, ratio, reward)
        }
    }.toList()

    class Index(resolutions: List<TelemetryResolution>) {
        private val byStream: Map<Long, List<TelemetryResolution>> =
            resolutions.groupBy { it.streamId }.mapValues { (_, v) -> v.sortedBy { it.timestamp } }

        // Nearest telemetry line for this stream within the tolerance whose logged reward
        // agrees with the stored one. The reward check guards against pairing an event
        // with a different play of the same track.
        fun find(
            streamId: Long,
            resolvedAt: Long,
            storedReward: Double,
            toleranceMillis: Long = AdaptiveShuffleConfig.TELEMETRY_MATCH_TOLERANCE_MILLIS
        ): TelemetryResolution? {
            val candidates = byStream[streamId] ?: return null
            return candidates
                .filter { abs(it.timestamp - resolvedAt) <= toleranceMillis }
                .filter { abs(it.reward - storedReward) < 1e-6 }
                .minByOrNull { abs(it.timestamp - resolvedAt) }
        }

        fun isEmpty(): Boolean = byStream.isEmpty()
    }

    fun outcomeFrom(match: TelemetryResolution): RawInputsCodec.Outcome = RawInputsCodec.Outcome(
        completionRatio = match.completionRatio.coerceIn(0.0, 1.0),
        restartDelta = RewardCalculator.recoverRestartDelta(match.reward, match.completionRatio)
    )
}
