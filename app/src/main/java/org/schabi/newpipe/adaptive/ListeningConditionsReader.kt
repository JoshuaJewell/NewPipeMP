/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.PowerManager

// Reads the ListeningConditions of the moment from the system.
class ListeningConditionsReader(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager?

    fun read(): ListeningConditions = try {
        ListeningConditions(powerManager?.isInteractive ?: false, route())
    } catch (e: RuntimeException) {
        ListeningConditions.UNKNOWN
    }

    // Media goes to a wired or USB headset before Bluetooth, and to either before the
    // built-in speaker, so the most specific connected output is the one in use.
    private fun route(): OutputRoute {
        val types = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.map { it.type }?.toSet() ?: return OutputRoute.SPEAKER
        return when {
            types.any { it in WIRED } -> OutputRoute.WIRED
            types.any { it in BLUETOOTH } -> OutputRoute.BLUETOOTH
            types.any { it in OTHER } -> OutputRoute.OTHER
            else -> OutputRoute.SPEAKER
        }
    }

    private companion object {
        // Values of AudioDeviceInfo constants added after API 23, the minimum SDK.
        const val TYPE_USB_HEADSET = 22
        const val TYPE_HEARING_AID = 23
        const val TYPE_BLE_HEADSET = 26
        const val TYPE_BLE_SPEAKER = 27
        const val TYPE_BLE_BROADCAST = 30

        val WIRED = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            TYPE_USB_HEADSET
        )
        val BLUETOOTH = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            TYPE_HEARING_AID,
            TYPE_BLE_HEADSET,
            TYPE_BLE_SPEAKER,
            TYPE_BLE_BROADCAST
        )
        val OTHER = setOf(
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE
        )
    }
}
