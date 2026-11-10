package com.pisces312.piscesplayer.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.ai.AiHandler
import com.pisces312.piscesplayer.data.ai.AiLyricsTranslationCache
import com.pisces312.piscesplayer.data.ai.provider.AiErrorKind
import com.pisces312.piscesplayer.data.ai.provider.AiProvider
import com.pisces312.piscesplayer.data.ai.provider.AiProviderException
import com.pisces312.piscesplayer.data.ai.serendipity.City
import com.pisces312.piscesplayer.data.ai.serendipity.CityCatalog
import com.pisces312.piscesplayer.data.ai.serendipity.SerendipityWeatherSource
import com.pisces312.piscesplayer.data.database.AiCacheDao
import com.pisces312.piscesplayer.data.database.AiUsageDao
import com.pisces312.piscesplayer.data.preferences.AiPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AiSettingsViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AiPreferencesRepository,
    private val handler: AiHandler,
    private val cacheDao: AiCacheDao,
    private val translationCache: AiLyricsTranslationCache,
    private val cityCatalog: CityCatalog,
    usageDao: AiUsageDao
) : ViewModel() {

    data class AiSettingsUiState(
        val provider: AiProvider = AiProvider.MIMO,
        val apiKey: String = "",
        val model: String = "",
        val baseUrl: String = "",
        /** Endpoint actually used, derived from the key for providers with a fixed url. */
        val endpoint: String = "",
        val thinkingEnabled: Boolean = false,
        val availableModels: List<String> = emptyList(),
        val modelsLoading: Boolean = false,
        val testing: Boolean = false,
        /** Where Serendipity reads the weather from. */
        val weatherSource: SerendipityWeatherSource = SerendipityWeatherSource.DEFAULT,
        /** City Serendipity looks the weather up for; blank means "nothing picked yet". */
        val city: String = ""
    )

    data class UsageStats(val promptTokens: Int = 0, val outputTokens: Int = 0, val thoughtTokens: Int = 0)

    data class StatusMessage(val text: String, val isError: Boolean)

    private val availableModels = MutableStateFlow<List<String>>(emptyList())
    private val modelsLoading = MutableStateFlow(false)
    private val testing = MutableStateFlow(false)

    /** Raw text of the city picker's search box. */
    private val cityQuery = MutableStateFlow("")

    private val _status = MutableStateFlow<StatusMessage?>(null)
    val status: StateFlow<StatusMessage?> = _status

    private val providerState: StateFlow<AiSettingsUiState> =
            preferences.providerFlow
                    .flatMapLatest { provider ->
                        combine(
                                preferences.getApiKey(provider),
                                preferences.getModel(provider),
                                preferences.getBaseUrl(provider),
                                preferences.getThinkingEnabled(provider)
                        ) { apiKey, model, baseUrl, thinking ->
                            AiSettingsUiState(
                                    provider = provider,
                                    apiKey = apiKey,
                                    model = model,
                                    baseUrl = baseUrl,
                                    endpoint = baseUrl.ifBlank { provider.resolveBaseUrl(apiKey) },
                                    thinkingEnabled = thinking
                            )
                        }
                    }
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettingsUiState())

    val uiState: StateFlow<AiSettingsUiState> =
            combine(
                            providerState,
                            availableModels,
                            modelsLoading,
                            testing,
                            // Global rather than per provider: both describe the user, not the model.
                            combine(
                                    preferences.getSerendipityCity(),
                                    preferences.getSerendipityWeatherSource()
                            ) { city, source -> city to source }
                    ) { state, models, loading, isTesting, serendipity ->
                        state.copy(
                                availableModels = models,
                                modelsLoading = loading,
                                testing = isTesting,
                                city = serendipity.first,
                                weatherSource = serendipity.second
                        )
                    }
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettingsUiState())

    /**
     * Rows for the city picker, filtered by [setCityQuery].
     *
     * Scanning the bundled list is cheap but not free, so it runs off the main thread. An empty
     * query returns the head of the list, which the bundled data is sorted to make the notable
     * cities — capitals and provincial seats, home country first.
     */
    val cityResults: StateFlow<List<City>> =
            cityQuery.map { cityCatalog.search(it) }
                    .flowOn(Dispatchers.Default)
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Reading the bundled list takes long enough to be visible when the picker opens, so it is
        // paid once here instead — while the user is still reading the rest of the screen.
        viewModelScope.launch(Dispatchers.Default) { runCatching { cityCatalog.search("") } }
    }

    val usage: StateFlow<UsageStats> =
            combine(
                            usageDao.getTotalPromptTokens(),
                            usageDao.getTotalOutputTokens(),
                            usageDao.getTotalThoughtTokens()
                    ) { prompt, output, thought ->
                                UsageStats(prompt ?: 0, output ?: 0, thought ?: 0)
                            }
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageStats())

    fun setProvider(provider: AiProvider) {
        viewModelScope.launch {
            preferences.setProvider(provider)
            availableModels.value = emptyList()
        }
    }

    fun setApiKey(value: String) {
        viewModelScope.launch { preferences.setApiKey(providerState.value.provider, value) }
    }

    fun setModel(value: String) {
        viewModelScope.launch { preferences.setModel(providerState.value.provider, value) }
    }

    fun setBaseUrl(value: String) {
        viewModelScope.launch { preferences.setBaseUrl(providerState.value.provider, value) }
    }

    fun setThinkingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setThinkingEnabled(providerState.value.provider, enabled)
        }
    }

    /** Where Serendipity reads the weather from; see [SerendipityWeatherSource]. */
    fun setWeatherSource(source: SerendipityWeatherSource) {
        viewModelScope.launch { preferences.setSerendipityWeatherSource(source) }
    }

    /**
     * City used for Serendipity's weather lookup.
     *
     * Picking one from the bundled list is what keeps the feature usable without granting the
     * location permission at all, so this is a name out of that list rather than free text.
     */
    fun setSerendipityCity(value: String) {
        viewModelScope.launch { preferences.setSerendipityCity(value) }
    }

    /** Filters [cityResults]. */
    fun setCityQuery(value: String) {
        cityQuery.value = value
    }

    /** Loads the provider's `/models` list so the user can pick instead of typing an id. */
    fun fetchModels() {
        viewModelScope.launch {
            modelsLoading.value = true
            val models = runCatching { handler.fetchModels() }
            modelsLoading.value = false
            models.onSuccess { list ->
                val usable = list.filter(::isChatModel)
                availableModels.value = usable
                _status.value =
                        StatusMessage(
                                context.getString(R.string.ai_status_models_loaded, usable.size),
                                false
                        )
            }
            models.onFailure { _status.value = StatusMessage(errorText(it), true) }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            testing.value = true
            val result = runCatching { handler.testConnection() }
            testing.value = false
            _status.value =
                    result.fold(
                            onSuccess = {
                                StatusMessage(
                                        context.getString(R.string.ai_status_test_ok),
                                        false
                                )
                            },
                            onFailure = { StatusMessage(errorText(it), true) }
                    )
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            cacheDao.clearAllCache()
            translationCache.clear()
            _status.value =
                    StatusMessage(context.getString(R.string.ai_status_cache_cleared), false)
        }
    }

    fun consumeStatus() {
        _status.value = null
    }

    private fun errorText(throwable: Throwable): String {
        val kind = (throwable as? AiProviderException)?.kind
        val resId =
                when (kind) {
                    AiErrorKind.UNAUTHORIZED -> R.string.ai_error_unauthorized
                    AiErrorKind.QUOTA_EXCEEDED -> R.string.ai_error_quota
                    AiErrorKind.MODEL_NOT_FOUND -> R.string.ai_error_model_not_found
                    AiErrorKind.RATE_LIMITED -> R.string.ai_error_rate_limited
                    AiErrorKind.SERVER -> R.string.ai_error_server
                    AiErrorKind.NETWORK -> R.string.ai_error_network
                    AiErrorKind.RESPONSE_PARSE -> R.string.ai_error_parse
                    else -> R.string.ai_error_unknown
                }
        val text = context.getString(resId)
        // The provider's own wording is the only way to tell a wrong key from a wrong url.
        val detail = (throwable as? AiProviderException)?.message
        return if (detail.isNullOrBlank()) text else "$text\n$detail"
    }

    companion object {
        /** Drops speech/embedding ids that cannot answer a chat request. */
        private val NON_CHAT_TOKENS = listOf("tts", "asr", "voice", "embed", "rerank")

        private fun isChatModel(id: String): Boolean =
                NON_CHAT_TOKENS.none { id.contains(it, ignoreCase = true) }
    }
}
