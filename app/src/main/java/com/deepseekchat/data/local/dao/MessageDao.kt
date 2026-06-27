package com.deepseekchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.deepseekchat.data.local.entity.MessageContentEntity
import com.deepseekchat.data.local.entity.MessageNodeEntity
import com.deepseekchat.data.local.entity.NodeArtifactEntity
import kotlinx.coroutines.flow.Flow

data class MessageNodeRow(
    val id: String,
    val conversationId: String,
    val parentId: String?,
    val activeChildId: String?,
    val role: String,
    val contentId: String,
    val preview: String,
    val contentLength: Int,
    val timestamp: Long,
    val isArchived: Boolean,
    val isHidden: Boolean,
    val branchIndex: Int,
    val storageType: String,
    val text: String?,
    val relativePath: String?,
    val byteSize: Long,
    val sha256: String
)

data class ArtifactContentRow(
    val id: String,
    val conversationId: String,
    val nodeId: String?,
    val type: String,
    val contentId: String?,
    val title: String?,
    val createdAt: Long,
    val storageType: String,
    val text: String?,
    val relativePath: String?,
    val preview: String,
    val byteSize: Long,
    val sha256: String
)

@Dao
interface MessageDao {

    @Query(
        """
        WITH RECURSIVE path(
            id, conversationId, parentId, activeChildId, role, contentId, preview,
            contentLength, timestamp, isArchived, isHidden, branchIndex, depth
        ) AS (
            SELECT n.id, n.conversationId, n.parentId, n.activeChildId, n.role, n.contentId, n.preview,
                   n.contentLength, n.timestamp, n.isArchived, n.isHidden, n.branchIndex, 0
            FROM message_nodes n
            WHERE n.id = (SELECT activeLeafNodeId FROM conversations WHERE id = :conversationId)
            UNION ALL
            SELECT p.id, p.conversationId, p.parentId, p.activeChildId, p.role, p.contentId, p.preview,
                   p.contentLength, p.timestamp, p.isArchived, p.isHidden, p.branchIndex, path.depth + 1
            FROM message_nodes p
            JOIN path ON path.parentId = p.id AND path.conversationId = p.conversationId
        )
        SELECT path.id, path.conversationId, path.parentId, path.activeChildId, path.role,
               path.contentId, path.preview, path.contentLength, path.timestamp,
               path.isArchived, path.isHidden, path.branchIndex,
               c.storageType, c.text, c.relativePath, c.byteSize, c.sha256
        FROM path
        JOIN message_contents c ON c.id = path.contentId
        WHERE path.isArchived = 0
        ORDER BY path.depth DESC
        """
    )
    fun getActivePathRows(conversationId: String): Flow<List<MessageNodeRow>>

    @Query(
        """
        WITH RECURSIVE path(
            id, conversationId, parentId, activeChildId, role, contentId, preview,
            contentLength, timestamp, isArchived, isHidden, branchIndex, depth
        ) AS (
            SELECT n.id, n.conversationId, n.parentId, n.activeChildId, n.role, n.contentId, n.preview,
                   n.contentLength, n.timestamp, n.isArchived, n.isHidden, n.branchIndex, 0
            FROM message_nodes n
            WHERE n.id = (SELECT activeLeafNodeId FROM conversations WHERE id = :conversationId)
            UNION ALL
            SELECT p.id, p.conversationId, p.parentId, p.activeChildId, p.role, p.contentId, p.preview,
                   p.contentLength, p.timestamp, p.isArchived, p.isHidden, p.branchIndex, path.depth + 1
            FROM message_nodes p
            JOIN path ON path.parentId = p.id AND path.conversationId = p.conversationId
        )
        SELECT path.id, path.conversationId, path.parentId, path.activeChildId, path.role,
               path.contentId, path.preview, path.contentLength, path.timestamp,
               path.isArchived, path.isHidden, path.branchIndex,
               c.storageType, c.text, c.relativePath, c.byteSize, c.sha256
        FROM path
        JOIN message_contents c ON c.id = path.contentId
        WHERE path.isArchived = 0
        ORDER BY path.depth DESC
        """
    )
    suspend fun getActivePathRowsOnce(conversationId: String): List<MessageNodeRow>

