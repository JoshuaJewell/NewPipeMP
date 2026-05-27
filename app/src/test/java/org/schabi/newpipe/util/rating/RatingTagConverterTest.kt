package org.schabi.newpipe.util.rating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RatingTagConverterTest {

    @Test
    fun `stars to POPM byte covers the full 1 to 10 table`() {
        val expected = mapOf(
            1 to 25, 2 to 51, 3 to 76, 4 to 102, 5 to 128,
            6 to 153, 7 to 179, 8 to 204, 9 to 230, 10 to 255
        )
        for ((stars, byte) in expected) {
            assertEquals("stars=$stars", byte, RatingTagConverter.starsToPopmByte(stars))
        }
    }

    @Test
    fun `POPM byte to stars covers the full table`() {
        val expected = mapOf(
            25 to 1, 51 to 2, 76 to 3, 102 to 4, 128 to 5,
            153 to 6, 179 to 7, 204 to 8, 230 to 9, 255 to 10
        )
        for ((byte, stars) in expected) {
            assertEquals("byte=$byte", stars, RatingTagConverter.popmByteToStars(byte))
        }
    }

    @Test
    fun `POPM byte 0 reads as unrated`() {
        assertNull(RatingTagConverter.popmByteToStars(0))
    }

    @Test
    fun `POPM byte buckets interior values to nearest star`() {
        // foobar2000 and MusicBee write specific values; we must read their values
        // back as the closest 1..10 bucket, not crash or return null.
        assertEquals(2, RatingTagConverter.popmByteToStars(40)) // near foobar2000 "2 stars"
        assertEquals(5, RatingTagConverter.popmByteToStars(120)) // near MusicBee "5 stars"
    }

    @Test
    fun `stars to Vorbis string is times-ten`() {
        for (stars in 1..10) {
            assertEquals("${stars * 10}", RatingTagConverter.starsToRatingString(stars))
        }
    }

    @Test
    fun `Vorbis string to stars accepts integers in 0 to 100`() {
        assertEquals(1, RatingTagConverter.ratingStringToStars("10"))
        assertEquals(7, RatingTagConverter.ratingStringToStars("70"))
        assertEquals(10, RatingTagConverter.ratingStringToStars("100"))
        assertNull(RatingTagConverter.ratingStringToStars("0"))
    }

    @Test
    fun `Vorbis string to stars handles whitespace and non-numeric`() {
        assertEquals(5, RatingTagConverter.ratingStringToStars(" 50 "))
        assertNull(RatingTagConverter.ratingStringToStars(""))
        assertNull(RatingTagConverter.ratingStringToStars("not a number"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stars to POPM byte rejects 0`() {
        RatingTagConverter.starsToPopmByte(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stars to POPM byte rejects 11`() {
        RatingTagConverter.starsToPopmByte(11)
    }
}
