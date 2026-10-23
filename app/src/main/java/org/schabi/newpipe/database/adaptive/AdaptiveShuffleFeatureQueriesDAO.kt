/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.database.adaptive

import androidx.room.Dao
import androidx.room.Query
import org.schabi.newpipe.database.stream.model.StreamEntity

@Dao
abstract class AdaptiveShuffleFeatureQueriesDAO {

    @Query(
        """
        SELECT COUNT(*) FROM playlist_stream_join
        WHERE playlist_id IN (SELECT playlist_id FROM playlist_stream_join WHERE stream_id = :a)
          AND stream_id = :b
        """
    )
    abstract fun coMembershipCountBlocking(a: Long, b: Long): Int

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM offline_file_mappings ofm
            INNER JOIN streams s ON s.service_id = ofm.stream_service_id AND s.url = ofm.stream_url
            WHERE s.uid = :streamId AND ofm.is_available = 1
        )
        """
    )
    abstract fun isDownloadedOfflineBlocking(streamId: Long): Boolean

    // Most recently played distinct streams, newest first. Seeds the recency window and
    // uploader spacing so they hold across app restarts and new queues.
    @Query(
        """
        SELECT s.service_id AS serviceId, s.url AS url, s.uploader AS uploader,
            s.uploader_url AS uploaderUrl
        FROM streams s
        INNER JOIN (
            SELECT stream_id, MAX(access_date) AS last_access
            FROM stream_history
            GROUP BY stream_id
        ) h ON h.stream_id = s.uid
        ORDER BY h.last_access DESC
        LIMIT :limit
        """
    )
    abstract fun recentlyPlayedBlocking(limit: Int): List<RecentlyPlayed>

    // Batched lookups for one adaptive pick. Each replaces a query per candidate (about
    // six per candidate, thirty candidates a pick); none of them changes the schema.

    // Plain blocking lookup; StreamDAO.getStream goes through a Flowable, which sets up and
    // tears down an invalidation observer on every call.
    @Query("SELECT * FROM streams WHERE service_id = :serviceId AND url = :url LIMIT 1")
    abstract fun streamBlocking(serviceId: Int, url: String): StreamEntity?

    // Rating average and outcome totals for several uploaders in one pass. There is no
    // index on streams.uploader, so each per-uploader query scanned the whole table, twice.
    @Query(
        """
        SELECT
            s.uploader AS uploader,
            AVG(s.user_rating) AS avgRating,
            COALESCE(SUM(ps.completion_count), 0) AS completions,
            COALESCE(SUM(ps.skip_count), 0) AS skips
        FROM streams s
        LEFT JOIN playback_statistics ps ON ps.stream_id = s.uid
        WHERE s.uploader IN (:uploaders)
        GROUP BY s.uploader
        """
    )
    abstract fun uploaderAggregatesBlocking(uploaders: List<String>): List<UploaderRow>

    @Query(
        """
        SELECT stream_id AS streamId, skip_count AS skips,
               completion_count AS completions, play_count AS plays,
               forward_seek_count AS forwardSeeks, backward_seek_count AS backwardSeeks,
               pause_resume_count AS pauseResumes, pause_skip_count AS pauseSkips,
               volume_up_count AS volumeUps, volume_down_count AS volumeDowns,
               dropout_count AS leaveCount, dropout_mean AS leaveMean, dropout_m2 AS leaveM2
        FROM playback_statistics
        WHERE stream_id IN (:streamIds)
        """
    )
    abstract fun statisticsBlocking(streamIds: List<Long>): List<StatisticsRow>

    // access_date is stored as epoch milliseconds.
    @Query(
        """
        SELECT stream_id AS streamId, MAX(access_date) AS lastAccessMillis
        FROM stream_history
        WHERE stream_id IN (:streamIds)
        GROUP BY stream_id
        """
    )
    abstract fun lastAccessBlocking(streamIds: List<Long>): List<LastAccessRow>

    @Query(
        """
        SELECT stream_id AS streamId, COUNT(*) AS count
        FROM playlist_stream_join
        WHERE playlist_id IN (SELECT playlist_id FROM playlist_stream_join WHERE stream_id = :previous)
          AND stream_id IN (:streamIds)
        GROUP BY stream_id
        """
    )
    abstract fun coMembershipCountsBlocking(previous: Long, streamIds: List<Long>): List<CountRow>

    @Query(
        """
        SELECT s.uid
        FROM streams s
        INNER JOIN offline_file_mappings ofm
            ON s.service_id = ofm.stream_service_id AND s.url = ofm.stream_url
        WHERE s.uid IN (:streamIds) AND ofm.is_available = 1
        """
    )
    abstract fun offlineAvailableBlocking(streamIds: List<Long>): List<Long>

    data class UploaderRow(val uploader: String, val avgRating: Double?, val completions: Int, val skips: Int)

    data class StatisticsRow(
        val streamId: Long,
        val skips: Int,
        val completions: Int,
        val plays: Int,
        val forwardSeeks: Int = 0,
        val backwardSeeks: Int = 0,
        val pauseResumes: Int = 0,
        val pauseSkips: Int = 0,
        val volumeUps: Int = 0,
        val volumeDowns: Int = 0,
        val leaveCount: Int = 0,
        val leaveMean: Double = 0.0,
        val leaveM2: Double = 0.0
    )

    data class LastAccessRow(val streamId: Long, val lastAccessMillis: Long)

    data class CountRow(val streamId: Long, val count: Int)

    @Query("SELECT uid, uploader FROM streams WHERE uid IN (:streamIds)")
    abstract fun uploadersBlocking(streamIds: List<Long>): List<StreamUploader>

    data class StreamUploader(val uid: Long, val uploader: String?)

    @Query(
        """
        SELECT uid, service_id AS serviceId, url, title, uploader, duration, user_rating AS userRating
        FROM streams WHERE uid IN (:streamIds)
        """
    )
    abstract fun tracksBlocking(streamIds: List<Long>): List<TrackRow>

    data class TrackRow(
        val uid: Long,
        val serviceId: Int,
        val url: String,
        val title: String?,
        val uploader: String?,
        val duration: Long,
        val userRating: Int?
    )

    data class RecentlyPlayed(
        val serviceId: Int,
        val url: String,
        val uploader: String?,
        val uploaderUrl: String? = null
    )
}
