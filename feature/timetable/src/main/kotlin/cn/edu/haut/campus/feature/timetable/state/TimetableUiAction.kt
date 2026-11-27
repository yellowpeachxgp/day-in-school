package cn.edu.haut.campus.feature.timetable.state

import cn.edu.haut.campus.core.database.UnscheduledAcademicCourseEntity
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.OccurrenceOverride
import java.time.LocalDate

/** 课表页用户意图。UI 只发送动作，不直接触碰 Repository、平台单例或导航回调。 */
sealed interface TimetableUiAction {
    data object OpenImporter : TimetableUiAction
    data class OpenMapRoom(val room: String) : TimetableUiAction

    /** 点击瞬间冻结的恢复输入；ViewModel 负责构造结构化命令，UI 不引用 Repository。 */
    data class RestoreCanceledGroup(
        val termId: String?,
        val groupRows: List<OccurrenceOverride>,
        val occurrenceOverrides: List<OccurrenceOverride>,
        val writeToken: String? = null
    ) : TimetableUiAction
    data class DeleteLegacyOccurrenceOverride(
        val overrideId: String,
        val writeToken: String? = null
    ) : TimetableUiAction
    data object ClearLegacyReviewTargets : TimetableUiAction
    data class ConsumeWriteCompletion(val token: String) : TimetableUiAction

    data class SelectCurrentTerm(
        val termId: String,
        val writeToken: String? = null
    ) : TimetableUiAction
    data class UpdateTermStartDate(
        val termId: String,
        val startDate: LocalDate,
        val writeToken: String? = null
    ) : TimetableUiAction

    data class ResetSingleDayOccurrenceEdit(
        val occurrence: ClassOccurrence,
        val writeToken: String? = null
    ) : TimetableUiAction
    data class CancelClassOccurrence(
        val occurrence: ClassOccurrence,
        val termId: String,
        val writeToken: String? = null
    ) : TimetableUiAction
    data class DeleteCourseScheduleRules(
        val ruleIds: List<String>,
        val contextDate: LocalDate,
        val writeToken: String? = null
    ) : TimetableUiAction
    data class SaveSingleDayOccurrenceEdit(
        val occurrence: ClassOccurrence,
        val termId: String,
        val targetDate: LocalDate,
        val newStartPeriod: Int,
        val newEndPeriod: Int,
        val newRoom: String,
        val writeToken: String? = null
    ) : TimetableUiAction
    data class SaveMergedCourseRuleEdit(
        val course: Course,
        val primaryRule: CourseScheduleRule,
        val mergedRuleIds: List<String>,
        val contextDate: LocalDate,
        val name: String,
        val teacher: String,
        val room: String,
        val weekday: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val weeks: Set<Int>,
        val colorArgb: Long,
        val writeToken: String? = null
    ) : TimetableUiAction

    data class SaveMasterCourseRuleEdit(
        val course: Course,
        val rule: CourseScheduleRule,
        val name: String,
        val teacher: String,
        val room: String,
        val weekday: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val weeks: Set<Int>,
        val colorArgb: Long,
        val writeToken: String? = null
    ) : TimetableUiAction
    data class DeleteCourseScheduleRule(
        val ruleId: String,
        val writeToken: String? = null
    ) : TimetableUiAction

    data class SchedulePracticalCourse(
        val unscheduled: UnscheduledAcademicCourseEntity,
        val rawRoom: String,
        val weekday: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val weeks: Set<Int>,
        val writeToken: String? = null
    ) : TimetableUiAction
    data class AddManualCourse(
        val termId: String,
        val name: String,
        val teacher: String?,
        val rawRoom: String,
        val weekday: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val weeks: Set<Int>,
        val colorArgb: Long,
        val writeToken: String? = null
    ) : TimetableUiAction
}
