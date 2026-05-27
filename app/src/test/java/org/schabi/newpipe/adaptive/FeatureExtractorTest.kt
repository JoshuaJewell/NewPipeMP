/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureExtractorTest {

    // An empty scaler passes values through, so each test reads the raw extractor output.
    private val passthroughScaler = FeatureScaler(FeatureSchema.scaledIndices)

    private fun ctx(
        hour: Int = 12,
        dow: Int = 0,
        sessionPosition: Int = 0,
        recentSkipRate: Double = 0.2,
        previousAutoQueued: Boolean = false,
        previousCompleted: Boolean = false,
        previousUploader: String? = null,
        previousStreamId: Long? = null,
        queueOrigin: QueueOrigin = QueueOrigin.SINGLE
    ) = DecisionContext(
        hourOfDay = hour,
        dayOfWeek = dow,
        sessionPosition = sessionPosition,
        recentSkipRate = recentSkipRate,
        previousAutoQueued = previousAutoQueued,
        previousCompleted = previousCompleted,
        previousUploader = previousUploader,
        previousStreamId = previousStreamId,
        queueOrigin = queueOrigin
    )

    private fun item(
        durationSeconds: Long = 240L,
        userRating: Int? = null,
        plays: Int = 0,
        earlySkips: Int = 0,
        completions: Int = 0,
        daysSinceLastAccess: Double? = null,
        streamType: StreamTypeFeature = StreamTypeFeature.VIDEO,
        uploader: String? = null,
        uploaderAvgRating: Double? = null,
        uploaderCompletionRate: Double? = null,
        uploaderSkipRate: Double? = null,
        playlistCoMembershipWithPrev: Int = 0,
        isDownloadedOffline: Boolean = false
    ) = CandidateItem(
        streamId = 1L,
        durationSeconds = durationSeconds,
        userRating = userRating,
        lifetimePlayCount = plays,
        lifetimeEarlySkips = earlySkips,
        lifetimeCompletions = completions,
        daysSinceLastAccess = daysSinceLastAccess,
        streamType = streamType,
        uploader = uploader,
        uploaderAvgRating = uploaderAvgRating,
        uploaderCompletionRate = uploaderCompletionRate,
        uploaderSkipRate = uploaderSkipRate,
        playlistCoMembershipWithPrev = playlistCoMembershipWithPrev,
        isDownloadedOffline = isDownloadedOffline
    )

    @Test fun `vector length equals the schema dimension`() {
        val v = FeatureExtractor.buildVector(ctx(), item(), passthroughScaler)
        assertEquals(FeatureSchema.DIMENSION, v.size)
    }

    @Test fun `hour zero encodes to sin zero cos one`() {
        val v = FeatureExtractor.buildVector(ctx(hour = 0), item(), passthroughScaler)
        assertEquals(0.0, v[0], 1e-12)
        assertEquals(1.0, v[1], 1e-12)
    }

    @Test fun `bias term is one`() {
        val v = FeatureExtractor.buildVector(ctx(), item(), passthroughScaler)
        assertEquals(1.0, v[FeatureSchema.DIMENSION - 1], 0.0)
    }

    @Test fun `null user rating produces neutral zero`() {
        val v = FeatureExtractor.buildVector(ctx(), item(userRating = null), passthroughScaler)
        assertEquals(0.0, v[13], 0.0)
    }

    @Test fun `five star rating maps to plus one`() {
        val v = FeatureExtractor.buildVector(ctx(), item(userRating = 5), passthroughScaler)
        assertEquals(1.0, v[13], 1e-12)
    }

    @Test fun `one star rating maps to minus one`() {
        val v = FeatureExtractor.buildVector(ctx(), item(userRating = 1), passthroughScaler)
        assertEquals(-1.0, v[13], 1e-12)
    }

    @Test fun `queue origin one hot has exactly one set bit across the four positions`() {
        for (origin in QueueOrigin.values()) {
            val v = FeatureExtractor.buildVector(ctx(queueOrigin = origin), item(), passthroughScaler)
            val sum = v[8] + v[9] + v[10] + v[11]
            assertEquals("origin $origin", 1.0, sum, 0.0)
        }
    }

    @Test fun `days since played defaults when never played`() {
        val v = FeatureExtractor.buildVector(ctx(), item(daysSinceLastAccess = null), passthroughScaler)
        assertEquals(ln(1.0 + AdaptiveShuffleConfig.NEVER_PLAYED_DAYS_DEFAULT), v[17], 1e-12)
    }

    @Test fun `same uploader as previous matches when both non-null and equal`() {
        val v = FeatureExtractor.buildVector(
            ctx(previousUploader = "Bach"),
            item(uploader = "Bach"),
            passthroughScaler
        )
        assertEquals(1.0, v[20], 0.0)
    }

    @Test fun `same uploader as previous is zero when previous uploader unknown`() {
        val v = FeatureExtractor.buildVector(
            ctx(previousUploader = null),
            item(uploader = "Bach"),
            passthroughScaler
        )
        assertEquals(0.0, v[20], 0.0)
    }

    @Test fun `is audio only flag set for audio stream types`() {
        val a = FeatureExtractor.buildVector(ctx(), item(streamType = StreamTypeFeature.AUDIO), passthroughScaler)
        val la = FeatureExtractor.buildVector(ctx(), item(streamType = StreamTypeFeature.LIVE_AUDIO), passthroughScaler)
        val v = FeatureExtractor.buildVector(ctx(), item(streamType = StreamTypeFeature.VIDEO), passthroughScaler)
        assertEquals(1.0, a[18], 0.0)
        assertEquals(1.0, la[18], 0.0)
        assertEquals(0.0, v[18], 0.0)
    }

    @Test fun `is live flag set for both live audio and live video`() {
        val lv = FeatureExtractor.buildVector(ctx(), item(streamType = StreamTypeFeature.LIVE_VIDEO), passthroughScaler)
        val la = FeatureExtractor.buildVector(ctx(), item(streamType = StreamTypeFeature.LIVE_AUDIO), passthroughScaler)
        val a = FeatureExtractor.buildVector(ctx(), item(streamType = StreamTypeFeature.AUDIO), passthroughScaler)
        assertEquals(1.0, lv[19], 0.0)
        assertEquals(1.0, la[19], 0.0)
        assertEquals(0.0, a[19], 0.0)
    }

    @Test fun `cold start defaults applied when lifetime plays below threshold`() {
        val v = FeatureExtractor.buildVector(
            ctx(),
            item(plays = 1, earlySkips = 0, completions = 1),
            passthroughScaler
        )
        assertEquals(AdaptiveShuffleConfig.COLD_START_SKIP_RATIO, v[15], 0.0)
        assertEquals(AdaptiveShuffleConfig.COLD_START_COMPLETION_RATIO, v[16], 0.0)
    }

    @Test fun `lifetime ratios used when plays at or above threshold`() {
        val v = FeatureExtractor.buildVector(
            ctx(),
            item(plays = 10, earlySkips = 2, completions = 7),
            passthroughScaler
        )
        assertEquals(0.2, v[15], 1e-12)
        assertEquals(0.7, v[16], 1e-12)
    }

    @Test fun `uploader average rating maps null to neutral and 5 stars to plus one`() {
        val neutral = FeatureExtractor.buildVector(ctx(), item(uploaderAvgRating = null), passthroughScaler)
        val top = FeatureExtractor.buildVector(ctx(), item(uploaderAvgRating = 5.0), passthroughScaler)
        val bottom = FeatureExtractor.buildVector(ctx(), item(uploaderAvgRating = 1.0), passthroughScaler)
        assertEquals(0.0, neutral[21], 0.0)
        assertEquals(1.0, top[21], 1e-12)
        assertEquals(-1.0, bottom[21], 1e-12)
    }

    @Test fun `uploader rates fall back to cold-start defaults when null`() {
        val v = FeatureExtractor.buildVector(
            ctx(),
            item(uploaderCompletionRate = null, uploaderSkipRate = null),
            passthroughScaler
        )
        assertEquals(AdaptiveShuffleConfig.COLD_START_COMPLETION_RATIO, v[22], 0.0)
        assertEquals(AdaptiveShuffleConfig.COLD_START_SKIP_RATIO, v[23], 0.0)
    }

    @Test fun `playlist co-membership uses log scaling`() {
        val zero = FeatureExtractor.buildVector(ctx(), item(playlistCoMembershipWithPrev = 0), passthroughScaler)
        val three = FeatureExtractor.buildVector(ctx(), item(playlistCoMembershipWithPrev = 3), passthroughScaler)
        assertEquals(0.0, zero[24], 0.0)
        assertEquals(kotlin.math.ln(4.0), three[24], 1e-12)
    }

    @Test fun `is downloaded offline flag maps to one`() {
        val on = FeatureExtractor.buildVector(ctx(), item(isDownloadedOffline = true), passthroughScaler)
        val off = FeatureExtractor.buildVector(ctx(), item(isDownloadedOffline = false), passthroughScaler)
        assertEquals(1.0, on[25], 0.0)
        assertEquals(0.0, off[25], 0.0)
    }
}
