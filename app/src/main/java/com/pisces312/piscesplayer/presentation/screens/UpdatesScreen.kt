package com.pisces312.piscesplayer.presentation.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.update.UpdateError
import com.pisces312.piscesplayer.data.update.UpdateStatus
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight
import com.pisces312.piscesplayer.presentation.viewmodel.UpdatesUiState
import com.pisces312.piscesplayer.presentation.viewmodel.UpdatesViewModel
import timber.log.Timber

@Composable
fun UpdatesScreen(
    onBack: () -> Unit,
    viewModel: UpdatesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.updates_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.auth_cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            )
        }
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = scaffoldPadding.calculateTopPadding() + 12.dp,
                end = 16.dp,
                bottom = MiniPlayerHeight + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "status") {
                UpdateStatusCard(
                    state = state,
                    onCheck = viewModel::checkNow,
                    onInstall = viewModel::downloadAndInstall,
                    onCancel = viewModel::cancel,
                    onDismiss = viewModel::dismissError,
                    onAllowInstalls = {
                        context.startActivitySafely(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                        )
                    },
                    onOpenPrompt = { intent -> context.startActivitySafely(Intent(intent)) },
                    onOpenUrl = { url -> context.openUrl(url) },
                )
            }
            item(key = "settings") {
                ExpressiveSettingsGroup {
                    SwitchSettingItem(
                        title = stringResource(R.string.updates_auto_check_title),
                        subtitle = stringResource(R.string.updates_auto_check_subtitle),
                        checked = state.autoCheckEnabled,
                        onCheckedChange = viewModel::setAutoCheck,
                        leadingIcon = {
                            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateStatusCard(
    state: UpdatesUiState,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onAllowInstalls: () -> Unit,
    onOpenPrompt: (Intent) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(
                    Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = stringResource(R.string.updates_installed_version, state.installedVersion),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = statusMessage(state),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            when (val status = state.status) {
                UpdateStatus.Idle -> PrimaryAction(stringResource(R.string.updates_action_check), onCheck)
                UpdateStatus.Checking -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
                is UpdateStatus.UpToDate -> SecondaryAction(stringResource(R.string.updates_action_check_again), onCheck)
                is UpdateStatus.Available -> {
                    if (status.requiresReinstall) {
                        PrimaryAction(stringResource(R.string.updates_action_open_release)) {
                            onOpenUrl(status.release.pageUrl)
                        }
                    } else {
                        PrimaryAction(stringResource(R.string.updates_action_install), onInstall)
                    }
                    SecondaryAction(stringResource(R.string.updates_action_release_notes)) {
                        onOpenUrl(status.release.pageUrl)
                    }
                }
                is UpdateStatus.Downloading -> {
                    if (status.progress != null) {
                        LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    SecondaryAction(stringResource(R.string.updates_action_cancel), onCancel)
                }
                is UpdateStatus.AwaitingInstallPermission -> {
                    PrimaryAction(stringResource(R.string.updates_action_allow_installs), onAllowInstalls)
                    SecondaryAction(stringResource(R.string.updates_action_cancel), onCancel)
                }
                // The committed session is decided in the system prompt (its Cancel aborts it).
                is UpdateStatus.AwaitingConfirmation ->
                    PrimaryAction(stringResource(R.string.updates_action_open_prompt)) { onOpenPrompt(status.confirmIntent) }
                is UpdateStatus.Installing -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                is UpdateStatus.Failed -> {
                    PrimaryAction(stringResource(R.string.updates_action_check_again), onCheck)
                    SecondaryAction(stringResource(R.string.updates_action_dismiss), onDismiss)
                }
            }
        }
    }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun SecondaryAction(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun statusMessage(state: UpdatesUiState): String = when (val status = state.status) {
    UpdateStatus.Idle -> stringResource(R.string.updates_status_idle)
    UpdateStatus.Checking -> stringResource(R.string.updates_status_checking)
    is UpdateStatus.UpToDate -> stringResource(R.string.updates_status_up_to_date)
    is UpdateStatus.Available -> when {
        !status.requiresReinstall -> stringResource(R.string.updates_status_available, status.release.versionName)
        else -> stringResource(R.string.updates_status_reinstall_signature, status.release.versionName)
    }
    is UpdateStatus.Downloading -> status.progress
        ?.let { stringResource(R.string.updates_status_downloading_progress, status.release.versionName, (it * 100).toInt()) }
        ?: stringResource(R.string.updates_status_downloading, status.release.versionName)
    is UpdateStatus.AwaitingInstallPermission -> stringResource(R.string.updates_status_permission, status.release.versionName)
    is UpdateStatus.AwaitingConfirmation -> stringResource(R.string.updates_status_confirm, status.release.versionName)
    is UpdateStatus.Installing -> stringResource(R.string.updates_status_installing, status.release.versionName)
    is UpdateStatus.Failed -> stringResource(
        when (status.error) {
            UpdateError.CHECK_FAILED -> R.string.updates_error_check
            UpdateError.NOTHING_PUBLISHED -> R.string.updates_error_nothing_published
            UpdateError.DOWNLOAD_FAILED -> R.string.updates_error_download
            UpdateError.INVALID_APK -> R.string.updates_error_invalid_apk
            UpdateError.NOT_NEWER -> R.string.updates_error_not_newer
            UpdateError.INSTALL_FAILED -> R.string.updates_error_install
        }
    )
}

private fun Context.openUrl(url: String) = startActivitySafely(Intent(Intent.ACTION_VIEW, url.toUri()))

private fun Context.startActivitySafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (error: ActivityNotFoundException) {
        Timber.w(error, "No activity for $intent")
    }
}
