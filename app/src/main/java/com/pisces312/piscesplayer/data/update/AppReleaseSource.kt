package com.pisces312.piscesplayer.data.update

import android.os.Build
import com.pisces312.piscesplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Looks up the newest published build of each channel. */
@Singleton
class AppReleaseSource @Inject constructor(
    baseClient: OkHttpClient,
) {
    private val client = baseClient.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "PiscesPlayer/${BuildConfig.VERSION_NAME} ($PROJECT_URL)")
                    .build()
            )
        }
        .build()

    /** Newest published release, or null when nothing installable for this device is published. */
    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        parseLatestRelease(
            JSONArray(getBody(GITHUB_RELEASES_API)),
            supportedAbis = Build.SUPPORTED_ABIS.toList()
        )
    }

    private fun getBody(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
            return response.body.string()
        }
    }

    companion object {
        private const val PROJECT_URL = "https://github.com/pisces312/PixelPlayerOSS"

        /** Release applicationId; debug builds add a suffix but should still see real releases. */
        internal const val PUBLISHED_PACKAGE = "com.pisces312.piscesplayer"
        private const val GITHUB_RELEASES_API =
            "https://api.github.com/repos/pisces312/PixelPlayerOSS/releases?per_page=30"

        /**
         * Newest published release with an APK for one of [supportedAbis] (in device preference
         * order). This fork publishes one lowercase APK per release named
         * `pixelplayeross-<abi>-<version>-release.apk` (abi in the middle, ends with `.apk`).
         */
        internal fun parseLatestRelease(releases: JSONArray, supportedAbis: List<String>): AppRelease? {
            return (0 until releases.length())
                .mapNotNull { releases.optJSONObject(it) }
                // This fork publishes plain (non-prerelease) GitHub releases, so we must not
                // filter by `prerelease` the way upstream does; keep the `!draft` guard only.
                .filter { !it.optBoolean("draft") }
                .mapNotNull { release ->
                    val tag = release.optString("tag_name")
                    val version = AlphaVersion.parse(tag) ?: return@mapNotNull null
                    val asset = pickAsset(release.optJSONArray("assets"), supportedAbis) ?: return@mapNotNull null
                    version to AppRelease(
                        versionName = tag.removePrefix("v"),
                        apkUrl = asset.optString("browser_download_url"),
                        apkSizeBytes = asset.optLong("size", -1L).takeIf { it > 0 },
                        pageUrl = release.optString("html_url").ifBlank { "$PROJECT_URL/releases/tag/$tag" },
                    )
                }
                .maxByOrNull { (version, _) -> version }
                ?.second
        }

        private fun pickAsset(assets: JSONArray?, supportedAbis: List<String>): JSONObject? {
            if (assets == null) return null
            val apks = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .filter {
                    it.optString("name").endsWith(".apk") && it.optString("browser_download_url").isNotBlank()
                }
            return supportedAbis.firstNotNullOfOrNull { abi ->
                apks.firstOrNull { hasAbiSegment(it.optString("name"), abi) }
            }
        }

        /**
         * Matches the ABI as a whole `-`-separated segment of the file name, so `x86` never picks
         * up an `x86_64` build while `arm64-v8a` stays one segment in either naming scheme.
         */
        private fun hasAbiSegment(fileName: String, abi: String): Boolean {
            val stem = fileName.removeSuffix(".apk")
            var from = 0
            while (true) {
                val start = stem.indexOf("-$abi", from)
                if (start < 0) return false
                val end = start + 1 + abi.length
                if (end == stem.length || stem[end] == '-' || stem[end] == '.') return true
                from = start + 1
            }
        }
    }
}
