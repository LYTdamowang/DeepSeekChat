package com.deepseekchat.util

import android.content.Context
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import android.provider.OpenableColumns
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.deepseekchat.data.local.entity.ConversationEntity
import com.deepseekchat.data.local.entity.MessageEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportImportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reasoningStorage: ReasoningStorage
) {
    enum class ImportFileKind {
        JSON,
        ZIP,
        UNKNOWN
    }

    class ImportUserException(message: String) : IllegalStateException(message)

    fun requireSupportedImportFile(uri: Uri): ImportFileKind {
        val kind = detectImportFileKind(uri)
        if (kind == ImportFileKind.UNKNOWN) throw ImportUserException(FILE_TYPE_ERROR)
        return kind
    }

    fun userMessageForImportFailure(uri: Uri, error: Throwable): String {
        if (error is ImportUserException) return error.message ?: GENERIC_IMPORT_ERROR
        if (error.isStorageFullError()) return STORAGE_FULL_ERROR
        return when (detectImportFileKind(uri)) {
            ImportFileKind.ZIP -> ZIP_ERROR
            ImportFileKind.JSON -> JSON_ERROR
            ImportFileKind.UNKNOWN -> FILE_TYPE_ERROR
        }
    }

    fun exportToZip(
        conversation: ConversationEntity,
        messages: List<MessageEntity>,
        uri: Uri,
        artifacts: List<ImportedArtifact> = emptyList()
    ) {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                OutputStreamWriter(zip, Charsets.UTF_8).also { writer ->
                    JsonWriter(writer).apply {
                        beginObject()
                        name("format").value("deepseekChatBackup")
                        name("formatVersion").value(2)
                        name("exportedAt").value(Date().time)
                        name("conversationId").value(conversation.id)
                        endObject()
                        flush()
                    }
                }
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("reasoning.json"))
                OutputStreamWriter(zip, Charsets.UTF_8).also { writer ->
                    writeReasoningJson(JsonWriter(writer), reasoningStorage.loadConversation(conversation.id))
                    writer.flush()
                }
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("conversation.json"))
                OutputStreamWriter(zip, Charsets.UTF_8).also { writer ->
                    writeConversationJson(JsonWriter(writer), conversation, messages, artifacts)
                    writer.flush()
                }
                zip.closeEntry()
            }
        } ?: throw IllegalStateException("无法打开导出文件")
    }

    fun exportToJson(
        conversation: ConversationEntity,
        messages: List<MessageEntity>,
        artifacts: List<ImportedArtifact> = emptyList()
    ): String {
        val writer = java.io.StringWriter()
        writeConversationJson(JsonWriter(writer).apply { setIndent("  ") }, conversation, messages, artifacts)
        return writer.toString()
    }

    fun writeToUri(uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun importFromDeepSeekZip(
        uri: Uri,
        onProgress: (current: Int) -> Unit = {},
        onConversation: suspend (ImportResult) -> Unit
    ): Int {
        val importer = DeepSeekFormatImporter()
        importedCount.set(0)
        return importer.importFromZip(uri, context) { result ->
            onConversation(result)
            onProgress(importedCount.incrementAndGet())
        }.also { importedCount.set(0) }
    }

    suspend fun importFromDeepSeekJson(
        uri: Uri,
        onProgress: (current: Int) -> Unit = {},
        onConversation: suspend (ImportResult) -> Unit
    ): Int {
        val importer = DeepSeekFormatImporter()
        importedCount.set(0)
        return importer.importFromJson(uri, context) { result ->
            onConversation(result)
            onProgress(importedCount.incrementAndGet())
        }.also { importedCount.set(0) }
    }

    fun importFromBackupZip(
        uri: Uri,
        onProgress: (current: Int, total: Int) -> Unit
    ): ImportResult? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream.buffered()).use { zip ->
                    var reasoning: Map<String, Pair<String, Long>> = emptyMap()
                    var conversationResult: ImportResult? = null
                    var entry = zip.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            "reasoning.json" -> {
                                reasoning = readReasoningJson(JsonReader(InputStreamReader(zip, Charsets.UTF_8)))
                            }
                            "conversation.json" -> {
                                val reader = JsonReader(InputStreamReader(zip, Charsets.UTF_8))
                                conversationResult = readConversationBackup(reader, onProgress)
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    conversationResult?.copy(reasoning = reasoning)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun importFromJsonStream(
        uri: Uri,
        onProgress: (current: Int, total: Int) -> Unit
    ): ImportResult? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                JsonReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) return null
                    readConversationBackup(reader, onProgress)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun importFromJsonStreamed(
        uri: Uri,
        onProgress: (current: Int, total: Int) -> Unit,
        importConversation: suspend (
            conversation: ConversationEntity,
            readPayload: suspend (
                onMessage: suspend (MessageEntity) -> Unit,
                onArtifact: suspend (ImportedArtifact) -> Unit
            ) -> Unit
        ) -> String?
    ): BackupJsonImportResult {
        var importStarted = false
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                JsonReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                        return BackupJsonImportResult(false, null)
                    }
                    reader.beginObject()
                    var formatVersion = 0
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "formatVersion" -> formatVersion = safeInt(reader)
                            "exportedAt" -> reader.skipValue()
                            "conversation" -> {
                                if (formatVersion < 1) {
                                    reader.skipValue()
                                    return BackupJsonImportResult(false, null)
                                }
                                val conversation = readConversationEntity(reader)
                                importStarted = true
                                val importedId = importConversation(conversation) { onMessage, onArtifact ->
                                    readRemainingBackupPayload(
                                        reader = reader,
                                        conversationId = conversation.id,
                                        onProgress = onProgress,
                                        onMessage = onMessage,
                                        onArtifact = onArtifact
                                    )
                                }
                                return BackupJsonImportResult(true, importedId)
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    BackupJsonImportResult(false, null)
                }
            } ?: BackupJsonImportResult(false, null)
        } catch (e: Exception) {
            if (importStarted) throw e
            BackupJsonImportResult(false, null)
        }
    }

    fun readFromUri(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inp ->
                inp.reader(Charsets.UTF_8).readText()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeConversationJson(
        writer: JsonWriter,
        conversation: ConversationEntity,
        messages: List<MessageEntity>,
        artifacts: List<ImportedArtifact> = emptyList()
    ) {
        writer.beginObject()
        writer.name("formatVersion").value(2)
        writer.name("exportedAt").value(Date().time)
        writer.name("conversation")
        writer.beginObject()
        writer.name("id").value(conversation.id)
        writer.name("title").value(conversation.title)
        writer.name("type").value(conversation.type)
        writer.name("model").value(conversation.model)
        writer.name("pinned").value(conversation.pinned)
        writer.name("createdAt").value(conversation.createdAt)
        writer.name("updatedAt").value(conversation.updatedAt)
        writer.name("rootNodeId").value(conversation.rootNodeId)
        writer.name("activeLeafNodeId").value(conversation.activeLeafNodeId)
        val summaryText = conversation.summaryContentId
            ?.let { summaryContentId -> messages.firstOrNull { it.contentId == summaryContentId }?.content }
            ?: messages.lastOrNull { it.role == "assistant" && it.isSummaryMessage() }?.content
            ?: conversation.summary
        summaryText?.let { writer.name("summary").value(it) }
        conversation.summaryStartIndex?.let { writer.name("summaryStartIndex").value(it) }
        conversation.summaryEndIndex?.let { writer.name("summaryEndIndex").value(it) }
        conversation.summaryPreview?.let { writer.name("summaryPreview").value(it) }
        conversation.characterPrompt?.let { writer.name("characterPrompt").value(it) }
        conversation.characterProfileJson?.let { writer.name("characterProfileJson").value(it) }
        writer.endObject()

        writer.name("messages")
        writer.beginArray()
        messages.filter { !it.isArchived }.forEach { message ->
            writer.beginObject()
            writer.name("id").value(message.id)
            writer.name("parentId").value(message.parentId)
            writer.name("role").value(message.role)
            writer.name("content").value(message.content)
            writer.name("timestamp").value(message.timestamp)
            writer.name("isHidden").value(message.isHidden)
            writer.name("branchIndex").value(message.branchIndex)
            if (message.editHistory.isNotBlank()) writer.name("editHistory").value(message.editHistory)
            if (message.editCount > 0) writer.name("editCount").value(message.editCount)
            writer.endObject()
        }
        writer.endArray()
        if (artifacts.isNotEmpty()) {
            writer.name("artifacts")
            writer.beginArray()
            artifacts.forEach { artifact ->
                writer.beginObject()
                writer.name("messageId").value(artifact.messageId)
                writer.name("type").value(artifact.type)
                artifact.title?.let { writer.name("title").value(it) }
                writer.name("content").value(artifact.content)
                writer.endObject()
            }
            writer.endArray()
        }
        writer.endObject()
        writer.flush()
    }

    private fun writeReasoningJson(writer: JsonWriter, reasoning: Map<String, Pair<String, Long>>) {
        writer.beginArray()
        reasoning.forEach { (messageId, pair) ->
            writer.beginObject()
            writer.name("messageId").value(messageId)
            writer.name("text").value(pair.first)
            writer.name("durationMs").value(pair.second)
            writer.endObject()
        }
        writer.endArray()
        writer.flush()
    }

    private fun readReasoningJson(reader: JsonReader): Map<String, Pair<String, Long>> {
        val result = linkedMapOf<String, Pair<String, Long>>()
        return try {
            reader.beginArray()
            while (reader.hasNext()) {
                var messageId = ""
                var text = ""
                var durationMs = 0L
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "messageId" -> messageId = nullableString(reader) ?: ""
                        "text" -> text = nullableString(reader) ?: ""
                        "durationMs" -> durationMs = safeLong(reader)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (messageId.isNotBlank() && text.isNotBlank()) result[messageId] = text to durationMs
            }
            reader.endArray()
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun readConversationBackup(
        reader: JsonReader,
        onProgress: (current: Int, total: Int) -> Unit
    ): ImportResult? {
        val r = reader
            r.beginObject()
            var formatVersion = 0
            var conversation: ConversationEntity? = null
            val messages = mutableListOf<MessageEntity>()
            val artifacts = mutableListOf<ImportedArtifact>()

            while (r.hasNext()) {
                when (r.nextName()) {
                    "formatVersion" -> formatVersion = safeInt(r)
                    "exportedAt" -> r.skipValue()
                    "conversation" -> conversation = readConversationEntity(r)
                    "messages" -> readMessagesArray(r, messages, conversation?.id, onProgress)
                    "artifacts" -> readArtifactsArray(r, artifacts)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (formatVersion < 1 || conversation == null) return null
            return ImportResult(conversation!!, messages, artifacts = artifacts)
    }

    private suspend fun readRemainingBackupPayload(
        reader: JsonReader,
        conversationId: String,
        onProgress: (current: Int, total: Int) -> Unit,
        onMessage: suspend (MessageEntity) -> Unit,
        onArtifact: suspend (ImportedArtifact) -> Unit
    ) {
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "messages" -> readMessagesArrayStreaming(reader, conversationId, onProgress, onMessage)
                "artifacts" -> readArtifactsArrayStreaming(reader, onArtifact)
                "formatVersion", "exportedAt" -> reader.skipValue()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun readConversationEntity(reader: JsonReader): ConversationEntity {
        var id = UUID.randomUUID().toString()
        var title = "导入会话"
        var type = "chat"
        var model = SettingsManager.DEFAULT_MODEL
        var pinned = false
        var createdAt = System.currentTimeMillis()
        var updatedAt = createdAt
        var characterPrompt: String? = null
        var characterProfileJson: String? = null
        var summary: String? = null
        var summaryPreview: String? = null
        var summaryStartIndex: Int? = null
        var summaryEndIndex: Int? = null
        var rootNodeId: String? = null
        var activeLeafNodeId: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = nullableString(reader) ?: id
                "title" -> title = nullableString(reader) ?: title
                "type" -> type = nullableString(reader) ?: type
                "model" -> model = nullableString(reader) ?: model
                "pinned" -> pinned = safeBoolean(reader)
                "createdAt" -> createdAt = safeLong(reader)
                "updatedAt" -> updatedAt = safeLong(reader)
                "characterPrompt" -> characterPrompt = nullableString(reader)
                "characterProfileJson" -> characterProfileJson = nullableString(reader)
                "summary" -> summary = nullableString(reader)
                "summaryPreview" -> summaryPreview = nullableString(reader)
                "summaryStartIndex" -> summaryStartIndex = safeInt(reader)
                "summaryEndIndex" -> summaryEndIndex = safeInt(reader)
                "rootNodeId" -> rootNodeId = nullableString(reader)
                "activeLeafNodeId" -> activeLeafNodeId = nullableString(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return ConversationEntity(
            id = id,
            title = title,
            model = model,
            type = type,
            createdAt = createdAt,
            updatedAt = updatedAt,
            pinned = pinned,
            characterPrompt = characterPrompt,
            characterProfileJson = characterProfileJson,
            summary = summary,
            summaryPreview = summaryPreview ?: summary?.take(600),
            summaryStartIndex = summaryStartIndex,
            summaryEndIndex = summaryEndIndex,
            rootNodeId = rootNodeId,
            activeLeafNodeId = activeLeafNodeId
        )
    }

    private fun readMessagesArray(
        reader: JsonReader,
        messages: MutableList<MessageEntity>,
        conversationId: String?,
        onProgress: (current: Int, total: Int) -> Unit
    ) {
        val cid = conversationId ?: UUID.randomUUID().toString()
        reader.beginArray()
        var count = 0
        while (reader.hasNext()) {
            readMessageEntity(reader, cid)?.let {
                messages.add(it)
                count++
                if (count % 50 == 0) onProgress(count, -1)
            }
        }
        reader.endArray()
        onProgress(count, count)
    }

    private suspend fun readMessagesArrayStreaming(
        reader: JsonReader,
        conversationId: String,
        onProgress: (current: Int, total: Int) -> Unit,
        onMessage: suspend (MessageEntity) -> Unit
    ) {
        reader.beginArray()
        var count = 0
        while (reader.hasNext()) {
            readMessageEntity(reader, conversationId)?.let {
                onMessage(it)
                count++
                if (count % 50 == 0) onProgress(count, -1)
            }
        }
        reader.endArray()
        onProgress(count, count)
    }

    private fun readArtifactsArray(
        reader: JsonReader,
        artifacts: MutableList<ImportedArtifact>
    ) {
        reader.beginArray()
        while (reader.hasNext()) {
            readImportedArtifact(reader)?.let(artifacts::add)
        }
        reader.endArray()
    }

    private suspend fun readArtifactsArrayStreaming(
        reader: JsonReader,
        onArtifact: suspend (ImportedArtifact) -> Unit
    ) {
        reader.beginArray()
        while (reader.hasNext()) {
            readImportedArtifact(reader)?.let { onArtifact(it) }
        }
        reader.endArray()
    }

    private fun readImportedArtifact(reader: JsonReader): ImportedArtifact? {
        return try {
            var messageId = ""
            var type = ""
            var title: String? = null
            var content = ""

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "messageId" -> messageId = nullableString(reader) ?: ""
                    "type" -> type = nullableString(reader) ?: ""
                    "title" -> title = nullableString(reader)
                    "content" -> content = nullableString(reader) ?: ""
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (messageId.isBlank() || type.isBlank() || content.isBlank()) {
                null
            } else {
                ImportedArtifact(messageId, type, title, content)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readMessageEntity(reader: JsonReader, conversationId: String): MessageEntity? {
        return try {
            var id = UUID.randomUUID().toString()
            var parentId: String? = null
            var role = ""
            var content = ""
            var timestamp = System.currentTimeMillis()
            var editHistory = ""
            var editCount = 0
            var isHidden = false
            var branchIndex = 0

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id" -> id = nullableString(reader) ?: id
                    "parentId" -> parentId = nullableString(reader)
                    "role" -> role = nullableString(reader) ?: role
                    "content" -> content = nullableString(reader) ?: content
                    "timestamp" -> timestamp = safeLong(reader)
                    "editHistory" -> editHistory = nullableString(reader) ?: ""
                    "editCount" -> editCount = safeInt(reader)
                    "isHidden" -> isHidden = safeBoolean(reader)
                    "branchIndex" -> branchIndex = safeInt(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (role.isBlank()) null else MessageEntity(
                id = id,
                conversationId = conversationId,
                role = role,
                content = content,
                timestamp = timestamp,
                editHistory = editHistory,
                editCount = editCount,
                isHidden = isHidden,
                parentId = parentId,
                branchIndex = branchIndex
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun nullableString(reader: JsonReader): String? {
        return if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            null
        } else {
            reader.nextString()
        }
    }

    private fun safeLong(reader: JsonReader): Long {
        return try {
            when (reader.peek()) {
                JsonToken.NUMBER -> reader.nextLong()
                JsonToken.STRING -> reader.nextString().toLongOrNull() ?: System.currentTimeMillis()
                JsonToken.NULL -> {
                    reader.nextNull()
                    System.currentTimeMillis()
                }
                else -> {
                    reader.skipValue()
                    System.currentTimeMillis()
                }
            }
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun safeInt(reader: JsonReader): Int {
        return try {
            when (reader.peek()) {
                JsonToken.NUMBER -> reader.nextInt()
                JsonToken.STRING -> reader.nextString().toIntOrNull() ?: 0
                JsonToken.NULL -> {
                    reader.nextNull()
                    0
                }
                else -> {
                    reader.skipValue()
                    0
                }
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun safeBoolean(reader: JsonReader): Boolean {
        return try {
            when (reader.peek()) {
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.STRING -> reader.nextString().toBooleanStrictOrNull() ?: false
                JsonToken.NULL -> {
                    reader.nextNull()
                    false
                }
                else -> {
                    reader.skipValue()
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun detectImportFileKind(uri: Uri): ImportFileKind {
        val name = getDisplayName(uri)?.lowercase(Locale.ROOT).orEmpty()
        val mime = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT).orEmpty()
        return when {
            name.endsWith(".zip") ||
                mime == "application/zip" ||
                mime == "application/x-zip-compressed" -> ImportFileKind.ZIP
            name.endsWith(".json") ||
                mime == "application/json" ||
                mime == "text/json" -> ImportFileKind.JSON
            else -> ImportFileKind.UNKNOWN
        }
    }

    private fun getDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun Throwable.isStorageFullError(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is SQLiteFullException || current is SQLiteDiskIOException) return true
            val message = current.message.orEmpty()
            if (
                message.contains("No space left", ignoreCase = true) ||
                message.contains("database or disk is full", ignoreCase = true) ||
                message.contains("ENOSPC", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun MessageEntity.isSummaryMessage(): Boolean {
        val text = content.trimStart()
        return text.startsWith("对话摘要：") || text.startsWith("对话摘要:")
    }

    private val importedCount = AtomicInteger(0)

    companion object {
        const val FILE_TYPE_ERROR = "文件类型不正确，请选择 JSON 或 ZIP 文件"
        const val ZIP_ERROR = "ZIP 文件损坏或无法解压"
        const val JSON_ERROR = "JSON 格式错误，请确认文件未被修改或损坏"
        const val STORAGE_FULL_ERROR = "手机存储空间不足，请释放空间后重试"
        const val GENERIC_IMPORT_ERROR = "导入失败，请重试或更换文件"
    }
}

data class ImportResult(
    val conversation: ConversationEntity,
    val messages: List<MessageEntity>,
    val reasoning: Map<String, Pair<String, Long>> = emptyMap(),
    val artifacts: List<ImportedArtifact> = emptyList()
)

data class BackupJsonImportResult(
    val recognized: Boolean,
    val importedConversationId: String?
)

data class ImportedArtifact(
    val messageId: String,
    val type: String,
    val title: String?,
    val content: String
)
