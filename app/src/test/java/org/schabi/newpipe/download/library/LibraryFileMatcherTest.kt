/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFileMatcherTest {
    private fun t(
        id: String,
        title: String,
        artist: String = "Artist",
        url: String = "https://www.youtube.com/watch?v=$id"
    ) = LibraryTrack(0, url, title, artist, 200L, null)

    private fun names(vararg tracks: LibraryTrack): Pair<Map<String, LibraryTrack>, Map<String, String>> {
        val plan = LibraryPlanner.plan(listOf(LibraryPlaylist("P", tracks.toList())), emptySet())
        return plan.tracks to plan.toDownload.associate { it.key to it.baseName }
    }

    private fun file(
        name: String,
        uri: String = "file:///music/$name",
        size: Long = 1234L,
        depth: Int = 0,
        inLibraryFolder: Boolean = false
    ) = LocalAudioFile(name, uri, size, depth, inLibraryFolder)

    @Test
    fun `planner names match regardless of audio extension`() {
        val (tracks, plannedNames) = names(t("aaaaaaaaaaa", "Song"))
        val key = LibraryPlanner.trackKey(0, "https://www.youtube.com/watch?v=aaaaaaaaaaa")
        val f = file("Artist - Song.opus", "content://library/song.opus", inLibraryFolder = true)

        val matched = LibraryFileMatcher.match(tracks, plannedNames, listOf(f))

        assertEquals("content://library/song.opus", matched[key])
    }

    @Test
    fun `numbered files from old playlist folders match by title`() {
        val (tracks, plannedNames) = names(t("aaaaaaaaaaa", "Song"))
        val key = LibraryPlanner.trackKey(0, "https://www.youtube.com/watch?v=aaaaaaaaaaa")
        val f = file("01 - Song.m4a", depth = 1)

        assertEquals("file:///music/01 - Song.m4a", LibraryFileMatcher.match(tracks, plannedNames, listOf(f))[key])
    }

    @Test
    fun `a video id disambiguates tracks with the same name`() {
        val first = t("aaaaaaaaaaa", "Song")
        val second = t("bbbbbbbbbbb", "Song")
        val (tracks, plannedNames) = names(first, second)
        val key = LibraryPlanner.trackKey(0, second.url)

        val matched = LibraryFileMatcher.match(
            tracks,
            plannedNames,
            listOf(file("Artist - Song [bbbbbbbbbbb].m4a"))
        )

        assertEquals("file:///music/Artist - Song [bbbbbbbbbbb].m4a", matched[key])
        assertNull(matched[LibraryPlanner.trackKey(0, first.url)])
    }

    @Test
    fun `an ambiguous name without an id matches nothing`() {
        val (tracks, plannedNames) = names(t("aaaaaaaaaaa", "Song"), t("bbbbbbbbbbb", "Song"))

        assertTrue(LibraryFileMatcher.match(tracks, plannedNames, listOf(file("Artist - Song.m4a"))).isEmpty())
    }

    @Test
    fun `extra words in a file name still match a unique track`() {
        val (tracks, plannedNames) = names(t("aaaaaaaaaaa", "Song"))
        val key = LibraryPlanner.trackKey(0, "https://www.youtube.com/watch?v=aaaaaaaaaaa")

        val matched = LibraryFileMatcher.match(
            tracks,
            plannedNames,
            listOf(file("Artist - Song (Official Audio).m4a"))
        )

        assertEquals("file:///music/Artist - Song (Official Audio).m4a", matched[key])
    }

    @Test
    fun `unrelated and empty files are ignored`() {
        val (tracks, plannedNames) = names(t("aaaaaaaaaaa", "Song"))
        val files = listOf(
            file("Something Else Entirely.m4a"),
            file("Artist - Song.m3u8"),
            file("Artist - Song.m4a", size = 0L)
        )

        assertTrue(LibraryFileMatcher.match(tracks, plannedNames, files).isEmpty())
    }

    @Test
    fun `a file in the Library folder wins over an older duplicate`() {
        val (tracks, plannedNames) = names(t("aaaaaaaaaaa", "Song"))
        val key = LibraryPlanner.trackKey(0, "https://www.youtube.com/watch?v=aaaaaaaaaaa")
        val older = file("Artist - Song.m4a", "file:///music/Old/Artist - Song.m4a", depth = 1)
        val library = file("Artist - Song.m4a", "file:///music/Library/Artist - Song.m4a", depth = 1, inLibraryFolder = true)

        val matched = LibraryFileMatcher.match(tracks, plannedNames, listOf(older, library))

        assertEquals("file:///music/Library/Artist - Song.m4a", matched[key])
    }

    @Test
    fun `found names are reserved when planning the missing tracks`() {
        val track = t("aaaaaaaaaaa", "Song")
        val files = listOf(file("Artist - Song.m4a"))

        val plan = LibraryPlanner.plan(
            listOf(LibraryPlaylist("P", listOf(track))),
            existingKeys = emptySet(),
            takenNames = files.map { LibraryFileMatcher.baseName(it.name) }.toSet()
        )

        assertEquals("Artist - Song [aaaaaaaaaaa]", plan.toDownload.single().baseName)
    }

    @Test
    fun `audio extensions are recognised case insensitively`() {
        assertTrue(LibraryFileMatcher.isAudioFile("track.M4A"))
        assertTrue(LibraryFileMatcher.isAudioFile("track.opus"))
        assertFalse(LibraryFileMatcher.isAudioFile("playlist.m3u8"))
        assertFalse(LibraryFileMatcher.isAudioFile("cover.jpg"))
        assertEquals("01 - Song", LibraryFileMatcher.baseName("01 - Song.m4a"))
    }
}
