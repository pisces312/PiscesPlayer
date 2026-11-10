package com.pisces312.piscesplayer.data.stats

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * A [StatsTimeRange] pinned to one concrete period.
 *
 * [StatsTimeRange] on its own always means "up to now"; browsing needs to point at an arbitrary
 * day, week, month or year and get the same numbers for it. [anchorMillis] says which one —
 * `null` means the period containing `now`, which keeps existing callers unchanged.
 */
data class StatsPeriod(
    val range: StatsTimeRange,
    val anchorMillis: Long? = null
) {

    /** First day of the period, or null for [StatsTimeRange.ALL] which has no natural start. */
    fun startDate(nowMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate? {
        val anchorDate = Instant.ofEpochMilli(anchorMillis ?: nowMillis).atZone(zoneId).toLocalDate()
        return when (range) {
            StatsTimeRange.DAY -> anchorDate
            StatsTimeRange.WEEK -> anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            StatsTimeRange.MONTH -> anchorDate.withDayOfMonth(1)
            StatsTimeRange.YEAR -> anchorDate.withDayOfYear(1)
            StatsTimeRange.ALL -> null
        }
    }

    /** Day after the last day of the period, exclusive. Null for [StatsTimeRange.ALL]. */
    fun endDateExclusive(nowMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate? {
        val start = startDate(nowMillis, zoneId) ?: return null
        return when (range) {
            StatsTimeRange.DAY -> start.plusDays(1)
            StatsTimeRange.WEEK -> start.plusWeeks(1)
            StatsTimeRange.MONTH -> start.plusMonths(1)
            StatsTimeRange.YEAR -> start.plusYears(1)
            StatsTimeRange.ALL -> null
        }
    }

    /** How many whole periods this one sits before the current one. 0 means "current". */
    fun periodsFromCurrent(nowMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): Int {
        val start = startDate(nowMillis, zoneId) ?: return 0
        val currentStart = StatsPeriod(range).startDate(nowMillis, zoneId) ?: return 0
        val amount = when (range) {
            StatsTimeRange.DAY -> ChronoUnit.DAYS.between(start, currentStart)
            StatsTimeRange.WEEK -> ChronoUnit.WEEKS.between(start, currentStart)
            StatsTimeRange.MONTH -> ChronoUnit.MONTHS.between(start, currentStart)
            StatsTimeRange.YEAR -> ChronoUnit.YEARS.between(start, currentStart)
            StatsTimeRange.ALL -> 0L
        }
        return amount.toInt()
    }

    /**
     * The period [steps] positions away. Result is clamped to the current period so browsing
     * cannot run into the future; the past is bounded by whatever history exists.
     */
    fun shift(steps: Int, nowMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): StatsPeriod {
        if (steps == 0) return this
        val start = startDate(nowMillis, zoneId) ?: return this
        val shiftedStart = when (range) {
            StatsTimeRange.DAY -> start.plusDays(steps.toLong())
            StatsTimeRange.WEEK -> start.plusWeeks(steps.toLong())
            StatsTimeRange.MONTH -> start.plusMonths(steps.toLong())
            StatsTimeRange.YEAR -> start.plusYears(steps.toLong())
            StatsTimeRange.ALL -> start
        }
        val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val currentStart = StatsPeriod(range).startDate(nowMillis, zoneId) ?: nowDate
        if (!shiftedStart.isBefore(currentStart) && range != StatsTimeRange.ALL) {
            return StatsPeriod(range)
        }
        return StatsPeriod(
            range = range,
            anchorMillis = shiftedStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
        )
    }

    companion object {
        fun current(range: StatsTimeRange): StatsPeriod = StatsPeriod(range)
    }
}
