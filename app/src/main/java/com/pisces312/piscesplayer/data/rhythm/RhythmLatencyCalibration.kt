/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (RhythmChartLab docs/design/rhythm-latency-calibration-ui-design.md).
 */
package com.pisces312.piscesplayer.data.rhythm

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.annotation.StringRes
import com.pisces312.piscesplayer.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Latency calibration: the route-keyed offset store, the generated metronome track and the
 * tap-matching math.
 *
 * The offset is a property of the OUTPUT ROUTE (wired / Bluetooth / speaker), not of any chart:
 * Android exposes no API for the latency that sits after the AudioTimestamp presented line
 * (HAL tail, DAC, and the whole Bluetooth link — 100–300 ms), so it can only be observed by
 * closing the loop with the player's ears and fingers: metronome ticks play through the current
 * route, the player taps on the heard beats, and the median tap delay becomes the offset.
 *
 * The offset is stored per route key, so calibrating on Bluetooth never disturbs the wired
 * value: switching routes just reads the other key. Each route also keeps the run's own jitter
 * (sigma) and when it was measured, so the entry card can say how trustworthy the value is.
 */
object RhythmLatencyStore {
    private const val PREFS_NAME = "rhythm_latency"
    private const val KEY_OFFSET_PREFIX = "offset_ms:"
    private const val KEY_SIGMA_PREFIX = "sigma_ms:"
    private const val KEY_CALIBRATED_AT_PREFIX = "calibrated_at:"

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
        prefs(context).getInt(KEY_OFFSET_PREFIX + key, 0)

    /** Jitter of the last run on this route, or null when the route was never calibrated. */
    fun sigmaMs(context: Context, key: String = deviceKey(context)): Double? {
        val prefs = prefs(context)
        val stored = KEY_SIGMA_PREFIX + key
        return if (prefs.contains(stored)) prefs.getFloat(stored, 0f).toDouble() else null
    }

    /** Wall time (epoch ms) of the last calibration on this route; 0 when never calibrated. */
    fun calibratedAtMs(context: Context, key: String = deviceKey(context)): Long =
        prefs(context).getLong(KEY_CALIBRATED_AT_PREFIX + key, 0L)

