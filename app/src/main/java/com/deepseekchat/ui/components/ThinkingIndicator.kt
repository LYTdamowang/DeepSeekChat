package com.deepseekchat.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ThinkingIndicator(
    modifier: Modifier = Modifier,
    blockWidth: Dp = 8.dp,
    blockGap: Dp = 4.dp,
    minHeight: Dp = 6.dp,
    maxHeight: Dp = 20.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val blockCount = 5

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(blockGap),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in 0 until blockCount) {
            PixelBlock(
                width = blockWidth,
                minHeight = minHeight,
                maxHeight = maxHeight,
                index = i,
                total = blockCount,
                infiniteTransition = infiniteTransition
            )
        }
    }
}

@Composable
private fun PixelBlock(
    width: Dp,
    minHeight: Dp,
    maxHeight: Dp,
    index: Int,
    total: Int,
    infiniteTransition: androidx.compose.animation.core.InfiniteTransition
) {
    val delay = index * 150
    val duration = 600

    val fraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, delay),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pixelBlock$index"
    )

    val height = minHeight + (maxHeight - minHeight) * fraction

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.primary)
    )
}
