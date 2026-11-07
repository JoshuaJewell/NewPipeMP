/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMetadataDamageTest {
    private val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    private val channel = "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw"

    @Test fun `a url in place of the title is damage`() {
        assertTrue(StreamMetadataDamage.isBadTitle(channel, url, channel))
        assertTrue(StreamMetadataDamage.isBadTitle(url, url, channel))
        assertTrue(StreamMetadataDamage.isBadTitle("https://example.com/x", url, null))
        assertTrue(StreamMetadataDamage.isBadTitle("  ", url, channel))
        assertTrue(StreamMetadataDamage.isBadTitle(null, url, channel))
        assertFalse(StreamMetadataDamage.isBadTitle("Air Hostess", url, channel))
        assertFalse(StreamMetadataDamage.isBadTitle("AC/DC: Live", url, channel))
    }

    @Test fun `missing, placeholder or url uploaders are damage`() {
        assertTrue(StreamMetadataDamage.isBadUploader(""))
        assertTrue(StreamMetadataDamage.isBadUploader(null))
        assertTrue(StreamMetadataDamage.isBadUploader("Unknown"))
        assertTrue(StreamMetadataDamage.isBadUploader(channel))
        assertFalse(StreamMetadataDamage.isBadUploader("Busted"))
    }

    @Test fun `titles and artists come back from library file names`() {
        assertEquals(StreamMetadataDamage.FromFileName("Busted", "Air Hostess"), StreamMetadataDamage.fromFileName("Busted - Air Hostess.m4a"))
        assertEquals(StreamMetadataDamage.FromFileName("Busted", "Air Hostess - Live"), StreamMetadataDamage.fromFileName("/x/Library/Busted - Air Hostess - Live.opus"))
        assertEquals(StreamMetadataDamage.FromFileName(null, "Air Hostess"), StreamMetadataDamage.fromFileName("05 - Air Hostess.mp3"))
        assertEquals(StreamMetadataDamage.FromFileName(null, "Air Hostess"), StreamMetadataDamage.fromFileName("Air Hostess.webm"))
        assertNull(StreamMetadataDamage.fromFileName(".m4a"))
    }
}
