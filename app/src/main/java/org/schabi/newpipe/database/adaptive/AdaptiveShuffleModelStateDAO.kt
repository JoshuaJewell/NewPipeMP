/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.database.adaptive

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.reactivex.rxjava3.core.Maybe

@Dao
abstract class AdaptiveShuffleModelStateDAO {

    @Query("SELECT * FROM adaptive_shuffle_model_state WHERE id = :id")
    abstract fun get(id: Int = AdaptiveShuffleModelStateEntity.SINGLETON_ID): Maybe<AdaptiveShuffleModelStateEntity>

    @Query("SELECT * FROM adaptive_shuffle_model_state WHERE id = :id")
    abstract fun getBlocking(id: Int = AdaptiveShuffleModelStateEntity.SINGLETON_ID): AdaptiveShuffleModelStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsert(state: AdaptiveShuffleModelStateEntity): Long

    @Query("DELETE FROM adaptive_shuffle_model_state")
    abstract fun deleteAll(): Int
}