    @Query(
        """
        SELECT n.id, n.conversationId, n.parentId, n.activeChildId, n.role,
               n.contentId, n.preview, n.contentLength, n.timestamp,
               n.isArchived, n.isHidden, n.branchIndex,
               c.storageType, c.text, c.relativePath, c.byteSize, c.sha256
        FROM message_nodes n
        JOIN message_contents c ON c.id = n.contentId
        WHERE n.conversationId = :conversationId
        ORDER BY n.timestamp ASC
        """
    )
    suspend fun getAllRows(conversationId: String): List<MessageNodeRow>

    @Query(
        """
        SELECT n.id, n.conversationId, n.parentId, n.activeChildId, n.role,
               n.contentId, n.preview, n.contentLength, n.timestamp,
               n.isArchived, n.isHidden, n.branchIndex,
               c.storageType, c.text, c.relativePath, c.byteSize, c.sha256
        FROM message_nodes n
        JOIN message_contents c ON c.id = n.contentId
        WHERE n.role = 'assistant'
          AND n.preview LIKE '%' || :marker || '%'
        ORDER BY n.timestamp ASC
        """
    )
    suspend fun getAssistantRowsContaining(marker: String): List<MessageNodeRow>

    @Query(
        """
        WITH RECURSIVE reachable(id) AS (
            SELECT id
            FROM message_nodes
            WHERE conversationId = :conversationId AND parentId IS NULL
            UNION ALL
            SELECT n.id
            FROM message_nodes n
            JOIN reachable r ON n.parentId = r.id
            WHERE n.conversationId = :conversationId
        )
        SELECT n.id, n.conversationId, n.parentId, n.activeChildId, n.role,
               n.contentId, n.preview, n.contentLength, n.timestamp,
               n.isArchived, n.isHidden, n.branchIndex,
               c.storageType, c.text, c.relativePath, c.byteSize, c.sha256
        FROM message_nodes n
        JOIN message_contents c ON c.id = n.contentId
        WHERE n.conversationId = :conversationId
          AND n.id IN (SELECT id FROM reachable)
        ORDER BY n.timestamp ASC
        """
    )
    suspend fun getReachableRows(conversationId: String): List<MessageNodeRow>

    @Query(
        """
        WITH RECURSIVE reachable(id) AS (
            SELECT id
            FROM message_nodes
            WHERE conversationId = :conversationId AND parentId IS NULL
            UNION ALL
            SELECT n.id
            FROM message_nodes n
            JOIN reachable r ON n.parentId = r.id
            WHERE n.conversationId = :conversationId
        )
        SELECT n.id, n.conversationId, n.parentId, n.activeChildId, n.role,
               n.contentId, n.preview, n.contentLength, n.timestamp,
               n.isArchived, n.isHidden, n.branchIndex,
               c.storageType, c.text, c.relativePath, c.byteSize, c.sha256
        FROM message_nodes n
        JOIN message_contents c ON c.id = n.contentId
        WHERE n.conversationId = :conversationId
          AND n.id NOT IN (SELECT id FROM reachable)
        ORDER BY n.timestamp ASC
        """
    )
    suspend fun getUnreachableRows(conversationId: String): List<MessageNodeRow>

    @Query(
        """
        WITH RECURSIVE subtree(id) AS (
            SELECT id
            FROM message_nodes
            WHERE conversationId = :conversationId AND id = :rootId
            UNION ALL
            SELECT n.id
            FROM message_nodes n
            JOIN subtree s ON n.parentId = s.id
            WHERE n.conversationId = :conversationId
        ),
        retained(id) AS (
            SELECT id
            FROM message_nodes
            WHERE conversationId = :conversationId
              AND :retainedRootId IS NOT NULL
              AND id = :retainedRootId
            UNION ALL
            SELECT n.id
            FROM message_nodes n
            JOIN retained r ON n.parentId = r.id
            WHERE n.conversationId = :conversationId
        )
        SELECT n.id, n.conversationId, n.parentId, n.activeChildId, n.role,
               n.contentId, n.preview, n.contentLength, n.timestamp,
               n.isArchived, n.isHidden, n.branchIndex,
               c.storageType, c.text, c.relativePath, c.byteSize, c.sha256
        FROM message_nodes n
        JOIN message_contents c ON c.id = n.contentId
        WHERE n.conversationId = :conversationId
          AND n.id IN (SELECT id FROM subtree)
          AND (:retainedRootId IS NULL OR n.id NOT IN (SELECT id FROM retained))
        ORDER BY n.timestamp ASC
        """
    )
    suspend fun getSubtreeRowsExcluding(
        conversationId: String,
        rootId: String,
        retainedRootId: String?
    ): List<MessageNodeRow>

