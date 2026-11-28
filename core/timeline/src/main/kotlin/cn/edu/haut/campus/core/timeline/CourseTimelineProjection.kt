package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.SchoolAccountScope

/**
 * 课程/实践课 Timeline 投影的同步门面：一次调用同时覆盖 COURSE 与 PRACTICE 两个 kind，
 * 供课表适配层在消费端闭环读取（投影为事件 → 从事件还原领域模型）。
 *
 * 窗口过滤、排序与去重语义与 [CourseTimelineSource] / [PracticeTimelineSource] 完全一致——
 * 两个 Source 与本门面共用同一个展开内核，不存在第二套语义。
 * 时间语义纪律不变：UNCERTAIN 事件没有 end，还原侧必须按 timeEvidence 恢复锚点展示，
 * 绝不允许因为有了元数据就把不确定时间冒充精确。
 */
object CourseTimelineProjection {

    fun projectAll(
        snapshot: CourseScheduleTimelineSnapshot,
        scope: SchoolAccountScope,
        window: TimeWindow,
        placeResolver: TimelinePlaceResolver = TimelinePlaceResolver.NONE,
        practiceCourseClassifier: PracticeCourseClassifier = PracticeCourseClassifier.STABLE_PRACTICE_KEY
    ): List<TimelineEvent> {
        val courseEvents = CourseScheduleTimelineProjector.project(
            snapshot = snapshot,
            scope = scope,
            window = window,
            sourceId = CourseTimelineSource.SOURCE_ID,
            kind = TimelineKind.COURSE,
            includePracticeCourses = false,
            placeResolver = placeResolver,
            practiceCourseClassifier = practiceCourseClassifier
        )
        val practiceEvents = CourseScheduleTimelineProjector.project(
            snapshot = snapshot,
            scope = scope,
            window = window,
            sourceId = PracticeTimelineSource.SOURCE_ID,
            kind = TimelineKind.PRACTICE,
            includePracticeCourses = true,
            placeResolver = placeResolver,
            practiceCourseClassifier = practiceCourseClassifier
        )
        return (courseEvents + practiceEvents).sortedWith(
            compareBy<TimelineEvent> { it.start }
                .thenBy { it.end ?: it.start }
                .thenBy { it.id.value }
        )
    }
}