    fun setOffsetMs(
        context: Context,
        ms: Int,
        sigmaMs: Double?,
        key: String = deviceKey(context),
    ) {
        prefs(context).edit().apply {
            putInt(KEY_OFFSET_PREFIX + key, ms)
            if (sigmaMs != null) {
                putFloat(KEY_SIGMA_PREFIX + key, sigmaMs.toFloat())
            } else {
                remove(KEY_SIGMA_PREFIX + key)
            }
            putLong(KEY_CALIBRATED_AT_PREFIX + key, System.currentTimeMillis())
        }.apply()
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

/**
 * Calibration metronome: pure generated PCM so it always matches the game's AudioTrack path.
 *
 * The track is split into a lead-in and a measured section. The lead-in ([LEAD_IN_TICKS]) is
 * audible but never measured: it warms the Bluetooth link (codec session and jitter buffer, which
 * on a cold link arrives later than the steady state) AND gives the player four beats to lock the
 * tempo before any tap counts. That replaces the old "do NOT tap the first 2 ticks" rule — a
 * negative instruction the player had to keep in mind while trying to feel a beat.
 */
object RhythmCalibrationTrack {
    const val LEAD_IN_TICKS = 4
    const val MEASURED_TICKS = 8
    const val TICK_COUNT = LEAD_IN_TICKS + MEASURED_TICKS

    /** A run needs at least this many measured ticks to produce a number at all. */
    const val MIN_MATCHED_TICKS = 5

    /** Below this many matched ticks the result is flagged low trust. */
    const val GOOD_MATCHED_TICKS = 6

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

    /** Song time of tick [index] in ms; the first [LEAD_IN_TICKS] ticks are the warm-up. */
    fun tickSongMs(index: Int): Double = (FIRST_TICK_MS + index * PERIOD_MS).toDouble()

    /** True when a tap on tick [index] counts towards the measurement. */
    fun isMeasured(index: Int): Boolean = index >= LEAD_IN_TICKS

    /**
     * 16-bit PCM with [TICK_COUNT] sine ticks at [tickSongMs] positions. The last tick is
     * followed by [PERIOD_MS] + 100 ms of silence so the final tap lands while the stream runs.
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

/** Per-tick residual for the result scatter: [offsetMs] is how late that tick was tapped. */
data class CalibrationTickSample(
    val index: Int,
    val offsetMs: Double,
    val rejected: Boolean,
)

/**
 * Robust outcome of one calibration run.
 *
 * [offsetMs] is the median perceived latency; [sigmaMs] is the run's own jitter
 * (1.4826 × MAD, i.e. a normal-distribution-equivalent standard deviation) and decides whether the
 * number can be trusted. Nothing here is saved until the caller applies it.
 */
data class CalibrationStats(
    val offsetMs: Double,
    val sigmaMs: Double,
    val matchedTicks: Int,
    val rejectedTicks: Int,
    val measuredTicks: Int,
    val taps: Int,
    val samples: List<CalibrationTickSample>,
) {
    /** Offset to store: the negation of the measured latency, clamped to a plausible range. */
    val appliedOffsetMs: Int
        get() = (-offsetMs).roundToInt().coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)

    val isLowConfidence: Boolean
        get() = matchedTicks < RhythmCalibrationTrack.GOOD_MATCHED_TICKS ||
            sigmaMs > LOW_CONFIDENCE_SIGMA_MS

    companion object {
        const val MAX_OFFSET_MS = 500
        const val LOW_CONFIDENCE_SIGMA_MS = 50.0
    }
}

/**
 * Floor of the blunder window. A real finger slip is 100 ms or worse; without a floor, a run that
 * happens to be very clean would start rejecting its own normal spread.
 */
private const val BLUNDER_FLOOR_NS = 120_000_000.0

/**
 * Robust latency estimate from the tap times (design doc §6).
 *
 * 1. every tap joins its NEAREST measured tick within half a period;
 * 2. per tick the tap median is taken (a double tap must not weigh twice);
 * 3. the across-tick median is the anchor, and per-tick residuals beyond
 *    `max(120 ms, 4σ_raw)` are rejected as blunders;
 * 4. the final value is the median of what remains — the median, not the mean, so a single
 *    mistimed tap can no longer drag the result (the mean moved by ~30 ms on one 300 ms slip).
 *
 * @param tapsNs tap times in the boot clock (elapsedRealtimeNanos)
 * @param tickWallsNs interpolated wall time each tick's frame crossed the presented line;
 *   null for ticks that never crossed
 */
fun analyzeCalibration(
    tapsNs: List<Long>,
    tickWallsNs: List<Long?>,
    halfWindowNs: Long = RhythmCalibrationTrack.PERIOD_MS * 1_000_000L / 2,
): CalibrationStats? {
    val tapsPerTick = sortedMapOf<Int, MutableList<Long>>()
    for (tap in tapsNs) {
        var bestTick = -1
        var bestDist = Long.MAX_VALUE
        for ((index, wall) in tickWallsNs.withIndex()) {
            if (wall == null || !RhythmCalibrationTrack.isMeasured(index)) continue
            val dist = abs(tap - wall)
            if (dist < bestDist) {
                bestDist = dist
                bestTick = index
            }
        }
        if (bestTick >= 0 && bestDist <= halfWindowNs) {
            tapsPerTick.getOrPut(bestTick) { mutableListOf() }.add(tap)
        }
    }
    if (tapsPerTick.size < RhythmCalibrationTrack.MIN_MATCHED_TICKS) return null

    // Taps that land out of order mean the tick assignment itself is wrong (a tap belonging to
    // tick N+1 was matched to N): nothing in the run can be trusted.
    var previousTapNs = Long.MIN_VALUE
    val perTickResiduals = ArrayList<Double>(tapsPerTick.size)
    val tickIndexes = ArrayList<Int>(tapsPerTick.size)
    for ((index, tickTaps) in tapsPerTick) {
        val medianTap = medianLong(tickTaps)
        if (medianTap <= previousTapNs) return null
        previousTapNs = medianTap
        tickIndexes += index
        perTickResiduals += (medianTap - tickWallsNs[index]!!).toDouble()
    }

    val anchor = medianDouble(perTickResiduals)
    val rawSigma = 1.4826 * medianDouble(perTickResiduals.map { abs(it - anchor) })
    val rejectLimit = max(BLUNDER_FLOOR_NS, 4.0 * rawSigma)

    val kept = perTickResiduals.filter { abs(it - anchor) <= rejectLimit }
    if (kept.size < RhythmCalibrationTrack.MIN_MATCHED_TICKS) return null

    val offsetNs = medianDouble(kept)
    val sigmaNs = 1.4826 * medianDouble(kept.map { abs(it - offsetNs) })

    return CalibrationStats(
        offsetMs = offsetNs / 1_000_000.0,
        sigmaMs = sigmaNs / 1_000_000.0,
        matchedTicks = kept.size,
        rejectedTicks = perTickResiduals.size - kept.size,
        measuredTicks = RhythmCalibrationTrack.MEASURED_TICKS,
        taps = tapsNs.size,
        samples = tickIndexes.mapIndexed { position, index ->
            val residual = perTickResiduals[position]
            CalibrationTickSample(
                index = index,
                offsetMs = residual / 1_000_000.0,
                rejected = abs(residual - anchor) > rejectLimit,
            )
        },
    )
}

private fun medianLong(values: List<Long>): Long {
    require(values.isNotEmpty()) { "median of an empty list" }
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[mid]
    } else {
        (sorted[mid - 1] + sorted[mid]) / 2
    }
}

private fun medianDouble(values: List<Double>): Double {
    require(values.isNotEmpty()) { "median of an empty list" }
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[mid]
    } else {
        (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
