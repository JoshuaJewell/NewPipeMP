/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamUrlsTest {
    private val watch = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test
    fun youtubeFormsBecomeTheWatchUrl() {
        assertEquals(watch, StreamUrls.canonical(0, watch))
        assertEquals(watch, StreamUrls.canonical(0, "https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(watch, StreamUrls.canonical(0, "https://youtu.be/dQw4w9WgXcQ?t=30"))
        assertEquals(watch, StreamUrls.canonical(0, "https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"))
        assertEquals(watch, StreamUrls.canonical(0, " https://www.youtube.com/shorts/dQw4w9WgXcQ "))
    }

    // Seen in a real library: every track of one Bandcamp artist stored as "//track/",
    // while playback recorded "/track/".
    @Test
    fun repeatedSlashesInThePathCollapse() {
        assertEquals(
            "https://hugothepoet1.bandcamp.com/track/1989",
            StreamUrls.canonical(4, "https://hugothepoet1.bandcamp.com//track/1989")
        )
        assertEquals(
            "https://a.bandcamp.com/track/x?q=a//b#c//d",
            StreamUrls.canonical(4, "https://a.bandcamp.com//track//x?q=a//b#c//d")
        )
    }

    @Test
    fun otherUrlsAreLeftAlone() {
        val sc = "https://soundcloud.com/artist/track"
        assertEquals(sc, StreamUrls.canonical(1, sc))
        assertEquals("not a url", StreamUrls.canonical(1, "not a url"))
        // A YouTube-looking URL under another service is not rewritten to YouTube.
        assertEquals(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            StreamUrls.canonical(3, "https://music.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun filterKeysAgreeAcrossForms() {
        assertEquals(
            CandidateFilter.key(4, "https://a.bandcamp.com/track/x"),
            CandidateFilter.key(4, "https://a.bandcamp.com//track/x")
        )
        assertEquals(CandidateFilter.key(0, watch), CandidateFilter.key(0, "https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun uploaderKeyPrefersTheChannelUrl() {
        assertEquals(
            "https://hugothepoet1.bandcamp.com",
            CandidateFilter.uploaderKey("https://hugothepoet1.bandcamp.com/", "")
        )
        assertEquals(
            CandidateFilter.uploaderKey("https://hugothepoet1.bandcamp.com/", ""),
            CandidateFilter.uploaderKey("https://hugothepoet1.bandcamp.com", "Hugo The Poet")
        )
        assertEquals("Name", CandidateFilter.uploaderKey(" ", "Name"))
        assertNull(CandidateFilter.uploaderKey(null, ""))
    }
}
