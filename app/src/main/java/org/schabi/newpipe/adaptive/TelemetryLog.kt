/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock

// Append-only JSONL log of adaptive-shuffle decisions and resolutions. Rotates the
// current file to a single .1 sibling once it exceeds the configured size cap; the
// previous .1 is overwritten, so disk usage is bounded at twice the cap.
class TelemetryLog private constructor(context: Context) {
    private val file: File = File(context.applicationContext.filesDir, FILE_NAME)
    private val rotated: File = File(context.applicationContext.filesDir, FILE_NAME + ".1")
    private val writeLock = ReentrantLock()

    fun logDecision(
        chosenStreamId: Long,
        candidateCount: Int,
        predictedReward: Double,
        uncertainty: Double,
        nowMillis: Long
    ) {
        val line = buildString {
            append('{')
            append("\"event\":\"decision\",")
            append("\"timestamp\":").append(nowMillis).append(',')
            append("\"stream_id\":").append(chosenStreamId).append(',')
            append("\"candidate_count\":").append(candidateCount).append(',')
            append("\"predicted_reward\":").append(predictedReward).append(',')
            append("\"uncertainty\":").append(uncertainty).append(',')
            append("\"exploit\":").append(predictedReward > uncertainty)
            append('}')
        }
        appendLine(line)
    }

    fun logResolution(
        streamId: Long,
        completionRatio: Double,
        reward: Double,
        timeSinceDecisionMs: Long,
        nowMillis: Long
    ) {
        val line = buildString {
            append('{')
            append("\"event\":\"resolution\",")
            append("\"timestamp\":").append(nowMillis).append(',')
            append("\"stream_id\":").append(streamId).append(',')
            append("\"completion_ratio\":").append(completionRatio).append(',')
            append("\"reward\":").append(reward).append(',')
            append("\"time_since_decision_ms\":").append(timeSinceDecisionMs)
            append('}')
        }
        appendLine(line)
    }

    private fun appendLine(line: String) {
        writeLock.lock()
        try {
            if (file.length() > AdaptiveShuffleConfig.TELEMETRY_MAX_BYTES) {
                if (rotated.exists()) rotated.delete()
                file.renameTo(rotated)
            }
            FileOutputStream(file, true).use { out ->
                out.write(line.toByteArray(StandardCharsets.UTF_8))
                out.write('\n'.code)
            }
        } catch (e: IOException) {
            Log.w(TAG, "telemetry write failed: " + e.message)
        } finally {
            writeLock.unlock()
        }
    }

    companion object {
        private const val TAG = "AdaptiveTelemetry"
        private const val FILE_NAME = "adaptive_shuffle_telemetry.jsonl"

        @Volatile private var instance: TelemetryLog? = null

        fun get(context: Context): TelemetryLog {
            val existing = instance
            if (existing != null) return existing
            synchronized(this) {
                val again = instance
                if (again != null) return again
                val created = TelemetryLog(context)
                instance = created
                return created
            }
        }
    }
}
