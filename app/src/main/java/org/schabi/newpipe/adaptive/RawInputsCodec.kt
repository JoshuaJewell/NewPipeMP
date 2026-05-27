/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

// Binary serialisation of the raw inputs used at decision time. Stored alongside the
// scaled feature vector so the model can be rebuilt against a new schema or scaler
// without losing history.
//
// Version history:
//   1: original schema (22 features).
//   2: adds uploader aggregates, playlist co-membership and offline flag (27 features).
object RawInputsCodec {
    private const val VERSION: Byte = 2

    data class Decoded(val context: DecisionContext, val item: CandidateItem)

    fun encode(context: DecisionContext, item: CandidateItem): ByteArray {
        val prevUploader = context.previousUploader?.toByteArray(StandardCharsets.UTF_8)
        val uploader = item.uploader?.toByteArray(StandardCharsets.UTF_8)
        val capacity =
            Byte.SIZE_BYTES + // version
                Byte.SIZE_BYTES + // hour
                Byte.SIZE_BYTES + // dow
                Int.SIZE_BYTES + // session_position
                Double.SIZE_BYTES + // recent_skip_rate
                Byte.SIZE_BYTES + // prev_autoqueued
                Byte.SIZE_BYTES + // prev_completed
                Byte.SIZE_BYTES + // prev_uploader present
                Int.SIZE_BYTES + (prevUploader?.size ?: 0) +
                Byte.SIZE_BYTES + // prev_stream_id present
                Long.SIZE_BYTES + // prev_stream_id value
                Byte.SIZE_BYTES + // queue_origin
                Long.SIZE_BYTES + // stream_id
                Long.SIZE_BYTES + // duration_seconds
                Byte.SIZE_BYTES + // user_rating present
                Int.SIZE_BYTES + // user_rating value
                Int.SIZE_BYTES + Int.SIZE_BYTES + Int.SIZE_BYTES + // play_count, early_skips, completions
                Byte.SIZE_BYTES + Double.SIZE_BYTES + // days_since present + value
                Byte.SIZE_BYTES + // stream_type
                Byte.SIZE_BYTES + // uploader present
                Int.SIZE_BYTES + (uploader?.size ?: 0) +
                Byte.SIZE_BYTES + Double.SIZE_BYTES + // uploader_avg_rating present + value
                Byte.SIZE_BYTES + Double.SIZE_BYTES + // uploader_completion_rate present + value
                Byte.SIZE_BYTES + Double.SIZE_BYTES + // uploader_skip_rate present + value
                Int.SIZE_BYTES + // playlist_co_membership_with_prev
                Byte.SIZE_BYTES // is_downloaded_offline
        val buf = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(VERSION)
        buf.put(context.hourOfDay.toByte())
        buf.put(context.dayOfWeek.toByte())
        buf.putInt(context.sessionPosition)
        buf.putDouble(context.recentSkipRate)
        buf.put(if (context.previousAutoQueued) 1.toByte() else 0.toByte())
        buf.put(if (context.previousCompleted) 1.toByte() else 0.toByte())
        if (prevUploader != null) {
            buf.put(1.toByte())
            buf.putInt(prevUploader.size)
            buf.put(prevUploader)
        } else {
            buf.put(0.toByte())
            buf.putInt(0)
        }
        if (context.previousStreamId != null) {
            buf.put(1.toByte())
            buf.putLong(context.previousStreamId)
        } else {
            buf.put(0.toByte())
            buf.putLong(0L)
        }
        buf.put(context.queueOrigin.ordinal.toByte())
        buf.putLong(item.streamId)
        buf.putLong(item.durationSeconds)
        if (item.userRating != null) {
            buf.put(1.toByte())
            buf.putInt(item.userRating)
        } else {
            buf.put(0.toByte())
            buf.putInt(0)
        }
        buf.putInt(item.lifetimePlayCount)
        buf.putInt(item.lifetimeEarlySkips)
        buf.putInt(item.lifetimeCompletions)
        if (item.daysSinceLastAccess != null) {
            buf.put(1.toByte())
            buf.putDouble(item.daysSinceLastAccess)
        } else {
            buf.put(0.toByte())
            buf.putDouble(0.0)
        }
        buf.put(item.streamType.ordinal.toByte())
        if (uploader != null) {
            buf.put(1.toByte())
            buf.putInt(uploader.size)
            buf.put(uploader)
        } else {
            buf.put(0.toByte())
            buf.putInt(0)
        }
        writeOptionalDouble(buf, item.uploaderAvgRating)
        writeOptionalDouble(buf, item.uploaderCompletionRate)
        writeOptionalDouble(buf, item.uploaderSkipRate)
        buf.putInt(item.playlistCoMembershipWithPrev)
        buf.put(if (item.isDownloadedOffline) 1.toByte() else 0.toByte())
        return buf.array()
    }

