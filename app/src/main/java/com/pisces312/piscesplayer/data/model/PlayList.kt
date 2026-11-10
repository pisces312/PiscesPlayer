package com.pisces312.piscesplayer.data.model

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songIds: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis(),
    val isQueueGenerated: Boolean = false,
    val coverImageUri: String? = null,
    val coverColorArgb: Int? = null,
    val coverIconName: String? = null,
    val coverShapeType: String? = null,
    val coverShapeDetail1: Float? = null,
    val coverShapeDetail2: Float? = null,
    val coverShapeDetail3: Float? = null,
    val coverShapeDetail4: Float? = null,
    val source: String = "LOCAL",
    /**
     * The description (or Serendipity context sentence) this playlist was generated from.
     * Null for manually built playlists. Kept out of [name] on purpose: names are identifiers
     * that get searched, exported as file names and shown as queue names, while this is content.
     */
    val aiPrompt: String? = null,
    /** Name of the [com.pisces312.piscesplayer.data.ai.AiLibrarySampleMode] used at generation. */
    val aiSampleMode: String? = null,
    /** How many library titles were sent to the model as context at generation. */
    val aiSampleSize: Int? = null,
    /**
     * Ordered snapshot of the songs the model returned before the user edited the result. The live
     * [songIds] can be added to, removed from or reordered; this keeps what was *originally*
     * generated, so the detail screen can show it. Empty for manual and pre-v10 playlists.
     */
    val aiOriginalSongIds: List<String> = emptyList(),
    /**
     * The chain of thought the model streamed before producing the list, kept so the result phase
     * and the prompt details dialog can show how the mix was arrived at. Null for playlists built
     * by hand, generated with thinking off, or saved before this was recorded.
     */
    val aiThinking: String? = null
)

/** Marks a playlist produced by the AI mix flow. Lives in the data layer so backup and other data-side consumers can reference it. */
const val AI_MIX_SOURCE = "AI"

/**
 * Marks a playlist produced by Serendipity.
 *
 * Its own value rather than [AI_MIX_SOURCE] so the two can be told apart later (usage, filters),
 * while AI-mix listings deliberately accept both — a mix the user generated is worth offering
 * again whichever button produced it.
 */
const val SERENDIPITY_SOURCE = "AI_SERENDIPITY"

enum class PlaylistShapeType {
    Circle,
    SmoothRect,
    RotatedPill,
    Star
}
