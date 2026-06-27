package com.deepseekchat

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseekchat.ui.navigation.AppNavigation
import com.deepseekchat.ui.settings.SettingsViewModel
import com.deepseekchat.ui.theme.DeepSeekTheme
import com.deepseekchat.util.SettingsManager

@Composable
fun DeepSeekChatApp() {
    val settingsState by hiltViewModel<SettingsViewModel>().state.collectAsStateWithLifecycle()

    val themeMode = settingsState.themeMode
    val darkTheme = when (themeMode) {
        SettingsManager.THEME_LIGHT -> false
        SettingsManager.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    DeepSeekTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            AppNavigation()
        }
    }
}
