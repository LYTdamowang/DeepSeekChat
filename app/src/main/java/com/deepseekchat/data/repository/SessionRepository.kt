package com.deepseekchat.data.repository

import androidx.room.withTransaction
import com.deepseekchat.data.local.AppDatabase
import com.deepseekchat.data.local.dao.ArtifactContentRow
import com.deepseekchat.data.local.dao.ConversationDao
import com.deepseekchat.data.local.dao.MessageDao
import com.deepseekchat.data.local.dao.MessageNodeRow
import com.deepseekchat.data.local.entity.ConversationEntity
import com.deepseekchat.data.local.entity.MessageContentEntity
import com.deepseekchat.data.local.entity.MessageEntity
import com.deepseekchat.data.local.entity.MessageNodeEntity
import com.deepseekchat.data.local.entity.NodeArtifactEntity
import com.deepseekchat.util.ContentStore
import com.deepseekchat.util.DeepSeekFormatImporter
import com.deepseekchat.util.ImportedArtifact
import com.deepseekchat.util.ReasoningStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class MessageArtifact(
    val id: String,
    val messageId: String,
    val type: String,
    val title: String?,
    val content: String,
    val isPreview: Boolean = false
)

@Singleton
class SessionRepository @Inject constructor(
    private val db: AppDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val contentStore: ContentStore,
    private val reasoningStorage: ReasoningStorage
) {
    fun getAllConversations(): Flow<List<ConversationEntity>> =
        conversationDao.getAllConversations()

    suspend fun getConversation(id: String): ConversationEntity? =
        withContext(Dispatchers.IO) { conversationDao.getById(id) }

    fun getMessages(conversationId: String): Flow<List<MessageEntity>> =
        getMessagesLite(conversationId)

    fun getMessagesLite(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.getActivePathRows(conversationId).map { rows ->
            withContext(Dispatchers.IO) { rows.toMessages(lazy = true) }
        }

    suspend fun getActiveMessages(conversationId: String): List<MessageEntity> =
        getActiveMessagesFull(conversationId)

    suspend fun getActiveMessagesLite(conversationId: String): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            messageDao.getActivePathRowsOnce(conversationId).toMessages(lazy = true)
        }

    suspend fun getActiveMessagesFull(conversationId: String): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            messageDao.getActivePathRowsOnce(conversationId).toMessages(lazy = false)
        }

    suspend fun getAllMessages(conversationId: String): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            cleanupUnreachableRows(conversationId)
            messageDao.getReachableRows(conversationId).toMessages(lazy = false)
        }

    suspend fun getActiveOnly(conversationId: String): List<MessageEntity> =
        getActiveMessagesFull(conversationId)

    suspend fun createConversation(title: String, model: String, type: String = "chat"): ConversationEntity =
        createConversationWithId(UUID.randomUUID().toString(), title, model, type)

    suspend fun createConversationWithId(id: String, title: String, model: String, type: String = "chat"): ConversationEntity =
        withContext(Dispatchers.IO) {
            conversationDao.getById(id)?.let { return@withContext it }
            val now = System.currentTimeMillis()
            val conversation = ConversationEntity(
                id = id,
                title = title,
                model = model,
                type = type,
                createdAt = now,
                updatedAt = now
            )
            conversationDao.upsert(conversation)
            conversation
        }

    suspend fun saveMessage(
        conversationId: String,
        role: String,
        content: String,
        isHidden: Boolean = false,
        messageId: String? = null
    ): MessageEntity = withContext(Dispatchers.IO) {
        saveMessageInternal(
            conversationId = conversationId,
            role = role,
            content = content,
            isHidden = isHidden,
            parentOverride = null,
            useParentOverride = false,
            makeActive = true,
            messageId = messageId
        )
    }

    suspend fun createBranchFromMessage(
        conversationId: String,
        originalMessageId: String,
        newContent: String
    ): MessageEntity = withContext(Dispatchers.IO) {
        val original = messageDao.getRowById(conversationId, originalMessageId)
            ?: throw IllegalArgumentException("找不到要修改的消息")
        saveMessageInternal(
            conversationId = conversationId,
            role = original.role,
            content = newContent,
            isHidden = original.isHidden,
            parentOverride = original.parentId,
            useParentOverride = true,
            makeActive = true
        )
    }

    suspend fun switchMessageVersion(conversationId: String, messageId: String, delta: Int) = withContext(Dispatchers.IO) {
        val row = messageDao.getRowById(conversationId, messageId) ?: return@withContext
        val siblings = messageDao.getSiblingIdsAnyRole(row.conversationId, row.parentId)
        if (siblings.size <= 1) return@withContext
        val currentIndex = siblings.indexOf(row.id).coerceAtLeast(0)
        val nextIndex = (currentIndex + delta).coerceIn(0, siblings.lastIndex)
        val selected = siblings[nextIndex]
        val leaf = deepestActiveLeaf(row.conversationId, selected)
        db.withTransaction {
            val now = System.currentTimeMillis()
            if (row.parentId == null) {
                conversationDao.updateRootAndActiveLeaf(row.conversationId, selected, leaf, now)
            } else {
                messageDao.updateActiveChild(row.conversationId, row.parentId, selected)
                conversationDao.updateActiveLeaf(row.conversationId, leaf, now)
            }
        }
    }

    suspend fun activatePathToMessage(conversationId: String, messageId: String): Boolean = withContext(Dispatchers.IO) {
        val target = messageDao.getRowById(conversationId, messageId) ?: return@withContext false
        if (target.isArchived || target.isHidden) return@withContext false
        val pathFromTarget = mutableListOf<MessageNodeRow>()
        val visited = mutableSetOf<String>()
        var current: MessageNodeRow? = target
        while (current != null) {
            if (!visited.add(current.id)) return@withContext false
            pathFromTarget.add(current)
            val parentId = current.parentId ?: break
            current = messageDao.getRowById(conversationId, parentId) ?: return@withContext false
            if (current.isArchived) return@withContext false
        }
        val pathFromRoot = pathFromTarget.asReversed()
        val root = pathFromRoot.firstOrNull() ?: return@withContext false
        if (root.parentId != null) return@withContext false
        val leaf = deepestActiveLeaf(conversationId, target.id)
        db.withTransaction {
            val now = System.currentTimeMillis()
            pathFromRoot.zipWithNext { parent, child ->
                messageDao.updateActiveChild(conversationId, parent.id, child.id)
            }
            conversationDao.updateRootAndActiveLeaf(
                conversationId = conversationId,
                rootNodeId = root.id,
                activeLeafNodeId = leaf,
                updatedAt = now
            )
        }
        true
    }

    suspend fun loadFullContent(conversationId: String, messageId: String): String? = withContext(Dispatchers.IO) {
        messageDao.getRowById(conversationId, messageId)?.let { row ->
            val full = contentStore.read(row)
            if (
                row.storageType == ContentStore.STORAGE_FILE &&
                row.contentLength > row.preview.length &&
                full == row.preview
            ) {
                null
            } else {
                full
            }
        }
    }

    suspend fun getWebSearchArtifacts(
        conversationId: String,
        messageIds: List<String>
    ): Map<String, List<MessageArtifact>> = withContext(Dispatchers.IO) {
        val ids = messageIds.distinct()
        if (ids.isEmpty()) return@withContext emptyMap()
        messageDao.getArtifactsForNodes(conversationId, ids, DeepSeekFormatImporter.ARTIFACT_WEB_SEARCH)
            .mapNotNull { row ->
                val nodeId = row.nodeId ?: return@mapNotNull null
                MessageArtifact(
                    id = row.id,
                    messageId = nodeId,
                    type = row.type,
                    title = row.title,
                    content = row.preview,
                    isPreview = row.storageType == ContentStore.STORAGE_FILE
                )
            }
            .groupBy { it.messageId }
    }

    suspend fun loadWebSearchArtifactContent(
        conversationId: String,
        artifactId: String
    ): MessageArtifact? = withContext(Dispatchers.IO) {
        val row = messageDao.getArtifactById(conversationId, artifactId) ?: return@withContext null
        val nodeId = row.nodeId ?: return@withContext null
        val content = contentStore.read(row.toContentEntity()) ?: return@withContext null
        MessageArtifact(
            id = row.id,
            messageId = nodeId,
            type = row.type,
            title = row.title,
            content = content,
            isPreview = false
        )
    }

    suspend fun getAllWebSearchArtifacts(conversationId: String): List<ImportedArtifact> =
        withContext(Dispatchers.IO) {
            messageDao.getArtifactsForConversation(conversationId, DeepSeekFormatImporter.ARTIFACT_WEB_SEARCH)
                .mapNotNull { row ->
                    val nodeId = row.nodeId ?: return@mapNotNull null
                    val content = contentStore.read(row.toContentEntity()) ?: return@mapNotNull null
                    ImportedArtifact(
                        messageId = nodeId,
                        type = row.type,
                        title = row.title,
                        content = content
                    )
                }
        }

    suspend fun updateConversationTitle(conversationId: String, title: String) =
        withContext(Dispatchers.IO) {
            conversationDao.getById(conversationId)?.let {
                conversationDao.update(it.copy(title = title, updatedAt = System.currentTimeMillis()))
            }
        }

    suspend fun deleteConversation(conversationId: String) =
        withContext(Dispatchers.IO) {
            val conversation = conversationDao.getById(conversationId)
            val rows = messageDao.getAllRows(conversationId)
            val artifactContentIds = messageDao.getArtifactContentIds(conversationId)
            val candidateContentIds = (
                rows.map { it.contentId } +
                    artifactContentIds +
                    listOfNotNull(conversation?.summaryContentId)
                ).distinct()
            val candidateContents = if (candidateContentIds.isEmpty()) {
                emptyList()
            } else {
                messageDao.getContentsByIds(candidateContentIds)
            }
            val deletedContentIds = db.withTransaction {
                messageDao.deleteArtifactsByConversation(conversationId)
                messageDao.deleteNodesByConversation(conversationId)
                conversationDao.deleteById(conversationId)
                val unreferencedContentIds = if (candidateContentIds.isEmpty()) {
                    emptyList()
                } else {
                    messageDao.getUnreferencedContentIds(candidateContentIds)
                }
                if (unreferencedContentIds.isNotEmpty()) {
                    messageDao.deleteContents(unreferencedContentIds)
                }
                unreferencedContentIds
            }
            val deletedContentIdSet = deletedContentIds.toSet()
            contentStore.deleteRows(rows.filter { it.contentId in deletedContentIdSet })
            candidateContents
                .filter { it.id in deletedContentIdSet }
                .forEach { contentStore.delete(it) }
            reasoningStorage.deleteConversation(conversationId)
        }

    suspend fun updateSummary(conversationId: String, summary: String, startIndex: Int, endIndex: Int) =
        withContext(Dispatchers.IO) {
            val stored = contentStore.storeText(summary, ContentStore.KIND_SUMMARY, forceFile = true)
            db.withTransaction {
                messageDao.upsertContent(stored.entity)
                conversationDao.updateSummary(
                    conversationId = conversationId,
                    summaryContentId = stored.entity.id,
                    summaryPreview = stored.entity.preview,
                    startIndex = startIndex,
                    endIndex = endIndex,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }

    suspend fun getConversationSummary(conversationId: String): String? = withContext(Dispatchers.IO) {
        val conversation = conversationDao.getById(conversationId) ?: return@withContext null
        val content = conversation.summaryContentId?.let { messageDao.getContentById(it) }
        contentStore.read(content) ?: conversation.summary
    }

    suspend fun getLatestConversation(): ConversationEntity? =
        withContext(Dispatchers.IO) { conversationDao.getLatest() }

    suspend fun getActiveMessageCount(conversationId: String): Int =
        withContext(Dispatchers.IO) { messageDao.getActiveMessageCount(conversationId) }

    suspend fun cleanupConversationGarbage(conversationId: String) =
        withContext(Dispatchers.IO) {
            cleanupUnreachableRows(conversationId)
            repairConversationPointers(conversationId)
        }

    suspend fun cleanupStorageGarbage() =
        withContext(Dispatchers.IO) {
            val affectedConversationIds = messageDao.getConversationIdsWithDetachedNodes().toMutableSet()
            affectedConversationIds.forEach { conversationId ->
                cleanupUnreachableRows(conversationId)
                repairConversationPointers(conversationId)
            }
            conversationDao.getIdsWithBrokenTreePointers().forEach { conversationId ->
                repairConversationPointers(conversationId)
            }
            migrateInlineReasoningFromMessageBodies()
            val unreferencedContents = messageDao.getAllUnreferencedContents()
            if (unreferencedContents.isNotEmpty()) {
                db.withTransaction {
                    messageDao.deleteContents(unreferencedContents.map { it.id })
                }
                unreferencedContents.forEach { contentStore.delete(it) }
            }
        }

    fun searchMessages(query: String): Flow<List<MessageEntity>> =
        messageDao.searchRows(query).map { rows ->
            withContext(Dispatchers.IO) { rows.toMessages(lazy = true) }
        }

    suspend fun deleteMessagesSince(conversationId: String, sinceTimestamp: Long) =
        deleteMessagesAfter(conversationId, sinceTimestamp - 1)

    suspend fun deleteMessagesAfter(conversationId: String, sinceTimestamp: Long) =
        withContext(Dispatchers.IO) {
            val rows = messageDao.getActivePathRowsOnce(conversationId)
                .filter { it.timestamp > sinceTimestamp }
            deleteRows(conversationId, rows)
        }

    suspend fun saveEditHistory(messageId: String, newContent: String, editHistoryJson: String, editCount: Int) {
        // Branches replaced the old editHistory blob. Kept for binary/source compatibility.
    }

    suspend fun pinConversation(id: String, pinned: Boolean) =
        withContext(Dispatchers.IO) { conversationDao.setPinned(id, pinned) }

    suspend fun renameConversation(id: String, title: String) =
        withContext(Dispatchers.IO) {
            conversationDao.rename(id, title, System.currentTimeMillis())
        }

    suspend fun archiveMessages(conversationId: String, messageIds: List<String>) =
        withContext(Dispatchers.IO) {
            if (messageIds.isNotEmpty()) messageDao.archiveNodes(conversationId, messageIds)
        }

    suspend fun deleteMessages(conversationId: String, messageIds: List<String>) =
        withContext(Dispatchers.IO) {
            val rows = messageIds.mapNotNull { messageDao.getRowById(conversationId, it) }
            deleteRows(conversationId, rows)
        }

    suspend fun importConversation(
        conversation: ConversationEntity,
        messages: List<MessageEntity>,
        reasoning: Map<String, Pair<String, Long>> = emptyMap(),
        artifacts: List<ImportedArtifact> = emptyList()
    ): String =
        withContext(Dispatchers.IO) {
            if (messages.any { it.parentId != null }) {
                importTreeConversation(conversation, messages, reasoning, artifacts)
            } else {
                importLinearConversation(conversation, messages, reasoning, artifacts)
            }
        }

    suspend fun importConversationIfMissing(
        conversation: ConversationEntity,
        messages: List<MessageEntity>,
        reasoning: Map<String, Pair<String, Long>> = emptyMap(),
        artifacts: List<ImportedArtifact> = emptyList()
    ): String? =
        withContext(Dispatchers.IO) {
            if (conversationDao.getById(conversation.id) != null) {
                null
            } else {
                importConversation(conversation, messages, reasoning, artifacts)
            }
        }

    suspend fun importConversationStreamedIfMissing(
        conversation: ConversationEntity,
        readPayload: suspend (
            onMessage: suspend (MessageEntity) -> Unit,
            onArtifact: suspend (ImportedArtifact) -> Unit
        ) -> Unit
    ): String? =
        withContext(Dispatchers.IO) {
            if (conversationDao.getById(conversation.id) != null) {
                return@withContext null
            }
            val importer = StreamedConversationImporter(conversation)
            try {
                db.withTransaction {
                    importer.begin()
                    readPayload(
                        { message -> importer.addMessage(message) },
                        { artifact -> importer.addArtifact(artifact) }
                    )
                    importer.finish()
                }
                importer.conversationId
            } catch (e: Exception) {
                importer.deleteCreatedFiles()
                throw e
            }
        }

    suspend fun setCharacterPrompt(id: String, prompt: String, profileJson: String) =
        withContext(Dispatchers.IO) {
            conversationDao.setCharacterPrompt(id, prompt, profileJson, System.currentTimeMillis())
        }

    suspend fun getEditHistory(messageId: String): String? = null

    suspend fun replaceOldMessagesWithSummary(
        conversationId: String,
        oldMessageIds: List<String>,
        summary: String
    ) = withContext(Dispatchers.IO) {
        if (oldMessageIds.isEmpty()) return@withContext
        val path = messageDao.getActivePathRowsOnce(conversationId)
        val oldIdSet = oldMessageIds.toSet()
        val indexedOldRows = path.withIndex().filter { it.value.id in oldIdSet }
        if (indexedOldRows.isEmpty()) return@withContext
        val oldRows = indexedOldRows.map { it.value }
        val firstOld = indexedOldRows.minBy { it.index }.value
        val lastOldIndex = indexedOldRows.maxOf { it.index }
        val firstRecent = path.drop(lastOldIndex + 1).firstOrNull { it.id !in oldIdSet }
        val oldRowIds = oldRows.map { it.id }.toSet()
        val preservedOldRowIds = mutableSetOf<String>()
        oldRows.forEachIndexed { index, row ->
            val sideBranchChildren = messageDao.getChildIds(conversationId, row.id)
                .filter { childId -> childId !in oldRowIds && childId != firstRecent?.id }
            if (sideBranchChildren.isNotEmpty()) {
                oldRows.take(index + 1).forEach { preservedOldRowIds.add(it.id) }
            }
        }
        val rowsToDelete = oldRows.filter { it.id !in preservedOldRowIds }
        val deletedRowIds = rowsToDelete.map { it.id }.toSet()
        val activeChildRewrites = oldRows
            .filter { it.id in preservedOldRowIds }
            .mapNotNull { row ->
                val currentActiveChild = row.activeChildId ?: return@mapNotNull null
                if (currentActiveChild !in deletedRowIds && currentActiveChild != firstRecent?.id) {
                    return@mapNotNull null
                }
                val replacement = messageDao.getChildIds(conversationId, row.id)
                    .firstOrNull { childId -> childId !in deletedRowIds && childId != firstRecent?.id }
                row.id to replacement
            }
        val stored = contentStore.storeText("对话摘要：\n\n$summary", ContentStore.KIND_SUMMARY, forceFile = true)
        val summaryNodeId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val summaryNode = MessageNodeEntity(
            id = summaryNodeId,
            conversationId = conversationId,
            parentId = firstOld.parentId,
            activeChildId = firstRecent?.id,
            role = "assistant",
            contentId = stored.entity.id,
            preview = stored.entity.preview,
            contentLength = stored.fullText.length,
            timestamp = now,
            isArchived = false,
            isHidden = false,
            branchIndex = messageDao.countSiblings(conversationId, firstOld.parentId, "assistant")
        )
        val deletedContentIds = try {
            db.withTransaction {
                messageDao.upsertContent(stored.entity)
                messageDao.upsertNode(summaryNode)
                firstOld.parentId?.let { messageDao.updateActiveChild(conversationId, it, summaryNodeId) }
                if (firstRecent != null) {
                    messageDao.updateParent(conversationId, firstRecent.id, summaryNodeId)
                }
                activeChildRewrites.forEach { (rowId, childId) ->
                    messageDao.updateActiveChild(conversationId, rowId, childId)
                }
                if (rowsToDelete.isNotEmpty()) {
                    messageDao.deleteNodes(conversationId, rowsToDelete.map { it.id })
                }
                val oldContentIds = rowsToDelete.map { it.contentId }.distinct()
                val unreferencedContentIds = if (oldContentIds.isEmpty()) {
                    emptyList()
                } else {
                    messageDao.getUnreferencedContentIds(oldContentIds)
                }
                if (unreferencedContentIds.isNotEmpty()) {
                    messageDao.deleteContents(unreferencedContentIds)
                }
                val leaf = firstRecent?.let { deepestActiveLeaf(conversationId, it.id) } ?: summaryNodeId
                val root = if (firstOld.parentId == null) summaryNodeId
                    else conversationDao.getById(conversationId)?.rootNodeId ?: summaryNodeId
                conversationDao.updateRootAndActiveLeaf(conversationId, root, leaf, now)
                conversationDao.updateSummary(
                    conversationId = conversationId,
                    summaryContentId = stored.entity.id,
                    summaryPreview = stored.entity.preview,
                    startIndex = 0,
                    endIndex = oldRows.lastIndex,
                    updatedAt = now
                )
                unreferencedContentIds
            }
        } catch (e: Exception) {
            contentStore.delete(stored.entity)
            throw e
        }
        val deletedContentIdSet = deletedContentIds.toSet()
        contentStore.deleteRows(rowsToDelete.filter { it.contentId in deletedContentIdSet })
        cleanupUnreachableRows(conversationId)
    }

    private suspend fun saveMessageInternal(
        conversationId: String,
        role: String,
        content: String,
        isHidden: Boolean,
        parentOverride: String?,
        useParentOverride: Boolean,
        makeActive: Boolean,
        messageId: String? = null
    ): MessageEntity {
        val conversation = conversationDao.getById(conversationId)
            ?: throw IllegalArgumentException("会话不存在")
        val parentId = if (useParentOverride) parentOverride else conversation.activeLeafNodeId
        val stored = contentStore.storeText(content)
        val nodeId = messageId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val branchIndex = messageDao.countSiblings(conversationId, parentId, role)
        val node = MessageNodeEntity(
            id = nodeId,
            conversationId = conversationId,
            parentId = parentId,
            activeChildId = null,
            role = role,
            contentId = stored.entity.id,
            preview = stored.entity.preview,
            contentLength = content.length,
            timestamp = now,
            isArchived = false,
            isHidden = isHidden,
            branchIndex = branchIndex
        )
        try {
            db.withTransaction {
                messageDao.upsertContent(stored.entity)
                messageDao.upsertNode(node)
                if (makeActive) {
                    parentId?.let { messageDao.updateActiveChild(conversationId, it, nodeId) }
                    conversationDao.updateRootAndActiveLeaf(
                        conversationId = conversationId,
                        rootNodeId = if (parentId == null) nodeId else conversation.rootNodeId ?: nodeId,
                        activeLeafNodeId = nodeId,
                        updatedAt = now
                    )
                }
            }
        } catch (e: Exception) {
            contentStore.delete(stored.entity)
            throw e
        }
        return rowToMessage(messageDao.getRowById(conversationId, nodeId) ?: node.toRow(stored.entity), lazy = false)
    }

    private suspend fun importLinearConversation(
        conversation: ConversationEntity,
        messages: List<MessageEntity>,
        reasoning: Map<String, Pair<String, Long>>,
        artifacts: List<ImportedArtifact>
    ): String {
        val existing = conversationDao.getById(conversation.id)
        val preserveIds = canPreserveImportedMessageIds(existing, messages)
        val conversationId = if (existing == null) conversation.id else UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val contents = mutableListOf<MessageContentEntity>()
        val importedSummary = conversation.summary
            ?.takeIf { it.isNotBlank() }
            ?.let { contentStore.storeText(it, ContentStore.KIND_SUMMARY, forceFile = true) }
        importedSummary?.let { contents.add(it.entity) }
        val importedConversation = conversation.copy(
            id = conversationId,
            title = if (existing == null) conversation.title else "${conversation.title} (导入)",
            rootNodeId = null,
            activeLeafNodeId = null,
            summary = importedSummary?.entity?.preview ?: conversation.summary,
            summaryContentId = importedSummary?.entity?.id,
            summaryPreview = importedSummary?.entity?.preview ?: conversation.summaryPreview,
            updatedAt = now
        )
        val nodes = mutableListOf<MessageNodeEntity>()
        val importedIdMap = mutableMapOf<String, String>()
        val siblingCounters = mutableMapOf<String, Int>()
        var currentParent: String? = null
        var rootId: String? = null
        var activeLeafId: String? = null

        fun nextBranch(parentId: String?, role: String): Int {
            val key = "${parentId ?: "root"}|$role"
            val value = siblingCounters[key] ?: 0
            siblingCounters[key] = value + 1
            return value
        }

        fun setActiveChild(parentId: String?, childId: String) {
            if (parentId == null) return
            val index = nodes.indexOfFirst { it.id == parentId }
            if (index >= 0) nodes[index] = nodes[index].copy(activeChildId = childId)
        }

        fun addNode(message: MessageEntity, parentId: String?, makeMainline: Boolean): MessageNodeEntity {
            val stored = contentStore.storeText(message.content)
            contents.add(stored.entity)
            val node = MessageNodeEntity(
                id = if (preserveIds && message.id.isNotBlank() && nodes.none { it.id == message.id }) message.id else UUID.randomUUID().toString(),
                conversationId = conversationId,
                parentId = parentId,
                activeChildId = null,
                role = message.role,
                contentId = stored.entity.id,
                preview = stored.entity.preview,
                contentLength = message.content.length,
                timestamp = message.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis(),
                isArchived = message.isArchived,
                isHidden = message.isHidden,
                branchIndex = nextBranch(parentId, message.role)
            )
            nodes.add(node)
            if (message.id.isNotBlank()) importedIdMap[message.id] = node.id
            if (makeMainline) {
                setActiveChild(parentId, node.id)
                if (rootId == null) rootId = node.id
                currentParent = node.id
                activeLeafId = node.id
            }
            return node
        }

        messages.forEach { message ->
            if (message.role == "user" && message.editHistory.isNotBlank()) {
                parseLegacyEditHistory(message.editHistory).forEach { entry ->
                    val user = addNode(message.copy(id = UUID.randomUUID().toString(), content = entry.userContent), currentParent, makeMainline = false)
                    val ai = addNode(
                        MessageEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = "assistant",
                            content = entry.aiContent,
                            timestamp = message.timestamp + 1
                        ),
                        user.id,
                        makeMainline = false
                    )
                    val userIndex = nodes.indexOfFirst { it.id == user.id }
                    if (userIndex >= 0) nodes[userIndex] = nodes[userIndex].copy(activeChildId = ai.id)
                }
            }
            addNode(message.copy(conversationId = conversationId), currentParent, makeMainline = true)
        }
        val artifactRows = storeImportedArtifacts(
            conversationId = conversationId,
            artifacts = artifacts,
            idMap = importedIdMap,
            contents = contents
        )

        try {
            db.withTransaction {
                conversationDao.upsert(importedConversation.copy(rootNodeId = rootId, activeLeafNodeId = activeLeafId))
                if (contents.isNotEmpty()) messageDao.upsertContents(contents)
                if (nodes.isNotEmpty()) messageDao.upsertNodes(nodes)
                if (artifactRows.isNotEmpty()) messageDao.upsertArtifacts(artifactRows)
            }
        } catch (e: Exception) {
            contents.forEach { contentStore.delete(it) }
            throw e
        }
        if (reasoning.isNotEmpty()) {
            try {
                reasoningStorage.saveMapStrict(conversationId, remapReasoning(reasoning, importedIdMap))
            } catch (e: Exception) {
                deleteConversation(conversationId)
                throw e
            }
        }
        return conversationId
    }

    private fun storeImportedArtifacts(
        conversationId: String,
        artifacts: List<ImportedArtifact>,
        idMap: Map<String, String>,
        contents: MutableList<MessageContentEntity>
    ): List<NodeArtifactEntity> {
        if (artifacts.isEmpty()) return emptyList()
        return artifacts.mapNotNull { artifact ->
            val nodeId = idMap[artifact.messageId] ?: return@mapNotNull null
            val stored = contentStore.storeText(
                text = artifact.content,
                kind = ContentStore.KIND_ATTACHMENT,
                forceFile = true
            )
            contents.add(stored.entity)
            NodeArtifactEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                nodeId = nodeId,
                type = artifact.type,
                contentId = stored.entity.id,
                title = artifact.title,
                createdAt = System.currentTimeMillis()
            )
        }
    }

    private suspend fun canPreserveImportedMessageIds(
        existingConversation: ConversationEntity?,
        messages: List<MessageEntity>
    ): Boolean {
        if (existingConversation != null) return false
        val ids = messages.map { it.id }.filter { it.isNotBlank() }
        if (ids.isEmpty() || ids.distinct().size != ids.size || ids.size != messages.size) return false
        return messageDao.countExistingNodeIds(ids) == 0
    }

    private fun remapReasoning(
        reasoning: Map<String, Pair<String, Long>>,
        idMap: Map<String, String>
    ): Map<String, Pair<String, Long>> =
        reasoning.mapKeys { (messageId, _) -> idMap[messageId] ?: messageId }

    private suspend fun importTreeConversation(
        conversation: ConversationEntity,
        messages: List<MessageEntity>,
        reasoning: Map<String, Pair<String, Long>>,
        artifacts: List<ImportedArtifact>
    ): String {
        val existing = conversationDao.getById(conversation.id)
        val preserveIds = canPreserveImportedMessageIds(existing, messages)
        val conversationId = if (existing == null) conversation.id else UUID.randomUUID().toString()
        val contents = mutableListOf<MessageContentEntity>()
        val importedSummary = conversation.summary
            ?.takeIf { it.isNotBlank() }
            ?.let { contentStore.storeText(it, ContentStore.KIND_SUMMARY, forceFile = true) }
        importedSummary?.let { contents.add(it.entity) }
        val nodes = mutableListOf<MessageNodeEntity>()
        val siblingCounters = mutableMapOf<String, Int>()
        val idMap = messages.associate { it.id to if (preserveIds) it.id else UUID.randomUUID().toString() }

        fun nextBranch(parentId: String?, role: String): Int {
            val key = "${parentId ?: "root"}|$role"
            val value = siblingCounters[key] ?: 0
            siblingCounters[key] = value + 1
            return value
        }

        messages.sortedBy { it.timestamp }.forEach { message ->
            val stored = contentStore.storeText(message.content)
            contents.add(stored.entity)
            val mappedParent = message.parentId?.let { idMap[it] }
            nodes.add(
                MessageNodeEntity(
                    id = idMap[message.id] ?: UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    parentId = mappedParent,
                    activeChildId = null,
                    role = message.role,
                    contentId = stored.entity.id,
                    preview = stored.entity.preview,
                    contentLength = message.content.length,
                    timestamp = message.timestamp,
                    isArchived = message.isArchived,
                    isHidden = message.isHidden,
                    branchIndex = nextBranch(mappedParent, message.role)
                )
            )
        }

        val activeByParent = nodes.groupBy { it.parentId }
            .mapValues { (_, children) -> children.maxByOrNull { it.timestamp }?.id }
        for (i in nodes.indices) {
            nodes[i] = nodes[i].copy(activeChildId = activeByParent[nodes[i].id])
        }
        val rootId = activeByParent[null] ?: nodes.firstOrNull { it.parentId == null }?.id
        var leafId = rootId
        val guard = mutableSetOf<String>()
        while (leafId != null && guard.add(leafId)) {
            val child = activeByParent[leafId] ?: break
            leafId = child
        }
        val artifactRows = storeImportedArtifacts(
            conversationId = conversationId,
            artifacts = artifacts,
            idMap = idMap,
            contents = contents
        )

        try {
            db.withTransaction {
                conversationDao.upsert(
                    conversation.copy(
                        id = conversationId,
                        title = if (existing == null) conversation.title else "${conversation.title} (导入)",
                        rootNodeId = rootId,
                        activeLeafNodeId = leafId,
                        summary = importedSummary?.entity?.preview ?: conversation.summary,
                        summaryContentId = importedSummary?.entity?.id,
                        summaryPreview = importedSummary?.entity?.preview ?: conversation.summaryPreview,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                if (contents.isNotEmpty()) messageDao.upsertContents(contents)
                if (nodes.isNotEmpty()) messageDao.upsertNodes(nodes)
                if (artifactRows.isNotEmpty()) messageDao.upsertArtifacts(artifactRows)
            }
        } catch (e: Exception) {
            contents.forEach { contentStore.delete(it) }
            throw e
        }
        if (reasoning.isNotEmpty()) {
            try {
                reasoningStorage.saveMapStrict(conversationId, remapReasoning(reasoning, idMap))
            } catch (e: Exception) {
                deleteConversation(conversationId)
                throw e
            }
        }
        return conversationId
    }

    private inner class StreamedConversationImporter(
        private val sourceConversation: ConversationEntity
    ) {
        val conversationId: String = sourceConversation.id
        private val treeMode = !sourceConversation.rootNodeId.isNullOrBlank() ||
            !sourceConversation.activeLeafNodeId.isNullOrBlank()
        private val contentBatch = mutableListOf<MessageContentEntity>()
        private val nodeBatch = mutableListOf<MessageNodeEntity>()
        private val artifactBatch = mutableListOf<NodeArtifactEntity>()
        private val createdFileContents = mutableListOf<MessageContentEntity>()
        private val idMap = mutableMapOf<String, String>()
        private val siblingCounters = mutableMapOf<String, Int>()
        private val activeChildUpdates = linkedMapOf<String, String>()
        private val pendingParentUpdates = mutableMapOf<String, String>()
        private val latestTreeChildBySourceParent = mutableMapOf<String?, LatestTreeChild>()
        private var currentLinearParent: String? = null
        private var rootId: String? = null
        private var activeLeafId: String? = null

        suspend fun begin() {
            val now = System.currentTimeMillis()
            val summaryContent = sourceConversation.summary
                ?.takeIf { it.isNotBlank() }
                ?.let { contentStore.storeText(it, ContentStore.KIND_SUMMARY, forceFile = true) }
            summaryContent?.let {
                trackFile(it.entity)
                messageDao.upsertContent(it.entity)
            }
            conversationDao.upsert(
                sourceConversation.copy(
                    rootNodeId = null,
                    activeLeafNodeId = null,
                    summary = summaryContent?.entity?.preview ?: sourceConversation.summary,
                    summaryContentId = summaryContent?.entity?.id,
                    summaryPreview = summaryContent?.entity?.preview ?: sourceConversation.summaryPreview,
                    updatedAt = now
                )
            )
        }

        suspend fun addMessage(message: MessageEntity) {
            if (!treeMode && message.role == "user" && message.editHistory.isNotBlank()) {
                parseLegacyEditHistory(message.editHistory).forEach { entry ->
                    val userId = addNode(
                        message = message.copy(id = UUID.randomUUID().toString(), content = entry.userContent),
                        parentId = currentLinearParent,
                        sourceParentId = null,
                        makeLinearMainline = false
                    )
                    val aiId = addNode(
                        message = MessageEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = "assistant",
                            content = entry.aiContent,
                            timestamp = message.timestamp + 1
                        ),
                        parentId = userId,
                        sourceParentId = null,
                        makeLinearMainline = false
                    )
                    activeChildUpdates[userId] = aiId
                }
            }

            val sourceParentId = message.parentId
            val parentId = when {
                treeMode -> sourceParentId?.let { idMap[it] }
                sourceParentId != null -> sourceParentId.let { idMap[it] }
                else -> currentLinearParent
            }
            val isTreeMessage = treeMode || sourceParentId != null
            val nodeId = addNode(
                message = message.copy(conversationId = conversationId),
                parentId = parentId,
                sourceParentId = sourceParentId,
                makeLinearMainline = !isTreeMessage
            )
            if (isTreeMessage) {
                val current = latestTreeChildBySourceParent[sourceParentId]
                if (current == null || message.timestamp >= current.timestamp) {
                    latestTreeChildBySourceParent[sourceParentId] = LatestTreeChild(nodeId, message.timestamp)
                }
                if (sourceParentId != null && parentId == null) {
                    pendingParentUpdates[nodeId] = sourceParentId
                }
            }
        }

        suspend fun addArtifact(artifact: ImportedArtifact) {
            val nodeId = idMap[artifact.messageId] ?: return
            val stored = contentStore.storeText(
                text = artifact.content,
                kind = ContentStore.KIND_ATTACHMENT,
                forceFile = true
            )
            trackFile(stored.entity)
            contentBatch.add(stored.entity)
            artifactBatch.add(
                NodeArtifactEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    nodeId = nodeId,
                    type = artifact.type,
                    contentId = stored.entity.id,
                    title = artifact.title,
                    createdAt = System.currentTimeMillis()
                )
            )
            flushIfNeeded()
        }

        suspend fun finish() {
            flush()
            if (treeMode || latestTreeChildBySourceParent.isNotEmpty()) {
                pendingParentUpdates.forEach { (nodeId, sourceParentId) ->
                    idMap[sourceParentId]?.let { messageDao.updateParent(conversationId, nodeId, it) }
                }
                latestTreeChildBySourceParent.forEach { (sourceParentId, latest) ->
                    val parentId = sourceParentId?.let { idMap[it] } ?: return@forEach
                    messageDao.updateActiveChild(conversationId, parentId, latest.nodeId)
                }
                rootId = sourceConversation.rootNodeId
                    ?.let { idMap[it] }
                    ?: latestTreeChildBySourceParent[null]?.nodeId
                    ?: rootId
                activeLeafId = sourceConversation.activeLeafNodeId
                    ?.let { idMap[it] }
                    ?: rootId?.let { deepestActiveLeaf(conversationId, it) }
            } else {
                activeChildUpdates.forEach { (parentId, childId) ->
                    messageDao.updateActiveChild(conversationId, parentId, childId)
                }
            }
            conversationDao.updateRootAndActiveLeaf(
                conversationId = conversationId,
                rootNodeId = rootId,
                activeLeafNodeId = activeLeafId,
                updatedAt = System.currentTimeMillis()
            )
        }

        fun deleteCreatedFiles() {
            createdFileContents.forEach { contentStore.delete(it) }
        }

        private suspend fun addNode(
            message: MessageEntity,
            parentId: String?,
            sourceParentId: String?,
            makeLinearMainline: Boolean
        ): String {
            val stored = contentStore.storeText(message.content)
            trackFile(stored.entity)
            val nodeId = UUID.randomUUID().toString()
            if (message.id.isNotBlank()) idMap[message.id] = nodeId
            val node = MessageNodeEntity(
                id = nodeId,
                conversationId = conversationId,
                parentId = parentId,
                activeChildId = null,
                role = message.role,
                contentId = stored.entity.id,
                preview = stored.entity.preview,
                contentLength = message.content.length,
                timestamp = message.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis(),
                isArchived = message.isArchived,
                isHidden = message.isHidden,
                branchIndex = nextBranch(sourceParentId ?: parentId, message.role)
            )
            contentBatch.add(stored.entity)
            nodeBatch.add(node)
            if (makeLinearMainline) {
                parentId?.let { activeChildUpdates[it] = nodeId }
                if (rootId == null) rootId = nodeId
                currentLinearParent = nodeId
                activeLeafId = nodeId
            } else if (rootId == null && parentId == null) {
                rootId = nodeId
            }
            flushIfNeeded()
            return nodeId
        }

        private fun nextBranch(parentKey: String?, role: String): Int {
            val key = "${parentKey ?: "root"}|$role"
            val value = siblingCounters[key] ?: 0
            siblingCounters[key] = value + 1
            return value
        }

        private fun trackFile(content: MessageContentEntity) {
            if (content.storageType == ContentStore.STORAGE_FILE) {
                createdFileContents.add(content)
            }
        }

        private suspend fun flushIfNeeded() {
            if (
                contentBatch.size >= STREAM_IMPORT_BATCH_SIZE ||
                nodeBatch.size >= STREAM_IMPORT_BATCH_SIZE ||
                artifactBatch.size >= STREAM_IMPORT_BATCH_SIZE
            ) {
                flush()
            }
        }

        private suspend fun flush() {
            if (contentBatch.isNotEmpty()) {
                messageDao.upsertContents(contentBatch.toList())
                contentBatch.clear()
            }
            if (nodeBatch.isNotEmpty()) {
                messageDao.upsertNodes(nodeBatch.toList())
                nodeBatch.clear()
            }
            if (artifactBatch.isNotEmpty()) {
                messageDao.upsertArtifacts(artifactBatch.toList())
                artifactBatch.clear()
            }
        }
    }

    private data class LatestTreeChild(val nodeId: String, val timestamp: Long)

    private suspend fun migrateInlineReasoningFromMessageBodies() {
        val candidates = messageDao.getAssistantRowsContaining(INLINE_REASONING_MARKER)
        if (candidates.isEmpty()) return
        candidates.forEach { row ->
            val split = splitInlineReasoning(contentStore.read(row)) ?: return@forEach
            val currentReasoning = reasoningStorage.loadConversation(row.conversationId).toMutableMap()
            if (!currentReasoning.containsKey(row.id)) {
                currentReasoning[row.id] = split.reasoning to 0L
                reasoningStorage.saveMapStrict(row.conversationId, currentReasoning)
            }
            val stored = contentStore.storeText(split.response)
            val deletedContentIds = try {
                db.withTransaction {
                    messageDao.upsertContent(stored.entity)
                    messageDao.updateNodeContent(
                        conversationId = row.conversationId,
                        messageId = row.id,
                        contentId = stored.entity.id,
                        preview = stored.entity.preview,
                        contentLength = split.response.length
                    )
                    val unreferencedContentIds = messageDao.getUnreferencedContentIds(listOf(row.contentId))
                    if (unreferencedContentIds.isNotEmpty()) {
                        messageDao.deleteContents(unreferencedContentIds)
                    }
                    unreferencedContentIds
                }
            } catch (e: Exception) {
                contentStore.delete(stored.entity)
                throw e
            }
            if (row.contentId in deletedContentIds) {
                contentStore.deleteRows(listOf(row))
            }
        }
    }

    private fun splitInlineReasoning(content: String): InlineReasoningSplit? {
        val text = content.trimStart()
        val thinkLabel = INLINE_REASONING_START_LABELS.firstOrNull { text.startsWith(it) } ?: return null
        val afterThink = text.substring(thinkLabel.length).trimStart()
        val replyMatch = INLINE_REPLY_LABELS
            .mapNotNull { label ->
                val index = afterThink.indexOf(label)
                if (index >= 0) label to index else null
            }
            .minByOrNull { it.second }
            ?: return null
        val reasoning = afterThink.substring(0, replyMatch.second).trim()
        val response = afterThink.substring(replyMatch.second + replyMatch.first.length).trimStart()
        if (reasoning.isBlank() || response.isBlank()) return null
        return InlineReasoningSplit(reasoning, response)
    }

    private suspend fun cleanupUnreachableRows(conversationId: String) {
        val unreachableRows = messageDao.getUnreachableRows(conversationId)
        if (unreachableRows.isNotEmpty()) {
            deleteRows(conversationId, unreachableRows)
        }
    }

    private suspend fun repairConversationPointers(conversationId: String) {
        val conversation = conversationDao.getById(conversationId) ?: return
        val rows = messageDao.getReachableRows(conversationId)
        if (rows.isEmpty()) {
            if (conversation.rootNodeId != null || conversation.activeLeafNodeId != null) {
                conversationDao.updateRootAndActiveLeaf(conversationId, null, null, System.currentTimeMillis())
            }
            return
        }
        val rowIds = rows.map { it.id }.toSet()
        val rootId = conversation.rootNodeId
            ?.takeIf { id -> rows.any { it.id == id && it.parentId == null } }
            ?: rows.firstOrNull { it.parentId == null }?.id
            ?: rows.first().id
        val activeLeafId = conversation.activeLeafNodeId
            ?.takeIf { it in rowIds }
            ?: deepestActiveLeaf(conversationId, rootId)
        if (conversation.rootNodeId != rootId || conversation.activeLeafNodeId != activeLeafId) {
            conversationDao.updateRootAndActiveLeaf(
                conversationId = conversationId,
                rootNodeId = rootId,
                activeLeafNodeId = activeLeafId,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private suspend fun deleteRows(conversationId: String, rows: List<MessageNodeRow>) {
        if (rows.isEmpty()) return
        val deletedContentIds = db.withTransaction {
            messageDao.deleteNodes(conversationId, rows.map { it.id })
            val contentIds = rows.map { it.contentId }.distinct()
            val unreferencedContentIds = if (contentIds.isEmpty()) {
                emptyList()
            } else {
                messageDao.getUnreferencedContentIds(contentIds)
            }
            if (unreferencedContentIds.isNotEmpty()) {
                messageDao.deleteContents(unreferencedContentIds)
            }
            unreferencedContentIds
        }
        val deletedContentIdSet = deletedContentIds.toSet()
        contentStore.deleteRows(rows.filter { it.contentId in deletedContentIdSet })
    }

    private suspend fun deepestActiveLeaf(conversationId: String, startId: String): String {
        var current = startId
        val visited = mutableSetOf<String>()
        while (visited.add(current)) {
            current = messageDao.getActiveChildId(conversationId, current) ?: return current
        }
        return current
    }

    private suspend fun List<MessageNodeRow>.toMessages(lazy: Boolean): List<MessageEntity> =
        map { row -> rowToMessage(row, lazy) }

    private suspend fun rowToMessage(row: MessageNodeRow, lazy: Boolean): MessageEntity {
        val siblings = messageDao.getSiblingIdsAnyRole(row.conversationId, row.parentId)
        val versionIndex = siblings.indexOf(row.id).coerceAtLeast(0)
        val content = if (lazy && row.storageType == ContentStore.STORAGE_FILE) row.preview else contentStore.read(row)
        return MessageEntity(
            id = row.id,
            conversationId = row.conversationId,
            role = row.role,
            content = content,
            timestamp = row.timestamp,
            isArchived = row.isArchived,
            editHistory = "",
            editCount = (siblings.size - 1).coerceAtLeast(0),
            isHidden = row.isHidden,
            parentId = row.parentId,
            contentId = row.contentId,
            preview = row.preview,
            branchIndex = versionIndex,
            storageType = row.storageType
        )
    }

    private fun MessageNodeEntity.toRow(content: MessageContentEntity): MessageNodeRow =
        MessageNodeRow(
            id = id,
            conversationId = conversationId,
            parentId = parentId,
            activeChildId = activeChildId,
            role = role,
            contentId = contentId,
            preview = preview,
            contentLength = contentLength,
            timestamp = timestamp,
            isArchived = isArchived,
            isHidden = isHidden,
            branchIndex = branchIndex,
            storageType = content.storageType,
            text = content.text,
            relativePath = content.relativePath,
            byteSize = content.byteSize,
            sha256 = content.sha256
        )

    private fun ArtifactContentRow.toContentEntity(): MessageContentEntity =
        MessageContentEntity(
            id = contentId ?: id,
            storageType = storageType,
            text = text,
            relativePath = relativePath,
            preview = preview,
            byteSize = byteSize,
            sha256 = sha256,
            createdAt = createdAt
        )

    private data class LegacyEditEntry(val userContent: String, val aiContent: String)

    private data class InlineReasoningSplit(val reasoning: String, val response: String)

    private val STREAM_IMPORT_BATCH_SIZE = 100

    private val INLINE_REASONING_MARKER = "思考过程"
    private val INLINE_REASONING_START_LABELS = listOf("【思考过程】")
    private val INLINE_REPLY_LABELS = listOf("【回复】")

    private fun parseLegacyEditHistory(history: String): List<LegacyEditEntry> {
        if (history.isBlank()) return emptyList()
        return history.split("\n<|SEP|>\n").mapNotNull { entry ->
            val parts = entry.split("|||")
            val user = parts.getOrNull(0).orEmpty()
            val ai = parts.getOrNull(1).orEmpty()
            if (user.isBlank() && ai.isBlank()) null else LegacyEditEntry(user, ai)
        }
    }
}
