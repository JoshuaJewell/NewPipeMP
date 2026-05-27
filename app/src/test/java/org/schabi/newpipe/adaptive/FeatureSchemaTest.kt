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
}
