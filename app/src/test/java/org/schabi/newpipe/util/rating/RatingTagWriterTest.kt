package org.schabi.newpipe.util.rating

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RatingTagWriterTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private fun fixture(name: String): File {
        val src = File("src/test/resources/audio/$name")
        val dst = tmp.newFile(name)
        src.copyTo(dst, overwrite = true)
        return dst
    }

    @Test
    fun `writes a rating to mp3 and read returns it`() {
        val f = fixture("silence-1s.mp3")
        assertTrue(RatingTagWriter.write(f, 7).isSuccess)
        assertEquals(7, RatingTagReader.read(f))
    }

    @Test
    fun `writes a rating to flac and read returns it`() {
        val f = fixture("silence-1s.flac")
        assertTrue(RatingTagWriter.write(f, 4).isSuccess)
        assertEquals(4, RatingTagReader.read(f))
    }

    @Test
    fun `writes a rating to opus and read returns it`() {
        val f = fixture("silence-1s.opus")
        assertTrue(RatingTagWriter.write(f, 9).isSuccess)
        assertEquals(9, RatingTagReader.read(f))
    }

    @Test
    fun `writes a rating to m4a and read returns it`() {
        val f = fixture("silence-1s.m4a")
        assertTrue(RatingTagWriter.write(f, 6).isSuccess)
        assertEquals(6, RatingTagReader.read(f))
    }

    @Test
    fun `writing null clears the rating`() {
        val f = fixture("silence-1s.mp3")
        RatingTagWriter.write(f, 7)
        assertTrue(RatingTagWriter.write(f, null).isSuccess)
        assertNull(RatingTagReader.read(f))
    }

    @Test
    fun `write preserves mtime within 2 seconds`() {
        val f = fixture("silence-1s.mp3")
        val before = f.lastModified()
        Thread.sleep(20)
        RatingTagWriter.write(f, 3)
        val after = f.lastModified()
        assertTrue("mtime drift was ${after - before}ms", kotlin.math.abs(after - before) < 2000)
    }

    @Test
    fun `write fails cleanly on unknown container`() {
        val txt = tmp.newFile("not-audio.txt").apply { writeText("hi") }
        assertTrue(RatingTagWriter.write(txt, 5).isFailure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `write rejects rating out of range`() {
        val f = fixture("silence-1s.mp3")
        RatingTagWriter.write(f, 11).getOrThrow()
    }
}
