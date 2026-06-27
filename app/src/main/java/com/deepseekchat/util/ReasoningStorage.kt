package com.deepseekchat.util

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ReasoningEntry(
    val messageId: String,
    val text: String,
    val durationMs: Long
)

class ReasoningStorage(context: Context) {
    private val dir = File(context.filesDir, "chat_store/reasoning").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Map<String, Map<String, Pair<String, Long>>> = emptyMap()

    fun loadConversation(convId: String): Map<String, Pair<String, Long>> {
        val file = fileFor(convId)
        if (!file.exists()) return emptyMap()
        return try {
            json.decodeFromString<List<ReasoningEntry>>(file.readText(Charsets.UTF_8))
                .associate { it.messageId to (it.text to it.durationMs) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveMap(convId: String, map: Map<String, Pair<String, Long>>) {
        try {
            saveMapStrict(convId, map)
        } catch (_: Exception) {
        }
    }

    fun saveMapStrict(convId: String, map: Map<String, Pair<String, Long>>) {
        dir.mkdirs()
        val entries = map.map { (messageId, pair) ->
            ReasoningEntry(messageId, pair.first, pair.second)
        }
        val target = fileFor(convId)
        val temp = File(dir, "${target.name}.tmp")
        temp.writeText(json.encodeToString(entries), Charsets.UTF_8)
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            target.writeText(json.encodeToString(entries), Charsets.UTF_8)
            temp.delete()
        }
    }

    fun deleteConversation(convId: String) {
        runCatching { fileFor(convId).delete() }
    }

    private fun fileFor(convId: String): File {
        val safe = convId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(dir, "$safe.json")
    }
}
