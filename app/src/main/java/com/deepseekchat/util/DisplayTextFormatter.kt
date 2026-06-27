package com.deepseekchat.util

import java.text.Normalizer

fun String.toReadableAiText(): String =
    normalizeForDisplay()
        .decodeCommonHtmlEntities()
        .replaceLatexDelimiters()
        .replaceLatexSymbols()

fun String.normalizeForDisplay(): String {
    if (isEmpty()) return this
    val normalized = Normalizer.normalize(this, Normalizer.Form.NFC)
    val builder = StringBuilder(normalized.length)
    var index = 0
    while (index < normalized.length) {
        val codePoint = normalized.codePointAt(index)
        val keep = when (codePoint) {
            '\n'.code, '\r'.code, '\t'.code -> true
            0xFEFF -> false
            else -> Character.getType(codePoint) != Character.CONTROL.toInt()
        }
        if (keep) builder.appendCodePoint(codePoint)
        index += Character.charCount(codePoint)
    }
    return builder.toString()
}

private fun String.decodeCommonHtmlEntities(): String {
    var text = this
    htmlEntities.forEach { (entity, replacement) ->
        text = text.replace(entity, replacement)
    }
    return numericEntityRegex.replace(text) { match ->
        val raw = match.groupValues[1]
        val value = runCatching {
            if (raw.startsWith("x", ignoreCase = true)) raw.drop(1).toInt(16) else raw.toInt()
        }.getOrNull()
        if (value != null && Character.isValidCodePoint(value)) {
            String(Character.toChars(value))
        } else {
            match.value
        }
    }
}

private fun String.replaceLatexDelimiters(): String =
    replace("\\(", "")
        .replace("\\)", "")
        .replace("\\[", "")
        .replace("\\]", "")
        .replace("\\,", " ")
        .replace("\\;", " ")
        .replace("\\:", " ")
        .replace("\\!", "")

private fun String.replaceLatexSymbols(): String {
    var text = this
    latexSymbols.forEach { (command, replacement) ->
        text = Regex("${Regex.escape(command)}(?![A-Za-z])").replace(text, replacement)
    }
    return text
}

private val htmlEntities = mapOf(
    "&lt;" to "<",
    "&gt;" to ">",
    "&amp;" to "&",
    "&quot;" to "\"",
    "&#39;" to "'",
    "&apos;" to "'",
    "&nbsp;" to " "
)

private val numericEntityRegex = Regex("&#(x[0-9A-Fa-f]+|\\d+);")

private val latexSymbols = listOf(
    "\\rightarrow" to "→",
    "\\Rightarrow" to "⇒",
    "\\leftarrow" to "←",
    "\\Leftarrow" to "⇐",
    "\\leftrightarrow" to "↔",
    "\\Leftrightarrow" to "⇔",
    "\\times" to "×",
    "\\div" to "÷",
    "\\cdot" to "·",
    "\\pm" to "±",
    "\\mp" to "∓",
    "\\leq" to "≤",
    "\\le" to "≤",
    "\\geq" to "≥",
    "\\ge" to "≥",
    "\\neq" to "≠",
    "\\ne" to "≠",
    "\\approx" to "≈",
    "\\equiv" to "≡",
    "\\infty" to "∞",
    "\\sum" to "∑",
    "\\prod" to "∏",
    "\\int" to "∫",
    "\\sqrt" to "√",
    "\\partial" to "∂",
    "\\nabla" to "∇",
    "\\forall" to "∀",
    "\\exists" to "∃",
    "\\in" to "∈",
    "\\notin" to "∉",
    "\\subset" to "⊂",
    "\\subseteq" to "⊆",
    "\\cup" to "∪",
    "\\cap" to "∩",
    "\\alpha" to "α",
    "\\beta" to "β",
    "\\gamma" to "γ",
    "\\delta" to "δ",
    "\\epsilon" to "ε",
    "\\theta" to "θ",
    "\\lambda" to "λ",
    "\\mu" to "μ",
    "\\pi" to "π",
    "\\rho" to "ρ",
    "\\sigma" to "σ",
    "\\tau" to "τ",
    "\\phi" to "φ",
    "\\omega" to "ω",
    "\\Delta" to "Δ",
    "\\Theta" to "Θ",
    "\\Lambda" to "Λ",
    "\\Pi" to "Π",
    "\\Sigma" to "Σ",
    "\\Phi" to "Φ",
    "\\Omega" to "Ω"
).sortedByDescending { it.first.length }
