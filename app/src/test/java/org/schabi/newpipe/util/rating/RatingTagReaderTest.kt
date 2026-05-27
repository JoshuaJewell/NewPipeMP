package org.schabi.newpipe.util.rating

import java.io.File
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RatingTagReaderTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private fun fixture(name: String): File {
        val src = File("src/test/resources/audio/$name")
        // Copy to TemporaryFolder so a malformed test write can't corrupt the fixture.
        val dst = tmp.newFile(name)
        src.copyTo(dst, overwrite = true)
        return dst
    }

    @Test
    fun `reads no rating from unrated mp3`() {
        assertNull(RatingTagReader.read(fixture("silence-1s.mp3")))
    }

    @Test
    fun `reads no rating from unrated flac`() {
        assertNull(RatingTagReader.read(fixture("silence-1s.flac")))
    }

    @Test
    fun `reads no rating from unrated opus`() {
        assertNull(RatingTagReader.read(fixture("silence-1s.opus")))
    }

    @Test
    fun `reads no rating from unrated m4a`() {
        assertNull(RatingTagReader.read(fixture("silence-1s.m4a")))
    }

    @Test
    fun `returns null for unknown container`() {
        val txt = tmp.newFile("not-audio.txt").apply { writeText("hello") }
        assertNull(RatingTagReader.read(txt))
    }
}
