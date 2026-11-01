package cn.edu.haut.campus.core.selection

import cn.edu.haut.campus.core.domain.WeeksSetParser
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule

/** 占用时段的来源，决定冲突明细中的提示语与分组。 */
enum class OccupiedSource(val label: String) {
    TIMETABLE("已有课表"),
    DRAFT("选课草稿")
}

/** 一个已经占用的时段；把“课程身份”和“时段”组合起来，避免用布尔值丢失冲突细节。 */
data class OccupiedSlot(
    val source: OccupiedSource,
    val termId: String,
    val courseName: String,
    val slot: ClassSlot,
    val teachingClassId: String? = null
) {

    init {
        require(termId.isNotBlank()) { "占用时段必须绑定学期" }
        require(courseName.isNotBlank()) { "占用时段必须带课程名，否则无法向用户解释冲突来源" }
    }
}

/** 待评估的候选教学班：一个教学班可以有多个上课时段。 */
data class SelectionCandidate(
    val offeringId: String,
    val courseName: String,
    val teachingClassId: String,
    val termId: String,
    val slots: List<ClassSlot>
) {

    init {
        require(offeringId.isNotBlank() && courseName.isNotBlank() && teachingClassId.isNotBlank()) {
            "候选课程标识不完整"
        }
        require(termId.isNotBlank()) { "候选课程必须绑定学期" }
    }
}

/** 一条冲突明细：与哪门课、哪一天、哪几节、哪些周次重叠。 */
data class ConflictDetail(
    val source: OccupiedSource,
    val conflictingCourseName: String,
    val conflictingTeachingClassId: String?,
    val weekday: Int,
    val sectionStart: Int,
    val sectionEnd: Int,
    val sharedWeeks: Set<Int>,
    val weeksUncertain: Boolean,
    val message: String
) {

    val weekdayLabel: String get() = ClassSlot.weekdayLabelOf(weekday)
}

/** 冲突分析结果；调用方拿到的是明细列表，而不是一个布尔值。 */
data class ConflictReport(
    val candidate: SelectionCandidate,
    val details: List<ConflictDetail>
) {

    val hasConflict: Boolean get() = details.isNotEmpty()

    val timetableConflicts: List<ConflictDetail> get() = details.filter { it.source == OccupiedSource.TIMETABLE }

    val draftConflicts: List<ConflictDetail> get() = details.filter { it.source == OccupiedSource.DRAFT }

    /** 汇总文案；没有冲突时也要说明比较范围，避免让用户以为系统校验过全部约束。 */
    val summary: String
        get() {
            if (details.isEmpty()) return "与已有课表、选课草稿未发现时间冲突"
            val timetableCount = timetableConflicts.map { it.conflictingCourseName }.distinct().size
            val draftCount = draftConflicts.map { it.conflictingCourseName }.distinct().size
            val parts = buildList {
                if (timetableCount > 0) add("已有课表 $timetableCount 门")
                if (draftCount > 0) add("选课草稿 $draftCount 门")
            }
            return "存在时间冲突：" + parts.joinToString("、")
        }
}

/**
 * 选课冲突分析：候选教学班 × 已有课表 × 草稿。
 *
 * 只比较同一学期内的时段；周次未知时按可能冲突处理并在明细里标注，
 * 让调用方能把“确定冲突”和“数据不足”分开呈现。
 */
object SelectionConflictAnalyzer {

    fun analyze(candidate: SelectionCandidate, occupied: List<OccupiedSlot>): ConflictReport {
        val details = ArrayList<ConflictDetail>()
        for (other in occupied) {
            if (other.termId != candidate.termId) continue
            for (mine in candidate.slots) {
                if (!mine.sharesSectionWith(other.slot)) continue
                if (!mine.weeks.intersects(other.slot.weeks)) continue
                val uncertain = !mine.weeks.isKnown || !other.slot.weeks.isKnown
                val sectionStart = maxOf(mine.startSection, other.slot.startSection)
                val sectionEnd = minOf(mine.endSection, other.slot.endSection)
                val shared = mine.weeks.sharedWeeks(other.slot.weeks)
                details += ConflictDetail(
                    source = other.source,
                    conflictingCourseName = other.courseName,
                    conflictingTeachingClassId = other.teachingClassId,
                    weekday = other.slot.weekday,
                    sectionStart = sectionStart,
                    sectionEnd = sectionEnd,
                    sharedWeeks = shared,
                    weeksUncertain = uncertain,
                    message = describe(other, sectionStart, sectionEnd, shared, uncertain)
                )
            }
        }
        details.sortWith(
            compareBy(
                { it.source.ordinal },
                { it.weekday },
                { it.sectionStart },
                { it.conflictingCourseName }
            )
        )
        return ConflictReport(candidate, details)
    }

    private fun describe(
        other: OccupiedSlot,
        sectionStart: Int,
        sectionEnd: Int,
        sharedWeeks: Set<Int>,
        uncertain: Boolean
    ): String {
        val weeks = if (uncertain) {
            "周次未知，按可能冲突处理"
        } else {
            "共同周次 " + WeeksSetParser.format(sharedWeeks)
        }
        return "与" + other.source.label + "《" + other.courseName + "》" +
            ClassSlot.weekdayLabelOf(other.slot.weekday) +
            ClassSlot.sectionTextOf(sectionStart, sectionEnd) + "冲突（" + weeks + "）"
    }
}

/**
 * 已有课表 → 占用时段的桥接。
 *
 * UNVERIFIED：这里只复用本项目已有的 [CourseScheduleRule] 排课模型，与具体教务系统的周次写法无关；
 * 找不到课程元数据的规则会被跳过，避免在冲突提示里编造一个不存在的课程名。
 */
object TimetableOccupancy {

    fun fromScheduleRules(
        termId: String,
        courses: List<Course>,
        rules: List<CourseScheduleRule>
    ): List<OccupiedSlot> {
        val courseById = courses.associateBy { it.id }
        return rules.asSequence()
            .filter { it.termId == termId }
            .sortedWith(compareBy({ it.weekday.value }, { it.startPeriod }, { it.courseId }))
            .mapNotNull { rule ->
                val course = courseById[rule.courseId] ?: return@mapNotNull null
                OccupiedSlot(
                    source = OccupiedSource.TIMETABLE,
                    termId = termId,
                    courseName = course.name,
                    slot = ClassSlot(
                        weekday = rule.weekday.value,
                        startSection = rule.startPeriod,
                        endSection = rule.endPeriod,
                        weeks = TeachingWeeks.of(rule.weeks),
                        location = rule.rawRoom.ifBlank { null }
                    )
                )
            }
            .toList()
    }
}
