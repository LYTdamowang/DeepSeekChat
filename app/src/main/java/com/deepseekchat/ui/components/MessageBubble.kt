package com.deepseekchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseekchat.util.normalizeForDisplay
import com.deepseekchat.util.toReadableAiText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    role: String,
    content: String,
    isStreaming: Boolean = false,
    timestamp: Long = 0L,
    editHistory: List<com.deepseekchat.ui.chat.ChatViewModel.EditEntry> = emptyList(),
    editCount: Int = 0,
    versionIndex: Int = 0,
    onPrevVersion: (() -> Unit)? = null,
    onNextVersion: (() -> Unit)? = null,
    onCopyFull: (() -> Unit)? = null,
    onEditRequest: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isUser = role == "user"
    val totalVersions = editCount + 1
    val isHistory = versionIndex < editHistory.size
    val displayContent = if (isHistory) editHistory[versionIndex].userContent else content
    val visibleContent = if (isUser) displayContent.normalizeForDisplay() else displayContent.toReadableAiText()
    val bubbleShape = RoundedCornerShape(if (isUser) 16.dp else 10.dp)
    val displayNumber = if (editHistory.isEmpty()) {
        versionIndex + 1
    } else {
        versionIndex + 1 + maxOf(0, editCount - editHistory.size)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            AvatarCircle(
                label = "AI",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                size = 24.dp,
                fontSize = 9.sp
            )
            Spacer(Modifier.width(6.dp))
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = if (isUser) Modifier.widthIn(max = 280.dp) else Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .drawBehind {
                        val shadowColor = Color.Black.copy(alpha = if (isUser) 0.06f else 0.012f)
                        val offsetY = 2.dp.toPx()
                        drawRoundRect(
                            color = shadowColor,
                            topLeft = Offset(0f, offsetY),
                            size = size,
                            cornerRadius = CornerRadius(if (isUser) 16.dp.toPx() else 10.dp.toPx())
                        )
                    }
                    .border(
                        width = 1.dp,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
                        },
                        shape = bubbleShape
                    )
                    .clip(bubbleShape)
                    .background(
                        if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.42f)
                    )
                    .padding(horizontal = if (isUser) 14.dp else 6.dp, vertical = if (isUser) 10.dp else 7.dp)
            ) {
                if (isUser) {
                    ClearSelectionOnCopyContainer {
                        Text(
                            text = visibleContent,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(2.dp)
                        )
                    }
                } else {
                    MarkdownMessage(
                        content = visibleContent,
                        isStreaming = isStreaming,
                        modifier = Modifier
                    )
                }
            }

            // Meta row below bubble (timestamp + actions in one line)
            if (isUser) {
                var expanded by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    if (timestamp > 0) {
                        Text(
                            text = formatTimestamp(timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        )
                    }
                    if (editCount > 0) {
                        IconButton(
                            onClick = { onPrevVersion?.invoke() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Text("<", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            text = "$displayNumber/$totalVersions",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = { onNextVersion?.invoke() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Text(">", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(
                        onClick = { expanded = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        if (onCopyFull != null) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                                        Text(" 复制", modifier = Modifier.padding(start = 8.dp))
                                    }
                                },
                                onClick = { expanded = false; onCopyFull() }
                            )
                        }
                        if (onEditRequest != null) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Edit, null, Modifier.size(16.dp))
                                        Text(" 修改", modifier = Modifier.padding(start = 8.dp))
                                    }
                                },
                                onClick = { expanded = false; onEditRequest() }
                            )
                        }
                    }
                }
            } else {
                if (onCopyFull != null || timestamp > 0 || editCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        if (editCount > 0) {
                            IconButton(
                                onClick = { onPrevVersion?.invoke() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Text("<", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = "$displayNumber/$totalVersions",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(
                                onClick = { onNextVersion?.invoke() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Text(">", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (onCopyFull != null) {
                            IconButton(onClick = onCopyFull, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "复制",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(15.dp))
                            }
                        }
                        if (timestamp > 0) {
                            Text(
                                text = formatTimestamp(timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            }
        }

        if (isUser) {
            Spacer(Modifier.width(8.dp))
            AvatarCircle(
                label = "Me",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun AvatarCircle(
    label: String,
    containerColor: Color,
    contentColor: Color,
    size: androidx.compose.ui.unit.Dp = 28.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 10.sp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = fontSize
            ),
            color = contentColor
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
