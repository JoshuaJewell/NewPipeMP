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
        assertEquals(1.0, v[FeatureSchema.BIAS], 0.0)
    }

    @Test fun `null user rating is unrated and neutral`() {
        val v = FeatureExtractor.buildVector(ctx(), item(userRating = null), passthroughScaler)
        assertEquals(0.0, v[FeatureSchema.IS_RATED], 0.0)
        assertEquals(0.0, v[FeatureSchema.USER_RATING], 0.0)
    }

    @Test fun `three star rating is distinguishable from unrated`() {
        val three = FeatureExtractor.buildVector(ctx(), item(userRating = 3), passthroughScaler)
        val none = FeatureExtractor.buildVector(ctx(), item(userRating = null), passthroughScaler)
        assertEquals(three[FeatureSchema.USER_RATING], none[FeatureSchema.USER_RATING], 0.0)
        assertEquals(1.0, three[FeatureSchema.IS_RATED], 0.0)
        assertEquals(0.0, none[FeatureSchema.IS_RATED], 0.0)
    }

    @Test fun `five star rating maps to plus one`() {
        val v = FeatureExtractor.buildVector(ctx(), item(userRating = 5), passthroughScaler)
        assertEquals(1.0, v[FeatureSchema.USER_RATING], 1e-12)
    }

    @Test fun `one star rating maps to minus one`() {
        val v = FeatureExtractor.buildVector(ctx(), item(userRating = 1), passthroughScaler)
        assertEquals(-1.0, v[FeatureSchema.USER_RATING], 1e-12)
    }

    @Test fun `queue origin sets at most one bit and other is the all-zero baseline`() {
        val cols = intArrayOf(FeatureSchema.ORIGIN_SINGLE, FeatureSchema.ORIGIN_PLAYLIST, FeatureSchema.ORIGIN_CHANNEL)
        for (origin in QueueOrigin.values()) {
            val v = FeatureExtractor.buildVector(ctx(queueOrigin = origin), item(), passthroughScaler)
            val sum = cols.sumOf { v[it] }
            val expected = if (origin == QueueOrigin.OTHER) 0.0 else 1.0
            assertEquals("origin $origin", expected, sum, 0.0)
        }
    }

    @Test fun `days since played defaults when never played`() {
        val v = FeatureExtractor.buildVector(ctx(), item(daysSinceLastAccess = null), passthroughScaler)
        assertEquals(ln(1.0 + AdaptiveShuffleConfig.NEVER_PLAYED_DAYS_DEFAULT), v[FeatureSchema.DAYS_SINCE_PLAYED], 1e-12)
    }

    @Test fun `same uploader as previous matches when both non-null and equal`() {
        val v = FeatureExtractor.buildVector(
            ctx(previousUploader = "Bach"),
            item(uploader = "Bach"),
            passthroughScaler
        )
        assertEquals(1.0, v[FeatureSchema.SAME_UPLOADER], 0.0)
    }

    @Test fun `same uploader as previous is zero when previous uploader unknown`() {
        val v = FeatureExtractor.buildVector(
            ctx(previousUploader = null),
            item(uploader = "Bach"),
            passthroughScaler
        )
        assertEquals(0.0, v[FeatureSchema.SAME_UPLOADER], 0.0)
    }

    @Test fun `is audio only flag set for audio stream types`() {
        val a = FeatureExtractor.buildVector(ctx(), item(streamType = StreamTypeFeature.AUDIO), passthroughScaler)
        val la = FeatureExtractor.buildVector(ctx(), item(streamType = StreamTypeFeature.LIVE_AUDIO), passthroughScaler)
        val v = FeatureExtractor.buildVector(ctx(), item(streamType = StreamTypeFeature.VIDEO), passthroughScaler)
        assertEquals(1.0, a[FeatureSchema.IS_AUDIO_ONLY], 0.0)
        assertEquals(1.0, la[FeatureSchema.IS_AUDIO_ONLY], 0.0)
        assertEquals(0.0, v[FeatureSchema.IS_AUDIO_ONLY], 0.0)
    }

    @Test fun `is live flag set for both live audio and live video`() {
        val lv = FeatureExtractor.buildVector(ctx(), item(streamType = StreamTypeFeature.LIVE_VIDEO), passthroughScaler)
        val la = FeatureExtractor.buildVector(ctx(), item(streamType = StreamTypeFeature.LIVE_AUDIO), passthroughScaler)
        val a = FeatureExtractor.buildVector(ctx(), item(streamType = StreamTypeFeature.AUDIO), passthroughScaler)
        assertEquals(1.0, lv[FeatureSchema.IS_LIVE], 0.0)
        assertEquals(1.0, la[FeatureSchema.IS_LIVE], 0.0)
        assertEquals(0.0, a[FeatureSchema.IS_LIVE], 0.0)
    }

    @Test fun `cold start default applied when lifetime plays below threshold`() {
        val v = FeatureExtractor.buildVector(
            ctx(),
            item(plays = 1, earlySkips = 0, completions = 1),
            passthroughScaler
        )
        assertEquals(AdaptiveShuffleConfig.COLD_START_COMPLETION_RATIO, v[FeatureSchema.COMPLETION_RATE], 0.0)
    }

    @Test fun `lifetime completion rate is over finished listens`() {
        val v = FeatureExtractor.buildVector(
            ctx(),
            item(plays = 10, earlySkips = 2, completions = 7),
            passthroughScaler
        )
        assertEquals(7.0 / 9.0, v[FeatureSchema.COMPLETION_RATE], 1e-12)
    }

    @Test fun `resumes and rebuffers do not dilute the completion rate`() {
        // play_count rises on every resume; the rate must not move with it.
        val few = FeatureExtractor.buildVector(ctx(), item(plays = 4, earlySkips = 1, completions = 3), passthroughScaler)
        val many = FeatureExtractor.buildVector(ctx(), item(plays = 40, earlySkips = 1, completions = 3), passthroughScaler)
        assertEquals(few[FeatureSchema.COMPLETION_RATE], many[FeatureSchema.COMPLETION_RATE], 0.0)
    }

    @Test fun `two tracks by one uploader get different uploader-derived values`() {
        // The v2 collapse: every track by an uploader carried the same uploader features,
        // so a strong track lifted its weak album-mates. Deviations separate them.
        val strong = item(uploader = "U", plays = 10, earlySkips = 1, completions = 9, uploaderCompletionRate = 0.5)
        val weak = item(uploader = "U", plays = 10, earlySkips = 9, completions = 1, uploaderCompletionRate = 0.5)
        val vs = FeatureExtractor.buildVector(ctx(), strong, passthroughScaler)
        val vw = FeatureExtractor.buildVector(ctx(), weak, passthroughScaler)
        assertEquals(0.4, vs[FeatureSchema.COMPLETION_VS_UPLOADER], 1e-12)
        assertEquals(-0.4, vw[FeatureSchema.COMPLETION_VS_UPLOADER], 1e-12)
    }

    @Test fun `uploader deviations are zero when either side is unknown`() {
        val noUploaderRate = item(plays = 10, earlySkips = 2, completions = 8, uploaderCompletionRate = null)
        val coldTrack = item(plays = 1, earlySkips = 0, completions = 1, uploaderCompletionRate = 0.9)
        val noRating = item(userRating = null, uploaderAvgRating = 4.0)
        val noUploaderRating = item(userRating = 5, uploaderAvgRating = null)
        for (it in listOf(noUploaderRate, coldTrack)) {
            assertEquals(0.0, FeatureExtractor.buildVector(ctx(), it, passthroughScaler)[FeatureSchema.COMPLETION_VS_UPLOADER], 0.0)
        }
        for (it in listOf(noRating, noUploaderRating)) {
            assertEquals(0.0, FeatureExtractor.buildVector(ctx(), it, passthroughScaler)[FeatureSchema.RATING_VS_UPLOADER], 0.0)
        }
    }

    @Test fun `rating relative to uploader is scaled into unit range`() {
        val v = FeatureExtractor.buildVector(ctx(), item(userRating = 5, uploaderAvgRating = 2.5), passthroughScaler)
        assertEquals(0.5, v[FeatureSchema.RATING_VS_UPLOADER], 1e-12)
    }

    @Test fun `playlist co-membership uses log scaling`() {
        val zero = FeatureExtractor.buildVector(ctx(), item(playlistCoMembershipWithPrev = 0), passthroughScaler)
        val three = FeatureExtractor.buildVector(ctx(), item(playlistCoMembershipWithPrev = 3), passthroughScaler)
        assertEquals(0.0, zero[FeatureSchema.PLAYLIST_CO_MEMBERSHIP], 0.0)
        assertEquals(kotlin.math.ln(4.0), three[FeatureSchema.PLAYLIST_CO_MEMBERSHIP], 1e-12)
    }

    @Test fun `is downloaded offline flag maps to one`() {
        val on = FeatureExtractor.buildVector(ctx(), item(isDownloadedOffline = true), passthroughScaler)
        val off = FeatureExtractor.buildVector(ctx(), item(isDownloadedOffline = false), passthroughScaler)
        assertEquals(1.0, on[FeatureSchema.IS_OFFLINE], 0.0)
        assertEquals(0.0, off[FeatureSchema.IS_OFFLINE], 0.0)
    }
}
