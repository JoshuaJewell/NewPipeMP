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
class AdaptiveShuffleRepository(database: AppDatabase) {
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

    fun getStreamTitle(streamId: Long): String? = streamDao.getTitleByIdBlocking(streamId)

    fun getRestartCount(streamId: Long): Int = playbackStatsDao.getStatistics(streamId).blockingGet()?.restartCount ?: 0

    fun getPlaybackStatistics(streamId: Long): org.schabi.newpipe.database.playback.model.PlaybackStatisticsEntity? = playbackStatsDao.getStatistics(streamId).blockingGet()

    fun getLatestHistoryEntry(streamId: Long): org.schabi.newpipe.database.history.model.StreamHistoryEntity? = historyDao.getLatestEntry(streamId)

    data class LoadedModelState(
        val linucb: LinUCB,
        val scaler: FeatureScaler,
        val nEvents: Long,
        val featureSchemaHash: String
    )

    fun loadModel(): LoadedModelState? {
        val row = modelDao.getBlocking() ?: return null
        return LoadedModelState(
            linucb = LinUCB.fromByteArray(row.linucbBlob),
            scaler = FeatureScaler.fromByteArray(row.scalerBlob),
            nEvents = row.nEvents,
            featureSchemaHash = row.featureSchemaHash
        )
    }

    fun saveModel(linucb: LinUCB, scaler: FeatureScaler, nEvents: Long, now: Long) {
        val row = AdaptiveShuffleModelStateEntity(
            id = AdaptiveShuffleModelStateEntity.SINGLETON_ID,
            modelVersion = AdaptiveShuffleModelStateEntity.CURRENT_MODEL_VERSION,
            featureSchemaHash = FeatureSchema.schemaHash,
            linucbBlob = linucb.toByteArray(),
            scalerBlob = scaler.toByteArray(),
            nEvents = nEvents,
            updatedAt = now
        )
        modelDao.upsert(row)
    }

    fun insertPending(event: AdaptiveShufflePendingEventEntity): Long = pendingDao.insert(event)

    fun resolvePending(id: Long, reward: Double, resolvedAt: Long): Int = pendingDao.resolve(id, reward, resolvedAt)

    fun findLatestUnresolvedForStream(streamId: Long): Long? = pendingDao.findLatestUnresolvedForStream(streamId).blockingGet()

    fun getRecent(limit: Int): List<AdaptiveShufflePendingEventEntity> = pendingDao.getRecentBlocking(limit)

    fun getAllResolvedOrdered(): List<AdaptiveShufflePendingEventEntity> = pendingDao.getAllResolvedOrderedBlocking()

    fun clearAll() {
        pendingDao.deleteAll()
        modelDao.deleteAll()
    }
}
