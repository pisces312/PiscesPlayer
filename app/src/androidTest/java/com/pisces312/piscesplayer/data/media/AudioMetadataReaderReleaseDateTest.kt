package com.pisces312.piscesplayer.data.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Verifies the release-date read path (full date / year-only / no date) parses tags correctly
 * and — the critical invariant — never modifies the song file bytes. Both the TagLib primary
 * path and the JAudioTagger fallback are exercised inside [AudioMetadataReader.read]; the
 * byte-equality assertion covers the whole call regardless of which internal path runs.
 */
@RunWith(AndroidJUnit4::class)
class AudioMetadataReaderReleaseDateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `full date tag is parsed and the file bytes are untouched`() {
        val file = tempMp3("ro_full.mp3", dateTag = "1998-05-12")
        val before = file.readBytes()

        val meta = AudioMetadataReader.read(file, readArtwork = false)
        val derived = deriveReleaseDateValue(meta?.releaseDate, meta?.year)

        assertThat(meta?.releaseDate).isEqualTo("1998-05-12")
        assertThat(derived).isEqualTo("1998-05-12")
        assertThat(file.readBytes()).isEqualTo(before)
    }

    @Test
    fun `year-only tag falls back to january first and the file bytes are untouched`() {
        val file = tempMp3("ro_year.mp3", dateTag = "1998")
        val before = file.readBytes()

        val meta = AudioMetadataReader.read(file, readArtwork = false)
        val derived = deriveReleaseDateValue(meta?.releaseDate, meta?.year)

        assertThat(meta?.releaseDate).isNull()
        assertThat(meta?.year).isEqualTo(1998)
        assertThat(derived).isEqualTo("1998-01-01")
        assertThat(file.readBytes()).isEqualTo(before)
    }

    @Test
    fun `file without date tag yields the checked sentinel and the file bytes are untouched`() {
        val file = tempMp3("ro_nodate.mp3", dateTag = null)
        val before = file.readBytes()

        val meta = AudioMetadataReader.read(file, readArtwork = false)
        val derived = deriveReleaseDateValue(meta?.releaseDate, meta?.year)

        assertThat(derived).isEqualTo("0")
        assertThat(file.readBytes()).isEqualTo(before)
    }

    private fun tempMp3(name: String, dateTag: String?): File =
        File(context.cacheDir, name).apply {
            deleteOnExit()
            writeBytes(buildId3v24Mp3(dateTag))
        }

    /** Minimal MP3: optional ID3v2.4 TDRC frame + one silent MPEG-1 Layer III frame. */
    private fun buildId3v24Mp3(dateTag: String?): ByteArray {
        val out = ByteArrayOutputStream()
        if (dateTag != null) {
            val body = byteArrayOf(0x00) + dateTag.toByteArray(Charsets.ISO_8859_1)
            val frame = "TDRC".toByteArray(Charsets.ISO_8859_1) +
                syncsafeInt(body.size) +
                byteArrayOf(0x00, 0x00) + // frame flags
                body
            out.write("ID3".toByteArray(Charsets.ISO_8859_1))
            out.write(byteArrayOf(0x04, 0x00, 0x00)) // v2.4, no flags
            out.write(syncsafeInt(frame.size))
            out.write(frame)
        }
        // MPEG frame header: 128 kbps, 44.1 kHz, mono → 417-byte frame.
        out.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00))
        out.write(ByteArray(413))
        return out.toByteArray()
    }

    private fun syncsafeInt(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte()
    )
}
