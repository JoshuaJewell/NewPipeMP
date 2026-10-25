/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download.library

import java.util.Locale

// A file seen in the audio download folder. `size` is -1 when the provider does not
// report it; a zero-length file is ignored because it is usually an unfinished download.
data class LocalAudioFile(
    val name: String,
    val uri: String,
    val size: Long = -1L,
    val depth: Int = 0,
    val inLibraryFolder: Boolean = false
)

// Matches files already in the download folder to the tracks a library download would
// fetch. The offline mapping table is not enough after an update: files downloaded by
// an older build, a cleared download history or a SAF URI whose permission was lost
// leave no usable mapping even though the audio is still on the phone. Names are
// matched instead, with a video id used to disambiguate same-named tracks.
object LibraryFileMatcher {
    private val AUDIO_EXTENSIONS = setOf(
        "m4a", "mp3", "opus", "ogg", "oga", "webm", "flac", "wav", "aac",
        "m4b", "mp4", "3gp", "mkv"
    )
    private val BRACKETED_ID = Regex("""[\[(]([A-Za-z0-9_-]{11})[\])]""")
    private val ID_TOKEN = Regex("""(?<![A-Za-z0-9_-])([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""")
    private val LEADING_TRACK_NUMBER = Regex(
        """^\s*(?:track\s*)?\d{1,4}\s*(?:[-._)]\s*)+""",
        RegexOption.IGNORE_CASE
    )
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")

    private val FILE_PREFERENCE = compareByDescending<LocalAudioFile> { it.inLibraryFolder }
        .thenBy { it.depth }
        .thenByDescending { it.size }
        .thenBy { it.name.length }
        .thenBy { it.name }

    @JvmStatic
    fun isAudioFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in AUDIO_EXTENSIONS

    @JvmStatic
    fun baseName(name: String): String = name.substringBeforeLast('.', name)

    /**
     * Returns one usable file URI per track key. `plannedNames` should contain the name
     * the planner would use for every track (build it with an empty `existingKeys` set).
     */
    @JvmStatic
    fun match(
        tracks: Map<String, LibraryTrack>,
        plannedNames: Map<String, String>,
        files: List<LocalAudioFile>,
        allowFuzzy: Boolean = true
    ): Map<String, String> {
        if (tracks.isEmpty() || files.isEmpty()) return emptyMap()

        val byYoutubeId = HashMap<String, MutableList<String>>()
        val byNormalizedName = HashMap<String, MutableList<String>>()
        for ((key, track) in tracks) {
            val id = key.substringAfter("|yt:", "")
            if (id.isNotEmpty()) {
                byYoutubeId.getOrPut(id) { ArrayList() }.add(key)
            }
            val candidates = setOf(
                normalize(plannedNames[key] ?: LibraryPlanner.displayName(track)),
                normalize(LibraryPlanner.displayName(track)),
                normalize(track.title)
            )
            for (candidate in candidates) {
                if (candidate.isEmpty()) continue
                val keys = byNormalizedName.getOrPut(candidate) { ArrayList() }
                if (key !in keys) keys.add(key)
            }
        }

        val result = LinkedHashMap<String, String>()
        for (file in files.sortedWith(FILE_PREFERENCE)) {
            val name = file.name.trim()
            if (name.isEmpty() || file.uri.isBlank()) continue
            if (!isAudioFile(name) || file.size == 0L) continue

            val stem = baseName(name)
            val normalized = normalize(stem)
            if (normalized.isEmpty()) continue

            val idKeys = extractIds(stem)
                .flatMap { id -> byYoutubeId[id].orEmpty() }
                .distinct()
            val exactKeys = byNormalizedName[normalized].orEmpty()

            var candidates: List<String> = when {
                idKeys.isNotEmpty() && exactKeys.isNotEmpty() -> {
                    val overlap = idKeys.filter { it in exactKeys }
                    overlap.ifEmpty { idKeys }
                }

                idKeys.isNotEmpty() -> idKeys

                exactKeys.isNotEmpty() -> exactKeys

                else -> emptyList()
            }
            if (allowFuzzy && candidates.isEmpty()) {
                candidates = fuzzyCandidates(normalized, byNormalizedName, idKeys)
            }

            val key = when {
                candidates.size == 1 -> candidates[0]
                candidates.size > 1 && idKeys.size == 1 && idKeys[0] in candidates -> idKeys[0]
                else -> null
            }
            if (key != null && !result.containsKey(key)) {
                result[key] = file.uri
            }
        }
        return result
    }

    // Names written by the planner, by older bulk downloads ("01 - Title.m4a") and by
    // other players should all reduce to the same key. Bracketed video ids are removed
    // here and handled separately.
    private fun normalize(name: String): String {
        val withoutId = name.replace(BRACKETED_ID, " ")
        val withoutNumber = withoutId.replace(LEADING_TRACK_NUMBER, "")
        return SPACES.replace(
            NON_ALNUM.replace(withoutNumber.lowercase(Locale.ROOT), " "),
            " "
        ).trim()
    }

    private fun extractIds(stem: String): List<String> {
        val ids = LinkedHashSet<String>()
        for (match in BRACKETED_ID.findAll(stem)) ids.add(match.groupValues[1])
        for (match in ID_TOKEN.findAll(stem)) ids.add(match.groupValues[1])
        return ids.toList()
    }

    // A file may carry extra words such as "(Official Audio)" or a quality suffix. Only
    // accept a longer name when it points to exactly one track, to avoid skipping a
    // different track that happens to share a short title.
    private fun fuzzyCandidates(
        normalized: String,
        index: Map<String, List<String>>,
        idKeys: List<String>
    ): List<String> {
        if (normalized.length < 8) return emptyList()
        val found = LinkedHashSet<String>()
        for ((candidate, keys) in index) {
            if (candidate.length < 8) continue
            if (!normalized.contains(candidate) && !candidate.contains(normalized)) continue
            for (key in keys) {
                if (idKeys.isEmpty() || key in idKeys) found.add(key)
            }
        }
        return found.toList()
    }
}
