package com.deepseekchat.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseekchat.data.local.entity.MessageEntity
import com.deepseekchat.ui.components.ChatInputBar
import com.deepseekchat.ui.components.ImportedWebSearchSection
import com.deepseekchat.ui.components.MessageBubble
import com.deepseekchat.ui.components.ReasoningSection
import com.deepseekchat.ui.components.StreamingCursor
import com.deepseekchat.ui.components.ThinkingIndicator
import com.deepseekchat.ui.components.TokenUsageBar
import com.deepseekchat.ui.compress.CompressDialog
import com.deepseekchat.ui.settings.SettingsViewModel
import com.deepseekchat.util.DocumentParser
import com.deepseekchat.util.importedSearchResultsForDisplay
import com.deepseekchat.util.splitImportedSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private suspend fun LazyListState.scrollToBottom(animated: Boolean = false) {
    snapshotFlow { layoutInfo.totalItemsCount }
        .first { it > 0 }
    withFrameNanos { }
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    if (animated) {
        animateScrollToItem(lastIndex)
    } else {
        scrollToItem(lastIndex)
    }
}

private fun LazyListState.isScrolledToBottom(): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
    return lastVisible.index >= info.totalItemsCount - 1 &&
        lastVisible.offset + lastVisible.size <= info.viewportEndOffset + 8
}

private enum class ChatScrollMode {
    FollowBottom,
    UserReading,
    SearchLocked
}

