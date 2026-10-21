/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.database.adaptive.AdaptiveShufflePendingEventEntity

class AdaptiveShuffleEngineTest {

    private fun engine(store: InMemoryStore = InMemoryStore(), clock: FakeClock = FakeClock()) = AdaptiveShuffleEngine(store, clock, Random(7), discount = 1.0)

    private fun decideAndResolve(e: AdaptiveShuffleEngine, item: CandidateItem, ratio: Double) {
        e.chooseNext(listOf(item), fixtureContext())
        e.captureSnapshot(item.streamId, 0)
        e.resolveOutcome(item.streamId, (ratio * 100_000).toLong(), 100_000L, 0)
    }

    @Test fun `scaler learns from raw vectors, not already-scaled ones`() {
        // Spec D3: the live path used to feed the scaled vector back into the scaler.
        val store = InMemoryStore()
        val e = engine(store)
        repeat(40) { i ->
            val duration = if (i % 2 == 0) 120L else 480L
            decideAndResolve(e, fixtureItem(i.toLong(), durationSeconds = duration), 1.0)
        }
        val scaler = FeatureScaler.fromByteArray(store.model!!.scalerBlob)
        val short = FeatureExtractor.buildRawVector(fixtureContext(), fixtureItem(1, durationSeconds = 120L))
        val long = FeatureExtractor.buildRawVector(fixtureContext(), fixtureItem(2, durationSeconds = 480L))
        val zs = scaler.transform(short)[FeatureSchema.LOG_DURATION]
        val zl = scaler.transform(long)[FeatureSchema.LOG_DURATION]
        // Raw-scale statistics put the two durations symmetrically about zero at about +-1.
        assertEquals(-1.0, zs, 0.1)
        assertEquals(1.0, zl, 0.1)
    }

    @Test fun `resolution finds its event however many decisions came after it`() {
        // Spec D4: lookup used to be confined to the 50 most recent rows.
        val store = InMemoryStore()
        val e = engine(store)
        e.chooseNext(listOf(fixtureItem(999)), fixtureContext())
        e.captureSnapshot(999, 0)
        repeat(80) { i -> e.chooseNext(listOf(fixtureItem(i.toLong())), fixtureContext()) }
        e.resolveOutcome(999, 100_000L, 100_000L, 0)
        val event = store.events.values.first { it.streamId == 999L }
        assertTrue(event.resolved)
        assertEquals(1L, e.numEvents())
    }

    @Test fun `resolved events carry their outcome for exact replay`() {
        val store = InMemoryStore()
        val e = engine(store)
        decideAndResolve(e, fixtureItem(5), 0.45)
        val decoded = RawInputsCodec.decode(store.events.values.single().rawInputsBlob)
        assertEquals(0.45, decoded.outcome!!.completionRatio, 1e-9)
        assertEquals(0, decoded.outcome!!.restartDelta)
    }

    @Test fun `a discarded decision disappears and cannot be resolved later`() {
        val store = InMemoryStore()
        val e = engine(store)
        val d = e.chooseNext(listOf(fixtureItem(42)), fixtureContext())
        e.captureSnapshot(42, 0)
        e.discardDecision(d.pendingEventId, 42)
        assertTrue(store.events.isEmpty())
        assertNull(e.resolveOutcome(42, 100_000L, 100_000L, 0))
        assertEquals(0L, e.numEvents())
    }

    @Test fun `a single candidate is always the greedy choice`() {
        val e = engine()
        val d = e.chooseNext(listOf(fixtureItem(1)), fixtureContext())
        assertTrue(d.greedy)
        assertTrue(e.recentDecisions(1).single().exploit)
    }

    @Test fun `rebuild rescores stored outcomes under the current reward`() {
        val store = InMemoryStore()
        val e = engine(store)
        decideAndResolve(e, fixtureItem(1), 0.45)
        // Simulate an event resolved under an older reward: same outcome, different scalar.
        store.events.values.single().reward = -0.2
        val report = e.rebuildFromPending()
        assertEquals(1, report.fromStoredOutcome)
        assertEquals(0, report.carriedOver)
    }

    @Test fun `rebuild recovers legacy outcomes from telemetry and writes them back`() {
        val store = InMemoryStore()
        val e = engine(store)
        // A v2-era event: no outcome stored, legacy step reward for a 50% listen.
        val d = e.chooseNext(listOf(fixtureItem(7)), fixtureContext())
        val event = store.events[d.pendingEventId]!!
        val v2Blob = RawInputsCodecTest.downgradeToV2(event.rawInputsBlob)
        val legacyReward = RewardCalculator.legacyStepReward(0.5, 1)
        store.resolvePending(d.pendingEventId, legacyReward, v2Blob, 5_000_000L)

        val line = "{\"event\":\"resolution\",\"timestamp\":5000003,\"stream_id\":7," +
            "\"completion_ratio\":0.5,\"reward\":$legacyReward,\"time_since_decision_ms\":1}"
        val index = OutcomeRecovery.Index(OutcomeRecovery.parseResolutions(sequenceOf(line)))
        val report = e.rebuildFromPending(index)
        assertEquals(1, report.fromTelemetry)
        val recovered = RawInputsCodec.decode(store.events[d.pendingEventId]!!.rawInputsBlob).outcome
        assertNotNull(recovered)
        assertEquals(0.5, recovered!!.completionRatio, 1e-9)
        assertEquals(1, recovered.restartDelta)

        // Second rebuild needs no telemetry: the outcome now lives in the event.
        assertEquals(1, e.rebuildFromPending(null).fromStoredOutcome)
    }

