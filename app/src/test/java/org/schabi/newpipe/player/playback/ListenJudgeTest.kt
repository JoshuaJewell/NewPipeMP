/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
