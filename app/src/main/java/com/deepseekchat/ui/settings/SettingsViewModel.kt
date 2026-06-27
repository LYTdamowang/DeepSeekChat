package com.deepseekchat.ui.settings

import androidx.lifecycle.ViewModel
import com.deepseekchat.util.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SettingsState(
    val apiKey: String = "",
    val selectedModel: String = SettingsManager.DEFAULT_MODEL,
    val reasoningEffort: String = "off",
    val themeMode: String = SettingsManager.THEME_SYSTEM,
    val autoCompress: Boolean = true,
    val compressThreshold: Int = SettingsManager.DEFAULT_THRESHOLD
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsManager: SettingsManager
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState(
        apiKey = settingsManager.getApiKey() ?: "",
        selectedModel = settingsManager.getModel(),
        reasoningEffort = settingsManager.getReasoningEffort() ?: "off",
        themeMode = settingsManager.getThemeMode(),
        autoCompress = settingsManager.isAutoCompressEnabled(),
        compressThreshold = settingsManager.getAutoCompressThreshold()
    ))
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    fun updateApiKey(key: String) {
        settingsManager.setApiKey(key)
        _state.update { it.copy(apiKey = key) }
    }

    fun updateModel(model: String) {
        settingsManager.setModel(model)
        _state.update { it.copy(selectedModel = model) }
    }

    fun updateReasoningEffort(effort: String) {
        settingsManager.setReasoningEffort(effort)
        _state.update { it.copy(reasoningEffort = effort) }
    }

    fun updateThemeMode(mode: String) {
        settingsManager.setThemeMode(mode)
        _state.update { it.copy(themeMode = mode) }
    }

    fun updateAutoCompress(enabled: Boolean) {
        settingsManager.setAutoCompressEnabled(enabled)
        _state.update { it.copy(autoCompress = enabled) }
    }

    fun updateCompressThreshold(threshold: Int) {
        settingsManager.setAutoCompressThreshold(threshold)
        _state.update { it.copy(compressThreshold = threshold) }
    }

    fun getSettingsManager(): SettingsManager = settingsManager
}
