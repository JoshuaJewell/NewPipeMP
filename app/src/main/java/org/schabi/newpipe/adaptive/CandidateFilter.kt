/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.util.Random

// Hard rules applied to the queue tail before the model sees anything. The model learns
// preferences; it should not have to learn "don't play the same thing twice in an hour"
// or "don't play three tracks by one artist in a row", and a confidently wrong model
// cannot be trusted to (spec section 6.2).
object CandidateFilter {

    data class TailEntry(
        val tailIndex: Int,
        val key: String,
        val uploader: String?
    )

    data class Result(
        val eligible: List<TailEntry>,
        val recencyWindowUsed: Int,
        val uploaderSpacingUsed: Int
    )

    // Over the canonical URL, so a track reached under two URLs is excluded as one.
    fun key(serviceId: Int, url: String): String = "$serviceId|${StreamUrls.canonical(serviceId, url)}"

    // Who made a track, for uploader spacing. The channel URL where there is one: some
    // imports store no uploader name (a whole Bandcamp artist had none), and a blank name
    // never counted as a recent uploader, so spacing let that artist play back to back.
    // The URL is also stable where display names differ between sources.
    fun uploaderKey(uploaderUrl: String?, uploader: String?): String? = uploaderUrl?.trim()?.takeIf { it.isNotEmpty() }?.trimEnd('/')
        ?: uploader?.trim()?.takeIf { it.isNotEmpty() }

    fun recencyWindowFor(tailSize: Int): Int {
        val scaled = (tailSize * AdaptiveShuffleConfig.RECENCY_EXCLUSION_FRACTION).toInt()
        return scaled.coerceIn(AdaptiveShuffleConfig.RECENCY_EXCLUSION_N, AdaptiveShuffleConfig.RECENCY_EXCLUSION_MAX)
    }

    // recentKeys and recentUploaders are ordered most recent first. Exclusions relax in a
    // fixed order when they would leave fewer than minEligible candidates: uploader
    // spacing goes first (it is the softer preference), then the recency window shrinks.
    // If the tail itself is smaller than minEligible, the whole tail is eligible once
    // both rules are fully relaxed; exclusion never empties a non-empty tail.
    fun filter(
        tail: List<TailEntry>,
        recentKeys: List<String>,
        recentUploaders: List<String?>,
        recencyWindow: Int = recencyWindowFor(tail.size),
        uploaderSpacing: Int = AdaptiveShuffleConfig.UPLOADER_SPACING,
        minEligible: Int = AdaptiveShuffleConfig.MIN_ELIGIBLE_CANDIDATES
    ): Result {
        if (tail.isEmpty()) return Result(emptyList(), 0, 0)
        val target = minOf(minEligible, tail.size)
        var window = minOf(recencyWindow, recentKeys.size)
        var spacing = minOf(uploaderSpacing, recentUploaders.size)
        while (true) {
            val blockedKeys = recentKeys.subList(0, window).toHashSet()
            val blockedUploaders = recentUploaders.subList(0, spacing)
                .filterNotNull()
                .filter { it.isNotBlank() }
                .toHashSet()
            val eligible = tail.filter { e ->
                e.key !in blockedKeys && (e.uploader == null || e.uploader !in blockedUploaders)
            }
            if (eligible.size >= target || (window == 0 && spacing == 0)) {
                return Result(eligible, window, spacing)
            }
            if (spacing > 0) {
                spacing -= 1
            } else {
                window = if (window > 8) window / 2 else window - 1
            }
        }
    }

    // Uniform sample without replacement, preserving nothing about order.
    fun <T> sample(items: List<T>, cap: Int, rng: Random): List<T> {
        if (items.size <= cap) return items
        val indices = IntArray(items.size) { it }
        for (i in 0 until cap) {
            val j = i + rng.nextInt(indices.size - i)
            val tmp = indices[i]
            indices[i] = indices[j]
            indices[j] = tmp
        }
        return (0 until cap).map { items[indices[it]] }
    }
}
