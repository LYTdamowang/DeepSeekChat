package com.deepseekchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownMessage(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)
    val quoteColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)

    val blocks = remember(content, isStreaming) { parseMarkdown(content, isStreaming) }
    ClearSelectionOnCopyContainer {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> HeadingBlock(block)
                    is MarkdownBlock.Paragraph -> ParagraphBlock(block.text, codeBackground)
                    is MarkdownBlock.ListGroup -> ListBlock(block, codeBackground)
                    is MarkdownBlock.Quote -> QuoteBlock(block.text, quoteColor, codeBackground)
                    is MarkdownBlock.Code -> CodeBlock(block.code, block.language, codeBackground)
                    is MarkdownBlock.Table -> TableBlock(block.rows, codeBackground)
                    MarkdownBlock.Divider -> DividerBlock()
                }
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: MarkdownBlock.Heading) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp)
        2 -> MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)
        else -> MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp)
    }
    Text(
        text = inlineMarkdown(block.text, MaterialTheme.colorScheme.surfaceVariant),
        style = style.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = if (block.level == 1) 6.dp else 3.dp)
    )
}

@Composable
private fun ParagraphBlock(text: String, codeBackground: Color) {
    Text(
        text = inlineMarkdown(text.trim(), codeBackground),
        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun ListBlock(block: MarkdownBlock.ListGroup, codeBackground: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.items.forEach { item ->
            ListItemRow(item, codeBackground)
        }
    }
}

@Composable
private fun ListItemRow(item: MarkdownListItem, codeBackground: Color) {
    Row(
        modifier = Modifier.padding(start = (item.indent * 18).dp),
        verticalAlignment = Alignment.Top
    ) {
        if (item.taskChecked != null) {
            TaskMarker(
                checked = item.taskChecked,
                modifier = Modifier.padding(top = 5.dp, end = 8.dp)
            )
        } else {
            Text(
                text = item.marker,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = if (item.ordered) 30.dp else 22.dp)
            )
        }
        Text(
            text = inlineMarkdown(item.text, codeBackground),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DividerBlock() {
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        thickness = 0.8.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    )
}

@Composable
private fun TaskMarker(
    checked: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(14.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(
                if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent
            )
            .border(
                1.dp,
                if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.72f),
                RoundedCornerShape(3.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun QuoteBlock(text: String, quoteColor: Color, codeBackground: Color) {
    BlockContainer(
        label = "\u5f15\u7528",
        copyText = text,
        background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier
                    .padding(end = 9.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(quoteColor, RoundedCornerShape(2.dp))
            )
            Text(
                text = inlineMarkdown(text, codeBackground),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CodeBlock(code: String, language: String?, codeBackground: Color) {
    val codeText = code.trimEnd()
    val languageLabel = language?.takeIf { it.isNotBlank() } ?: "text"
    BlockContainer(
        label = languageLabel,
        copyText = codeText,
        background = codeBackground
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(codeBackground)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
            ) {
                Text(
                    text = codeText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun TableBlock(rows: List<List<String>>, codeBackground: Color) {
    val tableText = remember(rows) {
        rows.joinToString("\n") { row -> row.joinToString("\t") }
    }
    BlockContainer(
        label = "\u8868\u683c",
        copyText = tableText,
        background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.background(
                        if (rowIndex == 0) codeBackground else Color.Transparent
                    )
                ) {
                    row.forEach { cell ->
                        Box(
                            modifier = Modifier
                                .widthIn(min = 92.dp, max = 180.dp)
                                .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
                                .padding(horizontal = 8.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = inlineMarkdown(cell.trim(), codeBackground),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockContainer(
    label: String,
    copyText: String,
    background: Color,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f), shape)
    ) {
        BlockToolbar(label = label, copyText = copyText)
        content()
    }
}

@Composable
private fun BlockToolbar(
    label: String,
    copyText: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.54f))
            .padding(start = 10.dp, top = 5.dp, end = 6.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))
        BlockCopyButton(text = copyText)
    }
}

@Composable
private fun BlockCopyButton(
    text: String,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    DisableSelection {
        Box(
            modifier = modifier
                .size(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.68f))
                .border(
                    0.5.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.24f),
                    RoundedCornerShape(6.dp)
                )
                .clickable { clipboardManager.setText(AnnotatedString(text)) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = "Copy block",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun inlineMarkdown(text: String, codeBackground: Color): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            when {
                text.startsWith("**", index) -> {
                    val end = findClosingMarker(text, "**", index + 2)
                    if (end > index) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(text.substring(index + 2, end))
                        }
                        index = end + 2
                    } else if (isLikelyDanglingAsterisk(text, index, 2)) {
                        index += 2
                    } else {
                        append(text[index++])
                    }
                }
                text[index] == '`' -> {
                    val end = text.indexOf('`', index + 1)
                    if (end > index) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBackground
                            )
                        ) {
                            append(text.substring(index + 1, end))
                        }
                        index = end + 1
                    } else {
                        append(text[index++])
                    }
                }
                text[index] == '*' -> {
                    val end = findClosingMarker(text, "*", index + 1)
                    if (end > index && !text.startsWith("**", index)) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(index + 1, end))
                        }
                        index = end + 1
                    } else if (isLikelyDanglingAsterisk(text, index, 1)) {
                        index += 1
                    } else {
                        append(text[index++])
                    }
                }
                else -> append(text[index++])
            }
        }
    }

private fun findClosingMarker(text: String, marker: String, startIndex: Int): Int {
    var end = text.indexOf(marker, startIndex)
    while (end >= 0) {
        val contentStart = startIndex
        val contentEnd = end
        val hasContent = contentEnd > contentStart
        val openingTouchesText = contentStart < text.length && !text[contentStart].isWhitespace()
        val closingTouchesText = contentEnd > 0 && !text[contentEnd - 1].isWhitespace()
        if (hasContent && openingTouchesText && closingTouchesText) return end
        end = text.indexOf(marker, end + marker.length)
    }
    return -1
}

private fun isLikelyDanglingAsterisk(text: String, index: Int, length: Int): Boolean {
    val before = text.getOrNull(index - 1)
    val after = text.getOrNull(index + length)
    val touchesBefore = before != null && !before.isWhitespace()
    val touchesAfter = after != null && !after.isWhitespace()
    return touchesBefore || touchesAfter
}

private fun parseMarkdown(content: String, isStreaming: Boolean): List<MarkdownBlock> {
    val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.lines()
    val endsWithNewline = normalized.endsWith('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paragraph.joinToString("\n").trim()))
            paragraph.clear()
        }
    }

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()

        if (trimmed.isBlank()) {
            flushParagraph()
            index++
            continue
        }

        if (trimmed.startsWith("```")) {
            flushParagraph()
            val parsedCode = parseCodeBlock(lines, index)
            blocks.add(parsedCode.block)
            index = parsedCode.nextIndex
            continue
        }

        if (horizontalRuleRegex.matches(trimmed)) {
            flushParagraph()
            blocks.add(MarkdownBlock.Divider)
            index++
            continue
        }

        val heading = headingRegex.matchEntire(trimmed)
        if (heading != null) {
            flushParagraph()
            blocks.add(
                MarkdownBlock.Heading(
                    level = heading.groupValues[1].length.coerceIn(1, 6),
                    text = heading.groupValues[2].trim()
                )
            )
            index++
            continue
        }

        val table = parseTableBlock(lines, index, isStreaming, endsWithNewline)
        if (table != null) {
            flushParagraph()
            blocks.add(table.block)
            index = table.nextIndex
            continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph()
            val quote = mutableListOf<String>()
            while (index < lines.size && lines[index].trim().startsWith(">")) {
                quote.add(lines[index].trim().removePrefix(">").trim())
                index++
            }
            blocks.add(MarkdownBlock.Quote(quote.joinToString("\n")))
            continue
        }

        val list = parseListBlock(lines, index, isStreaming, endsWithNewline)
        if (list != null) {
            flushParagraph()
            blocks.add(list.block)
            index = list.nextIndex
            continue
        }

        paragraph.add(line)
        index++
    }

    flushParagraph()
    return blocks.ifEmpty { listOf(MarkdownBlock.Paragraph(content)) }
}

