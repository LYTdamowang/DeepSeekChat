package com.deepseekchat.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TokenUsageBar(
    currentTokens: Int,
    maxTokens: Int,
    modifier: Modifier = Modifier
) {
    val ratio = (currentTokens.toFloat() / maxTokens).coerceIn(0f, 1f)
    val fillColor by animateColorAsState(
        targetValue = when {
            ratio < 0.5f -> Color(0xFF4CAF50)
            ratio < 0.8f -> Color(0xFFFFC107)
            else -> Color(0xFFF44336)
        },
        label = "pixelColor"
    )
    val emptyColor = fillColor.copy(alpha = 0.15f)
    val segments = 50
    val filledCount = if (currentTokens > 0) {
        (ratio * segments).toInt().coerceIn(1, segments)
    } else 0
    val blockHeight = 14.dp
    val gap = 2.dp

    val percentText = when {
        ratio < 0.001f -> "<0.1%"
        ratio < 0.01f -> "${"%.1f".format(ratio * 100)}%"
        else -> "${(ratio * 100).toInt()}%"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap)
    ) {
        for (i in 0 until segments) {
            PixelSegment(
                filled = i < filledCount,
                fillColor = fillColor,
                emptyColor = emptyColor,
                height = blockHeight,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = percentText,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
private fun PixelSegment(
    filled: Boolean,
    fillColor: Color,
    emptyColor: Color,
    height: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(if (filled) fillColor else emptyColor)
    )
}
