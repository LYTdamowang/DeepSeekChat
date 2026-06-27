package com.deepseekchat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.deepseekchat.util.toReadableAiText

@Composable
fun ReasoningSection(
    reasoningText: String,
    isExpanded: Boolean,
    isStreaming: Boolean,
    thinkingDurationMs: Long,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val headerText = if (isStreaming) {
        if (thinkingDurationMs > 0) "正在思考 (${"%.1f".format(thinkingDurationMs / 1000f)}s)..." else "正在思考..."
    } else {
        "已深度思考 (${"%.1f".format(thinkingDurationMs / 1000f)}s)"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!isStreaming) Modifier.clickable { onToggle() }
                    else Modifier
                )
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isStreaming) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown
                                 else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = headerText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isStreaming) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    val alpha by rememberInfiniteTransition(label = "dot").animateFloat(
                        initialValue = 0.3f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse)
                    )
                    Box(
                        Modifier.size(8.dp).alpha(alpha)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isStreaming || isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 4.dp)
                    .padding(12.dp)
            ) {
                ClearSelectionOnCopyContainer {
                    Text(
                        text = reasoningText.toReadableAiText(),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontStyle = FontStyle.Italic
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
