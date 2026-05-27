/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

// Taken when a track starts. The engine diffs against the current playback_statistics
// row when the track resolves, so it can attribute restarts to this single play event.
data class PlaybackSnapshot(
    val streamId: Long,
    val takenAt: Long,
    val restartCountAtStart: Int
)
