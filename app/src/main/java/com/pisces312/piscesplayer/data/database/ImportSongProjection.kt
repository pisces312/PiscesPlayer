/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: ported from the author's china-only PixelPlayer fork.
 */
package com.pisces312.piscesplayer.data.database

import androidx.room.ColumnInfo

/**
 * 导入匹配用的轻量歌曲投影。
 * 一次性载入全部本地歌曲建内存索引，避免千级规模下的逐首单点查询。
 */
data class ImportSongProjection(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "album_name") val albumName: String,
    @ColumnInfo(name = "duration") val duration: Long
)
