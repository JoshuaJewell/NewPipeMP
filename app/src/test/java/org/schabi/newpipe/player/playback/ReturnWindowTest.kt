/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnWindowTest {
    private class Track(val name: String)

    private val a = Track("a")
    private val b = Track("b")
    private val c = Track("c")

    @Test fun `skipping and coming straight back resumes the listen and makes a detour`() {
        val w = ReturnWindow<Track, String>()
        assertNull((w.leave(a, "a-skip", b) as ReturnWindow.Outcome.Held).released)
        val back = w.leave(b, "b-brief", a)
        assertTrue(back is ReturnWindow.Outcome.Returned)
        back as ReturnWindow.Outcome.Returned
        assertEquals("a-skip", back.resumed)
        assertEquals("b-brief", back.detour)
        assertFalse(w.isHolding)
    }

    @Test fun `the next track playing on releases the held listen`() {
        val w = ReturnWindow<Track, String>(windowMillis = 20_000L)
        w.leave(a, "a", b)
        assertNull(w.onProgress(19_000L))
        assertEquals("a", w.onProgress(20_000L))
        assertNull(w.leave(b, "b", a).let { (it as ReturnWindow.Outcome.Held).released })
    }

    @Test fun `moving on to a third track releases the first and holds the second`() {
        val w = ReturnWindow<Track, String>()
        w.leave(a, "a", b)
        val out = w.leave(b, "b", c) as ReturnWindow.Outcome.Held
        assertEquals("a", out.released)
        assertTrue(w.isHolding)
        assertEquals("b", w.release())
    }

    @Test fun `the same track twice in a queue is not a return`() {
        val w = ReturnWindow<Track, String>()
        val again = Track("a")
        w.leave(a, "a", b)
        val out = w.leave(b, "b", again)
        assertTrue(out is ReturnWindow.Outcome.Held)
        assertSame("a", (out as ReturnWindow.Outcome.Held).released)
    }
}
