package com.deepseekchat.data.remote

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThinkingConfig(val type: String)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<MessageDto>,
    @EncodeDefault
    val stream: Boolean = true,
    val thinking: ThinkingConfig? = null,
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null
)

@Serializable
data class MessageDto(
    val role: String,
    val content: String
)

@Serializable
data class ChatStreamChunk(
    val id: String? = null,
    val choices: List<ChoiceDto>? = null
)

@Serializable
data class ChoiceDto(
    val index: Int,
    val delta: DeltaDto? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class DeltaDto(
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<ChoiceFullDto>? = null
)

@Serializable
data class ChoiceFullDto(
    val index: Int,
    val message: MessageContentDto? = null,
    val delta: DeltaDto? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class MessageContentDto(
    val role: String? = null,
    val content: String? = null
)
