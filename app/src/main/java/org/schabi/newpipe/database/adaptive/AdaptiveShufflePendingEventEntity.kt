/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.database.adaptive

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = AdaptiveShufflePendingEventEntity.TABLE_NAME,
    indices = [
        Index(value = [AdaptiveShufflePendingEventEntity.RESOLVED]),
        Index(value = [AdaptiveShufflePendingEventEntity.CHOSEN_AT])
    ]
)
data class AdaptiveShufflePendingEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = ID)
    var id: Long = 0L,

    @ColumnInfo(name = STREAM_ID)
    var streamId: Long,

    @ColumnInfo(name = CHOSEN_AT)
    var chosenAt: Long,

    @ColumnInfo(name = FEATURE_VECTOR_BLOB, typeAffinity = ColumnInfo.BLOB)
    var featureVectorBlob: ByteArray,

    @ColumnInfo(name = RAW_INPUTS_BLOB, typeAffinity = ColumnInfo.BLOB)
    var rawInputsBlob: ByteArray,

    @ColumnInfo(name = PREDICTED_REWARD)
    var predictedReward: Double,

    @ColumnInfo(name = UNCERTAINTY)
    var uncertainty: Double,

    @ColumnInfo(name = CANDIDATE_COUNT)
    var candidateCount: Int,

    @ColumnInfo(name = RESOLVED, defaultValue = "0")
    var resolved: Boolean = false,

    @ColumnInfo(name = REWARD)
    var reward: Double? = null,

    @ColumnInfo(name = RESOLVED_AT)
    var resolvedAt: Long? = null
) {
    companion object {
        const val TABLE_NAME = "adaptive_shuffle_pending_events"
        const val ID = "id"
        const val STREAM_ID = "stream_id"
        const val CHOSEN_AT = "chosen_at"
        const val FEATURE_VECTOR_BLOB = "feature_vector_blob"
        const val RAW_INPUTS_BLOB = "raw_inputs_blob"
        const val PREDICTED_REWARD = "predicted_reward"
        const val UNCERTAINTY = "uncertainty"
        const val CANDIDATE_COUNT = "candidate_count"
        const val RESOLVED = "resolved"
        const val REWARD = "reward"
        const val RESOLVED_AT = "resolved_at"
    }
}
