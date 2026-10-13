/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureSchemaTest {

    @Test fun `dimension matches the name list size`() {
        assertEquals(FeatureSchema.names.size, FeatureSchema.DIMENSION)
    }

    @Test fun `schema hash is non-empty and deterministic across calls`() {
        val first = FeatureSchema.schemaHash
        val second = FeatureSchema.schemaHash
        assertNotNull(first)
        assertTrue("hash should be 64 hex chars", first.length == 64)
        assertEquals(first, second)
    }

    @Test fun `scaled indices are unique and within bounds`() {
        val xs = FeatureSchema.scaledIndices.toSet()
        assertEquals(FeatureSchema.scaledIndices.size, xs.size)
        for (i in FeatureSchema.scaledIndices) {
            assertTrue("$i in bounds", i in 0 until FeatureSchema.DIMENSION)
        }
    }

    @Test fun `no feature is a linear combination of the others over varied inputs`() {
        // The v2 schema had three exact dependencies (origin one-hots + bias, uploader
        // completion + skip rates, and near-complementary track ratios) that made weights
        // unidentifiable. A full-rank Gram matrix rules all of them out.
        val rng = java.util.Random(5)
        val d = FeatureSchema.DIMENSION
        val gram = DoubleArray(d * d)
        repeat(4000) {
            val plays = rng.nextInt(20)
            val completions = if (plays == 0) 0 else rng.nextInt(plays + 1)
            val ctx = DecisionContext(
                hourOfDay = rng.nextInt(24),
                dayOfWeek = rng.nextInt(7),
                sessionPosition = rng.nextInt(40),
                recentSkipRate = rng.nextDouble(),
                previousAutoQueued = rng.nextBoolean(),
                previousCompleted = rng.nextBoolean(),
                previousUploader = if (rng.nextBoolean()) "A" else "B",
                previousStreamId = null,
                queueOrigin = QueueOrigin.values()[rng.nextInt(4)],
                screenOn = rng.nextBoolean(),
                outputRoute = OutputRoute.values()[rng.nextInt(4)]
            )
            val listens = rng.nextInt(12)
            val leaveMean = rng.nextDouble()
            val item = CandidateItem(
                streamId = 1L,
                durationSeconds = 60L + rng.nextInt(600),
                userRating = if (rng.nextBoolean()) 1 + rng.nextInt(10) else null,
                lifetimePlayCount = plays,
                lifetimeEarlySkips = plays - completions,
                lifetimeCompletions = completions,
                daysSinceLastAccess = if (rng.nextBoolean()) rng.nextDouble() * 60 else null,
                streamType = StreamTypeFeature.values()[rng.nextInt(4)],
                uploader = if (rng.nextBoolean()) "A" else "C",
                uploaderAvgRating = if (rng.nextBoolean()) 1 + rng.nextDouble() * 9 else null,
                uploaderCompletionRate = if (rng.nextBoolean()) rng.nextDouble() else null,
                uploaderSkipRate = null,
                playlistCoMembershipWithPrev = rng.nextInt(4),
                isDownloadedOffline = rng.nextBoolean(),
                listenEvents = ListenEventCounts(
                    forwardSeeks = rng.nextInt(8),
                    backwardSeeks = rng.nextInt(8),
                    pauseResumes = rng.nextInt(5),
                    pauseSkips = rng.nextInt(3),
                    volumeUps = rng.nextInt(10),
                    volumeDowns = rng.nextInt(10),
                    leaveCount = listens,
                    leaveMean = leaveMean,
                    leaveM2 = rng.nextDouble() * listens * 0.1
                )
            )
            val x = FeatureExtractor.buildRawVector(ctx, item)
            for (i in 0 until d) for (j in 0 until d) gram[i * d + j] += x[i] * x[j]
        }
        // Cholesky fails on a singular matrix; normalise by the diagonal first so the
        // pivot threshold is scale-free.
        val norm = DoubleArray(d) { kotlin.math.sqrt(gram[it * d + it]) }
        val corr = DoubleArray(d * d) { k -> gram[k] / (norm[k / d] * norm[k % d]) }
        val l = Matrix.of(d, d, corr).choleskyLower()
        for (i in 0 until d) assertTrue("pivot $i (${FeatureSchema.names[i]}) ${l[i, i]}", l[i, i] > 1e-3)
    }
}
