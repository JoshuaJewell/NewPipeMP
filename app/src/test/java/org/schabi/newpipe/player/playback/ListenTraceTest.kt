/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenTraceTest {
    private val minute = 60_000L

    @Test fun `a straight listen covers what it played`() {
        val t = ListenTrace()
        t.start(0L)
        t.close(2 * minute)
        assertEquals(0.5, t.coverage(4 * minute)!!, 1e-9)
        assertEquals(2 * minute, t.heardMillis)
    }

    @Test fun `seeking to the end and letting it finish is not a full listen`() {
        val t = ListenTrace()
        t.start(0L)
        t.seek(20_000L, 230_000L)
        t.close(240_000L)
        assertEquals(30_000.0 / 240_000.0, t.coverage(240_000L)!!, 1e-9)
    }

    @Test fun `a listen resumed halfway and finished is a full listen`() {
        val t = ListenTrace()
        t.start(2 * minute)
        t.close(4 * minute)
        assertEquals(1.0, t.coverage(4 * minute)!!, 1e-9)
    }

    @Test fun `resumed near the end there is nothing to judge`() {
        val t = ListenTrace()
        t.start(225_000L)
        t.close(240_000L)
        assertNull(t.coverage(240_000L))
    }

    @Test fun `replaying a part counts once toward coverage but twice as heard`() {
        val t = ListenTrace()
        t.start(0L)
        t.seek(90_000L, 60_000L) // back 30 s: a partial rewind
        t.close(120_000L)
        assertEquals(0.5, t.coverage(240_000L)!!, 1e-9)
        assertEquals(150_000L, t.heardMillis)
        assertEquals(1, t.partialRewinds)
        assertEquals(0, t.restarts)
    }

    @Test fun `going back to the start is a restart`() {
        val t = ListenTrace()
        t.start(0L)
        t.seek(100_000L, 0L)
        t.close(240_000L)
        assertEquals(1, t.restarts)
        assertEquals(0, t.partialRewinds)
    }

    @Test fun `tapping rewind three times is one rewind, and scrubbing is none`() {
        val t = ListenTrace()
        t.start(0L)
        t.seek(100_000L, 90_000L)
        t.seek(90_500L, 80_500L)
        t.seek(81_000L, 71_000L) // three 10 s taps within a second or two
        t.seek(150_000L, 145_000L) // later: back 5 s to catch a word
        t.close(200_000L)
        assertEquals(1, t.partialRewinds)
        assertEquals(0, t.restarts)
    }

    @Test fun `heard so far includes the span still playing`() {
        val t = ListenTrace()
        t.start(10_000L)
        assertEquals(15_000L, t.heardAt(25_000L))
    }
}
