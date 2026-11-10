package com.pisces312.piscesplayer.data.ai

import com.pisces312.piscesplayer.data.ai.provider.AiErrorKind
import com.pisces312.piscesplayer.data.ai.provider.AiProviderException
import com.pisces312.piscesplayer.data.ai.provider.aiErrorKindFor
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource

private val json = Json { ignoreUnknownKeys = true }

private const val SSE_DATA_PREFIX = "data:"
private const val SSE_DONE_MARKER = "[DONE]"

/** How one raw SSE line was classified. */
internal sealed interface SseLine {
    /** `data: {...}` — a decoded chunk. */
    data class Data(val payload: JsonObject) : SseLine

    /** `data: [DONE]` — the stream ended normally. */
    data object Done : SseLine

    /** Blank lines, `:` heartbeats, `event:` / `id:` fields and unparsable payloads. */
    data object Ignore : SseLine
}

/**
 * Classifies one raw line of a chat completions stream.
 *
 * Kept free of IO so the stream handling can be unit tested by feeding hand-written lines.
 */
internal fun parseSseLine(line: String): SseLine {
    val trimmed = line.trim()
    if (!trimmed.startsWith(SSE_DATA_PREFIX)) return SseLine.Ignore
    val payload = trimmed.removePrefix(SSE_DATA_PREFIX).trim()
    if (payload == SSE_DONE_MARKER) return SseLine.Done
    val decoded = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
    return if (decoded == null) SseLine.Ignore else SseLine.Data(decoded)
}

/**
 * Accumulates one chat completions stream: thinking deltas, answer deltas and the trailing usage
 * packet. The [listener] sees every delta as it arrives; nothing is written to disk here.
 */
internal class ChatStreamAccumulator(private val listener: AiProgressListener?) {

    private val thinking = StringBuilder()
    private val answer = StringBuilder()

    private var promptTokens = 0
    private var outputTokens = 0
    private var thoughtTokens = 0

    /** True once `data: [DONE]` arrived: the read loop can stop. */
    var isDone = false
        private set

    /** Consumes one raw SSE line. */
    fun accept(rawLine: String) {
        if (isDone) return
        when (val line = parseSseLine(rawLine)) {
            SseLine.Ignore -> Unit
            SseLine.Done -> isDone = true
            is SseLine.Data -> acceptChunk(line.payload)
        }
    }

    /**
     * Consumes a whole non-streamed body.
     *
     * Some proxies buffer the SSE response and hand back a single `chat.completion` object; parsing
     * it here means such an endpoint degrades to "wait, then show everything" instead of failing.
     */
    fun acceptWholeBody(body: String) {
        val payload =
                runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                        ?: throw AiProviderException(
                                kind = AiErrorKind.RESPONSE_PARSE,
                                statusCode = null,
                                message = "Malformed response"
                        )
        val message = payload["choices"]?.jsonArrayFirst()?.let { (it as? JsonObject)?.get("message") }
        emitThinking(message.deltaField("reasoning_content") ?: message.deltaField("reasoning"))
        emitAnswer(message.deltaField("content"))
        applyUsage(payload["usage"] as? JsonObject)
        isDone = true
    }

    /** Trims the accumulated answer and pairs it with the token counters. */
    fun toResult(): OpenAiCompatibleClient.ChatResult {
        val content = answer.toString().trim()
        if (content.isEmpty()) {
            throw AiProviderException(
                    kind = AiErrorKind.RESPONSE_PARSE,
                    statusCode = null,
                    message = "Empty response from model"
            )
        }
        return OpenAiCompatibleClient.ChatResult(
                content = content,
                promptTokens = promptTokens,
                outputTokens = outputTokens,
                thoughtTokens = thoughtTokens
        )
    }

    private fun acceptChunk(payload: JsonObject) {
        (payload["error"] as? JsonObject)?.let { error ->
            throw AiProviderException(
                    kind = AiErrorKind.SERVER,
                    statusCode = null,
                    message = error["message"]?.primitiveContent() ?: "Provider reported an error"
            )
        }
        val delta = payload["choices"]?.jsonArrayFirst()?.let { (it as? JsonObject)?.get("delta") }
        emitThinking(delta.deltaField("reasoning_content") ?: delta.deltaField("reasoning"))
        emitAnswer(delta.deltaField("content"))
        applyUsage(payload["usage"] as? JsonObject)
    }

    private fun emitThinking(delta: String?) {
        if (delta.isNullOrEmpty()) return
        thinking.append(delta)
        listener?.onProgress(AiProgressEvent.Thinking(delta, thinking.toString()))
    }

