package com.deepseekchat.ui.sidebar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepseekchat.data.local.entity.ConversationEntity
import com.deepseekchat.data.repository.SessionRepository
import com.deepseekchat.util.ExportImportManager
import com.deepseekchat.util.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SidebarState(
    val conversations: List<ConversationEntity> = emptyList(),
    val isLoading: Boolean = true,
    val isImporting: Boolean = false,
    val importProgress: Int = 0,
    val importTotal: Int = 0,
    val scrollToTopTrigger: Int = 0
)

data class ImportUiResult(
    val ok: Boolean,
    val importedCount: Int,
    val message: String
)

@HiltViewModel
class SidebarViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val settingsManager: SettingsManager,
    private val exportImportManager: ExportImportManager
) : ViewModel() {

    private val _state = MutableStateFlow(SidebarState())
    val state: StateFlow<SidebarState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessionRepository.getAllConversations().collect { conversations ->
                if (!_state.value.isImporting) {
                    _state.update { it.copy(conversations = conversations, isLoading = false) }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            sessionRepository.cleanupStorageGarbage()
        }
    }

    fun createNewConversation(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val conv = sessionRepository.createConversation(
                title = "新对话",
                model = settingsManager.getModel()
            )
            onCreated(conv.id)
        }
    }

    fun createNewCharacterConversation(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val conv = sessionRepository.createConversation(
                title = "新角色",
                model = settingsManager.getModel(),
                type = "character"
            )
            onCreated(conv.id)
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            sessionRepository.deleteConversation(conversationId)
        }
    }

    fun pinConversation(id: String, pinned: Boolean) {
        viewModelScope.launch {
            sessionRepository.pinConversation(id, pinned)
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch {
            sessionRepository.renameConversation(id, title)
        }
    }

    fun setCharacterNameAndPrompt(id: String, name: String, prompt: String, profileJson: String) {
        viewModelScope.launch {
            sessionRepository.renameConversation(id, name)
            sessionRepository.setCharacterPrompt(id, prompt, profileJson)
        }
    }

    fun exportConversation(conv: ConversationEntity, uri: android.net.Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val messages = sessionRepository.getAllMessages(conv.id)
                val artifacts = sessionRepository.getAllWebSearchArtifacts(conv.id)
                val latestConversation = sessionRepository.getConversation(conv.id) ?: conv
                val fullSummary = sessionRepository.getConversationSummary(conv.id)
                exportImportManager.exportToZip(
                    latestConversation.copy(summary = fullSummary ?: latestConversation.summary),
                    messages,
                    uri,
                    artifacts
                )
                onDone(true)
            } catch (_: Exception) {
                onDone(false)
            }
        }
    }

    fun importConversation(uri: android.net.Uri, onDone: (ImportUiResult) -> Unit) {
        _state.update { it.copy(isImporting = true, importProgress = 0, importTotal = 0) }
        viewModelScope.launch {
            val importedIds = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) {
                    val progress: (Int, Int) -> Unit = { current, total ->
                        _state.update { it.copy(importProgress = current, importTotal = total) }
                    }
                    when (exportImportManager.requireSupportedImportFile(uri)) {
                        ExportImportManager.ImportFileKind.ZIP -> {
                            val backup = exportImportManager.importFromBackupZip(uri, progress)
                            if (backup != null) {
                                sessionRepository.importConversationIfMissing(
                                    backup.conversation,
                                    backup.messages,
                                    backup.reasoning,
                                    backup.artifacts
                                )?.let(importedIds::add)
                            } else {
                                val count = exportImportManager.importFromDeepSeekZip(
                                    uri = uri,
                                    onProgress = { current -> _state.update { it.copy(importProgress = current) } },
                                    onConversation = { result ->
                                        sessionRepository.importConversationIfMissing(
                                            result.conversation,
                                            result.messages,
                                            result.reasoning,
                                            result.artifacts
                                        )?.let(importedIds::add)
                                    }
                                )
                                if (count <= 0) {
                                    throw ExportImportManager.ImportUserException(ExportImportManager.ZIP_ERROR)
                                }
                            }
                        }
                        ExportImportManager.ImportFileKind.JSON -> {
                            val legacy = exportImportManager.importFromJsonStreamed(
                                uri = uri,
                                onProgress = progress,
                                importConversation = { conversation, readPayload ->
                                    sessionRepository.importConversationStreamedIfMissing(
                                        conversation = conversation,
                                        readPayload = readPayload
                                    )
                                }
                            )
                            if (legacy.recognized) {
                                legacy.importedConversationId?.let(importedIds::add)
                            } else {
                                val count = exportImportManager.importFromDeepSeekJson(
                                    uri = uri,
                                    onProgress = { current -> _state.update { it.copy(importProgress = current) } },
                                    onConversation = { result ->
                                        sessionRepository.importConversationIfMissing(
                                            result.conversation,
                                            result.messages,
                                            result.reasoning,
                                            result.artifacts
                                        )?.let(importedIds::add)
                                    }
                                )
                                if (count <= 0) {
                                    throw ExportImportManager.ImportUserException(ExportImportManager.JSON_ERROR)
                                }
                            }
                        }
                        ExportImportManager.ImportFileKind.UNKNOWN -> {
                            throw ExportImportManager.ImportUserException(ExportImportManager.FILE_TYPE_ERROR)
                        }
                    }
                }
                refreshConversations()
                _state.update { it.copy(isImporting = false, scrollToTopTrigger = it.scrollToTopTrigger + 1) }
                onDone(successResult(importedIds.size))
            } catch (e: Exception) {
                importedIds.forEach { runCatching { sessionRepository.deleteConversation(it) } }
                refreshConversations()
                _state.update { it.copy(isImporting = false) }
                onDone(ImportUiResult(false, 0, exportImportManager.userMessageForImportFailure(uri, e)))
            }
        }
    }

    fun importDeepSeekZip(uri: android.net.Uri, onDone: (ImportUiResult) -> Unit) {
        _state.update { it.copy(isImporting = true, importProgress = 0, importTotal = 0) }
        viewModelScope.launch {
            val importedIds = mutableListOf<String>()
            try {
                val parsedCount = withContext(Dispatchers.IO) {
                    when (exportImportManager.requireSupportedImportFile(uri)) {
                        ExportImportManager.ImportFileKind.ZIP -> exportImportManager.importFromDeepSeekZip(
                            uri = uri,
                            onProgress = { current ->
                                _state.update { it.copy(importProgress = current) }
                            },
                            onConversation = { result ->
                                sessionRepository.importConversationIfMissing(
                                    result.conversation,
                                    result.messages,
                                    result.reasoning,
                                    result.artifacts
                                )?.let(importedIds::add)
                            }
                        )
                        ExportImportManager.ImportFileKind.JSON -> exportImportManager.importFromDeepSeekJson(
                            uri = uri,
                            onProgress = { current ->
                                _state.update { it.copy(importProgress = current) }
                            },
                            onConversation = { result ->
                                sessionRepository.importConversationIfMissing(
                                    result.conversation,
                                    result.messages,
                                    result.reasoning,
                                    result.artifacts
                                )?.let(importedIds::add)
                            }
                        )
                        ExportImportManager.ImportFileKind.UNKNOWN -> {
                            throw ExportImportManager.ImportUserException(ExportImportManager.FILE_TYPE_ERROR)
                        }
                    }
                }
                if (parsedCount <= 0) {
                    val kind = exportImportManager.requireSupportedImportFile(uri)
                    val message = if (kind == ExportImportManager.ImportFileKind.JSON) {
                        ExportImportManager.JSON_ERROR
                    } else {
                        ExportImportManager.ZIP_ERROR
                    }
                    throw ExportImportManager.ImportUserException(message)
                }
                refreshConversations()
                _state.update { it.copy(isImporting = false, importTotal = parsedCount, importProgress = parsedCount) }
                onDone(successResult(importedIds.size))
            } catch (e: Exception) {
                importedIds.forEach { runCatching { sessionRepository.deleteConversation(it) } }
                refreshConversations()
                _state.update { it.copy(isImporting = false) }
                onDone(ImportUiResult(false, 0, exportImportManager.userMessageForImportFailure(uri, e)))
            }
        }
    }

    fun clearImportState() {
        _state.update { it.copy(isImporting = false, importProgress = 0, importTotal = 0) }
    }

    private fun refreshConversations() {
        viewModelScope.launch {
            val conversations = sessionRepository.getAllConversations().first()
            _state.update { it.copy(conversations = conversations) }
        }
    }

    private fun successResult(importedCount: Int): ImportUiResult {
        return if (importedCount <= 0) {
            ImportUiResult(true, importedCount, "没有新增会话，文件中的会话已存在")
        } else {
            ImportUiResult(true, importedCount, "导入完成，共新增 ${importedCount} 个会话")
        }
    }
}
