package com.pisces312.piscesplayer.presentation.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogParserTest {

    @Test
    fun parse_readsShippedChangelogVersionsInOrder() {
        val parsed = ChangelogParser.parse(repositoryChangelog().readText())

        assertEquals(
            listOf(
                "1.0.1",
                "1.0.0",
                "0.4.3-pisces.1",
                "0.4.2-pisces.1",
                "0.4.1-pisces.1",
                "0.4.0-pisces.1",
                "0.3.0",
                "0.2.0",
                "0.1.0",
            ),
            parsed.map { it.version },
        )
        assertEquals(
            listOf(
                "2026-11-11",
                "2026-11-10",
                "2026-09-19",
                "2026-09-16",
                "2026-09-13",
                "2026-09-11",
                "2026-08-15",
                "2026-07-17",
                "2026-06-09",
            ),
            parsed.map { it.date },
        )
    }

    @Test
    fun parse_skipsUndatedVersionBlocks() {
        val parsed = ChangelogParser.parse(
            """
            ## [Unreleased]

            ### Fixed
            - Not shipped yet.

            ## [1.0.0] - 2026-01-01

            ### Fixed
            - Shipped.
            """.trimIndent()
        )

        assertEquals(listOf("1.0.0"), parsed.map { it.version })
        assertEquals(listOf("Shipped."), parsed.single().sections.single().items)
    }

    @Test
    fun parse_dropsSectionsWithoutALocalizedHeading() {
        val parsed = ChangelogParser.parse(
            """
            ## [1.0.0] - 2026-01-01

            ### Deprecated
            - Dropped.

            ### Fixed
            - Kept.
            """.trimIndent()
        )

        assertEquals(1, parsed.single().sections.size)
        assertEquals(listOf("Kept."), parsed.single().sections.single().items)
    }

    @Test
    fun parse_readsBulletsInsideSectionsWithRepeatedHeadings() {
        val parsed = ChangelogParser.parse(
            """
            ## [1.0.0] - 2026-01-01

            ### Added
            - First.

            ### Fixed
            - Second.

            ### Fixed
            - Third.
            """.trimIndent()
        )

        val sections = parsed.single().sections
        assertEquals(3, sections.size)
        assertEquals(listOf("First."), sections[0].items)
        assertEquals(listOf("Second."), sections[1].items)
        assertEquals(listOf("Third."), sections[2].items)
    }

    @Test
    fun parse_dropsBoldLeadInLabelsButKeepsTheDescription() {
        val parsed = ChangelogParser.parse(
            """
            ## [1.0.0] - 2026-01-01

            ### Added
            - **Playback:** gapless output.
            - **Library**: smarter grouping.
            - Plain bullet.
            """.trimIndent()
        )

        assertEquals(
            listOf("gapless output.", "smarter grouping.", "Plain bullet."),
            parsed.single().sections.single().items,
        )
    }

    /**
     * Guards the heading -> string-resource mapping: a heading the parser does not know is
     * silently dropped from the UI, so every heading shipped in CHANGELOG.md must resolve.
     */
    @Test
    fun parse_mapsEveryHeadingUsedByTheShippedChangelog() {
        val headings = repositoryChangelog().readLines()
            .asSequence()
            .filter { it.startsWith("### ") }
            .map { it.removePrefix("### ").trim() }
            .distinct()
            .toList()

        assertTrue("CHANGELOG.md should declare section headings", headings.isNotEmpty())

        val synthetic = headings
            .mapIndexed { index, heading ->
                "## [9.$index.0] - 2000-01-01\n\n### $heading\n\n- probe\n"
            }
            .joinToString("\n")

        val parsed = ChangelogParser.parse(synthetic)

        // A heading the parser does not know yields no section, so a smaller result means
        // one or more sections are silently missing from the sheet.
        assertEquals(
            "Unmapped heading(s) would disappear from the changelog sheet: $headings",
            headings.size,
            parsed.size,
        )
        parsed.forEach { version ->
            assertEquals(1, version.sections.size)
            assertEquals(listOf("probe"), version.sections.single().items)
        }
    }

    @Test
    fun parse_readsEverySectionOfTheShippedReleases() {
        val parsed = ChangelogParser.parse(repositoryChangelog().readText())

        // 1.0.1 declares one Keep a Changelog heading; 1.0.0, 0.4.3-pisces.1, 0.4.2-pisces.1
        // and 0.4.1-pisces.1 declare three; 0.4.0-pisces.1 and 0.2.0 declare four; 0.3.0 and
        // 0.1.0 declare five, 0.1.0 adding its own "App polish included in this FOSS release".
        assertEquals(listOf(1, 3, 3, 3, 3, 4, 5, 4, 5), parsed.map { it.sections.size })
        parsed.forEach { version ->
            version.sections.forEach { section ->
                assertTrue(
                    "Section of ${version.version} has no items",
                    section.items.isNotEmpty() && section.items.none { it.isBlank() },
                )
            }
        }
    }

    private fun repositoryChangelog(): File = listOf(File("CHANGELOG.md"), File("../CHANGELOG.md"))
        .firstOrNull { it.isFile }
        ?: error("CHANGELOG.md not found; cwd=${File(".").absolutePath}")
}
