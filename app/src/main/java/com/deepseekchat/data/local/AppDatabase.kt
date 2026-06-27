package com.deepseekchat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.deepseekchat.data.local.dao.ConversationDao
import com.deepseekchat.data.local.dao.MessageDao
import com.deepseekchat.data.local.entity.ConversationEntity
import com.deepseekchat.data.local.entity.MessageContentEntity
import com.deepseekchat.data.local.entity.MessageNodeEntity
import com.deepseekchat.data.local.entity.NodeArtifactEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageNodeEntity::class,
        MessageContentEntity::class,
        NodeArtifactEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}
