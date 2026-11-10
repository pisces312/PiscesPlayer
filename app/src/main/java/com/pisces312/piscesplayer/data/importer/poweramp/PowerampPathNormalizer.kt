/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: ported from the author's china-only PixelPlayer fork.
 */
package com.pisces312.piscesplayer.data.importer.poweramp

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Poweramp 路径规范化。
 *
 * Poweramp path 形态（实测）：
 * - `primary/Music4Phone/xxx.mp3` —— 内置存储
 * - `9C33-6BBD/Music4Phone/...` —— 外置 SD 卡（FAT32 卷序列号）
 * - `01 - 彩云追月.mp3` —— 裸文件名（无目录无卷前缀）
 *
 * 规范化 = 去卷前缀 → 拼对应存储根。卷不存在 / 裸文件名 → 返回 null，
 * 由 SongMatcher 降级到文件名 / 元数据匹配。
 */
@Singleton
class PowerampPathNormalizer internal constructor(
    private val volumeRoots: Map<String, String>
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(buildVolumeRoots(context))

    /**
     * 规范化为绝对路径（与 `songs.file_path` 同一坐标系）。
     * @return 绝对路径；裸文件名 / 卷不存在时返回 null。
     */
    fun normalize(powerampPath: String): String? {
        val unified = powerampPath.replace('\\', '/').trimEnd('/')
        val slashIdx = unified.indexOf('/')
        if (slashIdx <= 0) return null // 裸文件名：无卷前缀，无法构成绝对路径
        val volume = unified.substring(0, slashIdx).lowercase()
        val rest = unified.substring(slashIdx + 1)
        val root = volumeRoots[volume] ?: return null // 本机不存在该卷（如换机后 SD 卡已移除）
        return "$root/$rest"
    }

    /** 取路径尾段文件名（含扩展名），供第 2 级文件名匹配。 */
    fun fileName(powerampPath: String): String =
        powerampPath.replace('\\', '/').substringAfterLast('/')

    /**
     * 取中间目录名（不含卷前缀与文件名），供第 3 级匹配的 artist/album 加分信号。
     * 例：`primary/Music4Phone/周杰伦/十一月的萧邦/01 - 安静.mp3` → ["Music4Phone", "周杰伦", "十一月的萧邦"]
     */
    fun directoryHints(powerampPath: String): List<String> {
        val segments = powerampPath.replace('\\', '/').split('/')
        if (segments.size <= 2) return emptyList()
        return segments.subList(1, segments.size - 1).filter { it.isNotBlank() }
    }

    companion object {
        private const val VOLUME_PRIMARY = "primary"

        /**
         * 枚举本机可用存储卷：getExternalFilesDirs 每个元素对应一个卷，
         * 形如 /storage/9C33-6BBD/Android/data/<pkg>/files → 根为 /storage/9C33-6BBD。
         * SD 卡挂载点名即 FAT32 卷序列号，与 Poweramp 卷前缀一致。
         */
        internal fun buildVolumeRoots(context: Context): Map<String, String> {
            val roots = mutableMapOf<String, String>()
            // 主存储（兜底，防止 getExternalFilesDirs 为空）
            roots[VOLUME_PRIMARY] = Environment.getExternalStorageDirectory().absolutePath
            context.getExternalFilesDirs(null).forEachIndexed { index, dir ->
                val root = dir?.absolutePath?.substringBefore("/Android") ?: return@forEachIndexed
                if (index == 0) {
                    roots[VOLUME_PRIMARY] = root
                } else {
                    roots[root.substringAfterLast('/').lowercase()] = root
                }
            }
            return roots
        }
    }
}
