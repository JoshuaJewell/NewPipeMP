package org.schabi.newpipe.util.rating

import java.io.File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyPOPM

/**
 * Reads the user rating from an audio file's container-appropriate tag.
 *
 * See acetate/docs/superpowers/specs/2026-05-12-digikam-for-music-design.md §3.1
 * for the per-container conventions.
 *
 * Returns null when the file has no rating tag, the container is unsupported,
 * or the file cannot be parsed.
 */
object RatingTagReader {

    fun read(file: File): Int? {
        if (!file.isFile) return null
        val container = AudioContainer.fromFile(file) ?: return null
        return try {
            when (container) {
                AudioContainer.MP3 -> readPopm(file)
                AudioContainer.FLAC -> readVorbis(file)
                AudioContainer.OPUS -> readOpus(file)
                AudioContainer.M4A -> readItunesAtom(file)
            }
        } catch (_: Exception) {
            // Defensive: any tagger parse failure means we cannot read a
            // rating; fall back to "unrated" rather than propagate.
            null
        }
    }

    private fun readOpus(file: File): Int? {
        val value = OpusTagEditor(file).readRatingString() ?: return null
        return RatingTagConverter.ratingStringToStars(value)
    }

    private fun readPopm(file: File): Int? {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag as? AbstractID3v2Tag ?: return null
        val raw = tag.getFrame("POPM") ?: return null
        val bodies = collectPopmBodies(raw)
        if (bodies.isEmpty()) return null

        val preferOrder = listOf(
            "acetate@joshuajewell.dev",
            "newpipemp@joshuajewell.dev"
        )
        for (preferred in preferOrder) {
            val match = bodies.firstOrNull { it.emailToUser == preferred }
            if (match != null) {
                return RatingTagConverter.popmByteToStars(match.rating.toInt())
            }
        }
        return RatingTagConverter.popmByteToStars(bodies.first().rating.toInt())
    }

    private fun collectPopmBodies(raw: Any): List<FrameBodyPOPM> {
        val bodies = mutableListOf<FrameBodyPOPM>()
        when (raw) {
            is AbstractID3v2Frame -> (raw.body as? FrameBodyPOPM)?.let { bodies.add(it) }

            is List<*> -> raw.forEach {
                if (it is AbstractID3v2Frame) {
                    (it.body as? FrameBodyPOPM)?.let { b -> bodies.add(b) }
                }
            }
        }
        return bodies
    }

    private fun readVorbis(file: File): Int? {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag ?: return null
        // Vorbis tags don't always recognise FieldKey.RATING; use the raw key.
        val value = tag.getFirst("RATING").ifBlank { null } ?: return null
        return RatingTagConverter.ratingStringToStars(value)
    }

    private fun readItunesAtom(file: File): Int? {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag ?: return null
        val raw = tag.getFirst("----:com.apple.iTunes:RATING").ifBlank { null }
            ?: try {
                tag.getFirst(FieldKey.RATING).ifBlank { null }
            } catch (_: Exception) {
                null
            }
            ?: return null
        return RatingTagConverter.ratingStringToStars(raw)
    }
}
