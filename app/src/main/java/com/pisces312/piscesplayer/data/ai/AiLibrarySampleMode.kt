package com.pisces312.piscesplayer.data.ai

/** How the slice of the library handed to the model is chosen. */
enum class AiLibrarySampleMode {
    /**
     * Play count descending, then title.
     *
     * Stable across requests: the same description produces the same prompt and therefore hits the
     * response cache. Songs that were never played only appear once the library is smaller than the
     * requested size.
     */
    MOST_PLAYED,

    /**
     * Reshuffled on every request.
     *
     * Every song stays reachable regardless of play history, and regenerating over the same
     * description surfaces different corners of the library. The prompt changes every time, so the
     * response cache is effectively bypassed.
     */
    RANDOM;

    companion object {
        fun fromName(name: String?): AiLibrarySampleMode =
                entries.firstOrNull { it.name == name } ?: MOST_PLAYED
    }
}
