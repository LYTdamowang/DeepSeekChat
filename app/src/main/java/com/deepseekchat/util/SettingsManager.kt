package com.deepseekchat.util

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("deepseek_prefs", Context.MODE_PRIVATE)

    private val _themeModeFlow = MutableStateFlow(getThemeMode())
    val themeModeFlow: StateFlow<String> = _themeModeFlow.asStateFlow()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_THEME) _themeModeFlow.value = getThemeMode()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)

    fun setApiKey(key: String) = prefs.edit().putString(KEY_API_KEY, key).apply()

    fun getModel(): String = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setModel(model: String) = prefs.edit().putString(KEY_MODEL, model).apply()

    fun getReasoningEffort(): String? {
        val v = prefs.getString(KEY_REASONING, "off") ?: "off"
        return if (v == "off") null else v
    }

    fun setReasoningEffort(effort: String) = prefs.edit().putString(KEY_REASONING, effort).apply()

    fun getThemeMode(): String = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM

    fun setThemeMode(mode: String) = prefs.edit().putString(KEY_THEME, mode).apply()

    fun isAutoCompressEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_COMPRESS, true)

    fun setAutoCompressEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_AUTO_COMPRESS, enabled).apply()

    fun getAutoCompressThreshold(): Int = prefs.getInt(KEY_COMPRESS_THRESHOLD, DEFAULT_THRESHOLD)

    fun setAutoCompressThreshold(tokens: Int) = prefs.edit().putInt(KEY_COMPRESS_THRESHOLD, tokens).apply()

    fun getModelMaxTokens(): Int {
        return when (getModel()) {
            "deepseek-v4-flash" -> 1_000_000
            "deepseek-v4-pro" -> 1_000_000
            else -> 1_000_000
        }
    }

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_REASONING = "reasoning_effort"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_AUTO_COMPRESS = "auto_compress"
        private const val KEY_COMPRESS_THRESHOLD = "compress_threshold"
        const val DEFAULT_MODEL = "deepseek-v4-flash"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"
        const val DEFAULT_THRESHOLD = 1_000
    }
}