private fun parseCodeBlock(
    lines: List<String>,
    startIndex: Int
): ParsedCodeBlock {
    val firstLine = lines[startIndex].trim()
    val language = parseCodeLanguage(firstLine)
    val code = StringBuilder()
    var index = startIndex + 1
    while (index < lines.size) {
        if (lines[index].trim().startsWith("```")) {
            return ParsedCodeBlock(
                block = MarkdownBlock.Code(code.toString(), language),
                nextIndex = index + 1
            )
        }
        code.append(lines[index])
        if (index != lines.lastIndex) code.append('\n')
        index++
    }
    return ParsedCodeBlock(
        block = MarkdownBlock.Code(code.toString(), language),
        nextIndex = lines.size
    )
}

private fun parseCodeLanguage(fenceLine: String): String? {
    val info = fenceLine.removePrefix("```").trim()
    if (info.isBlank()) return null
    return info
        .substringBefore(' ')
        .substringBefore('{')
        .trim()
        .take(24)
        .ifBlank { null }
}

private fun parseTableBlock(
    lines: List<String>,
    index: Int,
    isStreaming: Boolean,
    endsWithNewline: Boolean
): ParsedTableBlock? {
    if (!isTableStart(lines, index, isStreaming, endsWithNewline)) return null
    val rows = mutableListOf<List<String>>()
    rows.add(parseTableRow(lines[index]))
    var cursor = index + 2
    while (cursor < lines.size && lines[cursor].contains('|') && lines[cursor].isNotBlank()) {
        if (isStreaming && !endsWithNewline && cursor == lines.lastIndex) break
        rows.add(parseTableRow(lines[cursor]))
        cursor++
    }
    return ParsedTableBlock(MarkdownBlock.Table(rows), cursor)
}

