package com.pisces312.piscesplayer.data.ai

import com.pisces312.piscesplayer.data.ai.provider.AiProvider
import com.pisces312.piscesplayer.data.database.AiCacheDao
import com.pisces312.piscesplayer.data.database.AiCacheEntity
import com.pisces312.piscesplayer.data.database.AiUsageDao
import com.pisces312.piscesplayer.data.database.AiUsageEntity
import com.pisces312.piscesplayer.data.preferences.AiPreferencesRepository
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Single entry point for AI requests: resolves the active provider, applies the response cache,
 * records token usage, and normalises failures.
 */
@Singleton
class AiHandler
@Inject
constructor(
    private val client: OpenAiCompatibleClient,
    private val preferences: AiPreferencesRepository,
    private val cacheDao: AiCacheDao,
    private val usageDao: AiUsageDao,
    private val requestLogStore: AiRequestLogStore
) {

    /** Model ids offered by the active provider, or null when the call failed. */
    suspend fun fetchModels(): List<String> {
        val (provider, apiKey, baseUrl) = credentials()
        return client.listModels(baseUrl, apiKey)
    }

    /** Sends a minimal request to verify key / url / model are usable. */
    suspend fun testConnection() {
        val (provider, apiKey, baseUrl) = credentials()
        val model = preferences.getModel(provider).first()
        client.chat(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                systemPrompt = "You are a connectivity probe.",
                userPrompt = "Reply with the single word: OK",
                thinkingEnabled = null
        )
    }

    /**
     * Runs [request] against the active provider and returns the raw model response.
     *
     * Results are cached per (model, prompt) so repeating a request costs nothing. Serendipity
     * passes `useCache = false`: its prompts are random by design, so a cache entry could only be
     * written and never read, growing the table for no benefit.
     *
     * [promptType] only labels the usage row and the request log, which is how the settings screen
     * can tell an ordinary mix from one that also paid for a rephrased prompt.
     *
     * [listener] receives thinking / answer deltas while the request is streaming. A cache hit is
     * answered without contacting the provider, so it reports no progress at all.
     */
    suspend fun generate(
        request: String,
        librarySample: String,
        promptType: String = PROMPT_TYPE_PLAYLIST,
        useCache: Boolean = true,
        listener: AiProgressListener? = null
    ): String {
        val (provider, apiKey, baseUrl) = credentials()
        val model = preferences.getModel(provider).first()
        val thinking =
                if (provider.supportsThinkingParam) preferences.getThinkingEnabled(provider).first()
                else null

        val userPrompt = AiSystemPromptEngine.userPrompt(request, librarySample)
        val hash = sha256("$model|$userPrompt")

        if (useCache) {
            val cached = cacheDao.getCache(hash)
            if (cached != null &&
                    System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MILLIS) {
                requestLogStore.write(
                    AiRequestLog(
                        timestamp = System.currentTimeMillis(),
                        status = AiRequestLog.STATUS_FROM_CACHE,
                        promptType = promptType,
                        provider = provider.displayName,
                        model = model,
                        endpoint = AiRequestLogStore.redactUrl("${baseUrl.trimEnd('/')}/chat/completions"),
                        systemPrompt = AiSystemPromptEngine.systemPrompt(),
                        userPrompt = userPrompt,
                        responseText = cached.responseJson
                    )
                )
                return cached.responseJson
            }
        }

        val requestStart = System.currentTimeMillis()
        val result =
            try {
                client.chat(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        systemPrompt = AiSystemPromptEngine.systemPrompt(),
                        userPrompt = userPrompt,
                        thinkingEnabled = thinking,
                        listener = listener
                )
            } catch (t: Throwable) {
                // Closing the sheet cancels the request: that is not a failure worth logging.
                if (t is CancellationException) throw t
                requestLogStore.write(
                    AiRequestLog(
                        timestamp = System.currentTimeMillis(),
                        status = AiRequestLog.STATUS_FAILED,
                        promptType = promptType,
                        provider = provider.displayName,
                        model = model,
                        endpoint = AiRequestLogStore.redactUrl("${baseUrl.trimEnd('/')}/chat/completions"),
                        durationMs = System.currentTimeMillis() - requestStart,
                        systemPrompt = AiSystemPromptEngine.systemPrompt(),
                        userPrompt = userPrompt,
                        errorMessage = t.message ?: t.javaClass.simpleName
                    )
                )
                throw t
            }

        val now = System.currentTimeMillis()

        if (useCache) {
            // The library sample is reshuffled per request, so prompts rarely repeat and stale
            // rows would otherwise pile up forever.
            cacheDao.clearOldCache(now - CACHE_TTL_MILLIS)
            cacheDao.insert(
                    AiCacheEntity(
                            promptHash = hash,
                            responseJson = result.content,
                            timestamp = now
                    )
            )
        }
        usageDao.insertUsage(
                AiUsageEntity(
                        timestamp = now,
                        provider = provider.name,
                        model = model,
                        promptType = promptType,
                        promptTokens = result.promptTokens,
                        outputTokens = result.outputTokens,
                        thoughtTokens = result.thoughtTokens
                )
        )
        requestLogStore.write(
                AiRequestLog(
                        timestamp = now,
                        status = AiRequestLog.STATUS_SUCCESS,
                        promptType = promptType,
                        provider = provider.displayName,
                        model = model,
                        endpoint = AiRequestLogStore.redactUrl("${baseUrl.trimEnd('/')}/chat/completions"),
                        durationMs = now - requestStart,
                        systemPrompt = AiSystemPromptEngine.systemPrompt(),
                        userPrompt = userPrompt,
                        responseText = result.content,
                        promptTokens = result.promptTokens,
                        outputTokens = result.outputTokens,
                        thoughtTokens = result.thoughtTokens
                )
        )

        return result.content
    }

    /**
     * A one-shot text completion with no library sample attached and no cache.
     *
     * Used by Serendipity's optional "let the AI rephrase it" step: the input and the output are
     * both short, the answer is only useful once, and caching it would just add a row that never
     * gets read. Thinking is forced off — rephrasing one sentence does not need a chain of
     * thought, and the user is waiting on it.
     */
    suspend fun generateText(
        systemPrompt: String,
        userPrompt: String,
        promptType: String
    ): String {
        val (provider, apiKey, baseUrl) = credentials()
        val model = preferences.getModel(provider).first()
        val thinking = if (provider.supportsThinkingParam) false else null
        val endpoint = AiRequestLogStore.redactUrl("${baseUrl.trimEnd('/')}/chat/completions")

        val requestStart = System.currentTimeMillis()
        val result =
                try {
                    client.chat(
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            model = model,
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                            thinkingEnabled = thinking
                    )
                } catch (t: Throwable) {
                    // Closing the sheet cancels the request: that is not a failure worth logging.
                    if (t is CancellationException) throw t
                    requestLogStore.write(
                            AiRequestLog(
                                    timestamp = System.currentTimeMillis(),
                                    status = AiRequestLog.STATUS_FAILED,
                                    promptType = promptType,
                                    provider = provider.displayName,
                                    model = model,
                                    endpoint = endpoint,
                                    durationMs = System.currentTimeMillis() - requestStart,
                                    systemPrompt = systemPrompt,
                                    userPrompt = userPrompt,
                                    errorMessage = t.message ?: t.javaClass.simpleName
                            )
                    )
                    throw t
                }

        val now = System.currentTimeMillis()
        usageDao.insertUsage(
                AiUsageEntity(
                        timestamp = now,
                        provider = provider.name,
                        model = model,
                        promptType = promptType,
                        promptTokens = result.promptTokens,
                        outputTokens = result.outputTokens,
                        thoughtTokens = result.thoughtTokens
                )
        )
        requestLogStore.write(
                AiRequestLog(
                        timestamp = now,
                        status = AiRequestLog.STATUS_SUCCESS,
                        promptType = promptType,
                        provider = provider.displayName,
                        model = model,
                        endpoint = endpoint,
                        durationMs = now - requestStart,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        responseText = result.content,
                        promptTokens = result.promptTokens,
                        outputTokens = result.outputTokens,
                        thoughtTokens = result.thoughtTokens
                )
        )

        return result.content
    }

    /**
     * Streams one multi-turn chat request with no cache.
     *
     * [messages] must start with the system message and contain the bounded user/assistant
     * transcript. Q&A answers are intentionally uncached: the same words can mean something else
     * after the song context changes, and a cache hit would also bypass the streaming UI.
     */
    suspend fun chat(
        messages: List<AiChatMessage>,
        promptType: String,
        listener: AiProgressListener? = null
    ): String {
        require(messages.firstOrNull()?.role == AiChatMessage.Role.SYSTEM) {
            "The first chat message must be the system prompt"
        }
        val (provider, apiKey, baseUrl) = credentials()
        val model = preferences.getModel(provider).first()
        // Conversational answers should start immediately; thinking stays a playlist-only option.
        val thinking = if (provider.supportsThinkingParam) false else null
        val endpoint = AiRequestLogStore.redactUrl("${baseUrl.trimEnd('/')}/chat/completions")
        val systemPrompt = messages.first().content
        val transcript = messages.drop(1).joinToString(separator = "\n\n") { message ->
            "${message.role.apiValue}: ${message.content}"
        }

        val requestStart = System.currentTimeMillis()
        val result =
                try {
                    client.chat(
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            model = model,
                            messages = messages,
                            thinkingEnabled = thinking,
                            listener = listener
                    )
                } catch (t: Throwable) {
                    // Closing the sheet cancels the request: that is not a failure worth logging.
                    if (t is CancellationException) throw t
                    requestLogStore.write(
                            AiRequestLog(
                                    timestamp = System.currentTimeMillis(),
                                    status = AiRequestLog.STATUS_FAILED,
                                    promptType = promptType,
                                    provider = provider.displayName,
                                    model = model,
                                    endpoint = endpoint,
                                    durationMs = System.currentTimeMillis() - requestStart,
                                    systemPrompt = systemPrompt,
                                    userPrompt = transcript,
                                    errorMessage = t.message ?: t.javaClass.simpleName
                            )
                    )
                    throw t
                }

        val now = System.currentTimeMillis()
        usageDao.insertUsage(
                AiUsageEntity(
                        timestamp = now,
                        provider = provider.name,
                        model = model,
                        promptType = promptType,
                        promptTokens = result.promptTokens,
                        outputTokens = result.outputTokens,
                        thoughtTokens = result.thoughtTokens
                )
        )
        requestLogStore.write(
                AiRequestLog(
                        timestamp = now,
                        status = AiRequestLog.STATUS_SUCCESS,
                        promptType = promptType,
                        provider = provider.displayName,
                        model = model,
                        endpoint = endpoint,
                        durationMs = now - requestStart,
                        systemPrompt = systemPrompt,
                        userPrompt = transcript,
                        responseText = result.content,
                        promptTokens = result.promptTokens,
                        outputTokens = result.outputTokens,
                        thoughtTokens = result.thoughtTokens
                )
        )

        return result.content
    }

    private suspend fun credentials(): Triple<AiProvider, String, String> {
        val provider = preferences.getProvider()
        val apiKey = preferences.getApiKey(provider).first()
        val baseUrl =
                preferences.getBaseUrl(provider).first().ifBlank {
                    provider.resolveBaseUrl(apiKey)
                }
        return Triple(provider, apiKey, baseUrl)
    }

    private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                    .digest(value.toByteArray())
                    .joinToString("") { "%02x".format(it) }

    companion object {
        const val PROMPT_TYPE_PLAYLIST = "playlist"

        /** Serendipity's playlist request: random by design, so never cached. */
        const val PROMPT_TYPE_SERENDIPITY_PLAYLIST = "serendipity_playlist"

        /** Serendipity's optional "let the AI rephrase it" step. */
        const val PROMPT_TYPE_SERENDIPITY_PROMPT = "serendipity_prompt"

        /** Multi-turn Q&A opened from the player. */
        const val PROMPT_TYPE_SONG_QA = "song_qa"

        private const val CACHE_TTL_MILLIS = AiPreferencesRepository.CACHE_TTL_MILLIS
    }
}
