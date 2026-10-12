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
