/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (RhythmChartLab docs/design/rhythm-latency-calibration-ui-design.md and
 * rhythm-latency-per-device-design.md).
 */
package com.pisces312.piscesplayer.data.rhythm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.StringRes
import com.pisces312.piscesplayer.R
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Which family of output a route belongs to. The wire name doubles as the *kind-level device id*:
 * the generic bucket for a kind (e.g. "bluetooth") is the same key the pre-per-device builds used,
 * so an existing calibration survives as an inheritable baseline (design §3).
 */
enum class LatencyRouteKind(val wire: String) {
    SPEAKER("speaker"),
    WIRED("wired"),
    USB("usb"),
    BLUETOOTH("bluetooth"),
    OTHER("other"),
    ;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            SPEAKER -> R.string.rhythm_latency_device_speaker
            WIRED -> R.string.rhythm_latency_device_wired
            USB -> R.string.rhythm_latency_device_usb
            BLUETOOTH -> R.string.rhythm_latency_device_bluetooth
            OTHER -> R.string.rhythm_latency_device_other
        }

    companion object {
        fun ofWire(wire: String?): LatencyRouteKind? = entries.firstOrNull { it.wire == wire }

        /** Kind of an arbitrary device id ("bt:…", "usb:…", "wired", …). */
        fun ofId(id: String): LatencyRouteKind = ofWire(id) ?: when {
            id.startsWith("bt:") -> BLUETOOTH
            id.startsWith("usb:") -> USB
            id.startsWith("other:") -> OTHER
            else -> OTHER
        }
    }
}

/**
 * One physical (or generic) output.
 *
 * [id] is the storage key and must be stable across reconnects: the MAC for Bluetooth, the product
 * name for USB, and the kind wire name when the device cannot be told apart from its siblings
 * ([identifiable] = false, e.g. any 3.5 mm headset).
 */
data class LatencyDevice(
    val id: String,
    val kind: LatencyRouteKind,
    /** Raw product name from the framework; empty when unavailable (no permission / unnamed). */
    val displayName: String,
    val identifiable: Boolean,
)

/** Where the value the game will use came from. */
enum class LatencySource { CALIBRATED, MANUAL, INHERITED, NONE }

/**
 * One device's setting as the UI and the game read it.
 *
 * [offsetMs] is the value the next run must apply; [inheritedFrom] is the kind whose baseline is in
 * effect when [source] is [LatencySource.INHERITED], and null otherwise.
 */
data class LatencySetting(
    val offsetMs: Int,
    val sigmaMs: Double?,
    val updatedAtMs: Long,
    val source: LatencySource,
    val device: LatencyDevice,
    val inheritedFrom: LatencyRouteKind? = null,
) {
    val hasRecord: Boolean get() = source != LatencySource.NONE
}

/**
 * Latency calibration: the per-device offset store, the generated metronome track and the
 * tap-matching math.
 *
 * The offset is a property of the OUTPUT DEVICE, not of any chart: Android exposes no API for the
 * latency that sits after the AudioTimestamp presented line (HAL tail, DAC, and the whole Bluetooth
 * link — 100–300 ms), so it can only be observed by closing the loop with the player's ears and
 * fingers: metronome ticks play through the current route, the player taps on the heard beats, and
 * the median tap delay becomes the offset.
 *
 * Values are keyed by [LatencyDevice.id] so two headsets keep two numbers. A kind-level record
 * (the old route-keyed value) still answers for any device of that kind that has no record of its
 * own, so a freshly paired headset starts from "Bluetooth ≈ −430 ms" instead of 0.
 *
 * Manual entry and calibration write the SAME `offset_ms:<id>`; [LatencySource] only records where
 * the number came from, so there is exactly one value for the game to apply.
 */
