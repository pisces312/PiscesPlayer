/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: request log browser for this codebase.
 */
package com.pisces312.piscesplayer.presentation.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.ai.AiRequestLog
import com.pisces312.piscesplayer.presentation.viewmodel.AiRequestLogViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → AI → Request log. Lists the most recent AI requests and shows the full
 * payload (prompts, response, error) of the selected one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiRequestLogScreen(
    navController: NavController,
    viewModel: AiRequestLogViewModel = hiltViewModel()
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var askClearAll by rememberSaveable { mutableStateOf(false) }
    val selected = logs.firstOrNull { it.id == selectedId }

    BackHandler(enabled = selected != null) { selectedId = null }

    Scaffold(
            topBar = {
                TopAppBar(
                        title = {
                            Text(
                                    stringResource(
                                            if (selected != null) R.string.ai_request_log_detail_title
                                            else R.string.ai_request_log_title
                                    )
                            )
                        },
                        navigationIcon = {
                            IconButton(
                                    onClick = {
                                        if (selected != null) selectedId = null
                                        else navController.navigateUp()
                                    }
                            ) {
                                Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.auth_cd_back)
                                )
                            }
                        },
                        actions = {
                            if (selected == null && logs.isNotEmpty()) {
                                IconButton(onClick = { askClearAll = true }) {
                                    Icon(
                                            Icons.Rounded.DeleteSweep,
                                            contentDescription =
                                                    stringResource(R.string.ai_request_log_clear_all)
                                    )
                                }
                            }
                        }
                )
            }
    ) { padding ->
        when {
            isLoading -> {}
            logs.isEmpty() -> {
                Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                            text = stringResource(R.string.ai_request_log_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            selected != null ->
                    AiRequestLogDetail(
                            log = selected,
                            viewModel = viewModel,
                            modifier = Modifier.padding(padding)
                    )
            else -> {
                LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding =
                                PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(logs, key = { it.id }) { log ->
                        AiRequestLogRow(log = log, onClick = { selectedId = log.id })
                    }
                }
            }
        }
    }

    if (askClearAll) {
        AlertDialog(
                onDismissRequest = { askClearAll = false },
                title = { Text(stringResource(R.string.ai_request_log_clear_all)) },
                text = {
                    Text(stringResource(R.string.ai_request_log_clear_all_message, logs.size))
                },
                confirmButton = {
                    TextButton(
                            onClick = {
                                viewModel.clearAll()
                                askClearAll = false
                            }
                    ) { Text(stringResource(R.string.ai_request_log_clear_all)) }
                },
                dismissButton = {
                    TextButton(onClick = { askClearAll = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
        )
    }
}

@Composable
private fun AiRequestLogRow(log: AiRequestLog, onClick: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp
    ) {
        Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                        text = timeFormat.format(Date(log.timestamp)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusChip(status = log.status)
            }
            Text(
                    text =
                            if (log.model.isBlank()) log.provider
                            else "${log.provider} · ${log.model}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                            text = log.promptType,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Text(
                        text = formatMeta(log),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (container, content) =
            when (status) {
                AiRequestLog.STATUS_FAILED ->
                        MaterialTheme.colorScheme.errorContainer to
                                MaterialTheme.colorScheme.onErrorContainer
                AiRequestLog.STATUS_FROM_CACHE ->
                        MaterialTheme.colorScheme.tertiaryContainer to
                                MaterialTheme.colorScheme.onTertiaryContainer
                else ->
                        MaterialTheme.colorScheme.primaryContainer to
                                MaterialTheme.colorScheme.onPrimaryContainer
            }
    Surface(color = container, shape = RoundedCornerShape(8.dp)) {
        Text(
                text =
                        stringResource(
                                when (status) {
                                    AiRequestLog.STATUS_FAILED -> R.string.ai_request_log_status_failed
                                    AiRequestLog.STATUS_FROM_CACHE ->
                                            R.string.ai_request_log_status_cached
                                    else -> R.string.ai_request_log_status_success
                                }
                        ),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = content
        )
    }
}

@Composable
private fun AiRequestLogDetail(
    log: AiRequestLog,
    viewModel: AiRequestLogViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sections: List<Pair<String, String>> = remember(log.id) { buildSections(log) }
    val fullText: String =
            remember(log.id) {
                sections.joinToString(separator = "\n\n") { section ->
                    "── ${section.first} ──\n${section.second}"
                }
            }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = { copyText(context, fullText) }) {
                Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ai_request_log_copy_all))
            }
            if (!log.responseText.isNullOrBlank()) {
                TextButton(onClick = { copyText(context, log.responseText.orEmpty()) }) {
                    Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_request_log_copy_response))
                }
            }
            TextButton(
                    onClick = {
                        val file = viewModel.fileFor(log.id)
                        if (!file.exists()) return@TextButton
                        val uri =
                                FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.provider",
                                        file
                                )
                        val sendIntent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "AI request log ${log.id}")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                        context.startActivity(
                                Intent.createChooser(sendIntent, null)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                    }
            ) {
                Icon(Icons.Rounded.Share, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ai_request_log_share))
            }
        }

        HorizontalDivider()

        Text(
                text = fullText,
                modifier =
                        Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                style =
                        MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                        ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("AI request log", text))
}

private fun buildSections(log: AiRequestLog): List<Pair<String, String>> {
    val sections = mutableListOf<Pair<String, String>>()
    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    val meta =
            buildString {
                append("time: ").append(timeFormat.format(Date(log.timestamp))).append('\n')
                append("status: ").append(log.status).append('\n')
                append("type: ").append(log.promptType).append('\n')
                append("provider: ").append(log.provider)
                if (log.model.isNotBlank()) append(" · model: ").append(log.model)
                append('\n')
                log.endpoint?.let { append("endpoint: ").append(it).append('\n') }
                append("duration: ")
                        .append(String.format(Locale.US, "%.1f s", log.durationMs / 1000f))
                        .append('\n')
                append("tokens: in=")
                        .append(log.promptTokens)
                        .append(" out=")
                        .append(log.outputTokens)
                        .append(" thought=")
                        .append(log.thoughtTokens)
                if (log.truncated) append("\n(truncated)")
            }
    sections += "META" to meta

    log.systemPrompt?.let { sections += "SYSTEM PROMPT" to it }
    log.userPrompt?.let { sections += "USER PROMPT" to it }
    log.responseText?.let { sections += "RESPONSE" to it }
    log.errorMessage?.let { sections += "ERROR" to it }
    return sections
}

private fun formatMeta(log: AiRequestLog): String {
    val duration = String.format(Locale.US, "%.1fs", log.durationMs / 1000f)
    val size = (log.responseText?.length ?: 0) + (log.userPrompt?.length ?: 0)
    return "$duration · $size chars"
}