private data class BottomFollowSignal(
    val conversationId: String?,
    val isStreaming: Boolean,
    val messageCount: Int,
    val lastMessageId: String?,
    val lastMessageContentLength: Int,
    val lastReasoningLength: Int,
    val streamingContentLength: Int,
    val streamingReasoningLength: Int,
    val loadingContentCount: Int,
    val viewportHeight: Int,
    val scrollMode: ChatScrollMode,
    val searchTargetActive: Boolean,
    val isUserDragging: Boolean,
    val isListScrollInProgress: Boolean
) {
    val canFollowBottom: Boolean
        get() = conversationId != null &&
            scrollMode == ChatScrollMode.FollowBottom &&
            !searchTargetActive &&
            !isUserDragging &&
            !isListScrollInProgress
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    targetMessageId: String? = null,
    scrollRequestKey: Int = 0,
    onOpenSidebar: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latestState by rememberUpdatedState(state)
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var searchTargetActive by remember(conversationId, targetMessageId, scrollRequestKey) {
        mutableStateOf(targetMessageId != null)
    }
    var scrollMode by remember(conversationId, targetMessageId, scrollRequestKey) {
        mutableStateOf(if (targetMessageId != null) ChatScrollMode.SearchLocked else ChatScrollMode.FollowBottom)
    }
    var searchJumpCompleted by remember(conversationId, targetMessageId, scrollRequestKey) {
        mutableStateOf(false)
    }
    var programmaticScrollDepth by remember(conversationId, targetMessageId, scrollRequestKey) {
        mutableStateOf(0)
    }
    fun enterFollowBottom() {
        searchTargetActive = false
        searchJumpCompleted = false
        scrollMode = ChatScrollMode.FollowBottom
    }
    fun enterUserReading() {
        if (scrollMode != ChatScrollMode.SearchLocked) {
            scrollMode = ChatScrollMode.UserReading
        }
    }
    fun enterSearchLocked() {
        searchTargetActive = true
        scrollMode = ChatScrollMode.SearchLocked
    }
    fun releaseSearchLock() {
        searchTargetActive = false
        searchJumpCompleted = false
        if (scrollMode == ChatScrollMode.SearchLocked) {
            scrollMode = ChatScrollMode.UserReading
        }
    }
    suspend fun runProgrammaticScroll(block: suspend () -> Unit) {
        programmaticScrollDepth += 1
        try {
            block()
            withFrameNanos { }
            delay(50)
        } finally {
            programmaticScrollDepth = (programmaticScrollDepth - 1).coerceAtLeast(0)
        }
    }
    val latestScrollMode by rememberUpdatedState(scrollMode)
    val latestSearchTargetActive by rememberUpdatedState(searchTargetActive)
    val latestIsUserDragging by rememberUpdatedState(isUserDragging)
    val latestProgrammaticScrollActive by rememberUpdatedState(programmaticScrollDepth > 0)
    val shouldAutoFollowBottom = {
        latestState.conversationId != null &&
            latestScrollMode == ChatScrollMode.FollowBottom &&
            !latestSearchTargetActive &&
            !latestIsUserDragging &&
            !listState.isScrollInProgress
    }
    val showScrollButton by remember { derivedStateOf {
        !listState.isScrolledToBottom()
    } }
    val streamingId = state.streamingMessageId
    val streamingMessage = if (
        streamingId != null &&
        (state.streamingContent.isNotEmpty() || state.streamingReasoning.isNotEmpty()) &&
        state.messages.none { it.id == streamingId }
    ) {
        MessageEntity(
            id = streamingId,
            conversationId = state.conversationId ?: conversationId,
            role = "assistant",
            content = state.streamingContent,
            timestamp = state.streamingStartedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            isArchived = false,
            editHistory = "",
            editCount = 0,
            isHidden = false,
            parentId = state.messages.lastOrNull { !it.isHidden }?.id,
            contentId = "",
            preview = state.streamingContent,
            branchIndex = 0,
            storageType = "inline"
        )
    } else {
        null
    }
    val displayMessages = if (streamingMessage != null) {
        state.messages + streamingMessage
    } else {
        state.messages
    }
    val persistedMessageIds = remember(state.messages) { state.messages.map { it.id }.toSet() }
    val latestDisplayMessages by rememberUpdatedState(displayMessages)
    var editingMessage by remember { mutableStateOf<Pair<com.deepseekchat.data.local.entity.MessageEntity, String>?>(null) }
    var showCompressDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val composeScope = rememberCoroutineScope()

    // File picker launcher
    val docMimeTypes = arrayOf(
        "text/*",
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            composeScope.launch {
                val parser = DocumentParser()
                val mimeType = context.contentResolver.getType(uri) ?: "text/plain"
                withContext(Dispatchers.IO) {
                    parser.readText(context, uri, mimeType)
                }
                    .onSuccess { doc ->
                        viewModel.setAttachment(doc.fileName, doc.content)
                        doc.notice?.let { viewModel.setError(it) }
                    }
                    .onFailure { e ->
                        viewModel.setError("无法读取文件：${e.message}")
                    }
            }
        }
    }

    LaunchedEffect(conversationId, targetMessageId, scrollRequestKey) {
        viewModel.loadConversation(conversationId, targetMessageId)
    }

    LaunchedEffect(state.error, targetMessageId, scrollRequestKey) {
        if (targetMessageId != null && state.error?.contains("搜索结果") == true) {
            releaseSearchLock()
        }
    }

    LaunchedEffect(listState, searchTargetActive, searchJumpCompleted) {
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.isScrolledToBottom(),
                listState.layoutInfo.totalItemsCount
            )
        }.distinctUntilChanged().collect { (isScrolling, isAtBottom, totalItems) ->
            val isUserScroll = isScrolling && latestIsUserDragging
            if (searchTargetActive) {
                if (searchJumpCompleted && totalItems > 0 && isUserScroll && isAtBottom) {
                    enterFollowBottom()
                } else if (isUserScroll) {
                    enterSearchLocked()
                }
            } else {
                if (isUserScroll) {
                    if (isAtBottom) {
                        enterFollowBottom()
                    } else {
                        enterUserReading()
                    }
                } else if (!isScrolling && isAtBottom && !latestProgrammaticScrollActive) {
                    enterFollowBottom()
                }
            }
        }
    }

    LaunchedEffect(scrollMode) {
        viewModel.setStreamingViewportFollowsLatest(scrollMode == ChatScrollMode.FollowBottom)
    }

    LaunchedEffect(isUserDragging, searchTargetActive) {
        if (isUserDragging) {
            delay(40)
            if (searchTargetActive || !listState.isScrolledToBottom()) {
                if (searchTargetActive) {
                    enterSearchLocked()
                } else {
                    enterUserReading()
                }
            }
        }
    }

    LaunchedEffect(listState, displayMessages, searchTargetActive) {
        snapshotFlow {
            listState.isScrollInProgress to listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                displayMessages.getOrNull(item.index)?.id
            }
        }.distinctUntilChanged().collect { (isScrolling, ids) ->
            if (!isScrolling && ids.isNotEmpty()) {
                delay(if (searchTargetActive) 450 else 120)
                if (!listState.isScrollInProgress) {
                    val idsToLoad = if (searchTargetActive && targetMessageId != null) {
                        ids.filter { it == targetMessageId }
                    } else {
                        ids
                    }
                    if (idsToLoad.isNotEmpty()) {
                        viewModel.loadVisibleContent(idsToLoad)
                    }
                }
            }
        }
    }

    LaunchedEffect(state.conversationId, targetMessageId, scrollRequestKey) {
        if (state.conversationId == conversationId && targetMessageId == null) {
            enterFollowBottom()
            runProgrammaticScroll {
                listState.scrollToBottom()
            }
        }
    }

    LaunchedEffect(state.conversationId, targetMessageId, scrollRequestKey, searchTargetActive) {
        val target = targetMessageId ?: return@LaunchedEffect
        if (!searchTargetActive || state.conversationId != conversationId) return@LaunchedEffect
        enterSearchLocked()
        val targetIndex = withTimeoutOrNull(30_000) {
            snapshotFlow { latestState.messages.indexOfFirst { it.id == target } }
                .first { it >= 0 }
        } ?: run {
            releaseSearchLock()
            viewModel.setError("定位搜索结果超时，请稍后重试")
            return@LaunchedEffect
        }
        var targetVisible = false
        repeat(8) {
            withFrameNanos { }
            runProgrammaticScroll {
                listState.scrollToItem(targetIndex, 0)
            }
            withFrameNanos { }
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
            if (visible) {
                targetVisible = true
                return@repeat
            }
            delay(80)
        }
        if (!targetVisible) {
            releaseSearchLock()
            viewModel.setError("定位搜索结果失败，请稍后重试")
            return@LaunchedEffect
        }

        viewModel.loadVisibleContent(listOf(target))
        val settleStartedAt = System.currentTimeMillis()
        while (true) {
            val currentTargetIndex = latestState.messages.indexOfFirst { it.id == target }
            if (currentTargetIndex < 0) {
                releaseSearchLock()
                viewModel.setError("定位搜索结果失败，请稍后重试")
                return@LaunchedEffect
            }
            runProgrammaticScroll {
                listState.scrollToItem(currentTargetIndex, 0)
            }
            withFrameNanos { }

            val currentState = latestState
            val targetMessage = currentState.messages.getOrNull(currentTargetIndex)
            val targetContentStable = targetMessage == null ||
                targetMessage.storageType != "file" ||
                targetMessage.content != targetMessage.preview ||
                target !in currentState.loadingContentIds
            val elapsed = System.currentTimeMillis() - settleStartedAt
            val contextStable = !currentState.isContextLoading && targetContentStable
            if (contextStable && elapsed >= 600) {
                break
            }
            if (elapsed > 120_000) {
                break
            }
            delay(120)
        }
        val finalTargetIndex = latestState.messages.indexOfFirst { it.id == target }
        if (finalTargetIndex >= 0) {
            runProgrammaticScroll {
                listState.scrollToItem(finalTargetIndex, 0)
            }
        }
        searchJumpCompleted = true
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val currentState = latestState
            val currentMessages = latestDisplayMessages
            val lastVisibleMessage = currentMessages.lastOrNull { !it.isHidden }
            val lastVisibleMessageId = lastVisibleMessage?.id
            BottomFollowSignal(
                conversationId = currentState.conversationId,
                isStreaming = currentState.isStreaming,
                messageCount = currentMessages.size,
                lastMessageId = lastVisibleMessageId,
                lastMessageContentLength = lastVisibleMessage?.content?.length ?: 0,
                lastReasoningLength = lastVisibleMessageId
                    ?.let { currentState.reasoningByMessage[it]?.first?.length }
                    ?: 0,
                streamingContentLength = currentState.streamingContent.length,
                streamingReasoningLength = currentState.streamingReasoning.length,
                loadingContentCount = currentState.loadingContentIds.size,
                viewportHeight = listState.layoutInfo.viewportSize.height,
                scrollMode = scrollMode,
                searchTargetActive = searchTargetActive,
                isUserDragging = isUserDragging,
                isListScrollInProgress = listState.isScrollInProgress
            )
        }.distinctUntilChanged().collect { signal ->
            if (!signal.canFollowBottom) return@collect
            withFrameNanos { }
            if (!shouldAutoFollowBottom()) return@collect
            runProgrammaticScroll {
                listState.scrollToBottom()
            }
        }
    }

    // Keep the latest messages visible after the IME changes the available height.
    LaunchedEffect(listState.layoutInfo.viewportSize.height, scrollMode) {
        if (
            listState.layoutInfo.viewportSize.height > 0 &&
            shouldAutoFollowBottom()
        ) {
            delay(150)
            runProgrammaticScroll {
                listState.scrollToBottom()
            }
        }
    }

    // Edit dialog
    if (editingMessage != null) {
        val (msg, displayContent) = editingMessage!!
        var editText by remember(msg.id) { mutableStateOf(displayContent) }
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text("编辑消息") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.editAndResend(msg.id, editText)
                    editingMessage = null
                }) { Text("保存并重发") }
            },
            dismissButton = {
                TextButton(onClick = { editingMessage = null }) { Text("取消") }
            }
        )
    }

    // Compress dialog
    if (showCompressDialog) {
        CompressDialog(
            currentTokens = state.tokenCount,
            maxTokens = state.maxTokens,
            totalMessages = state.messages.size,
            onConfirm = { keepCount, customMemory ->
                viewModel.compressManually(keepCount, customMemory)
                showCompressDialog = false
            },
            onDismiss = { showCompressDialog = false }
        )
    }

    // Error dialog
    if (state.error != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("提示") },
            text = { Text(state.error ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    if (state.error?.contains("API Key") == true) {
                        onOpenSettings()
                    }
                    viewModel.clearError()
                }) {
                    Text(if (state.error?.contains("API Key") == true) "前往设置" else "确定")
                }
            }
        )
    }

    val settingsState = hiltViewModel<SettingsViewModel>().state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${settingsState.value.selectedModel}  |  推理: ${state.reasoningEffort}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenSidebar) {
                        Icon(Icons.Default.Menu, contentDescription = "会话列表")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
    ) { padding ->
        val focusManager = LocalFocusManager.current
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { focusManager.clearFocus() }
        ) {
            // Token usage bar with compress button
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TokenUsageBar(
                    currentTokens = state.tokenCount,
                    maxTokens = state.maxTokens,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFE57373))
                        .clickable { showCompressDialog = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Compress, null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("压缩记忆", style = MaterialTheme.typography.labelSmall,
                        color = Color.White)
                }
            }
            // Messages
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                state = listState,
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                itemsIndexed(
                    displayMessages,
                    key = { _, m -> m.id }
                ) { idx, message ->
                    // Skip hidden messages (document content) from display
                    if (!message.isHidden) {
                    val importedSearchSplit = if (message.role == "assistant") {
                        message.content.splitImportedSearchResults()
                    } else {
                        null
                    }
                    val displayContent = importedSearchSplit?.body ?: message.content
                    val webSearchArtifacts = state.webSearchArtifactsByMessage[message.id].orEmpty()
                    val webSearchText = (
                        listOfNotNull(importedSearchSplit?.search) +
                            webSearchArtifacts.map { it.content.importedSearchResultsForDisplay() }
                    )
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                        .takeIf { it.isNotBlank() }
                    val isWebSearchLoading = state.expandedWebSearchArtifacts.contains(message.id) &&
                        webSearchArtifacts.any { it.isPreview }
                    val isStreamingMessage = state.streamingMessageId == message.id &&
                        (state.isStreaming || message.id !in persistedMessageIds)

                    // Show reasoning above this AI message
                    if (message.role == "assistant") {
                        val r = if (isStreamingMessage && state.streamingReasoning.isNotEmpty()) {
                            state.streamingReasoning to state.thinkingDurationMs
                        } else {
                            state.reasoningByMessage[message.id]
                        }
                        if (r != null) {
                            val isCollapsed = state.collapsedReasonings.contains(message.id)
                            ReasoningSection(
                                reasoningText = r.first,
                                isExpanded = !isCollapsed,
                                isStreaming = isStreamingMessage,
                                thinkingDurationMs = r.second,
                                onToggle = { viewModel.toggleReasoningCollapsed(message.id) },
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }

                    val editHistory = ChatViewModel.parseEditHistory(message.editHistory)
                    val verIdx = message.branchIndex
                    MessageBubble(
                        role = message.role,
                        content = displayContent,
                        isStreaming = isStreamingMessage,
                        timestamp = message.timestamp,
                        editHistory = editHistory,
                        editCount = message.editCount,
                        versionIndex = verIdx,
                        onPrevVersion = if (message.editCount > 0) {
                            { viewModel.switchMessageVersion(message.id, -1) }
                        } else null,
                        onNextVersion = if (message.editCount > 0) {
                            { viewModel.switchMessageVersion(message.id, 1) }
                        } else null,
                        onCopyFull = {
                            clipboardManager.setText(AnnotatedString(displayContent))
                        },
                        onEditRequest = if (message.role == "user" && !message.isArchived) {{
                            editingMessage = Pair(message, message.content)
                        }} else null,
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .then(if (message.isArchived) Modifier.alpha(0.45f) else Modifier)
                    )
                    if (message.role == "assistant" && webSearchText != null) {
                        ImportedWebSearchSection(
                            searchText = webSearchText,
                            isExpanded = state.expandedWebSearchArtifacts.contains(message.id),
                            isLoadingFullContent = isWebSearchLoading,
                            onToggle = { viewModel.toggleWebSearchArtifactExpanded(message.id) }
                        )
                    }
                    } // end if (!message.isHidden)
                }

                // Waiting indicator
                if (state.isStreaming && state.streamingReasoning.isEmpty() && state.streamingContent.isEmpty()) {
                    item(key = "waiting") {
                        val transition = rememberInfiniteTransition(label = "waiting")
                        val chars = listOf("等", "待", "回", "复", ".", ".", ".")
                        Row(modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)) {
                            chars.forEachIndexed { i, c ->
                                val alpha by transition.animateFloat(
                                    initialValue = 0.15f, targetValue = 0.85f,
                                    animationSpec = infiniteRepeatable(
                                        tween(300, delayMillis = i * 100),
                                        RepeatMode.Reverse
                                    )
                                )
                                Text(
                                    c,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                                )
                            }
                        }
                    }
                }

                // Streaming cursor
                if (state.isStreaming) {
                    item(key = "cursor") {
                        StreamingCursor(
                            modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                        )
                    }
                }

                item(key = "bottomAnchor") {
                    Spacer(Modifier.height(1.dp))
                }
            }

            // Reasoning effort buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                val efforts = listOf(
                    "high" to "深度思考",
                    "max" to "超级思考"
                )
                efforts.forEach { (key, label) ->
                    val selected = state.reasoningEffort == key
                    Surface(
                        onClick = { viewModel.setReasoningEffort(if (selected) "off" else key) },
                        modifier = Modifier.padding(end = 8.dp).width(110.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        border = if (selected)
                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        else
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (key == "high") "𖦹" else "✦",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (selected) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "✓",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // Input bar
            ChatInputBar(
                value = inputText,
                onValueChange = { inputText = it },
                onSend = {
                    enterFollowBottom()
                    viewModel.sendMessage(inputText)
                    inputText = ""
                    composeScope.launch {
                        runProgrammaticScroll {
                            listState.scrollToBottom(animated = true)
                        }
                    }
                },
                onStop = { viewModel.stopStreaming() },
                onAttach = { filePickerLauncher.launch(docMimeTypes) },
                isStreaming = state.isStreaming,
                enabled = state.conversationId != null,
                attachedFileName = state.attachedFileName,
                onRemoveAttachment = { viewModel.clearAttachment() },
                onTap = {
                    enterFollowBottom()
                    composeScope.launch {
                        runProgrammaticScroll {
                            listState.scrollToBottom(animated = true)
                        }
                    }
                },
                onFocused = {
                    enterFollowBottom()
                    composeScope.launch {
                        delay(120)
                        runProgrammaticScroll {
                            listState.scrollToBottom(animated = true)
                        }
                    }
                }
            )
        }
        if (state.isCompressing) {
            CompressingOverlay(
                title = state.compressionTitle,
                detail = state.compressionDetail
            )
        }
        // Scroll-to-bottom button when user scrolls up
        if (showScrollButton) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 80.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape
                    )
                    .clickable {
                        enterFollowBottom()
                        composeScope.launch {
                            runProgrammaticScroll {
                                listState.scrollToBottom(animated = true)
                            }
                        }
                    }
                    .size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    "下滑",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
    }
}
