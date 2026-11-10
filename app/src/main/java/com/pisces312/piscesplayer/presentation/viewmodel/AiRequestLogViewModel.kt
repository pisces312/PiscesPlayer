/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: request log browser for this codebase.
 */
package com.pisces312.piscesplayer.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.data.ai.AiRequestLog
import com.pisces312.piscesplayer.data.ai.AiRequestLogStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AiRequestLogViewModel @Inject constructor(
    private val store: AiRequestLogStore
) : ViewModel() {

    private val _logs = MutableStateFlow<List<AiRequestLog>>(emptyList())
    val logs: StateFlow<List<AiRequestLog>> = _logs.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _logs.value = store.list()
            _isLoading.value = false
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            store.clearAll()
            _logs.value = emptyList()
        }
    }

    fun fileFor(id: String): File = store.fileFor(id)
}
