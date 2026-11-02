package com.lostf1sh.pixelplayeross.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RhythmChartLeadInTest {

    private fun chart(vararg times: Int) = RhythmChart(
        source = RhythmChart.SOURCE_DSP,
        difficultyName = "DSP Raw",
        notes = times.mapIndexed { index, time -> RhythmNote(index, time, index % 4) },
        durationMs = 20_000,
    )

    @Test
    fun dropsNotesBeforeTheLeadIn() {
        val trimmed = chart(500, 999, 1000, 1500).withoutLeadIn()

        assertEquals(listOf(1000, 1500), trimmed.notes.map { it.timeMs })
    }

    @Test
    fun keepsAChartThatLivesEntirelyInsideTheLeadIn() {
        val original = chart(500, 900)

        assertSame(original, original.withoutLeadIn())
    }

    @Test
    fun keepsTheChartWhenNothingIsEarly() {
        val original = chart(1000, 1500)

        assertSame(original, original.withoutLeadIn())
    }
}
