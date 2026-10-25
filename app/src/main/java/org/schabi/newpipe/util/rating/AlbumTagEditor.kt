/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.util.rating

import java.io.File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

// Operates on a staged local copy, never directly on the user's file.
object AlbumTagEditor {
    fun read(file: File): Pair<String, String> {
        if (AudioContainer.fromFile(file) == AudioContainer.OPUS) {
            val comments = OpusTagEditor(file).readAllComments() ?: error("Missing Opus tags")
            fun value(key: String): String {
                return comments.firstOrNull { it.startsWith("$key=", ignoreCase = true) }?.substringAfter('=') ?: ""
            }
            return value("ALBUM") to value("TRACKNUMBER")
        }
        val tag = AudioFileIO.read(file).tag ?: return "" to ""
        return tag.getFirst(FieldKey.ALBUM) to tag.getFirst(FieldKey.TRACK)
    }

    fun write(file: File, album: String, number: Int) {
        require(album.isNotBlank() && number > 0)
        require(AudioContainer.fromFile(file) != null) { "Unsupported audio container" }
        if (AudioContainer.fromFile(file) == AudioContainer.OPUS) {
            OpusTagEditor(file).writeAlbumAndTrack(album, number)
        } else {
            val audio = AudioFileIO.read(file)
            val tag = audio.tagOrCreateAndSetDefault
            tag.setField(FieldKey.ALBUM, album)
            tag.setField(FieldKey.TRACK, number.toString())
            // An old album's total track count no longer describes this playlist.
            tag.deleteField(FieldKey.TRACK_TOTAL)
            audio.commit()
        }
        check(read(file) == (album to number.toString())) { "Album/track tag verification failed" }
    }
}
