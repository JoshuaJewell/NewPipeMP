/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.database.adaptive

import androidx.room.Dao
import androidx.room.Query

@Dao
abstract class AdaptiveShuffleFeatureQueriesDAO {

    @Query(
        "SELECT AVG(user_rating) FROM streams " +
            "WHERE uploader = :uploader AND user_rating IS NOT NULL"
    )
    abstract fun averageRatingForUploaderBlocking(uploader: String): Double?

    @Query(
        """
        SELECT
            COALESCE(SUM(ps.completion_count), 0) AS completions,
            COALESCE(SUM(ps.skip_count), 0) AS skips
        FROM streams s
        LEFT JOIN playback_statistics ps ON ps.stream_id = s.uid
        WHERE s.uploader = :uploader
        """
    )
    abstract fun aggregateForUploaderBlocking(uploader: String): UploaderAggregate

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

    data class UploaderAggregate(val completions: Int, val skips: Int)
}
