package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.Term
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/**
 * 课程/实践课 Timeline 投影所需的纯 JVM 输入快照。
 *
 * [zone] 必须由适配层按校区显式提供，严禁在核心模块读取设备默认时区。[revision]
 * 是输入快照的单调版本：同一份领域数据重复同步时必须保持不变，内容变化后应增加；
 * TimelineEvent.revision 直接沿用该值，跨同步稳定 ID 与版本替换语义因此可测试。
 */
data class CourseScheduleTimelineSnapshot(
    val zone: ZoneId,
    val provenance: DataProvenance,
    val revision: Long,
    val terms: List<Term> = emptyList(),
    val courses: List<Course> = emptyList(),
    val rules: List<CourseScheduleRule> = emptyList(),
    val periodTimes: List<PeriodTime> = emptyList(),
    val calendarOverrides: List<CalendarOverride> = emptyList(),
    val occurrenceOverrides: List<OccurrenceOverride> = emptyList()
) {
    init {
        require(revision >= 0) { "课程时间流输入 revision 不能为负" }
        requireUnique(terms.map { it.id }, "学期")
        requireUnique(courses.map { it.id }, "课程")
        requireUnique(rules.map { it.id }, "课程排课规则")
        requireUnique(calendarOverrides.map { it.id }, "学期日历覆盖")
        requireUnique(occurrenceOverrides.map { it.id }, "单日课程覆盖")
    }
}

/** 按 scope/window 观察课程输入。实现可以是 Room 适配器，也可以是纯 JVM 测试夹具。 */
fun interface CourseScheduleTimelineInputProvider {
    fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<CourseScheduleTimelineSnapshot>
}

/** 考试 Timeline 投影输入；考试时区同样显式注入，不读取设备默认时区。 */
data class ExamTimelineSnapshot(
    val zone: ZoneId,
    val provenance: DataProvenance,
    val revision: Long,
    val exams: List<ExamScheduleItem> = emptyList()
) {
    init {
        require(revision >= 0) { "考试时间流输入 revision 不能为负" }
        requireUnique(exams.map { it.id }, "考试")
    }
}

fun interface ExamTimelineInputProvider {
    fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<ExamTimelineSnapshot>
}

private fun requireUnique(values: List<String>, label: String) {
    require(values.distinct().size == values.size) { label + "ID 重复，Timeline 拒绝歧义投影" }
}
