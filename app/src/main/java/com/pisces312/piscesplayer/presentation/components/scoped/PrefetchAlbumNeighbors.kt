package com.pisces312.piscesplayer.presentation.components.scoped

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil.size.Size
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.presentation.components.albumArtMemoryCacheKey
import com.pisces312.piscesplayer.presentation.components.safeAlbumArtTargetSize
import com.pisces312.piscesplayer.utils.LocalArtworkUri
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun PrefetchAlbumNeighbors(
    isActive: Boolean,
    pagerState: PagerState,
    queue: ImmutableList<Song>,
    radius: Int = 1,
    targetSize: Size = Size(600, 600),
    anchorIndex: Int? = null
) {
    if (!isActive || queue.isEmpty()) return
    val context = LocalContext.current
    val imageLoader = coil.Coil.imageLoader(context)
    val requestTargetSize = remember(targetSize) {
        safeAlbumArtTargetSize(targetSize)
    }

    LaunchedEffect(pagerState, queue, anchorIndex, requestTargetSize) {
        snapshotFlow { 
            if (pagerState.isScrollInProgress) pagerState.currentPage 
            else anchorIndex ?: pagerState.currentPage 
        }
            .distinctUntilChanged()
            .collect { page ->
                val indices = (page - radius..page + radius)
                    .filter { it in queue.indices && it != page }
                indices.forEach { idx ->
                    queue[idx].albumArtUriString?.let { uri ->
                        val diskPolicy = if (LocalArtworkUri.isLocalArtworkUri(uri)) coil.request.CachePolicy.DISABLED else coil.request.CachePolicy.ENABLED
                        val memoryCacheKey = albumArtMemoryCacheKey(uri, requestTargetSize)
                        val req = coil.request.ImageRequest.Builder(context)
                            .data(uri)
                            .size(requestTargetSize)
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .apply {
                                if (memoryCacheKey != null) {
                                    memoryCacheKey(memoryCacheKey)
                                }
                            }
                            .diskCachePolicy(diskPolicy)
                            .networkCachePolicy(coil.request.CachePolicy.ENABLED)
                            .allowHardware(true)
                            .build()
                        imageLoader.enqueue(req)
                    }
                }
            }
    }
}
