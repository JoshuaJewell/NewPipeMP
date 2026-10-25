/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

// Runs the staged replay (spec section 7.2) over a synthetic log, and over a real export
// when ADAPTIVE_REPLAY_EVENTS names one (see ReplayHarness.load for the format).
//
// The synthetic log is written by a collapsed policy, as the real one was: most picks
// come from a small favourite set. Its listener is invented, so the assertions here
// only catch gross regressions; they say nothing about how the live experience changes.
class StagedReplayTest {

    private class Track(
        val id: Long,
        val uploader: String,
        val propensity: Double,
        val rating: Int?,
        val durationSeconds: Long
    ) {
        var plays = 0
        var completions = 0
        var earlySkips = 0
        var lastPlayedAt: Long? = null
    }

    internal fun syntheticLog(seed: Long, decisions: Int = 1500): List<ReplayHarness.Event> {
        val rng = Random(seed)
        val tracks = ArrayList<Track>()
        var nextId = 1L
        for (u in 0 until 30) {
            val uploaderBase = 0.25 + 0.5 * rng.nextDouble()
            repeat(10) {
                val p = (uploaderBase + rng.nextGaussian() * 0.2).coerceIn(0.02, 0.98)
                val rating = if (rng.nextDouble() < 0.3) (1 + (p * 4.99).toInt()).coerceIn(1, 5) else null
                tracks += Track(nextId++, "U$u", p, rating, 150L + rng.nextInt(300))
            }
        }
        val favourites = tracks.shuffled(rng).take(15)
        val origins = QueueOrigin.values()

        val events = ArrayList<ReplayHarness.Event>()
        var now = 1_700_000_000_000L
        var sessionPosition = 0
        var sessionLength = 15 + rng.nextInt(16)
        var origin = origins[rng.nextInt(origins.size)]
        var previous: Track? = null
        var previousCompleted = false
        val recentRatios = ArrayDeque<Double>()

        repeat(decisions) {
            if (sessionPosition >= sessionLength) {
                now += 2L * 60L * 60L * 1000L + rng.nextInt(20 * 60 * 60 * 1000)
                sessionPosition = 0
                sessionLength = 15 + rng.nextInt(16)
                origin = origins[rng.nextInt(origins.size)]
                previous = null
            }
            val t = if (rng.nextDouble() < 0.6) favourites[rng.nextInt(favourites.size)] else tracks[rng.nextInt(tracks.size)]

            val byUploader = tracks.filter { it.uploader == t.uploader }
            val upCompletions = byUploader.sumOf { it.completions }
            val upResolved = upCompletions + byUploader.sumOf { it.earlySkips }
            val upRate = if (upResolved > 0) upCompletions.toDouble() / upResolved else null
            val rated = byUploader.mapNotNull { it.rating }
            val item = CandidateItem(
                streamId = t.id,
                durationSeconds = t.durationSeconds,
                userRating = t.rating,
                lifetimePlayCount = t.plays,
                lifetimeEarlySkips = t.earlySkips,
                lifetimeCompletions = t.completions,
                daysSinceLastAccess = t.lastPlayedAt?.let { (now - it) / 86_400_000.0 },
                streamType = StreamTypeFeature.AUDIO,
                uploader = t.uploader,
                uploaderAvgRating = if (rated.isEmpty()) null else rated.average(),
                uploaderCompletionRate = upRate,
                uploaderSkipRate = upRate?.let { 1.0 - it }
            )
            val hour = ((now / 3_600_000L) % 24L).toInt()
            val context = DecisionContext(
                hourOfDay = hour,
                dayOfWeek = ((now / 86_400_000L) % 7L).toInt(),
                sessionPosition = sessionPosition,
                recentSkipRate = if (recentRatios.isEmpty()) 0.0 else recentRatios.count { it < 0.15 }.toDouble() / recentRatios.size,
                previousAutoQueued = false,
                previousCompleted = previousCompleted,
                previousUploader = previous?.uploader,
                previousStreamId = previous?.id,
                queueOrigin = origin
            )

            // The listener: finishes with the track's propensity, otherwise leaves early
            // or partway through.
            val ratio = if (rng.nextDouble() < t.propensity) {
                0.9 + 0.1 * rng.nextDouble()
            } else if (rng.nextBoolean()) {
                0.14 * rng.nextDouble()
            } else {
                0.15 + 0.7 * rng.nextDouble()
            }
            events += ReplayHarness.Event(now, context, item, ratio, if (ratio > 0.9 && rng.nextDouble() < 0.05) 1 else 0)

            t.plays += 1
            if (ratio >= AdaptiveShuffleConfig.COMPLETION_AT_OR_ABOVE) t.completions += 1
            if (ratio < AdaptiveShuffleConfig.EARLY_SKIP_BELOW) t.earlySkips += 1
            t.lastPlayedAt = now
            recentRatios.addLast(ratio)
            if (recentRatios.size > 10) recentRatios.removeFirst()
            previous = t
            previousCompleted = ratio >= AdaptiveShuffleConfig.COMPLETION_AT_OR_ABOVE
            sessionPosition += 1
            now += (t.durationSeconds * 1000L * ratio).toLong() + 5_000L
        }
        return events
    }

