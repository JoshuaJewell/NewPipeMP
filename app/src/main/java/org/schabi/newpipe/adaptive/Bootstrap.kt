/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.time.OffsetDateTime
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType

// One-shot backfill that primes the LinUCB matrices from historic playback
// statistics. Approximate: historical events lack queue-origin context and per-access
// progress, so we synthesise one labelled example per stream from its aggregate stats.
object Bootstrap {

    fun runIfNeeded(database: AppDatabase, engine: AdaptiveShuffleEngine, repository: AdaptiveShuffleRepository): Int {
        if (engine.numEvents() > 0L) return 0
        val streams = database.streamDAO().getAll().blockingFirst(emptyList())
        if (streams.isEmpty()) return 0

        val freshLinucb = LinUCB(FeatureSchema.DIMENSION)
        val freshScaler = FeatureScaler(FeatureSchema.scaledIndices)
        val now = OffsetDateTime.now()
        var ingested = 0L
        val uploaderCache = HashMap<String, AdaptiveShuffleRepository.UploaderStats>()

        for (stream in streams) {
            val stats = repository.getPlaybackStatistics(stream.uid) ?: continue
            val plays = stats.skipCount + stats.completionCount
            if (plays <= 0) continue

            val completionRatio = stats.completionCount.toDouble() / plays
            val skipRatio = stats.skipCount.toDouble() / plays
            // Approximate reward in the same range the live RewardCalculator emits: a
            // completion bias minus a heavier early-skip penalty, plus a token rewind bonus.
            val rewindBonus = minOf(stats.restartCount.toDouble(), AdaptiveShuffleConfig.REWIND_CAP) *
                AdaptiveShuffleConfig.REWARD_REWIND_DENSITY / plays.coerceAtLeast(1)
            val reward = AdaptiveShuffleConfig.REWARD_COMPLETION * completionRatio +
                AdaptiveShuffleConfig.REWARD_EARLY_SKIP * skipRatio + rewindBonus

            val lastHistory = repository.getLatestHistoryEntry(stream.uid)
            val context = synthesisedContext(lastHistory?.accessDate ?: now)
            val uploaderStats = stream.uploader?.takeIf { it.isNotBlank() }?.let { name ->
                uploaderCache.getOrPut(name) { repository.getUploaderStats(name) }
            } ?: AdaptiveShuffleRepository.UploaderStats(null, null, null)
            val offline = repository.isDownloadedOffline(stream.uid)
            val item = synthesisedItem(
                stream,
                stats.skipCount,
                stats.completionCount,
                plays,
                lastHistory?.accessDate,
                now,
                uploaderStats,
                offline
            )

            val rawV = FeatureExtractor.buildRawVector(context, item)
            freshScaler.observe(rawV)
            val scaledV = freshScaler.transform(rawV)
            freshLinucb.update(scaledV, reward)
            ingested += 1L
        }

        if (ingested == 0L) return 0
        engine.replaceWith(freshLinucb, freshScaler, ingested)
        return ingested.toInt()
    }

    private fun synthesisedContext(at: OffsetDateTime): DecisionContext = DecisionContext(
        hourOfDay = at.hour,
        dayOfWeek = (at.dayOfWeek.value - 1).coerceIn(0, 6),
        sessionPosition = 0,
        recentSkipRate = AdaptiveShuffleConfig.COLD_START_SKIP_RATIO,
        previousAutoQueued = false,
        previousCompleted = false,
        previousUploader = null,
        previousStreamId = null,
        queueOrigin = QueueOrigin.OTHER
    )

    private fun synthesisedItem(
        stream: StreamEntity,
        skips: Int,
        completions: Int,
        plays: Int,
        lastAccess: OffsetDateTime?,
        now: OffsetDateTime,
        uploaderStats: AdaptiveShuffleRepository.UploaderStats,
        offline: Boolean
    ): CandidateItem {
        val daysSince: Double? = lastAccess?.let { ad ->
            (java.time.Duration.between(ad, now).seconds.coerceAtLeast(0L)).toDouble() / 86_400.0
        }
        return CandidateItem(
            streamId = stream.uid,
            durationSeconds = stream.duration,
            userRating = stream.userRating,
            lifetimePlayCount = plays,
            lifetimeEarlySkips = skips,
            lifetimeCompletions = completions,
            daysSinceLastAccess = daysSince,
            streamType = mapStreamType(stream.streamType),
            uploader = stream.uploader,
            uploaderAvgRating = uploaderStats.avgRating,
            uploaderCompletionRate = uploaderStats.completionRate,
            uploaderSkipRate = uploaderStats.skipRate,
            playlistCoMembershipWithPrev = 0,
            isDownloadedOffline = offline
        )
    }

    private fun mapStreamType(t: StreamType): StreamTypeFeature = when (t) {
        StreamType.AUDIO_STREAM -> StreamTypeFeature.AUDIO
        StreamType.LIVE_STREAM -> StreamTypeFeature.LIVE_VIDEO
        StreamType.AUDIO_LIVE_STREAM -> StreamTypeFeature.LIVE_AUDIO
        else -> StreamTypeFeature.VIDEO
    }
}
