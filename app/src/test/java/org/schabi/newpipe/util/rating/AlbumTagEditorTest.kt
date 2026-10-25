/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.util.rating

import java.io.File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlbumTagEditorTest {
    @Rule
    @JvmField
    val temporary = TemporaryFolder()

    @Test
    fun `album retagging preserves rating title artist comment and artwork across containers`() {
        for (extension in listOf("mp3", "m4a", "flac", "opus")) {
            val file = temporary.newFile("track.$extension")
            File("src/test/resources/audio/silence-1s.$extension").copyTo(file, overwrite = true)
            if (extension == "opus") {
                OpusTagEditor(file).writeFullComments(
                    mapOf("TITLE" to "Title", "ARTIST" to "Artist", "COMMENT" to "source URL", "ALBUM" to "Collection"),
                    artworkBytes = ByteArray(100_000) { (it % 251).toByte() },
                    artworkMime = "image/png"
                )
                RatingTagWriter.write(file, 8).getOrThrow()
                val before = OpusTagEditor(file).readAllComments()!!.filterNot { it.startsWith("ALBUM=") }
                AlbumTagEditor.write(file, "Specific album", 7)
                val after = OpusTagEditor(file).readAllComments()!!
                assertTrue(after.containsAll(before))
                assertEquals(1, after.count { it.startsWith("ALBUM=") })
            } else {
                val audio = AudioFileIO.read(file)
                val tag = audio.tagOrCreateAndSetDefault
                tag.setField(FieldKey.TITLE, "Title")
                tag.setField(FieldKey.ARTIST, "Artist")
                tag.setField(FieldKey.COMMENT, "source URL")
                audio.commit()
                RatingTagWriter.write(file, 8).getOrThrow()
                val duration = AudioFileIO.read(file).audioHeader.trackLength
                AlbumTagEditor.write(file, "Specific album", 7)
                val read = AudioFileIO.read(file)
                assertEquals("Title", read.tag.getFirst(FieldKey.TITLE))
                assertEquals("Artist", read.tag.getFirst(FieldKey.ARTIST))
                assertEquals("source URL", read.tag.getFirst(FieldKey.COMMENT))
                assertEquals(duration, read.audioHeader.trackLength)
            }
            assertEquals("$extension rating", 8, RatingTagReader.read(file))
            assertEquals("Specific album" to "7", AlbumTagEditor.read(file))
            // A second identical write must still produce valid tags.
            AlbumTagEditor.write(file, "Specific album", 7)
            assertEquals("Specific album" to "7", AlbumTagEditor.read(file))
        }
    }
}
