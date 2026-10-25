/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class FeatureAblationTest {
    @Test
    fun `baseline keeps preference recency and transitions but removes standalone context`() {
        val full = ReplayHarness.ABLATION_STAGES.first()
        val baseline = ReplayHarness.ABLATION_STAGES.last()
        val raw = DoubleArray(FeatureSchema.DIMENSION) { 1.0 }
        val reduced = ReplayHarness.ablate(raw, baseline)
        assertEquals(1.0, reduced[FeatureSchema.USER_RATING], 0.0)
        assertEquals(1.0, reduced[FeatureSchema.COMPLETION_RATE], 0.0)
        assertEquals(1.0, reduced[FeatureSchema.DAYS_SINCE_PLAYED], 0.0)
        assertEquals(1.0, reduced[FeatureSchema.SAME_UPLOADER], 0.0)
        assertEquals(1.0, reduced[FeatureSchema.PLAYLIST_CO_MEMBERSHIP], 0.0)
        assertEquals(0.0, reduced[FeatureSchema.HOUR_SIN], 0.0)
        assertEquals(0.0, reduced[FeatureSchema.ROUTE_BLUETOOTH], 0.0)
        assertEquals(0.0, reduced[FeatureSchema.LOG_DURATION], 0.0)
        assertEquals(1.0, raw[FeatureSchema.HOUR_SIN], 0.0)
        assertTrue(ReplayHarness.ablate(raw, full).contentEquals(raw))
        assertEquals(full.copy(label = baseline.label, simpleBaseline = true), baseline)
    }

    @Test
    fun `shared additive time changes absolute predictions but not candidate ranking`() {
        val weights = DoubleArray(FeatureSchema.DIMENSION)
        weights[FeatureSchema.HOUR_SIN] = 2.0
        weights[FeatureSchema.USER_RATING] = 0.7
        fun gap(hour: Double): Double {
            val a = DoubleArray(FeatureSchema.DIMENSION)
            a[FeatureSchema.HOUR_SIN] = hour
            a[FeatureSchema.USER_RATING] = 1.0
            val b = a.copyOf()
            b[FeatureSchema.USER_RATING] = -1.0
            return LinUCB.dot(weights, a) - LinUCB.dot(weights, b)
        }
        assertEquals(1.4, gap(-1.0), 1e-12)
        assertEquals(gap(-1.0), gap(1.0), 1e-12)
    }

    @Test
    fun `ablation predicts before learning and compares the same chronological events`() {
        val events = StagedReplayTest().syntheticLog(seed = 29L, decisions = 250)
        val reports = ReplayHarness.ABLATION_STAGES.map { stage ->
            val report = ReplayHarness.run(events, stage, warmUp = 50)
            assertEquals(200, report.scoredEvents)
            assertTrue(report.meanSquaredRewardError.isFinite())
            assertTrue(report.meanSquaredRewardError >= 0.0)
            assertTrue(report.spearmanReward.isFinite())
            assertEquals(report, ReplayHarness.run(events.reversed(), stage, warmUp = 50))
            report
        }
        println("Synthetic smoke test, not evidence of improved listening: \n" + ReplayHarness.format(reports))
    }

    @Test
    fun `compare current and simple model on a real export when supplied`() {
        val path = System.getenv("ADAPTIVE_REPLAY_EVENTS")
        assumeTrue("Set ADAPTIVE_REPLAY_EVENTS to a resolved decision export", !path.isNullOrBlank())
        val loaded = ReplayHarness.load(File(path!!))
        assumeTrue("Need more than 50 replayable events", loaded.events.size > 50)
        println("Excluded ${loaded.withoutOutcome} events without outcomes and ${loaded.undecodable} undecodable events")
        val reports = ReplayHarness.ABLATION_STAGES.map { ReplayHarness.run(loaded.events, it) }
        println(ReplayHarness.format(reports))
        // No asserted winner: this is an experiment, not a reason to tune against the log.
        for (report in reports) assertTrue(report.meanSquaredRewardError.isFinite())
    }
}
