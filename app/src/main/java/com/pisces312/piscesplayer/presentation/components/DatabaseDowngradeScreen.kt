package com.pisces312.piscesplayer.presentation.components

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.pisces312.piscesplayer.data.database.DatabaseDowngradeSnapshot
import com.pisces312.piscesplayer.data.database.DatabaseVersionGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Builds the readable copy and arms the session. Must return before the process restarts,
 * because arming is what the next launch reads (see `DatabaseVersionGuard.isTemporarySession`).
 *
 * Returns false when the copy could not be produced or Room refused it — the caller then stays
 * on the downgrade screen, which is the only safe outcome: a restart with a bad copy would die
 * on a background thread instead.
 */
suspend fun enterTemporarySession(context: Context): Boolean = withContext(Dispatchers.IO) {
    if (!DatabaseDowngradeSnapshot.prepare(context)) return@withContext false
    DatabaseVersionGuard.requestTemporarySession(context)
    true
}

/**
 * Restarts the process so `PiscesPlayerApplication.onCreate` re-inspects the database and
 * `AppModule` hands out the copy instead of the in-memory placeholder. The flag is already
 * written by [enterTemporarySession], so the restart is what makes it take effect.
 */
fun restartForTemporarySession(context: Context) {
    Toast.makeText(context, R.string.db_downgrade_browse_starting, Toast.LENGTH_SHORT).show()
    (context as? Activity)?.finishAffinity()
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 400L)
}

/** Drops the copy and the flag, so the next launch shows the downgrade screen again. */
fun exitTemporarySessionAndRestart(context: Context) {    DatabaseVersionGuard.clearTemporarySession(context)
    DatabaseDowngradeSnapshot.deleteSnapshot(context)
    Toast.makeText(context, R.string.db_temp_session_ended, Toast.LENGTH_LONG).show()
    (context as? Activity)?.finishAffinity()
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 700L)
}

/**
 * Reminder that the library on screen is a discarded-on-exit copy.
 *
 * A full-width banner would either cover the bottom navigation (where Settings, the place the
 * user needs for exporting, lives) or the mini player, so this is a small pill plus a dialog
 * with the explanation and the way out.
 */
@Composable
fun TemporarySessionBanner(
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDetails by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.clickable { showDetails = true },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(50),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.db_temp_session_badge),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(stringResource(R.string.db_temp_session_title)) },
            text = { Text(stringResource(R.string.db_temp_session_banner)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDetails = false
                        onExit()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.db_temp_session_exit),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDetails = false }) {
                    Text(stringResource(R.string.db_temp_session_keep))
                }
            }
        )
    }
}

/**
 * Screen shown instead of the app when the database on disk was written by a build with a newer
 * schema (see `DatabaseVersionGuard`).
 *
 * It replaces the whole window rather than floating above the library: nothing below it may
 * boot, because every library screen, ViewModel and worker opens the database and Room would
 * kill the process from a background thread the moment it did.
 *
 * [onBrowseTemporarily] is the escape hatch the user actually needs — it opens a rewritten copy
 * so their playlists, favorites and lyrics are readable and the existing backup export produces a
 * real backup (the original file is never modified, see `DatabaseDowngradeSnapshot`).
 * [onResetDatabase] exists so a device is never permanently bricked, but it deletes the user's
 * data, so it sits behind an explicit second confirmation.
 */
@Composable
fun DatabaseDowngradeScreen(
    onDiskVersion: Int,
    appVersion: Int,
    isPreparingTemporarySession: Boolean,
    onBrowseTemporarily: () -> Unit,
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
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.db_downgrade_browse_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = onBrowseTemporarily,
                enabled = !isPreparingTemporarySession,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isPreparingTemporarySession) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = stringResource(
                        if (isPreparingTemporarySession) {
                            R.string.db_downgrade_browse_preparing
                        } else {
                            R.string.db_downgrade_browse
                        }
                    )
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onQuit, enabled = !isPreparingTemporarySession) {
                    Text(stringResource(R.string.db_downgrade_quit))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { showResetConfirm = true },
                    enabled = !isPreparingTemporarySession
                ) {
                    Text(
                        text = stringResource(R.string.db_downgrade_reset),
                        color = MaterialTheme.colorScheme.error
                    )
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
