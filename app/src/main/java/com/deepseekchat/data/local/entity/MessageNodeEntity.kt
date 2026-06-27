package com.deepseekchat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_nodes",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MessageContentEntity::class,
            parentColumns = ["id"],
            childColumns = ["contentId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("conversationId"),
        Index("parentId"),
        Index("activeChildId"),
        Index("contentId"),
        Index(value = ["conversationId", "parentId", "role", "branchIndex"])
    ]
)
data class MessageNodeEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val parentId: String?,
    val activeChildId: String?,
    val role: String,
    val contentId: String,
    val preview: String,
    val contentLength: Int,
    val timestamp: Long,
    val isArchived: Boolean = false,
    val isHidden: Boolean = false,
    val branchIndex: Int = 0
)
