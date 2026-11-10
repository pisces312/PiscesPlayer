package com.pisces312.piscesplayer.presentation.stats

import androidx.annotation.StringRes
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.stats.StatsTimeRange

@StringRes
fun StatsTimeRange.displayNameRes(): Int = when (this) {
    StatsTimeRange.DAY -> R.string.presentation_batch_g_stats_range_today
    StatsTimeRange.WEEK -> R.string.presentation_batch_g_stats_range_week_to_date
    StatsTimeRange.MONTH -> R.string.presentation_batch_g_stats_range_month_to_date
    StatsTimeRange.YEAR -> R.string.presentation_batch_g_stats_range_year_to_date
    StatsTimeRange.ALL -> R.string.presentation_batch_g_stats_range_all_time
}

/** Compact label for the equal-width range selector. */
@StringRes
fun StatsTimeRange.shortNameRes(): Int = when (this) {
    StatsTimeRange.DAY -> R.string.presentation_batch_g_stats_range_day_short
    StatsTimeRange.WEEK -> R.string.presentation_batch_g_stats_range_week_short
    StatsTimeRange.MONTH -> R.string.presentation_batch_g_stats_range_month_short
    StatsTimeRange.YEAR -> R.string.presentation_batch_g_stats_range_year_short
    StatsTimeRange.ALL -> R.string.presentation_batch_g_stats_range_all_short
}
