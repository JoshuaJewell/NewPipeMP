/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.time.OffsetDateTime
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.playqueue.PlayQueueItem

// Translates a play-queue item into the engine's CandidateItem by reading lifetime
// statistics from the database. Blocks the calling thread; intended for use from
// Schedulers.io. Uploader aggregates are cached per call so that 30 candidates from
// the same artist trigger one set of queries rather than thirty.
class CandidateMaterialiser(private val repository: AdaptiveShuffleRepository) {

    fun materialise(
        items: List<PlayQueueItem>,
        previousStreamId: Long? = null
    ): List<CandidateItem> {
        val now = OffsetDateTime.now()
        val uploaderCache = HashMap<String, AdaptiveShuffleRepository.UploaderStats>()
        val out = ArrayList<CandidateItem>(items.size)
        for (item in items) {
            val stream = repository.getStreamForUrl(item.serviceId, item.url) ?: continue
            val streamId = stream.uid
            val stats = repository.getPlaybackStatistics(streamId)
            val lastHistory = repository.getLatestHistoryEntry(streamId)
            val daysSince: Double? = lastHistory?.accessDate?.let { ad ->
                val seconds = java.time.Duration.between(ad, now).seconds.coerceAtLeast(0L)
                seconds.toDouble() / 86_400.0
            }
            val skipCount = stats?.skipCount ?: 0
            val completionCount = stats?.completionCount ?: 0
            val playCount = stats?.playCount ?: 0
            val effectivePlays = maxOf(playCount, skipCount + completionCount)

            val uploaderStats = stream.uploader?.takeIf { it.isNotBlank() }?.let { name ->
                uploaderCache.getOrPut(name) { repository.getUploaderStats(name) }
            } ?: AdaptiveShuffleRepository.UploaderStats(null, null, null)

            val coMembership = if (previousStreamId != null && previousStreamId != streamId) {
                repository.playlistCoMembershipCount(previousStreamId, streamId)
            } else {
                0
            }

            val offline = repository.isDownloadedOffline(streamId)

            out += CandidateItem(
                streamId = streamId,
                durationSeconds = stream.duration,
                userRating = stream.userRating,
                lifetimePlayCount = effectivePlays,
                lifetimeEarlySkips = skipCount,
                lifetimeCompletions = completionCount,
                daysSinceLastAccess = daysSince,
                streamType = mapStreamType(stream.streamType),
                uploader = stream.uploader,
                uploaderAvgRating = uploaderStats.avgRating,
                uploaderCompletionRate = uploaderStats.completionRate,
                uploaderSkipRate = uploaderStats.skipRate,
                playlistCoMembershipWithPrev = coMembership,
                isDownloadedOffline = offline
            )
        }
        return out
    }

    private fun mapStreamType(t: StreamType): StreamTypeFeature = when (t) {
        StreamType.AUDIO_STREAM -> StreamTypeFeature.AUDIO
        StreamType.LIVE_STREAM -> StreamTypeFeature.LIVE_VIDEO
        StreamType.AUDIO_LIVE_STREAM -> StreamTypeFeature.LIVE_AUDIO
        else -> StreamTypeFeature.VIDEO
    }
}
