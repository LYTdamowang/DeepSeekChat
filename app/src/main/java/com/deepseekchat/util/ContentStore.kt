package com.deepseekchat.util

import android.content.Context
import com.deepseekchat.data.local.dao.MessageNodeRow
import com.deepseekchat.data.local.entity.MessageContentEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class StoredText(
    val entity: MessageContentEntity,
    val fullText: String
)

@Singleton
class ContentStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val rootDir: File = File(context.filesDir, "chat_store")
    private val contentDir: File = File(rootDir, "content")
    private val summaryDir: File = File(rootDir, "summary")
    private val attachmentDir: File = File(rootDir, "attachments")
    private val reasoningDir: File = File(rootDir, "reasoning")

    init {
        listOf(contentDir, summaryDir, attachmentDir, reasoningDir).forEach { it.mkdirs() }
    }

    fun storeText(text: String, kind: String = KIND_CONTENT, forceFile: Boolean = false): StoredText {
        val id = UUID.randomUUID().toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val preview = buildPreview(text)
        val shouldUseFile = forceFile || bytes.size > INLINE_BYTES_LIMIT
        val now = System.currentTimeMillis()
        val entity = if (shouldUseFile) {
            val dir = dirForKind(kind)
            dir.mkdirs()
            val file = File(dir, "$id.txt")
            file.writeBytes(bytes)
            MessageContentEntity(
                id = id,
                storageType = STORAGE_FILE,
                text = null,
                relativePath = relativePath(file),
                preview = preview,
                byteSize = bytes.size.toLong(),
                sha256 = sha256(bytes),
                createdAt = now
            )
        } else {
            MessageContentEntity(
                id = id,
                storageType = STORAGE_INLINE,
                text = text,
                relativePath = null,
                preview = preview,
                byteSize = bytes.size.toLong(),
                sha256 = sha256(bytes),
                createdAt = now
            )
        }
        return StoredText(entity, text)
    }

    fun read(row: MessageNodeRow): String {
        return when (row.storageType) {
            STORAGE_INLINE -> row.text.orEmpty()
            STORAGE_FILE -> readRelativePath(row.relativePath) ?: row.preview
            else -> row.text ?: row.preview
        }
    }

    fun read(content: MessageContentEntity?): String? {
        if (content == null) return null
        return when (content.storageType) {
            STORAGE_INLINE -> content.text.orEmpty()
            STORAGE_FILE -> readRelativePath(content.relativePath)
            else -> content.text
        }
    }

    fun delete(content: MessageContentEntity) {
        if (content.storageType == STORAGE_FILE) {
            content.relativePath?.let { rel ->
                val file = File(rootDir, rel.removePrefix("chat_store/"))
                if (file.exists() && file.canonicalPath.startsWith(rootDir.canonicalPath)) {
                    file.delete()
                }
            }
        }
    }

    fun deleteRows(rows: List<MessageNodeRow>) {
        rows.forEach { row ->
            if (row.storageType == STORAGE_FILE) {
                row.relativePath?.let { rel ->
                    val file = File(rootDir, rel.removePrefix("chat_store/"))
                    if (file.exists() && file.canonicalPath.startsWith(rootDir.canonicalPath)) {
                        runCatching { file.delete() }
                    }
                }
            }
        }
    }

    fun tempImportDir(): File = File(context.cacheDir, "import_tmp").apply { mkdirs() }

    fun tempExportDir(): File = File(context.cacheDir, "export_tmp").apply { mkdirs() }

    fun cleanupTemp() {
        listOf(tempImportDir(), tempExportDir()).forEach { dir ->
            runCatching { dir.deleteRecursively() }
        }
    }

    private fun readRelativePath(relativePath: String?): String? {
        if (relativePath.isNullOrBlank()) return null
        val normalized = relativePath.removePrefix("chat_store/")
        val file = File(rootDir, normalized)
        return try {
            if (!file.canonicalPath.startsWith(rootDir.canonicalPath) || !file.exists()) null
            else file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun dirForKind(kind: String): File = when (kind) {
        KIND_SUMMARY -> summaryDir
        KIND_ATTACHMENT -> attachmentDir
        KIND_REASONING -> reasoningDir
        else -> contentDir
    }

    private fun relativePath(file: File): String {
        return rootDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
    }

    private fun buildPreview(text: String): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= PREVIEW_CHARS) compact else compact.take(PREVIEW_CHARS) + "..."
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val STORAGE_INLINE = "inline"
        const val STORAGE_FILE = "file"
        const val KIND_CONTENT = "content"
        const val KIND_SUMMARY = "summary"
        const val KIND_ATTACHMENT = "attachment"
        const val KIND_REASONING = "reasoning"
        private const val INLINE_BYTES_LIMIT = 16 * 1024
        private const val PREVIEW_CHARS = 600
    }
}
