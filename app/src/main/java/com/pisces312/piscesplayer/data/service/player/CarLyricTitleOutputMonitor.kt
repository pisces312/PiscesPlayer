package com.pisces312.piscesplayer.data.service.player

import android.content.ContentResolver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.provider.Settings

/**
 * Audio-route truth for the car lyric title: is Bluetooth A2DP the active output, and a callback
 * that fires [onRouteChanged] whenever the device list changes so the Bluetooth gate reacts
 * immediately instead of waiting for the next lyric boundary.
 *
 * The device list is the *event*, not the answer — [isActive] re-reads the route on every call,
 * so no cached flag here can ever go stale.
 */
class CarLyricTitleOutputMonitor(
    private val audioManager: AudioManager,
    private val contentResolver: ContentResolver,
    private val isDebuggable: Boolean,
    private val onRouteChanged: () -> Unit
) {

    private var callback: AudioDeviceCallback? = null

    fun start() {
        if (callback != null) return
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                onRouteChanged()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                onRouteChanged()
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        callback = deviceCallback
    }

    fun stop() {
        callback?.let { deviceCallback ->
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        }
        callback = null
    }

    /**
     * Whether the car lyric title may replace the session title: true only while Bluetooth is
     * the active output, so wired headphones or the phone speaker keep showing the real track
     * title.
     *
     * Note that Android exposes no API for the peer's AVRCP version, and none is needed: a head
     * unit that cannot render metadata simply never asks for it. The condition here is about
     * *our* routing, not the remote's capability.
     *
     * Debug builds additionally honour [FORCE_A2DP_SETTING], because an emulator has no A2DP sink
     * and the feature would otherwise be impossible to exercise locally.
     */
    fun isActive(): Boolean {
        if (hasBluetoothA2dpOutput()) return true
        if (!isDebuggable) return false
        return Settings.Global.getInt(contentResolver, FORCE_A2DP_SETTING, 0) == 1
    }

    private fun hasBluetoothA2dpOutput(): Boolean {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    }

    private companion object {
        /** Debug-only escape hatch: `adb shell settings put global <name> 1` pretends a Bluetooth
         *  A2DP sink is connected, which an emulator can never have. Ignored on release builds. */
        const val FORCE_A2DP_SETTING = "pixelplayer_car_lyric_title_force_a2dp"
    }
}
