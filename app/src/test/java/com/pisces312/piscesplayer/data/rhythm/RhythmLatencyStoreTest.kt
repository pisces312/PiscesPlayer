package com.pisces312.piscesplayer.data.rhythm

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.pisces312.piscesplayer.presentation.rhythm.toggleLatencySign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The per-device store: which record answers for which device, and what the two writers (a
 * measurement and a typed number) leave behind. [Context] is faked down to the three calls the
 * store makes, so no Robolectric is needed.
 */
class RhythmLatencyStoreTest {

    private lateinit var prefs: FakePrefs
    private lateinit var context: Context

    private val btMac = LatencyDevice(
        id = "bt:aa:bb:cc:dd:ee:ff",
        kind = LatencyRouteKind.BLUETOOTH,
        displayName = "WH-1000XM4",
        identifiable = true,
    )
    private val btName = LatencyDevice(
        id = "bt:wh-1000xm4",
        kind = LatencyRouteKind.BLUETOOTH,
        displayName = "WH-1000XM4",
        identifiable = true,
    )
    private val wired = LatencyDevice(
        id = "wired",
        kind = LatencyRouteKind.WIRED,
        displayName = "",
        identifiable = false,
    )

    @Before
    fun setUp() {
        prefs = FakePrefs()
        context = FakeContext(prefs)
    }

    @Test
    fun `device record wins over the kind baseline`() {
        prefs.putInt("offset_ms:bluetooth", -430)
        prefs.putFloat("sigma_ms:bluetooth", 9f)
        RhythmLatencyStore.setManual(context, btMac, -120)

        val setting = RhythmLatencyStore.resolve(context, btMac)
        assertEquals(-120, setting.offsetMs)
        assertEquals(LatencySource.MANUAL, setting.source)
        assertNull(setting.inheritedFrom)
    }

    @Test
    fun `an unrecorded device inherits the kind baseline`() {
        // Route-keyed builds wrote the offset and the sigma but no source/updated_at.
        prefs.putInt("offset_ms:bluetooth", -430)
        prefs.putFloat("sigma_ms:bluetooth", 9f)
        prefs.putLong("calibrated_at:bluetooth", 1_700_000_000_000L)

        val setting = RhythmLatencyStore.resolve(context, btMac)
        assertEquals(-430, setting.offsetMs)
        assertEquals(LatencySource.INHERITED, setting.source)
        assertEquals(LatencyRouteKind.BLUETOOTH, setting.inheritedFrom)
        assertEquals(1_700_000_000_000L, setting.updatedAtMs)
        assertEquals("inherited keeps the device it was resolved for", btMac.id, setting.device.id)
    }

    @Test
    fun `nothing stored means zero and no record`() {
        val setting = RhythmLatencyStore.resolve(context, wired)
        assertEquals(0, setting.offsetMs)
        assertEquals(LatencySource.NONE, setting.source)
        assertFalse(setting.hasRecord)
    }

    @Test
    fun `manual write drops the jitter but keeps the last measurement`() {
        RhythmLatencyStore.saveCalibration(context, btMac, -420, 8.5)
        RhythmLatencyStore.setManual(context, btMac, -437)

        val setting = RhythmLatencyStore.resolve(context, btMac)
        assertEquals(-437, setting.offsetMs)
        assertEquals(LatencySource.MANUAL, setting.source)
        assertNull("a typed number has no run jitter", setting.sigmaMs)
        assertEquals(-420, RhythmLatencyStore.calibratedOffsetMs(context, btMac))
    }

    @Test
    fun `restore puts the measured value and its jitter back`() {
        RhythmLatencyStore.saveCalibration(context, btMac, -420, 8.5)
        RhythmLatencyStore.setManual(context, btMac, -100)
        assertTrue(RhythmLatencyStore.restoreCalibrated(context, btMac))

        val setting = RhythmLatencyStore.resolve(context, btMac)
        assertEquals(-420, setting.offsetMs)
        assertEquals(LatencySource.CALIBRATED, setting.source)
        assertEquals(8.5, setting.sigmaMs!!, 0.001)
    }

    @Test
    fun `restore without a measurement does nothing`() {
        RhythmLatencyStore.setManual(context, wired, -85)
        assertFalse(RhythmLatencyStore.restoreCalibrated(context, wired))
        assertEquals(-85, RhythmLatencyStore.resolve(context, wired).offsetMs)
    }

    @Test
    fun `values are clamped to the measurement range`() {
        RhythmLatencyStore.setManual(context, btMac, -9_999)
        assertEquals(-RhythmLatencyStore.MAX_OFFSET_MS, RhythmLatencyStore.resolve(context, btMac).offsetMs)
        RhythmLatencyStore.saveCalibration(context, btMac, 9_999, 1.0)
        assertEquals(RhythmLatencyStore.MAX_OFFSET_MS, RhythmLatencyStore.resolve(context, btMac).offsetMs)
    }

    @Test
    fun `forget returns the device to its baseline`() {
        prefs.putInt("offset_ms:bluetooth", -430)
        RhythmLatencyStore.setManual(context, btMac, -120)
        RhythmLatencyStore.forget(context, btMac.id)

        val setting = RhythmLatencyStore.resolve(context, btMac)
        assertEquals(-430, setting.offsetMs)
        assertEquals(LatencySource.INHERITED, setting.source)
    }

