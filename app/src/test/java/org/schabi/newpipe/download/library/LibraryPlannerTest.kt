/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPlannerTest {

    private fun t(id: String, title: String, artist: String = "Artist", url: String = "https://www.youtube.com/watch?v=$id") = LibraryTrack(0, url, title, artist, 200L, null)

    @Test fun `the same video under different urls is one track`() {
        val a = LibraryPlanner.trackKey(0, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(a, LibraryPlanner.trackKey(0, "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RD"))
        assertEquals(a, LibraryPlanner.trackKey(0, "https://youtu.be/dQw4w9WgXcQ?t=30"))
        assertEquals(a, LibraryPlanner.trackKey(0, "https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"))
        assertTrue(a != LibraryPlanner.trackKey(1, "https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test fun `a track in several playlists is downloaded once and listed in each`() {
        val shared = t("aaaaaaaaaaa", "Shared")
        val plan = LibraryPlanner.plan(
            listOf(
                LibraryPlaylist("One", listOf(shared, t("bbbbbbbbbbb", "Only one"))),
                LibraryPlaylist("Two", listOf(t("ccccccccccc", "Only two"), shared))
            ),
            existingKeys = emptySet()
        )
        assertEquals(3, plan.toDownload.size)
        assertEquals(4, plan.totalEntries)
        val sharedPlan = plan.toDownload.single { it.track == shared }
        assertEquals("One", sharedPlan.album)
        assertEquals(1, sharedPlan.trackNumber)
        assertEquals(listOf("0|yt:ccccccccccc", "0|yt:aaaaaaaaaaa"), plan.playlists[1].keys)
    }

    @Test fun `tracks already on the device are not downloaded again but stay listed`() {
        val have = t("aaaaaaaaaaa", "Have")
        val plan = LibraryPlanner.plan(
            listOf(LibraryPlaylist("P", listOf(have, t("bbbbbbbbbbb", "New")))),
            existingKeys = setOf(LibraryPlanner.trackKey(0, have.url))
        )
        assertEquals(listOf("New"), plan.toDownload.map { it.track.title })
        assertEquals(setOf("0|yt:aaaaaaaaaaa"), plan.alreadyHave)
        assertEquals(2, plan.playlists.single().keys.size)
    }

    @Test fun `different tracks with the same name get distinct file names`() {
        val plan = LibraryPlanner.plan(
            listOf(LibraryPlaylist("P", listOf(t("aaaaaaaaaaa", "Intro"), t("bbbbbbbbbbb", "Intro")))),
            existingKeys = emptySet(),
            takenNames = setOf("artist - intro [aaaaaaaaaaa]")
        )
        assertEquals(listOf("Artist - Intro", "Artist - Intro [bbbbbbbbbbb]"), plan.toDownload.map { it.baseName })
    }

    @Test fun `names are made safe and the artist is not repeated`() {
        assertEquals("AC_DC - Back In Black", LibraryPlanner.safeName("AC/DC - Back In Black"))
        assertEquals("a_b_ c", LibraryPlanner.safeName("  a:b?  c.. "))
        assertEquals("Queen - Bohemian Rhapsody", LibraryPlanner.displayName(t("x", "Queen - Bohemian Rhapsody", "Queen")))
        assertEquals("Song", LibraryPlanner.displayName(t("x", "Song", "")))
    }

    @Test fun `playlists with the same name get distinct files`() {
        val plan = LibraryPlanner.plan(
            listOf(LibraryPlaylist("Mix", emptyList()), LibraryPlaylist("Mix", emptyList())),
            existingKeys = emptySet()
        )
        assertEquals(listOf("Mix.m3u8", "Mix (2).m3u8"), plan.playlists.map { it.fileName })
    }

    @Test fun `m3u lists entries with duration and label`() {
        val text = M3u.write("Road", listOf(M3uEntry("A - B.opus", 61, "A - B"), M3uEntry("C.m4a", 0, "C")))
        assertEquals("#EXTM3U\n#PLAYLIST:Road\n#EXTINF:61,A - B\nA - B.opus\n#EXTINF:-1,C\nC.m4a\n", text)
    }

    @Test fun `relative paths between storage documents`() {
        assertEquals("song.m4a", RelativePath.between("primary:Music/NewPipe/Library", "primary:Music/NewPipe/Library/song.m4a"))
        assertEquals("../Old Mix/01 - song.m4a", RelativePath.between("primary:Music/NewPipe/Library", "primary:Music/NewPipe/Old Mix/01 - song.m4a"))
        assertEquals("../x.opus", RelativePath.between("/storage/emulated/0/NewPipe/Library", "/storage/emulated/0/NewPipe/x.opus"))
        assertNull(RelativePath.between("primary:Music/Library", "1234-ABCD:Music/song.m4a"))
    }
}
