/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

// What of a track one listen actually covered, built from where playback started, every
// seek and where it was left (spec section 4.2). The position a track is left at says
// little on its own: seeking to the last ten seconds and letting it end looked like a
// full listen, and a track resumed halfway and finished looked like half of one.
//
// Coverage is the share of the track heard at least once, measured from where the listen
// began. Rewinds are counted by episode: a run of seeks with less than a few seconds of
// listening between them is one gesture (tapping rewind three times is one rewind), and
// only its net movement counts.
class ListenTrace {
    // Heard spans [start, end) of the track, kept sorted and disjoint.
    private val spans = ArrayList<LongArray>()
    private var origin: Long = -1L
    private var segmentStart: Long = -1L
    private var heard: Long = 0L

    // The seek episode in progress: where it began, and listening since its last seek.
    private var episodeFrom: Long = -1L
    private var episodeTo: Long = -1L
    private var heardAtLastSeek: Long = 0L

    var restarts: Int = 0
        private set
    var partialRewinds: Int = 0
        private set

    val heardMillis: Long get() = heard

    // Heard so far, counting the span still playing up to positionMillis.
    fun heardAt(positionMillis: Long): Long {
        val open = if (segmentStart >= 0L && positionMillis > segmentStart) positionMillis - segmentStart else 0L
        return heard + open
    }

    val hasStarted: Boolean get() = origin >= 0L

    // Playback (re)started at positionMillis: the first call fixes the listen's origin.
    fun start(positionMillis: Long) {
        val p = positionMillis.coerceAtLeast(0L)
        if (origin < 0L) origin = p
        if (segmentStart < 0L) segmentStart = p
    }

    fun seek(fromMillis: Long, toMillis: Long) {
        if (origin < 0L) {
            start(toMillis)
            return
        }
        closeSegment(fromMillis)
        if (episodeFrom >= 0L && heard - heardAtLastSeek >= EPISODE_GAP_MILLIS) endEpisode()
        if (episodeFrom < 0L) episodeFrom = fromMillis
        episodeTo = toMillis.coerceAtLeast(0L)
        heardAtLastSeek = heard
        segmentStart = episodeTo
    }

    // The listen ended (or paused for good) at positionMillis.
    fun close(positionMillis: Long) {
        closeSegment(positionMillis)
        endEpisode()
    }

    // Share of [origin, duration) heard at least once, in [0, 1]. Null when the listen
    // began too close to the end for coverage to mean anything.
    fun coverage(durationMillis: Long): Double? {
        if (origin < 0L || durationMillis <= 0L) return null
        val span = durationMillis - origin.coerceAtMost(durationMillis)
        if (span < MIN_SPAN_MILLIS) return null
        var covered = 0L
        for (s in spans) {
            val a = s[0].coerceIn(origin, durationMillis)
            val b = s[1].coerceIn(origin, durationMillis)
            if (b > a) covered += b - a
        }
        return (covered.toDouble() / span).coerceIn(0.0, 1.0)
    }

    private fun closeSegment(endMillis: Long) {
        val start = segmentStart
        segmentStart = -1L
        if (start < 0L || endMillis <= start) return
        heard += endMillis - start
        addSpan(start, endMillis)
    }

    private fun addSpan(start: Long, end: Long) {
        var a = start
        var b = end
        val kept = ArrayList<LongArray>(spans.size + 1)
        for (s in spans) {
            if (s[1] < a || s[0] > b) {
                kept += s
            } else {
                a = minOf(a, s[0])
                b = maxOf(b, s[1])
            }
        }
        kept += longArrayOf(a, b)
        kept.sortBy { it[0] }
        spans.clear()
        spans.addAll(kept)
    }

    private fun endEpisode() {
        if (episodeFrom < 0L) return
        val back = episodeFrom - episodeTo
        if (back >= REWIND_MIN_MILLIS) {
            if (episodeTo < RESTART_TO_MILLIS && episodeFrom > RESTART_FROM_MILLIS) {
                restarts += 1
            } else {
                partialRewinds += 1
            }
        }
        episodeFrom = -1L
        episodeTo = -1L
    }

    companion object {
        // A backward move shorter than this is scrubbing or catching a word, not a replay.
        const val REWIND_MIN_MILLIS: Long = 10_000L

        // Seeks closer together than this, in listening time, are one gesture.
        const val EPISODE_GAP_MILLIS: Long = 3_000L

        // The player's own definition of a restart: from past 10 s to before 5 s.
        const val RESTART_FROM_MILLIS: Long = 10_000L
        const val RESTART_TO_MILLIS: Long = 5_000L

        // A listen resumed with less than this left says nothing about the track.
        const val MIN_SPAN_MILLIS: Long = 30_000L
    }
}
