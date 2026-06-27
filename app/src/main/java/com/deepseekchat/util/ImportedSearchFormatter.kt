package com.deepseekchat.util

data class ImportedSearchSplit(
    val body: String,
    val search: String?
)

fun String.splitImportedSearchResults(): ImportedSearchSplit {
    val header = importedSearchHeaders
        .mapNotNull { marker ->
            val index = indexOf(marker)
            if (index >= 0) marker to index else null
        }
        .minByOrNull { it.second }
        ?: return ImportedSearchSplit(this, null)

    val beforeHeader = substring(0, header.second).trimEnd()
    val dividerIndex = beforeHeader.lastIndexOf("\n---")
    val body = if (dividerIndex >= 0 && beforeHeader.substring(dividerIndex).trim().all { it == '-' }) {
        beforeHeader.substring(0, dividerIndex).trimEnd()
    } else {
        beforeHeader
    }
    val search = substring(header.second)
        .importedSearchResultsForDisplay()
        .takeIf { it.isNotBlank() }
    return ImportedSearchSplit(body, search)
}

fun String.withoutImportedSearchResults(): String =
    splitImportedSearchResults().body

fun String.importedSearchResultsForDisplay(): String {
    var text = trim()
    importedSearchHeaders.forEach { header ->
        if (text.startsWith(header)) {
            text = text.removePrefix(header).trimStart()
        }
    }
    return text
}

private val importedSearchHeaders = listOf(
    "\u3010\u641c\u7d22\u7ed3\u679c\u3011"
)
