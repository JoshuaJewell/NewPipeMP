/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.database.adaptive

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe

@Dao
abstract class AdaptiveShufflePendingEventDAO {

    @Insert
    abstract fun insert(event: AdaptiveShufflePendingEventEntity): Long

    @Query(
        """
        UPDATE adaptive_shuffle_pending_events SET
            resolved = 1,
            reward = :reward,
            resolved_at = :resolvedAt
        WHERE id = :id
        """
    )
    abstract fun resolve(id: Long, reward: Double, resolvedAt: Long): Int

    @Query("SELECT * FROM adaptive_shuffle_pending_events ORDER BY chosen_at DESC LIMIT :limit")
    abstract fun getRecent(limit: Int): Flowable<List<AdaptiveShufflePendingEventEntity>>

    @Query("SELECT * FROM adaptive_shuffle_pending_events ORDER BY chosen_at DESC LIMIT :limit")
    abstract fun getRecentBlocking(limit: Int): List<AdaptiveShufflePendingEventEntity>

    @Query(
        "SELECT * FROM adaptive_shuffle_pending_events WHERE resolved = 0 ORDER BY chosen_at ASC LIMIT :limit"
    )
    abstract fun getUnresolved(limit: Int = 100): Maybe<List<AdaptiveShufflePendingEventEntity>>

    @Query(
        "SELECT * FROM adaptive_shuffle_pending_events WHERE resolved = 1 ORDER BY chosen_at ASC"
    )
    abstract fun getAllResolvedOrdered(): Maybe<List<AdaptiveShufflePendingEventEntity>>

    @Query(
        "SELECT * FROM adaptive_shuffle_pending_events WHERE resolved = 1 ORDER BY chosen_at ASC"
    )
    abstract fun getAllResolvedOrderedBlocking(): List<AdaptiveShufflePendingEventEntity>

    @Query("SELECT COUNT(*) FROM adaptive_shuffle_pending_events WHERE resolved = 1")
    abstract fun countResolved(): Maybe<Int>

    @Query(
        "SELECT id FROM adaptive_shuffle_pending_events WHERE stream_id = :streamId AND resolved = 0 ORDER BY chosen_at DESC LIMIT 1"
    )
    abstract fun findLatestUnresolvedForStream(streamId: Long): Maybe<Long>

    @Query("DELETE FROM adaptive_shuffle_pending_events")
    abstract fun deleteAll(): Int
}
