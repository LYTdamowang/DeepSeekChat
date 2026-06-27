package com.deepseekchat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "message_contents")
data class MessageContentEntity(
    @PrimaryKey val id: String,
    val storageType: String,
    val text: String?,
    val relativePath: String?,
    val preview: String,
    val byteSize: Long,
    val sha256: String,
    val createdAt: Long
)