    @Query(
        """
        SELECT n.id, n.conversationId, n.parentId, n.activeChildId, n.role,
               n.contentId, n.preview, n.contentLength, n.timestamp,
               n.isArchived, n.isHidden, n.branchIndex,
               c.storageType, c.text, c.relativePath, c.byteSize, c.sha256
        FROM message_nodes n
        JOIN message_contents c ON c.id = n.contentId
        WHERE n.conversationId = :conversationId AND n.id = :messageId
        LIMIT 1
        """
    )
    suspend fun getRowById(conversationId: String, messageId: String): MessageNodeRow?

    @Query(
        """
        SELECT n.id, n.conversationId, n.parentId, n.activeChildId, n.role,
               n.contentId, n.preview, n.contentLength, n.timestamp,
               n.isArchived, n.isHidden, n.branchIndex,
               c.storageType, c.text, c.relativePath, c.byteSize, c.sha256
        FROM message_nodes n
        JOIN message_contents c ON c.id = n.contentId
        WHERE n.isArchived = 0
          AND n.isHidden = 0
          AND n.preview LIKE '%' || :query || '%'
        ORDER BY n.timestamp DESC
        """
    )
    fun searchRows(query: String): Flow<List<MessageNodeRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNode(node: MessageNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNodes(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContent(content: MessageContentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContents(contents: List<MessageContentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtifact(artifact: NodeArtifactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtifacts(artifacts: List<NodeArtifactEntity>)

    @Query(
        """
        SELECT a.id, a.conversationId, a.nodeId, a.type, a.contentId, a.title, a.createdAt,
               c.storageType, c.text, c.relativePath, c.preview, c.byteSize, c.sha256
        FROM node_artifacts a
        JOIN message_contents c ON c.id = a.contentId
        WHERE a.conversationId = :conversationId
          AND a.type = :type
          AND a.nodeId IN (:nodeIds)
        ORDER BY a.createdAt ASC
        """
    )
    suspend fun getArtifactsForNodes(
        conversationId: String,
        nodeIds: List<String>,
        type: String
    ): List<ArtifactContentRow>

    @Query(
        """
        SELECT a.id, a.conversationId, a.nodeId, a.type, a.contentId, a.title, a.createdAt,
               c.storageType, c.text, c.relativePath, c.preview, c.byteSize, c.sha256
        FROM node_artifacts a
        JOIN message_contents c ON c.id = a.contentId
        WHERE a.conversationId = :conversationId
          AND a.type = :type
        ORDER BY a.createdAt ASC
        """
    )
    suspend fun getArtifactsForConversation(
        conversationId: String,
        type: String
    ): List<ArtifactContentRow>

    @Query(
        """
        SELECT a.id, a.conversationId, a.nodeId, a.type, a.contentId, a.title, a.createdAt,
               c.storageType, c.text, c.relativePath, c.preview, c.byteSize, c.sha256
        FROM node_artifacts a
        JOIN message_contents c ON c.id = a.contentId
        WHERE a.conversationId = :conversationId
          AND a.id = :artifactId
        LIMIT 1
        """
    )
    suspend fun getArtifactById(
        conversationId: String,
        artifactId: String
    ): ArtifactContentRow?

    @Query("DELETE FROM message_nodes WHERE conversationId = :conversationId")
    suspend fun deleteNodesByConversation(conversationId: String)

    @Query("DELETE FROM node_artifacts WHERE conversationId = :conversationId")
    suspend fun deleteArtifactsByConversation(conversationId: String)

    @Query("SELECT contentId FROM node_artifacts WHERE conversationId = :conversationId AND contentId IS NOT NULL")
    suspend fun getArtifactContentIds(conversationId: String): List<String>

    @Query("DELETE FROM message_contents WHERE id IN (:contentIds)")
    suspend fun deleteContents(contentIds: List<String>)

    @Query(
        """
        SELECT id FROM message_contents
        WHERE id IN (:contentIds)
          AND id NOT IN (SELECT contentId FROM message_nodes)
          AND id NOT IN (SELECT contentId FROM node_artifacts WHERE contentId IS NOT NULL)
          AND id NOT IN (SELECT summaryContentId FROM conversations WHERE summaryContentId IS NOT NULL)
        """
    )
    suspend fun getUnreferencedContentIds(contentIds: List<String>): List<String>

    @Query(
        """
        SELECT * FROM message_contents
        WHERE id NOT IN (SELECT contentId FROM message_nodes)
          AND id NOT IN (SELECT contentId FROM node_artifacts WHERE contentId IS NOT NULL)
          AND id NOT IN (SELECT summaryContentId FROM conversations WHERE summaryContentId IS NOT NULL)
        """
    )
    suspend fun getAllUnreferencedContents(): List<MessageContentEntity>

    @Query(
        """
        SELECT DISTINCT child.conversationId
        FROM message_nodes AS child
        WHERE child.parentId IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM message_nodes AS parent
              WHERE parent.conversationId = child.conversationId
                AND parent.id = child.parentId
          )
        """
    )
    suspend fun getConversationIdsWithDetachedNodes(): List<String>

    @Query("DELETE FROM message_nodes WHERE conversationId = :conversationId AND id IN (:messageIds)")
    suspend fun deleteNodes(conversationId: String, messageIds: List<String>)

    @Query("UPDATE message_nodes SET isArchived = 1 WHERE conversationId = :conversationId AND id IN (:messageIds)")
    suspend fun archiveNodes(conversationId: String, messageIds: List<String>)

    @Query("UPDATE message_nodes SET activeChildId = :childId WHERE conversationId = :conversationId AND id = :parentId")
    suspend fun updateActiveChild(conversationId: String, parentId: String, childId: String?)

    @Query("UPDATE message_nodes SET parentId = :parentId WHERE conversationId = :conversationId AND id = :messageId")
    suspend fun updateParent(conversationId: String, messageId: String, parentId: String?)

    @Query(
        """
        UPDATE message_nodes
        SET contentId = :contentId,
            preview = :preview,
            contentLength = :contentLength
        WHERE conversationId = :conversationId AND id = :messageId
        """
    )
    suspend fun updateNodeContent(
        conversationId: String,
        messageId: String,
        contentId: String,
        preview: String,
        contentLength: Int
    )

    @Query("SELECT COUNT(*) FROM message_nodes WHERE conversationId = :conversationId AND role = :role AND ((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId)")
    suspend fun countSiblings(conversationId: String, parentId: String?, role: String): Int

    @Query("SELECT id FROM message_nodes WHERE conversationId = :conversationId AND role = :role AND ((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId) ORDER BY branchIndex ASC, timestamp ASC")
    suspend fun getSiblingIds(conversationId: String, parentId: String?, role: String): List<String>

    @Query("SELECT id FROM message_nodes WHERE conversationId = :conversationId AND ((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId) ORDER BY branchIndex ASC, timestamp ASC")
    suspend fun getSiblingIdsAnyRole(conversationId: String, parentId: String?): List<String>

    @Query("SELECT id FROM message_nodes WHERE conversationId = :conversationId AND parentId = :parentId ORDER BY branchIndex ASC, timestamp ASC")
    suspend fun getChildIds(conversationId: String, parentId: String): List<String>

    @Query("SELECT activeChildId FROM message_nodes WHERE conversationId = :conversationId AND id = :messageId")
    suspend fun getActiveChildId(conversationId: String, messageId: String): String?

    @Query("SELECT COUNT(*) FROM message_nodes WHERE conversationId = :conversationId AND isArchived = 0")
    suspend fun getActiveMessageCount(conversationId: String): Int

    @Query("SELECT contentId FROM message_nodes WHERE conversationId = :conversationId AND id IN (:messageIds)")
    suspend fun getContentIdsForNodes(conversationId: String, messageIds: List<String>): List<String>

    @Query("SELECT COUNT(*) FROM message_nodes WHERE id IN (:messageIds)")
    suspend fun countExistingNodeIds(messageIds: List<String>): Int

    @Query("SELECT * FROM message_contents WHERE id = :contentId")
    suspend fun getContentById(contentId: String): MessageContentEntity?

    @Query("SELECT * FROM message_contents WHERE id IN (:contentIds)")
    suspend fun getContentsByIds(contentIds: List<String>): List<MessageContentEntity>
}
