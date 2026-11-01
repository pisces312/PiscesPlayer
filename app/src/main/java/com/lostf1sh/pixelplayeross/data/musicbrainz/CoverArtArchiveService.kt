package com.lostf1sh.pixelplayeross.data.musicbrainz

import com.lostf1sh.pixelplayeross.BuildConfig
import com.lostf1sh.pixelplayeross.data.media.CoverArtUpdate
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads front cover art from the Cover Art Archive.
 *
 * CAA answers 307 to archive.org, and some networks cannot reach archive.org at all. A missing
 * cover (404/410), an unreachable mirror or an oversized image is reported as null so callers
 * can still apply the textual fields.
 */
@Singleton
class CoverArtArchiveService @Inject constructor(
    baseClient: OkHttpClient
) {
    private val client = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "PixelPlayerOSS/${BuildConfig.VERSION_NAME} " +
                            "(https://github.com/PixelPlayerHQ/PixelPlayerOSS)"
                    )
                    .header("Accept", "image/*")
                    .build()
            )
        }
        .build()

    suspend fun download(url: String): CoverArtUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404 || response.code == 410) return@withContext null
            if (!response.isSuccessful) {
                throw IOException("Cover Art Archive returned HTTP ${response.code}")
            }
            val mimeType = response.header("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
            if (mimeType == null || !mimeType.startsWith("image/")) {
                throw IOException("Cover Art Archive returned a non-image response")
            }
            if (response.body.contentLength() > MAX_BYTES) return@withContext null

            val bytes = response.body.byteStream().use { stream ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = stream.read(chunk)
                    if (read == -1) break
                    total += read
                    if (total > MAX_BYTES) return@withContext null
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
            if (bytes.isEmpty()) return@withContext null
            CoverArtUpdate(bytes = bytes, mimeType = mimeType)
        }
    }

    companion object {
        private const val MAX_BYTES = 8L * 1024L * 1024L

        fun releaseCoverUrl(releaseId: String, size: Int = 500): String =
            "https://coverartarchive.org/release/$releaseId/front-$size"

        fun releaseGroupCoverUrl(releaseGroupId: String, size: Int = 500): String =
            "https://coverartarchive.org/release-group/$releaseGroupId/front-$size"
    }
}
