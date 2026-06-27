package com.deepseekchat.data.repository

import com.deepseekchat.data.local.entity.ConversationEntity
import com.deepseekchat.data.local.entity.MessageEntity
import com.deepseekchat.data.remote.ChatRequest
import com.deepseekchat.data.remote.DeepSeekApi
import com.deepseekchat.data.remote.MessageDto
import com.deepseekchat.data.remote.StreamEvent
import com.deepseekchat.data.remote.ThinkingConfig
import com.deepseekchat.util.ContextCompressor
import com.deepseekchat.util.SettingsManager
import com.deepseekchat.util.TokenCounter
import com.deepseekchat.util.withoutImportedSearchResults
import kotlinx.coroutines.flow.collect
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class AutoCompressStatus {
    Started,
    Succeeded,
    Failed
}

@Singleton
class ChatRepository @Inject constructor(
    private val api: DeepSeekApi,
    private val sessionRepository: SessionRepository,
    private val settingsManager: SettingsManager,
    private val tokenCounter: TokenCounter,
    private val contextCompressor: ContextCompressor
) {
    @Volatile
    private var userCancelled = false

    fun cancelStreaming() {
        userCancelled = true
        api.cancelStreaming()
    }

    suspend fun sendMessage(
        conversationId: String,
        userMessage: String,
        assistantMessageId: String? = null,
        onStreamEvent: suspend (StreamEvent) -> Unit,
        onAutoCompressStatus: suspend (AutoCompressStatus) -> Unit = {}
    ): Result<String> {
        return sendInternal(
            conversationId = conversationId,
            userMessage = userMessage,
            assistantMessageId = assistantMessageId,
            onStreamEvent = onStreamEvent,
            skipSaveUser = false,
            onAutoCompressStatus = onAutoCompressStatus
        )
    }

    suspend fun resendAfterEdit(
        conversationId: String,
        assistantMessageId: String? = null,
        onStreamEvent: suspend (StreamEvent) -> Unit,
        onAutoCompressStatus: suspend (AutoCompressStatus) -> Unit = {}
    ): Result<String> {
        return sendInternal(
            conversationId = conversationId,
            userMessage = "",
            assistantMessageId = assistantMessageId,
            onStreamEvent = onStreamEvent,
            skipSaveUser = true,
            onAutoCompressStatus = onAutoCompressStatus
        )
    }

    private suspend fun sendInternal(
        conversationId: String,
        userMessage: String,
        assistantMessageId: String?,
        onStreamEvent: suspend (StreamEvent) -> Unit,
        skipSaveUser: Boolean,
        onAutoCompressStatus: suspend (AutoCompressStatus) -> Unit
    ): Result<String> {
        val contentBuilder = StringBuilder()
        return try {
            userCancelled = false
            if (!skipSaveUser) {
                sessionRepository.saveMessage(conversationId, "user", userMessage)
            }

            val apiKey = settingsManager.getApiKey() ?: return Result.failure(
                IllegalStateException("请先在设置中配置 API Key")
            )
            val conversation = sessionRepository.getConversation(conversationId)
            val systemPrompt = buildSystemPrompt(conversation)
            val maxTokens = settingsManager.getModelMaxTokens()

            var activeMessages = sessionRepository.getActiveMessagesFull(conversationId)
            var messages = buildRequestMessages(systemPrompt, activeMessages)
            val tokenCount = tokenCounter.countMessages(messages)
            if (settingsManager.isAutoCompressEnabled() && tokenCount > (maxTokens * 0.90f).toInt()) {
                onAutoCompressStatus(AutoCompressStatus.Started)
                val compressedMessages = contextCompressor.compressToFitOrNull(
                    conversationId = conversationId,
                    activeMessages = activeMessages,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens
                )
                if (compressedMessages == null) {
                    onAutoCompressStatus(AutoCompressStatus.Failed)
                    return Result.failure(Exception("自动压缩失败，上下文仍然过长，请手动压缩后再发送"))
                }
                activeMessages = compressedMessages
                messages = buildRequestMessages(systemPrompt, activeMessages)
                if (tokenCounter.countMessages(messages) > maxTokens) {
                    onAutoCompressStatus(AutoCompressStatus.Failed)
                    return Result.failure(Exception("自动压缩失败，上下文仍然过长，请手动压缩后再发送"))
                }
                onAutoCompressStatus(AutoCompressStatus.Succeeded)
            }
            if (tokenCounter.countMessages(messages) > maxTokens) {
                return Result.failure(Exception("上下文太长，请手动压缩后再发送"))
            }

            val effort = settingsManager.getReasoningEffort()
            val reasoningEnabled = effort != null && effort != "off"
            val request = ChatRequest(
                model = settingsManager.getModel(),
                messages = messages,
                stream = true,
                thinking = if (reasoningEnabled) ThinkingConfig("enabled") else ThinkingConfig("disabled"),
                reasoningEffort = if (reasoningEnabled) effort else null
            )

            api.streamChat(apiKey, request).collect { event ->
                when (event) {
                    is StreamEvent.Content -> contentBuilder.append(event.token)
                    is StreamEvent.Reasoning -> Unit
                }
                onStreamEvent(event)
            }
            val text = contentBuilder.toString()
            if (text.isEmpty()) return Result.failure(Exception("API 返回了空响应"))

            sessionRepository.saveMessage(
                conversationId = conversationId,
                role = "assistant",
                content = text,
                messageId = assistantMessageId
            )
            if (!skipSaveUser && conversation?.characterPrompt == null) {
                val messageCount = sessionRepository.getActiveMessageCount(conversationId)
                if (messageCount <= 2) {
                    sessionRepository.updateConversationTitle(
                        conversationId,
                        if (userMessage.length > 30) userMessage.take(30) + "..." else userMessage
                    )
                }
            }

            Result.success(text)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is IOException) {
                if (userCancelled) return Result.success(contentBuilder.toString())
                return Result.failure(Exception("网络连接中断，请检查网络后重试"))
            }
            Result.failure(e)
        }
    }

    private suspend fun buildSystemPrompt(conversation: ConversationEntity?): String {
        val model = settingsManager.getModel()
        val effort = settingsManager.getReasoningEffort() ?: "off"
        return if (!conversation?.characterPrompt.isNullOrBlank()) {
            conversation!!.characterPrompt!!
        } else {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd EEEE", java.util.Locale.getDefault())
                .format(java.util.Date())
            "You are $model running on DeepSeek API. Today is $today. " +
                "Reasoning mode: $effort. Answer concisely and accurately."
        }
    }

    private fun buildRequestMessages(systemPrompt: String, activeMessages: List<MessageEntity>): List<MessageDto> {
        return buildList {
            add(MessageDto("system", systemPrompt))
            activeMessages
                .asSequence()
                .filter { !it.isArchived }
                .filter { !it.isHidden || it.isHiddenDocumentMessage() }
                .forEach {
                    if (it.isDisplaySummaryMessage()) {
                        add(MessageDto("system", it.content.withoutImportedSearchResults()))
                    } else {
                        add(MessageDto(it.role, it.content.withoutImportedSearchResults()))
                    }
                }
        }
    }

    private fun MessageEntity.isDisplaySummaryMessage(): Boolean {
        if (role != "assistant") return false
        val text = content.trimStart()
        return text.startsWith("对话摘要：") || text.startsWith("对话摘要:")
    }

    private fun MessageEntity.isHiddenDocumentMessage(): Boolean {
        if (role != "user" || !isHidden) return false
        return content.trimStart().startsWith("📎 ") || content.trimStart().startsWith("[文件]")
    }
}
