package cn.edu.haut.campus.feature.timetable.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal val SHORT_DATE_FMT = DateTimeFormatter.ofPattern("MM/dd")
internal val PERIOD_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

/** 运行期仍为系统日期；Preview/Roborazzi 可注入固定日期以避免截图漂移。 */
internal fun interface TimetableClock {
    fun today(): LocalDate
}

internal val LocalTimetableClock = androidx.compose.runtime.staticCompositionLocalOf<TimetableClock> {
    TimetableClock { ZonedDateTime.now(HAUT_ZONE_ID).toLocalDate() }
}

internal fun dateMonthLabel(date: LocalDate?): String =
    date?.let { "${it.monthValue}月" } ?: ""

internal val GRID_BUILDING_REGEX = Regex("""^(\d+)号教学楼(\d+[A-Za-z0-9\-]*)$""")

internal fun formatCompactGridRoom(rawRoom: String, compact: Boolean = false): String {
    val cleaned = rawRoom
        .replace("河南工业大学", "")
        .replace("莲花街校区", "")
        .replace("莲花街", "")
        .trim()
    if (cleaned.isEmpty()) return ""
    val m = GRID_BUILDING_REGEX.matchEntire(cleaned)
    if (m != null) {
        return "${m.groupValues[1]}教${m.groupValues[2]}"
    }
    val formatted = cleaned
        .replace("号教学楼", "教")
        .replace("教学楼", "教")
        .replace("号楼", "楼")
    return if (compact) formatted.replace("报告厅", "") else formatted
}
