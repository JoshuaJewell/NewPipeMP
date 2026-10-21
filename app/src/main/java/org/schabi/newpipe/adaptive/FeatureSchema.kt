/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.security.MessageDigest

object FeatureSchema {
    // Schema v3. Each change removes a linear dependency that left individual weights
    // unidentifiable (ADAPTIVE_SHUFFLE_SPEC.md, section 5):
    //   - queue_origin_other is dropped. The four origin one-hots always summed to one,
    //     so with the bias they were collinear; OTHER is now the baseline the bias holds.
    //   - The track skip_ratio is dropped. Skips and completions make up nearly every
    //     play, so the two ratios were close to complementary.
    //   - uploader_completion_rate and uploader_skip_rate are dropped. They summed to
    //     exactly one, and as absolute terms they lifted a whole catalogue at once.
    //   - uploader_avg_rating is replaced by within-uploader deviations, which ask whether
    //     a track is better or worse than its own uploader's baseline.
    //   - is_rated is added so an unrated track is no longer read as a three-star one.
    // Schema v4 renames completion_ratio to completion_rate and divides by finished
    // listens (completions plus skips) instead of play_count, which also counts every
    // resume and rebuffer. The rename changes the hash, so the model rebuilds.
    // Schema v5 adds the v1.1 instrumentation (ADAPTIVE_SHUFFLE_SPEC.md, section 4):
    //   - Context: screen on, and the output route as one-hots with the phone speaker as
    //     the baseline the bias holds (as with queue origin).
    //   - Item: six per-listen event rates and the mean and spread of the leave point.
    //     Rates divide by the listens whose leave point was recorded, the one count all
    //     of them were collected alongside.
    // It also puts user_rating_norm on the 1 to 10 scale ratings are stored in (it assumed
    // 1 to 5, so a full rating encoded as 3.5), and divides rating_vs_uploader by 9.
    val names: List<String> = listOf(
        "hour_sin", "hour_cos", "dow_sin", "dow_cos",
        "session_position", "recent_skip_rate",
        "prev_was_autoqueued", "prev_was_completed",
        "queue_origin_single", "queue_origin_playlist", "queue_origin_channel",
        "log_duration", "is_rated", "user_rating_norm", "log_play_count",
        "completion_rate", "days_since_played",
        "is_audio_only", "is_live",
        "same_uploader_as_prev",
        "completion_vs_uploader", "rating_vs_uploader",
        "playlist_co_membership_prev", "is_downloaded_offline",
        "screen_on", "route_bluetooth", "route_wired", "route_other",
        "forward_seeks_per_listen", "backward_seeks_per_listen",
        "pause_resumes_per_listen", "pause_skips_per_listen",
        "volume_ups_per_listen", "volume_downs_per_listen",
        "mean_leave_point", "leave_point_spread",
        "bias"
    )

    const val HOUR_SIN = 0
    const val HOUR_COS = 1
    const val DOW_SIN = 2
    const val DOW_COS = 3
    const val SESSION_POSITION = 4
    const val RECENT_SKIP_RATE = 5
    const val PREV_AUTOQUEUED = 6
    const val PREV_COMPLETED = 7
    const val ORIGIN_SINGLE = 8
    const val ORIGIN_PLAYLIST = 9
    const val ORIGIN_CHANNEL = 10
    const val LOG_DURATION = 11
    const val IS_RATED = 12
    const val USER_RATING = 13
    const val LOG_PLAY_COUNT = 14
    const val COMPLETION_RATE = 15
    const val DAYS_SINCE_PLAYED = 16
    const val IS_AUDIO_ONLY = 17
    const val IS_LIVE = 18
    const val SAME_UPLOADER = 19
    const val COMPLETION_VS_UPLOADER = 20
    const val RATING_VS_UPLOADER = 21
    const val PLAYLIST_CO_MEMBERSHIP = 22
    const val IS_OFFLINE = 23
    const val SCREEN_ON = 24
    const val ROUTE_BLUETOOTH = 25
    const val ROUTE_WIRED = 26
    const val ROUTE_OTHER = 27
    const val FORWARD_SEEKS = 28
    const val BACKWARD_SEEKS = 29
    const val PAUSE_RESUMES = 30
    const val PAUSE_SKIPS = 31
    const val VOLUME_UPS = 32
    const val VOLUME_DOWNS = 33
    const val MEAN_LEAVE_POINT = 34
    const val LEAVE_POINT_SPREAD = 35
    const val BIAS = 36

