/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

data class UploaderStats(
    val avgRating: Double?,
    val completionRate: Double?,
    val skipRate: Double?
) {
    companion object {
        val UNKNOWN = UploaderStats(null, null, null)

        fun of(avgRating: Double?, completions: Int, skips: Int): UploaderStats {
            val total = completions + skips
            return UploaderStats(
                avgRating = avgRating,
                completionRate = if (total > 0) completions.toDouble() / total else null,
                skipRate = if (total > 0) skips.toDouble() / total else null
            )
        }
    }
}

// Uploader aggregates for candidate scoring, fetched in batches and kept for a while.
// They cover every track by an uploader, so one more listen barely moves them, and
// computing them is the expensive part of a pick (a scan of the streams table, which has
// no index on uploader). fetch receives only the names it needs and returns whatever it
// found; a name it does not return is cached as unknown.
class UploaderStatsCache(
    private val fetch: (List<String>) -> Map<String, UploaderStats>,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Entry(val stats: UploaderStats, val fetchedAt: Long)

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun get(names: Collection<String>): Map<String, UploaderStats> {
        val now = clock()
        val wanted = names.filter { it.isNotBlank() }.distinct()
        val stale = wanted.filter { name ->
            val e = entries[name]
            e == null || now - e.fetchedAt > ttlMillis
        }
        if (stale.isNotEmpty()) {
            val fetched = fetch(stale)
            for (name in stale) entries[name] = Entry(fetched[name] ?: UploaderStats.UNKNOWN, now)
        }
        return wanted.associateWith { entries.getValue(it).stats }
    }

    fun get(name: String?): UploaderStats = if (name.isNullOrBlank()) UploaderStats.UNKNOWN else get(listOf(name)).getValue(name)

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 10L * 60L * 1000L
    }
}
