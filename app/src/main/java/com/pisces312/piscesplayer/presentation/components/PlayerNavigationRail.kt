package com.pisces312.piscesplayer.presentation.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pisces312.piscesplayer.BottomNavItem
import com.pisces312.piscesplayer.presentation.components.scoped.TabDoubleTapDetector
import com.pisces312.piscesplayer.presentation.navigation.navigateToTopLevelSafely
import kotlinx.collections.immutable.ImmutableList

/**
 * Vertical navigation rail used instead of the bottom bar on medium/expanded
 * window widths (tablets, unfolded foldables, landscape phones). Mirrors the
 * navigation semantics of [PlayerInternalNavigationBar], including
 * double-tap-on-current-tab.
 */
@Composable
fun PlayerNavigationRail(
    navController: NavHostController,
    navItems: ImmutableList<BottomNavItem>,
    currentRoute: String?,
    modifier: Modifier = Modifier,
    onRootTabDoubleTap: (String) -> Unit = {}
) {
    val latestCurrentRoute by rememberUpdatedState(currentRoute)
    val latestOnRootTabDoubleTap by rememberUpdatedState(onRootTabDoubleTap)
    val latestNavigationEnabled by rememberUpdatedState(currentRoute != null)
    val doubleTapDetector = remember { TabDoubleTapDetector() }

    NavigationRail(modifier = modifier.fillMaxHeight()) {
        Spacer(Modifier.weight(1f))
        navItems.forEach { item ->
            val isSelected = currentRoute != null && currentRoute == item.screen.route
            val iconPainterResId = if (isSelected && item.selectedIconResId != null && item.selectedIconResId != 0) {
                item.selectedIconResId
            } else {
                item.iconResId
            }
            val onClickLambda: () -> Unit = remember(item.screen.route, navController, doubleTapDetector) {
                click@{
                    if (!latestNavigationEnabled) {
                        doubleTapDetector.reset()
                        return@click
                    }

                    val itemRoute = item.screen.route
                    val isAlreadySelected = latestCurrentRoute == itemRoute
                    val isDoubleTapOnCurrent = doubleTapDetector.onClick(itemRoute, isAlreadySelected)

                    if (!isAlreadySelected) {
                        if (!navController.navigateToTopLevelSafely(itemRoute)) {
                            doubleTapDetector.reset()
                        }
                        return@click
                    }

                    if (isDoubleTapOnCurrent) {
                        doubleTapDetector.reset()
                        latestOnRootTabDoubleTap(itemRoute)
                    }
                }
            }
            NavigationRailItem(
                selected = isSelected,
                onClick = onClickLambda,
                enabled = currentRoute != null,
                icon = {
                    Icon(
                        painter = painterResource(id = iconPainterResId),
                        contentDescription = item.label
                    )
                },
                label = { Text(item.label) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                )
            )
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.weight(1f))
    }
}
