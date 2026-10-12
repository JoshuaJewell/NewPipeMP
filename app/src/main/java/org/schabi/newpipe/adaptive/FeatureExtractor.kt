/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

object FeatureExtractor {
    fun buildVector(
        context: DecisionContext,
        item: CandidateItem,
        scaler: FeatureScaler
    ): DoubleArray = scaler.transform(buildRawVector(context, item))

    fun buildRawVector(context: DecisionContext, item: CandidateItem): DoubleArray {
        val v = DoubleArray(FeatureSchema.DIMENSION)

        // Context: cyclical time encodings keep midnight and noon adjacent on the unit circle.
        val hourRadians = 2.0 * PI * context.hourOfDay / 24.0
        v[FeatureSchema.HOUR_SIN] = sin(hourRadians)
        v[FeatureSchema.HOUR_COS] = cos(hourRadians)
        val dowRadians = 2.0 * PI * context.dayOfWeek / 7.0
        v[FeatureSchema.DOW_SIN] = sin(dowRadians)
        v[FeatureSchema.DOW_COS] = cos(dowRadians)

        v[FeatureSchema.SESSION_POSITION] = ln(1.0 + context.sessionPosition.coerceAtLeast(0))
        v[FeatureSchema.RECENT_SKIP_RATE] = context.recentSkipRate
        v[FeatureSchema.PREV_AUTOQUEUED] = if (context.previousAutoQueued) 1.0 else 0.0
        v[FeatureSchema.PREV_COMPLETED] = if (context.previousCompleted) 1.0 else 0.0

        // OTHER is the baseline held by the bias, so it has no column of its own.
        v[FeatureSchema.ORIGIN_SINGLE] = if (context.queueOrigin == QueueOrigin.SINGLE) 1.0 else 0.0
        v[FeatureSchema.ORIGIN_PLAYLIST] = if (context.queueOrigin == QueueOrigin.PLAYLIST) 1.0 else 0.0
        v[FeatureSchema.ORIGIN_CHANNEL] = if (context.queueOrigin == QueueOrigin.CHANNEL) 1.0 else 0.0

        // Item.
        v[FeatureSchema.LOG_DURATION] = ln(1.0 + item.durationSeconds.coerceAtLeast(0L))
        val rating = item.userRating
        v[FeatureSchema.IS_RATED] = if (rating != null) 1.0 else 0.0
        v[FeatureSchema.USER_RATING] = if (rating != null) (rating - 3.0) / 2.0 else 0.0
        v[FeatureSchema.LOG_PLAY_COUNT] = ln(1.0 + item.lifetimePlayCount.coerceAtLeast(0))

        val warm = item.lifetimePlayCount >= AdaptiveShuffleConfig.COLD_START_MIN_PLAYS
        v[FeatureSchema.COMPLETION_RATIO] = if (warm) {
            item.lifetimeCompletions.toDouble() / item.lifetimePlayCount
        } else {
            AdaptiveShuffleConfig.COLD_START_COMPLETION_RATIO
        }

        val days = item.daysSinceLastAccess ?: AdaptiveShuffleConfig.NEVER_PLAYED_DAYS_DEFAULT
        v[FeatureSchema.DAYS_SINCE_PLAYED] = ln(1.0 + days.coerceAtLeast(0.0))

        val isAudio = item.streamType == StreamTypeFeature.AUDIO ||
            item.streamType == StreamTypeFeature.LIVE_AUDIO
        val isLive = item.streamType == StreamTypeFeature.LIVE_VIDEO ||
            item.streamType == StreamTypeFeature.LIVE_AUDIO
        v[FeatureSchema.IS_AUDIO_ONLY] = if (isAudio) 1.0 else 0.0
        v[FeatureSchema.IS_LIVE] = if (isLive) 1.0 else 0.0

        // Cross.
        val prev = context.previousUploader
        val here = item.uploader
        v[FeatureSchema.SAME_UPLOADER] = if (prev != null && here != null && prev == here) 1.0 else 0.0

        // Within-uploader deviations. Both are zero when either side is unknown, so a
        // track never inherits standing from its uploader, only its difference from it.
        v[FeatureSchema.COMPLETION_VS_UPLOADER] = completionVsUploader(item)
        v[FeatureSchema.RATING_VS_UPLOADER] = ratingVsUploader(item)

        // Pair-level cross: how many playlists contain both the previous track and this one.
        v[FeatureSchema.PLAYLIST_CO_MEMBERSHIP] = ln(1.0 + item.playlistCoMembershipWithPrev.coerceAtLeast(0))

        // Implicit positive: the user has chosen to keep this track locally.
        v[FeatureSchema.IS_OFFLINE] = if (item.isDownloadedOffline) 1.0 else 0.0

        v[FeatureSchema.BIAS] = 1.0

        return v
    }

    // The uploader rate is completions / (completions + skips) summed over the uploader's
    // tracks, so the track side uses the same denominator for a like-for-like difference.
    internal fun completionVsUploader(item: CandidateItem): Double {
        val uploaderRate = item.uploaderCompletionRate ?: return 0.0
        val resolved = item.lifetimeCompletions + item.lifetimeEarlySkips
        if (resolved < AdaptiveShuffleConfig.COLD_START_MIN_PLAYS) return 0.0
        val trackRate = item.lifetimeCompletions.toDouble() / resolved
        return (trackRate - uploaderRate).coerceIn(-1.0, 1.0)
    }

    // Ratings run 0 to 5, so a difference spans at most 5; divide to keep it in [-1, 1].
    internal fun ratingVsUploader(item: CandidateItem): Double {
        val rating = item.userRating ?: return 0.0
        val avg = item.uploaderAvgRating ?: return 0.0
        return ((rating - avg) / 5.0).coerceIn(-1.0, 1.0)
    }
}
