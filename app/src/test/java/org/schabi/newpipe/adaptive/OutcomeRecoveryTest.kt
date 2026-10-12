/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OutcomeRecoveryTest {

    private val lines = sequenceOf(
        "{\"event\":\"decision\",\"timestamp\":100,\"stream_id\":3,\"candidate_count\":30}",
        "{\"event\":\"resolution\",\"timestamp\":5000,\"stream_id\":3,\"completion_ratio\":0.42,\"reward\":-0.2,\"time_since_decision_ms\":9}",
        "{\"event\":\"resolution\",\"timestamp\":900000,\"stream_id\":3,\"completion_ratio\":1.0,\"reward\":1.0,\"time_since_decision_ms\":9}",
        "not json at all",
        "{\"event\":\"resolution\",\"timestamp\":7000,\"stream_id\":4,\"completion_ratio\":NaN,\"reward\":0.0}"
    )

    @Test fun `parses only well-formed resolution lines`() {
        val parsed = OutcomeRecovery.parseResolutions(lines)
        assertEquals(2, parsed.size)
        assertEquals(0.42, parsed[0].completionRatio, 1e-12)
    }

    @Test fun `matches by stream, time and reward`() {
        val index = OutcomeRecovery.Index(OutcomeRecovery.parseResolutions(lines))
        assertNotNull(index.find(3, 4_000, -0.2))
        // Right stream and time but a different reward: another play of the same track.
        assertNull(index.find(3, 4_000, 1.0))
        // Right reward but outside the time tolerance.
        assertNull(index.find(3, 60_000, -0.2))
        assertNull(index.find(99, 5_000, -0.2))
    }

    @Test fun `recovered outcome inverts the legacy reward`() {
        val legacy = RewardCalculator.legacyStepReward(0.95, 2)
        val match = OutcomeRecovery.TelemetryResolution(1, 0, 0.95, legacy)
        val outcome = OutcomeRecovery.outcomeFrom(match)
        assertEquals(2, outcome.restartDelta)
        assertEquals(0.95, outcome.completionRatio, 1e-12)
    }
}
