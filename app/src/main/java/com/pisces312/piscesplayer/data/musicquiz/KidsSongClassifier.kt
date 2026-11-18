package com.pisces312.piscesplayer.data.musicquiz

/**
 * Tier B kids-song classification. Tier A (catalog) lives in [KidsSongCatalog]; the caller
 * falls back to this classifier only when the catalog hits cannot fill a pool.
 *
 * Deliberate counter-examples (regression-tested): 《童年》《听妈妈的话》《宝贝对不起》
 * and "Baby" (pop) must not qualify — one weak title word is never enough on its own.
 */
internal object KidsSongClassifier {

    /** Tier A hit via the built-in catalog. */
    fun matchesCatalog(title: String): Boolean = KidsSongCatalog.matches(title)

    /**
     * Always-on performer-label rule (v4, user rule): the singer is a kids-content
     * label — 贝瓦儿歌、贝乐虎儿歌 or any performer containing 儿歌 — so the track is a
     * children's song regardless of the catalog/keyword tiers.
     */
    fun matchesArtistLabel(artist: String): Boolean {
        val key = MusicQuizText.normalizeWord(artist)
        return key.isNotEmpty() &&
            KidsSongKeywords.kidArtistLabelWords.any { MusicQuizText.containsPhrase(key, it) }
    }

    /**
     * Tier B keyword rules (any hit qualifies):
     * 1. artist/album hits a strong publisher word;
     * 2. title hits >= 1 strong word;
     * 3. title hits >= 2 weak words;
     * 4. title hits 1 weak word and lyrics hit >= 2 kids words;
     * 5. lyrics alone hit >= 3 distinct kids words including >= 1 strong word.
     */
    fun matchesKeywords(
        title: String,
        artist: String,
        album: String = "",
        lyrics: String? = null,
    ): Boolean {
        val titleKey = MusicQuizText.normalizeTitle(title)
        val metaKey = MusicQuizText.normalizeLoose("$artist $album")

        if (KidsSongKeywords.strongArtistWords.any { MusicQuizText.containsPhrase(metaKey, it) }) {
            return true
        }

        val titleStrong = KidsSongKeywords.strongTitleWords.count {
            MusicQuizText.containsPhrase(titleKey, it)
        }
        if (titleStrong >= 1) return true

        val titleWeak = KidsSongKeywords.weakTitleWords.count {
            MusicQuizText.containsPhrase(titleKey, it)
        }

        val lyricsKey = lyrics?.let { MusicQuizText.lyricsKey(it) }.orEmpty()
        val lyricHits: List<String> = if (lyricsKey.isEmpty()) {
            emptyList()
        } else {
            KidsSongKeywords.lyricWords.filter { MusicQuizText.containsPhrase(lyricsKey, it) }
        }
        val lyricStrong = lyricHits.count { it in KidsSongKeywords.strongTitleWords }

        return when {
            titleWeak >= 2 -> true
            titleWeak >= 1 && lyricHits.size >= 2 -> true
            lyricHits.size >= 3 && lyricStrong >= 1 -> true
            else -> false
        }
    }
}
