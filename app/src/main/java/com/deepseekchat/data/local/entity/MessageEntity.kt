package com.deepseekchat.data.local.entity

/**
 * UI/repository message model.
 *
 * The Room-backed storage is now split into message_nodes + message_contents.
 * Keeping this shape avoids rewriting the whole Compose UI at once.
 */
data class MessageEntity(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val isArchived: Boolean = false,
    val editHistory: String = "",
    val editCount: Int = 0,
    val isHidden: Boolean = false,
    val parentId: String? = null,
    val contentId: String? = null,
    val preview: String = "",
    val branchIndex: Int = 0,
    val storageType: String = "inline"
)
