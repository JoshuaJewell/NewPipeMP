package org.schabi.newpipe.util.rating

import java.io.File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.framebody.FrameBodyPOPM

/**
 * Writes the user rating into the audio file's container-appropriate tag.
 *
 * See acetate/docs/superpowers/specs/2026-05-12-digikam-for-music-design.md §3.1
 * for conventions. Preserves mtime within filesystem precision; never deletes
 * other tag fields.
 *
 * Returns a [Result]; never throws on container parse failure.
 */
object RatingTagWriter {

    private const val POPM_EMAIL = "acetate@joshuajewell.dev"

    /**
     * Java-friendly bridge: writes the rating and returns true on success,
     * false on any failure. Kotlin's [Result] value class doesn't surface
     * cleanly across the Java boundary because of name mangling, so callers
     * from Java should use this method instead of [write].
     */
    @JvmStatic
    fun writeOrFalse(file: File, stars: Int?): Boolean = write(file, stars).isSuccess

    /**
     * Write [stars] (1..10) or null (clear the rating) to [file].
     */
    fun write(file: File, stars: Int?): Result<Unit> {
        if (stars != null) {
            require(stars in 1..10) { "stars must be 1..10, was $stars" }
        }
        if (!file.isFile) {
            return Result.failure(IllegalArgumentException("not a file: $file"))
        }
        val container = AudioContainer.fromFile(file)
            ?: return Result.failure(IllegalArgumentException("unknown container: ${file.name}"))
        val originalMtime = file.lastModified()
        return runCatching {
            when (container) {
                AudioContainer.MP3 -> writePopm(file, stars)
                AudioContainer.FLAC -> writeVorbis(file, stars)
                AudioContainer.OPUS -> writeOpus(file, stars)
                AudioContainer.M4A -> writeItunesAtom(file, stars)
            }
            // Restore mtime; tag writes touch it.
            file.setLastModified(originalMtime)
            Unit
        }
    }

    private fun writeOpus(file: File, stars: Int?) {
        OpusTagEditor(file).writeRatingString(
            if (stars == null) null else RatingTagConverter.starsToRatingString(stars)
        )
    }

    private fun writePopm(file: File, stars: Int?) {
        val audio = AudioFileIO.read(file)
        val tag = (audio.tagOrCreateAndSetDefault as? AbstractID3v2Tag)
            ?: error("not an ID3v2 tag")
        // Drop any POPM frame previously written under our namespace.
        removeOurPopm(tag)
        if (stars != null) {
            val frame = ID3v24Frame("POPM").apply {
                body = FrameBodyPOPM().apply {
                    emailToUser = POPM_EMAIL
                    rating = RatingTagConverter.starsToPopmByte(stars).toLong()
                    counter = 0L
                }
            }
            tag.setFrame(frame)
        }
        audio.commit()
    }

    private fun removeOurPopm(tag: AbstractID3v2Tag) {
        val existing = tag.getFrame("POPM") ?: return
        val frames: List<AbstractID3v2Frame> = when (existing) {
            is AbstractID3v2Frame -> listOf(existing)
            is List<*> -> existing.filterIsInstance<AbstractID3v2Frame>()
            else -> emptyList()
        }
        val mine = frames.any { (it.body as? FrameBodyPOPM)?.emailToUser == POPM_EMAIL }
        if (mine) {
            // JAudioTagger removeFrame removes all POPM frames; rebuild
            // foreign-namespace frames after.
            val others = frames
                .filter { (it.body as? FrameBodyPOPM)?.emailToUser != POPM_EMAIL }
                .toList()
            tag.removeFrame("POPM")
            for (f in others) tag.setFrame(f)
        }
    }

    private fun writeVorbis(file: File, stars: Int?) {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault ?: error("no tag")
        if (stars == null) {
            try {
                tag.deleteField(FieldKey.RATING)
            } catch (_: Exception) { /* nothing */ }
            try {
                tag.deleteField("RATING")
            } catch (_: Exception) { /* nothing */ }
        } else {
            val value = RatingTagConverter.starsToRatingString(stars)
            tag.setField(tag.createField(FieldKey.RATING, value))
        }
        audio.commit()
    }

    private fun writeItunesAtom(file: File, stars: Int?) {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault ?: error("no tag")
        if (stars == null) {
            try {
                tag.deleteField(FieldKey.RATING)
            } catch (_: Exception) { /* nothing */ }
        } else {
            val value = RatingTagConverter.starsToRatingString(stars)
            tag.setField(tag.createField(FieldKey.RATING, value))
        }
        audio.commit()
    }
}
