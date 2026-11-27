package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.model.PeriodTime
import java.time.LocalTime

/** 可重建的默认参考作息；用户擦除个人覆盖时只恢复这些公开/离线模板，绝不恢复用户内容。 */
internal object ReferencePeriodTimes {
    private val MANUAL_STARTS = listOf(
        "08:00", "08:55", "10:00", "10:55",
        "14:00", "14:55", "16:00", "16:55",
        "19:00", "19:55", "20:50", "21:45"
    )

    fun forTimeTableId(timeTableId: String): List<PeriodTime> = when (timeTableId) {
        HautMockSeedData.DEFAULT_TIMETABLE_ID -> HautMockSeedData.defaultPeriodTimes
        "manual_default" -> MANUAL_STARTS.mapIndexed { index, raw ->
            val start = LocalTime.parse(raw)
            PeriodTime(
                id = "manual_period_" + (index + 1),
                timeTableId = timeTableId,
                periodNo = index + 1,
                startLocalTime = start,
                endLocalTime = start.plusMinutes(45)
            )
        }
        else -> emptyList()
    }
}
