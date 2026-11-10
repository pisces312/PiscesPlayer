/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: import wizard state machine ported and adapted to this codebase.
 */
package com.pisces312.piscesplayer.presentation.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.data.importer.ImportOptions
import com.pisces312.piscesplayer.data.importer.ImportParseException
import com.pisces312.piscesplayer.data.importer.ImportProgress
import com.pisces312.piscesplayer.data.importer.ImportResult
import com.pisces312.piscesplayer.data.importer.PowerampBackupImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 第三方导入向导状态机。
 *
 * 流程：IDLE → PARSING → PREVIEW → OPTIONS → IMPORTING → RESULT / ERROR。
 * IMPORTING 可取消（取消后回到 OPTIONS，已写入的分步数据保留）。
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val importer: PowerampBackupImporter
) : ViewModel() {

    enum class Step { IDLE, PARSING, PREVIEW, OPTIONS, IMPORTING, RESULT, ERROR }

    data class UiState(
        val step: Step = Step.IDLE,
        val prepared: PowerampBackupImporter.PreparedImport? = null,
        val impact: PowerampBackupImporter.ImportImpact? = null,
        val options: ImportOptions = ImportOptions(),
        val progress: ImportProgress? = null,
        val result: ImportResult? = null,
        val errorMessage: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var importJob: Job? = null

    /** 文件选择器回调：解析 + 匹配 → 预览（不落库）。 */
    fun onFileSelected(uri: Uri) {
        if (_uiState.value.step == Step.PARSING || _uiState.value.step == Step.IMPORTING) return
        _uiState.update { it.copy(step = Step.PARSING, errorMessage = null, result = null) }
        viewModelScope.launch {
            try {
                val prepared = importer.prepare(uri)
                val impact = runCatching { importer.currentImpact() }.getOrNull()
                _uiState.update { it.copy(step = Step.PREVIEW, prepared = prepared, impact = impact) }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(step = Step.IDLE) }
                throw e
            } catch (e: Exception) {
                val msg = when (e) {
                    is ImportParseException -> e.message
                    else -> e.message ?: e.javaClass.simpleName
                }
                _uiState.update { it.copy(step = Step.ERROR, errorMessage = msg) }
            }
        }
    }

    /** 预览页「下一步」→ 选项配置。 */
    fun confirmPreview() {
        if (_uiState.value.prepared != null) {
            _uiState.update { it.copy(step = Step.OPTIONS) }
        }
    }

    fun backToPreview() {
        _uiState.update { it.copy(step = Step.PREVIEW) }
    }

    /** 选项变更（阈值 / 开关 / 模式）。 */
    fun updateOptions(options: ImportOptions) {
        _uiState.update { it.copy(options = options) }
    }

    /** 指定阈值下预计新增收藏数（选项页实时显示）。 */
    fun favoritesCountForThreshold(threshold: Int): Int {
        val prepared = _uiState.value.prepared ?: return 0
        return importer.favoritesCountForThreshold(prepared, threshold)
    }

    /** 匹配成功且含播放数据的记录数（预览页冲击对比：参与度现状 → 导入后）。 */
    fun engagementImportCount(): Int {
        val prepared = _uiState.value.prepared ?: return 0
        return prepared.data.songRecords.entries.count { (key, r) ->
            prepared.matchMap.containsKey(key) && (r.playCount > 0 || (r.lastPlayedAt ?: 0) > 0)
        }
    }

    /** 开始执行导入。 */
    fun startImport() {
        val prepared = _uiState.value.prepared ?: return
        if (importJob?.isActive == true) return
        _uiState.update { it.copy(step = Step.IMPORTING, progress = null) }
        importJob = viewModelScope.launch {
            try {
                val result = importer.execute(prepared, _uiState.value.options) { progress ->
                    _uiState.update { it.copy(progress = progress) }
                }
                _uiState.update { it.copy(step = Step.RESULT, result = result) }
            } catch (e: CancellationException) {
                // 用户取消：回到选项页（已写入分步保留，重跑幂等——参与度除外，累加语义）
                _uiState.update { it.copy(step = Step.OPTIONS, progress = null) }
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(step = Step.ERROR, errorMessage = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    /** 结束/返回来源页时重置全部状态。 */
    fun reset() {
        importJob?.cancel()
        importJob = null
        _uiState.value = UiState()
    }
}
