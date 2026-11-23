package com.pisces312.piscesplayer.presentation.home

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.home.HomeCardSize
import com.pisces312.piscesplayer.data.home.HomeCardType

/**
 * The static descriptor of one home card type: label, subtitle and default span.
 * Distinct fixed types (games, learning tools, 入梦, …) each get one entry; user
 * playlists share the single [PLAYLIST] descriptor.
 *
 * v1.3: every card renders in the same neutral Material 3 container
 * (`surfaceContainer` + `HomeCardShape`, see §12 of the plan) — the per-type
 * accent gradients are gone, so the catalog no longer carries colors. Cards are
 * told apart by icon and title only.
 */
enum class HomeCardCatalog(
    val type: HomeCardType,
    @StringRes val nameRes: Int,
    @StringRes val subtitleRes: Int?,
    val defaultSize: HomeCardSize,
    /** A stable decorative identity for the card icon. */
    val icon: HomeCardIcon,
) {
    AI_PLAYLIST(
        type = HomeCardType.AI_PLAYLIST,
        nameRes = R.string.ai_playlist_entry_title,
        subtitleRes = null,
        defaultSize = HomeCardSize.L_2x2,
        icon = HomeCardIcon.PainterIcon(R.drawable.rounded_auto_awesome_24),
    ),
    DAILY_MIX(
        type = HomeCardType.DAILY_MIX,
        nameRes = R.string.presentation_batch_b_daily_mix_title,
        subtitleRes = null,
        defaultSize = HomeCardSize.L_2x2,
        icon = HomeCardIcon.VectorIcon(Icons.Rounded.LightMode),
    ),
    GAME_RHYTHM(
        type = HomeCardType.GAME_RHYTHM,
        nameRes = R.string.rhythm_setup_title,
        subtitleRes = R.string.music_games_rhythm_subtitle,
        defaultSize = HomeCardSize.S_1x1,
        icon = HomeCardIcon.VectorIcon(Icons.Rounded.GraphicEq),
    ),
    GAME_QUIZ(
        type = HomeCardType.GAME_QUIZ,
        nameRes = R.string.music_games_quiz_title,
        subtitleRes = R.string.music_games_quiz_subtitle,
        defaultSize = HomeCardSize.S_1x1,
        icon = HomeCardIcon.VectorIcon(Icons.Rounded.Mic),
    ),
    SOUNDSCAPE(
        type = HomeCardType.SOUNDSCAPE,
        nameRes = R.string.soundscape_title,
        subtitleRes = R.string.home_grid_soundscape_subtitle,
        defaultSize = HomeCardSize.S_1x1,
        icon = HomeCardIcon.VectorIcon(Icons.Rounded.Bedtime),
    ),
    LEARN_CLASSICAL(
        type = HomeCardType.LEARN_CLASSICAL,
        nameRes = R.string.library_tab_classical,
        subtitleRes = R.string.music_learning_classical_subtitle,
        defaultSize = HomeCardSize.S_1x1,
        icon = HomeCardIcon.VectorIcon(Icons.Rounded.LibraryMusic),
    ),
    LEARN_MIDI(
        type = HomeCardType.LEARN_MIDI,
        nameRes = R.string.midi_library_title,
        subtitleRes = R.string.music_learning_midi_subtitle,
        defaultSize = HomeCardSize.S_1x1,
        icon = HomeCardIcon.VectorIcon(Icons.Rounded.MusicNote),
    ),
    LEARN_EAR(
        type = HomeCardType.LEARN_EAR,
        nameRes = R.string.music_learning_ear_training_title,
        subtitleRes = R.string.music_learning_ear_training_subtitle,
        defaultSize = HomeCardSize.S_1x1,
        icon = HomeCardIcon.VectorIcon(Icons.Rounded.Hearing),
    ),
    LEARN_METRONOME(
        type = HomeCardType.LEARN_METRONOME,
        nameRes = R.string.music_learning_metronome_title,
        subtitleRes = R.string.music_learning_metronome_subtitle,
        defaultSize = HomeCardSize.S_1x1,
        icon = HomeCardIcon.VectorIcon(Icons.Rounded.Timer),
    ),
    STREAMING(
        type = HomeCardType.STREAMING,
        nameRes = R.string.presentation_batch_g_streaming_title,
        subtitleRes = R.string.home_grid_streaming_subtitle,
        defaultSize = HomeCardSize.S_1x1,
        icon = HomeCardIcon.VectorIcon(Icons.Rounded.Cloud),
    ),
    SETTINGS(
        type = HomeCardType.SETTINGS,
        nameRes = R.string.settings_top_bar_title,
        subtitleRes = R.string.home_grid_settings_subtitle,
        defaultSize = HomeCardSize.S_1x1,
        icon = HomeCardIcon.PainterIcon(R.drawable.rounded_settings_24),
    ),
    PLAYLIST(
        type = HomeCardType.PLAYLIST,
        nameRes = R.string.home_grid_playlist_fallback_name,
        subtitleRes = R.string.home_grid_playlist_tracks_format,
        defaultSize = HomeCardSize.S_1x1,
        icon = HomeCardIcon.VectorIcon(Icons.Rounded.MusicNote),
    ),
    ;

    companion object {
        fun forType(type: HomeCardType): HomeCardCatalog = entries.first { it.type == type }

        /** Fixed (non-playlist) descriptors, grouped for the「添加卡片」sheet. */
        val fixedEntries: List<HomeCardCatalog> = entries.filter { it.type != HomeCardType.PLAYLIST }

        /** Groups in the order the add sheet presents them (plan §5.3). */
        val groups: List<HomeCardGroup> = listOf(
            HomeCardGroup(
                nameRes = R.string.home_grid_group_recommended,
                members = listOf(AI_PLAYLIST, DAILY_MIX),
            ),
            HomeCardGroup(
                nameRes = R.string.home_grid_group_games,
                members = listOf(GAME_RHYTHM, GAME_QUIZ),
            ),
            HomeCardGroup(
                nameRes = R.string.home_grid_group_soundscape,
                members = listOf(SOUNDSCAPE),
            ),
            HomeCardGroup(
                nameRes = R.string.home_grid_group_learning,
                members = listOf(LEARN_CLASSICAL, LEARN_MIDI, LEARN_EAR, LEARN_METRONOME),
            ),
            HomeCardGroup(
                nameRes = R.string.home_grid_group_sources,
                members = listOf(STREAMING, SETTINGS),
            ),
        )
    }
}

/** A named section of the「添加卡片」sheet. */
data class HomeCardGroup(
    @StringRes val nameRes: Int,
    val members: List<HomeCardCatalog>,
)

/** Icon identity that survives configuration changes and does not depend on a composable. */
sealed interface HomeCardIcon {
    data class VectorIcon(val imageVector: ImageVector) : HomeCardIcon
    data class PainterIcon(@androidx.annotation.DrawableRes val drawableRes: Int) : HomeCardIcon
}

@Composable
fun HomeCardIcon.Render(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    when (this) {
        is HomeCardIcon.VectorIcon ->
            Icon(imageVector = imageVector, contentDescription = null, modifier = modifier, tint = tint)
        is HomeCardIcon.PainterIcon ->
            Icon(painter = painterResource(drawableRes), contentDescription = null, modifier = modifier, tint = tint)
    }
}
