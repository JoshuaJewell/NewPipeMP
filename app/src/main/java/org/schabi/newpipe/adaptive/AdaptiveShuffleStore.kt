/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import org.schabi.newpipe.database.adaptive.AdaptiveShufflePendingEventEntity

// What the engine needs from persistence. AdaptiveShuffleRepository implements it over
// Room; tests implement it in memory, which is what lets the engine be tested off-device.
interface AdaptiveShuffleStore {

    data class StoredModel(
        val linucbBlob: ByteArray,
        val scalerBlob: ByteArray,
        val nEvents: Long,
        val featureSchemaHash: String
    )

    fun loadModel(): StoredModel?
    fun saveModel(linucbBlob: ByteArray, scalerBlob: ByteArray, nEvents: Long, schemaHash: String, now: Long)

    fun insertPending(event: AdaptiveShufflePendingEventEntity): Long
    fun getPendingById(id: Long): AdaptiveShufflePendingEventEntity?
    fun findLatestUnresolvedForStream(streamId: Long): Long?
    fun resolvePending(id: Long, reward: Double, rawInputsBlob: ByteArray, resolvedAt: Long)
    fun updateRawInputs(id: Long, rawInputsBlob: ByteArray)
    fun deletePending(id: Long)
    fun getRecent(limit: Int): List<AdaptiveShufflePendingEventEntity>
    fun getRecentResolved(limit: Int): List<AdaptiveShufflePendingEventEntity>
    fun getAllResolvedOrdered(): List<AdaptiveShufflePendingEventEntity>

    // Resolved decisions of streamId chosen in [fromMillis, toMillis).
    fun countResolvedForStreamBetween(streamId: Long, fromMillis: Long, toMillis: Long): Int

    fun getStreamTitle(streamId: Long): String?
}
