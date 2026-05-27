package org.schabi.newpipe.util.rating

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Opus-in-Ogg tag editor: read and write Vorbis-style comments and embedded
 * cover-art pictures inside an `.opus` file.
 *
 * Why this exists: JAudioTagger 3.0.1 (the version on the current dependency
 * graph) only recognises Vorbis-codec OGG identification packets, not Opus's
 * `OpusHead` packet, so neither read nor write paths can go through it. This
 * handler implements the slice of RFC 7845 §5.2 (OpusTags) and RFC 3533
 * (Ogg page framing) needed to read and write tags without touching audio.
 *
 * Scope:
 *  - One Ogg bitstream per file (one serial number throughout).
 *  - OpusHead is page 0 (BOS); OpusTags begins at page 1 and may span pages.
 *  - The new OpusTags packet may span multiple Ogg pages when artwork is
 *    embedded; audio pages following the tags are re-numbered and have their
 *    CRCs recomputed if the page count shifts.
 *
 * Cover art uses the FLAC `METADATA_BLOCK_PICTURE` convention, base64-encoded
 * as a single Vorbis comment.
 */
internal class OpusTagEditor(private val file: File) {

    companion object {
        private const val OPUS_TAGS_MAGIC = "OpusTags"
        private const val MAX_SEGMENT_VALUE = 255
        private const val MAX_PAGE_PAYLOAD = MAX_SEGMENT_VALUE * MAX_SEGMENT_VALUE
        private const val FLAC_PICTURE_TYPE_FRONT_COVER = 3
        private const val OGG_PAGE_HEADER_FIXED_SIZE = 27
    }

    /**
     * Reads the rating string (the value of any `RATING=` / `RATING:` Vorbis
     * comment) from the file. Returns null when absent or the file isn't a
     * recognisable Opus.
     */
    fun readRatingString(): String? {
        val tags = readTagsPacket() ?: return null
        val (_, comments) = parseOpusTags(tags) ?: return null
        return comments.firstOrNull {
            it.startsWith("RATING=", ignoreCase = true) ||
                it.startsWith("RATING:", ignoreCase = true)
        }?.substringAfter('=')
    }

    /**
     * Writes (or clears, when [ratingString] is null) the `RATING` comment.
     * Replaces any existing `RATING=` / `RATING:` entries; preserves all
     * other comments and the vendor string.
     */
    fun writeRatingString(ratingString: String?) {
        val tagBytes = readTagsPacket() ?: error("Opus tags packet missing")
        val (vendor, comments) = parseOpusTags(tagBytes)
            ?: error("OpusTags payload malformed")
        val mutated = comments.toMutableList()
        mutated.removeAll {
            it.startsWith("RATING=", ignoreCase = true) ||
                it.startsWith("RATING:", ignoreCase = true)
        }
        if (ratingString != null) mutated.add("RATING=$ratingString")
        rewriteWithTags(vendor, mutated)
    }

    /**
     * Reads all Vorbis comments in the file's OpusTags packet as a flat list.
     * Comments are returned in their original on-disk order.
     */
    fun readAllComments(): List<String>? {
        val tags = readTagsPacket() ?: return null
        val (_, comments) = parseOpusTags(tags) ?: return null
        return comments
    }

    /**
     * Overwrites every Vorbis comment with [comments] (key/value pairs). If
     * [artworkBytes] is provided, encodes a `METADATA_BLOCK_PICTURE` entry
     * carrying the image. Vendor string is preserved.
     *
     * Existing comments are replaced wholesale: callers that want to merge
     * must read first, mutate, then call this.
     */
    @JvmOverloads
    fun writeFullComments(
        comments: Map<String, String>,
        artworkBytes: ByteArray? = null,
        artworkMime: String? = null
    ) {
        val tagBytes = readTagsPacket() ?: error("Opus tags packet missing")
        val (vendor, _) = parseOpusTags(tagBytes) ?: error("OpusTags payload malformed")
        val list = comments.map { (k, v) -> "$k=$v" }.toMutableList()
        if (artworkBytes != null) {
            list.add(
                "METADATA_BLOCK_PICTURE=" + encodeMetadataBlockPicture(
                    artworkBytes,
                    artworkMime ?: "image/jpeg"
                )
            )
        }
        rewriteWithTags(vendor, list)
    }

    // ============================================================================
    // Implementation
    // ============================================================================

    private fun rewriteWithTags(vendor: String, comments: List<String>) {
        val pages = readPages()
        require(pages.size >= 2) { "Opus file has fewer than 2 Ogg pages" }
        val tagSpan = findTagSpan(pages)
            ?: error("OpusTags pages not located after page 0")

        val newPacket = encodeOpusTags(vendor, comments)
        val basePage = pages[tagSpan.first]
        val newTagPages = encodeMultiPage(
            payload = newPacket,
            serial = basePage.serial,
            startSequence = basePage.sequence,
            firstHeaderType = basePage.headerType
        )

        // Renumber pages after the tag span if the count changed.
        val oldTagPageCount = tagSpan.last - tagSpan.first + 1
        val newTagPageCount = newTagPages.size
        val seqDelta = newTagPageCount - oldTagPageCount

        val renumberedTail = if (seqDelta == 0) {
            pages.drop(tagSpan.last + 1).map { it.rawBytes }
        } else {
            pages.drop(tagSpan.last + 1).map { p ->
                renumberAndRecrc(p.rawBytes, p.sequence + seqDelta)
            }
        }

        val totalSize = pages[0].rawBytes.size +
            newTagPages.sumOf { it.size } +
            renumberedTail.sumOf { it.size }
        val out = ByteBuffer.allocate(totalSize)
        out.put(pages[0].rawBytes)
        for (tp in newTagPages) out.put(tp)
        for (tail in renumberedTail) out.put(tail)
        file.writeBytes(out.array().copyOf(out.position()))
    }

