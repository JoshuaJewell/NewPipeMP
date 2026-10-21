/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.player.playback.ListenJudge.Verdict

class ListenJudgeTest {

    @Test fun `a natural end is a completion whatever the reported position`() {
        // On an automatic transition the player already reports the next track's
        // position, which is how completions used to be lost.
        assertEquals(Verdict.COMPLETION, ListenJudge.judge(0L, 200_000L, naturalEnd = true, unjudged = false))
    }

    @Test fun `leaving before ninety percent is a skip and after it a completion`() {
        assertEquals(Verdict.SKIP, ListenJudge.judge(1_000L, 200_000L, naturalEnd = false, unjudged = false))
        assertEquals(Verdict.SKIP, ListenJudge.judge(180_000L, 200_000L, naturalEnd = false, unjudged = false))
        assertEquals(Verdict.COMPLETION, ListenJudge.judge(181_000L, 200_000L, naturalEnd = false, unjudged = false))
    }

    @Test fun `an unjudged listen or an unknown duration records nothing`() {
        assertEquals(Verdict.NONE, ListenJudge.judge(1_000L, 200_000L, naturalEnd = false, unjudged = true))
        assertEquals(Verdict.NONE, ListenJudge.judge(190_000L, 200_000L, naturalEnd = true, unjudged = true))
        assertEquals(Verdict.NONE, ListenJudge.judge(1_000L, 0L, naturalEnd = false, unjudged = false))
    }

    @Test fun `shuffle grace covers only the opening of the queue's first track`() {
        assertTrue(ListenJudge.isShuffleStartGrace(isFirstTrackOfQueue = true, positionMillis = 4_000L))
        assertFalse(ListenJudge.isShuffleStartGrace(isFirstTrackOfQueue = true, positionMillis = 45_000L))
        assertFalse(ListenJudge.isShuffleStartGrace(isFirstTrackOfQueue = false, positionMillis = 4_000L))
    }

    private fun facts(
        coverage: Double?,
        heard: Long = 120_000L,
        naturalEnd: Boolean = false,
        screenOn: Boolean = true,
        interrupted: Boolean = false,
        stalled: Boolean = false,
        reason: ListenJudge.Unjudged? = null
    ) = ListenJudge.Facts(0L, 200_000L, naturalEnd, heard, coverage, screenOn, interrupted, stalled, reason)

    @Test fun `coverage decides, and a natural end forgives a skipped intro`() {
        assertEquals(Verdict.COMPLETION, ListenJudge.judge(facts(0.95)).verdict)
        assertEquals(Verdict.SKIP, ListenJudge.judge(facts(0.7)).verdict)
        assertEquals(Verdict.COMPLETION, ListenJudge.judge(facts(0.7, naturalEnd = true)).verdict)
        // Seeking to the last few seconds and letting it end is still a skip.
        assertEquals(Verdict.SKIP, ListenJudge.judge(facts(0.1, naturalEnd = true)).verdict)
        assertEquals(0.7, ListenJudge.judge(facts(0.7)).completionRatio!!, 0.0)
    }

    @Test fun `listens that say nothing about the track are unjudged`() {
        val u = ListenJudge.Unjudged.QUEUE_START
        assertEquals(u, ListenJudge.judge(facts(0.05, reason = u)).unjudged)
        assertEquals(ListenJudge.Unjudged.INTERRUPTED, ListenJudge.judge(facts(0.3, interrupted = true)).unjudged)
        assertEquals(ListenJudge.Unjudged.STALLED, ListenJudge.judge(facts(0.3, stalled = true)).unjudged)
        assertEquals(ListenJudge.Unjudged.TOO_SHORT, ListenJudge.judge(facts(0.0, heard = 600L)).unjudged)
        assertEquals(ListenJudge.Unjudged.UNSEEN, ListenJudge.judge(facts(0.01, heard = 2_000L, screenOn = false)).unjudged)
        assertEquals(Verdict.SKIP, ListenJudge.judge(facts(0.01, heard = 2_000L, screenOn = true)).verdict)
        assertEquals(ListenJudge.Unjudged.RESUMED_NEAR_END, ListenJudge.judge(facts(null)).unjudged)
        assertNull(ListenJudge.judge(facts(null)).completionRatio)
    }
}
