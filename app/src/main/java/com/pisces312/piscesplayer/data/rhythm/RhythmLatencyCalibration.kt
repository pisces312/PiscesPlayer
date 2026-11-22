/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (RhythmChartLab docs/experiments/2026-11-21-game-start-note-tremble-analysis.md §14).
 */
package com.pisces312.piscesplayer.data.rhythm

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.annotation.StringRes
import com.pisces312.piscesplayer.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.PI

/**
 * Latency calibration: the route-keyed offset store, the generated metronome track and the
 * tap-matching math.
 *
 * The offset is a property of the OUTPUT ROUTE (wired / Bluetooth / speaker), not of any chart:
 * Android exposes no API for the latency that sits after the AudioTimestamp presented line
 * (HAL tail, DAC, and the whole Bluetooth link — 100–300 ms), so it can only be observed by
 * closing the loop with the player's ears and fingers: metronome ticks play through the current
 * route, the player taps on the heard beats, and the mean tap delay becomes the offset.
 *
 * The offset is stored per route key, so calibrating on Bluetooth never disturbs the wired
 * value: switching routes just reads the other key.
 */
object RhythmLatencyStore {
    private const val PREFS_NAME = "rhythm_latency"
    private const val KEY_PREFIX = "offset_ms:"

    const val DEVICE_BLUETOOTH = "bluetooth"
    const val DEVICE_WIRED = "wired"
    const val DEVICE_SPEAKER = "speaker"
    const val DEVICE_USB = "usb"
    const val DEVICE_OTHER = "other"

    /**
     * Key of the route music would play on right now.
     *
     * There is no public API for the *active* output route ([AudioManager.getDevicesForStream]
     * is hidden and returns a legacy device mask, [AudioManager.getDevicesForAttributes] is
     * system-api), so this probes the connected sink devices and applies Android's default
     * routing priority: wired beats USB beats Bluetooth beats the built-in speaker.
     */
    fun deviceKey(context: Context): String = pickKey(connectedOutputs(context))

    fun offsetMs(context: Context, key: String = deviceKey(context)): Int =
        prefs(context).getInt(KEY_PREFIX + key, 0)

    fun setOffsetMs(context: Context, ms: Int, key: String = deviceKey(context)) {
        prefs(context).edit().putInt(KEY_PREFIX + key, ms).apply()
    }

    @StringRes
    fun deviceLabelRes(key: String): Int = when (key) {
        DEVICE_BLUETOOTH -> R.string.rhythm_latency_device_bluetooth
        DEVICE_WIRED -> R.string.rhythm_latency_device_wired
        DEVICE_SPEAKER -> R.string.rhythm_latency_device_speaker
        DEVICE_USB -> R.string.rhythm_latency_device_usb
        else -> R.string.rhythm_latency_device_other
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun connectedOutputs(context: Context): List<AudioDeviceInfo> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return emptyList()
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    }

    private fun pickKey(devices: List<AudioDeviceInfo>): String {
        if (devices.isEmpty()) return DEVICE_SPEAKER
        fun hasAny(vararg types: Int) = devices.any { it.type in types }
        return when {
            hasAny(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            ) -> DEVICE_WIRED
            hasAny(
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_DOCK,
            ) -> DEVICE_USB
            hasAny(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_BROADCAST,
                AudioDeviceInfo.TYPE_HEARING_AID,
            ) -> DEVICE_BLUETOOTH
            hasAny(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) -> DEVICE_SPEAKER
            else -> DEVICE_OTHER
        }
    }
}

/** Calibration metronome: pure generated PCM so it always matches the game's AudioTrack path. */
object RhythmCalibrationTrack {
    const val TICK_COUNT = 10
    const val FIRST_TICK_MS = 1000

    /**
     * Tick spacing. The nearest-tick match window is half of this, so the period must exceed
     * twice the largest plausible latency: a classic Bluetooth link measures 400–500 ms end to
     * end, and a 500 ms period aliased exactly that case onto the NEXT tick (a ~463 ms latency
     * measured as −37 ms, whose absolute value looked like a "37 ms" low-latency link). At
     * 1200 ms the window is ±600 ms: the whole Bluetooth range plus tap jitter lands on the
     * tick that was actually heard.
     */
    const val PERIOD_MS = 1200
    const val TICK_MS = 50
    const val SKIP_TICKS = 2
    const val MIN_MATCHED_TICKS = 3

