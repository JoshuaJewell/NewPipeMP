/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

// One URL per track. A playlist can hold a track under a different URL from the one the
// extractor reports when it plays (music.youtube.com against www.youtube.com, or an old
// Bandcamp import's "//track/" path). History and statistics are written against the
// played URL, so a decision made on the playlist's URL never resolved and its candidate
// always looked unplayed: the model never learnt anything about those tracks, and their
// uncertainty, never shrinking, kept winning picks.
object StreamUrls {
    private const val YOUTUBE_SERVICE_ID = 0
    private val YOUTUBE_ID = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})""")
    private val SCHEME_AND_HOST = Regex("""^([a-z][a-z0-9+.-]*://[^/?#]*)(.*)$""", RegexOption.IGNORE_CASE)
    private val REPEATED_SLASHES = Regex("/{2,}")

    // YouTube URLs become the watch URL the extractor reports. Elsewhere, repeated slashes
    // in the path collapse to one; the query and fragment are kept as they are.
    @JvmStatic
    fun canonical(serviceId: Int, url: String): String {
        val trimmed = url.trim()
        if (serviceId == YOUTUBE_SERVICE_ID) {
            youtubeId(trimmed)?.let { return "https://www.youtube.com/watch?v=$it" }
        }
        val m = SCHEME_AND_HOST.matchEntire(trimmed) ?: return trimmed
        val rest = m.groupValues[2]
        val cut = rest.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val path = rest.substring(0, cut).replace(REPEATED_SLASHES, "/")
        return m.groupValues[1] + path + rest.substring(cut)
    }

    @JvmStatic
    fun youtubeId(url: String): String? {
        val lower = url.lowercase()
        if ("youtube.com" !in lower && "youtu.be" !in lower) return null
        return YOUTUBE_ID.find(url)?.groupValues?.get(1)
    }
}
