package com.deepseekchat.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ParsedDocument(
    val fileName: String,
    val content: String,
    val mimeType: String,
    val notice: String? = null
)

@Singleton
class DocumentParser @Inject constructor() {

    fun readText(context: Context, uri: Uri, mimeType: String): Result<ParsedDocument> = runCatching {
        try {
            val fileName = getFileName(context, uri) ?: "未知文件"
            val fileSize = getFileSize(context, uri)
            val lowerName = fileName.lowercase(Locale.ROOT)
            val parsed = when {
                isTextFile(mimeType, lowerName) -> readTextFile(context, uri)
                isPdfFile(mimeType, lowerName) -> readPdf(context, uri, fileSize)
                isDocxFile(mimeType, lowerName) -> readDocx(context, uri)
                isXlsxFile(mimeType, lowerName) -> readXlsx(context, uri)
                else -> readTextFile(context, uri)
            }
            if (parsed.text.isBlank()) {
                throw IllegalStateException("未读取到可用文本内容，请确认文件不是图片扫描件或加密文件")
            }
            val notice = buildNotice(parsed, fileSize)
            ParsedDocument(
                fileName = if (parsed.truncated) "$fileName（已读取部分内容）" else fileName,
                content = buildAttachmentText(parsed.text, notice),
                mimeType = mimeType,
                notice = notice
            )
        } catch (error: OutOfMemoryError) {
            throw IllegalStateException("文件过大，手机内存不足，请拆分文件后重试")
        }
    }

