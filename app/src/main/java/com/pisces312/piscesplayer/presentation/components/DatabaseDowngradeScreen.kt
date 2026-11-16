package com.pisces312.piscesplayer.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.database.DatabaseVersionGuard
import kotlin.system.exitProcess

/**
 * Deletes the database that belongs to a newer build and shuts the process down.
 *
 * The process has to die: [DatabaseVersionGuard] caches its verdict for the process lifetime
 * and Hilt has already handed out an in-memory database, so merely recreating the activity
 * would keep showing this same screen. Restarting also gives the app a clean Room instance on
 * the freshly created (empty) file.
 */
fun clearBlockedDatabaseAndExit(context: Context) {
    DatabaseVersionGuard.deleteDatabase(context)
    Toast.makeText(context, R.string.db_downgrade_reset_done, Toast.LENGTH_LONG).show()
    (context as? Activity)?.finishAffinity()
    // Let the toast reach the window manager before the process disappears.
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 700L)
}

/**
 * Terminal screen shown instead of the app when the database on disk was written by a build
 * with a newer schema (see `DatabaseVersionGuard`).
 *
 * It replaces the whole window rather than floating above the library: nothing below it may
 * boot, because every library screen, ViewModel and worker opens the database and Room would
 * kill the process from a background thread the moment it did.
 *
 * The only non-destructive exit is [onQuit] (install a build that understands the newer
 * schema). [onResetDatabase] exists so a device is never permanently bricked, but it deletes
 * the user's data, so it sits behind an explicit second confirmation.
 */
@Composable
fun DatabaseDowngradeScreen(
    onDiskVersion: Int,
    appVersion: Int,
    onQuit: () -> Unit,
    onResetDatabase: () -> Unit,
) {
    var showResetConfirm by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.db_downgrade_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    R.string.db_downgrade_body,
                    onDiskVersion,
                    appVersion
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.db_downgrade_hint, onDiskVersion),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onQuit) {
                    Text(stringResource(R.string.db_downgrade_quit))
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { showResetConfirm = true }) {
                    Text(stringResource(R.string.db_downgrade_reset))
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.db_downgrade_confirm_title)) },
            text = { Text(stringResource(R.string.db_downgrade_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        onResetDatabase()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.db_downgrade_confirm_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.db_downgrade_cancel))
                }
            }
        )
    }
}
