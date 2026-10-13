/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.playqueue.PlayQueueItem

// Translates play-queue items into the engine's CandidateItems by reading lifetime
// statistics from the database. Blocks the calling thread; runs on the adaptive shuffle
// thread. One row lookup per candidate, then one batched query per kind of fact, and
// uploader aggregates from a short-lived cache.
class CandidateMaterialiser(private val repository: AdaptiveShuffleRepository) {

    fun materialise(
        items: List<PlayQueueItem>,
        previousStreamId: Long? = null
    ): List<CandidateItem> = materialisePairs(items, previousStreamId).map { it.second }

    // Each candidate alongside the queue item it came from. Items never seen before (most
    // of a remote playlist) get a streams row created from the queue item, so they can be
    // scored as cold candidates. Skipping them, as before, meant a remote playlist fell
    // back to playlist order and never-played tracks could not be chosen at all.
    fun materialisePairs(
        items: List<PlayQueueItem>,
        previousStreamId: Long? = null
    ): List<Pair<PlayQueueItem, CandidateItem>> {
        val streams = items.mapNotNull { item -> repository.getOrCreateStream(item)?.let { item to it } }
        if (streams.isEmpty()) return emptyList()
        val ids = streams.map { it.second.uid }
        val facts = repository.candidateFacts(ids, previousStreamId)
        val uploaders = repository.getUploaderStats(streams.mapNotNull { it.second.uploader })
        val nowMillis = System.currentTimeMillis()

        return streams.map { (item, stream) ->
            val streamId = stream.uid
            val stats = facts.statistics[streamId]
            val skipCount = stats?.skips ?: 0
            val completionCount = stats?.completions ?: 0
            val playCount = stats?.plays ?: 0
            val daysSince = facts.lastAccessMillis[streamId]?.let { at ->
                (nowMillis - at).coerceAtLeast(0L) / 86_400_000.0
            }
            val uploaderStats = stream.uploader?.let { uploaders[it] } ?: UploaderStats.UNKNOWN
            item to CandidateItem(
                streamId = streamId,
                durationSeconds = stream.duration,
                userRating = stream.userRating,
                lifetimePlayCount = maxOf(playCount, skipCount + completionCount),
                lifetimeEarlySkips = skipCount,
                lifetimeCompletions = completionCount,
                daysSinceLastAccess = daysSince,
                streamType = mapStreamType(stream.streamType),
                uploader = stream.uploader,
                uploaderAvgRating = uploaderStats.avgRating,
                uploaderCompletionRate = uploaderStats.completionRate,
                uploaderSkipRate = uploaderStats.skipRate,
                playlistCoMembershipWithPrev = if (streamId == previousStreamId) 0 else facts.coMembership[streamId] ?: 0,
                isDownloadedOffline = streamId in facts.offline,
                listenEvents = stats?.let {
                    ListenEventCounts(
                        forwardSeeks = it.forwardSeeks,
                        backwardSeeks = it.backwardSeeks,
                        pauseResumes = it.pauseResumes,
                        pauseSkips = it.pauseSkips,
                        volumeUps = it.volumeUps,
                        volumeDowns = it.volumeDowns,
                        leaveCount = it.leaveCount,
                        leaveMean = it.leaveMean,
                        leaveM2 = it.leaveM2
                    )
                } ?: ListenEventCounts.NONE
            )
        }
    }

    private fun mapStreamType(t: StreamType): StreamTypeFeature = when (t) {
        StreamType.AUDIO_STREAM -> StreamTypeFeature.AUDIO
        StreamType.LIVE_STREAM -> StreamTypeFeature.LIVE_VIDEO
        StreamType.AUDIO_LIVE_STREAM -> StreamTypeFeature.LIVE_AUDIO
        else -> StreamTypeFeature.VIDEO
    }
}
