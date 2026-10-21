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
//   3: appends a trailer holding decision metadata (whether the pick was the greedy
//      choice) and, once the track resolves, the outcome (completion ratio and restart
//      delta). The outcome lets any future reward function be replayed exactly, which
//      v2 could not do because only the scalar reward was stored.
//   4 to 6: written by the on-device v1.1 build, whose source is lost. Each is the v2
//      body followed by a block of its own; decoded here, never written:
//        4: output route (byte), screen on (byte), 8 bytes not decoded.
//        5: route, screen, then the listen-event counts as six ints (backward seeks,
//           forward seeks, volume up, volume down, pauses resumed, pauses skipped), the
//           leave count (int), mean and M2 (doubles).
//        6: as 5, then 154 bytes (audio features, never filled in) not decoded.
//      Neither has the v3 trailer.
//   7: the v2 body, the v5 block, then the v3 trailer. Numbered past the v1.1 build's
//      versions so its blobs are never mistaken for this layout.
//      The greedy byte's 0x10 bit marks a baseline pick: one drawn uniformly at random
//      from the candidates instead of by the model (AdaptiveShuffleConfig.BASELINE_SHARE).
//      Blobs written before it existed never set it.
object RawInputsCodec {
    private const val VERSION: Byte = 7
    private const val VERSION_2: Byte = 2
    private const val VERSION_3: Byte = 3
    private const val LEGACY_4: Byte = 4
    private const val LEGACY_5: Byte = 5
    private const val LEGACY_6: Byte = 6
    private const val LEGACY_4_UNDECODED_BYTES = 8
    private const val BASELINE_BIT = 0x10
    private const val GREEDY_MASK = 0x0F

    data class Outcome(val completionRatio: Double, val restartDelta: Int)

    data class Decoded(
        val context: DecisionContext,
        val item: CandidateItem,
        // null when unknown (v2 blobs, or decisions made before the flag existed).
        val greedy: Boolean? = null,
        val outcome: Outcome? = null,
        val baseline: Boolean = false
    )

    // Re-encodes an existing blob with an outcome attached, upgrading it to the current
    // version on the way through.
    fun withOutcome(bytes: ByteArray, outcome: Outcome): ByteArray {
        val d = decode(bytes)
        return encode(d.context, d.item, d.greedy, outcome, d.baseline)
    }

    fun encode(
        context: DecisionContext,
        item: CandidateItem,
        greedy: Boolean? = null,
        outcome: Outcome? = null,
        baseline: Boolean = false
    ): ByteArray {
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
                Byte.SIZE_BYTES + // is_downloaded_offline
                Byte.SIZE_BYTES + Byte.SIZE_BYTES + // v7: output route, screen on
                6 * Int.SIZE_BYTES + // v7: listen-event counts
                Int.SIZE_BYTES + Double.SIZE_BYTES + Double.SIZE_BYTES + // v7: leave count, mean, M2
                Byte.SIZE_BYTES + // v3: greedy flag (0 no, 1 yes, 2 unknown)
                Byte.SIZE_BYTES + Double.SIZE_BYTES + Int.SIZE_BYTES // v3: outcome present, ratio, restart delta
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
        buf.put(context.outputRoute.ordinal.toByte())
        buf.put(if (context.screenOn) 1.toByte() else 0.toByte())
        val e = item.listenEvents
        buf.putInt(e.backwardSeeks)
        buf.putInt(e.forwardSeeks)
        buf.putInt(e.volumeUps)
        buf.putInt(e.volumeDowns)
        buf.putInt(e.pauseResumes)
        buf.putInt(e.pauseSkips)
        buf.putInt(e.leaveCount)
        buf.putDouble(e.leaveMean)
        buf.putDouble(e.leaveM2)
        val greedyCode = when (greedy) {
            false -> 0
            true -> 1
            null -> 2
        }
        buf.put((greedyCode or (if (baseline) BASELINE_BIT else 0)).toByte())
        if (outcome != null) {
            buf.put(1.toByte())
            buf.putDouble(outcome.completionRatio)
            buf.putInt(outcome.restartDelta)
        } else {
            buf.put(0.toByte())
            buf.putDouble(0.0)
            buf.putInt(0)
        }
        return buf.array()
    }

    fun decode(bytes: ByteArray): Decoded {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val v = buf.get()
        require(v in SUPPORTED) { "unknown raw inputs version $v" }
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
        var conditions = ListeningConditions.UNKNOWN
        var events = ListenEventCounts.NONE
        if (v == LEGACY_4 || v == LEGACY_5 || v == LEGACY_6 || v == VERSION) {
            val route = OutputRoute.values().getOrElse(buf.get().toInt()) { OutputRoute.OTHER }
            conditions = ListeningConditions(screenOn = buf.get() != 0.toByte(), outputRoute = route)
        }
        if (v == LEGACY_4) {
            buf.position(buf.position() + LEGACY_4_UNDECODED_BYTES)
        } else if (v == LEGACY_5 || v == LEGACY_6 || v == VERSION) {
            events = ListenEventCounts(
                backwardSeeks = buf.getInt(),
                forwardSeeks = buf.getInt(),
                volumeUps = buf.getInt(),
                volumeDowns = buf.getInt(),
                pauseResumes = buf.getInt(),
                pauseSkips = buf.getInt(),
                leaveCount = buf.getInt(),
                leaveMean = buf.getDouble(),
                leaveM2 = buf.getDouble()
            )
        }
        var greedy: Boolean? = null
        var outcome: Outcome? = null
        var baseline = false
        if (v == VERSION_3 || v == VERSION) {
            val code = buf.get().toInt()
            baseline = v == VERSION && (code and BASELINE_BIT) != 0
            greedy = when (code and GREEDY_MASK) {
                0 -> false
                1 -> true
                else -> null
            }
            val outcomePresent = buf.get() != 0.toByte()
            val ratio = buf.getDouble()
            val restart = buf.getInt()
            if (outcomePresent) outcome = Outcome(ratio, restart)
        }
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
                queueOrigin = origin,
                screenOn = conditions.screenOn,
                outputRoute = conditions.outputRoute
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
                isDownloadedOffline = isOffline,
                listenEvents = events
            ),
            greedy = greedy,
            outcome = outcome,
            baseline = baseline
        )
    }

    private val SUPPORTED = setOf(VERSION_2, VERSION_3, LEGACY_4, LEGACY_5, LEGACY_6, VERSION)

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

    // The candidates a decision chose from, as the v1.1 build stored them: a count, then
    // each stream id.
    fun encodeStreamIds(ids: List<Long>): ByteArray {
        val buf = ByteBuffer.allocate(Int.SIZE_BYTES + ids.size * Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(ids.size)
        for (id in ids) buf.putLong(id)
        return buf.array()
    }

    fun decodeStreamIds(bytes: ByteArray): LongArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return LongArray(buf.getInt()) { buf.getLong() }
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
