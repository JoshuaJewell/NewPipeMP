/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

// Where and how the user is listening at decision time.
data class ListeningConditions(val screenOn: Boolean, val outputRoute: OutputRoute) {
    companion object {
        // Used where nothing is known (decisions recorded before these existed): the
        // feature baseline, which encodes as all zeros.
        @JvmField
        val UNKNOWN = ListeningConditions(screenOn = false, outputRoute = OutputRoute.SPEAKER)
    }
}

// Ordinals are persisted by RawInputsCodec; append new values, never reorder. The v1.1
// build's blobs are read with the same ordinals. Its order is inferred: it wrote only 0
// and 2, 2 for most decisions, and that listening was mostly over Bluetooth.
enum class OutputRoute { SPEAKER, WIRED, BLUETOOTH, OTHER }