    private fun emitAnswer(delta: String?) {
        if (delta.isNullOrEmpty()) return
        answer.append(delta)
        listener?.onProgress(AiProgressEvent.Answer(delta, answer.toString()))
    }

    private fun applyUsage(usage: JsonObject?) {
        if (usage == null) return
        promptTokens = usage.intField("prompt_tokens")
        outputTokens = usage.intField("completion_tokens")
        thoughtTokens =
                usage.nestedIntField("completion_tokens_details", "reasoning_tokens")
                        ?: usage.intField("reasoning_tokens")
    }
}

private fun JsonElement?.deltaField(name: String): String? =
        (this as? JsonObject)?.get(name)?.primitiveContent()

private fun JsonElement?.primitiveContent(): String? =
        runCatching { this?.jsonPrimitive?.contentOrNull }.getOrNull()

private fun JsonElement?.jsonArrayFirst(): JsonElement? =
        runCatching { (this as? JsonArray)?.firstOrNull() }.getOrNull()

private fun JsonObject?.intField(name: String): Int =
        runCatching { this?.get(name)?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0

private fun JsonObject?.nestedIntField(parent: String, name: String): Int? =
        runCatching { ((this?.get(parent) as? JsonObject)?.get(name)?.jsonPrimitive?.intOrNull) }
                .getOrNull()

/**
 * Minimal OpenAI-compatible client: chat completions plus model listing.
 *
 * Base URLs are user configurable, so Retrofit's fixed-baseUrl model would need a rebuilt
 * instance per provider — a plain OkHttp call is smaller and keeps every provider on one code
 * path.
 */
@Singleton
class OpenAiCompatibleClient
@Inject
constructor(private val baseClient: OkHttpClient) {

    data class ChatResult(
        val content: String,
        val promptTokens: Int,
        val outputTokens: Int,
        val thoughtTokens: Int
    )

    /**
     * Used for chat. Completions are streamed, so the socket keeps receiving thinking tokens while
     * the model reasons — the shared client's 8s read timeout would fire during that silence and be
     * reported as "network unreachable".
     */
    private val streamingClient by lazy {
        baseClient
                .newBuilder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // No overall cap: a long chain of thought can legitimately outlive any fixed budget.
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
    }

    /** Used for model listing: a plain GET, so a bounded total time is still the right guard. */
    private val simpleClient by lazy {
        baseClient
                .newBuilder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(SIMPLE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(SIMPLE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
    }

    /** `GET {baseUrl}/models` -> model ids, sorted alphabetically. */
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> =
            withContext(Dispatchers.IO) {
                val request =
                        Request.Builder()
                                .url(endpoint(baseUrl, "models"))
                                .applyAuth(apiKey)
                                .get()
                                .build()
                val payload = execute(request)
                val data = payload["data"] as? JsonArray
                data?.mapNotNull { (it as? JsonObject)?.get("id")?.primitiveContent() }?.sorted()
                        ?: emptyList()
            }

    /**
     * Streams a one-shot chat completion and returns the assembled answer.
     *
     * Kept as a convenience wrapper for the playlist and settings probes; feature code that has a
     * real conversation should call the [messages] overload instead.
     */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        thinkingEnabled: Boolean?,
        listener: AiProgressListener? = null
    ): ChatResult =
            chat(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    messages =
                            listOf(
                                    AiChatMessage(AiChatMessage.Role.SYSTEM, systemPrompt),
                                    AiChatMessage(AiChatMessage.Role.USER, userPrompt)
                            ),
                    thinkingEnabled = thinkingEnabled,
                    listener = listener
            )

    /**
     * Streams a chat completion and returns the assembled answer.
     *
     * [listener] is called for every thinking / answer delta while the stream is open. Cancelling
     * the calling coroutine closes the socket, so a dismissed sheet stops paying for tokens.
     */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<AiChatMessage>,
        thinkingEnabled: Boolean?,
        listener: AiProgressListener? = null
    ): ChatResult =
            withContext(Dispatchers.IO) {
                val body =
                        buildJsonObject {
                                    put("model", model)
                                    put("stream", true)
                                    put(
                                            "stream_options",
                                            buildJsonObject { put("include_usage", true) }
                                    )
                                    put(
                                            "messages",
                                            buildJsonArray {
                                                messages.forEach { message ->
                                                    add(
                                                            buildJsonObject {
                                                                put("role", message.role.apiValue)
                                                                put("content", message.content)
                                                            }
                                                    )
                                                }
                                            }
                                    )
                                    if (thinkingEnabled != null) {
                                        put(
                                                "thinking",
                                                buildJsonObject {
                                                    put(
                                                            "type",
                                                            if (thinkingEnabled) "enabled"
                                                            else "disabled"
                                                    )
                                                }
                                        )
                                    }
                                }
                                .toString()

                val request =
                        Request.Builder()
                                .url(endpoint(baseUrl, "chat/completions"))
                                .applyAuth(apiKey)
                                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                                .build()

                executeStream(request, listener)
            }

    private suspend fun executeStream(
        request: Request,
        listener: AiProgressListener?
    ): ChatResult {
        val call = streamingClient.newCall(request)
        val job = coroutineContext[Job]
        job?.invokeOnCompletion { if (job.isCancelled) call.cancel() }

        val response =
                try {
                    call.execute()
                } catch (e: IOException) {
                    // A cancelled call also surfaces as an IOException; it is not a network failure.
                    if (job?.isCancelled == true) {
                        throw CancellationException("Chat request cancelled")
                    }
                    throw AiProviderException(
                            kind = AiErrorKind.NETWORK,
                            statusCode = null,
                            message = e.message ?: "Network failure",
                            cause = e
                    )
                }

        response.use {
            if (!it.isSuccessful) {
                // Error bodies are ordinary JSON even on a streaming endpoint.
                val raw = it.body.string()
                throw AiProviderException(
                        kind = aiErrorKindFor(it.code),
                        statusCode = it.code,
                        message = errorMessage(raw) ?: "HTTP ${it.code}"
                )
            }

            val accumulator = ChatStreamAccumulator(listener)
            try {
                pump(it.body.source(), accumulator)
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                throw AiProviderException(
                        kind = AiErrorKind.NETWORK,
                        statusCode = null,
                        message = e.message ?: "Network failure",
                        cause = e
                )
            }
            return accumulator.toResult()
        }
    }

    /** Feeds the SSE body into [accumulator] until `[DONE]`, EOF or cancellation. */
    private suspend fun pump(source: BufferedSource, accumulator: ChatStreamAccumulator) {
        var isFirstLine = true
        while (!accumulator.isDone) {
            coroutineContext.ensureActive()
            val line = source.readUtf8Line() ?: break
            if (isFirstLine) {
                isFirstLine = false
                // A buffering proxy answers with one plain chat.completion object instead of SSE.
                if (line.trimStart().startsWith("{")) {
                    accumulator.acceptWholeBody(line + "\n" + source.readUtf8())
                    break
                }
            }
            accumulator.accept(line)
        }
    }

    private fun Request.Builder.applyAuth(apiKey: String): Request.Builder = apply {
        if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
    }

    private fun endpoint(baseUrl: String, path: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("http")) { "Base URL must start with http or https" }
        return "$normalized/$path"
    }

    /** Runs [request] and returns the decoded JSON body, mapping failures to [AiProviderException]. */
    private fun execute(request: Request): JsonObject {
        val response =
                try {
                    simpleClient.newCall(request).execute()
                } catch (e: IOException) {
                    throw AiProviderException(
                            kind = AiErrorKind.NETWORK,
                            statusCode = null,
                            message = e.message ?: "Network failure",
                            cause = e
                    )
                }

        response.use {
            val raw = it.body.string()
            if (!it.isSuccessful) {
                throw AiProviderException(
                        kind = aiErrorKindFor(it.code),
                        statusCode = it.code,
                        message = errorMessage(raw) ?: "HTTP ${it.code}"
                )
            }
            return try {
                json.parseToJsonElement(raw) as? JsonObject
                        ?: throw IllegalArgumentException("Response is not a JSON object")
            } catch (e: Exception) {
                throw AiProviderException(
                        kind = AiErrorKind.RESPONSE_PARSE,
                        statusCode = it.code,
                        message = "Malformed response",
                        cause = e
                )
            }
        }
    }

    private fun errorMessage(raw: String): String? =
            try {
                (json.parseToJsonElement(raw) as? JsonObject)
                        ?.get("error")
                        ?.let { it as? JsonObject }
                        ?.get("message")
                        ?.primitiveContent()
            } catch (e: Exception) {
                null
            }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L

        /** Silence allowed between two chunks; a live stream never comes close. */
        const val STREAM_READ_TIMEOUT_SECONDS = 120L
        const val SIMPLE_READ_TIMEOUT_SECONDS = 60L
        const val SIMPLE_CALL_TIMEOUT_SECONDS = 60L
        const val WRITE_TIMEOUT_SECONDS = 30L

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
