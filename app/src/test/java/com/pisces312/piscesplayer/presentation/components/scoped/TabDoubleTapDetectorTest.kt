package com.pisces312.piscesplayer.presentation.components.scoped

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TabDoubleTapDetectorTest {

    @Test
    fun `second click on same selected tab within window is double tap`() {
        var now = 1_000L
        val detector = TabDoubleTapDetector(windowMillis = 350L) { now }

        assertThat(detector.onClick("home", isAlreadySelected = true)).isFalse()
        now = 1_200L
        assertThat(detector.onClick("home", isAlreadySelected = true)).isTrue()
    }

    @Test
    fun `second click after window is not double tap`() {
        var now = 1_000L
        val detector = TabDoubleTapDetector(windowMillis = 350L) { now }

        assertThat(detector.onClick("home", isAlreadySelected = true)).isFalse()
        now = 1_400L
        assertThat(detector.onClick("home", isAlreadySelected = true)).isFalse()
    }

    @Test
    fun `click on different tab is not double tap`() {
        var now = 1_000L
        val detector = TabDoubleTapDetector(windowMillis = 350L) { now }

        detector.onClick("library", isAlreadySelected = true)
        now = 1_100L
        assertThat(detector.onClick("home", isAlreadySelected = true)).isFalse()
    }

    @Test
    fun `tap to navigate then quick second tap counts as double tap on selected tab`() {
        var now = 1_000L
        val detector = TabDoubleTapDetector(windowMillis = 350L) { now }

        // First tap while another tab is selected: navigates, action must not fire.
        assertThat(detector.onClick("search", isAlreadySelected = false)).isFalse()
        // Second tap after the tab becomes selected: completes the double-tap.
        now = 1_150L
        assertThat(detector.onClick("search", isAlreadySelected = true)).isTrue()
    }

    @Test
    fun `reset clears pending tap`() {
        var now = 1_000L
        val detector = TabDoubleTapDetector(windowMillis = 350L) { now }

        detector.onClick("home", isAlreadySelected = true)
        detector.reset()
        now = 1_100L
        assertThat(detector.onClick("home", isAlreadySelected = true)).isFalse()
    }
}