    @Test
    fun `saved devices list the record rows only, active first`() {
        RhythmLatencyStore.setManual(context, btMac, -420)
        RhythmLatencyStore.saveCalibration(context, wired, -85, 12.0)
        // An orphan label left behind by a half-removed record must not become a row.
        prefs.putString("label:bt:dead:be:ef:00:11", "Ghost")

        val saved = RhythmLatencyStore.savedDevices(context)
        assertEquals(2, saved.size)
        assertTrue(saved.none { it.device.displayName == "Ghost" })
        assertEquals(btMac.id, saved[0].device.id)
        assertEquals(-420, saved[0].offsetMs)
        assertEquals(LatencySource.CALIBRATED, saved[1].source)
    }

    @Test
    fun `adopt renames every key of a record`() {
        RhythmLatencyStore.saveCalibration(context, btName, -420, 8.5)
        RhythmLatencyStore.adopt(context, btName.id, btMac.id)

        assertNull(prefs.all["offset_ms:bt:wh-1000xm4"])
        val setting = RhythmLatencyStore.resolve(context, btMac)
        assertEquals(-420, setting.offsetMs)
        assertEquals(LatencySource.CALIBRATED, setting.source)
        assertEquals(8.5, setting.sigmaMs!!, 0.001)
    }

    @Test
    fun `adoptNameKeyedRecord moves only a same-kind same-label record`() {
        RhythmLatencyStore.saveCalibration(context, btName, -420, 8.5)
        assertTrue(RhythmLatencyStore.adoptNameKeyedRecord(context, btMac))
        assertEquals(-420, RhythmLatencyStore.resolve(context, btMac).offsetMs)

        // A renamed headset must not be guessed back to the old record.
        prefs.clear()
        RhythmLatencyStore.saveCalibration(
            context,
            btName.copy(displayName = "Old Name"),
            -420,
            8.5,
        )
        assertFalse(RhythmLatencyStore.adoptNameKeyedRecord(context, btMac))
    }

    @Test
    fun `adoptNameKeyedRecord ignores the generic bucket`() {
        prefs.putInt("offset_ms:bluetooth", -430)
        assertFalse(RhythmLatencyStore.adoptNameKeyedRecord(context, btMac))
        assertNotNull(prefs.all["offset_ms:bluetooth"])
    }
}

class LatencyInputTest {

    @Test
    fun `accepts every way a human writes a negative number`() {
        assertEquals(-420, parseLatencyInput("−420"))
        assertEquals(-420, parseLatencyInput("-420"))
        assertEquals(-420, parseLatencyInput("－４２０"))
        assertEquals(-420, parseLatencyInput(" -420 "))
        assertEquals(-420, parseLatencyInput("−420ms"))
        assertEquals(42, parseLatencyInput("+42"))
        assertEquals(0, parseLatencyInput("0"))
        assertEquals(0, parseLatencyInput("-0"))
    }

    @Test
    fun `rejects drafts that are not a number yet`() {
        assertNull(parseLatencyInput(""))
        assertNull(parseLatencyInput("   "))
        assertNull(parseLatencyInput("-"))
        assertNull(parseLatencyInput("--5"))
        assertNull(parseLatencyInput("4a2"))
    }

    @Test
    fun `clamps instead of overflowing`() {
        assertEquals(500, parseLatencyInput("999"))
        assertEquals(-500, parseLatencyInput("-999"))
        assertEquals(500, parseLatencyInput("12345678901234567890"))
    }

    @Test
    fun `sign toggle flips a parsed value and leaves a draft alone`() {
        assertEquals("420", toggleLatencySign("−420"))
        assertEquals("−420", toggleLatencySign("420"))
        assertEquals("−42", toggleLatencySign("+42"))
        assertEquals("-", toggleLatencySign("-"))
    }
}

private class FakeContext(private val prefs: FakePrefs) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    override fun getSystemService(name: String): Any? = null
}

private class FakePrefs : SharedPreferences {
    val all = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = all
    override fun getString(key: String?, defValue: String?): String? =
        all[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (all[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = all[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = all[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = all[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        all[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = all.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    fun putInt(key: String, value: Int) {
        all[key] = value
    }

    fun putFloat(key: String, value: Float) {
        all[key] = value
    }

    fun putLong(key: String, value: Long) {
        all[key] = value
    }

    fun putString(key: String, value: String) {
        all[key] = value
    }

    fun clear() = all.clear()

    private inner class Editor : SharedPreferences.Editor {
        private val writes = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { writes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) =
            apply { writes[key] = values }

        override fun putInt(key: String, value: Int) = apply { writes[key] = value }
        override fun putLong(key: String, value: Long) = apply { writes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { writes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { writes[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { clearAll = true }
        override fun commit(): Boolean {
            flush()
            return true
        }

        override fun apply() = flush()

        private fun flush() {
            if (clearAll) all.clear()
            removals.forEach { all.remove(it) }
            writes.forEach { (key, value) -> if (value == null) all.remove(key) else all[key] = value }
        }
    }
}
