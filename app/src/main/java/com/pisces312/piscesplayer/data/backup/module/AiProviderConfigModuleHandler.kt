/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: backup module for the AI provider configuration.
 */
package com.pisces312.piscesplayer.data.backup.module

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pisces312.piscesplayer.data.backup.model.BackupSection
import com.pisces312.piscesplayer.data.preferences.AiPreferencesRepository
import com.pisces312.piscesplayer.data.preferences.PreferenceBackupEntry
import com.pisces312.piscesplayer.di.BackupGson
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backs up the AI provider settings as a list of preference entries, the same shape the global
 * settings module uses.
 *
 * The payload includes API keys in clear text — see
 * [AiPreferencesRepository.exportForBackup] for why.
 */
@Singleton
class AiProviderConfigModuleHandler
@Inject
constructor(
        private val aiPreferences: AiPreferencesRepository,
        @BackupGson private val gson: Gson
) : BackupModuleHandler {

    override val section: BackupSection = BackupSection.AI_PROVIDER_CONFIG

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        gson.toJson(aiPreferences.exportForBackup())
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        aiPreferences.exportForBackup().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        val type = TypeToken.getParameterized(List::class.java, PreferenceBackupEntry::class.java).type
        val entries: List<PreferenceBackupEntry> = gson.fromJson(payload, type) ?: emptyList()
        aiPreferences.importFromBackup(entries, clearExisting = true)
    }

    override suspend fun rollback(snapshot: String) = restore(snapshot)
}
