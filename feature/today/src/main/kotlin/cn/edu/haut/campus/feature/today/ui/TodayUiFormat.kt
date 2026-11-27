package cn.edu.haut.campus.feature.today.ui

import java.time.format.DateTimeFormatter

internal val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal val DATE_TITLE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM月dd日")

internal fun String?.cleanUiText(): String? {
    val trimmed = this?.trim() ?: return null
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase()
    if (lower == "null" || lower == "undefined" || lower == "none" || lower == "nil") return null
    val cleaned = trimmed
        .replace(Regex("(?i)\s*·\s*null\b"), "")
        .replace(Regex("(?i)\bnull\s*·\s*"), "")
        .trim()
    if (cleaned.isEmpty() || cleaned.equals("null", ignoreCase = true)) return null
    return cleaned
}