    /**
     * Returns the inclusive (firstIndex, lastIndex) page range that holds the
     * OpusTags packet. Walks forward from page 1 while continuation lacing
     * (last segment = 255) indicates more pages.
     */
    private fun findTagSpan(pages: List<Page>): IntRange? {
        if (pages.size < 2) return null
        if (!pages[1].payload.startsWith(OPUS_TAGS_MAGIC.toByteArray())) return null
        var last = 1
        while (last < pages.size && pages[last].lastSegmentLength == MAX_SEGMENT_VALUE) {
            last++
        }
        return 1..minOf(last, pages.lastIndex)
    }

    /**
     * Returns the OpusTags packet bytes (assembled across any continuation
     * pages), or null when the file isn't a parseable Opus.
     */
    private fun readTagsPacket(): ByteArray? {
        val pages = try {
            readPages()
        } catch (_: Exception) {
            return null
        }
        val span = findTagSpan(pages) ?: return null
        val payloadSize = (span.first..span.last).sumOf { pages[it].payload.size }
        val packet = ByteArray(payloadSize)
        var off = 0
        for (i in span) {
            pages[i].payload.copyInto(packet, off)
            off += pages[i].payload.size
        }
        return packet
    }

    private data class Page(
        val headerType: Byte,
        val granule: Long,
        val serial: Int,
        val sequence: Int,
        val payload: ByteArray,
        val rawBytes: ByteArray,
        val lastSegmentLength: Int
    )

    private fun readPages(): List<Page> {
        val data = file.readBytes()
        val pages = mutableListOf<Page>()
        var i = 0
        while (i + OGG_PAGE_HEADER_FIXED_SIZE <= data.size) {
            require(
                data[i] == 'O'.code.toByte() && data[i + 1] == 'g'.code.toByte() &&
                    data[i + 2] == 'g'.code.toByte() && data[i + 3] == 'S'.code.toByte()
            ) { "Ogg page not aligned at offset $i" }
            val bb = ByteBuffer.wrap(data, i, OGG_PAGE_HEADER_FIXED_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
            bb.position(i + 5)
            val headerType = bb.get()
            val granule = bb.long
            val serial = bb.int
            val sequence = bb.int
            bb.int // CRC, not verified
            val segCount = bb.get().toInt() and 0xFF
            val segTableStart = i + OGG_PAGE_HEADER_FIXED_SIZE
            val segTableEnd = segTableStart + segCount
            require(segTableEnd <= data.size) { "Ogg page truncated at $i" }
            var payloadLen = 0
            for (s in segTableStart until segTableEnd) {
                payloadLen += data[s].toInt() and 0xFF
            }
            val payloadEnd = segTableEnd + payloadLen
            require(payloadEnd <= data.size) { "Ogg payload truncated at $segTableEnd" }
            val payload = data.copyOfRange(segTableEnd, payloadEnd)
            val rawBytes = data.copyOfRange(i, payloadEnd)
            val lastSeg = if (segCount == 0) 0 else (data[segTableEnd - 1].toInt() and 0xFF)
            pages += Page(headerType, granule, serial, sequence, payload, rawBytes, lastSeg)
            i = payloadEnd
        }
        return pages
    }

    private fun parseOpusTags(payload: ByteArray): Pair<String, List<String>>? {
        if (payload.size < 8 + 4) return null
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8)
        val vendorLen = bb.int
        if (vendorLen < 0 || bb.position() + vendorLen + 4 > payload.size) return null
        val vendorBytes = ByteArray(vendorLen)
        bb.get(vendorBytes)
        val vendor = String(vendorBytes, Charsets.UTF_8)
        val count = bb.int
        if (count < 0) return null
        val comments = mutableListOf<String>()
        for (n in 0 until count) {
            if (bb.position() + 4 > payload.size) return null
            val len = bb.int
            if (len < 0 || bb.position() + len > payload.size) return null
            val cb = ByteArray(len)
            bb.get(cb)
            comments += String(cb, Charsets.UTF_8)
        }
        return vendor to comments
    }

    private fun encodeOpusTags(vendor: String, comments: List<String>): ByteArray {
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        val commentBytes = comments.map { it.toByteArray(Charsets.UTF_8) }
        val size = 8 + 4 + vendorBytes.size + 4 + commentBytes.sumOf { 4 + it.size }
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(OPUS_TAGS_MAGIC.toByteArray())
        bb.putInt(vendorBytes.size)
        bb.put(vendorBytes)
        bb.putInt(commentBytes.size)
        for (cb in commentBytes) {
            bb.putInt(cb.size)
            bb.put(cb)
        }
        return bb.array()
    }

