package com.pisces312.piscesplayer.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for the customizable home card grid (home-cards-grid-plan.md §6).
 *
 * The whole layout is a single user preference — one JSON list — so it lives in
 * DataStore rather than Room: no schema version bump, no migration, and the
 * read cost is one tiny string. Any unreadable value degrades to the default
 * layout so a corrupt write can never brick the home page.
 */
@Singleton
class HomeLayoutRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {

    private object Keys {
        // v3 (2026-11-23, plan §9 v1.3.3): the default order changed again
        // (AI 2×2 → games → 入梦 + 云端 → 每日推荐), so the old key is abandoned
        // and everyone lands on the fresh default once.
        val HOME_CARDS = stringPreferencesKey("home_cards_v3")
    }

    private val cardListSerializer = ListSerializer(HomeCard.serializer())

    /** Emits the stored layout, or [DefaultHomeLayout] when unset or unreadable. */
    val cardsFlow: Flow<List<HomeCard>> = dataStore.data.map { preferences ->
        val raw = preferences[Keys.HOME_CARDS] ?: return@map DefaultHomeLayout
        runCatching {
            json.decodeFromString(cardListSerializer, raw)
        }.getOrElse {
            Timber.tag(TAG).w(it, "Failed to decode home layout; falling back to default")
            DefaultHomeLayout
        }
    }

    /** Replaces the whole layout (drag reorder, delete, resize, restore). */
    suspend fun saveCards(cards: List<HomeCard>) {
        runCatching {
            dataStore.edit { preferences ->
                preferences[Keys.HOME_CARDS] = json.encodeToString(cardListSerializer, cards)
            }
        }.onFailure {
            Timber.tag(TAG).e(it, "Failed to save home layout")
        }
    }

    suspend fun restoreDefault() = saveCards(DefaultHomeLayout)

    private companion object {
        const val TAG = "HomeLayoutRepository"
    }
}
