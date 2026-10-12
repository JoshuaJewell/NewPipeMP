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
//
// Two safeguards, both added after the v1 bootstrap was found to dominate the model:
//   - Each synthetic example is weighted at BOOTSTRAP_WEIGHT of a real decision. With
//     ~1500 played streams and a few hundred real decisions, unweighted synthetic rows
//     outvoted real evidence several times over.
//   - The synthetic reward is computed from the track's own completion and skip counts,
//     so those counts are withheld from the features (the item is presented as cold).
//     Otherwise the prior simply learns that completion_ratio predicts itself.
object Bootstrap {

    fun runIfNeeded(database: AppDatabase, engine: AdaptiveShuffleEngine, repository: AdaptiveShuffleRepository): Int {
        if (engine.numEvents() > 0L) return 0
        val streams = database.streamDAO().getAll().blockingFirst(emptyList())
        if (streams.isEmpty()) return 0

        val freshLinucb = engine.newModel()
        val freshScaler = FeatureScaler(FeatureSchema.scaledIndices)
        val now = OffsetDateTime.now()
        var ingested = 0L
        val uploaderCache = HashMap<String, AdaptiveShuffleRepository.UploaderStats>()

        for (stream in streams) {
            val stats = repository.getPlaybackStatistics(stream.uid) ?: continue
            val plays = stats.skipCount + stats.completionCount
            if (plays <= 0) continue

            val completionShare = stats.completionCount.toDouble() / plays
            val skipShare = stats.skipCount.toDouble() / plays
            // Mean reward per play under the live reward, assuming a completion listens to
            // the end and a skip leaves around a third of the way in (skip position is
            // not recorded historically), plus a token rewind bonus.
            val rewindBonus = minOf(stats.restartCount.toDouble(), AdaptiveShuffleConfig.REWIND_CAP) *
                AdaptiveShuffleConfig.REWARD_REWIND_DENSITY / plays.coerceAtLeast(1)
            val reward = completionShare * RewardCalculator.fromRatio(1.0, 0) +
                skipShare * RewardCalculator.fromRatio(ASSUMED_SKIP_RATIO, 0) + rewindBonus

            val lastHistory = repository.getLatestHistoryEntry(stream.uid)
            val context = synthesisedContext(lastHistory?.accessDate ?: now)
            val uploaderStats = stream.uploader?.takeIf { it.isNotBlank() }?.let { name ->
                uploaderCache.getOrPut(name) { repository.getUploaderStats(name) }
            } ?: AdaptiveShuffleRepository.UploaderStats(null, null, null)
            val offline = repository.isDownloadedOffline(stream.uid)
            // Outcome counts withheld: see the class comment.
            val item = synthesisedItem(
                stream,
                0,
                0,
                0,
                lastHistory?.accessDate,
                now,
                uploaderStats,
                offline
            )

            val rawV = FeatureExtractor.buildRawVector(context, item)
            freshScaler.observe(rawV)
            val scaledV = freshScaler.transform(rawV)
            freshLinucb.update(scaledV, reward, AdaptiveShuffleConfig.BOOTSTRAP_WEIGHT)
            ingested += 1L
        }

        if (ingested == 0L) return 0
        // The event count reports real decisions only; the prior is not evidence.
        engine.replaceWith(freshLinucb, freshScaler, 0L)
        return ingested.toInt()
    }

    private const val ASSUMED_SKIP_RATIO = 0.33

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
