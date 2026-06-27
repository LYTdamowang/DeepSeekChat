package com.deepseekchat.ui.sidebar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseekchat.data.local.entity.ConversationEntity

@Composable
fun SidebarDrawer(
    currentConversationId: String?,
    onConversationSelected: (String) -> Unit,
    onNewConversation: (String) -> Unit,
    onConversationDeleted: (String) -> Unit = {},
    onNewCharacter: (String) -> Unit = {},
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    viewModel: SidebarViewModel = hiltViewModel(),
    content: @Composable (() -> Unit) -> Unit
) {
    var isOpen by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appVersionName = remember(context) { getInstalledVersionName(context) }

    // Export launcher
    var exportConv by remember { mutableStateOf<ConversationEntity?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null && exportConv != null) {
            viewModel.exportConversation(exportConv!!, uri) { ok ->
                Toast.makeText(context, if (ok) "导出成功" else "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
        exportConv = null
    }

    // Import launcher (app format)
    var importResult by remember { mutableStateOf<ImportUiResult?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importConversation(uri) { result -> importResult = result }
        }
    }

    // DeepSeek ZIP import launcher
    var deepseekImportResult by remember { mutableStateOf<ImportUiResult?>(null) }
    val deepseekImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importDeepSeekZip(uri) { result -> deepseekImportResult = result }
        }
    }

    // Import result dialog
    if (importResult != null) {
        val result = importResult!!
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = { Text("导入会话") },
            text = { Text(result.message) },
            confirmButton = {
                TextButton(onClick = { importResult = null }) { Text("确定") }
            }
        )
    }

    // DeepSeek import result dialog
    if (deepseekImportResult != null) {
        val result = deepseekImportResult!!
        AlertDialog(
            onDismissRequest = { deepseekImportResult = null },
            title = { Text("导入 DeepSeek 数据") },
            text = { Text(result.message) },
            confirmButton = {
                TextButton(onClick = { deepseekImportResult = null }) { Text("确定") }
            }
        )
    }

    // Import progress dialog (non-dismissible)
    if (state.isImporting) {
        AlertDialog(
            onDismissRequest = { /* 不可取消 */ },
            title = { Text("正在导入，请勿关闭应用") },
            text = {
                Column {
                    if (state.importTotal > 0) {
                        Text(
                            "${state.importProgress}/${state.importTotal}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.padding(4.dp))
                        LinearProgressIndicator(
                            progress = { state.importProgress.toFloat() / state.importTotal.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(Modifier.padding(8.dp))
                            Text(
                                if (state.importProgress > 0) "已处理 ${state.importProgress} 个会话"
                                else "正在读取文件...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Dialogs
    var deleteTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var pendingCharacterId by remember { mutableStateOf<String?>(null) }
    var viewCharacter by remember { mutableStateOf<ConversationEntity?>(null) }

    // Character create dialog
    if (pendingCharacterId != null) {
        CharacterCreateDialog(
            onDismiss = {
                viewModel.deleteConversation(pendingCharacterId!!)
                pendingCharacterId = null
            },
            onConfirm = { name, prompt, profileJson ->
                viewModel.setCharacterNameAndPrompt(pendingCharacterId!!, name, prompt, profileJson)
                onNewCharacter(pendingCharacterId!!)
                isOpen = false
                pendingCharacterId = null
            }
        )
    }

    // View character info
    if (viewCharacter != null) {
        val conv = viewCharacter!!
        val profile = parseProfileJson(conv.characterProfileJson)
        AlertDialog(
            onDismissRequest = { viewCharacter = null },
            title = { Text("角色原始信息") },
            text = {
                Column {
                    profile.forEach { (key, value) ->
                        Text("$key：$value", style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewCharacter = null }) { Text("关闭") }
            }
        )
    }

    // Delete confirmation
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话") },
            text = { Text("确定要删除「${deleteTarget!!.title}」吗？\n该操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val deletedId = deleteTarget!!.id
                    viewModel.deleteConversation(deletedId)
                    deleteTarget = null
                    onConversationDeleted(deletedId)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    // Rename dialog
    if (renameTarget != null) {
        var newTitle by remember(renameTarget) { mutableStateOf(renameTarget!!.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newTitle.isNotBlank()) {
                        viewModel.renameConversation(renameTarget!!.id, newTitle.trim())
                    }
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        content { isOpen = true }

        AnimatedVisibility(
            visible = isOpen,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { isOpen = false }
            )
        }

        AnimatedVisibility(
            visible = isOpen,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it }
        ) {
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(top = 48.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("会话列表", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    var addExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { addExpanded = true }) {
                            Icon(Icons.Default.Add, contentDescription = "新建")
                        }
                        DropdownMenu(
                            expanded = addExpanded,
                            onDismissRequest = { addExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("新建对话") },
                                onClick = {
                                    addExpanded = false
                                    viewModel.createNewConversation { newId ->
                                        onNewConversation(newId)
                                        isOpen = false
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("新建角色") },
                                onClick = {
                                    addExpanded = false
                                    viewModel.createNewCharacterConversation { newId ->
                                        pendingCharacterId = newId
                                    }
                                }
                            )
                        }
                    }
                }
                HorizontalDivider()

                val listState = rememberLazyListState()
                LaunchedEffect(state.scrollToTopTrigger) {
                    if (state.scrollToTopTrigger > 0) {
                        listState.animateScrollToItem(0)
                    }
                }
                LaunchedEffect(isOpen, currentConversationId, state.conversations) {
                    if (isOpen && currentConversationId != null) {
                        val currentIndex = state.conversations.indexOfFirst { it.id == currentConversationId }
                        if (currentIndex >= 0) {
                            listState.scrollToItem((currentIndex - 2).coerceAtLeast(0))
                        }
                    }
                }

                LazyColumn(modifier = Modifier.weight(1f), state = listState) {
                    items(state.conversations, key = { it.id }) { conv ->
                        var menuExpanded by remember { mutableStateOf(false) }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                                .clickable {
                                    onConversationSelected(conv.id)
                                    isOpen = false
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (conv.id == currentConversationId)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                else
                                    MaterialTheme.colorScheme.surface
                            ),
                            border = if (conv.id == currentConversationId)
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                            else
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (conv.pinned) {
                                Icon(Icons.Filled.PushPin, null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 4.dp).size(14.dp))
                            }
                            Icon(
                                if (conv.type == "character") Icons.Filled.Face else Icons.Default.Chat,
                                null,
                                tint = if (conv.id == currentConversationId)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(conv.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box {
                                IconButton(
                                    onClick = { menuExpanded = true },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Filled.MoreVert, "更多",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp))
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (conv.pinned) "取消置顶" else "置顶")
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            viewModel.pinConversation(conv.id, !conv.pinned)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("重命名") },
                                        onClick = {
                                            menuExpanded = false
                                            renameTarget = conv
                                        }
                                    )
                                    if (conv.type == "character" && !conv.characterProfileJson.isNullOrBlank()) {
                                        DropdownMenuItem(
                                            text = { Text("查看角色") },
                                            onClick = {
                                                menuExpanded = false
                                                viewCharacter = conv
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("导出会话") },
                                        onClick = {
                                            menuExpanded = false
                                            exportConv = conv
                                            exportLauncher.launch("${conv.title}.zip")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            menuExpanded = false
                                            deleteTarget = conv
                                        }
                                    )
                                }
                            }
                        }
                        }
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    thickness = 2.dp
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 3.dp
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            CompactButton(text = "搜索", icon = Icons.Default.Search) {
                                onSearch(); isOpen = false
                            }
                            Spacer(Modifier.width(24.dp))
                            CompactButton(text = "设置", icon = Icons.Default.Settings) {
                                onSettings(); isOpen = false
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        CompactButton(text = "导入本软件会话记录", icon = Icons.Filled.FileUpload) {
                            importLauncher.launch(arrayOf("application/json", "application/zip", "application/octet-stream"))
                        }
                        Spacer(Modifier.height(10.dp))
                        CompactButton(text = "导入deepseek官方会话记录", icon = Icons.Filled.FileUpload) {
                            deepseekImportLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream"))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "v$appVersionName",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun getInstalledVersionName(context: Context): String {
    return try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        packageInfo.versionName ?: com.deepseekchat.BuildConfig.VERSION_NAME
    } catch (_: Exception) {
        com.deepseekchat.BuildConfig.VERSION_NAME
    }
}

private fun parseProfileJson(json: String?): List<Pair<String, String>> {
    if (json.isNullOrBlank()) return emptyList()
    val labelMap = mapOf(
        "name" to "姓名", "gender" to "性别", "age" to "年龄",
        "relationship" to "关系", "personality" to "性格", "extra" to "其他"
    )
    return try {
        val cleaned = json.trimStart('{').trimEnd('}')
        cleaned.split("\",\"").mapNotNull { pair ->
            val colon = pair.indexOf("\":\"")
            if (colon == -1) null
            else {
                val key = pair.substring(0, colon).trim('"')
                val value = pair.substring(colon + 3).trim('"')
                val label = labelMap[key] ?: key
                label to value
            }
        }
    } catch (_: Exception) { emptyList() }
}
