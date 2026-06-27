package com.deepseekchat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "node_artifacts",
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
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("conversationId"), Index("nodeId"), Index("contentId"), Index("type")]
)
data class NodeArtifactEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val nodeId: String?,
    val type: String,
    val contentId: String?,
    val title: String?,
    val createdAt: Long
)