    fun getFileName(context: Context, uri: Uri): String? {
        return runCatching {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && it.moveToFirst()) it.getString(nameIndex) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun getFileSize(context: Context, uri: Uri): Long? {
        return runCatching {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && it.moveToFirst() && !it.isNull(sizeIndex)) it.getLong(sizeIndex) else null
            }
        }.getOrNull()?.takeIf { it >= 0 }
    }

    private fun readStream(context: Context, uri: Uri): InputStream {
        return context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开文件")
    }

    private fun isTextFile(mimeType: String, lowerName: String): Boolean =
        mimeType.startsWith("text/") ||
            lowerName.endsWith(".txt") ||
            lowerName.endsWith(".md") ||
            lowerName.endsWith(".csv") ||
            lowerName.endsWith(".json") ||
            lowerName.endsWith(".xml") ||
            lowerName.endsWith(".log")

    private fun isPdfFile(mimeType: String, lowerName: String): Boolean =
        mimeType == "application/pdf" || lowerName.endsWith(".pdf")

    private fun isDocxFile(mimeType: String, lowerName: String): Boolean =
        mimeType.contains("wordprocessingml") || lowerName.endsWith(".docx")

    private fun isXlsxFile(mimeType: String, lowerName: String): Boolean =
        mimeType.contains("spreadsheetml") || lowerName.endsWith(".xlsx")

    private fun readTextFile(context: Context, uri: Uri): ParseResult {
        val builder = StringBuilder()
        var truncated = false
        readStream(context, uri).buffered().use { input ->
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(READ_BUFFER_CHARS)
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    if (appendLimited(builder, String(buffer, 0, read))) {
                        truncated = true
                        break
                    }
                }
            }
        }
        return ParseResult(builder.toString(), truncated)
    }

    private fun readPdf(context: Context, uri: Uri, fileSize: Long?): ParseResult {
        if (fileSize != null && fileSize > PDF_SAFE_BYTES) {
            throw IllegalStateException("PDF 文件过大，为避免手机内存不足，请拆分文件后再导入")
        }
        val builder = StringBuilder()
        var truncated = false
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        readStream(context, uri).buffered().use { input ->
            val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(input)
            doc.use { document ->
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                stripper.sortByPosition = true
                val pageCount = document.numberOfPages
                val pagesToRead = minOf(pageCount, MAX_PDF_PAGES)
                for (page in 1..pagesToRead) {
                    stripper.startPage = page
                    stripper.endPage = page
                    if (appendLimited(builder, stripper.getText(document) + "\n")) {
                        truncated = true
                        break
                    }
                }
                if (pageCount > pagesToRead) truncated = true
            }
        }
        return ParseResult(builder.toString(), truncated)
    }

    private fun readDocx(context: Context, uri: Uri): ParseResult {
        val builder = StringBuilder()
        var truncated = false
        readStream(context, uri).buffered().use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        truncated = parseDocxXml(zip, builder)
                        break
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return ParseResult(builder.toString(), truncated)
    }

    private fun parseDocxXml(input: InputStream, output: StringBuilder): Boolean {
        val parser = newXmlParser(input)
        var inTextTag = false
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "w:t" || parser.name == "t") inTextTag = true
                }
                XmlPullParser.TEXT -> {
                    if (inTextTag && appendLimited(output, parser.text.orEmpty())) return true
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "w:t" || parser.name == "t") inTextTag = false
                    if ((parser.name == "w:p" || parser.name == "p") && appendLimited(output, "\n")) return true
                }
            }
            eventType = parser.next()
        }
        return false
    }

    private fun readXlsx(context: Context, uri: Uri): ParseResult {
        val builder = StringBuilder()
        val sharedStrings = mutableListOf<String>()
        var truncated = false
        var sharedStringsTruncated = false
        var sheetsRead = 0
        readStream(context, uri).buffered().use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && !truncated) {
                    when {
                        entry.name == "xl/sharedStrings.xml" -> {
                            sharedStringsTruncated = parseXlsxSharedStrings(zip, sharedStrings)
                        }
                        entry.name.startsWith("xl/worksheets/sheet") && entry.name.endsWith(".xml") -> {
                            sheetsRead++
                            if (sheetsRead > MAX_XLSX_SHEETS) {
                                truncated = true
                            } else {
                                if (appendLimited(builder, "\n[工作表：${entry.name.substringAfterLast('/')}]\n")) {
                                    truncated = true
                                } else {
                                    truncated = parseXlsxSheet(zip, sharedStrings, builder)
                                }
                            }
                        }
                    }
                    if (!truncated) {
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        }
        return ParseResult(builder.toString(), truncated || sharedStringsTruncated)
    }

    private fun parseXlsxSharedStrings(input: InputStream, strings: MutableList<String>): Boolean {
        val parser = newXmlParser(input)
        val current = StringBuilder()
        var inSharedItem = false
        var inTextTag = false
        var storedChars = strings.sumOf { it.length }
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "si" -> {
                            inSharedItem = true
                            current.clear()
                        }
                        "t" -> if (inSharedItem) inTextTag = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inSharedItem && inTextTag) current.append(parser.text.orEmpty())
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "t" -> inTextTag = false
                        "si" -> {
                            val text = current.toString()
                            if (strings.size >= MAX_SHARED_STRINGS ||
                                storedChars + text.length > MAX_SHARED_STRING_CHARS
                            ) {
                                return true
                            }
                            strings.add(text)
                            storedChars += text.length
                            inSharedItem = false
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return false
    }

    private fun parseXlsxSheet(
        input: InputStream,
        sharedStrings: List<String>,
        output: StringBuilder
    ): Boolean {
        val parser = newXmlParser(input)
        var currentRow = 1
        var lastWrittenRow = 1
        var rowsSeen = 0
        var inCell = false
        var inValue = false
        var inInlineText = false
        var cellType = ""
        val cellValue = StringBuilder()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> {
                            rowsSeen++
                            if (rowsSeen > MAX_XLSX_ROWS) return true
                            currentRow = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: currentRow
                        }
                        "c" -> {
                            cellType = parser.getAttributeValue(null, "t") ?: ""
                            inCell = true
                            cellValue.clear()
                        }
                        "v" -> if (inCell) inValue = true
                        "t" -> if (inCell && (cellType == "inlineStr" || cellType == "str")) inInlineText = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inValue || inInlineText) cellValue.append(parser.text.orEmpty())
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "v" -> inValue = false
                        "t" -> inInlineText = false
                        "c" -> {
                            val text = resolveCellText(cellType, cellValue.toString(), sharedStrings)
                            if (text.isNotBlank()) {
                                if (currentRow > lastWrittenRow && appendLimited(output, "\n")) return true
                                if (appendLimited(output, text)) return true
                                if (appendLimited(output, "\t")) return true
                                lastWrittenRow = currentRow
                            }
                            inCell = false
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return false
    }

    private fun resolveCellText(cellType: String, rawValue: String, sharedStrings: List<String>): String {
        return if (cellType == "s" && rawValue.isNotBlank()) {
            val index = rawValue.toIntOrNull()
            if (index != null && index in sharedStrings.indices) sharedStrings[index] else ""
        } else {
            rawValue
        }
    }

    private fun newXmlParser(input: InputStream): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(InputStreamReader(input, Charsets.UTF_8))
        return parser
    }

    private fun appendLimited(builder: StringBuilder, text: String): Boolean {
        if (text.isEmpty()) return false
        val remaining = MAX_ATTACHMENT_CHARS - builder.length
        if (remaining <= 0) return true
        return if (text.length > remaining) {
            builder.append(text, 0, remaining)
            true
        } else {
            builder.append(text)
            false
        }
    }

    private fun buildNotice(parsed: ParseResult, fileSize: Long?): String? {
        if (!parsed.truncated) return null
        val sizeText = fileSize?.let { "，文件大小约 ${formatBytes(it)}" }.orEmpty()
        return "文件较大$sizeText，已优先读取前面的可用文本内容，未读取部分不会发送给 AI。"
    }

    private fun buildAttachmentText(text: String, notice: String?): String {
        val normalized = text.trim()
        return if (notice == null) {
            normalized
        } else {
            "【文件读取提示】$notice\n\n$normalized"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1) "%.1f MB".format(Locale.ROOT, mb) else "${bytes / 1024} KB"
    }

    private data class ParseResult(
        val text: String,
        val truncated: Boolean
    )

    companion object {
        private const val MAX_ATTACHMENT_CHARS = 200_000
        private const val READ_BUFFER_CHARS = 8 * 1024
        private const val PDF_SAFE_BYTES = 40L * 1024L * 1024L
        private const val MAX_PDF_PAGES = 80
        private const val MAX_XLSX_SHEETS = 20
        private const val MAX_XLSX_ROWS = 10_000
        private const val MAX_SHARED_STRINGS = 50_000
        private const val MAX_SHARED_STRING_CHARS = 1_000_000
    }
}
