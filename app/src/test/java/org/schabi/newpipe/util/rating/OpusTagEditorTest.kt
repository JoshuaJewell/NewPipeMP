package org.schabi.newpipe.util.rating

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpusTagEditorTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private fun fixture(): File {
        val src = File("src/test/resources/audio/silence-1s.opus")
        val dst = tmp.newFile("silence-1s.opus")
        src.copyTo(dst, overwrite = true)
        return dst
    }

    @Test
    fun `writeFullComments persists every key and reads them back`() {
        val f = fixture()
        val editor = OpusTagEditor(f)
        editor.writeFullComments(
            mapOf(
                "TITLE" to "Never Gonna Give You Up",
                "ARTIST" to "Rick Astley",
                "ALBUM" to "Whenever You Need Somebody",
                "TRACKNUMBER" to "1"
            )
        )
        val comments = OpusTagEditor(f).readAllComments()
        assertNotNull(comments)
        assertTrue("missing TITLE", comments!!.any { it == "TITLE=Never Gonna Give You Up" })
        assertTrue("missing ARTIST", comments.any { it == "ARTIST=Rick Astley" })
        assertTrue("missing ALBUM", comments.any { it == "ALBUM=Whenever You Need Somebody" })
        assertTrue("missing TRACKNUMBER", comments.any { it == "TRACKNUMBER=1" })
    }

    @Test
    fun `writeFullComments with artwork survives round-trip`() {
        val f = fixture()
        val artwork = ByteArray(180_000) { (it and 0xFF).toByte() }
        // 180KB artwork forces a multi-page tags packet (one page holds ~64KB).
        OpusTagEditor(f).writeFullComments(
            mapOf("TITLE" to "Multi-Page Test"),
            artworkBytes = artwork,
            artworkMime = "image/jpeg"
        )
        val comments = OpusTagEditor(f).readAllComments()
        assertNotNull(comments)
        assertTrue("missing TITLE", comments!!.any { it == "TITLE=Multi-Page Test" })
        val pictureEntry = comments.firstOrNull { it.startsWith("METADATA_BLOCK_PICTURE=") }
        assertNotNull("missing artwork comment", pictureEntry)
    }

    @Test
    fun `writeFullComments replacing existing keys does not duplicate them`() {
        val f = fixture()
        OpusTagEditor(f).writeFullComments(mapOf("TITLE" to "first"))
        OpusTagEditor(f).writeFullComments(mapOf("TITLE" to "second"))
        val comments = OpusTagEditor(f).readAllComments()!!
        assertEquals(
            "expected exactly one TITLE entry",
            1,
            comments.count { it.startsWith("TITLE=") }
        )
        assertTrue(comments.contains("TITLE=second"))
    }

    @Test
    fun `RATING write does not lose other keys`() {
        val f = fixture()
        OpusTagEditor(f).writeFullComments(
            mapOf("TITLE" to "Stay", "ARTIST" to "Rihanna")
        )
        OpusTagEditor(f).writeRatingString("90")
        val comments = OpusTagEditor(f).readAllComments()!!
        assertTrue(comments.contains("TITLE=Stay"))
        assertTrue(comments.contains("ARTIST=Rihanna"))
        assertTrue(comments.any { it.startsWith("RATING=") })
        assertEquals("90", OpusTagEditor(f).readRatingString())
    }

    @Test
    fun `readAllComments returns null for non-opus`() {
        val txt = tmp.newFile("not-audio.txt").apply { writeText("hi") }
        assertNull(OpusTagEditor(txt).readAllComments())
    }
}
