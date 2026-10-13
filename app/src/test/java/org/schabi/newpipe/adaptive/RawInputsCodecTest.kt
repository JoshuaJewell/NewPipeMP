/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RawInputsCodecTest {

    @Test fun `encode then decode round trips both context and item`() {
        val context = DecisionContext(
            hourOfDay = 19,
            dayOfWeek = 3,
            sessionPosition = 7,
            recentSkipRate = 0.42,
            previousAutoQueued = true,
            previousCompleted = false,
            previousUploader = "Telemann",
            previousStreamId = 9876L,
            queueOrigin = QueueOrigin.CHANNEL
        )
        val item = CandidateItem(
            streamId = 12345L,
            durationSeconds = 318L,
            userRating = 4,
            lifetimePlayCount = 11,
            lifetimeEarlySkips = 2,
            lifetimeCompletions = 8,
            daysSinceLastAccess = 1.5,
            streamType = StreamTypeFeature.AUDIO,
            uploader = "Hopkinson Smith",
            uploaderAvgRating = 4.3,
            uploaderCompletionRate = 0.71,
            uploaderSkipRate = 0.12,
            playlistCoMembershipWithPrev = 2,
            isDownloadedOffline = true
        )
        val bytes = RawInputsCodec.encode(context, item)
        val decoded = RawInputsCodec.decode(bytes)
        assertEquals(context, decoded.context)
        assertEquals(item, decoded.item)
    }

    @Test fun `null string and numeric fields round trip`() {
        val context = DecisionContext(
            hourOfDay = 0,
            dayOfWeek = 0,
            sessionPosition = 0,
            recentSkipRate = 0.0,
            previousAutoQueued = false,
            previousCompleted = false,
            previousUploader = null,
            previousStreamId = null,
            queueOrigin = QueueOrigin.OTHER
        )
        val item = CandidateItem(
            streamId = 1L,
            durationSeconds = 0L,
            userRating = null,
            lifetimePlayCount = 0,
            lifetimeEarlySkips = 0,
            lifetimeCompletions = 0,
            daysSinceLastAccess = null,
            streamType = StreamTypeFeature.VIDEO,
            uploader = null,
            uploaderAvgRating = null,
            uploaderCompletionRate = null,
            uploaderSkipRate = null,
            playlistCoMembershipWithPrev = 0,
            isDownloadedOffline = false
        )
        val decoded = RawInputsCodec.decode(RawInputsCodec.encode(context, item))
        assertEquals(context, decoded.context)
        assertEquals(item, decoded.item)
    }

    private val richContext = DecisionContext(
        hourOfDay = 22,
        dayOfWeek = 5,
        sessionPosition = 3,
        recentSkipRate = 0.3,
        previousAutoQueued = false,
        previousCompleted = true,
        previousUploader = "Gabrieli",
        previousStreamId = 55L,
        queueOrigin = QueueOrigin.PLAYLIST,
        screenOn = true,
        outputRoute = OutputRoute.BLUETOOTH
    )
    private val richItem = CandidateItem(
        streamId = 777L,
        durationSeconds = 241L,
        userRating = 9,
        lifetimePlayCount = 14,
        lifetimeEarlySkips = 3,
        lifetimeCompletions = 9,
        daysSinceLastAccess = 4.25,
        streamType = StreamTypeFeature.AUDIO,
        uploader = "Savall",
        uploaderAvgRating = 7.5,
        uploaderCompletionRate = 0.6,
        playlistCoMembershipWithPrev = 1,
        isDownloadedOffline = false,
        listenEvents = ListenEventCounts(
            forwardSeeks = 4,
            backwardSeeks = 1,
            pauseResumes = 2,
            pauseSkips = 1,
            volumeUps = 6,
            volumeDowns = 3,
            leaveCount = 12,
            leaveMean = 0.71,
            leaveM2 = 0.93
        )
    )

    @Test fun `listening conditions and listen events round trip with the trailer`() {
        val outcome = RawInputsCodec.Outcome(0.42, 1)
        val decoded = RawInputsCodec.decode(RawInputsCodec.encode(richContext, richItem, greedy = false, outcome = outcome))
        assertEquals(richContext, decoded.context)
        assertEquals(richItem, decoded.item)
        assertEquals(false, decoded.greedy)
        assertEquals(outcome, decoded.outcome)
    }

    // The trailer is a greedy byte, an outcome-present byte, a double and an int.
    private val trailerBytes = 1 + 1 + 8 + 4

    // The on-device v1.1 build wrote the v2 body and the listening/event block, with no
    // trailer, as version 5; version 6 added 154 bytes after it.
    private fun legacy(version: Int, extra: Int = 0): ByteArray {
        val v7 = RawInputsCodec.encode(richContext, richItem)
        val body = v7.copyOf(v7.size - trailerBytes)
        body[0] = version.toByte()
        return body + ByteArray(extra)
    }

    @Test fun `v1 point 1 build version 5 decodes conditions and events`() {
        val decoded = RawInputsCodec.decode(legacy(5))
        assertEquals(richContext, decoded.context)
        assertEquals(richItem, decoded.item)
        assertEquals(null, decoded.greedy)
        assertEquals(null, decoded.outcome)
    }

    @Test fun `v1 point 1 build version 6 skips its trailing block`() {
        val decoded = RawInputsCodec.decode(legacy(6, extra = 154))
        assertEquals(richContext, decoded.context)
        assertEquals(richItem, decoded.item)
    }

    @Test fun `v1 point 1 build version 4 has conditions but no events`() {
        val v2Body = legacy(5).let { it.copyOf(it.size - (6 * 4 + 4 + 8 + 8 + 2)) }
        val bytes = v2Body + byteArrayOf(OutputRoute.WIRED.ordinal.toByte(), 0) + ByteArray(8)
        bytes[0] = 4
        val decoded = RawInputsCodec.decode(bytes)
        assertEquals(richContext.copy(screenOn = false, outputRoute = OutputRoute.WIRED), decoded.context)
        assertEquals(richItem.copy(listenEvents = ListenEventCounts.NONE), decoded.item)
    }

    // The v1.1 build wrote route 2 for most of its decisions, made mostly over Bluetooth.
    @Test fun `v1 point 1 route value 2 reads as bluetooth`() {
        val bytes = legacy(5)
        val routeOffset = bytes.size - (6 * 4 + 4 + 8 + 8 + 2)
        bytes[routeOffset] = 2
        assertEquals(OutputRoute.BLUETOOTH, RawInputsCodec.decode(bytes).context.outputRoute)
    }

    @Test fun `attaching an outcome upgrades a legacy blob to the current version`() {
        val outcome = RawInputsCodec.Outcome(1.0, 0)
        val upgraded = RawInputsCodec.withOutcome(legacy(6, extra = 154), outcome)
        assertEquals(7, upgraded[0].toInt())
        val decoded = RawInputsCodec.decode(upgraded)
        assertEquals(richItem, decoded.item)
        assertEquals(outcome, decoded.outcome)
    }

    @Test fun `stream id list round trips`() {
        val ids = listOf(3L, 118823L, Long.MAX_VALUE)
        assertArrayEquals(ids.toLongArray(), RawInputsCodec.decodeStreamIds(RawInputsCodec.encodeStreamIds(ids)))
    }

    @Test fun `feature vector round trip`() {
        val v = doubleArrayOf(1.5, -2.0, 0.0, 3.14, Double.MIN_VALUE)
        assertArrayEquals(v, RawInputsCodec.decodeFeatureVector(RawInputsCodec.encodeFeatureVector(v)), 0.0)
    }

    @Test fun `greedy flag and outcome round trip`() {
        val ctx = fixtureContext()
        val item = fixtureItem(3)
        val bytes = RawInputsCodec.encode(ctx, item, greedy = false)
        val withOutcome = RawInputsCodec.withOutcome(bytes, RawInputsCodec.Outcome(0.37, 2))
        val decoded = RawInputsCodec.decode(withOutcome)
        assertEquals(false, decoded.greedy)
        assertEquals(0.37, decoded.outcome!!.completionRatio, 0.0)
        assertEquals(2, decoded.outcome!!.restartDelta)
        assertEquals(item, decoded.item)
    }

    @Test fun `v2 blobs still decode and upgrade when an outcome is attached`() {
        val ctx = fixtureContext()
        val item = fixtureItem(3)
        val v2 = downgradeToV2(RawInputsCodec.encode(ctx, item))
        val decoded = RawInputsCodec.decode(v2)
        assertEquals(item, decoded.item)
        assertEquals(null, decoded.greedy)
        assertEquals(null, decoded.outcome)
        val upgraded = RawInputsCodec.decode(RawInputsCodec.withOutcome(v2, RawInputsCodec.Outcome(0.9, 0)))
        assertEquals(0.9, upgraded.outcome!!.completionRatio, 0.0)
    }

    companion object {
        private const val V3_TRAILER_BYTES = 1 + 1 + 8 + 4

        // Builds the blob a v2 build would have written for the same inputs.
        fun downgradeToV2(v3: ByteArray): ByteArray {
            val out = v3.copyOf(v3.size - V3_TRAILER_BYTES)
            out[0] = 2
            return out
        }
    }
}
