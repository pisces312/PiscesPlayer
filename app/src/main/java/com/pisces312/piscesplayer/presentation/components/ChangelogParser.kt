package com.pisces312.piscesplayer.presentation.components

import androidx.annotation.StringRes
import com.pisces312.piscesplayer.R

/**
 * Parses the repository's CHANGELOG.md (Keep a Changelog format) into the UI model used by
 * [ChangelogBottomSheet], so the in-app changelog stays in sync with the single source of
 * truth instead of a hardcoded list of localized entries.
 *
 * Only the section *titles* remain localized (via [sectionTitleRes]); the bullet text is
 * taken verbatim from CHANGELOG.md, which is English-only.
 *
 * Version blocks without a date (for example `## [Unreleased]`) and sections whose heading
 * has no localized label are skipped.
 */
object ChangelogParser {

    private val versionRegex = Regex("""^## \[(.+?)] - (.+)$""")
    private val sectionRegex = Regex("""^### (.+)$""")

    // Bullets may carry a bold lead-in label, written either as `**Playback:** text` or
    // `**Playback**: text`. The label is dropped; only the description is rendered.
    private val bulletRegex = Regex("""^-\s+(?:\*\*(.+?)\*\*:?\s*)?(.*)$""")

    fun parse(markdown: String): List<ChangelogVersion> {
        val result = mutableListOf<ChangelogVersion>()

        var currentVersion: String? = null
        var currentDate: String? = null
        val currentSections = mutableListOf<ChangelogSection>()
        var currentSectionTitleRes: Int? = null
        val currentItems = mutableListOf<String>()

        fun closeSection() {
            val titleRes = currentSectionTitleRes ?: return
            if (currentItems.isNotEmpty()) {
                currentSections.add(ChangelogSection(titleRes, currentItems.toList()))
            }
            currentItems.clear()
            currentSectionTitleRes = null
        }

        fun closeVersion() {
            closeSection()
            val version = currentVersion
            val date = currentDate
            if (version != null && date != null && currentSections.isNotEmpty()) {
                result.add(ChangelogVersion(version, date, currentSections.toList()))
            }
            // Always reset: sections collected under a skipped block (such as the undated
            // `## [Unreleased]` heading) must not leak into the next released version.
            currentVersion = null
            currentDate = null
            currentSections.clear()
        }

        for (line in markdown.lineSequence()) {
            val trimmed = line.trimEnd()
            versionRegex.matchEntire(trimmed)?.let { match ->
                closeVersion()
                currentVersion = match.groupValues[1]
                currentDate = match.groupValues[2].trim()
                continue
            }
            if (trimmed.startsWith("## [")) {
                // "## [Unreleased]" or a header without a date — skip this block.
                closeVersion()
                continue
            }
            sectionRegex.matchEntire(trimmed)?.let { match ->
                closeSection()
                currentSectionTitleRes = sectionTitleRes(match.groupValues[1])
                continue
            }
            bulletRegex.matchEntire(trimmed)?.let { match ->
                if (currentSectionTitleRes != null) {
                    currentItems.add(match.groupValues[2].trim())
                }
            }
        }
        closeVersion()
        return result
    }

    /**
     * Maps a Keep a Changelog section heading to the localized category label.
     * Returns null for headings that should not be shown.
     */
    @StringRes
    private fun sectionTitleRes(title: String): Int? = when (title.trim().lowercase()) {
        "added", "what's new", "whats new" ->
            R.string.presentation_batch_g_changelog_sec_whats_new

        "changed", "improvements", "performance" ->
            R.string.presentation_batch_g_changelog_sec_improvements

        "fixed", "fixes" -> R.string.presentation_batch_g_changelog_sec_fixed
        "removed" -> R.string.presentation_batch_g_changelog_sec_removed
        "security", "security and privacy" ->
            R.string.presentation_batch_g_changelog_sec_security_privacy

        "initial release" -> R.string.presentation_batch_g_changelog_sec_initial_release
        "removed for foss" -> R.string.presentation_batch_g_changelog_sec_removed_for_foss
        "release readiness" -> R.string.presentation_batch_g_changelog_sec_release_readiness
        "app polish included in this foss release" ->
            R.string.presentation_batch_g_changelog_sec_app_polish

        else -> null
    }
}
