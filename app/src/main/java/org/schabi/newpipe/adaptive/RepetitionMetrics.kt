/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

// Measures what the listener hears, not what the model believes (spec section 7.1).
// Stable weights coexisted with popularity collapse once already; these numbers are the
// ones that would have shown it.
object RepetitionMetrics {

    // offered: the uploaders of the candidates the pick was made from, when known. A pick
    // the listener made themselves offered only itself.
    data class Served(
        val chosenAt: Long,
        val streamId: Long,
        val uploader: String?,
        val offered: List<String?>? = null
    )

    // An uploader's share of what was served against its share of what was on offer.
    data class UploaderShare(val uploader: String, val served: Double, val offered: Double)

    data class TrackRecurrence(val streamId: Long, val uploader: String?, val sessionShare: Double)

    data class Report(
        val windowSize: Int,
        val uniqueTracks: Int,
        val topUploader: String?,
        val topUploaderShare: Double,
        val topTrackId: Long?,
        val topTrackShare: Double,
        // The top uploader's average share of the candidates on offer, over the picks
        // whose candidates are known; null if none are. Serving one uploader 70% of the
        // time is collapse when it was 10% of what was on offer, and no more than fair
        // when the queue was one album.
        val topUploaderOfferedShare: Double?,
        // The uploader served furthest above its share of what was on offer, if any is
        // by more than OVER_SERVED_MARGIN.
        val mostOverServed: UploaderShare?,
        val sessionsConsidered: Int,
        // For the most-served tracks across recent sessions: the fraction of sessions in
        // which each appeared. A track at 0.8 turns up in four sessions out of five.
        val recurringTracks: List<TrackRecurrence>
    ) {
        companion object {
            val EMPTY = Report(0, 0, null, 0.0, null, 0.0, null, null, 0, emptyList())
        }
    }

    // served may be in any order. Only decisions that actually played belong here.
    fun compute(
        served: List<Served>,
        window: Int = AdaptiveShuffleConfig.METRICS_WINDOW,
        sessions: Int = AdaptiveShuffleConfig.METRICS_SESSIONS,
        sessionGapMillis: Long = AdaptiveShuffleConfig.SESSION_GAP_MILLIS,
        topN: Int = 5
    ): Report {
        if (served.isEmpty()) return Report.EMPTY
        val newestFirst = served.sortedByDescending { it.chosenAt }
        val recent = newestFirst.take(window)

        val trackCounts = recent.groupingBy { it.streamId }.eachCount()
        val uploaderCounts = recent.mapNotNull { it.uploader?.takeIf { u -> u.isNotBlank() } }
            .groupingBy { it }.eachCount()
        val topTrack = trackCounts.maxByOrNull { it.value }
        val topUploader = uploaderCounts.maxByOrNull { it.value }

        // Each pick's candidates as shares per uploader, averaged over the picks with any.
        val withOffer = recent.filter { !it.offered.isNullOrEmpty() }
        val offeredShare = HashMap<String, Double>()
        for (s in withOffer) {
            val offered = s.offered!!
            for (u in offered) {
                if (u.isNullOrBlank()) continue
                offeredShare[u] = (offeredShare[u] ?: 0.0) + 1.0 / offered.size / withOffer.size
            }
        }
        val servedOfOffer = withOffer.mapNotNull { it.uploader?.takeIf { u -> u.isNotBlank() } }
            .groupingBy { it }.eachCount()
        val mostOverServed = servedOfOffer.map { (u, n) ->
            UploaderShare(u, n.toDouble() / withOffer.size, offeredShare[u] ?: 0.0)
        }.filter { it.served - it.offered > OVER_SERVED_MARGIN }.maxByOrNull { it.served - it.offered }

        val sessionList = splitSessions(newestFirst, sessionGapMillis).take(sessions)
        val sessionTrackSets = sessionList.map { s -> s.map { it.streamId }.toHashSet() }
        val acrossSessions = sessionList.flatten()
        val uploaderOf = acrossSessions.associate { it.streamId to it.uploader }
        val recurring = acrossSessions.groupingBy { it.streamId }.eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(topN)
            .map { (id, _) ->
                val present = sessionTrackSets.count { id in it }
                TrackRecurrence(id, uploaderOf[id], present.toDouble() / sessionTrackSets.size)
            }

        return Report(
            windowSize = recent.size,
            uniqueTracks = trackCounts.size,
            topUploader = topUploader?.key,
            topUploaderShare = (topUploader?.value ?: 0).toDouble() / recent.size,
            topTrackId = topTrack?.key,
            topTrackShare = (topTrack?.value ?: 0).toDouble() / recent.size,
            topUploaderOfferedShare = topUploader?.key?.takeIf { withOffer.isNotEmpty() }?.let { offeredShare[it] ?: 0.0 },
            mostOverServed = mostOverServed,
            sessionsConsidered = sessionTrackSets.size,
            recurringTracks = recurring
        )
    }

    const val OVER_SERVED_MARGIN: Double = 0.05

    // Input newest first; a gap longer than sessionGapMillis starts a new session.
    fun splitSessions(newestFirst: List<Served>, sessionGapMillis: Long): List<List<Served>> {
        val out = ArrayList<List<Served>>()
        var current = ArrayList<Served>()
        var previousAt: Long? = null
        for (s in newestFirst) {
            if (previousAt != null && previousAt - s.chosenAt > sessionGapMillis) {
                out += current
                current = ArrayList()
            }
            current += s
            previousAt = s.chosenAt
        }
        if (current.isNotEmpty()) out += current
        return out
    }
}
