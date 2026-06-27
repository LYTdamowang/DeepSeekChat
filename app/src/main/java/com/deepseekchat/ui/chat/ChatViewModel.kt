package com.deepseekchat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepseekchat.data.local.entity.MessageEntity
import com.deepseekchat.data.remote.MessageDto
import com.deepseekchat.data.remote.StreamEvent
import com.deepseekchat.data.repository.AutoCompressStatus
import com.deepseekchat.data.repository.ChatRepository
import com.deepseekchat.data.repository.MessageArtifact
import com.deepseekchat.data.repository.SessionRepository
import com.deepseekchat.util.ContextCompressor
import com.deepseekchat.util.ReasoningStorage
import com.deepseekchat.util.SettingsManager
import com.deepseekchat.util.TokenCounter
import com.deepseekchat.util.withoutImportedSearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ChatState(
    val conversationId: String? = null,
    val messages: List<MessageEntity> = emptyList(),
    val isStreaming: Boolean = false,
    val streamingMessageId: String? = null,
    val streamingStartedAt: Long = 0L,
    val streamingContent: String = "",
    val streamingReasoning: String = "",
    val reasoningByMessage: Map<String, Pair<String, Long>> = emptyMap(),
    val collapsedReasonings: Set<String> = emptySet(),
    val webSearchArtifactsByMessage: Map<String, List<MessageArtifact>> = emptyMap(),
    val expandedWebSearchArtifacts: Set<String> = emptySet(),
    val thinkingDurationMs: Long = 0,
    val error: String? = null,
    val tokenCount: Int = 0,
    val maxTokens: Int = 1_000_000,
    val isCompressing: Boolean = false,
    val compressionTitle: String = "压缩中...",
    val compressionDetail: String = "",
    val attachedFileName: String? = null,
    val attachedContent: String? = null,
    val reasoningEffort: String = "off",
    val isContextLoading: Boolean = false,
    val loadingContentIds: Set<String> = emptySet()
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val sessionRepository: SessionRepository,
    private val settingsManager: SettingsManager,
    private val tokenCounter: TokenCounter,
    private val contextCompressor: ContextCompressor,
    private val reasoningStorage: ReasoningStorage
) : ViewModel() {

    private val _state = MutableStateFlow(
        ChatState(reasoningEffort = settingsManager.getReasoningEffort() ?: "off")
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var streamingJob: Job? = null
    private var timerJob: Job? = null
    private var messageCollectionJob: Job? = null
    private var tokenCountJob: Job? = null
    private var tokenCountGeneration: Int = 0
    private var compressionNoticeJob: Job? = null
    @Volatile
    private var streamingViewportFollowsLatest: Boolean = true

    private val reasoningCache: MutableMap<String, MutableMap<String, Pair<String, Long>>> = mutableMapOf()
    private val missingContentWarnings: MutableSet<String> = mutableSetOf()

    init {
        viewModelScope.launch {
            sessionRepository.cleanupStorageGarbage()
        }
    }

    fun setStreamingViewportFollowsLatest(followsLatest: Boolean) {
        streamingViewportFollowsLatest = followsLatest
    }

    fun loadConversation(conversationId: String, targetMessageId: String? = null) {
        messageCollectionJob?.cancel()
        tokenCountJob?.cancel()
        tokenCountGeneration++
        val prevId = _state.value.conversationId
        val prevMap = _state.value.reasoningByMessage
        if (prevId != null && prevMap.isNotEmpty()) reasoningCache[prevId] = prevMap.toMutableMap()

        val cached = reasoningCache[conversationId] ?: reasoningStorage
            .loadConversation(conversationId)
            .toMutableMap()
            .also { reasoningCache[conversationId] = it }
        _state.update {
            it.copy(
                conversationId = conversationId,
                error = null,
                messages = emptyList(),
                tokenCount = 0,
                reasoningByMessage = cached,
                webSearchArtifactsByMessage = emptyMap(),
                expandedWebSearchArtifacts = emptySet(),
                isContextLoading = true,
                loadingContentIds = emptySet()
            )
        }
        messageCollectionJob = viewModelScope.launch {
            try {
                if (sessionRepository.getConversation(conversationId) == null) {
                    sessionRepository.createConversationWithId(
                        id = conversationId,
                        title = "新对话",
                        model = settingsManager.getModel()
                    )
                }
                sessionRepository.cleanupConversationGarbage(conversationId)
                if (!targetMessageId.isNullOrBlank()) {
                    val activated = sessionRepository.activatePathToMessage(conversationId, targetMessageId)
                    if (!activated) {
                        _state.update { it.copy(error = "未找到搜索结果对应的消息，可能已被删除或不在当前可见分支") }
                    }
                }
                sessionRepository.getMessagesLite(conversationId).collect { messages ->
                    attachPendingReasoning(conversationId, messages)
                    val webSearchArtifacts = sessionRepository.getWebSearchArtifacts(
                        conversationId = conversationId,
                        messageIds = messages.map { it.id }
                    )
                    val currentState = _state.value
                    val completedStreamingId = currentState.streamingMessageId
                    val completedStreamingSaved = completedStreamingId != null &&
                        messages.any { it.id == completedStreamingId }
                    val messagesForDisplay = if (
                        completedStreamingSaved &&
                        currentState.streamingContent.isNotEmpty()
                    ) {
                        messages.map { message ->
                            if (
                                message.id == completedStreamingId &&
                                message.storageType == "file" &&
                                message.content == message.preview
                            ) {
                                message.copy(content = currentState.streamingContent)
                            } else {
                                message
                            }
                        }
                    } else {
                        messages
                    }
                    val generation = ++tokenCountGeneration
                    _state.update {
                        val next = it.copy(
                            messages = messagesForDisplay,
                            webSearchArtifactsByMessage = webSearchArtifacts,
                            maxTokens = settingsManager.getModelMaxTokens(),
                            isContextLoading = true
                        )
                        if (!next.isStreaming && completedStreamingSaved) {
                            next.copy(
                                streamingMessageId = null,
                                streamingStartedAt = 0L,
                                streamingContent = "",
                                streamingReasoning = ""
                            )
                        } else {
                            next
                        }
                    }
                    tokenCountJob?.cancel()
                    tokenCountJob = launch {
                        try {
                            val fullForTokens = runCatching {
                                sessionRepository.getActiveMessagesFull(conversationId)
                            }.getOrElse { messages }
                            if (_state.value.conversationId != conversationId) return@launch
                            _state.update {
                                it.copy(
                                    tokenCount = tokenCounter.countMessages(
                                        fullForTokens.map { m ->
                                            MessageDto(m.role, m.content.withoutImportedSearchResults())
                                        }
                                    ),
                                    maxTokens = settingsManager.getModelMaxTokens()
                                )
                            }
                        } finally {
                            if (_state.value.conversationId == conversationId && tokenCountGeneration == generation) {
                                _state.update { it.copy(isContextLoading = false) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(error = "加载消息失败：${e.message}", isContextLoading = false) }
            }
        }
    }

    fun loadVisibleContent(messageIds: List<String>) {
        val conversationId = _state.value.conversationId ?: return
        val idsToLoad = messageIds.distinct().filter { id ->
            val currentState = _state.value
            val current = currentState.messages.firstOrNull { it.id == id } ?: return@filter false
            current.storageType == "file" &&
                current.content == current.preview &&
                id !in currentState.loadingContentIds
        }
        if (idsToLoad.isEmpty()) return
        val idsToLoadSet = idsToLoad.toSet()
        _state.update { it.copy(loadingContentIds = it.loadingContentIds + idsToLoadSet) }
        viewModelScope.launch {
            try {
                val loadedContents = mutableMapOf<String, String>()
                idsToLoad.forEach { id ->
                    val current = _state.value.messages.firstOrNull { it.id == id } ?: return@forEach
                    if (current.storageType != "file" || current.content != current.preview) return@forEach
                    val full = sessionRepository.loadFullContent(conversationId, id)
                    if (full == null) {
                        val warningKey = "$conversationId:$id"
                        if (missingContentWarnings.add(warningKey)) {
                            _state.update { it.copy(error = "该内容文件缺失，可能已被系统或用户清理") }
                        }
                        return@forEach
                    }
                    if (_state.value.conversationId != conversationId) return@forEach
                    loadedContents[id] = full
                }
                if (loadedContents.isNotEmpty() && _state.value.conversationId == conversationId) {
                    _state.update { state ->
                        state.copy(
                            messages = state.messages.map { message ->
                                loadedContents[message.id]?.let { full -> message.copy(content = full) } ?: message
                            }
                        )
                    }
                }
            } finally {
                if (_state.value.conversationId == conversationId) {
                    _state.update { it.copy(loadingContentIds = it.loadingContentIds - idsToLoadSet) }
                }
            }
        }
    }

    fun sendMessage(content: String) {
        sendMessageInternal(content)
    }

    fun setAttachment(fileName: String, content: String) {
        _state.update { it.copy(attachedFileName = fileName, attachedContent = content) }
    }

    fun clearAttachment() {
        _state.update { it.copy(attachedFileName = null, attachedContent = null) }
    }

    fun stopStreaming() {
        chatRepository.cancelStreaming()
        timerJob?.cancel()
        streamingJob?.cancel()
        compressionNoticeJob?.cancel()
        val partial = _state.value.streamingContent
        val cid = _state.value.conversationId
        val assistantMessageId = _state.value.streamingMessageId ?: UUID.randomUUID().toString()
        val willSaveStoppedMessage = partial.isNotBlank() || _state.value.streamingReasoning.isNotBlank()
        if (cid != null) {
            viewModelScope.launch {
                if (partial.isNotBlank()) {
                    sessionRepository.saveMessage(
                        conversationId = cid,
                        role = "assistant",
                        content = "$partial\n\n[已停止]",
                        messageId = assistantMessageId
                    )
                } else if (_state.value.streamingReasoning.isNotBlank()) {
                    sessionRepository.saveMessage(
                        conversationId = cid,
                        role = "assistant",
                        content = "[已停止]",
                        messageId = assistantMessageId
                    )
                }
            }
        }
        _state.update {
            if (willSaveStoppedMessage) {
                it.copy(isStreaming = false, isCompressing = false)
            } else {
                it.copy(
                    isStreaming = false,
                    streamingMessageId = null,
                    streamingStartedAt = 0L,
                    streamingContent = "",
                    streamingReasoning = "",
                    isCompressing = false
                )
            }
        }
    }

    fun setReasoningEffort(effort: String) {
        _state.update { it.copy(reasoningEffort = effort) }
        settingsManager.setReasoningEffort(effort)
    }

    fun toggleReasoningCollapsed(messageId: String) {
        _state.update {
            val set = it.collapsedReasonings.toMutableSet()
            if (set.contains(messageId)) set.remove(messageId) else set.add(messageId)
            it.copy(collapsedReasonings = set)
        }
    }

    fun toggleWebSearchArtifactExpanded(messageId: String) {
        val wasExpanded = _state.value.expandedWebSearchArtifacts.contains(messageId)
        _state.update {
            val set = it.expandedWebSearchArtifacts.toMutableSet()
            if (set.contains(messageId)) set.remove(messageId) else set.add(messageId)
            it.copy(expandedWebSearchArtifacts = set)
        }
        if (!wasExpanded) {
            loadFullWebSearchArtifacts(messageId)
        }
    }

    private fun loadFullWebSearchArtifacts(messageId: String) {
        val conversationId = _state.value.conversationId ?: return
        val previews = _state.value.webSearchArtifactsByMessage[messageId]
            .orEmpty()
            .filter { it.isPreview }
        if (previews.isEmpty()) return
        viewModelScope.launch {
            previews.forEach { artifact ->
                val full = sessionRepository.loadWebSearchArtifactContent(conversationId, artifact.id)
                    ?: return@forEach
                if (_state.value.conversationId != conversationId) return@forEach
                _state.update { state ->
                    val updated = state.webSearchArtifactsByMessage.toMutableMap()
                    updated[messageId] = updated[messageId].orEmpty().map {
                        if (it.id == full.id) full else it
                    }
                    state.copy(webSearchArtifactsByMessage = updated)
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun setError(msg: String) {
        _state.update { it.copy(error = msg) }
    }

    suspend fun loadEditHistory(messageId: String): String? = sessionRepository.getEditHistory(messageId)

    fun switchMessageVersion(messageId: String, delta: Int) {
        val conversationId = _state.value.conversationId ?: return
        viewModelScope.launch {
            sessionRepository.switchMessageVersion(conversationId, messageId, delta)
        }
    }

    fun editAndResend(messageId: String, newContent: String) {
        val conversationId = _state.value.conversationId ?: return
        if (newContent.isBlank()) return
        cancelStreamingState()
        viewModelScope.launch {
            try {
                sessionRepository.createBranchFromMessage(conversationId, messageId, newContent)
                startStreamingRequest(conversationId, skipUserSave = true, content = "")
            } catch (e: Exception) {
                _state.update { it.copy(error = "修改失败：${e.message}") }
            }
        }
    }

    fun compressManually(keepCount: Int, customMemory: String) {
        val conversationId = _state.value.conversationId ?: return
        compressionNoticeJob?.cancel()
        _state.update {
            it.copy(
                isCompressing = true,
                compressionTitle = "正在压缩上下文",
                compressionDetail = "正在生成摘要。压缩成功后保留最近消息和摘要，失败会保留原消息。"
            )
        }
        viewModelScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                val activeMessages = sessionRepository.getActiveMessagesFull(conversationId)
                val allDtos = activeMessages.map { MessageDto(it.role, it.content.withoutImportedSearchResults()) }
                contextCompressor.compressManually(
                    conversationId, allDtos, activeMessages, keepCount, customMemory
                )
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < 600) kotlinx.coroutines.delay(600 - elapsed)
                _state.update { it.copy(isCompressing = false, compressionDetail = "") }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isCompressing = false,
                        compressionDetail = "",
                        error = "压缩失败，原消息已保留：${e.message}"
                    )
                }
            }
        }
    }

    private fun sendMessageInternal(content: String) {
        val conversationId = _state.value.conversationId ?: return
        if (content.isBlank() || _state.value.isStreaming) return
        if (settingsManager.getApiKey().isNullOrBlank()) {
            _state.update { it.copy(error = "请先在设置中配置 API Key") }
            return
        }
        startStreamingRequest(conversationId, skipUserSave = false, content = content)
    }

    private fun startStreamingRequest(conversationId: String, skipUserSave: Boolean, content: String) {
        val startTime = System.currentTimeMillis()
        val assistantMessageId = UUID.randomUUID().toString()
        streamingViewportFollowsLatest = true
        _state.update {
            it.copy(
                isStreaming = true,
                streamingMessageId = assistantMessageId,
                streamingStartedAt = startTime,
                streamingContent = "",
                streamingReasoning = "",
                thinkingDurationMs = 0,
                error = null
            )
        }
        timerJob = viewModelScope.launch {
            while (true) {
                _state.update { it.copy(thinkingDurationMs = System.currentTimeMillis() - startTime) }
                kotlinx.coroutines.delay(250)
            }
        }
        streamingJob = viewModelScope.launch {
            val attachFile = _state.value.attachedFileName
            val attachContent = _state.value.attachedContent
            if (!skipUserSave && attachContent != null) {
                sessionRepository.saveMessage(
                    conversationId,
                    "user",
                    "📎 $attachFile\n---\n$attachContent\n---",
                    isHidden = true
                )
                clearAttachment()
            }
            var keepStreamingPreviewUntilSaved = false
            try {
                val result = if (skipUserSave) {
                    chatRepository.resendAfterEdit(
                        conversationId = conversationId,
                        assistantMessageId = assistantMessageId,
                        onStreamEvent = { handleStreamEvent(it, startTime) },
                        onAutoCompressStatus = { handleAutoCompressStatus(it) }
                    )
                } else {
                    chatRepository.sendMessage(
                        conversationId = conversationId,
                        userMessage = content,
                        assistantMessageId = assistantMessageId,
                        onStreamEvent = { handleStreamEvent(it, startTime) },
                        onAutoCompressStatus = { handleAutoCompressStatus(it) }
                    )
                }
                appendStreamingBuffers(reasoningBuffer, contentBuffer, force = true)
                keepStreamingPreviewUntilSaved = result.isSuccess
                result.onFailure { e ->
                    _state.update { it.copy(error = e.message ?: "请求失败") }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(error = e.message ?: "请求失败") }
            } finally {
                timerJob?.cancel()
                reasoningBuffer.clear()
                contentBuffer.clear()
                _state.update {
                    val savedMessageArrived = it.messages.any { message -> message.id == assistantMessageId }
                    if (keepStreamingPreviewUntilSaved && !savedMessageArrived) {
                        it.copy(isStreaming = false, isCompressing = false)
                    } else {
                        it.copy(
                            isStreaming = false,
                            streamingMessageId = null,
                            streamingStartedAt = 0L,
                            streamingContent = "",
                            streamingReasoning = "",
                            isCompressing = false
                        )
                    }
                }
                refreshTokenCount(conversationId)
            }
        }
    }

    private fun handleAutoCompressStatus(status: AutoCompressStatus) {
        when (status) {
            AutoCompressStatus.Started -> {
                compressionNoticeJob?.cancel()
                _state.update {
                    it.copy(
                        isCompressing = true,
                        compressionTitle = "正在自动压缩上下文",
                        compressionDetail = "上下文接近上限，正在把较早消息整理成摘要。完成后会继续发送。"
                    )
                }
            }
            AutoCompressStatus.Succeeded -> {
                _state.update {
                    it.copy(
                        isCompressing = true,
                        compressionTitle = "已自动压缩上下文",
                        compressionDetail = "摘要已保存，正在继续发送。"
                    )
                }
                compressionNoticeJob?.cancel()
                compressionNoticeJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(700)
                    _state.update { it.copy(isCompressing = false, compressionDetail = "") }
                }
            }
            AutoCompressStatus.Failed -> {
                compressionNoticeJob?.cancel()
                _state.update { it.copy(isCompressing = false, compressionDetail = "") }
            }
        }
    }

    private val reasoningBuffer = StringBuilder()
    private val contentBuffer = StringBuilder()
    private var flushPending = false

    private fun handleStreamEvent(event: StreamEvent, startTime: Long) {
        when (event) {
            is StreamEvent.Reasoning -> reasoningBuffer.append(event.content)
            is StreamEvent.Content -> {
                if (_state.value.streamingContent.isBlank() && contentBuffer.isEmpty()) {
                    timerJob?.cancel()
                    _state.update { it.copy(thinkingDurationMs = System.currentTimeMillis() - startTime) }
                }
                contentBuffer.append(event.token)
            }
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushPending) return
        flushPending = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(if (streamingViewportFollowsLatest) 10L else 140L)
            flushPending = false
            appendStreamingBuffers(reasoningBuffer, contentBuffer)
        }
    }

    private fun appendStreamingBuffers(
        reasoningBuf: StringBuilder,
        contentBuf: StringBuilder,
        force: Boolean = false
    ) {
        val reasoning = reasoningBuf.toString()
        val content = contentBuf.toString()
        if (!force && reasoning.isEmpty() && content.isEmpty()) return
        reasoningBuf.clear()
        contentBuf.clear()
        _state.update {
            it.copy(
                streamingReasoning = it.streamingReasoning + reasoning,
                streamingContent = it.streamingContent + content
            )
        }
    }

    private fun cancelStreamingState() {
        chatRepository.cancelStreaming()
        timerJob?.cancel()
        streamingJob?.cancel()
        reasoningBuffer.clear()
        contentBuffer.clear()
        _state.update {
            it.copy(
                isStreaming = false,
                streamingContent = "",
                streamingReasoning = "",
                thinkingDurationMs = 0,
                isCompressing = false
            )
        }
    }

    private fun attachPendingReasoning(conversationId: String, messages: List<MessageEntity>) {
        val pending = _state.value.streamingReasoning.ifBlank { null } ?: return
        val lastAiId = messages.lastOrNull { it.role == "assistant" && !it.isHidden }?.id ?: return
        if (_state.value.reasoningByMessage.containsKey(lastAiId)) return
        val entry = pending to _state.value.thinkingDurationMs
        val next = _state.value.reasoningByMessage.toMutableMap().apply { put(lastAiId, entry) }
        reasoningCache[conversationId] = next
        reasoningStorage.saveMap(conversationId, next)
        _state.update { it.copy(reasoningByMessage = next, streamingReasoning = "") }
    }

    private fun refreshTokenCount(conversationId: String) {
        viewModelScope.launch {
            runCatching {
                val messages = sessionRepository.getActiveMessagesFull(conversationId)
                _state.update {
                    it.copy(
                        tokenCount = tokenCounter.countMessages(
                            messages.map { m -> MessageDto(m.role, m.content.withoutImportedSearchResults()) }
                        ),
                        maxTokens = settingsManager.getModelMaxTokens()
                    )
                }
            }
        }
    }

    data class EditEntry(val userContent: String, val aiContent: String, val thinkingMs: Long)

    companion object {
        fun parseEditHistory(history: String): List<EditEntry> {
            if (history.isBlank()) return emptyList()
            return history.split("\n<|SEP|>\n").map { entry ->
                val parts = entry.split("|||")
                EditEntry(
                    userContent = parts.getOrElse(0) { "" },
                    aiContent = parts.getOrElse(1) { "" },
                    thinkingMs = parts.getOrElse(2) { "0" }.toLongOrNull() ?: 0L
                )
            }
        }
    }
}