object RhythmLatencyStore {
    private const val PREFS_NAME = "rhythm_latency"
    private const val KEY_OFFSET_PREFIX = "offset_ms:"
    private const val KEY_SIGMA_PREFIX = "sigma_ms:"
    private const val KEY_UPDATED_AT_PREFIX = "updated_at:"
    private const val KEY_SOURCE_PREFIX = "source:"
    private const val KEY_LABEL_PREFIX = "label:"
    private const val KEY_KIND_PREFIX = "kind:"
    private const val KEY_CALIBRATED_OFFSET_PREFIX = "calibrated_offset_ms:"
    private const val KEY_CALIBRATED_SIGMA_PREFIX = "calibrated_sigma_ms:"
    private const val KEY_ASKED_NEARBY = "asked_nearby"

    /** Written by the route-keyed builds; still read as the fallback "when" for those records. */
    private const val LEGACY_CALIBRATED_AT_PREFIX = "calibrated_at:"

    private const val SOURCE_CALIBRATED = "calibrated"
    private const val SOURCE_MANUAL = "manual"

    /** Same clamp as a measurement: one constant for both writers. */
    const val MAX_OFFSET_MS = CalibrationStats.MAX_OFFSET_MS

    private val MAC_REGEX = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

    /** The device music would play on right now. */
    fun activeDevice(context: Context): LatencyDevice {
        val info = pickOutput(connectedOutputs(context))
            ?: return LatencyDevice(LatencyRouteKind.SPEAKER.wire, LatencyRouteKind.SPEAKER, "", false)
        return deviceOf(context, info)
    }

    /** The setting the next run must apply for whatever is connected now. */
    fun resolve(context: Context): LatencySetting = resolve(context, activeDevice(context))

    fun resolve(context: Context, device: LatencyDevice): LatencySetting {
        record(context, device.id)?.let { return it }
        val baseline = if (device.id != device.kind.wire) record(context, device.kind.wire) else null
        return if (baseline != null) {
            baseline.copy(
                device = device,
                source = LatencySource.INHERITED,
                inheritedFrom = device.kind,
            )
        } else {
            LatencySetting(
                offsetMs = 0,
                sigmaMs = null,
                updatedAtMs = 0L,
                source = LatencySource.NONE,
                device = device,
            )
        }
    }

    /** Stores a measurement. Written by the calibration overlay only. */
    fun saveCalibration(context: Context, device: LatencyDevice, offsetMs: Int, sigmaMs: Double) {
        val clamped = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        prefs(context).edit()
            .putInt(KEY_OFFSET_PREFIX + device.id, clamped)
            .putFloat(KEY_SIGMA_PREFIX + device.id, sigmaMs.toFloat())
            .putLong(KEY_UPDATED_AT_PREFIX + device.id, System.currentTimeMillis())
            .putString(KEY_SOURCE_PREFIX + device.id, SOURCE_CALIBRATED)
            .putString(KEY_LABEL_PREFIX + device.id, device.displayName)
            .putString(KEY_KIND_PREFIX + device.id, device.kind.wire)
            // Keep the last measurement around so a hand edit can be undone without re-running.
            .putInt(KEY_CALIBRATED_OFFSET_PREFIX + device.id, clamped)
            .putFloat(KEY_CALIBRATED_SIGMA_PREFIX + device.id, sigmaMs.toFloat())
            .apply()
    }

    /**
     * Hand-written value. Same key as [saveCalibration]; the jitter is dropped because a typed
     * number says nothing about a run, while the last measurement is kept for [restoreCalibrated].
     */
    fun setManual(context: Context, device: LatencyDevice, offsetMs: Int) {
        val clamped = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        prefs(context).edit()
            .putInt(KEY_OFFSET_PREFIX + device.id, clamped)
            .remove(KEY_SIGMA_PREFIX + device.id)
            .putLong(KEY_UPDATED_AT_PREFIX + device.id, System.currentTimeMillis())
            .putString(KEY_SOURCE_PREFIX + device.id, SOURCE_MANUAL)
            .putString(KEY_LABEL_PREFIX + device.id, device.displayName)
            .putString(KEY_KIND_PREFIX + device.id, device.kind.wire)
            .apply()
    }

