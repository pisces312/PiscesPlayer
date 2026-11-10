package com.pisces312.piscesplayer.presentation.components

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Prefix used when the prompt is not one of the offered idea chips. */
internal const val AI_MIX_FALLBACK_PREFIX = "AI Mix"

/** Separates the idea word from the timestamp inside a generated mix name. */
internal const val AI_MIX_NAME_SEPARATOR = " · "

private val MIX_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * Default name for a generated mix: the idea word the user tapped, followed by when it was made.
 *
 * The word only wins when the prompt is exactly an idea chip — as soon as the user types
 * something of their own, the name falls back to the generic prefix instead of presenting a
 * half-quoted description as a playlist title.
 */
internal fun aiMixDefaultName(
    prompt: String,
    ideas: Collection<String>,
    now: LocalDateTime = LocalDateTime.now()
): String {
    val prefix =
            ideas.firstOrNull { it.equals(prompt.trim(), ignoreCase = true) }
                    ?: AI_MIX_FALLBACK_PREFIX
    return "$prefix$AI_MIX_NAME_SEPARATOR${now.format(MIX_TIMESTAMP_FORMAT)}"
}

/**
 * The part of a mix name worth a line of its own: the idea word, with the timestamp it was
 * saved with dropped — cards show the timestamp separately.
 *
 * A hand-written name has no separator and is used as it is.
 */
internal fun aiMixDisplayName(name: String): String =
        name.substringBefore(AI_MIX_NAME_SEPARATOR).trim().ifEmpty { name }

/** When a mix was created, in the same shape the generated names use. */
internal fun formatMixTimestamp(createdAtMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(createdAtMillis), ZoneId.systemDefault())
                .format(MIX_TIMESTAMP_FORMAT)
