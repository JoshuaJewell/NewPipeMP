/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download.library

import java.io.Serializable
import org.schabi.newpipe.adaptive.StreamUrls

// One entry of a playlist, as the library download sees it.
data class LibraryTrack(
    val serviceId: Int,
    val url: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class LibraryPlaylist(val name: String, val tracks: List<LibraryTrack>) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

// A track to download once for the whole library. album and trackNumber come from the
// first playlist it appears in, for the file's tags.
data class PlannedTrack(
    val key: String,
    val track: LibraryTrack,
    val baseName: String,
    val album: String,
    val trackNumber: Int
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

// A playlist file to write: its entries, in order, as track keys (repeats kept).
data class PlannedPlaylist(val name: String, val fileName: String, val keys: List<String>) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class LibraryPlan(
    // Tracks with no usable local copy yet, each once.
    val toDownload: List<PlannedTrack>,
    // Keys of tracks that already have a local copy.
    val alreadyHave: Set<String>,
    val playlists: List<PlannedPlaylist>,
    // Every distinct track, keyed, whether it is to be downloaded or not.
    val tracks: Map<String, LibraryTrack>,
    val totalEntries: Int
)

// Plans a download of every playlist in the library: one file per distinct track, in one
// folder, and one playlist file per playlist that lists them.
object LibraryPlanner {

    private const val MAX_NAME_LENGTH = 120

    // The same video reached through different URLs (music.youtube.com, youtu.be, a
    // trailing &list= or &t=, a doubled slash in a Bandcamp path) is one track.
    @JvmStatic
    fun trackKey(serviceId: Int, url: String): String {
        StreamUrls.youtubeId(url.trim())?.let { return "$serviceId|yt:$it" }
        return "$serviceId|${StreamUrls.canonical(serviceId, url)}"
    }

    @JvmStatic
    fun plan(
        playlists: List<LibraryPlaylist>,
        existingKeys: Set<String>,
        takenNames: Set<String> = emptySet()
    ): LibraryPlan {
        val tracks = LinkedHashMap<String, LibraryTrack>()
        val firstSeen = HashMap<String, Pair<String, Int>>()
        val plannedPlaylists = ArrayList<PlannedPlaylist>()
        val playlistNames = HashSet<String>()
        var total = 0
        for (playlist in playlists) {
            val keys = ArrayList<String>(playlist.tracks.size)
            playlist.tracks.forEachIndexed { index, t ->
                val key = trackKey(t.serviceId, t.url)
                keys += key
                total += 1
                if (key !in tracks) {
                    tracks[key] = t
                    firstSeen[key] = playlist.name to index + 1
                }
            }
            val fileName = unique(safeName(playlist.name).ifEmpty { "Playlist" }, ".m3u8", playlistNames)
            plannedPlaylists += PlannedPlaylist(playlist.name, fileName, keys)
        }

        val names = takenNames.mapTo(HashSet()) { it.lowercase() }
        val toDownload = ArrayList<PlannedTrack>()
        val have = HashSet<String>()
        for ((key, t) in tracks) {
            if (key in existingKeys) {
                have += key
                continue
            }
            val base = uniqueBaseName(displayName(t), key, names)
            val (album, number) = firstSeen.getValue(key)
            toDownload += PlannedTrack(key, t, base, album, number)
        }
        return LibraryPlan(toDownload, have, plannedPlaylists, tracks, total)
    }

    // "Artist - Title", unless the title already names the artist or there is none.
    @JvmStatic
    fun displayName(t: LibraryTrack): String {
        val artist = t.artist.trim()
        val title = t.title.trim()
        return when {
            artist.isEmpty() -> title
            title.startsWith(artist, ignoreCase = true) -> title
            else -> "$artist - $title"
        }
    }

    // A file name that is valid on Android, FAT/exFAT cards and desktop filesystems.
    @JvmStatic
    fun safeName(name: String): String {
        val cleaned = name
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.', ' ')
        return if (cleaned.length <= MAX_NAME_LENGTH) cleaned else cleaned.take(MAX_NAME_LENGTH).trimEnd('.', ' ')
    }

    private fun uniqueBaseName(display: String, key: String, taken: MutableSet<String>): String {
        val base = safeName(display).ifEmpty { "Track" }
        if (taken.add(base.lowercase())) return base
        // Two different tracks with the same name: tell them apart by video id.
        val id = key.substringAfter("|yt:", "")
        if (id.isNotEmpty()) {
            val withId = "$base [$id]"
            if (taken.add(withId.lowercase())) return withId
        }
        return unique(base, "", taken)
    }

    private fun unique(base: String, suffix: String, taken: MutableSet<String>): String {
        if (taken.add((base + suffix).lowercase())) return base + suffix
        var n = 2
        while (!taken.add("$base ($n)$suffix".lowercase())) n += 1
        return "$base ($n)$suffix"
    }
}

data class M3uEntry(val path: String, val durationSeconds: Long, val label: String)

object M3u {
    // Extended M3U in UTF-8 (.m3u8), with paths relative to the playlist file, which VLC
    // and most players resolve against the playlist's own folder.
    @JvmStatic
    fun write(name: String, entries: List<M3uEntry>): String = buildString {
        append("#EXTM3U\n")
        append("#PLAYLIST:").append(name.replace('\n', ' ')).append('\n')
        for (e in entries) {
            val seconds = if (e.durationSeconds > 0) e.durationSeconds else -1
            append("#EXTINF:").append(seconds).append(',').append(e.label.replace('\n', ' ')).append('\n')
            append(e.path).append('\n')
        }
    }
}

object RelativePath {
    // The path of file relative to dir, both given as "volume:a/b/c" (a storage
    // document id) or "/a/b/c" (a file path); null when they are on different volumes.
    @JvmStatic
    fun between(dir: String, file: String): String? {
        val (dirVolume, dirPath) = split(dir)
        val (fileVolume, filePath) = split(file)
        if (dirVolume != fileVolume) return null
        val d = dirPath.split('/').filter { it.isNotEmpty() }
        val f = filePath.split('/').filter { it.isNotEmpty() }
        if (f.isEmpty()) return null
        var common = 0
        while (common < d.size && common < f.size - 1 && d[common] == f[common]) common += 1
        val up = List(d.size - common) { ".." }
        return (up + f.drop(common)).joinToString("/")
    }

    private fun split(p: String): Pair<String, String> {
        val colon = p.indexOf(':')
        return if (!p.startsWith("/") && colon >= 0) p.substring(0, colon) to p.substring(colon + 1) else "" to p
    }
}