    /** Puts the last measured value (and its jitter) back. False when nothing was measured. */    fun restoreCalibrated(context: Context, device: LatencyDevice): Boolean {
        val prefs = prefs(context)
        val key = KEY_CALIBRATED_OFFSET_PREFIX + device.id
        if (!prefs.contains(key)) return false
        prefs.edit()
            .putInt(KEY_OFFSET_PREFIX + device.id, prefs.getInt(key, 0))
            .putLong(KEY_UPDATED_AT_PREFIX + device.id, System.currentTimeMillis())
            .putString(KEY_SOURCE_PREFIX + device.id, SOURCE_CALIBRATED)
            .apply {
                val sigmaKey = KEY_CALIBRATED_SIGMA_PREFIX + device.id
                if (prefs.contains(sigmaKey)) {
                    putFloat(KEY_SIGMA_PREFIX + device.id, prefs.getFloat(sigmaKey, 0f))
                } else {
                    remove(KEY_SIGMA_PREFIX + device.id)
                }
            }
            .apply()
        return true
    }

    /** The last measurement for this device, or null when it was never measured. */
    fun calibratedOffsetMs(context: Context, device: LatencyDevice): Int? {
        val prefs = prefs(context)
        val key = KEY_CALIBRATED_OFFSET_PREFIX + device.id
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    /** Every stored record, the active device's first, then most recently changed. */
    fun savedDevices(context: Context): List<LatencySetting> {
        val active = activeDevice(context)
        return prefs(context).all.keys
            .asSequence()
            .filter { it.startsWith(KEY_OFFSET_PREFIX) }
            .mapNotNull { record(context, it.removePrefix(KEY_OFFSET_PREFIX)) }
            .sortedWith(
                compareByDescending<LatencySetting> { it.device.id == active.id }
                    .thenByDescending { it.updatedAtMs },
            )
            .toList()
    }

    /** Drops one record entirely, so the device falls back to its kind baseline. */
    fun forget(context: Context, id: String) {
        prefs(context).edit().apply {
            listOf(
                KEY_OFFSET_PREFIX,
                KEY_SIGMA_PREFIX,
                KEY_UPDATED_AT_PREFIX,
                KEY_SOURCE_PREFIX,
                KEY_LABEL_PREFIX,
                KEY_KIND_PREFIX,
                KEY_CALIBRATED_OFFSET_PREFIX,
                KEY_CALIBRATED_SIGMA_PREFIX,
                LEGACY_CALIBRATED_AT_PREFIX,
            ).forEach { remove(it + id) }
        }.apply()
    }

    /**
     * Renames a record. Used once, when the Bluetooth permission is granted: the id changes from
     * `bt:<name>` to `bt:<mac>`, and without this the user's calibration would look lost.
     */
    fun adopt(context: Context, oldId: String, newId: String) {
        if (oldId == newId) return
        val prefs = prefs(context)
        val editor = prefs.edit()
        listOf(
            KEY_OFFSET_PREFIX,
            KEY_SIGMA_PREFIX,
            KEY_UPDATED_AT_PREFIX,
            KEY_SOURCE_PREFIX,
            KEY_LABEL_PREFIX,
            KEY_KIND_PREFIX,
            KEY_CALIBRATED_OFFSET_PREFIX,
            KEY_CALIBRATED_SIGMA_PREFIX,
            LEGACY_CALIBRATED_AT_PREFIX,
        ).forEach { prefix ->
            val from = prefix + oldId
            val value = prefs.all[from]
            if (value != null) {
                when (value) {
                    is Int -> editor.putInt(prefix + newId, value)
                    is Long -> editor.putLong(prefix + newId, value)
                    is Float -> editor.putFloat(prefix + newId, value)
                    is String -> editor.putString(prefix + newId, value)
                    is Boolean -> editor.putBoolean(prefix + newId, value)
                }
                editor.remove(from)
            }
        }
        editor.apply()
    }

    /**
     * After the Bluetooth permission is granted, the active device may have a name-keyed record
     * from when it was unidentifiable. Move it only when the label and kind match exactly; guessing
     * beyond that would fight a user who renamed their headset.
     */
    fun adoptNameKeyedRecord(context: Context, device: LatencyDevice): Boolean {
        if (!device.identifiable || device.displayName.isBlank()) return false
        val prefs = prefs(context)
        val candidate = prefs.all.keys
            .asSequence()
            .filter { it.startsWith(KEY_OFFSET_PREFIX) }
            .map { it.removePrefix(KEY_OFFSET_PREFIX) }
            .firstOrNull { id ->
                id != device.id &&
                    LatencyRouteKind.ofId(id) == device.kind &&
                    prefs.getString(KEY_LABEL_PREFIX + id, null)
                        ?.equals(device.displayName, ignoreCase = true) == true
            } ?: return false
        adopt(context, candidate, device.id)
        return true
    }

    fun hasBluetoothConnect(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** True once the system permission dialog has been offered, so we never nag again. */
    fun nearbyPermissionAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED_NEARBY, false)

    fun markNearbyPermissionAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_NEARBY, true).apply()
    }

    private fun record(context: Context, id: String): LatencySetting? {
        val prefs = prefs(context)
        val offsetKey = KEY_OFFSET_PREFIX + id
        if (!prefs.contains(offsetKey)) return null
        val sigma = if (prefs.contains(KEY_SIGMA_PREFIX + id)) {
            prefs.getFloat(KEY_SIGMA_PREFIX + id, 0f).toDouble()
        } else {
            null
        }
        val updatedAt = prefs.getLong(KEY_UPDATED_AT_PREFIX + id, 0L)
            .takeIf { it != 0L }
            ?: prefs.getLong(LEGACY_CALIBRATED_AT_PREFIX + id, 0L)
        val source = when (prefs.getString(KEY_SOURCE_PREFIX + id, null)) {
            SOURCE_CALIBRATED -> LatencySource.CALIBRATED
            SOURCE_MANUAL -> LatencySource.MANUAL
            // Route-keyed builds wrote only the offset (+sigma): that was a measurement.
            else -> if (sigma != null) LatencySource.CALIBRATED else LatencySource.MANUAL
        }
        val kind = LatencyRouteKind.ofWire(prefs.getString(KEY_KIND_PREFIX + id, null))
            ?: LatencyRouteKind.ofId(id)
        val device = LatencyDevice(
            id = id,
            kind = kind,
            displayName = prefs.getString(KEY_LABEL_PREFIX + id, "").orEmpty(),
            identifiable = id != kind.wire,
        )
        return LatencySetting(
            offsetMs = prefs.getInt(offsetKey, 0).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS),
            sigmaMs = sigma,
            updatedAtMs = updatedAt,
            source = source,
            device = device,
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun connectedOutputs(context: Context): List<AudioDeviceInfo> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return emptyList()
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    }

    /**
     * Android's default routing priority: wired beats USB beats Bluetooth beats the speaker.
     * There is no public API for the *active* output ([AudioManager.getDevicesForStream] is hidden,
     * [AudioManager.getDevicesForAttributes] is @SystemApi), so probing the connected sinks is the
     * only option.
     */
    private fun pickOutput(devices: List<AudioDeviceInfo>): AudioDeviceInfo? {
        fun firstOf(vararg types: Int): AudioDeviceInfo? =
            devices.firstOrNull { it.type in types }
        val priority = listOf(
            intArrayOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            ),
            intArrayOf(
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_DOCK,
            ),
            intArrayOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_BROADCAST,
                AudioDeviceInfo.TYPE_HEARING_AID,
            ),
            intArrayOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
        )
        for (types in priority) {
            firstOf(*types)?.let { return it }
        }
        return devices.firstOrNull()
    }

    private fun deviceOf(context: Context, info: AudioDeviceInfo): LatencyDevice {
        val kind = when (info.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> LatencyRouteKind.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            -> LatencyRouteKind.WIRED
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_DOCK,
            -> LatencyRouteKind.USB
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID,
            -> LatencyRouteKind.BLUETOOTH
            else -> LatencyRouteKind.OTHER
        }
        val name = info.productName?.toString()?.trim().orEmpty()
        val id = when (kind) {
            // A 3.5 mm headset carries no identity at all — one bucket is the honest answer.
            LatencyRouteKind.SPEAKER, LatencyRouteKind.WIRED -> kind.wire
            LatencyRouteKind.USB -> name.takeIf { it.isNotEmpty() }
                ?.let { "${kind.wire}:${slug(it)}" } ?: kind.wire
            LatencyRouteKind.BLUETOOTH -> {
                val mac = if (hasBluetoothConnect(context)) {
                    runCatching { info.address }.getOrNull()
                } else {
                    null
                }
                when {
                    // The MAC survives a rename, which the product name does not.
                    isValidMac(mac) -> "bt:" + mac!!.lowercase(Locale.US)
                    name.isNotEmpty() -> "bt:${slug(name)}"
                    else -> kind.wire
                }
            }
            LatencyRouteKind.OTHER -> name.takeIf { it.isNotEmpty() }
                ?.let { "${kind.wire}:${slug(it)}" } ?: kind.wire
        }
        return LatencyDevice(
            id = id,
            kind = kind,
            displayName = name,
            identifiable = id != kind.wire,
        )
    }

    /**
     * Android anonymises the address to `02:00:00:00:00:00` without the Bluetooth permission, and
     * some stacks report all zeroes: neither may become a storage key.
     */
    private fun isValidMac(mac: String?): Boolean {
        if (mac.isNullOrBlank()) return false
        val normalized = mac.trim().lowercase(Locale.US)
        if (!MAC_REGEX.matches(normalized)) return false
        if (normalized == "02:00:00:00:00:00") return false
        return normalized.split(':').any { it != "00" }
    }

    private fun slug(raw: String): String = raw.trim().lowercase(Locale.US)
        .replace(Regex("\\s+"), "-")
        .replace(Regex("[^\\p{L}\\p{N}\\-_]+"), "")
        .trim('-')
}

