package com.pisces312.piscesplayer.data.ai

import com.pisces312.piscesplayer.data.preferences.AiPreferencesRepository

/**
 * Builds the prompts sent to the provider.
 *
 * The library sample only ever carries song/artist text — no file paths or other device
 * identifiers leave the phone.
 */
object AiSystemPromptEngine {

    fun systemPrompt(): String = AiPreferencesRepository.DEFAULT_SYSTEM_PROMPT

    fun userPrompt(request: String, librarySample: String): String =
            buildString {
                appendLine("User request: $request")
                appendLine()
                appendLine("Library sample (pick from these when possible):")
                appendLine(librarySample.ifBlank { "(sample unavailable)" })
                appendLine()
                appendLine("Return up to 25 songs, one per line, as 'Title - Artist'.")
            }

    /**
     * System prompt for Serendipity's optional "let the AI rephrase it" step.
     *
     * The model rewrites one sentence and nothing else: no song titles, no lists, no commentary.
     * Serendipity's output is fed straight back into [userPrompt] as the user request, so anything
     * beyond the sentence would end up inside the playlist prompt.
     */
    fun serendipityRephraseSystemPrompt(): String =
            """
            You rewrite a short description of the present moment into one vivid English sentence.
            Keep every fact (day, time, weather, city, step count) exactly as given.
            Reply with that single sentence and nothing else: no lists, no quotes, no explanation.
            """
                    .trimIndent()

    fun serendipityRephraseUserPrompt(draft: String): String =
            "Rewrite this for a music playlist request: $draft"
}