    @Test fun `rebuild without telemetry keeps the original reward and says so`() {
        val store = InMemoryStore()
        val e = engine(store)
        val d = e.chooseNext(listOf(fixtureItem(7)), fixtureContext())
        val event = store.events[d.pendingEventId]!!
        store.resolvePending(d.pendingEventId, -0.2, RawInputsCodecTest.downgradeToV2(event.rawInputsBlob), 5_000_000L)
        val report = e.rebuildFromPending(null)
        assertEquals(1, report.carriedOver)
        assertEquals(1, report.ingested)
    }

    @Test fun `a model saved under another schema is reported as a mismatch`() {
        val store = InMemoryStore()
        store.model = AdaptiveShuffleStore.StoredModel(ByteArray(0), ByteArray(0), 10L, "old-schema")
        assertTrue(engine(store).initialise() is AdaptiveShuffleEngine.InitialiseResult.SchemaMismatch)
    }

    @Test fun `thompson varies picks under identical context where UCB does not`() {
        val store = InMemoryStore()
        val e = AdaptiveShuffleEngine(store, FakeClock(), Random(3), discount = 1.0)
        val candidates = (1L..6L).map { fixtureItem(it, uploader = "U$it", durationSeconds = 100L + 60L * it) }
        val ucb = (1..30).map { e.chooseNext(candidates, fixtureContext(), SelectionMode.UCB).chosen.streamId }.toSet()
        val ts = (1..30).map { e.chooseNext(candidates, fixtureContext(), SelectionMode.THOMPSON).chosen.streamId }.toSet()
        assertEquals(1, ucb.size)
        assertTrue("thompson should spread picks, got $ts", ts.size > 1)
        assertFalse(store.events.isEmpty())
    }

    @Test fun `an abandoned listen leaves no event and does not train the model`() {
        val store = InMemoryStore()
        val e = engine(store)
        val item = fixtureItem(9L)
        e.chooseNext(listOf(item), fixtureContext())
        e.captureSnapshot(item.streamId, 0)
        e.abandon(item.streamId)
        assertTrue(store.events.isEmpty())
        assertEquals(0L, e.numEvents())
        // The snapshot is gone too, so a later play of the track cannot resolve it.
        assertNull(e.resolveOutcome(item.streamId, 100_000L, 100_000L, 0))
    }

    @Test fun `abandon ignores tracks the engine did not choose`() {
        val store = InMemoryStore()
        val e = engine(store)
        val chosen = fixtureItem(1L)
        e.chooseNext(listOf(chosen), fixtureContext())
        e.captureSnapshot(chosen.streamId, 0)
        e.abandon(2L)
        assertEquals(1, store.events.size)
    }

    @Test fun `rebuild takes the outcome from the v1 point 1 ratio column and folds it into the blob`() {
        val store = InMemoryStore()
        val engine = AdaptiveShuffleEngine(store)
        engine.initialise()
        val raw = RawInputsCodec.encode(fixtureContext(), fixtureItem(1L))
        // A blob without the trailer, as the v1.1 build wrote: version 5, no outcome.
        val legacy = raw.copyOf(raw.size - (1 + 1 + 8 + 4)).also { it[0] = 5 }
        // That build's reward: fromRatio with one restart.
        val reward = RewardCalculator.fromRatio(0.6, 1)
        store.insertPending(
            AdaptiveShufflePendingEventEntity(
                streamId = 1L,
                chosenAt = 1_000L,
                featureVectorBlob = ByteArray(0),
                rawInputsBlob = legacy,
                predictedReward = 0.0,
                uncertainty = 0.0,
                candidateCount = 1,
                resolved = true,
                reward = reward,
                resolvedAt = 2_000L,
                completionRatio = 0.6
            )
        )
        val report = engine.rebuildFromPending()
        assertEquals(1, report.ingested)
        assertEquals(1, report.fromStoredOutcome)
        val folded = RawInputsCodec.decode(store.events.values.single().rawInputsBlob)
        assertEquals(RawInputsCodec.Outcome(0.6, 1), folded.outcome)
    }

