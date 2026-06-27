package com.deepseekchat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val model: String,
    val type: String = "chat",
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val characterPrompt: String? = null,
    val characterProfileJson: String? = null,
    val summary: String? = null,
    val summaryStartIndex: Int? = null,
    val summaryEndIndex: Int? = null,
    val rootNodeId: String? = null,
    val activeLeafNodeId: String? = null,
    val summaryContentId: String? = null,
    val summaryPreview: String? = null
)