    const val DIMENSION: Int = 37

    // Indices whose values vary on a continuous unbounded scale and benefit from running
    // standardisation. Bounded or one-hot features pass through untouched.
    val scaledIndices: IntArray = intArrayOf(
        SESSION_POSITION,
        LOG_DURATION,
        LOG_PLAY_COUNT,
        DAYS_SINCE_PLAYED,
        FORWARD_SEEKS,
        BACKWARD_SEEKS,
        PAUSE_RESUMES,
        PAUSE_SKIPS,
        VOLUME_UPS,
        VOLUME_DOWNS
    )

    // Stable SHA-256 over the feature name list. Any reorder, rename or addition
    // changes this hash and triggers a model rebuild from raw inputs on launch.
    // The model's form is part of it: a stored model of another form is rebuilt too.
    const val MODEL_FORM: String = "hybrid-1"

    val schemaHash: String by lazy {
        val payload = (names.joinToString("|") + "|model:" + MODEL_FORM).toByteArray()
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
        "queue_origin_single" to "Queue was a single track (vs search or related)",
        "queue_origin_playlist" to "Queue came from a playlist (vs search or related)",
        "queue_origin_channel" to "Queue came from a channel tab (vs search or related)",
        "log_duration" to "Track length (log scale)",
        "is_rated" to "Track has a star rating at all",
        "user_rating_norm" to "Your rating, centred to zero",
        "log_play_count" to "Lifetime plays of this track (log scale)",
        "completion_rate" to "Share of this track's finished listens that reached the end",
        "days_since_played" to "Time since last play (log scale)",
        "is_audio_only" to "Audio-only stream",
        "is_live" to "Live stream",
        "same_uploader_as_prev" to "Same uploader as the previous track",
        "completion_vs_uploader" to "Completion rate relative to this uploader's usual",
        "rating_vs_uploader" to "Rating relative to this uploader's average",
        "playlist_co_membership_prev" to "Playlists containing both this and the previous track (log scale)",
        "is_downloaded_offline" to "Track is available offline",
        "screen_on" to "Screen was on when the pick was made",
        "route_bluetooth" to "Playing over Bluetooth (vs phone speaker)",
        "route_wired" to "Playing over wired or USB headphones (vs phone speaker)",
        "route_other" to "Playing over another output, such as HDMI or a dock (vs phone speaker)",
        "forward_seeks_per_listen" to "Forward seeks per listen of this track (log scale)",
        "backward_seeks_per_listen" to "Backward seeks per listen of this track (log scale)",
        "pause_resumes_per_listen" to "Pauses later resumed, per listen (log scale)",
        "pause_skips_per_listen" to "Pauses that ended in leaving the track, per listen (log scale)",
        "volume_ups_per_listen" to "Volume-up steps per listen (log scale)",
        "volume_downs_per_listen" to "Volume-down steps per listen (log scale)",
        "mean_leave_point" to "Average point in this track at which you leave it",
        "leave_point_spread" to "How much that leave point varies between listens",
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
    val queueOrigin: QueueOrigin,
    val screenOn: Boolean = false,
    val outputRoute: OutputRoute = OutputRoute.SPEAKER
)

// Ordinals are persisted by RawInputsCodec; append new values, never reorder.
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
    // Persisted for history but no longer a feature: it is 1 - uploaderCompletionRate.
    val uploaderSkipRate: Double? = null,
    val playlistCoMembershipWithPrev: Int = 0,
    val isDownloadedOffline: Boolean = false,
    val listenEvents: ListenEventCounts = ListenEventCounts.NONE
)

// Lifetime per-listen counts from playback_statistics (spec section 4). leaveCount is the
// number of listens whose leave point was recorded, with the mean and Welford M2 of those
// points; the event counts are divided by it.
data class ListenEventCounts(
    val forwardSeeks: Int = 0,
    val backwardSeeks: Int = 0,
    val pauseResumes: Int = 0,
    val pauseSkips: Int = 0,
    val volumeUps: Int = 0,
    val volumeDowns: Int = 0,
    val leaveCount: Int = 0,
    val leaveMean: Double = 0.0,
    val leaveM2: Double = 0.0
) {
    companion object {
        @JvmField
        val NONE = ListenEventCounts()
    }
}

enum class StreamTypeFeature { VIDEO, AUDIO, LIVE_VIDEO, LIVE_AUDIO }
