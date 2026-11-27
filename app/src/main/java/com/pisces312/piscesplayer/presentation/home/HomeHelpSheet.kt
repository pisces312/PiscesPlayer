package com.pisces312.piscesplayer.presentation.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Recommend
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R

/**
 * 「快速上手」bottom sheet content (plan v1.3.5 decision 25): four short tips that
 * teach the customizable home grid and the two rich cards, plus a way into the
 * changelog for "what's new". Rendered inside a ModalBottomSheet by HomeScreen.
 */
@Composable
fun HomeHelpSheet(onViewChangelog: () -> Unit, onSupport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.home_help_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(20.dp))
        HelpTip(
            icon = Icons.Rounded.Edit,
            titleRes = R.string.home_help_tip_grid_title,
            bodyRes = R.string.home_help_tip_grid_body,
        )
        HelpTip(
            icon = Icons.Rounded.Add,
            titleRes = R.string.home_help_tip_add_title,
            bodyRes = R.string.home_help_tip_add_body,
        )
        HelpTip(
            icon = Icons.Rounded.AutoAwesome,
            titleRes = R.string.home_help_tip_ai_title,
            bodyRes = R.string.home_help_tip_ai_body,
        )
        HelpTip(
            icon = Icons.Rounded.Recommend,
            titleRes = R.string.home_help_tip_daily_title,
            bodyRes = R.string.home_help_tip_daily_body,
        )
        Row(modifier = Modifier.align(Alignment.CenterHorizontally)) {
            TextButton(onClick = onViewChangelog) {
                Text(stringResource(R.string.home_help_view_changelog))
            }
            TextButton(onClick = onSupport) {
                Text(stringResource(R.string.home_help_support))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HelpTip(icon: ImageVector, @StringRes titleRes: Int, @StringRes bodyRes: Int) {
    Row(modifier = Modifier.padding(bottom = 18.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
