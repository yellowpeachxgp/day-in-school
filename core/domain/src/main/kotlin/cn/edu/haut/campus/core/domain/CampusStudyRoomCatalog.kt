package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.Term
import java.time.DayOfWeek
import java.time.LocalDate
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
        "3号教学楼" to listOf("3101", "3103", "3201", "3203", "3301", "3401"),
        "4号教学楼" to listOf("4101", "4103", "4201", "4203", "4301", "4401"),
        "5号教学楼" to listOf("5101", "5103", "5105", "5201", "5202", "5204", "5301", "5303", "5402"),
        "6号教学楼" to listOf("6101", "6102", "6201", "6203", "6205", "6301", "6304", "6401"),
        "7号教学楼" to listOf("7102", "7104", "7201", "7203", "7302", "7305", "7401"),
        "8号教学楼" to listOf("8101", "8103", "8201", "8202", "8301", "8303", "8402"),
        "9号教学楼" to listOf("9101", "9102", "9201", "9204", "9301", "9303"),
        "31号楼" to listOf("31101", "31103", "31201", "31203", "31301"),
        "32号楼" to listOf("32101", "32103", "32201", "32203", "32301"),
    )

    val SONGSHANLU_STUDY_ROOMS_MAP: Map<String, List<String>> = linkedMapOf(
        "嵩山路1号教学楼" to listOf("嵩山1教101", "嵩山1教103", "嵩山1教201", "嵩山1教203", "嵩山1教301", "嵩山1教303", "嵩山1教401"),
        "嵩山路2号教学楼" to listOf("嵩山2教101", "嵩山2教102", "嵩山2教201", "嵩山2教202", "嵩山2教301", "嵩山2教302"),
        "嵩山路实验与综合楼" to listOf("嵩山综合楼201", "嵩山综合楼202", "嵩山综合楼301", "嵩山综合楼302"),
    )

    val ALL_LIANHUAJIE_CANDIDATE_ROOMS: List<String> =
        LIANHUAJIE_STUDY_ROOMS_MAP.values.flatten()

    val ALL_SONGSHANLU_CANDIDATE_ROOMS: List<String> =
        SONGSHANLU_STUDY_ROOMS_MAP.values.flatten()

    fun isSongshanCampus(campusId: String?): Boolean =
        campusId != null && (
            campusId.contains("songshan", ignoreCase = true) ||
                campusId.contains("嵩山") ||
                campusId.contains("齐礼阎")
            )

    fun studyRoomsMapForCampus(campusId: String?): Map<String, List<String>> =
        if (isSongshanCampus(campusId)) {
            SONGSHANLU_STUDY_ROOMS_MAP
        } else {
            LIANHUAJIE_STUDY_ROOMS_MAP
        }

    fun candidateRoomsForCampus(campusId: String?): List<String> =
        if (isSongshanCampus(campusId)) {
            ALL_SONGSHANLU_CANDIDATE_ROOMS
        } else {
            ALL_LIANHUAJIE_CANDIDATE_ROOMS
        }

    fun campusShortLabel(campusId: String?): String =
        if (isSongshanCampus(campusId)) "嵩山路" else "莲花街"

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

    fun resolveActiveSlotIndex(nowHour: Int): Int = when {
        nowHour < 10 -> 0
        nowHour < 12 -> 1
        nowHour < 16 -> 2
        nowHour < 18 -> 3
        else -> 4
    }

    fun resolveActiveSlot(nowTime: LocalTime): CampusStudyPeriodSlot =
        STUDY_PERIOD_SLOTS[resolveActiveSlotIndex(nowTime)]

    fun findBusyRawRooms(
        rules: List<CourseScheduleRule>,
        activeTermId: String? = null,
        termId: String? = activeTermId,
        teachingWeek: Int,
        weekday: DayOfWeek,
        slot: CampusStudyPeriodSlot,
    ): List<String> {
        val effectiveTermId = activeTermId ?: termId
        return rules.filter { rule ->
            (effectiveTermId == null || rule.termId == effectiveTermId) &&
                rule.weekday == weekday &&
                (rule.weeks.isEmpty() || teachingWeek in rule.weeks) &&
                rule.startPeriod <= slot.endPeriod &&
                rule.endPeriod >= slot.startPeriod
        }.map { it.rawRoom }
    }

    private val ROOM_DIGITS_REGEX = Regex("(\\d{3,5})$")

    private fun normalizeSongshanRoomKey(raw: String): String? {
        val text = raw.replace(" ", "").trim()
        if (!text.contains("嵩") && !text.contains("实验与综合楼") && !text.contains("综合楼")) return null
        val roomDigits = ROOM_DIGITS_REGEX.find(text)?.groupValues?.getOrNull(1) ?: return null
        return when {
            text.contains("1教") || text.contains("1号教学楼") || text.contains("1号楼") -> "ss_t1_$roomDigits"
            text.contains("2教") || text.contains("2号教学楼") || text.contains("2号楼") -> "ss_t2_$roomDigits"
            text.contains("综合楼") || text.contains("实验") -> "ss_lab_$roomDigits"
            else -> null
        }
    }

    fun isRoomFree(roomCode: String, busyRawRooms: List<String>): Boolean {
        val targetSongshanKey = normalizeSongshanRoomKey(roomCode)
        return busyRawRooms.none { raw ->
            raw.contains(roomCode) ||
                (targetSongshanKey != null && normalizeSongshanRoomKey(raw) == targetSongshanKey)
        }
    }

    fun findBusyRawRoomsFromOccurrences(
        occurrences: List<ClassOccurrence>,
        slot: CampusStudyPeriodSlot
    ): List<String> = occurrences
        .filter { occ ->
            occ.startPeriod <= slot.endPeriod && occ.endPeriod >= slot.startPeriod
        }
        .map { it.rawRoom.trim() }
        .filter { it.isNotEmpty() }

    fun findBusyRawRoomsForDate(
        date: LocalDate,
        term: Term?,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        calendarOverrides: List<CalendarOverride> = emptyList(),
        occurrenceOverrides: List<OccurrenceOverride> = emptyList(),
        slot: CampusStudyPeriodSlot
    ): List<String> {
        if (term == null) {
            return findBusyRawRooms(
                rules = rules,
                activeTermId = null,
                teachingWeek = 1,
                weekday = date.dayOfWeek,
                slot = slot
            )
        }
        val occurrences = ScheduleEngine.resolveOccurrencesForDate(
            date = date,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides
        )
        return findBusyRawRoomsFromOccurrences(occurrences, slot)
    }

    data class CampusStudyAvailabilitySummary(
        val campusKey: String,
        val campusShortLabel: String,
        val freeRoomsCount: Int,
        val totalCandidateRoomsCount: Int
    )

    fun computeAvailabilityForOccurrences(
        occurrences: List<ClassOccurrence>,
        slot: CampusStudyPeriodSlot,
        campusId: String?
    ): CampusStudyAvailabilitySummary {
        val isSongshan = isSongshanCampus(campusId)
        val candidates = if (isSongshan) ALL_SONGSHANLU_CANDIDATE_ROOMS else ALL_LIANHUAJIE_CANDIDATE_ROOMS
        val busyRaw = findBusyRawRoomsFromOccurrences(occurrences, slot)
        val freeCount = candidates.count { code -> isRoomFree(code, busyRaw) }
        return CampusStudyAvailabilitySummary(
            campusKey = if (isSongshan) "songshanlu" else "lianhuajie",
            campusShortLabel = if (isSongshan) "嵩山路" else "莲花街",
            freeRoomsCount = freeCount,
            totalCandidateRoomsCount = candidates.size
        )
    }

    fun computeAvailabilityForDate(
        date: LocalDate,
        term: Term?,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        calendarOverrides: List<CalendarOverride> = emptyList(),
        occurrenceOverrides: List<OccurrenceOverride> = emptyList(),
        slot: CampusStudyPeriodSlot,
        campusId: String?
    ): CampusStudyAvailabilitySummary {
        val isSongshan = isSongshanCampus(campusId)
        val candidates = if (isSongshan) ALL_SONGSHANLU_CANDIDATE_ROOMS else ALL_LIANHUAJIE_CANDIDATE_ROOMS
        val busyRaw = findBusyRawRoomsForDate(
            date = date,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides,
            slot = slot
        )
        val freeCount = candidates.count { code -> isRoomFree(code, busyRaw) }
        return CampusStudyAvailabilitySummary(
            campusKey = if (isSongshan) "songshanlu" else "lianhuajie",
            campusShortLabel = if (isSongshan) "嵩山路" else "莲花街",
            freeRoomsCount = freeCount,
            totalCandidateRoomsCount = candidates.size
        )
    }

    fun computeFreeRoomsCount(
        rules: List<CourseScheduleRule>,
        activeTermId: String? = null,
        termId: String? = activeTermId,
        teachingWeek: Int,
        weekday: DayOfWeek,
        slot: CampusStudyPeriodSlot,
        candidateRooms: List<String> = ALL_LIANHUAJIE_CANDIDATE_ROOMS,
    ): Int {
        val effectiveTermId = activeTermId ?: termId
        val busyRawRooms = findBusyRawRooms(
            rules = rules,
            activeTermId = effectiveTermId,
            teachingWeek = teachingWeek,
            weekday = weekday,
            slot = slot,
        )
        return candidateRooms.count { code -> isRoomFree(code, busyRawRooms) }
    }
}
