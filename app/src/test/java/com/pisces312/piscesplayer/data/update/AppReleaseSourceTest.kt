package com.pisces312.piscesplayer.data.update

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.junit.jupiter.api.Test

class AppReleaseSourceTest {

    @Test
    fun `newest release is picked by version even when the API lists it later`() {
        val releases = JSONArray(
            listOf(
                release("v0.3.0-alpha.9"),
                release("v0.3.0-alpha.16"),
                release("v0.3.0-alpha.15"),
            ).joinToString(prefix = "[", postfix = "]")
        )

        val latest = AppReleaseSource.parseLatestRelease(releases, listOf("arm64-v8a"))!!

        assertThat(latest.versionName).isEqualTo("0.3.0-alpha.16")
        assertThat(latest.apkUrl).endsWith("/piscesplayer-arm64-v8a-0.3.0-alpha.16-release.apk")
    }

    @Test
    fun `release asset follows the device ABI preference order`() {
        val releases = JSONArray("[${release("v0.3.0-alpha.16")}]")

        val latest = AppReleaseSource.parseLatestRelease(releases, listOf("armeabi-v7a", "arm64-v8a"))!!

        assertThat(latest.apkUrl).endsWith("/piscesplayer-armeabi-v7a-0.3.0-alpha.16-release.apk")
    }

    @Test
    fun `plain semantic versions are offered and drafts or missing APKs are not`() {
        val releases = JSONArray(
            listOf(
                release("v0.3.0-alpha.20", draft = true),
                release("v0.4.0", prerelease = false),
                release("v0.3.0-alpha.19", abis = listOf("x86_64")),
                release("v0.3.0-alpha.18"),
            ).joinToString(prefix = "[", postfix = "]")
        )

        assertThat(AppReleaseSource.parseLatestRelease(releases, listOf("arm64-v8a"))!!.versionName)
            .isEqualTo("0.4.0")
        assertThat(AppReleaseSource.parseLatestRelease(releases, listOf("x86"))).isNull()
    }

    @Test
    fun `plain semantic versions sort by base version`() {
        val releases = JSONArray(
            listOf(release("v1.0.0"), release("v1.0.1"), release("v1.1.0"))
                .joinToString(prefix = "[", postfix = "]")
        )

        assertThat(AppReleaseSource.parseLatestRelease(releases, listOf("arm64-v8a"))!!.versionName)
            .isEqualTo("1.1.0")
    }

    @Test
    fun `alpha versions order by base version before build number`() {
        val olderBaseHigherBuild = alphaRelease("0.3.0-alpha.99")
        val newerBase = alphaRelease("0.4.0-alpha.1")

        assertThat(newerBase.isNewerThan("0.3.0-alpha.99")).isTrue()
        assertThat(olderBaseHigherBuild.isNewerThan("0.4.0-alpha.1")).isFalse()
        assertThat(alphaRelease("0.3.0-alpha.16").isNewerThan("0.3.0-alpha.16")).isFalse()
    }

    @Test
    fun `PR builds on the alpha key are offered any published alpha`() {
        assertThat(alphaRelease("0.3.0-alpha.16").isNewerThan("0.3.0-pr.137.ba94188")).isTrue()
    }

    @Test
    fun `plain semantic versions compare against prereleases`() {
        assertThat(alphaRelease("1.0.0").isNewerThan("1.0.0")).isFalse()
        assertThat(alphaRelease("1.0.1").isNewerThan("1.0.0")).isTrue()
        assertThat(alphaRelease("1.0.0").isNewerThan("0.4.3-pisces.1")).isTrue()
    }

    private fun alphaRelease(versionName: String) = AppRelease(
        versionName = versionName,
        apkUrl = "https://example.invalid/app.apk",
        apkSizeBytes = null,
        pageUrl = "https://example.invalid",
    )

    private fun release(
        tag: String,
        prerelease: Boolean = true,
        draft: Boolean = false,
        abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
    ): String {
        val name = tag.removePrefix("v")
        val assets = abis.joinToString(prefix = "[", postfix = "]") { abi ->
            """{"name":"piscesplayer-$abi-$name-release.apk","size":37082259,
               "browser_download_url":"https://github.com/pisces312/PiscesPlayer/releases/download/$tag/piscesplayer-$abi-$name-release.apk"}"""
        }
        return """{"tag_name":"$tag","prerelease":$prerelease,"draft":$draft,
                   "html_url":"https://github.com/pisces312/PiscesPlayer/releases/tag/$tag","assets":$assets}"""
    }
}
