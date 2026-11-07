/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.util

// Recognises a stream's metadata as damaged, and reads a title and artist back from a
// downloaded file's name. The offline stream lookup once built its StreamInfo with the
// constructor's arguments out of order, so the uploader's URL became the track's name,
// the uploader was lost and the duration went into the age limit; recording the view
// then wrote that over the stored row. Kept free of Android so the rules can be tested.
object StreamMetadataDamage {

    // A title that is missing, or is a URL (the stream's own, its uploader's, or any).
    @JvmStatic
    fun isBadTitle(title: String?, url: String?, uploaderUrl: String?): Boolean {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return true
        if (t == url?.trim() || t == uploaderUrl?.trim()) return true
        return looksLikeUrl(t)
    }

    // An uploader that carries nothing: missing, blank, a URL, or the offline path's
    // placeholder.
    @JvmStatic
    fun isBadUploader(uploader: String?): Boolean {
        val u = uploader?.trim().orEmpty()
        return u.isEmpty() || u == UNKNOWN || looksLikeUrl(u)
    }

    @JvmStatic
    fun looksLikeUrl(s: String): Boolean {
        val t = s.trim().lowercase()
        return t.startsWith("http://") || t.startsWith("https://") || "://" in t || "/channel/" in t
    }

    data class FromFileName(val artist: String?, val title: String)

    // "Artist - Title.ext" (the library download's names), with an optional track number
    // ("05 - Title"); a name with no " - " is all title. Null for an empty name.
    @JvmStatic
    fun fromFileName(fileName: String): FromFileName? {
        val base = fileName.substringAfterLast('/').replaceFirst(Regex("\\.[^.]{1,5}$"), "").trim()
        val withoutNumber = base.replaceFirst(Regex("^\\d{1,3}\\s*-\\s*"), "").trim()
        if (withoutNumber.isEmpty()) return null
        val cut = withoutNumber.indexOf(" - ")
        if (cut <= 0 || cut + 3 >= withoutNumber.length) return FromFileName(null, withoutNumber)
        return FromFileName(withoutNumber.substring(0, cut).trim(), withoutNumber.substring(cut + 3).trim())
    }

    const val UNKNOWN = "Unknown"
}
