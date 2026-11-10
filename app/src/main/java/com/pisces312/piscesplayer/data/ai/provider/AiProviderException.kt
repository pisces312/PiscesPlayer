package com.pisces312.piscesplayer.data.ai.provider

/** Coarse failure classification so the UI can show an actionable message. */
enum class AiErrorKind {
    UNAUTHORIZED,
    QUOTA_EXCEEDED,
    MODEL_NOT_FOUND,
    RATE_LIMITED,
    SERVER,
    NETWORK,
    RESPONSE_PARSE,
    UNKNOWN
}

class AiProviderException(
    val kind: AiErrorKind,
    val statusCode: Int?,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

internal fun aiErrorKindFor(statusCode: Int): AiErrorKind =
        when (statusCode) {
            401,
            403 -> AiErrorKind.UNAUTHORIZED
            402 -> AiErrorKind.QUOTA_EXCEEDED
            404 -> AiErrorKind.MODEL_NOT_FOUND
            429 -> AiErrorKind.RATE_LIMITED
            in 500..599 -> AiErrorKind.SERVER
            else -> AiErrorKind.UNKNOWN
        }
