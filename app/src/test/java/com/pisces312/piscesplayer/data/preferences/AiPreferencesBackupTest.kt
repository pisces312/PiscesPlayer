/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: round-trip tests for AI preference backup.
 */
package com.pisces312.piscesplayer.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.pisces312.piscesplayer.data.ai.provider.AiProvider
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiPreferencesBackupTest {

    @Test
    fun `exported entries survive a round trip through import`() = runTest {
        val source = newRepository("source", backgroundScope)
        source.setProvider(AiProvider.CUSTOM)
        source.setApiKey(AiProvider.CUSTOM, "sk-test-key")
        source.setModel(AiProvider.CUSTOM, "mimo-7b")
        source.setBaseUrl(AiProvider.CUSTOM, "https://api.example.com/v1")
        source.setThinkingEnabled(AiProvider.CUSTOM, true)
        source.setLibrarySampleSize(150)

        val exported = source.exportForBackup()

        val target = newRepository("target", backgroundScope)
        target.importFromBackup(exported, clearExisting = true)

        assertEquals(AiProvider.CUSTOM, target.getProvider())
        assertEquals("sk-test-key", target.getApiKey(AiProvider.CUSTOM).first())
        assertEquals("mimo-7b", target.getModel(AiProvider.CUSTOM).first())
        assertEquals("https://api.example.com/v1", target.getBaseUrl(AiProvider.CUSTOM).first())
        assertTrue(target.getThinkingEnabled(AiProvider.CUSTOM).first())
        assertEquals(150, target.getLibrarySampleSize().first())
    }

    @Test
    fun `import drops keys that were cleared in the source`() = runTest {
        val source = newRepository("source-empty", backgroundScope)

        val target = newRepository("target-with-data", backgroundScope)
        target.setApiKey(AiProvider.MIMO, "stale-key")
        target.importFromBackup(source.exportForBackup(), clearExisting = true)

        assertEquals("", target.getApiKey(AiProvider.MIMO).first())
    }

    @Test
    fun `import ignores entries outside the AI namespace`() = runTest {
        val target = newRepository("target-foreign", backgroundScope)
        target.setApiKey(AiProvider.MIMO, "keep-me")

        target.importFromBackup(
            listOf(
                PreferenceBackupEntry("some_unrelated_setting", "string", stringValue = "x"),
                PreferenceBackupEntry("mimo_api_key", "string", stringValue = "overwritten")
            ),
            clearExisting = true
        )

        assertEquals("overwritten", target.getApiKey(AiProvider.MIMO).first())
    }

    @Test
    fun `clearAll removes every AI preference`() = runTest {
        val repository = newRepository("clear-all", backgroundScope)
        repository.setApiKey(AiProvider.MIMO, "sk-test-key")
        repository.setModel(AiProvider.MIMO, "mimo-7b")

        repository.clearAll()

        assertEquals("", repository.getApiKey(AiProvider.MIMO).first())
        assertEquals(0, repository.exportForBackup().size)
    }

    private fun newRepository(name: String, scope: CoroutineScope): AiPreferencesRepository {
        val tempDir = Files.createTempDirectory("ai-preferences-backup-test-$name")
        return AiPreferencesRepository(
            dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
            )
        )
    }
}
