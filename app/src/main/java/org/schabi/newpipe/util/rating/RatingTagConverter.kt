package org.schabi.newpipe.util.rating

import kotlin.math.abs

/**
 * Pure conversions between Acetate / NewPipeMP's internal 1..10 star rating
 * and the container-specific tag value formats.
 *
 * See acetate/docs/superpowers/specs/2026-05-12-digikam-for-music-design.md §3.1
 * for the full encoding table.
 */
object RatingTagConverter {

    private val popmTable = intArrayOf(25, 51, 76, 102, 128, 153, 179, 204, 230, 255)

    /**
     * Star → POPM byte (ID3v2 Popularimeter rating field).
     * 1..10 maps to 25, 51, 76, 102, 128, 153, 179, 204, 230, 255.
     */
    fun starsToPopmByte(stars: Int): Int {
        require(stars in 1..10) { "stars must be 1..10, was $stars" }
        return popmTable[stars - 1]
    }

    /**
     * POPM byte → star. Buckets to the nearest defined star value.
     * Byte 0 (the ID3 spec's "unrated") returns null.
     */
    fun popmByteToStars(byte: Int): Int? {
        require(byte in 0..255) { "POPM byte must be 0..255, was $byte" }
        if (byte == 0) return null
        var bestIdx = 0
        var bestDist = Int.MAX_VALUE
        for ((i, v) in popmTable.withIndex()) {
            val d = abs(byte - v)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx + 1
    }

    /**
     * Star → Vorbis Comment / iTunes-atom string value.
     * The de facto convention is "10".."100" in steps of 10.
     */
    fun starsToRatingString(stars: Int): String {
        require(stars in 1..10) { "stars must be 1..10, was $stars" }
        return (stars * 10).toString()
    }

    /**
     * Vorbis Comment / iTunes-atom string → star.
     * Accepts trimmed integer strings 1..100; returns null for "0", empty,
     * or non-numeric input.
     */
    fun ratingStringToStars(value: String): Int? {
        val n = value.trim().toIntOrNull() ?: return null
        if (n <= 0) return null
        return ((n + 5) / 10).coerceIn(1, 10)
    }
}
