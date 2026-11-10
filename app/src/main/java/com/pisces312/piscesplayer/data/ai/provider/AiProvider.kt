package com.pisces312.piscesplayer.data.ai.provider

/** Compile-time constants so the enum entries can use them during their own initialisation. */
private const val MIMO_PAY_AS_YOU_GO_URL = "https://api.xiaomimimo.com/v1"

private const val MIMO_TOKEN_PLAN_URL = "https://token-plan-cn.xiaomimimo.com/v1"

/**
 * Providers supported by the AI playlist generator.
 *
 * Every entry speaks the OpenAI-compatible `POST {baseUrl}/chat/completions` contract, so the
 * network layer stays a single implementation.
 */
enum class AiProvider(
    val displayName: String,
    val requiresApiKey: Boolean,
    val hasConfigurableUrl: Boolean,
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** Whether the endpoint understands `thinking: {"type": ...}` on chat requests. */
    val supportsThinkingParam: Boolean
) {
    MIMO(
        displayName = "Xiaomi MiMo",
        requiresApiKey = true,
        hasConfigurableUrl = false,
        defaultBaseUrl = MIMO_PAY_AS_YOU_GO_URL,
        defaultModel = "mimo-v2.5",
        supportsThinkingParam = true
    ),
    VOLCANO(
        displayName = "Volcano Engine (Ark)",
        requiresApiKey = true,
        hasConfigurableUrl = false,
        defaultBaseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        defaultModel = "",
        supportsThinkingParam = true
    ),
    CUSTOM(
        displayName = "Custom Provider",
        requiresApiKey = false,
        hasConfigurableUrl = true,
        defaultBaseUrl = "",
        defaultModel = "",
        supportsThinkingParam = false
    );

    /**
     * Endpoint to call for this provider and key.
     *
     * MiMo issues two kinds of key that are explicitly not interchangeable: pay-as-you-go keys
     * (`sk-`) only work against api.xiaomimimo.com, Token Plan keys (`tp-`) only against the
     * token-plan endpoint. Picking by prefix avoids the guaranteed 401 from pairing them wrongly.
     * Other clusters (Singapore, Amsterdam) can still be reached through a Custom provider.
     */
    fun resolveBaseUrl(apiKey: String): String {
        if (this != MIMO) return defaultBaseUrl
        return when {
            apiKey.trim().startsWith("tp-") -> MIMO_TOKEN_PLAN_URL
            apiKey.trim().startsWith("sk-") -> MIMO_PAY_AS_YOU_GO_URL
            else -> defaultBaseUrl
        }
    }

    /** Lower-cased id used as the DataStore key prefix, e.g. `mimo_model`. */
    val keyPrefix: String
        get() = name.lowercase()

    companion object {
        fun fromName(name: String?): AiProvider = entries.firstOrNull { it.name == name } ?: MIMO
    }
}