    @Test fun `a baseline pick is random, flagged, kept through resolution and still trains`() {
        val store = InMemoryStore()
        val e = AdaptiveShuffleEngine(store, FakeClock(), Random(3), discount = 1.0)
        val candidates = (1L..6L).map { fixtureItem(it, uploader = "U$it", durationSeconds = 100L + 60L * it) }
        val picks = (1..40).map { e.chooseNext(candidates, fixtureContext(), baseline = true) }
        assertTrue(picks.all { it.baseline })
        assertTrue("baseline should spread picks", picks.map { it.chosen.streamId }.toSet().size > 3)
        val chosen = picks.last().chosen.streamId
        e.captureSnapshot(chosen, 0)
        e.resolveOutcome(chosen, 90_000L, 100_000L, 0)
        val resolved = store.events.values.single { it.resolved }
        val decoded = RawInputsCodec.decode(resolved.rawInputsBlob)
        assertTrue(decoded.baseline)
        assertEquals(0.9, decoded.outcome!!.completionRatio, 1e-9)
        assertEquals(1L, e.numEvents())
        val report = e.armComparison()
        assertEquals(1, report.baseline!!.listens)
        assertNull(report.model)
    }

    @Test fun `model picks are not flagged as baseline`() {
        val store = InMemoryStore()
        val e = engine(store)
        assertFalse(e.chooseNext(listOf(fixtureItem(1)), fixtureContext()).baseline)
        assertFalse(RawInputsCodec.decode(store.events.values.single().rawInputsBlob).baseline)
    }

    private fun offsetOf(e: AdaptiveShuffleEngine, streamId: Long): Double = e.trackOffsets(limit = 100, minListens = 0.0).let { (liked, disliked) ->
        (liked + disliked).firstOrNull { it.streamId == streamId }?.offset ?: 0.0
    }

    @Test fun `a user pick trains at reduced weight and is in neither comparison arm`() {
        val store = InMemoryStore()
        val e = engine(store)
        val pick = e.recordUserPick(fixtureItem(9), fixtureContext(), displaced = null)
        assertEquals(Chooser.USER, pick.chooser)
        e.captureSnapshot(9, 0)
        e.resolveOutcome(9, RawInputsCodec.Outcome(1.0, 0))
        assertEquals(1L, e.numEvents())
        assertEquals(Chooser.USER, RawInputsCodec.decode(store.events.values.single().rawInputsBlob).chooser)
        val report = e.armComparison()
        assertNull(report.model)
        assertNull(report.baseline)
        // Half weight: the offset moves less than after a model pick with the same outcome.
        val other = engine()
        decideAndResolve(other, fixtureItem(9), 1.0)
        assertTrue(offsetOf(e, 9) < offsetOf(other, 9))
        assertTrue(offsetOf(e, 9) > 0.0)
    }

    @Test fun `choosing a track over the model's pick teaches the difference between them`() {
        // Long tracks displaced by short ones the listener then finishes.
        fun run(ratio: Double): Double {
            val e = engine()
            repeat(30) { i ->
                val chosen = fixtureItem(1000L + i, durationSeconds = 150L)
                val displaced = fixtureItem(2000L + i, durationSeconds = 600L)
                // Warm the scaler with both lengths.
                decideAndResolve(e, fixtureItem(3000L + i, durationSeconds = if (i % 2 == 0) 150L else 600L), 0.5)
                e.recordUserPick(chosen, fixtureContext(), displaced)
                e.captureSnapshot(chosen.streamId, 0)
                e.resolveOutcome(chosen.streamId, RawInputsCodec.Outcome(ratio, 0))
            }
            return e.theta()[FeatureSchema.LOG_DURATION]
        }
        val listened = run(1.0)
        val skippedAtOnce = run(0.05)
        // Preferring short tracks pushes the duration weight down; an immediate skip of
        // the chosen track is not taken as a preference at all.
        assertTrue("listened $listened vs skipped $skippedAtOnce", listened < skippedAtOnce - 0.05)
    }

    @Test fun `listens of one track within a day count for less each time`() {
        fun offsetAfterTen(gapMillis: Long): Double {
            val clock = FakeClock()
            val e = engine(clock = clock)
            repeat(10) {
                clock.now += gapMillis
                decideAndResolve(e, fixtureItem(5), 1.0)
                // Something else in between, so the shared weights are not all this track.
                decideAndResolve(e, fixtureItem(100L + it), 0.1)
            }
            return offsetOf(e, 5)
        }
        val sameAfternoon = offsetAfterTen(20L * 60L * 1000L)
        val tenDays = offsetAfterTen(2L * AdaptiveShuffleConfig.REPEAT_WINDOW_MILLIS)
        assertTrue("same afternoon $sameAfternoon vs ten days $tenDays", sameAfternoon < tenDays)
    }

    @Test fun `rebuild replays user picks, preferences and repeat weights without losing events`() {
        val store = InMemoryStore()
        val e = engine(store)
        repeat(6) { i ->
            decideAndResolve(e, fixtureItem(i.toLong()), 0.9)
            e.recordUserPick(fixtureItem(50L + i), fixtureContext(), fixtureItem(60L + i, durationSeconds = 500L))
            e.captureSnapshot(50L + i, 0)
            e.resolveOutcome(50L + i, RawInputsCodec.Outcome(1.0, 0, partialRewinds = 1, returned = i == 0))
        }
        val report = e.rebuildFromPending()
        assertEquals(12, report.ingested)
        assertEquals(12, report.fromStoredOutcome)
        assertTrue(offsetOf(e, 50) > 0.0)
    }
}
