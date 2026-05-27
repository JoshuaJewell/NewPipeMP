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
        v[0] = sin(hourRadians)
        v[1] = cos(hourRadians)
        val dowRadians = 2.0 * PI * context.dayOfWeek / 7.0
        v[2] = sin(dowRadians)
        v[3] = cos(dowRadians)

        v[4] = ln(1.0 + context.sessionPosition.coerceAtLeast(0))
        v[5] = context.recentSkipRate
        v[6] = if (context.previousAutoQueued) 1.0 else 0.0
        v[7] = if (context.previousCompleted) 1.0 else 0.0

        v[8] = if (context.queueOrigin == QueueOrigin.SINGLE) 1.0 else 0.0
        v[9] = if (context.queueOrigin == QueueOrigin.PLAYLIST) 1.0 else 0.0
        v[10] = if (context.queueOrigin == QueueOrigin.CHANNEL) 1.0 else 0.0
        v[11] = if (context.queueOrigin == QueueOrigin.OTHER) 1.0 else 0.0

        // Item.
        v[12] = ln(1.0 + item.durationSeconds.coerceAtLeast(0L))
        v[13] = when (val r = item.userRating) {
            null -> 0.0
            else -> (r - 3.0) / 2.0
        }
        v[14] = ln(1.0 + item.lifetimePlayCount.coerceAtLeast(0))

        val plays = item.lifetimePlayCount
        if (plays < AdaptiveShuffleConfig.COLD_START_MIN_PLAYS) {
            v[15] = AdaptiveShuffleConfig.COLD_START_SKIP_RATIO
            v[16] = AdaptiveShuffleConfig.COLD_START_COMPLETION_RATIO
        } else {
            val playsD = plays.toDouble()
            v[15] = item.lifetimeEarlySkips / playsD
            v[16] = item.lifetimeCompletions / playsD
        }

        val days = item.daysSinceLastAccess ?: AdaptiveShuffleConfig.NEVER_PLAYED_DAYS_DEFAULT
        v[17] = ln(1.0 + days.coerceAtLeast(0.0))

        val isAudio = item.streamType == StreamTypeFeature.AUDIO ||
            item.streamType == StreamTypeFeature.LIVE_AUDIO
        val isLive = item.streamType == StreamTypeFeature.LIVE_VIDEO ||
            item.streamType == StreamTypeFeature.LIVE_AUDIO
        v[18] = if (isAudio) 1.0 else 0.0
        v[19] = if (isLive) 1.0 else 0.0

        // Cross.
        val prev = context.previousUploader
        val here = item.uploader
        v[20] = if (prev != null && here != null && prev == here) 1.0 else 0.0

        // Uploader-level aggregates. Null inputs map to neutral values so the model
        // does not learn a spurious "missing data" effect.
        v[21] = when (val r = item.uploaderAvgRating) {
            null -> 0.0
            else -> ((r - 3.0) / 2.0).coerceIn(-1.0, 1.0)
        }
        v[22] = item.uploaderCompletionRate ?: AdaptiveShuffleConfig.COLD_START_COMPLETION_RATIO
        v[23] = item.uploaderSkipRate ?: AdaptiveShuffleConfig.COLD_START_SKIP_RATIO

        // Pair-level cross: how many playlists contain both the previous track and this one.
        v[24] = ln(1.0 + item.playlistCoMembershipWithPrev.coerceAtLeast(0))

        // Implicit positive: the user has chosen to keep this track locally.
        v[25] = if (item.isDownloadedOffline) 1.0 else 0.0

        // Bias.
        v[26] = 1.0

        return v
    }
}
