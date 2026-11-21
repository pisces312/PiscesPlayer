package com.pisces312.piscesplayer.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.classical.ClassicalDisplay
import com.pisces312.piscesplayer.data.classical.ClassicalDisplayNames
import com.pisces312.piscesplayer.data.classical.ClassicalNameCatalog
import com.pisces312.piscesplayer.data.classical.NameSet
import com.pisces312.piscesplayer.data.model.Song
import java.util.Locale

/**
 * Shared building blocks for the「古典熏陶」browse screens (composer list, work sets,
 * favorites tab): search field, track rows with favorite stars and the three-layer
 * title display ([ClassicalDisplay]).
 */

/** 三层曲名的实时搜索匹配：原文/英文/中文任一层或 fallback 命中即中。 */
internal fun namesMatchQuery(names: NameSet?, fallback: String, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase(Locale.ROOT)
    if (fallback.lowercase(Locale.ROOT).contains(q)) return true
    if (names == null) return false
    return listOf(names.original, names.en, names.zh).any {
        it.lowercase(Locale.ROOT).contains(q)
    }
}

/** 曲目的双语展示名（目录缺失时回退 DB 标题）。 */
internal fun trackDisplayNames(
    song: Song,
    nameCatalog: ClassicalNameCatalog,
    translationEnabled: Boolean
): ClassicalDisplayNames {
    val names = nameCatalog.songName(song.id)
    return if (names != null) {
        ClassicalDisplay.resolve(names, translate = translationEnabled)
    } else {
        ClassicalDisplayNames(primary = song.title, subtitle = null)
    }
}

@Composable
internal fun ClassicalSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        leadingIcon = {
            Icon(Icons.Rounded.Search, contentDescription = null)
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.classical_search_clear))
                }
            }
        } else {
            null
        },
        placeholder = { Text(hint) },
        textStyle = MaterialTheme.typography.bodyLarge
    )
}

/** 曲目行：标题（双语主副）+ 可选副行 + 收藏星标；点按整行触发 [onClick]。 */
@Composable
internal fun ClassicalTrackRow(
    title: ClassicalDisplayNames,
    subtitle: String?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 8.dp)
                .clickable(onClick = onClick)
        ) {
            Text(
                text = title.primary,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val secondary = title.subtitle ?: subtitle
            if (secondary != null) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = stringResource(
                    if (isFavorite) R.string.classical_unfavorite else R.string.classical_favorite
                ),
                tint = if (isFavorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
