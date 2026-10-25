/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRetagPlannerTest {
    private val tracks = mapOf(
        "a" to LibraryTrack(0, "https://example.com/a", "A", "Artist", 100, null),
        "b" to LibraryTrack(0, "https://example.com/b", "B", "Artist", 100, null)
    )
    private val files = mapOf("a" to "file:///a.m4a", "b" to "file:///b.opus")
    private val collection = PlannedPlaylist("Everything", "everything.m3u8", listOf("a", "b"))
    private val album = PlannedPlaylist("Album", "album.m3u8", listOf("b", "a", "a"))

    @Test
    fun `excluding the broad collection restores album and first track position`() {
        val plan = LibraryRetagPlanner.plan(listOf(collection, album), tracks, files, setOf(collection.fileName))
        assertEquals(2, plan.changes.size)
        assertTrue(plan.skipped.isEmpty())
        assertEquals("Album", plan.changes.first().album)
        assertEquals(2, plan.changes.first { it.key == "a" }.number)
        assertEquals(1, plan.changes.first { it.key == "b" }.number)
    }

    @Test
    fun `ambiguous tracks remain unchanged instead of using the first playlist`() {
        val plan = LibraryRetagPlanner.plan(listOf(collection, album), tracks, files, emptySet())
        assertTrue(plan.changes.isEmpty())
        assertEquals(2, plan.skipped.size)
        assertTrue(plan.skipped.all { it.reason.startsWith("Multiple eligible") })
    }

    @Test
    fun `different playlists with the same name remain ambiguous`() {
        val another = album.copy(fileName = "other.m3u8")
        val plan = LibraryRetagPlanner.plan(listOf(album, another), tracks, files, emptySet())
        assertTrue(plan.changes.isEmpty())
    }

    @Test
    fun `missing files and tracks with no eligible playlist are skipped`() {
        val plan = LibraryRetagPlanner.plan(listOf(album), tracks, files - "a", setOf(album.fileName))
        assertTrue(plan.changes.isEmpty())
        assertEquals("No matched local file", plan.skipped.first { it.key == "a" }.reason)
        assertEquals("No eligible playlist", plan.skipped.first { it.key == "b" }.reason)
    }

    @Test
    fun `a file matched to multiple tracks is never edited`() {
        val plan = LibraryRetagPlanner.plan(listOf(album), tracks, mapOf("a" to "same", "b" to "same"), emptySet())
        assertTrue(plan.changes.isEmpty())
        assertTrue(plan.skipped.all { it.reason.startsWith("Local file matched") })
    }
}