    fun decode(bytes: ByteArray): Decoded {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val v = buf.get()
        require(v == VERSION) { "unknown raw inputs version $v" }
        val hour = buf.get().toInt()
        val dow = buf.get().toInt()
        val session = buf.getInt()
        val skipRate = buf.getDouble()
        val prevAuto = buf.get() != 0.toByte()
        val prevComplete = buf.get() != 0.toByte()
        val prevUpPresent = buf.get() != 0.toByte()
        val prevUpLen = buf.getInt()
        val prevUploader = if (prevUpPresent) {
            val arr = ByteArray(prevUpLen)
            buf.get(arr)
            String(arr, StandardCharsets.UTF_8)
        } else {
            buf.position(buf.position() + prevUpLen)
            null
        }
        val prevStreamIdPresent = buf.get() != 0.toByte()
        val prevStreamIdValue = buf.getLong()
        val prevStreamId = if (prevStreamIdPresent) prevStreamIdValue else null
        val origin = QueueOrigin.values()[buf.get().toInt()]
        val streamId = buf.getLong()
        val duration = buf.getLong()
        val ratingPresent = buf.get() != 0.toByte()
        val ratingValue = buf.getInt()
        val userRating = if (ratingPresent) ratingValue else null
        val plays = buf.getInt()
        val earlySkips = buf.getInt()
        val completions = buf.getInt()
        val daysPresent = buf.get() != 0.toByte()
        val daysValue = buf.getDouble()
        val days = if (daysPresent) daysValue else null
        val streamType = StreamTypeFeature.values()[buf.get().toInt()]
        val uploaderPresent = buf.get() != 0.toByte()
        val uploaderLen = buf.getInt()
        val uploader = if (uploaderPresent) {
            val arr = ByteArray(uploaderLen)
            buf.get(arr)
            String(arr, StandardCharsets.UTF_8)
        } else {
            buf.position(buf.position() + uploaderLen)
            null
        }
        val uploaderAvgRating = readOptionalDouble(buf)
        val uploaderCompletionRate = readOptionalDouble(buf)
        val uploaderSkipRate = readOptionalDouble(buf)
        val coMembership = buf.getInt()
        val isOffline = buf.get() != 0.toByte()
        return Decoded(
            context = DecisionContext(
                hourOfDay = hour,
                dayOfWeek = dow,
                sessionPosition = session,
                recentSkipRate = skipRate,
                previousAutoQueued = prevAuto,
                previousCompleted = prevComplete,
                previousUploader = prevUploader,
                previousStreamId = prevStreamId,
                queueOrigin = origin
            ),
            item = CandidateItem(
                streamId = streamId,
                durationSeconds = duration,
                userRating = userRating,
                lifetimePlayCount = plays,
                lifetimeEarlySkips = earlySkips,
                lifetimeCompletions = completions,
                daysSinceLastAccess = days,
                streamType = streamType,
                uploader = uploader,
                uploaderAvgRating = uploaderAvgRating,
                uploaderCompletionRate = uploaderCompletionRate,
                uploaderSkipRate = uploaderSkipRate,
                playlistCoMembershipWithPrev = coMembership,
                isDownloadedOffline = isOffline
            )
        )
    }

    private fun writeOptionalDouble(buf: ByteBuffer, value: Double?) {
        if (value != null) {
            buf.put(1.toByte())
            buf.putDouble(value)
        } else {
            buf.put(0.toByte())
            buf.putDouble(0.0)
        }
    }

    private fun readOptionalDouble(buf: ByteBuffer): Double? {
        val present = buf.get() != 0.toByte()
        val value = buf.getDouble()
        return if (present) value else null
    }

    fun encodeFeatureVector(v: DoubleArray): ByteArray {
        val buf = ByteBuffer.allocate(Int.SIZE_BYTES + v.size * Double.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(v.size)
        for (x in v) buf.putDouble(x)
        return buf.array()
    }

    fun decodeFeatureVector(bytes: ByteArray): DoubleArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = buf.getInt()
        val out = DoubleArray(n)
        for (i in 0 until n) out[i] = buf.getDouble()
        return out
    }
}
