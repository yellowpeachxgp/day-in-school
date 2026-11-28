package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.TimeEvidence
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
 * 自习教室名录「空闲」语义等级：诚实区分个人课表反推参考与校方实时占用数据。
 */
enum class StudyRoomAvailabilityMeaning(val label: String) {
    /** 「无冲突」仅代表用户本地个人课表未占用，绝非校方官方实时空闲数据。 */
    PERSONAL_REFERENCE_NOT_REALTIME("个人课表反推参考·非实时占用"),

    /** 预留：未来接入校方官方实时教室占用/开放数据后的语义标记。 */
    OFFICIAL_REALTIME_FEED("校方实时占用数据")
}

/**
 * 单个校区自习教室名录的来源与核验状态（全部为可空字段，名录本身未经校方官方核验）。
 */
data class CampusStudyRoomCatalogProvenance(
    val campusKey: String,
    /** 名录条目来源说明（整理依据），未核验时为诚实说明而非「已核验」标记。 */
    val sourceReference: String? = null,
    /** 经官方来源逐条核验的日期；名录未经官方核验时保持 null，绝不伪造核验证据。 */
    val verifiedAt: LocalDate? = null,
    val availabilityMeaning: StudyRoomAvailabilityMeaning =
        StudyRoomAvailabilityMeaning.PERSONAL_REFERENCE_NOT_REALTIME,
    val note: String? = null
)

/**
 * 莲花街校区公共自习教室名录与空闲状态判定引擎，统一 `TodayScreen`、`EmptyClassroomScreen`
 * 与 `SettingsScreen`，消除多处硬编码教室表与时段判断漂移。
 */
object CampusStudyRoomCatalog {

    /**
     * 莲花街校区自习教室候选名录来源标注：整理自历史教务课表教室文本与莲花街校区楼宇名录
     * 交叉比对，并非校方发布的官方自习室名录；未经官方逐条核验，"空闲"仅为个人课表无冲突参考。
     */
    val LIANHUAJIE_CATALOG_PROVENANCE = CampusStudyRoomCatalogProvenance(
        campusKey = "lianhuajie",
        sourceReference = "教务历史课表教室文本 × 莲花街校区楼宇名录交叉整理（非官方自习室名录）",
        verifiedAt = null,
        availabilityMeaning = StudyRoomAvailabilityMeaning.PERSONAL_REFERENCE_NOT_REALTIME,
        note = "候选教室仅表示个人课表在该时段无排课冲突，不代表校方实时开放或占用状态"
    )

    /**
     * 嵩山路校区自习教室候选名录来源标注：整理自嵩山路校区教务教室文本（"嵩山X教/综合楼"前缀）
     * 与 HAUT_SONGSHANLU_BUILDINGS 楼宇名录交叉比对，同样未经校方官方核验。
     */
    val SONGSHANLU_CATALOG_PROVENANCE = CampusStudyRoomCatalogProvenance(
        campusKey = "songshanlu",
        sourceReference = "嵩山路校区教务教室文本 × HAUT_SONGSHANLU_BUILDINGS 楼宇名录交叉整理（非官方自习室名录）",
        verifiedAt = null,
        availabilityMeaning = StudyRoomAvailabilityMeaning.PERSONAL_REFERENCE_NOT_REALTIME,
        note = "候选教室仅表示个人课表在该时段无排课冲突，不代表校方实时开放或占用状态"
    )

