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
}
