package com.pisces312.piscesplayer.presentation.viewmodel

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SetupUiState required permissions (P0-4)")
class SetupUiStateTest {

    @Test
    fun `media granted without notifications can finish setup`() {
        val state = SetupUiState(
            mediaPermissionGranted = true,
            notificationsPermissionGranted = false
        )
        assertTrue(state.requiredPermissionsGranted)
    }

    @Test
    fun `notifications without media cannot finish setup`() {
        val state = SetupUiState(
            mediaPermissionGranted = false,
            notificationsPermissionGranted = true
        )
        assertFalse(state.requiredPermissionsGranted)
    }

    @Test
    fun `neither permission granted cannot finish setup`() {
        val state = SetupUiState(
            mediaPermissionGranted = false,
            notificationsPermissionGranted = false
        )
        assertFalse(state.requiredPermissionsGranted)
    }
}
