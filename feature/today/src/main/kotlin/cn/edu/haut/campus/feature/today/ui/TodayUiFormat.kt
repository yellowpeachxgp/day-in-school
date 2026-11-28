package cn.edu.haut.campus.feature.today.ui

import androidx.compose.runtime.staticCompositionLocalOf
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal val DATE_TITLE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM月dd日")

/** 运行期仍为系统时钟；Preview/Roborazzi 可注入固定时钟以避免日期漂移。 */
internal fun interface TodayClock {
    fun now(): ZonedDateTime
}

internal val LocalTodayClock = staticCompositionLocalOf<TodayClock> {
    TodayClock { ZonedDateTime.now(HAUT_ZONE_ID) }
}

internal fun String?.cleanUiText(): String? {
    val trimmed = this?.trim() ?: return null
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase()
    if (lower == "null" || lower == "undefined" || lower == "none" || lower == "nil") return null
    val cleaned = trimmed
        .replace(Regex("""(?i)\s*·\s*null\b"""), "")
        .replace(Regex("""(?i)\bnull\s*·\s*"""), "")
        .trim()
    if (cleaned.isEmpty() || cleaned.equals("null", ignoreCase = true)) return null
    return cleaned
}
