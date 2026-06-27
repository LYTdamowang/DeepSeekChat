package com.deepseekchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.deepseekchat.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC LIMIT 1")
    suspend fun getLatest(): ConversationEntity?

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET characterPrompt = :prompt, characterProfileJson = :profileJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setCharacterPrompt(id: String, prompt: String, profileJson: String, updatedAt: Long)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Query(
        """
        SELECT c.id
        FROM conversations c
        WHERE (c.rootNodeId IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM message_nodes n
            WHERE n.conversationId = c.id AND n.id = c.rootNodeId
        ))
        OR (c.activeLeafNodeId IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM message_nodes n
            WHERE n.conversationId = c.id AND n.id = c.activeLeafNodeId
        ))
        """
    )
    suspend fun getIdsWithBrokenTreePointers(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        UPDATE conversations
        SET summary = :summaryPreview,
            summaryContentId = :summaryContentId,
            summaryPreview = :summaryPreview,
            summaryStartIndex = :startIndex,
            summaryEndIndex = :endIndex,
            updatedAt = :updatedAt
        WHERE id = :conversationId
        """
    )
    suspend fun updateSummary(
        conversationId: String,
        summaryContentId: String,
        summaryPreview: String,
        startIndex: Int,
        endIndex: Int,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE conversations
        SET rootNodeId = :rootNodeId,
            activeLeafNodeId = :activeLeafNodeId,
            updatedAt = :updatedAt
        WHERE id = :conversationId
        """
    )
    suspend fun updateRootAndActiveLeaf(
        conversationId: String,
        rootNodeId: String?,
        activeLeafNodeId: String?,
        updatedAt: Long
    )

    @Query("UPDATE conversations SET activeLeafNodeId = :activeLeafNodeId, updatedAt = :updatedAt WHERE id = :conversationId")
    suspend fun updateActiveLeaf(conversationId: String, activeLeafNodeId: String?, updatedAt: Long)
}
