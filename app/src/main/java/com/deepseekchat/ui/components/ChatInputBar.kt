package com.deepseekchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    isStreaming: Boolean,
    enabled: Boolean = true,
    attachedFileName: String? = null,
    onRemoveAttachment: (() -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val surfaceModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp)

    Surface(
        modifier = if (onTap != null) {
            surfaceModifier.clickable { onTap.invoke() }
        } else {
            surfaceModifier
        },
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 6.dp,
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            if (attachedFileName != null) {
                Row(
                    modifier = Modifier
                        .padding(start = 12.dp, top = 8.dp, end = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📄",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = attachedFileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { onRemoveAttachment?.invoke() },
                        modifier = Modifier.size(18.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "移除附件",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onAttach,
                    modifier = Modifier.size(44.dp),
                    enabled = enabled && !isStreaming
                ) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = "附件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                onFocused?.invoke()
                            }
                        },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        if (value.isEmpty()) {
                            Text(
                                "输入消息...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        innerTextField()
                    },
                    maxLines = 5,
                    enabled = enabled
                )

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isStreaming) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primary
                        )
                        .clickable { if (isStreaming) onStop() else onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Default.Stop else Icons.Filled.Send,
                        contentDescription = if (isStreaming) "停止" else "发送",
                        tint = if (isStreaming)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
