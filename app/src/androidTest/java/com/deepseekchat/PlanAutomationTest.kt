package com.deepseekchat

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepseekchat.data.local.AppDatabase
import com.deepseekchat.data.local.entity.ConversationEntity
import com.deepseekchat.data.local.entity.MessageEntity
import com.deepseekchat.data.repository.SessionRepository
import com.deepseekchat.util.ContentStore
import com.deepseekchat.util.DeepSeekFormatImporter
import com.deepseekchat.util.ExportImportManager
import com.deepseekchat.util.ReasoningStorage
import com.deepseekchat.util.DocumentParser
import com.deepseekchat.util.toReadableAiText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class PlanAutomationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val createdFiles = mutableListOf<File>()
    private val createdReasoningConversations = mutableListOf<String>()

    @After
    fun cleanup() {
        createdFiles.forEach { runCatching { it.delete() } }
        val reasoning = ReasoningStorage(context)
        createdReasoningConversations.forEach { runCatching { reasoning.deleteConversation(it) } }
    }

    @Test
    fun aiDisplayTextRecognizesCommonSpecialSymbols() {
        val raw = "A \\times B &lt; C \\leq D, \\alpha&#x2192;\\Omega\u0000"
        val display = raw.toReadableAiText()

        assertTrue(display.contains("×"))
        assertTrue(display.contains("<"))
        assertTrue(display.contains("≤"))
        assertTrue(display.contains("α"))
        assertTrue(display.contains("→"))
        assertTrue(display.contains("Ω"))
        assertFalse(display.contains('\u0000'))
    }

    @Test
    fun largeTextAttachmentIsTruncatedWithoutCrashing() {
        val file = tempFile("large-text", ".txt")
        file.writeText("0123456789abcdef\n".repeat(20_000), Charsets.UTF_8)

        val parsed = DocumentParser()
            .readText(context, Uri.fromFile(file), "text/plain")
            .getOrThrow()

        assertNotNull(parsed.notice)
        assertTrue(parsed.content.length <= 201_000)
        assertTrue(parsed.content.contains("0123456789abcdef"))
    }

    @Test
    fun docxAndXlsxAttachmentsReadUsefulText() {
        val docx = tempFile("sample", ".docx")
        ZipOutputStream(docx.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>docx useful text</w:t></w:r></w:p></w:body>
                </w:document>
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }

        val xlsx = tempFile("sample", ".xlsx")
        ZipOutputStream(xlsx.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(
                """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c t="inlineStr"><is><t>sheet useful text</t></is></c></row>
                  </sheetData>
                </worksheet>
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }

        val parser = DocumentParser()
        val parsedDocx = parser.readText(
            context,
            Uri.fromFile(docx),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ).getOrThrow()
        val parsedXlsx = parser.readText(
            context,
            Uri.fromFile(xlsx),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ).getOrThrow()

        assertTrue(parsedDocx.content.contains("docx useful text"))
        assertTrue(parsedXlsx.content.contains("sheet useful text"))
    }

    @Test
    fun deepSeekThinkFragmentsAreStoredAsReasoningOnly() = runBlocking {
        val input = tempFile("official", ".json")
        input.writeText(
            """
            [
              {
                "id": "official-${UUID.randomUUID()}",
                "title": "official import",
                "inserted_at": "2026-06-24T00:00:00Z",
                "updated_at": "2026-06-24T00:00:01Z",
                "mapping": {
                  "root": {"children": ["u1"], "message": null},
                  "u1": {
                    "children": ["a1"],
                    "message": {
                      "inserted_at": "2026-06-24T00:00:00Z",
                      "model": "deepseek-reasoner",
                      "fragments": [{"type": "REQUEST", "content": "hello"}]
                    }
                  },
                  "a1": {
                    "children": [],
                    "message": {
                      "inserted_at": "2026-06-24T00:00:01Z",
                      "model": "deepseek-reasoner",
                      "fragments": [
                        {"type": "THINK", "content": "thinking-secret"},
                        {"type": "RESPONSE", "content": "final-answer"}
                      ]
                    }
                  }
                }
              }
            ]
            """.trimIndent(),
            Charsets.UTF_8
        )

        var result: com.deepseekchat.util.ImportResult? = null
        val count = DeepSeekFormatImporter().importFromJson(Uri.fromFile(input), context) {
            result = it
        }

        assertEquals(1, count)
        val imported = requireNotNull(result)
        val assistant = imported.messages.single { it.role == "assistant" }
        assertEquals("final-answer", assistant.content)
        assertFalse(assistant.content.contains("thinking-secret"))
        assertEquals("thinking-secret", imported.reasoning[assistant.id]?.first)
    }

    @Test
    fun exportZipRoundTripKeepsTreeAndReasoning() {
        val conversationId = "export-${UUID.randomUUID()}"
        val reasoning = ReasoningStorage(context)
        createdReasoningConversations.add(conversationId)
        reasoning.saveMapStrict(conversationId, mapOf("a1" to ("reasoning text" to 321L)))

        val conversation = ConversationEntity(
            id = conversationId,
            title = "round trip",
            model = "deepseek-chat",
            createdAt = 1L,
            updatedAt = 2L,
            rootNodeId = "u1",
            activeLeafNodeId = "a1",
            summary = "full summary"
        )
        val messages = listOf(
            MessageEntity("u1", conversationId, "user", "hello", 1L),
            MessageEntity("a1", conversationId, "assistant", "answer", 2L, parentId = "u1")
        )
        val zip = tempFile("backup", ".zip")

        val manager = ExportImportManager(context, reasoning)
        manager.exportToZip(conversation, messages, Uri.fromFile(zip))
        val imported = manager.importFromBackupZip(Uri.fromFile(zip)) { _, _ -> }

        assertNotNull(imported)
        assertEquals(2, imported!!.messages.size)
        assertEquals("u1", imported.messages.single { it.id == "a1" }.parentId)
        assertEquals("reasoning text", imported.reasoning["a1"]?.first)
        assertEquals("full summary", imported.conversation.summary)
    }

    @Test
    fun importFailureMessagesAreSpecificChinesePrompts() {
        val manager = ExportImportManager(context, ReasoningStorage(context))

        val unsupported = tempFile("unsupported", ".txt")
        unsupported.writeText("not importable", Charsets.UTF_8)
        val unsupportedError = runCatching {
            manager.requireSupportedImportFile(Uri.fromFile(unsupported))
        }.exceptionOrNull()
        assertTrue(unsupportedError is ExportImportManager.ImportUserException)
        assertEquals(ExportImportManager.FILE_TYPE_ERROR, unsupportedError?.message)

        val badJson = tempFile("bad-json", ".json")
        badJson.writeText("{", Charsets.UTF_8)
        assertEquals(
            ExportImportManager.ImportFileKind.JSON,
            manager.requireSupportedImportFile(Uri.fromFile(badJson))
        )
        assertEquals(
            ExportImportManager.JSON_ERROR,
            manager.userMessageForImportFailure(Uri.fromFile(badJson), IllegalStateException("broken json"))
        )

        val badZip = tempFile("bad-zip", ".zip")
        badZip.writeText("not a real zip", Charsets.UTF_8)
        assertEquals(
            ExportImportManager.ImportFileKind.ZIP,
            manager.requireSupportedImportFile(Uri.fromFile(badZip))
        )
        assertEquals(
            ExportImportManager.ZIP_ERROR,
            manager.userMessageForImportFailure(Uri.fromFile(badZip), IllegalStateException("broken zip"))
        )

        assertEquals(
            ExportImportManager.STORAGE_FULL_ERROR,
            manager.userMessageForImportFailure(
                Uri.fromFile(badJson),
                java.io.IOException("No space left on device")
            )
        )
    }

    @Test
    fun exportZipDetectsChineseSummaryMessageWithoutMojibakeFallback() {
        val conversationId = "summary-export-${UUID.randomUUID()}"
        val manager = ExportImportManager(context, ReasoningStorage(context))
        val conversation = ConversationEntity(
            id = conversationId,
            title = "summary export",
            model = "deepseek-chat",
            createdAt = 1L,
            updatedAt = 2L,
            rootNodeId = "s1",
            activeLeafNodeId = "s1",
            summary = null
        )
        val messages = listOf(
            MessageEntity("s1", conversationId, "assistant", "对话摘要：\n后续要记住中文摘要", 1L)
        )
        val zip = tempFile("summary-backup", ".zip")

        manager.exportToZip(conversation, messages, Uri.fromFile(zip))
        val imported = manager.importFromBackupZip(Uri.fromFile(zip)) { _, _ -> }

        assertNotNull(imported)
        assertEquals("对话摘要：\n后续要记住中文摘要", imported!!.conversation.summary)
    }

    @Test
    fun repositoryUsesFilesForLargeContentAndDeletesConversationFiles() = runBlocking {
        val db = newInMemoryDb()
        try {
            val repository = repository(db)
            val conversation = repository.createConversationWithId(
                id = "delete-${UUID.randomUUID()}",
                title = "delete test",
                model = "deepseek-chat"
            )
            createdReasoningConversations.add(conversation.id)

            val message = repository.saveMessage(conversation.id, "user", "A".repeat(50_000))
            val row = db.messageDao().getRowById(conversation.id, message.id)!!
            assertEquals(ContentStore.STORAGE_FILE, row.storageType)
            assertNull(row.text)
            val messageFile = contentFile(row.relativePath)
            assertTrue(messageFile.exists())

            repository.updateSummary(conversation.id, "S".repeat(50_000), 0, 1)
            val summaryContentId = db.conversationDao().getById(conversation.id)!!.summaryContentId!!
            val summaryContent = db.messageDao().getContentById(summaryContentId)!!
            assertEquals(ContentStore.STORAGE_FILE, summaryContent.storageType)
            val summaryFile = contentFile(summaryContent.relativePath)
            assertTrue(summaryFile.exists())

            ReasoningStorage(context).saveMapStrict(conversation.id, mapOf(message.id to ("thinking" to 1L)))
            val reasoningFile = reasoningFile(conversation.id)
            assertTrue(reasoningFile.exists())

            repository.deleteConversation(conversation.id)

            assertNull(db.conversationDao().getById(conversation.id))
            assertFalse(messageFile.exists())
            assertFalse(summaryFile.exists())
            assertFalse(reasoningFile.exists())
        } finally {
            db.close()
        }
    }

    @Test
    fun compressionReplacementKeepsSummaryAndRecentMessagesOnly() = runBlocking {
        val db = newInMemoryDb()
        try {
            val repository = repository(db)
            val conversation = repository.createConversationWithId(
                id = "compress-${UUID.randomUUID()}",
                title = "compress test",
                model = "deepseek-chat"
            )
            val saved = mutableListOf<MessageEntity>()
            repeat(8) { index ->
                val role = if (index % 2 == 0) "user" else "assistant"
                saved.add(repository.saveMessage(conversation.id, role, "message-$index"))
            }

            repository.replaceOldMessagesWithSummary(
                conversationId = conversation.id,
                oldMessageIds = saved.take(4).map { it.id },
                summary = "summary memory"
            )

            val active = repository.getActiveMessagesFull(conversation.id)
            val activeIds = active.map { it.id }.toSet()
            saved.take(4).forEach { assertFalse(activeIds.contains(it.id)) }
            saved.takeLast(4).forEach { assertTrue(activeIds.contains(it.id)) }
            assertTrue(active.any { it.content.contains("summary memory") })
        } finally {
            db.close()
        }
    }

    @Test
    fun compressionRewritesActivePathWithoutDeletingSideBranch() = runBlocking {
        val db = newInMemoryDb()
        try {
            val repository = repository(db)
            val conversation = repository.createConversationWithId(
                id = "branch-compress-${UUID.randomUUID()}",
                title = "branch compress test",
                model = "deepseek-chat"
            )
            val saved = mutableListOf<MessageEntity>()
            repeat(8) { index ->
                val role = if (index % 2 == 0) "user" else "assistant"
                saved.add(repository.saveMessage(conversation.id, role, "main-message-$index"))
            }
            val sideBranch = repository.createBranchFromMessage(
                conversationId = conversation.id,
                originalMessageId = saved[2].id,
                newContent = "side-branch-message"
            )
            repository.switchMessageVersion(conversation.id, sideBranch.id, -1)

            repository.replaceOldMessagesWithSummary(
                conversationId = conversation.id,
                oldMessageIds = saved.take(4).map { it.id },
                summary = "main branch summary"
            )

            val active = repository.getActiveMessagesFull(conversation.id)
            val activeIds = active.map { it.id }.toSet()
            saved.take(4).forEach { assertFalse(activeIds.contains(it.id)) }
            saved.takeLast(4).forEach { assertTrue(activeIds.contains(it.id)) }
            assertTrue(active.any { it.content.contains("main branch summary") })

            val allIds = db.messageDao().getAllRows(conversation.id).map { it.id }.toSet()
            assertTrue(allIds.contains(sideBranch.id))
            assertTrue(allIds.contains(saved[0].id))
            assertTrue(allIds.contains(saved[1].id))

            val summaryNode = active.first { it.content.contains("main branch summary") }
            assertTrue(summaryNode.editCount > 0)
            repository.switchMessageVersion(conversation.id, summaryNode.id, -1)
            val sidePathIds = repository.getActiveMessagesFull(conversation.id).map { it.id }.toSet()
            assertTrue(sidePathIds.contains(saved[0].id))
            assertTrue(sidePathIds.contains(saved[1].id))
            assertTrue(sidePathIds.contains(sideBranch.id))
        } finally {
            db.close()
        }
    }

    @Test
    fun editingFirstMessageCreatesRootVersion() = runBlocking {
        val db = newInMemoryDb()
        try {
            val repository = repository(db)
            val conversation = repository.createConversationWithId(
                id = "root-edit-${UUID.randomUUID()}",
                title = "root edit test",
                model = "deepseek-chat"
            )
            val first = repository.saveMessage(conversation.id, "user", "first version")
            val assistant = repository.saveMessage(conversation.id, "assistant", "answer")

            val edited = repository.createBranchFromMessage(
                conversationId = conversation.id,
                originalMessageId = first.id,
                newContent = "edited first version"
            )

            assertNull(edited.parentId)
            val active = repository.getActiveMessagesFull(conversation.id)
            assertEquals("edited first version", active.first().content)
            assertFalse(active.map { it.id }.contains(assistant.id))
            assertTrue(active.first().editCount > 0)

            repository.switchMessageVersion(conversation.id, edited.id, -1)
            val originalPath = repository.getActiveMessagesFull(conversation.id)
            assertEquals("first version", originalPath.first().content)
            assertTrue(originalPath.map { it.id }.contains(assistant.id))
        } finally {
            db.close()
        }
    }

    @Test
    fun searchResultCanActivateSideBranchAndHiddenMessagesAreSkipped() = runBlocking {
        val db = newInMemoryDb()
        try {
            val repository = repository(db)
            val conversation = repository.createConversationWithId(
                id = "search-${UUID.randomUUID()}",
                title = "search test",
                model = "deepseek-chat"
            )
            val original = repository.saveMessage(conversation.id, "user", "original root")
            val mainAnswer = repository.saveMessage(conversation.id, "assistant", "main active answer")
            val sideBranch = repository.createBranchFromMessage(
                conversationId = conversation.id,
                originalMessageId = original.id,
                newContent = "side search target"
            )
            repository.switchMessageVersion(conversation.id, sideBranch.id, -1)
            val hidden = repository.saveMessage(
                conversationId = conversation.id,
                role = "user",
                content = "hidden search target",
                isHidden = true
            )

            val sideResults = repository.searchMessages("side search").first()
            assertTrue(sideResults.any { it.id == sideBranch.id })
            val hiddenResults = repository.searchMessages("hidden search").first()
            assertFalse(hiddenResults.any { it.id == hidden.id })

            val activated = repository.activatePathToMessage(conversation.id, sideBranch.id)
            assertTrue(activated)
            val activePath = repository.getActiveMessagesFull(conversation.id)
            assertEquals("side search target", activePath.first().content)
            assertFalse(activePath.map { it.id }.contains(mainAnswer.id))

            val hiddenRootConversation = repository.createConversationWithId(
                id = "hidden-root-search-${UUID.randomUUID()}",
                title = "hidden root search test",
                model = "deepseek-chat"
            )
            repository.saveMessage(
                conversationId = hiddenRootConversation.id,
                role = "user",
                content = "[文件] hidden attachment",
                isHidden = true
            )
            val visibleAfterHidden = repository.saveMessage(
                conversationId = hiddenRootConversation.id,
                role = "user",
                content = "visible search result after hidden attachment"
            )

            val hiddenAncestorResults = repository.searchMessages("visible search result").first()
            assertTrue(hiddenAncestorResults.any { it.id == visibleAfterHidden.id })
            assertTrue(repository.activatePathToMessage(hiddenRootConversation.id, visibleAfterHidden.id))
            assertTrue(
                repository.getActiveMessagesFull(hiddenRootConversation.id)
                    .map { it.id }
                    .contains(visibleAfterHidden.id)
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun repeatedOfficialImportSkipsExistingConversation() = runBlocking {
        val db = newInMemoryDb()
        try {
            val repository = repository(db)
            val conversationId = "official-${UUID.randomUUID()}"
            val conversation = ConversationEntity(
                id = conversationId,
                title = "official duplicate test",
                model = "deepseek-chat",
                createdAt = 1L,
                updatedAt = 2L
            )
            val messages = listOf(
                MessageEntity("u1-$conversationId", conversationId, "user", "hello", 1L),
                MessageEntity("a1-$conversationId", conversationId, "assistant", "answer", 2L, parentId = "u1-$conversationId")
            )

            val first = repository.importConversationIfMissing(conversation, messages)
            val second = repository.importConversationIfMissing(conversation, messages)

            assertNotNull(first)
            assertNull(second)
            assertEquals(1, db.conversationDao().getAllConversations().first().size)
            assertEquals(2, db.messageDao().getAllRows(conversationId).size)
        } finally {
            db.close()
        }
    }

    @Test
    fun repeatedOfficialJsonImportSkipsExistingConversation() = runBlocking {
        val db = newInMemoryDb()
        try {
            val repository = repository(db)
            val conversationId = "official-json-${UUID.randomUUID()}"
            val input = tempFile("official-duplicate", ".json")
            input.writeText(
                """
                [
                  {
                    "id": "$conversationId",
                    "title": "official duplicate json",
                    "inserted_at": "2026-06-24T00:00:00Z",
                    "updated_at": "2026-06-24T00:00:01Z",
                    "mapping": {
                      "root": {"children": ["u1"], "message": null},
                      "u1": {
                        "children": ["a1"],
                        "message": {
                          "inserted_at": "2026-06-24T00:00:00Z",
                          "model": "deepseek-chat",
                          "fragments": [{"type": "REQUEST", "content": "hello"}]
                        }
                      },
                      "a1": {
                        "children": [],
                        "message": {
                          "inserted_at": "2026-06-24T00:00:01Z",
                          "model": "deepseek-chat",
                          "fragments": [{"type": "RESPONSE", "content": "answer"}]
                        }
                      }
                    }
                  }
                ]
                """.trimIndent(),
                Charsets.UTF_8
            )
            val importedIds = mutableListOf<String>()
            val importer = DeepSeekFormatImporter()

            val firstCount = importer.importFromJson(Uri.fromFile(input), context) { result ->
                repository.importConversationIfMissing(
                    result.conversation,
                    result.messages,
                    result.reasoning
                )?.let(importedIds::add)
            }
            val secondCount = importer.importFromJson(Uri.fromFile(input), context) { result ->
                repository.importConversationIfMissing(
                    result.conversation,
                    result.messages,
                    result.reasoning
                )?.let(importedIds::add)
            }

            assertEquals(1, firstCount)
            assertEquals(1, secondCount)
            assertEquals(1, importedIds.size)
            assertEquals(1, db.conversationDao().getAllConversations().first().size)
            assertEquals(2, db.messageDao().getAllRows(conversationId).size)
        } finally {
            db.close()
        }
    }

    private fun newInMemoryDb(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun repository(db: AppDatabase): SessionRepository =
        SessionRepository(
            db = db,
            conversationDao = db.conversationDao(),
            messageDao = db.messageDao(),
            contentStore = ContentStore(context),
            reasoningStorage = ReasoningStorage(context)
        )

    private fun tempFile(prefix: String, suffix: String): File {
        val file = File.createTempFile(prefix, suffix, context.cacheDir)
        createdFiles.add(file)
        return file
    }

    private fun contentFile(relativePath: String?): File {
        require(!relativePath.isNullOrBlank())
        return File(context.filesDir, "chat_store/${relativePath.removePrefix("chat_store/")}")
    }

    private fun reasoningFile(conversationId: String): File {
        val safe = conversationId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(context.filesDir, "chat_store/reasoning/$safe.json")
    }
}
