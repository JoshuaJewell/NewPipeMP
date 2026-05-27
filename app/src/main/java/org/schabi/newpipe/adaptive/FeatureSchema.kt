/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.security.MessageDigest

object FeatureSchema {
    val names: List<String> = listOf(
        "hour_sin", "hour_cos", "dow_sin", "dow_cos",
        "session_position", "recent_skip_rate",
        "prev_was_autoqueued", "prev_was_completed",
        "queue_origin_single", "queue_origin_playlist",
        "queue_origin_channel", "queue_origin_other",
        "log_duration", "user_rating_norm", "log_play_count",
        "skip_ratio", "completion_ratio", "days_since_played",
        "is_audio_only", "is_live",
        "same_uploader_as_prev",
        "uploader_avg_rating", "uploader_completion_rate", "uploader_skip_rate",
        "playlist_co_membership_prev", "is_downloaded_offline",
        "bias"
    )

    const val DIMENSION: Int = 27

    // Indices into the feature vector whose values vary on a continuous unbounded
    // scale and benefit from running standardisation. Bounded or one-hot features
    // are passed through untouched.
    val scaledIndices: IntArray = intArrayOf(
        4, // session_position (log-transformed)
        12, // log_duration
        14, // log_play_count
        17 // days_since_played (log-transformed)
    )

    // Stable SHA-256 over the feature name list. Any reorder, rename or addition
    // changes this hash and triggers a model rebuild from raw inputs on launch.
    val schemaHash: String by lazy {
        val payload = names.joinToString("|").toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        digest.joinToString("") { "%02x".format(it) }
    }

    // Short human-readable explanation per feature, shown in the debug view.
    // Keep these terse: one short sentence each.
    val descriptions: Map<String, String> = mapOf(
        "hour_sin" to "Time of day, cyclical (sine)",
        "hour_cos" to "Time of day, cyclical (cosine)",
        "dow_sin" to "Day of week, cyclical (sine)",
        "dow_cos" to "Day of week, cyclical (cosine)",
        "session_position" to "Tracks already played this session (log scale)",
        "recent_skip_rate" to "Early-skip rate over the last ten plays",
        "prev_was_autoqueued" to "Previous track was added by autoplay",
        "prev_was_completed" to "Previous track played to at least 85%",
        "queue_origin_single" to "Queue was a single track",
        "queue_origin_playlist" to "Queue came from a playlist",
        "queue_origin_channel" to "Queue came from a channel tab",
        "queue_origin_other" to "Queue came from search or related",
        "log_duration" to "Track length (log scale)",
        "user_rating_norm" to "Your five-star rating, centred to zero",
        "log_play_count" to "Lifetime plays of this track (log scale)",
        "skip_ratio" to "Lifetime early-skip rate for this track",
        "completion_ratio" to "Lifetime completion rate for this track",
        "days_since_played" to "Time since last play (log scale)",
        "is_audio_only" to "Audio-only stream",
        "is_live" to "Live stream",
        "same_uploader_as_prev" to "Same uploader as the previous track",
        "uploader_avg_rating" to "Average rating across this uploader's rated tracks",
        "uploader_completion_rate" to "Lifetime completion rate across all tracks by this uploader",
        "uploader_skip_rate" to "Lifetime early-skip rate across all tracks by this uploader",
        "playlist_co_membership_prev" to "Playlists containing both this and the previous track (log scale)",
        "is_downloaded_offline" to "Track is available offline",
        "bias" to "Constant baseline (model's average reward)"
    )
}

data class DecisionContext(
    val hourOfDay: Int,
    val dayOfWeek: Int,
    val sessionPosition: Int,
    val recentSkipRate: Double,
    val previousAutoQueued: Boolean,
    val previousCompleted: Boolean,
    val previousUploader: String?,
    val previousStreamId: Long?,
    val queueOrigin: QueueOrigin
)

enum class QueueOrigin { SINGLE, PLAYLIST, CHANNEL, OTHER }

data class CandidateItem(
    val streamId: Long,
    val durationSeconds: Long,
    val userRating: Int?,
    val lifetimePlayCount: Int,
    val lifetimeEarlySkips: Int,
    val lifetimeCompletions: Int,
    val daysSinceLastAccess: Double?,
    val streamType: StreamTypeFeature,
    val uploader: String?,
    val uploaderAvgRating: Double? = null,
    val uploaderCompletionRate: Double? = null,
    val uploaderSkipRate: Double? = null,
    val playlistCoMembershipWithPrev: Int = 0,
    val isDownloadedOffline: Boolean = false
)

enum class StreamTypeFeature { VIDEO, AUDIO, LIVE_VIDEO, LIVE_AUDIO }
