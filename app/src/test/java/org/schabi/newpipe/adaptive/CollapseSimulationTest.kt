/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test

// Synthetic end-to-end run of the decision loop against the failure seen in use at
// n=570: one uploader with a single track the listener always finishes and nine they
// abandon early, which the v2 model kept serving because the good track propped up the
// shared uploader features. This is a simulation with an invented listener, so it shows
// the mechanism is gone, not that the live experience is fixed; that needs the
// repetition metrics on device.
class CollapseSimulationTest {

    private class Track(val id: Long, val uploader: String, val trueRatio: Double) {
        var plays = 0
        var completions = 0
        var skips = 0
    }

    private data class RunResult(
        val poorAlbumMateShare: Double,
        val uniformPoorShare: Double,
        val topTrackShare: Double,
        val uniqueInLast100: Int,
        val meanRewardLast: Double
    )

    private fun run(seed: Long, decisions: Int = 1500, othersPerUploader: Int = 6): RunResult {
        val rng = Random(seed)
        val tracks = ArrayList<Track>()
        var id = 1L
        tracks += Track(id++, "Loved", 0.97)
        repeat(9) { tracks += Track(id++, "Loved", 0.12) }
        for (u in 0 until 20) repeat(othersPerUploader) { tracks += Track(id++, "U$u", 0.4 + 0.5 * rng.nextDouble()) }
        val byId = tracks.associateBy { it.id }

        val engine = AdaptiveShuffleEngine(InMemoryStore(), FakeClock(), Random(seed + 1))
        val recentKeys = ArrayDeque<String>()
        val recentUploaders = ArrayDeque<String?>()
        var previous: Track? = null
        val served = ArrayList<Track>()
        val rewards = ArrayList<Double>()

        repeat(decisions) { step ->
            val entries = tracks.mapIndexed { i, t -> CandidateFilter.TailEntry(i, "k${t.id}", t.uploader) }
            val eligible = CandidateFilter.filter(entries, recentKeys.toList(), recentUploaders.toList()).eligible
            val sampled = CandidateFilter.sample(eligible, AdaptiveShuffleConfig.CANDIDATE_SAMPLE_CAP, rng)
            val uploaderRate = tracks.groupBy { it.uploader }.mapValues { (_, ts) ->
                val c = ts.sumOf { it.completions }
                val s = ts.sumOf { it.skips }
                if (c + s > 0) c.toDouble() / (c + s) else null
            }
            val candidates = sampled.map { e ->
                val t = tracks[e.tailIndex]
                fixtureItem(
                    t.id,
                    uploader = t.uploader,
                    plays = t.plays,
                    skips = t.skips,
                    completions = t.completions,
                    uploaderCompletionRate = uploaderRate[t.uploader]
                )
            }
            val ctx = fixtureContext(hour = rng.nextInt(24), previousUploader = previous?.uploader, sessionPosition = step % 30)
            val decision = engine.chooseNext(candidates, ctx)
            val t = byId.getValue(decision.chosen.streamId)

            val ratio = (t.trueRatio + rng.nextGaussian() * 0.08).coerceIn(0.0, 1.0)
            engine.captureSnapshot(t.id, 0)
            val res = engine.resolveOutcome(t.id, (ratio * 200_000).toLong(), 200_000L, 0)!!
            rewards += res.reward
            t.plays += 1
            if (ratio >= AdaptiveShuffleConfig.COMPLETION_AT_OR_ABOVE) t.completions += 1 else t.skips += 1

            recentKeys.addFirst("k${t.id}")
            while (recentKeys.size > AdaptiveShuffleConfig.RECENCY_EXCLUSION_MAX) recentKeys.removeLast()
            recentUploaders.addFirst(t.uploader)
            while (recentUploaders.size > AdaptiveShuffleConfig.UPLOADER_SPACING) recentUploaders.removeLast()
            previous = t
            served += t
        }

        val last = served.takeLast(500)
        val poor = last.count { it.uploader == "Loved" && it.trueRatio < 0.5 }
        val counts = last.groupingBy { it.id }.eachCount()
        return RunResult(
            poorAlbumMateShare = poor.toDouble() / last.size,
            uniformPoorShare = 9.0 / tracks.size,
            topTrackShare = counts.values.maxOrNull()!!.toDouble() / last.size,
            uniqueInLast100 = served.takeLast(100).map { it.id }.toSet().size,
            meanRewardLast = rewards.takeLast(500).average()
        )
    }

    @Test fun `poor album-mates of a loved track are not carried by it`() {
        for (seed in listOf(1L, 2L, 3L)) {
            val r = run(seed)
            println("seed $seed: $r")
            assertTrue(
                "poor album-mates should be served well below their uniform share: $r",
                r.poorAlbumMateShare < r.uniformPoorShare / 3
            )
        }
    }

    @Test fun `a small library is capped by the minimum recency window`() {
        // 130 tracks: the window is its floor of 40, so a favourite can return every 41
        // picks. The model does concentrate on its favourites here; the window is the
        // only thing spreading them, and that is the intended dial.
        val r = run(4L)
        println("small library: $r")
        assertTrue("top track share ${r.topTrackShare}", r.topTrackShare <= 1.0 / 41.0 + 1e-9)
        assertTrue("distinct tracks in last 100: ${r.uniqueInLast100}", r.uniqueInLast100 >= 41)
    }

    @Test fun `a large library rotates widely because the window scales`() {
        // 1210 tracks, close to the real library: the window is about 300 picks, so the
        // last 100 are necessarily all different.
        val r = run(6L, decisions = 1200, othersPerUploader = 60)
        println("large library: $r")
        assertTrue("distinct tracks in last 100: ${r.uniqueInLast100}", r.uniqueInLast100 == 100)
        assertTrue("poor album-mates ${r.poorAlbumMateShare}", r.poorAlbumMateShare < r.uniformPoorShare)
    }

    @Test fun `the model still learns, beating uniform choice on reward`() {
        val r = run(5L)
        // Uniform choice over this library averages roughly -0.05 under the live reward.
        assertTrue("mean reward over the last 500 decisions ${r.meanRewardLast}", r.meanRewardLast > 0.05)
    }
}
