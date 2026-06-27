package com.deepseekchat.util

import com.deepseekchat.data.remote.MessageDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenCounter @Inject constructor() {

    fun countMessages(messages: List<MessageDto>): Int {
        return messages.sumOf { estimateTokens(it.content) }
    }

    fun estimateTokens(text: String): Int {
        var chineseChars = 0
        var otherChars = 0

        for (c in text) {
            if (c.code > 127) {
                chineseChars++
            } else {
                otherChars++
            }
        }

        return (chineseChars * 1.5 + otherChars * 0.3).toInt()
    }

    fun formatTokenCount(tokens: Int): String {
        return when {
            tokens >= 1_000_000 -> "${"%.1f".format(tokens / 1_000_000.0)}M"
            tokens >= 1_000 -> "${tokens / 1_000}K"
            else -> tokens.toString()
        }
    }
}
