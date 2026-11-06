package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.CourseScheduleRule
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 校园自习室与空闲教室检索的规范节次时间段契约。
 */
data class CampusStudyPeriodSlot(
    val index: Int,
    val label: String,
    val startPeriod: Int,
    val endPeriod: Int,
    val cutoffTime: LocalTime,
)

/**
 * 莲花街校区公共自习教室名录与空闲状态判定引擎，统一 `TodayScreen`、`EmptyClassroomScreen`
 * 与 `SettingsScreen`，消除多处硬编码教室表与时段判断漂移。
 */
object CampusStudyRoomCatalog {

    val LIANHUAJIE_STUDY_ROOMS_MAP: Map<String, List<String>> = linkedMapOf(
        "5号教学楼" to listOf("5101", "5103", "5105", "5201", "5202", "5204", "5301", "5303", "5402"),
        "6号教学楼" to listOf("6101", "6102", "6201", "6203", "6205", "6301", "6304", "6401"),
        "7号教学楼" to listOf("7102", "7104", "7201", "7203", "7302", "7305", "7401"),
        "8号教学楼" to listOf("8101", "8103", "8201", "8202", "8301", "8303", "8402"),
        "9号教学楼" to listOf("9101", "9102", "9201", "9204", "9301", "9303"),
        "11号教学楼" to listOf("11101", "11102", "11201", "11203", "11301", "11302"),
    )

    val ALL_LIANHUAJIE_CANDIDATE_ROOMS: List<String> =
        LIANHUAJIE_STUDY_ROOMS_MAP.values.flatten()

    val STUDY_PERIOD_SLOTS: List<CampusStudyPeriodSlot> = listOf(
        CampusStudyPeriodSlot(0, "1-2节", 1, 2, LocalTime.of(9, 50)),
        CampusStudyPeriodSlot(1, "3-4节", 3, 4, LocalTime.of(12, 0)),
        CampusStudyPeriodSlot(2, "5-6节", 5, 6, LocalTime.of(15, 50)),
        CampusStudyPeriodSlot(3, "7-8节", 7, 8, LocalTime.of(17, 50)),
        CampusStudyPeriodSlot(4, "9-10节", 9, 10, LocalTime.of(23, 59)),
    )

    fun resolveActiveSlotIndex(nowTime: LocalTime): Int {
        val matched = STUDY_PERIOD_SLOTS.firstOrNull { nowTime.isBefore(it.cutoffTime) }
        return matched?.index ?: STUDY_PERIOD_SLOTS.lastIndex
    }

    fun resolveActiveSlot(nowTime: LocalTime): CampusStudyPeriodSlot =
        STUDY_PERIOD_SLOTS[resolveActiveSlotIndex(nowTime)]

    fun findBusyRawRooms(
        rules: List<CourseScheduleRule>,
        activeTermId: String?,
        teachingWeek: Int,
        weekday: DayOfWeek,
        slot: CampusStudyPeriodSlot,
    ): List<String> {
        return rules.filter { rule ->
            (activeTermId == null || rule.termId == activeTermId) &&
                rule.weekday == weekday &&
                (rule.weeks.isEmpty() || teachingWeek in rule.weeks) &&
                rule.startPeriod <= slot.endPeriod &&
                rule.endPeriod >= slot.startPeriod
        }.map { it.rawRoom }
    }

    fun isRoomFree(roomCode: String, busyRawRooms: List<String>): Boolean =
        busyRawRooms.none { raw -> raw.contains(roomCode) }

    fun computeFreeRoomsCount(
        rules: List<CourseScheduleRule>,
        activeTermId: String?,
        teachingWeek: Int,
        weekday: DayOfWeek,
        slot: CampusStudyPeriodSlot,
        candidateRooms: List<String> = ALL_LIANHUAJIE_CANDIDATE_ROOMS,
    ): Int {
        val busyRawRooms = findBusyRawRooms(
            rules = rules,
            activeTermId = activeTermId,
            teachingWeek = teachingWeek,
            weekday = weekday,
            slot = slot,
        )
        return candidateRooms.count { code -> isRoomFree(code, busyRawRooms) }
    }
}
