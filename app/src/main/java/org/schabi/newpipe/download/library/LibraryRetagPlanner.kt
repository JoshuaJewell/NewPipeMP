/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download.library

object LibraryRetagPlanner {
    data class Change(val key: String, val track: LibraryTrack, val uri: String, val album: String, val number: Int)
    data class Skipped(val key: String, val track: LibraryTrack, val reason: String)
    data class Plan(val changes: List<Change>, val skipped: List<Skipped>)

    fun plan(
        playlists: List<PlannedPlaylist>,
        tracks: Map<String, LibraryTrack>,
        existing: Map<String, String>,
        excludedPlaylistFiles: Set<String>
    ): Plan {
        val memberships = HashMap<String, MutableList<Pair<String, Int>>>()
        for (playlist in playlists) {
            if (playlist.fileName in excludedPlaylistFiles) continue
            // Repeated appearances in one playlist use the first position.
            val seen = HashSet<String>()
            for ((index, key) in playlist.keys.withIndex()) {
                if (seen.add(key)) memberships.getOrPut(key) { ArrayList() }.add(playlist.name to index + 1)
            }
        }
        // Never assign two different tracks/albums to the same physical file.
        val sharedUris = existing.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val changes = ArrayList<Change>()
        val skipped = ArrayList<Skipped>()
        for ((key, track) in tracks) {
            val uri = existing[key]
            val owners = memberships[key].orEmpty()
            val reason = when {
                uri == null -> "No matched local file"
                uri in sharedUris -> "Local file matched more than one track"
                owners.isEmpty() -> "No eligible playlist"
                owners.size > 1 -> "Multiple eligible playlists: ${owners.joinToString { it.first }}"
                else -> null
            }
            if (reason != null) {
                skipped += Skipped(key, track, reason)
            } else {
                val owner = owners.single()
                changes += Change(key, track, uri!!, owner.first, owner.second)
            }
        }
        return Plan(changes, skipped)
    }
}
