/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.adaptive.AdaptiveShuffleModelStateEntity
import org.schabi.newpipe.database.adaptive.AdaptiveShufflePendingEventEntity

// Thin wrapper around the adaptive-shuffle DAOs. Synchronous: the engine drives this
// from the existing IO scheduler, so RxJava wrapping at this layer would add nothing.
class AdaptiveShuffleRepository(database: AppDatabase) : AdaptiveShuffleStore {
    private val modelDao = database.adaptiveShuffleModelStateDAO()
    private val pendingDao = database.adaptiveShufflePendingEventDAO()
    private val streamDao = database.streamDAO()
    private val historyDao = database.streamHistoryDAO()
    private val playbackStatsDao = database.playbackStatisticsDAO()
    private val featureQueriesDao = database.adaptiveShuffleFeatureQueriesDAO()

    data class UploaderStats(
        val avgRating: Double?,
        val completionRate: Double?,
        val skipRate: Double?
    )

    // Empty-uploader strings are common in the streams table; treat them as missing.
    fun getUploaderStats(uploader: String?): UploaderStats {
        if (uploader.isNullOrBlank()) return UploaderStats(null, null, null)
        val avg = featureQueriesDao.averageRatingForUploaderBlocking(uploader)
        val agg = featureQueriesDao.aggregateForUploaderBlocking(uploader)
        val total = agg.completions + agg.skips
        val completionRate = if (total > 0) agg.completions.toDouble() / total else null
        val skipRate = if (total > 0) agg.skips.toDouble() / total else null
        return UploaderStats(avg, completionRate, skipRate)
    }

    fun playlistCoMembershipCount(streamIdA: Long, streamIdB: Long): Int = if (streamIdA <= 0L || streamIdB <= 0L) {
        0
    } else {
        featureQueriesDao.coMembershipCountBlocking(streamIdA, streamIdB)
    }

    fun isDownloadedOffline(streamId: Long): Boolean = streamId > 0L && featureQueriesDao.isDownloadedOfflineBlocking(streamId)

    fun getStreamForUrl(serviceId: Int, url: String): org.schabi.newpipe.database.stream.model.StreamEntity? = streamDao.getStream(serviceId.toLong(), url).blockingFirst(emptyList()).firstOrNull()

    fun getOrCreateStream(item: org.schabi.newpipe.player.playqueue.PlayQueueItem): org.schabi.newpipe.database.stream.model.StreamEntity? {
        getStreamForUrl(item.serviceId, item.url)?.let { return it }
        return try {
            val entity = org.schabi.newpipe.database.stream.model.StreamEntity(item)
            entity.uid = streamDao.upsert(entity)
            entity
        } catch (t: RuntimeException) {
            null
        }
    }

    override fun getStreamTitle(streamId: Long): String? = streamDao.getTitleByIdBlocking(streamId)

    fun recentlyPlayed(limit: Int) = featureQueriesDao.recentlyPlayedBlocking(limit)

    fun getRestartCount(streamId: Long): Int = playbackStatsDao.getStatistics(streamId).blockingGet()?.restartCount ?: 0

    fun getPlaybackStatistics(streamId: Long): org.schabi.newpipe.database.playback.model.PlaybackStatisticsEntity? = playbackStatsDao.getStatistics(streamId).blockingGet()

    fun getLatestHistoryEntry(streamId: Long): org.schabi.newpipe.database.history.model.StreamHistoryEntity? = historyDao.getLatestEntry(streamId)

    override fun loadModel(): AdaptiveShuffleStore.StoredModel? {
        val row = modelDao.getBlocking() ?: return null
        return AdaptiveShuffleStore.StoredModel(
            linucbBlob = row.linucbBlob,
            scalerBlob = row.scalerBlob,
            nEvents = row.nEvents,
            featureSchemaHash = row.featureSchemaHash
        )
    }

    override fun saveModel(
        linucbBlob: ByteArray,
        scalerBlob: ByteArray,
        nEvents: Long,
        schemaHash: String,
        now: Long
    ) {
        val row = AdaptiveShuffleModelStateEntity(
            id = AdaptiveShuffleModelStateEntity.SINGLETON_ID,
            modelVersion = AdaptiveShuffleModelStateEntity.CURRENT_MODEL_VERSION,
            featureSchemaHash = schemaHash,
            linucbBlob = linucbBlob,
            scalerBlob = scalerBlob,
            nEvents = nEvents,
            updatedAt = now
        )
        modelDao.upsert(row)
    }

    override fun insertPending(event: AdaptiveShufflePendingEventEntity): Long = pendingDao.insert(event)

    override fun getPendingById(id: Long): AdaptiveShufflePendingEventEntity? = pendingDao.getByIdBlocking(id)

    override fun findLatestUnresolvedForStream(streamId: Long): Long? =
        pendingDao.findLatestUnresolvedForStream(streamId).blockingGet()

    override fun resolvePending(id: Long, reward: Double, rawInputsBlob: ByteArray, resolvedAt: Long) {
        pendingDao.resolveWithInputs(id, reward, rawInputsBlob, resolvedAt)
    }

    override fun updateRawInputs(id: Long, rawInputsBlob: ByteArray) {
        pendingDao.updateRawInputs(id, rawInputsBlob)
    }

    override fun deletePending(id: Long) {
        pendingDao.deleteById(id)
    }

    override fun getRecent(limit: Int): List<AdaptiveShufflePendingEventEntity> = pendingDao.getRecentBlocking(limit)

    override fun getRecentResolved(limit: Int): List<AdaptiveShufflePendingEventEntity> =
        pendingDao.getRecentResolvedBlocking(limit)

    override fun getAllResolvedOrdered(): List<AdaptiveShufflePendingEventEntity> =
        pendingDao.getAllResolvedOrderedBlocking()

    fun clearAll() {
        pendingDao.deleteAll()
        modelDao.deleteAll()
    }
}
