package com.pisces312.piscesplayer.data.preferences

import android.content.Context
import androidx.annotation.StringRes
import com.pisces312.piscesplayer.R

enum class AppLanguage(val tag: String, @StringRes val labelRes: Int) {
    SYSTEM("", R.string.setcat_language_system),
    ENGLISH("en", R.string.setcat_language_english),
    ZH_RCN("zh-CN", R.string.language_zh_rCN);

    companion object {
        val supportedLanguageTags: Set<String> = entries.map { it.tag }.toSet()

        fun getLanguageOptions(context: Context): Map<String, String> {
            val systemOption = SYSTEM.tag to context.getString(SYSTEM.labelRes)
            val otherOptions = entries
                .filter { it != SYSTEM }
                .map { it.tag to context.getString(it.labelRes) }
                .sortedBy { it.second.lowercase() }

            val result = LinkedHashMap<String, String>()
            result[systemOption.first] = systemOption.second
            for (option in otherOptions) {
                result[option.first] = option.second
            }
            return result
        }

        fun normalize(languageTag: String?): String {
            val normalized = languageTag?.trim() ?: return SYSTEM.tag
            return entries.find { it.tag.equals(normalized, ignoreCase = true) }?.tag ?: SYSTEM.tag
        }
    }
}
