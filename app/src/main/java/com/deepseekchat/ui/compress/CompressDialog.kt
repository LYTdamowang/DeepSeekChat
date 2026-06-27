package com.deepseekchat.ui.compress

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CompressDialog(
    currentTokens: Int,
    maxTokens: Int,
    totalMessages: Int,
    onConfirm: (keepRecentCount: Int, customMemory: String) -> Unit,
    onDismiss: () -> Unit
) {
    var keepCount by remember { mutableStateOf((totalMessages * 0.5).toInt().coerceAtLeast(1)) }
    var customMemory by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("压缩对话上下文") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "当前上下文: ${"%,d".format(currentTokens)} / ${"%,d".format(maxTokens)} tokens",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                Text("保留最近消息数: $keepCount 条（最多保留 50%）")
                Slider(
                    value = keepCount.toFloat(),
                    onValueChange = { keepCount = it.toInt() },
                    valueRange = 1f..(totalMessages * 0.5).toInt().coerceAtLeast(1).toFloat(),
                    steps = ((totalMessages * 0.5).toInt().coerceAtMost(50) - 2).coerceAtLeast(0)
                )

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = customMemory,
                    onValueChange = { customMemory = it },
                    label = { Text("保留的关键记忆（可选）") },
                    placeholder = { Text("例如：记住我们正在讨论 JWT 登录方案...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    "压缩后预计: 保留最近 $keepCount 条消息 + 摘要",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "压缩成功后，较早的原始消息会从当前分支中移除，只保留摘要用于后续对话；压缩失败会保留原消息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(keepCount, customMemory) }) {
                Text("确认压缩")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