    fun catalogProvenanceForCampus(campusId: String?): CampusStudyRoomCatalogProvenance =
        if (isSongshanCampus(campusId)) SONGSHANLU_CATALOG_PROVENANCE else LIANHUAJIE_CATALOG_PROVENANCE

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
            // 该入口没有日期/学期上下文，只用默认参考作息估计自习槽位；
            // 方案行由 ScheduleEngine 在真实日期展开时选择，避免多套方案在同一列表中混排。
            periodTimes.filter { it.timeTableId == timeTableId && it.schemeId == null }
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
        val totalCandidateRoomsCount: Int,
        /**
         * 空闲统计是否有真实课表输入作依据：无排课/无时间表输入时为 false，
         * 此时 freeRoomsCount 仅表示「未观测到冲突」，不得解读为"全部空闲"的官方结论。
         */
        val hasScheduleBasis: Boolean = true
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
            totalCandidateRoomsCount = candidates.size,
            hasScheduleBasis = occurrences.isNotEmpty()
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
            totalCandidateRoomsCount = candidates.size,
            hasScheduleBasis = rules.isNotEmpty() || occurrenceOverrides.isNotEmpty()
        )
    }

    /**
     * 「个人无冲突」统计依据判定结果：诚实区分完整依据 / 依据不完整 / 尚无依据——
     * 部分或非法输入绝不退化为全绿计数，也绝不与"完全无输入"混为一谈。
     */
    sealed class ScheduleBasisEvaluation {
        abstract val hasBasis: Boolean

        /** 供 UI 展示的诚实原因文案；[Valid] 时为 null。 */
        abstract val reasonLabel: String?

        /** 至少一条本学期输入可展开为真实课程事件且无任何未解析行 →「个人无冲突」计数可信。 */
        data object Valid : ScheduleBasisEvaluation() {
            override val hasBasis: Boolean get() = true
            override val reasonLabel: String? get() = null
        }

        /**
         * 存在可能影响冲突判断的缺课程/缺时间/非法时间/关联问题（即使同时存在
         * 可用行也不允许短路冒充完整依据）→「依据不完整」原因态。
         * [reason] 为按严重度选出的主因文案，[reasonCodes] 为全部未解析行的原因集合。
         */
        data class Incomplete(
            val reason: Reason,
            val reasonCodes: Set<Reason> = setOf(reason)
        ) : ScheduleBasisEvaluation() {
            override val hasBasis: Boolean get() = false
            override val reasonLabel: String get() = reason.label
        }

        /** 无当前学期或完全无本学期排课输入（其他学期残留、仅剩中性取消行均不计）。 */
        data object Absent : ScheduleBasisEvaluation() {
            override val hasBasis: Boolean get() = false
            override val reasonLabel: String get() = "尚无课表依据"
        }

        /** 「依据不完整」的具体原因码（一条输入行可同时携带多个原因）。 */
        enum class Reason(val label: String) {
            /** 学期作息表无任何节次定义，且输入未自带完整合法自定义时间 */
            TIMETABLE_INCOMPLETE("学期作息表不完整"),

            /** 本学期输入存在但不可展开（零/越界周次、非法节次区间等） */
            INPUTS_UNEXPANDABLE("课表依据不完整"),

            /** 输入引用的课程不存在/不可解析 */
            MISSING_COURSE("课程信息缺失"),

            /** 时间端缺失或仅能推算补全（作息未覆盖且无完整合法自定义时间） */
            TIME_UNRESOLVED("课程时间未解析"),

            /** 显式提供的时间非法（end<=start 反向/零时长） */
            TIME_INVALID("课程时间非法"),

            /** 覆盖行关联失效：孤儿行、跨学期关联或指向缺失课程 */
            ASSOCIATION_BROKEN("课表关联失效")
        }
    }

    /**
     * 「个人无冲突」统计依据的聚合结果——单遍聚合产出，绝不以
     * "任一可用行"短路 Valid 而掩盖其余未解析行（混合输入必须如实暴露）。
     */
    data class ScheduleBasisAggregation(
        /** 三态判定：Valid=有可用输入且无未解析项；Incomplete=存在未解析项；Absent=无本学期相关输入 */
        val status: ScheduleBasisEvaluation,
        /** 可展开且时间证据可信（EXPLICIT_VALID/TIMETABLE_VALID）的本学期输入行数 */
        val usableCount: Int,
        /** 存在影响冲突判断问题（缺课程/缺时间/非法时间/关联问题）的本学期输入行数 */
        val unresolvedCount: Int,
        /** 全部未解析行的诚实原因码集合（Valid/Absent 时为空） */
        val reasonCodes: Set<ScheduleBasisEvaluation.Reason>
    )

    /**
     * 当前学期课表是否构成"个人无冲突"统计的有效依据——单遍聚合判定（不短路）：
     * 必须存在当前学期；逐条盘点本学期排课规则与单日覆盖行，
     * 与 [ScheduleEngine] 展开器共用同一校验与同一时间窗证据解析
     * （[ScheduleEngine.isExpandableScheduleRule] /
     * [ScheduleEngine.sameTermBaseRule] / [ScheduleEngine.expandableOverrideTimeWindow] /
     * [ScheduleEngine.ruleSchedulePeriodWindow] / [TimeEvidence]）：
     * - usable：可展开且时间证据可信（EXPLICIT_VALID/TIMETABLE_VALID）——
     *   缺作息表但自带完整合法 customStart/customEnd 同样可信（EXPLICIT_VALID），
     *   推算补全（ESTIMATED）/无法解析（UNRESOLVED）/非法反向（INVALID）一律不采信；
     * - unresolved：存在影响冲突判断问题的行——缺课程（MISSING_COURSE）、
     *   缺时间/推算时间（TIME_UNRESOLVED/TIMETABLE_INCOMPLETE）、非法时间（TIME_INVALID）、
     *   关联问题（ASSOCIATION_BROKEN：孤儿行/跨学期关联/指向缺失课程）、
     *   零或越界周次与非法节次区间（INPUTS_UNEXPANDABLE）；
     * - neutral：正常停课覆盖（指向本学期现存规则或自带可解析课程）只屏蔽事件不算损坏；
     *   被有效非停课覆盖引用的"纯模板规则"（自身周次为空或与学期有效周零相交、
     *   绝不产出常规事件的借字段载体）不因模板自身缺陷判坏；weeks 与学期有效周
     *   相交的常规规则一经引用绝不豁免其自身问题。
     * 判定：usableCount>0 且 unresolvedCount==0 → Valid；
     * unresolvedCount>0 → Incomplete（即使同时存在可用行也如实报不完整）；
     * 无任何本学期相关输入（或仅剩中性行）→ Absent。
     * 结果与输入行顺序无关——"有效/无效输入顺序交换"绝不改变判定。
     * "完整个人课表但今日无课"仍为 Valid：依据判定看学期输入而非当日展开结果。
     */
    fun aggregateCurrentTermScheduleBasis(
        term: Term?,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        occurrenceOverrides: List<OccurrenceOverride> = emptyList()
    ): ScheduleBasisAggregation {
        if (term == null) {
            return ScheduleBasisAggregation(ScheduleBasisEvaluation.Absent, 0, 0, emptySet())
        }
        val courseIds = courses.mapTo(HashSet()) { it.id }
        val rulesById = rules.associateBy { it.id }
        val periodMap = ScheduleEngine.periodMapAcrossSchemes(term, periodTimes)

        val termRules = rules.filter { it.termId == term.id }
        val termOverrides = occurrenceOverrides.filter { it.termId == term.id }
        if (termRules.isEmpty() && termOverrides.isEmpty()) {
            // 其他学期残留的 rules/覆盖不属于本学期输入：诚实按"无输入"处理
            return ScheduleBasisAggregation(ScheduleBasisEvaluation.Absent, 0, 0, emptySet())
        }

        val usableItemKeys = linkedSetOf<String>()
        val unresolvedCodesByItem = linkedMapOf<String, Set<ScheduleBasisEvaluation.Reason>>()

        fun timeWindowProblemCodes(evidence: TimeEvidence): Set<ScheduleBasisEvaluation.Reason> = when {
            evidence.isBasisTrustworthy -> emptySet()
            evidence == TimeEvidence.INVALID ->
                setOf(ScheduleBasisEvaluation.Reason.TIME_INVALID)
            periodMap.isEmpty() ->
                setOf(ScheduleBasisEvaluation.Reason.TIMETABLE_INCOMPLETE)
            else -> // ESTIMATED 推算锚点 / UNRESOLVED 无法解析：时间依据不足
                setOf(ScheduleBasisEvaluation.Reason.TIME_UNRESOLVED)
        }

        for (rule in termRules) {
            // 与展开器共用同一「可展开」校验为唯一口径（绝不另起判定逻辑），
            // 不可展开时再按同一判定的组成项细分原因码。
            val expandable = ScheduleEngine.isExpandableScheduleRule(
                rule, term.id, courseIds, term.weekCount
            )
            val problems = mutableSetOf<ScheduleBasisEvaluation.Reason>()
            if (!expandable) {
                if (rule.courseId !in courseIds) {
                    problems += ScheduleBasisEvaluation.Reason.MISSING_COURSE
                }
                val weeksUsable = rule.weeks.any { it in 1..term.weekCount }
                val periodsOrdered = rule.startPeriod >= 1 && rule.startPeriod <= rule.endPeriod
                if (!weeksUsable || !periodsOrdered) {
                    problems += ScheduleBasisEvaluation.Reason.INPUTS_UNEXPANDABLE
                }
            } else {
                // 可展开规则再查自身时间窗证据等级（与展开器同一解析路径）
                problems += timeWindowProblemCodes(
                    ScheduleEngine.ruleSchedulePeriodWindow(rule, periodMap).evidence
                )
            }
            if (problems.isEmpty()) {
                usableItemKeys += "rule:${rule.id}"
            } else {
                unresolvedCodesByItem["rule:${rule.id}"] = problems
            }
        }

        val consumedTemplateRuleIds = mutableSetOf<String>()
        for (ov in termOverrides) {
            val itemKey = "override:${ov.id}"
            val baseRule = ScheduleEngine.sameTermBaseRule(ov, term.id, rulesById)
            if (ov.canceled) {
                // 正常停课覆盖不算损坏：指向本学期现存规则、或自带可解析课程的
                // 独立停课行只屏蔽事件、不产生事件；孤立/跨学期关联的取消 = 关联问题，
                // 绝不等于完整依据。
                val brokenAssociation = (ov.courseRuleId != null && baseRule == null) ||
                    (ov.courseRuleId == null &&
                        (ov.courseId == null || ov.courseId !in courseIds))
                if (brokenAssociation) {
                    unresolvedCodesByItem[itemKey] =
                        setOf(ScheduleBasisEvaluation.Reason.ASSOCIATION_BROKEN)
                }
                continue
            }
            // 非停课覆盖：与展开器共用同一「可展开」校验为唯一口径；
            // 缺课程/关联问题与覆盖行实际时间窗一并盘点——rule 合法但
            // override 非法必须按 override 实际字段判定，不得借规则遮丑。
            val problems = mutableSetOf<ScheduleBasisEvaluation.Reason>()
            val expandable = ScheduleEngine.isExpandableOccurrenceOverride(
                ov, term.id, rulesById, courseIds
            )
            if (ov.courseRuleId != null && baseRule == null) {
                problems += ScheduleBasisEvaluation.Reason.ASSOCIATION_BROKEN
            }
            val targetCourseId = ov.courseId ?: baseRule?.courseId
            if (targetCourseId == null || targetCourseId !in courseIds) {
                problems += ScheduleBasisEvaluation.Reason.MISSING_COURSE
            }
            if (expandable) {
                val window = ScheduleEngine.expandableOverrideTimeWindow(
                    ov, term.id, rulesById, courseIds, periodMap
                )
                if (window == null) {
                    problems += ScheduleBasisEvaluation.Reason.INPUTS_UNEXPANDABLE
                } else {
                    problems += timeWindowProblemCodes(window.evidence)
                }
            }
            if (problems.isEmpty()) {
                usableItemKeys += itemKey
                // 仅"纯模板规则"才被有效非停课覆盖消费豁免：自身周次为空或与学期
                // 有效周(1..term.weekCount)零相交、绝不产出常规事件的借字段载体——
                // weeks 与学期相交的常规规则自身问题必须保留为 unresolved，
                // 一条合法单日覆盖绝不抹掉该规则在其他日期的非法/残缺输入。
                if (baseRule != null &&
                    baseRule.weeks.none { it in 1..term.weekCount }) {
                    consumedTemplateRuleIds += baseRule.id
                }
            } else {
                unresolvedCodesByItem[itemKey] = problems
            }
        }

        // 被有效非停课覆盖消费的纯模板规则：自身不产出任何常规事件，
        // 其模板缺陷不会独立展开为冲突事件，不计损坏
        for (ruleId in consumedTemplateRuleIds) {
            unresolvedCodesByItem.remove("rule:$ruleId")
        }

        val usableCount = usableItemKeys.size
        val unresolvedCount = unresolvedCodesByItem.size
        val reasonCodes = linkedSetOf<ScheduleBasisEvaluation.Reason>().apply {
            unresolvedCodesByItem.values.forEach { addAll(it) }
        }
        val status = when {
            unresolvedCount > 0 -> ScheduleBasisEvaluation.Incomplete(
                reason = primaryBasisReason(reasonCodes),
                reasonCodes = reasonCodes
            )
            usableCount > 0 -> ScheduleBasisEvaluation.Valid
            else -> ScheduleBasisEvaluation.Absent // 仅剩中性行 → 无任何可用排课依据
        }
        return ScheduleBasisAggregation(status, usableCount, unresolvedCount, reasonCodes)
    }

    /** 主因优先级：按对用户决策影响排序，保证混合原因时文案确定性。 */
    private val BASIS_REASON_PRIORITY: List<ScheduleBasisEvaluation.Reason> = listOf(
        ScheduleBasisEvaluation.Reason.TIME_INVALID,
        ScheduleBasisEvaluation.Reason.ASSOCIATION_BROKEN,
        ScheduleBasisEvaluation.Reason.MISSING_COURSE,
        ScheduleBasisEvaluation.Reason.TIMETABLE_INCOMPLETE,
        ScheduleBasisEvaluation.Reason.TIME_UNRESOLVED,
        ScheduleBasisEvaluation.Reason.INPUTS_UNEXPANDABLE
    )

    private fun primaryBasisReason(
        codes: Set<ScheduleBasisEvaluation.Reason>
    ): ScheduleBasisEvaluation.Reason =
        BASIS_REASON_PRIORITY.firstOrNull { it in codes }
            ?: ScheduleBasisEvaluation.Reason.INPUTS_UNEXPANDABLE

    /**
     * 当前学期课表是否构成"个人无冲突"统计的有效依据（含原因态）——
     * 由 [aggregateCurrentTermScheduleBasis] 单遍聚合产出；Today 自习室卡片与
     * 校园 Hub 自习教室页消费同一判定结果，绝不两套口径。
     */
    fun evaluateCurrentTermScheduleBasis(
        term: Term?,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        occurrenceOverrides: List<OccurrenceOverride> = emptyList()
    ): ScheduleBasisEvaluation = aggregateCurrentTermScheduleBasis(
        term, courses, rules, periodTimes, occurrenceOverrides
    ).status

    /**
     * 当前学期课表是否构成"个人无冲突"统计的有效依据（布尔便捷入口）：
     * 原因态请改用 [evaluateCurrentTermScheduleBasis]——「依据不完整」与「尚无依据」
     * 在 UI 上是两种不同诚实文案，不可混同。
     */
    fun hasCurrentTermScheduleBasis(
        term: Term?,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        occurrenceOverrides: List<OccurrenceOverride> = emptyList()
    ): Boolean = evaluateCurrentTermScheduleBasis(
        term, courses, rules, periodTimes, occurrenceOverrides
    ).hasBasis

    /**
     * 自习槽位状态行文案：手动点选时明示所选槽位（并配合"恢复自动定位"入口），
     * 自动模式按命中性质区分"当前时段/预选下一时段/今日已结束"。
     */
    fun studySlotStatusLabel(
        manuallyPickedSlotLabel: String?,
        resolution: StudySlotResolution
    ): String {
        if (manuallyPickedSlotLabel != null) {
            return "已手动选择：$manuallyPickedSlotLabel"
        }
        return when (resolution.timing) {
            StudySlotTiming.IN_SESSION -> "自动定位当前时段：${resolution.slot.label}"
            StudySlotTiming.UPCOMING_NEXT -> "课间休息中 · 已预选下一时段：${resolution.slot.label}"
            StudySlotTiming.AFTER_LAST -> "今日课程已结束 · 参考时段：${resolution.slot.label}"
        }
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