private fun parseListBlock(
    lines: List<String>,
    startIndex: Int,
    isStreaming: Boolean,
    endsWithNewline: Boolean
): ParsedListBlock? {
    val items = mutableListOf<MarkdownListItem>()
    var index = startIndex

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed.isBlank()) break

        if (isStreaming && !endsWithNewline && index == lines.lastIndex && looksLikePartialListMarker(trimmed)) {
            break
        }

        val match = listRegex.matchEntire(line)
        if (match != null) {
            items.add(parseListItem(match))
            index++
            continue
        }

        if (items.isNotEmpty() && isListContinuation(line)) {
            val continuation = trimmed
            if (continuation.startsWith("```") ||
                continuation.startsWith(">") ||
                headingRegex.matches(continuation)
            ) {
                break
            }
            val lastIndex = items.lastIndex
            val last = items[lastIndex]
            items[lastIndex] = last.copy(
                text = listOf(last.text, continuation)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            )
            index++
            continue
        }

        break
    }

    if (items.isEmpty()) return null
    return ParsedListBlock(MarkdownBlock.ListGroup(items), index)
}

private fun parseListItem(match: MatchResult): MarkdownListItem {
    val indent = (whitespaceWidth(match.groupValues[1]) / 2).coerceIn(0, 6)
    val rawMarker = match.groupValues[2]
    val ordered = rawMarker.first().isDigit()
    val (taskChecked, text) = parseTaskPrefix(match.groupValues[3].trim())
    return MarkdownListItem(
        indent = indent,
        marker = if (ordered) rawMarker else "\u2022",
        ordered = ordered,
        taskChecked = taskChecked,
        text = text
    )
}

private fun parseTaskPrefix(text: String): Pair<Boolean?, String> {
    if (text.length >= 3 && text[0] == '[' && text[2] == ']') {
        val marker = text[1]
        if (marker == ' ' || marker == 'x' || marker == 'X') {
            val rest = text.drop(3).trimStart()
            return (marker == 'x' || marker == 'X') to rest
        }
    }
    return null to text
}

private fun looksLikePartialListMarker(trimmed: String): Boolean =
    trimmed == "-" || trimmed == "*" || trimmed == "+" || Regex("^\\d+[.)]$").matches(trimmed)

private fun isListContinuation(line: String): Boolean =
    whitespaceWidth(line.takeWhile { it == ' ' || it == '\t' }) > 0 && listRegex.matchEntire(line) == null

private fun whitespaceWidth(value: String): Int =
    value.fold(0) { total, char -> total + if (char == '\t') 4 else 1 }

private fun isTableStart(
    lines: List<String>,
    index: Int,
    isStreaming: Boolean,
    endsWithNewline: Boolean
): Boolean {
    if (index + 1 >= lines.size) return false
    if (isStreaming && !endsWithNewline && index + 1 == lines.lastIndex) return false
    if (!lines[index].contains('|')) return false
    return tableSeparatorRegex.matches(lines[index + 1].trim())
}

private fun parseTableRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

private data class ParsedCodeBlock(val block: MarkdownBlock.Code, val nextIndex: Int)
private data class ParsedTableBlock(val block: MarkdownBlock.Table, val nextIndex: Int)
private data class ParsedListBlock(val block: MarkdownBlock.ListGroup, val nextIndex: Int)

private data class MarkdownListItem(
    val indent: Int,
    val marker: String,
    val ordered: Boolean,
    val taskChecked: Boolean?,
    val text: String
)

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class ListGroup(val items: List<MarkdownListItem>) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val code: String, val language: String?) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    object Divider : MarkdownBlock
}

private val headingRegex = Regex("^(#{1,6})\\s+(.+)$")
private val listRegex = Regex("^(\\s*)([-*+]|\\d+[.)])\\s*(.*)$")
private val horizontalRuleRegex = Regex("^([-*_])(?:\\s*\\1){2,}\\s*$")
private val tableSeparatorRegex = Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$")
