package com.deepseekchat.ui.sidebar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class CharacterProfile(
    val name: String = "",
    val gender: String = "",
    val age: String = "",
    val relationship: String = "",
    val personality: String = "",
    val extra: String = ""
) {
    fun toJson(): String {
        return buildProfileJson(name, gender, age, relationship, personality, extra)
    }

    fun toSystemPrompt(): String? {
        if (name.isBlank()) return null
        val parts = mutableListOf("你是$name")
        if (gender.isNotBlank()) parts.add("性别$gender")
        if (age.isNotBlank()) parts.add("年龄$age")
        if (relationship.isNotBlank()) parts.add("与用户的关系是$relationship")
        if (personality.isNotBlank()) parts.add("性格$personality")
        if (extra.isNotBlank()) parts.add(extra)
        return parts.joinToString("，") + "。"
    }
}

@Composable
fun CharacterCreateDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, prompt: String, profileJson: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var personality by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }

    // Confirmation dialog
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("确认创建") },
            text = { Text("创建后角色无法更改，是否创建？") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    val profile = CharacterProfile(name, gender, age, relationship, personality, extra)
                    val prompt = profile.toSystemPrompt()
                    val profileJson = buildProfileJson(name, gender, age, relationship, personality, extra)
                    if (prompt != null && name.isNotBlank()) onConfirm(name.trim(), prompt, profileJson)
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("创建角色") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("姓名") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Person, null) },
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = gender, onValueChange = { gender = it },
                        label = { Text("性别") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Wc, null) },
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = age, onValueChange = { age = it },
                        label = { Text("年龄") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Cake, null) },
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = relationship, onValueChange = { relationship = it },
                        label = { Text("关系") }, placeholder = { Text("如：朋友、恋人、助手") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.FavoriteBorder, null) },
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = personality, onValueChange = { personality = it },
                        label = { Text("性格") }, placeholder = { Text("如：温柔、活泼、傲娇") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Psychology, null) },
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = extra, onValueChange = { extra = it },
                        label = { Text("其他补充") }, placeholder = { Text("额外的设定、背景故事等") },
                        minLines = 2,
                        leadingIcon = { Icon(Icons.Filled.Info, null) },
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) showConfirm = true
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        )
    }
}

private fun buildProfileJson(name: String, gender: String, age: String, relationship: String, personality: String, extra: String): String {
    val parts = mutableListOf<String>()
    parts.add("\"name\":\"${name.replace("\"", "\\\"")}\"")
    if (gender.isNotBlank()) parts.add("\"gender\":\"${gender.replace("\"", "\\\"")}\"")
    if (age.isNotBlank()) parts.add("\"age\":\"${age.replace("\"", "\\\"")}\"")
    if (relationship.isNotBlank()) parts.add("\"relationship\":\"${relationship.replace("\"", "\\\"")}\"")
    if (personality.isNotBlank()) parts.add("\"personality\":\"${personality.replace("\"", "\\\"")}\"")
    if (extra.isNotBlank()) parts.add("\"extra\":\"${extra.replace("\"", "\\\"")}\"")
    return "{${parts.joinToString(",")}}"
}
