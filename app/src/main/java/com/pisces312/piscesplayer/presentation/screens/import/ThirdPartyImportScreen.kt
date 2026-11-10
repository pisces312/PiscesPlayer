/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: import source picker written for this codebase.
 */
package com.pisces312.piscesplayer.presentation.screens.import

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.presentation.viewmodel.ImportViewModel

/**
 * Source picker for third-party imports: Settings → Backup & Restore → Third-party import.
 *
 * Only Poweramp is implemented today; the list is the extension point for other
 * [com.pisces312.piscesplayer.data.importer.ImportSource] implementations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThirdPartyImportScreen(
    navController: NavController,
    viewModel: ImportViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val filePicker =
            rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) {
                    uri ->
                uri?.let { viewModel.onFileSelected(it) }
            }

    // Leaving the screen drops a half-finished import; its coroutine is cancelled with the VM.
    DisposableEffect(Unit) { onDispose { viewModel.reset() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.auth_cd_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.import_source_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                ImportSourceCard(
                    title = "Poweramp",
                    subtitle = stringResource(R.string.import_source_poweramp_subtitle),
                    enabled = state.step == ImportViewModel.Step.IDLE,
                    onClick = { filePicker.launch(arrayOf("*/*")) }
                )
            }
            item {
                Text(
                    text = stringResource(R.string.import_source_more_coming),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    // Import wizard: parsing → preview → options → importing → result.
    PowerampImportFlow(viewModel = viewModel, onDismiss = { viewModel.reset() })
}

@Composable
private fun ImportSourceCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.FileUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
