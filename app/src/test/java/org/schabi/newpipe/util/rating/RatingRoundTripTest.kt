package org.schabi.newpipe.util.rating

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RatingRoundTripTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private var counter = 0
    private fun fixture(name: String): File {
        val src = File("src/test/resources/audio/$name")
        // Unique destination per call so loop iterations don't collide with
        // TemporaryFolder's no-overwrite policy on newFile.
        val dst = File(tmp.root, "$counter-$name").also { counter++ }
        src.copyTo(dst, overwrite = true)
        return dst
    }

    @Test
    fun `every star value round-trips through every container`() {
        val fixtures = listOf(
            "silence-1s.mp3",
            "silence-1s.flac",
            "silence-1s.opus",
            "silence-1s.m4a"
        )
        for (fname in fixtures) {
            for (stars in 1..10) {
                val f = fixture(fname)
                RatingTagWriter.write(f, stars).getOrThrow()
                val read = RatingTagReader.read(f)
                assertEquals("$fname / stars=$stars", stars, read)
            }
        }
    }

    @Test
    fun `writing then clearing leaves no readable rating`() {
        val fixtures = listOf(
            "silence-1s.mp3",
            "silence-1s.flac",
            "silence-1s.opus",
            "silence-1s.m4a"
        )
        for (fname in fixtures) {
            val f = fixture(fname)
            RatingTagWriter.write(f, 7).getOrThrow()
            RatingTagWriter.write(f, null).getOrThrow()
            assertEquals("$fname", null, RatingTagReader.read(f))
        }
    }
}