/**
 * Parses the hand-edited offset field. Returns null for anything that is not a number, so the
 * caller keeps the previous value instead of writing a guess.
 *
 * The field is the only writer that has to cope with a human keyboard: full-width digits, a true
 * minus sign, an en dash pasted from a document, and a stray `ms` suffix all mean the same thing.
 */
fun parseLatencyInput(raw: String): Int? {
    val normalized = buildString {
        for (ch in raw) {
            append(
                when (ch) {
                    in '０'..'９' -> '0' + (ch - '０')
                    '－', '−', '–', '—' -> '-'
                    '＋' -> '+'
                    else -> ch
                },
            )
        }
    }.replace(Regex("\\s+"), "")
        .removeSuffix("毫秒")
        .removeSuffix("ms")
        .removeSuffix("MS")
        .removeSuffix("Ms")
        .removeSuffix("mS")

    val match = Regex("^([+-]?)(\\d+)$").find(normalized) ?: return null
    val negative = match.groupValues[1] == "-"
    val digits = match.groupValues[2]
    // Long inputs clamp instead of overflowing: 999 is a typo for "500-ish", not an error state.
    val magnitude = if (digits.length > 4) {
        RhythmLatencyStore.MAX_OFFSET_MS
    } else {
        digits.toIntOrNull()?.coerceAtMost(RhythmLatencyStore.MAX_OFFSET_MS)
            ?: return null
    }
    return if (negative) -magnitude else magnitude
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
