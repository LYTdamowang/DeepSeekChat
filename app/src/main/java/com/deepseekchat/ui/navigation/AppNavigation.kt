package com.deepseekchat.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseekchat.ui.chat.ChatScreen
import com.deepseekchat.ui.search.SearchScreen
import com.deepseekchat.ui.settings.SettingsScreen
import com.deepseekchat.ui.sidebar.SidebarDrawer
import com.deepseekchat.ui.sidebar.SidebarViewModel

private sealed class Screen {
    data class Chat(
        val id: String,
        val targetMessageId: String? = null,
        val scrollRequestKey: Int = 0
    ) : Screen()
    object Search : Screen()
    object Settings : Screen()
}

@Composable
fun AppNavigation() {
    val sidebarViewModel: SidebarViewModel = hiltViewModel()
    val sidebarState by sidebarViewModel.state.collectAsStateWithLifecycle()

    var activeConversationId by rememberSaveable { mutableStateOf("") }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var searchScrollRequestKey by rememberSaveable { mutableStateOf(0) }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Chat("")) }

    // On first load: pick the latest conversation, or create a new one if none exist
    LaunchedEffect(sidebarState.isLoading, sidebarState.conversations) {
        if (!initialized && !sidebarState.isLoading) {
            initialized = true
            if (sidebarState.conversations.isNotEmpty()) {
                val id = sidebarState.conversations.first().id
                activeConversationId = id
                currentScreen = Screen.Chat(id)
            } else {
                sidebarViewModel.createNewConversation { newId ->
                    activeConversationId = newId
                    currentScreen = Screen.Chat(newId)
                }
            }
        }
    }

    if (activeConversationId.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("加载中...", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    SidebarDrawer(
        currentConversationId = activeConversationId,
        onConversationSelected = { id ->
            activeConversationId = id
            currentScreen = Screen.Chat(id)
        },
        onNewConversation = { id ->
            activeConversationId = id
            currentScreen = Screen.Chat(id)
        },
        onConversationDeleted = { deletedId ->
            if (deletedId == activeConversationId) {
                val remaining = sidebarState.conversations.filter { it.id != deletedId }
                if (remaining.isNotEmpty()) {
                    activeConversationId = remaining.first().id
                    currentScreen = Screen.Chat(remaining.first().id)
                } else {
                    sidebarViewModel.createNewConversation { newId ->
                        activeConversationId = newId
                        currentScreen = Screen.Chat(newId)
                    }
                }
            }
        },
        onNewCharacter = { id ->
            activeConversationId = id
            currentScreen = Screen.Chat(id)
        },
        onSearch = { currentScreen = Screen.Search },
        onSettings = { currentScreen = Screen.Settings }
    ) { openDrawer ->
        when (val screen = currentScreen) {
            is Screen.Chat -> ChatScreen(
                conversationId = screen.id,
                targetMessageId = screen.targetMessageId,
                scrollRequestKey = screen.scrollRequestKey,
                onOpenSidebar = openDrawer,
                onOpenSettings = { currentScreen = Screen.Settings }
            )
            is Screen.Search -> SearchScreen(
                onBack = { currentScreen = Screen.Chat(activeConversationId) },
                onNavigateToMessage = { conversationId, messageId ->
                    searchScrollRequestKey += 1
                    activeConversationId = conversationId
                    currentScreen = Screen.Chat(
                        id = conversationId,
                        targetMessageId = messageId,
                        scrollRequestKey = searchScrollRequestKey
                    )
                }
            )
            is Screen.Settings -> SettingsScreen(
                onBack = { currentScreen = Screen.Chat(activeConversationId) }
            )
        }
    }
}
