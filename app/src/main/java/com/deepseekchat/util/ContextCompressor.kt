package com.deepseekchat.util

import com.deepseekchat.data.local.entity.MessageEntity
import com.deepseekchat.data.remote.ChatRequest
import com.deepseekchat.data.remote.DeepSeekApi
import com.deepseekchat.data.remote.MessageDto
import com.deepseekchat.data.remote.StreamEvent
import com.deepseekchat.data.remote.ThinkingConfig
import com.deepseekchat.data.repository.SessionRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContextCompressor @Inject constructor(
    private val api: DeepSeekApi,
    private val sessionRepository: SessionRepository,
    private val tokenCounter: TokenCounter,
    private val settingsManager: SettingsManager
) {
    suspend fun compressToFitOrNull(
        conversationId: String,
        activeMessages: List<MessageEntity>,
        systemPrompt: String,
        maxTokens: Int,
        customMemory: String? = null
    ): List<MessageEntity>? {
        val visible = activeMessages.filter { !it.isHidden || it.isHiddenDocumentMessage() }
        if (visible.size < 6) return null

        val targetBudget = (maxTokens * 0.82).toInt().coerceAtLeast(4_000)
        val targetTokens = summaryTargetTokens(maxTokens)
        val attempts = listOf(
            (visible.size * 0.55).toInt(),
            (visible.size * 0.70).toInt(),
            (visible.size * 0.82).toInt()
        ).map { it.coerceIn(2, visible.size - 2) }.distinct()

        val previousSummary = activePathSummary(visible)
        for (splitPoint in attempts) {
            val oldMessages = visible.take(splitPoint)
            val recentMessages = visible.drop(splitPoint)
            val summary = generateSummary(
                oldMessages = oldMessages,
                previousSummary = previousSummary,
                customMemory = customMemory,
                targetTokens = targetTokens
            )
            val trialMessages = buildList {
                add(MessageDto("system", systemPromptWithSummary(systemPrompt, summary)))
                addAll(recentMessages.map { MessageDto(it.role, it.content.withoutImportedSearchResults()) })
            }
            if (tokenCounter.countMessages(trialMessages) <= targetBudget) {
                sessionRepository.replaceOldMessagesWithSummary(
                    conversationId = conversationId,
                    oldMessageIds = oldMessages.map { it.id },
                    summary = summary
                )
                return sessionRepository.getActiveMessagesFull(conversationId)
            }
        }
        return null
    }

    suspend fun compressManually(
        conversationId: String,
        messages: List<MessageDto>,
        activeMessages: List<MessageEntity>,
        keepCount: Int,
        customMemory: String?
    ) {
        val splitPoint = (activeMessages.size - keepCount).coerceAtLeast(0)
        if (splitPoint <= 0) return
        val oldMessages = activeMessages.take(splitPoint)
        val previousSummary = activePathSummary(activeMessages)
        val summary = generateSummary(
            oldMessages = oldMessages,
            previousSummary = previousSummary,
            customMemory = customMemory,
            targetTokens = summaryTargetTokens(settingsManager.getModelMaxTokens())
        )
        val recentMessages = activeMessages.drop(splitPoint)
        val trial = listOf(MessageDto("system", summary)) +
            recentMessages.map { MessageDto(it.role, it.content.withoutImportedSearchResults()) }
        if (tokenCounter.countMessages(trial) > settingsManager.getModelMaxTokens()) {
            throw IllegalStateException("压缩失败，摘要仍然过长")
        }
        sessionRepository.replaceOldMessagesWithSummary(
            conversationId = conversationId,
            oldMessageIds = oldMessages.map { it.id },
            summary = summary
        )
    }

    suspend fun compress(
        conversationId: String,
        messages: List<MessageDto>,
        activeMessages: List<MessageEntity>,
        customMemory: String?
    ): List<MessageDto> {
        val compressed = compressToFitOrNull(
            conversationId = conversationId,
            activeMessages = activeMessages,
            systemPrompt = messages.firstOrNull { it.role == "system" }?.content.orEmpty(),
            maxTokens = settingsManager.getModelMaxTokens(),
            customMemory = customMemory
        ) ?: activeMessages
        return compressed.map { MessageDto(it.role, it.content.withoutImportedSearchResults()) }
    }

    private suspend fun generateSummary(
        oldMessages: List<MessageEntity>,
        previousSummary: String?,
        customMemory: String?,
        targetTokens: Int
    ): String {
        val filtered = oldMessages.filter {
            (!it.isHidden || it.isHiddenDocumentMessage()) &&
                !it.content.startsWith("[已停止]") &&
                !it.isDisplaySummaryMessage()
        }
        val conversationText = buildConversationText(filtered)

        val memoryInjection = if (!customMemory.isNullOrBlank()) {
            "必须保留这些用户指定记忆：$customMemory\n\n"
        } else ""

        val previousInjection = if (!previousSummary.isNullOrBlank()) {
            "已有摘要如下，请合并去重：\n$previousSummary\n\n"
        } else ""

        val summaryPrompt = """
            ${memoryInjection}${previousInjection}请把下面旧对话压缩成后续对话可继续使用的中文摘要。
            要点：
            1. 优先保留用户目标、已经确定的方案、关键约束、文件名/接口/数据库字段等以后还会用的信息。
            2. 删除寒暄、重复内容、已废弃尝试和无关细节。
            3. 摘要不是按固定字数判断，而是要尽量控制在约 $targetTokens tokens 内；如果信息太多，请继续压缩，只保留以后最可能用得到的内容。

            旧对话：
            $conversationText
        """.trimIndent()

        val apiKey = settingsManager.getApiKey()
            ?: throw IllegalStateException("未设置 API Key，无法生成摘要")

        val request = ChatRequest(
            model = settingsManager.getModel(),
            messages = listOf(MessageDto("user", summaryPrompt)),
            stream = true,
            thinking = ThinkingConfig("disabled")
        )
        val response = StringBuilder()
        api.streamChat(apiKey, request).collect { event ->
            when (event) {
                is StreamEvent.Content -> response.append(event.token)
                is StreamEvent.Reasoning -> Unit
            }
        }
        val text = response.toString().trim()
        if (text.isBlank()) throw IllegalStateException("API 返回了空摘要")
        return text
    }

    private fun systemPromptWithSummary(systemPrompt: String, summary: String): String {
        return "$systemPrompt\n\n[已压缩的历史摘要]\n$summary"
    }

    private fun activePathSummary(messages: List<MessageEntity>): String? {
        val summaries = messages
            .filter { it.isDisplaySummaryMessage() }
            .map { it.content.withoutImportedSearchResults().removeSummaryPrefix().trim() }
            .filter { it.isNotBlank() }
        return summaries.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private fun summaryTargetTokens(maxTokens: Int): Int =
        (maxTokens * 0.02).toInt().coerceIn(800, 4_000)

    private fun buildConversationText(messages: List<MessageEntity>, maxChars: Int = 60_000): String {
        val builder = StringBuilder()
        for (message in messages) {
            val line = "${message.role}: ${message.content.withoutImportedSearchResults().take(1_200)}\n"
            if (builder.length + line.length > maxChars) {
                builder.append("\n[旧对话过长，其余内容已省略，请基于已提供内容和已有摘要继续压缩。]")
                break
            }
            builder.append(line)
        }
        return builder.toString()
    }

    private fun MessageEntity.isDisplaySummaryMessage(): Boolean {
        if (role != "assistant") return false
        val text = content.trimStart()
        return text.startsWith("对话摘要：") || text.startsWith("对话摘要:")
    }

    private fun String.removeSummaryPrefix(): String {
        val text = trimStart()
        return when {
            text.startsWith("对话摘要：") -> text.removePrefix("对话摘要：")
            text.startsWith("对话摘要:") -> text.removePrefix("对话摘要:")
            else -> text
        }
    }

    private fun MessageEntity.isHiddenDocumentMessage(): Boolean {
        if (role != "user" || !isHidden) return false
        return content.trimStart().startsWith("📎 ") || content.trimStart().startsWith("[文件]")
    }
}