    @Test fun `every stage replays a synthetic log and the report carries the caveat`() {
        val events = syntheticLog(seed = 11L)
        val reports = ReplayHarness.STAGES.map { ReplayHarness.run(events, it) }
        val text = ReplayHarness.format(reports)
        println(text)

        assertEquals(2, Regex(Regex.escape(ReplayHarness.CAVEAT)).findAll(text).count())
        for (stage in ReplayHarness.STAGES) assertTrue("missing ${stage.label}", text.contains(stage.label))

        for (r in reports) {
            assertEquals(events.size, r.events)
            assertEquals(AdaptiveShuffleConfig.METRICS_WINDOW, r.repetition.windowSize)
            // The listener's propensity shows in each track's completion history, so every
            // stage should find at least a weak ordering. Below this is a broken pipeline.
            assertTrue("${r.stage.label}: rho(cr) ${r.spearmanCompletion}", r.spearmanCompletion > 0.15)
        }
    }

    @Test fun `the candidate filter stops the replayed policy collapsing`() {
        val events = syntheticLog(seed = 12L)
        val byLabel = ReplayHarness.STAGES.associate { it.label to ReplayHarness.run(events, it) }
        val reports = ReplayHarness.STAGES.map { byLabel.getValue(it.label) }
        val baseline = reports[0].repetition
        val withoutFilter = reports[1].repetition
        // 300 tracks: the scaled window is 75 picks, so the last 100 simulated picks hold
        // at least 75 distinct tracks whenever the filter is on.
        for (r in reports.filter { it.stage.candidateFilter }) {
            val m = r.repetition
            assertTrue("${r.stage.label}: unique ${m.uniqueTracks}", m.uniqueTracks >= 75)
            assertTrue(
                "${r.stage.label}: unique ${m.uniqueTracks} vs unfiltered ${withoutFilter.uniqueTracks}",
                m.uniqueTracks >= withoutFilter.uniqueTracks
            )
            assertTrue(
                "${r.stage.label}: top track ${m.topTrackShare} vs baseline ${baseline.topTrackShare}",
                m.topTrackShare <= baseline.topTrackShare
            )
        }
    }

    @Test fun `replay is deterministic for a seed`() {
        val events = syntheticLog(seed = 13L, decisions = 400)
        val stage = ReplayHarness.STAGES.first { it.thompson }
        assertEquals(ReplayHarness.run(events, stage, seed = 5L), ReplayHarness.run(events, stage, seed = 5L))
    }

    @Test fun `replays a real export when one is provided`() {
        val path = System.getenv(EXPORT_ENV)
        assumeTrue("set $EXPORT_ENV to a decision export to run this", !path.isNullOrBlank())
        val loaded = ReplayHarness.load(File(path!!))
        println(
            "loaded ${loaded.events.size} events from $path; " +
                "${loaded.withoutOutcome} without a stored outcome and ${loaded.undecodable} undecodable were left out"
        )
        assumeTrue("no replayable events in $path", loaded.events.isNotEmpty())
        val reports = ReplayHarness.STAGES.map { ReplayHarness.run(loaded.events, it) }
        println(ReplayHarness.format(reports))
    }

    private companion object {
        const val EXPORT_ENV = "ADAPTIVE_REPLAY_EVENTS"
    }
}
