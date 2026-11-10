package com.pisces312.piscesplayer.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.ai.provider.AiProvider
import com.pisces312.piscesplayer.data.ai.serendipity.City
import com.pisces312.piscesplayer.data.ai.serendipity.SerendipityWeatherSource
import com.pisces312.piscesplayer.presentation.viewmodel.AiSettingsViewModel
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsSection(
    viewModel: AiSettingsViewModel = hiltViewModel(),
    onOpenRequestLog: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val cityResults by viewModel.cityResults.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(status) {
        status?.let {
            Toast.makeText(context, it.text, Toast.LENGTH_SHORT).show()
            viewModel.consumeStatus()
        }
    }

    Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(stringResource(R.string.ai_section_provider))
            ProviderDropdown(current = uiState.provider, onSelect = viewModel::setProvider)
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(stringResource(R.string.ai_section_credentials))
            OutlinedTextField(
                    value = uiState.apiKey,
                    onValueChange = viewModel::setApiKey,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ai_api_key_label)) },
                    placeholder = { Text(stringResource(R.string.ai_api_key_placeholder)) },
                    singleLine = true,
                    visualTransformation =
                            if (uiState.apiKey.isBlank()) VisualTransformation.None
                            else PasswordVisualTransformation(),
                    supportingText =
                            if (!uiState.provider.hasConfigurableUrl && uiState.endpoint.isNotBlank()) {
                                { Text(stringResource(R.string.ai_endpoint_resolved, uiState.endpoint)) }
                            } else null
            )
            if (uiState.provider.hasConfigurableUrl) {
                OutlinedTextField(
                        value = uiState.baseUrl,
                        onValueChange = viewModel::setBaseUrl,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ai_base_url_label)) },
                        placeholder = { Text(stringResource(R.string.ai_base_url_placeholder)) },
                        singleLine = true
                )
            }
            val modelsEmpty = uiState.availableModels.isEmpty()
            Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
            ) {
                if (modelsEmpty) {
                    OutlinedTextField(
                            value = uiState.model,
                            onValueChange = viewModel::setModel,
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.ai_model_label)) },
                            placeholder = { Text(stringResource(R.string.ai_model_placeholder)) },
                            singleLine = true
                    )
                } else {
                    ModelDropdown(
                            model = uiState.model,
                            models = uiState.availableModels,
                            onSelect = viewModel::setModel,
                            modifier = Modifier.weight(1f)
                    )
                }
                FilledTonalButton(
                        onClick = viewModel::fetchModels,
                        enabled = !uiState.modelsLoading,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxHeight()
                ) { Text(stringResource(R.string.ai_model_fetch)) }
            }
            if (modelsEmpty) {
                Text(
                        text = stringResource(R.string.ai_model_no_list),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
                )
            }
            SwitchSettingItem(
                    title = stringResource(R.string.ai_thinking_title),
                    subtitle = stringResource(R.string.ai_thinking_subtitle),
                    checked = uiState.thinkingEnabled,
                    onCheckedChange = viewModel::setThinkingEnabled,
                    enabled = uiState.provider.supportsThinkingParam,
                    leadingIcon = {
                        Icon(
                                Icons.Rounded.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                        )
                    }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(stringResource(R.string.ai_section_serendipity))
            WeatherSourceDropdown(
                    current = uiState.weatherSource,
                    onSelect = viewModel::setWeatherSource
            )
            if (uiState.weatherSource == SerendipityWeatherSource.SPECIFIC_CITY) {
                CityField(
                        city = uiState.city,
                        results = cityResults,
                        onQueryChange = viewModel::setCityQuery,
                        onSelect = viewModel::setSerendipityCity
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(stringResource(R.string.ai_section_actions))
            ActionRow(
                    label = stringResource(R.string.ai_action_test),
                    enabled = !uiState.testing,
                    onClick = viewModel::testConnection
            )
            ActionRow(
                    label = stringResource(R.string.ai_action_clear_cache),
                    enabled = true,
                    onClick = viewModel::clearCache
            )
            ActionRow(
                    label = stringResource(R.string.ai_action_request_log),
                    enabled = true,
                    onClick = onOpenRequestLog
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.ai_section_usage))
            val total = usage.promptTokens + usage.outputTokens + usage.thoughtTokens
            Text(
                    text =
                            if (total == 0) stringResource(R.string.ai_usage_none)
                            else
                                    stringResource(
                                            R.string.ai_usage_summary,
                                            usage.promptTokens,
                                            usage.outputTokens,
                                            usage.thoughtTokens
                                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDropdown(current: AiProvider, onSelect: (AiProvider) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
                value = current.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.ai_provider_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AiProvider.entries.forEach { provider ->
                DropdownMenuItem(
                        text = { Text(provider.displayName) },
                        onClick = {
                            onSelect(provider)
                            expanded = false
                        }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    model: String,
    models: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = modifier
    ) {
        OutlinedTextField(
                value = model,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.ai_model_label)) },
                placeholder = { Text(stringResource(R.string.ai_model_pick)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { id ->
                DropdownMenuItem(
                        text = { Text(id) },
                        onClick = {
                            onSelect(id)
                            expanded = false
                        }
                )
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
    ) { Text(label) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeatherSourceDropdown(
    current: SerendipityWeatherSource,
    onSelect: (SerendipityWeatherSource) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
                value = stringResource(weatherSourceLabelRes(current)),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.ai_serendipity_weather_source_label)) },
                supportingText = { Text(stringResource(weatherSourceHintRes(current))) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SerendipityWeatherSource.entries.forEach { source ->
                DropdownMenuItem(
                        text = { Text(stringResource(weatherSourceLabelRes(source))) },
                        onClick = {
                            onSelect(source)
                            expanded = false
                        }
                )
            }
        }
    }
}

/**
 * The city picker's entry point: a field that only ever opens the dialog.
 *
 * A read-only text field swallows taps, so an empty box is laid over it to catch the click.
 */
@Composable
private fun CityField(
    city: String,
    results: List<City>,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
                value = city.ifBlank { stringResource(R.string.ai_serendipity_city_none) },
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.ai_serendipity_city_label)) },
                supportingText = {
                    Text(
                            stringResource(
                                    if (city.isBlank()) R.string.ai_serendipity_city_hint_empty
                                    else R.string.ai_serendipity_city_hint
                            )
                    )
                },
                trailingIcon = {
                    Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                    )
                },
                modifier = Modifier.fillMaxWidth()
        )
        Box(modifier = Modifier.matchParentSize().clickable { picking = true })
    }
    if (picking) {
        CityPickerDialog(
                results = results,
                onQueryChange = onQueryChange,
                onSelect = { name ->
                    onSelect(name)
                    picking = false
                },
                onDismiss = {
                    // Dropping the query keeps the next open from starting on stale results.
                    onQueryChange("")
                    picking = false
                }
        )
    }
}

