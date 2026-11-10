@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.pisces312.piscesplayer.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.model.StorageFilter
import com.pisces312.piscesplayer.data.model.YearBucket
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight
import com.pisces312.piscesplayer.presentation.navigation.Screen
import com.pisces312.piscesplayer.presentation.navigation.navigateSafely
import com.pisces312.piscesplayer.presentation.viewmodel.LibraryViewModel
import com.pisces312.piscesplayer.utils.formatSongCount

/**
 * Library "Years" tab (L1): smart playlists grouped by release year.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun YearsTabContent(
    libraryViewModel: LibraryViewModel,
    navController: NavController,
    bottomBarHeight: Dp,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    storageFilter: StorageFilter = StorageFilter.ALL
) {
    val buckets by libraryViewModel.yearBucketsFlow.collectAsStateWithLifecycle(initialValue = kotlinx.collections.immutable.persistentListOf())
    val pullState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = pullState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    ) {
        if (buckets.isEmpty()) {
            LibraryExpressiveEmptyState(
                tabId = com.pisces312.piscesplayer.data.model.LibraryTabId.YEARS,
                storageFilter = storageFilter,
                bottomBarHeight = bottomBarHeight
            )
            return@PullToRefreshBox
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 6.dp,
                bottom = bottomBarHeight + MiniPlayerHeight + ListExtraBottomGap + 4.dp
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(items = buckets, key = { it.year }) { bucket ->
                YearBucketRow(
                    bucket = bucket,
                    onClick = {
                        navController.navigateSafely(
                            Screen.YearDetail.createRoute(bucket.year)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun YearBucketRow(
    bucket: YearBucket,
    onClick: () -> Unit
) {
    val title = if (bucket.isUnknown) {
        stringResource(R.string.unknown_year)
    } else {
        bucket.year.toString()
    }
    val subtitle = formatSongCount(bucket.songCount)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (bucket.isUnknown) {
                Icon(
                    painter = painterResource(R.drawable.rounded_calendar_view_week_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            } else {
                Text(
                    text = bucket.year.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
