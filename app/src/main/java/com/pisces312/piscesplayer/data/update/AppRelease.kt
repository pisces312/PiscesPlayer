package com.pisces312.piscesplayer.data.update

/** `1.0.0` or `0.3.0-alpha.16` as published on GitHub. */
data class AlphaVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val build: Int,
) : Comparable<AlphaVersion> {

    override fun compareTo(other: AlphaVersion): Int = compareValuesBy(
        this, other, AlphaVersion::major, AlphaVersion::minor, AlphaVersion::patch, AlphaVersion::build
    )

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(?:alpha|pisces)\.(\d+))?$""")

        fun parse(value: String): AlphaVersion? {
            val groups = PATTERN.matchEntire(value.trim())?.groupValues ?: return null
            return AlphaVersion(
                major = groups[1].toIntOrNull() ?: return null,
                minor = groups[2].toIntOrNull() ?: return null,
                patch = groups[3].toIntOrNull() ?: return null,
                build = groups[4].toIntOrNull() ?: 0,
            )
        }
    }
}

/** A published build that can be offered to the user. */
data class AppRelease(
    val versionName: String,
    val apkUrl: String,
    val apkSizeBytes: Long?,
    val pageUrl: String,
)

/** Whether this release is newer than [installedVersionName]. */
internal fun AppRelease.isNewerThan(installedVersionName: String): Boolean {
    val candidate = AlphaVersion.parse(versionName) ?: return false
    // Debug and local builds may carry a non-published suffix; any parsed published
    // release is then a way back onto the published line.
    val installed = AlphaVersion.parse(installedVersionName) ?: return true
    return candidate > installed
}
