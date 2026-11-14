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
        CampusStudyPeriodSlot(4, "9-10节", 9, 10, LocalTime.of(20, 45)),
        CampusStudyPeriodSlot(5, "11-12节", 11, 12, LocalTime.of(23, 59)),
    )

    /**
     * 自动定位所选槽位的命中性质：课间与课前绝不冒充"当前占用"。
     */
    enum class StudySlotTiming {
        /** 当前时刻落在所选槽位包含的实际节次区间内 */
        IN_SESSION,

        /** 当前时刻处于课间（或早于当天首个槽位），所选为即将开始的下一槽 */
        UPCOMING_NEXT,

        /** 当前时刻晚于最后一个已定义槽位的结束时间，仅保留最后槽位作参考 */
        AFTER_LAST
    }

    /**
     * 按激活学期作息表（Term.timeTableId 对应的 PeriodTime）解析当前应选中的自习槽位结果。
     */
    data class StudySlotResolution(
        val slot: CampusStudyPeriodSlot,
        val timing: StudySlotTiming,
        /** 激活作息表缺失/无匹配节次时，是否回退到了内置默认作息（不混入其他学校/学期表） */
        val usedFallbackTimetable: Boolean
    ) {
        val slotIndex: Int get() = slot.index

        /** 课间预选中的是"下一时段"，标签必须明示而非冒充当前占用 */
        val displayLabel: String
            get() = when (timing) {
                StudySlotTiming.IN_SESSION -> slot.label
                StudySlotTiming.UPCOMING_NEXT -> "${slot.label}·下一时段"
                StudySlotTiming.AFTER_LAST -> "${slot.label}·已结束"
            }
    }

    /**
     * 激活学期作息表缺失时的显式回退作息（与 haut_lianhuajie_default 内置默认作息一致）；
     * 绝不从其他学校、其他学期或相邻 timeTableId 的节次表中"就近借用"。
     */
    val FALLBACK_STUDY_PERIOD_TIMES: List<PeriodTime> = listOf(
        PeriodTime("fallback_pt_1", "haut_lianhuajie_default", 1, LocalTime.of(8, 30), LocalTime.of(9, 15), "上午"),
        PeriodTime("fallback_pt_2", "haut_lianhuajie_default", 2, LocalTime.of(9, 20), LocalTime.of(10, 5), "上午"),
        PeriodTime("fallback_pt_3", "haut_lianhuajie_default", 3, LocalTime.of(10, 25), LocalTime.of(11, 10), "上午"),
        PeriodTime("fallback_pt_4", "haut_lianhuajie_default", 4, LocalTime.of(11, 15), LocalTime.of(12, 0), "上午"),
        PeriodTime("fallback_pt_5", "haut_lianhuajie_default", 5, LocalTime.of(14, 30), LocalTime.of(15, 15), "下午"),
        PeriodTime("fallback_pt_6", "haut_lianhuajie_default", 6, LocalTime.of(15, 20), LocalTime.of(16, 5), "下午"),
        PeriodTime("fallback_pt_7", "haut_lianhuajie_default", 7, LocalTime.of(16, 25), LocalTime.of(17, 10), "下午"),
        PeriodTime("fallback_pt_8", "haut_lianhuajie_default", 8, LocalTime.of(17, 15), LocalTime.of(18, 0), "下午"),
        PeriodTime("fallback_pt_9", "haut_lianhuajie_default", 9, LocalTime.of(19, 30), LocalTime.of(20, 15), "晚间"),
        PeriodTime("fallback_pt_10", "haut_lianhuajie_default", 10, LocalTime.of(20, 25), LocalTime.of(21, 10), "晚间"),
        PeriodTime("fallback_pt_11", "haut_lianhuajie_default", 11, LocalTime.of(21, 15), LocalTime.of(22, 0), "晚间"),
        PeriodTime("fallback_pt_12", "haut_lianhuajie_default", 12, LocalTime.of(22, 5), LocalTime.of(22, 50), "晚间"),
    )

    /**
     * 依据激活学期 Term.timeTableId 对应的 PeriodTime 自动定位自习槽位：
     * - 命中任一槽位覆盖的节次区间（含区间内的短课间，如第9/10节之间）→ 选该槽位并标记 IN_SESSION；
     * - 处于相邻槽位之间的课间（或早于首个槽位）→ 选即将开始的下一槽并标记 UPCOMING_NEXT；
     * - 晚于最后槽位结束 → 保留最后槽位并标记 AFTER_LAST；
     * - [timeTableId] 缺失或该表无任何节次 → 明确回退 [FALLBACK_STUDY_PERIOD_TIMES]，不混入其他 timeTableId；
     * - 槽位内某节次未定义时按已定义节次的并集确定区间；某槽位完全无节次定义时不可被选中。
     */
    fun resolveActiveSlotForTimeTable(
        nowTime: LocalTime,
        periodTimes: List<PeriodTime>,
        timeTableId: String?
    ): StudySlotResolution {
        val activePeriods = if (timeTableId.isNullOrBlank()) {
            emptyList()
        } else {
            periodTimes.filter { it.timeTableId == timeTableId }
        }
        val usedFallback = activePeriods.isEmpty()
        val effectivePeriods = if (usedFallback) FALLBACK_STUDY_PERIOD_TIMES else activePeriods
        val windows = STUDY_PERIOD_SLOTS.mapNotNull { slot ->
            val times = effectivePeriods.filter { it.periodNo in slot.startPeriod..slot.endPeriod }
            if (times.isEmpty()) {
                null
            } else {
                Triple(
                    slot,
                    times.minOf { it.startLocalTime },
                    times.maxOf { it.endLocalTime }
                )
            }
        }
        if (windows.isEmpty()) {
            return StudySlotResolution(
                slot = STUDY_PERIOD_SLOTS.last(),
                timing = StudySlotTiming.AFTER_LAST,
                usedFallbackTimetable = true
            )
        }
        for ((slot, start, end) in windows) {
            if (!nowTime.isBefore(start) && nowTime.isBefore(end)) {
                return StudySlotResolution(slot, StudySlotTiming.IN_SESSION, usedFallback)
            }
            if (nowTime.isBefore(start)) {
                return StudySlotResolution(slot, StudySlotTiming.UPCOMING_NEXT, usedFallback)
            }
        }
        return StudySlotResolution(
            slot = windows.last().first,
            timing = StudySlotTiming.AFTER_LAST,
            usedFallbackTimetable = usedFallback
        )
    }

    @Deprecated(
        message = "固定 cutoff 与激活作息表不一致（如默认表第10节 21:10 结束而非 20:45）；请改用 resolveActiveSlotForTimeTable 传入 Term.timeTableId 对应的 PeriodTime",
        level = DeprecationLevel.WARNING
    )
    fun resolveActiveSlotIndex(nowTime: LocalTime): Int {
        val matched = STUDY_PERIOD_SLOTS.firstOrNull { nowTime.isBefore(it.cutoffTime) }
        return matched?.index ?: STUDY_PERIOD_SLOTS.lastIndex
    }

    @Deprecated(
        message = "仅按小时粗粒度估算且与 LocalTime 重载不一致，仅保留作兼容；请改用 resolveActiveSlotForTimeTable",
        level = DeprecationLevel.WARNING
    )
    fun resolveActiveSlotIndex(nowHour: Int): Int = when {
        nowHour < 10 -> 0
        nowHour < 12 -> 1
        nowHour < 16 -> 2
        nowHour < 18 -> 3
        nowHour < 21 -> 4
        else -> 5
    }

    @Deprecated(
        message = "固定 cutoff 与激活作息表不一致；请改用 resolveActiveSlotForTimeTable",
        level = DeprecationLevel.WARNING
    )
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
