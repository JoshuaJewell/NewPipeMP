/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.database.adaptive

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = AdaptiveShuffleModelStateEntity.TABLE_NAME)
data class AdaptiveShuffleModelStateEntity(
    @PrimaryKey
    @ColumnInfo(name = ID)
    var id: Int = SINGLETON_ID,

    @ColumnInfo(name = MODEL_VERSION)
    var modelVersion: Int,

    @ColumnInfo(name = FEATURE_SCHEMA_HASH)
    var featureSchemaHash: String,

    @ColumnInfo(name = LINUCB_BLOB, typeAffinity = ColumnInfo.BLOB)
    var linucbBlob: ByteArray,

    @ColumnInfo(name = SCALER_BLOB, typeAffinity = ColumnInfo.BLOB)
    var scalerBlob: ByteArray,

    @ColumnInfo(name = N_EVENTS)
    var nEvents: Long,

    @ColumnInfo(name = UPDATED_AT)
    var updatedAt: Long
) {
    companion object {
        const val TABLE_NAME = "adaptive_shuffle_model_state"
        const val ID = "id"
        const val MODEL_VERSION = "model_version"
        const val FEATURE_SCHEMA_HASH = "feature_schema_hash"
        const val LINUCB_BLOB = "linucb_blob"
        const val SCALER_BLOB = "scaler_blob"
        const val N_EVENTS = "n_events"
        const val UPDATED_AT = "updated_at"
        const val SINGLETON_ID = 1
        const val CURRENT_MODEL_VERSION = 1
    }
}