    /** Song time of tick [index] in ms; the first [RhythmCalibrationTrack.SKIP_TICKS] ticks warm the link. */
    fun tickSongMs(index: Int): Double = (FIRST_TICK_MS + index * PERIOD_MS).toDouble()

    /**
     * 16-bit PCM with [TICK_COUNT] sine ticks at [tickSongMs] positions. The last tick is
     * followed by [TAIL_MS] of silence so the final tap lands while the stream still runs.
     */
    fun build(sampleRate: Int = 44100, channels: Int = 2): RhythmPcmAudio {
        val tailMs = PERIOD_MS + 100
        val totalFrames = ((FIRST_TICK_MS + (TICK_COUNT - 1) * PERIOD_MS + tailMs) *
            sampleRate / 1000)
        val tickFrames = TICK_MS * sampleRate / 1000
        val fadeFrames = min(5 * sampleRate / 1000, tickFrames / 2)
        val bytes = ByteArray(totalFrames.toInt() * channels * 2)
        for (tick in 0 until TICK_COUNT) {
            val startFrame = ((FIRST_TICK_MS + tick * PERIOD_MS) * sampleRate / 1000).toInt()
            for (f in 0 until tickFrames) {
                val envelope = raisedCosine(f, fadeFrames, tickFrames)
                val sample = (cos(2.0 * PI * 880.0 * f / sampleRate) * envelope * 0.4)
                    .coerceIn(-1.0, 1.0)
                val pcm = (sample * 32767.0).toInt().toShort()
                val frameIndex = startFrame + f
                for (ch in 0 until channels) {
                    val offset = (frameIndex * channels + ch) * 2
                    bytes[offset] = (pcm.toInt() and 0xFF).toByte()
                    bytes[offset + 1] = (pcm.toInt() shr 8).toByte()
                }
            }
        }
        return RhythmPcmAudio(bytes, sampleRate, channels)
    }

    private fun raisedCosine(f: Int, fadeFrames: Int, tickFrames: Int): Double = when {
        f < fadeFrames -> 0.5 - 0.5 * cos(PI * f / fadeFrames)
        f > tickFrames - fadeFrames ->
            0.5 - 0.5 * cos(PI * (tickFrames - f) / fadeFrames)
        else -> 1.0
    }
}

/**
 * Mean perceived latency in ms from the tap times: every tap is matched to its nearest tick
 * (within half a period), ticks before [RhythmCalibrationTrack.SKIP_TICKS] are ignored (they
 * warm the link, they are not the steady-state route latency), and at least
 * [RhythmCalibrationTrack.MIN_MATCHED_TICKS] ticks must have a tap.
 *
 * @param tapsNs tap times, elapsedRealtime nanoseconds
 * @param tickWallsNs interpolated wall time each tick's frame crossed the presented line;
 *   null for ticks that never crossed
 */
fun analyzeTapOffsets(
    tapsNs: List<Long>,
    tickWallsNs: List<Long?>,
    skipTicks: Int = RhythmCalibrationTrack.SKIP_TICKS,
    halfWindowNs: Long = RhythmCalibrationTrack.PERIOD_MS * 1_000_000L / 2,
): Double? {
    val offsetsPerTick = mutableMapOf<Int, MutableList<Long>>()
    for (tap in tapsNs) {
        var bestTick = -1
        var bestDist = Long.MAX_VALUE
        for ((index, wall) in tickWallsNs.withIndex()) {
            if (wall == null || index < skipTicks) continue
            val dist = kotlin.math.abs(tap - wall)
            if (dist < bestDist) {
                bestDist = dist
                bestTick = index
            }
        }
        if (bestTick >= 0 && bestDist <= halfWindowNs) {
            offsetsPerTick.getOrPut(bestTick) { mutableListOf() }
                .add(tap - tickWallsNs[bestTick]!!)
        }
    }
    if (offsetsPerTick.size < RhythmCalibrationTrack.MIN_MATCHED_TICKS) return null
    val perTickMeans = offsetsPerTick.values.map { tickTaps -> tickTaps.sum() / tickTaps.size }
    return perTickMeans.sum() / perTickMeans.size / 1_000_000.0
}
