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

    @Query(
        """
        UPDATE adaptive_shuffle_pending_events SET
            resolved = 1,
            reward = :reward,
            raw_inputs_blob = :rawInputsBlob,
            resolved_at = :resolvedAt
        WHERE id = :id
        """
    )
    abstract fun resolveWithInputs(id: Long, reward: Double, rawInputsBlob: ByteArray, resolvedAt: Long): Int

    @Query("UPDATE adaptive_shuffle_pending_events SET raw_inputs_blob = :rawInputsBlob WHERE id = :id")
    abstract fun updateRawInputs(id: Long, rawInputsBlob: ByteArray): Int

    @Query("SELECT * FROM adaptive_shuffle_pending_events WHERE id = :id")
    abstract fun getByIdBlocking(id: Long): AdaptiveShufflePendingEventEntity?

    @Query("DELETE FROM adaptive_shuffle_pending_events WHERE id = :id")
    abstract fun deleteById(id: Long): Int

    @Query(
        "SELECT * FROM adaptive_shuffle_pending_events WHERE resolved = 1 ORDER BY chosen_at DESC LIMIT :limit"
    )
    abstract fun getRecentResolvedBlocking(limit: Int): List<AdaptiveShufflePendingEventEntity>

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

    @Query("SELECT * FROM adaptive_shuffle_pending_events ORDER BY chosen_at ASC")
    abstract fun getAllOrderedBlocking(): List<AdaptiveShufflePendingEventEntity>

    @Query("SELECT COUNT(*) FROM adaptive_shuffle_pending_events WHERE resolved = 1")
    abstract fun countResolved(): Maybe<Int>

    @Query(
        """
        SELECT COUNT(*) FROM adaptive_shuffle_pending_events
        WHERE stream_id = :streamId AND resolved = 1 AND chosen_at >= :fromMillis AND chosen_at < :toMillis
        """
    )
    abstract fun countResolvedForStreamBetween(streamId: Long, fromMillis: Long, toMillis: Long): Int

    @Query(
        "SELECT id FROM adaptive_shuffle_pending_events WHERE stream_id = :streamId AND resolved = 0 ORDER BY chosen_at DESC LIMIT 1"
    )
    abstract fun findLatestUnresolvedForStream(streamId: Long): Maybe<Long>

    // Synchronous counterpart for the adaptive-shuffle thread. Room schedules the Maybe
    // above on its query executor; blocking on that executor while a transaction holds
    // the only free connection can deadlock the database.
    @Query(
        "SELECT id FROM adaptive_shuffle_pending_events WHERE stream_id = :streamId AND resolved = 0 ORDER BY chosen_at DESC LIMIT 1"
    )
    abstract fun findLatestUnresolvedForStreamBlocking(streamId: Long): Long?

    @Query("DELETE FROM adaptive_shuffle_pending_events")
    abstract fun deleteAll(): Int
}
