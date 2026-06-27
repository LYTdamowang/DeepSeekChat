package com.deepseekchat.util

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import com.deepseekchat.data.local.entity.ConversationEntity
import com.deepseekchat.data.local.entity.MessageEntity
import java.io.InputStreamReader
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipInputStream

class DeepSeekFormatImporter {

    suspend fun importFromZip(
        uri: Uri,
        context: Context,
        onConversation: suspend (ImportResult) -> Unit
    ): Int {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "conversations.json") {
                        return parseConversations(JsonReader(InputStreamReader(zip, Charsets.UTF_8)), onConversation)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return 0
    }

    suspend fun importFromJson(
        uri: Uri,
        context: Context,
        onConversation: suspend (ImportResult) -> Unit
    ): Int {
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            parseConversations(JsonReader(InputStreamReader(inputStream, Charsets.UTF_8)), onConversation)
        } ?: 0
    }

    private suspend fun parseConversations(
        reader: JsonReader,
        onConversation: suspend (ImportResult) -> Unit
    ): Int {
        var count = 0
        reader.use { r ->
            if (r.peek() != JsonToken.BEGIN_ARRAY) return 0
            r.beginArray()
            while (r.hasNext()) {
                readConversation(r)?.let { parsed ->
                    onConversation(parsed.toImportResult())
                    count++
                }
            }
            r.endArray()
        }
        return count
    }

    private fun readConversation(reader: JsonReader): OfficialConversation? {
        var id = UUID.randomUUID().toString()
        var title = "DeepSeek 导入会话"
        var insertedAt = ""
        var updatedAt = ""
        var mapping: Map<String, OfficialNode> = emptyMap()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = nullableString(reader) ?: id
                "title" -> title = nullableString(reader) ?: title
                "inserted_at", "create_time" -> insertedAt = nullableString(reader) ?: insertedAt
                "updated_at", "update_time" -> updatedAt = nullableString(reader) ?: updatedAt
                "mapping" -> mapping = readMapping(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (mapping.isEmpty()) return null
        return OfficialConversation(id, title, insertedAt, updatedAt, mapping)
    }

    private fun readMapping(reader: JsonReader): Map<String, OfficialNode> {
        val mapping = linkedMapOf<String, OfficialNode>()
        reader.beginObject()
        while (reader.hasNext()) {
            val nodeId = reader.nextName()
            mapping[nodeId] = readNode(reader)
        }
        reader.endObject()
        return mapping
    }

    private fun readNode(reader: JsonReader): OfficialNode {
        val children = mutableListOf<String>()
        var message: OfficialMessage? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "children" -> {
                    reader.beginArray()
                    while (reader.hasNext()) nullableString(reader)?.let { children.add(it) }
                    reader.endArray()
                }
                "message" -> {
                    message = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        null
                    } else {
                        readMessage(reader)
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return OfficialNode(children, message)
    }

    private fun readMessage(reader: JsonReader): OfficialMessage {
        var insertedAt = ""
        var model: String? = null
        val fragments = mutableListOf<OfficialFragment>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "inserted_at", "create_time" -> insertedAt = nullableString(reader) ?: insertedAt
                "model" -> model = nullableString(reader)
                "fragments" -> {
                    reader.beginArray()
                    while (reader.hasNext()) fragments.add(readFragment(reader))
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return OfficialMessage(insertedAt, model, fragments)
    }

    private fun readFragment(reader: JsonReader): OfficialFragment {
        var type = ""
        var content = ""
        var searchResults: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "type" -> type = nullableString(reader) ?: type
                "content" -> content = nullableString(reader) ?: content
                "results" -> searchResults = readSearchResults(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return OfficialFragment(type, content, searchResults)
    }

    private fun readSearchResults(reader: JsonReader): String? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        val lines = mutableListOf<String>()
        reader.beginArray()
        var index = 1
        while (reader.hasNext()) {
            var title = ""
            var snippet = ""
            var url = ""
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "title" -> title = nullableString(reader) ?: ""
                    "snippet" -> snippet = nullableString(reader) ?: ""
                    "url" -> url = nullableString(reader) ?: ""
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (title.isNotBlank() || snippet.isNotBlank() || url.isNotBlank()) {
                lines.add("$index. $title\n   $snippet\n   $url")
                index++
            }
        }
        reader.endArray()
        return if (lines.isEmpty()) null else "【搜索结果】\n" + lines.joinToString("\n")
    }

    private fun OfficialConversation.toImportResult(): ImportResult {
        val createdAt = parseTimestamp(insertedAt) ?: System.currentTimeMillis()
        val updatedAtMs = parseTimestamp(updatedAt) ?: createdAt
        val builtConversation = buildMessages(id, mapping)
        val model = mapping.values.firstNotNullOfOrNull { it.message?.model?.takeIf(String::isNotBlank) }
            ?: "deepseek-chat"
        val conversation = ConversationEntity(
            id = id,
            title = title.take(200),
            type = "chat",
            model = model,
            createdAt = createdAt,
            updatedAt = updatedAtMs
        )
        return ImportResult(
            conversation = conversation,
            messages = builtConversation.messages,
            reasoning = builtConversation.reasoning,
            artifacts = builtConversation.artifacts
        )
    }

    private fun buildMessages(conversationId: String, mapping: Map<String, OfficialNode>): BuiltConversation {
        val root = mapping["root"] ?: return BuiltConversation(emptyList(), emptyMap(), emptyList())
        val ordered = mutableListOf<Pair<String, String?>>()
        val visited = mutableSetOf<String>()

        fun dfs(nodeId: String, parentMessageId: String?) {
            if (nodeId == "root" || !visited.add(nodeId)) return
            val node = mapping[nodeId] ?: return
            val fragmentResult = node.message?.fragments?.let { processFragments(it) }
            val thisMessageId = if (fragmentResult?.role != null && fragmentResult.content != null) nodeId else parentMessageId
            if (thisMessageId == nodeId) ordered.add(nodeId to parentMessageId)
            node.children.forEach { dfs(it, thisMessageId) }
        }

        root.children.forEach { dfs(it, null) }

        val messages = mutableListOf<MessageEntity>()
        val reasoning = mutableMapOf<String, Pair<String, Long>>()
        val artifacts = mutableListOf<ImportedArtifact>()
        ordered.forEach { (nodeId, parentMessageId) ->
            val node = mapping[nodeId] ?: return@forEach
            val message = node.message ?: return@forEach
            val result = processFragments(message.fragments)
            val role = result.role ?: return@forEach
            val content = result.content ?: return@forEach
            val finalContent = content
            if (role == "assistant" && !result.reasoning.isNullOrBlank()) {
                reasoning[nodeId] = result.reasoning to 0L
            }
            if (role == "assistant" && !result.searchResults.isNullOrBlank()) {
                artifacts.add(
                    ImportedArtifact(
                        messageId = nodeId,
                        type = ARTIFACT_WEB_SEARCH,
                        title = "联网搜索资料",
                        content = result.searchResults
                    )
                )
            }
            messages.add(
                MessageEntity(
                    id = nodeId,
                    conversationId = conversationId,
                    role = role,
                    content = finalContent,
                    timestamp = parseTimestamp(message.insertedAt) ?: System.currentTimeMillis(),
                    parentId = parentMessageId
                )
            )
        }
        return BuiltConversation(messages, reasoning, artifacts)
    }

    private fun processFragments(fragments: List<OfficialFragment>): FragmentResult {
        var role: String? = null
        val thinkParts = mutableListOf<String>()
        val responseParts = mutableListOf<String>()
        val searchParts = mutableListOf<String>()

        fragments.forEach { fragment ->
            when (fragment.type) {
                "REQUEST" -> return FragmentResult("user", fragment.content, null, null)
                "THINK" -> {
                    role = "assistant"
                    if (fragment.content.isNotBlank()) thinkParts.add(fragment.content)
                }
                "RESPONSE" -> {
                    role = "assistant"
                    if (fragment.content.isNotBlank()) responseParts.add(fragment.content)
                }
                "SEARCH" -> fragment.searchResults?.let { searchParts.add(it) }
            }
        }

        if (role == "assistant") {
            return FragmentResult(
                role = "assistant",
                content = responseParts.joinToString("\n").ifBlank { null },
                searchResults = searchParts.joinToString("\n\n").ifEmpty { null },
                reasoning = thinkParts.joinToString("\n").ifBlank { null }
            )
        }
        return FragmentResult(null, null, null, null)
    }

    private fun nullableString(reader: JsonReader): String? {
        return if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            null
        } else {
            reader.nextString()
        }
    }

    private fun parseTimestamp(iso: String): Long? {
        return try {
            OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private data class OfficialConversation(
        val id: String,
        val title: String,
        val insertedAt: String,
        val updatedAt: String,
        val mapping: Map<String, OfficialNode>
    )

    private data class OfficialNode(
        val children: List<String>,
        val message: OfficialMessage?
    )

    private data class OfficialMessage(
        val insertedAt: String,
        val model: String?,
        val fragments: List<OfficialFragment>
    )

    private data class OfficialFragment(
        val type: String,
        val content: String,
        val searchResults: String?
    )

    private data class BuiltConversation(
        val messages: List<MessageEntity>,
        val reasoning: Map<String, Pair<String, Long>>,
        val artifacts: List<ImportedArtifact>
    )

    private data class FragmentResult(
        val role: String?,
        val content: String?,
        val searchResults: String?,
        val reasoning: String?
    )

    companion object {
        const val ARTIFACT_WEB_SEARCH = "web_search"
    }
}