    /**
     * Encodes the [payload] (a single packet) across one or more Ogg pages.
     * The first page uses [firstHeaderType]; continuation pages use 1.
     */
    private fun encodeMultiPage(
        payload: ByteArray,
        serial: Int,
        startSequence: Int,
        firstHeaderType: Byte
    ): List<ByteArray> {
        val pages = mutableListOf<ByteArray>()
        var offset = 0
        var sequence = startSequence
        while (offset < payload.size || pages.isEmpty()) {
            val take = minOf(MAX_PAGE_PAYLOAD, payload.size - offset)
            val pagePayload = payload.copyOfRange(offset, offset + take)
            val isLast = offset + take == payload.size
            val headerType = if (pages.isEmpty()) firstHeaderType else 1.toByte()
            pages += encodePage(
                serial = serial,
                sequence = sequence,
                granule = 0L,
                headerType = headerType,
                payload = pagePayload,
                isLastPageOfPacket = isLast
            )
            offset += take
            sequence++
            if (isLast) break
        }
        return pages
    }

    private fun encodePage(
        serial: Int,
        sequence: Int,
        granule: Long,
        headerType: Byte,
        payload: ByteArray,
        isLastPageOfPacket: Boolean
    ): ByteArray {
        // Build segment table: payload split into 255-byte chunks; final chunk
        // size indicates packet termination (any value < 255).
        val segs = mutableListOf<Int>()
        var remaining = payload.size
        while (remaining >= MAX_SEGMENT_VALUE) {
            segs += MAX_SEGMENT_VALUE
            remaining -= MAX_SEGMENT_VALUE
        }
        if (isLastPageOfPacket || remaining > 0) {
            segs += remaining
        }
        require(segs.size in 1..MAX_SEGMENT_VALUE) {
            "page segment table out of range: ${segs.size}"
        }

        val headerSize = OGG_PAGE_HEADER_FIXED_SIZE + segs.size
        val bb = ByteBuffer.allocate(headerSize + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put('O'.code.toByte()).put('g'.code.toByte())
            .put('g'.code.toByte()).put('S'.code.toByte())
        bb.put(0.toByte())
        bb.put(headerType)
        bb.putLong(granule)
        bb.putInt(serial)
        bb.putInt(sequence)
        bb.putInt(0) // CRC placeholder
        bb.put(segs.size.toByte())
        for (s in segs) bb.put(s.toByte())
        bb.put(payload)

        val raw = bb.array()
        val crc = oggCrc32(raw)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc)
        return raw
    }

    /**
     * Patches the sequence number of an existing page in-place (returning a
     * fresh ByteArray) and recomputes its CRC.
     */
    private fun renumberAndRecrc(pageBytes: ByteArray, newSequence: Int): ByteArray {
        val copy = pageBytes.copyOf()
        ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putInt(18, newSequence)
        ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putInt(22, 0)
        val crc = oggCrc32(copy)
        ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc)
        return copy
    }

    /**
     * Builds a FLAC `METADATA_BLOCK_PICTURE` body (then base64-encoded) per
     * https://xiph.org/flac/format.html#metadata_block_picture. Width, height,
     * depth, colors are written as 0 (callers don't know them and most
     * players ignore them).
     */
    private fun encodeMetadataBlockPicture(picture: ByteArray, mime: String): String {
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        val descBytes = ByteArray(0)
        val size = 4 + 4 + mimeBytes.size + 4 + descBytes.size + 4 + 4 + 4 + 4 + 4 + picture.size
        // FLAC PICTURE block uses big-endian.
        val bb = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(FLAC_PICTURE_TYPE_FRONT_COVER)
        bb.putInt(mimeBytes.size)
        bb.put(mimeBytes)
        bb.putInt(descBytes.size)
        bb.put(descBytes)
        bb.putInt(0) // width
        bb.putInt(0) // height
        bb.putInt(0) // color depth
        bb.putInt(0) // colors used
        bb.putInt(picture.size)
        bb.put(picture)
        return Base64.getEncoder().encodeToString(bb.array())
    }

    // RFC 3533 §5: Ogg CRC-32, polynomial 0x04C11DB7, MSB-first, no input/
    // output reflection, init 0, final XOR 0. java.util.zip.CRC32 is IEEE/
    // reflected and not compatible.
    private val crcTable: IntArray = IntArray(256).also { table ->
        for (i in 0..255) {
            var crc = i shl 24
            for (b in 0..7) {
                crc = if ((crc and 0x80000000.toInt()) != 0) {
                    (crc shl 1) xor 0x04C11DB7
                } else {
                    crc shl 1
                }
            }
            table[i] = crc
        }
    }

    private fun oggCrc32(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            val idx = ((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF
            crc = (crc shl 8) xor crcTable[idx]
        }
        return crc
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (k in prefix.indices) if (this[k] != prefix[k]) return false
        return true
    }
}