@Composable
private fun CityPickerDialog(
    results: List<City>,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.ai_serendipity_city_pick)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                            value = query,
                            onValueChange = {
                                query = it
                                onQueryChange(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(stringResource(R.string.ai_serendipity_city_placeholder))
                            },
                            singleLine = true
                    )
                    if (results.isEmpty() && query.isNotBlank()) {
                        Text(
                                text = stringResource(R.string.ai_serendipity_city_no_match, query),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(results) { city ->
                                Text(
                                        text = city.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier =
                                                Modifier.fillMaxWidth()
                                                        // The label, not the bare name: China
                                                        // has five districts called 东区, and only
                                                        // the label says which city it belongs to.
                                                        .clickable { onSelect(city.label) }
                                                        .padding(vertical = 12.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
    )
}

private fun weatherSourceLabelRes(source: SerendipityWeatherSource): Int =
        when (source) {
            SerendipityWeatherSource.DEVICE_LOCATION -> R.string.ai_serendipity_source_device
            SerendipityWeatherSource.SPECIFIC_CITY -> R.string.ai_serendipity_source_city
            SerendipityWeatherSource.OFF -> R.string.ai_serendipity_source_off
        }

private fun weatherSourceHintRes(source: SerendipityWeatherSource): Int =
        when (source) {
            SerendipityWeatherSource.DEVICE_LOCATION -> R.string.ai_serendipity_source_device_hint
            SerendipityWeatherSource.SPECIFIC_CITY -> R.string.ai_serendipity_source_city_hint
            SerendipityWeatherSource.OFF -> R.string.ai_serendipity_source_off_hint
        }
