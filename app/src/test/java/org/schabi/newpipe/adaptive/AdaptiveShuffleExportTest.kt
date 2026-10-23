/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveShuffleExportTest {
    @Test fun `export holds every event with its features, and the model`() {
        val store = InMemoryStore()
        val e = AdaptiveShuffleEngine(store, FakeClock(), Random(1), discount = 1.0)
        repeat(5) { i ->
            val item = fixtureItem(i.toLong())
            e.chooseNext(listOf(item, fixtureItem(100L + i)), fixtureContext(), baseline = i % 2 == 0)
            e.captureSnapshot(item.streamId, 0)
            e.resolveOutcome(item.streamId, RawInputsCodec.Outcome(0.8, 0))
        }
        e.chooseNext(listOf(fixtureItem(50)), fixtureContext()) // unresolved
        val out = ByteArrayOutputStream()
        AdaptiveShuffleExport.write(e, store, out, 123L)

        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(setOf("README.txt", "model.json", "events.tsv", "offsets.tsv", "tracks.tsv"), files.keys)
        val rows = files.getValue("events.tsv").trim().lines()
        val header = rows.first().split('\t')
        assertEquals(17 + FeatureSchema.DIMENSION, header.size)
        assertEquals(7, rows.size) // header and six decisions
        assertTrue(rows.drop(1).all { it.split('\t').size == header.size })
        val choosers = rows.drop(1).map { it.split('\t')[header.indexOf("chooser")] }.toSet()
        assertEquals(setOf("baseline", "model"), choosers)
        assertTrue(files.getValue("model.json").contains("\"name\": \"bias\""))
        assertTrue(files.getValue("tracks.tsv").lines().size > 6)
    }
}
