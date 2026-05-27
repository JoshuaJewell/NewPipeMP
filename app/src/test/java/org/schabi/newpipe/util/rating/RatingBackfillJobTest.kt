package org.schabi.newpipe.util.rating

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The runner's responsibility is "compute the list of (streamId, fileRating)
 * updates from a sequence of (streamId, file) inputs." Room I/O is integration-
 * covered separately by [RatingBackfillJob.runOnce]; this tests the compute
 * side, which is pure.
 */
class RatingBackfillJobTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private var counter = 0
    private fun fixture(name: String): File {
        val src = File("src/test/resources/audio/$name")
        val dst = File(tmp.root, "$counter-$name").also { counter++ }
        src.copyTo(dst, overwrite = true)
        return dst
    }

    @Test
    fun `compute returns one update per rated file`() {
        val f1 = fixture("silence-1s.mp3")
        val f2 = fixture("silence-1s.flac")
        val f3 = fixture("silence-1s.opus")
        RatingTagWriter.write(f1, 3).getOrThrow()
        RatingTagWriter.write(f2, 8).getOrThrow()
        // f3 left unrated

        val updates = RatingBackfillJob.compute(
            listOf(
                10L to f1,
                11L to f2,
                12L to f3
            )
        )

        assertEquals(listOf(10L to 3, 11L to 8), updates)
    }

    @Test
    fun `compute skips missing files`() {
        val missing = File(tmp.root, "nope.mp3")
        val updates = RatingBackfillJob.compute(listOf(99L to missing))
        assertEquals(emptyList<Pair<Long, Int>>(), updates)
    }
}
