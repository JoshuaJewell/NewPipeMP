package org.schabi.newpipe.util.rating

import java.io.File

/**
 * Audio container kinds Acetate / NewPipeMP write rating tags into.
 * The kind drives temp-file extension choice in [us.shandian.giga.postprocessing.AudioMetadataTagging]
 * and dispatch in [RatingTagReader] / [RatingTagWriter].
 */
enum class AudioContainer(val extension: String) {
    MP3("mp3"),
    FLAC("flac"),
    OPUS("opus"),
    M4A("m4a");

    companion object {
        /**
         * Sniffs the container from a file extension. Returns null for unknown
         * containers (rating support is currently limited to the four above;
         * callers should not assume coverage of e.g. .wav, .wma).
         */
        @JvmStatic
        fun fromExtension(ext: String): AudioContainer? = entries.firstOrNull { it.extension.equals(ext.trimStart('.'), ignoreCase = true) }

        @JvmStatic
        fun fromFile(file: File): AudioContainer? = fromExtension(file.extension)
    }
}
