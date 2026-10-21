/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import org.schabi.newpipe.database.adaptive.AdaptiveShufflePendingEventEntity

internal fun fixtureContext(
    hour: Int = 12,
    origin: QueueOrigin = QueueOrigin.PLAYLIST,
    previousUploader: String? = null,
    sessionPosition: Int = 0
) = DecisionContext(
    hourOfDay = hour,
    dayOfWeek = 2,
    sessionPosition = sessionPosition,
    recentSkipRate = 0.2,
    previousAutoQueued = false,
    previousCompleted = false,
    previousUploader = previousUploader,
    previousStreamId = null,
    queueOrigin = origin
)

internal fun fixtureItem(
    streamId: Long,
    uploader: String? = "U",
    durationSeconds: Long = 240L,
    plays: Int = 0,
    skips: Int = 0,
    completions: Int = 0,
    uploaderCompletionRate: Double? = null,
    userRating: Int? = null
) = CandidateItem(
    streamId = streamId,
    durationSeconds = durationSeconds,
    userRating = userRating,
    lifetimePlayCount = plays,
    lifetimeEarlySkips = skips,
    lifetimeCompletions = completions,
    daysSinceLastAccess = null,
    streamType = StreamTypeFeature.AUDIO,
    uploader = uploader,
    uploaderCompletionRate = uploaderCompletionRate
)

// In-memory AdaptiveShuffleStore, so the engine can be exercised off-device.
internal class InMemoryStore : AdaptiveShuffleStore {
    var model: AdaptiveShuffleStore.StoredModel? = null
    val events = LinkedHashMap<Long, AdaptiveShufflePendingEventEntity>()
    private var nextId = 1L

    override fun loadModel() = model
    override fun saveModel(linucbBlob: ByteArray, scalerBlob: ByteArray, nEvents: Long, schemaHash: String, now: Long) {
        model = AdaptiveShuffleStore.StoredModel(linucbBlob, scalerBlob, nEvents, schemaHash)
    }
    override fun insertPending(event: AdaptiveShufflePendingEventEntity): Long {
        val id = nextId++
        event.id = id
        events[id] = event
        return id
    }
    override fun getPendingById(id: Long) = events[id]
    override fun findLatestUnresolvedForStream(streamId: Long): Long? = events.values.filter { it.streamId == streamId && !it.resolved }.maxByOrNull { it.chosenAt }?.id
    override fun resolvePending(id: Long, reward: Double, rawInputsBlob: ByteArray, resolvedAt: Long) {
        val e = events[id] ?: return
        e.resolved = true
        e.reward = reward
        e.rawInputsBlob = rawInputsBlob
        e.resolvedAt = resolvedAt
    }
    override fun updateRawInputs(id: Long, rawInputsBlob: ByteArray) {
        events[id]?.rawInputsBlob = rawInputsBlob
    }
    override fun deletePending(id: Long) {
        events.remove(id)
    }
    override fun getRecent(limit: Int) = events.values.sortedByDescending { it.chosenAt }.take(limit)
    override fun getRecentResolved(limit: Int) = events.values.filter { it.resolved }.sortedByDescending { it.chosenAt }.take(limit)
    override fun getAllResolvedOrdered() = events.values.filter { it.resolved }.sortedBy { it.chosenAt }
    override fun countResolvedForStreamBetween(streamId: Long, fromMillis: Long, toMillis: Long) = events.values.count { it.streamId == streamId && it.resolved && it.chosenAt >= fromMillis && it.chosenAt < toMillis }
    override fun getStreamTitle(streamId: Long): String? = "track $streamId"
    val uploaders = HashMap<Long, String?>()
    override fun getStreamUploaders(streamIds: Collection<Long>): Map<Long, String?> = streamIds.filter { it in uploaders }.associateWith { uploaders[it] }
}

// Monotonic fake clock; each call advances by stepMillis.
internal class FakeClock(var now: Long = 1_000_000L, private val stepMillis: Long = 1_000L) : () -> Long {
    override fun invoke(): Long {
        now += stepMillis
        return now
    }
}
