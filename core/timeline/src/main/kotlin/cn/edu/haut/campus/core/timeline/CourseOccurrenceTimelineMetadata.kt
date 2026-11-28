package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.TimeEvidence
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import java.time.LocalDate
import java.time.LocalTime

/**
 * 课程/实践课 Timeline 投影的来源私有元数据 schema。
 *
 * 通用 Timeline 语义仍只使用 start/end/timeSemantics/status/busy；这些字段只用于
 * 课表适配层把投影还原为既有 ClassOccurrence 编辑/展示模型。UNCERTAIN 事件的
 * startTime/endTime 继续标明 TimeEvidence，绝不因为有了元数据而冒充精确时间。
 */
object CourseOccurrenceTimelineMetadata {
    const val OCCURRENCE_ID = "courseOccurrence.occurrenceId"
    const val COURSE_ID = "courseOccurrence.courseId"
    const val RULE_ID = "courseOccurrence.ruleId"
    const val OVERRIDE_ID = "courseOccurrence.overrideId"
    const val TEACHER = "courseOccurrence.teacher"
    const val COLOR_ARGB = "courseOccurrence.colorArgb"
    const val DATE = "courseOccurrence.date"
    const val TEACHING_WEEK = "courseOccurrence.teachingWeek"
    const val START_PERIOD = "courseOccurrence.startPeriod"
    const val END_PERIOD = "courseOccurrence.endPeriod"
    const val START_TIME = "courseOccurrence.startTime"
    const val END_TIME = "courseOccurrence.endTime"
    const val RAW_ROOM = "courseOccurrence.rawRoom"
    const val BUILDING_ID = "courseOccurrence.buildingId"
    const val MAKEUP_OR_OVERRIDDEN = "courseOccurrence.makeupOrOverridden"
    const val OVERRIDE_REASON = "courseOccurrence.overrideReason"
    const val USER_MODIFIED = "courseOccurrence.userModified"
    const val MERGED_RULE_IDS_JSON = "courseOccurrence.mergedRuleIdsJson"
    const val TIME_EVIDENCE = "courseOccurrence.timeEvidence"

    fun encode(occurrence: ClassOccurrence): Map<String, String> = buildMap {
        put(OCCURRENCE_ID, occurrence.occurrenceId)
        put(COURSE_ID, occurrence.courseId)
        occurrence.ruleId?.let { put(RULE_ID, it) }
        occurrence.overrideId?.let { put(OVERRIDE_ID, it) }
        occurrence.teacher?.let { put(TEACHER, it) }
        put(COLOR_ARGB, occurrence.colorArgb.toString())
        put(DATE, occurrence.date.toString())
        occurrence.teachingWeek?.let { put(TEACHING_WEEK, it.toString()) }
        put(START_PERIOD, occurrence.startPeriod.toString())
        put(END_PERIOD, occurrence.endPeriod.toString())
        put(START_TIME, occurrence.startTime.toString())
        put(END_TIME, occurrence.endTime.toString())
        put(RAW_ROOM, occurrence.rawRoom)
        occurrence.buildingId?.let { put(BUILDING_ID, it) }
        put(MAKEUP_OR_OVERRIDDEN, occurrence.isMakeupOrOverridden.toString())
        occurrence.overrideReason?.let { put(OVERRIDE_REASON, it) }
        put(USER_MODIFIED, occurrence.userModified.toString())
        put(
            MERGED_RULE_IDS_JSON,
            Json.canonical(JsonValue.Arr(occurrence.mergedRuleIds.map { JsonValue.Str(it) }))
        )
        put(TIME_EVIDENCE, occurrence.timeEvidence.name)
    }

    /** 还原 Timeline 投影时的原始领域字段；buildingName、internalBreaks 与冲突分栏仍由课表 engine 统一后处理。 */
    fun decode(event: TimelineEvent): ClassOccurrence {
        val metadata = event.sourceMetadata
        fun required(key: String): String = metadata[key]
            ?: throw IllegalArgumentException("课程 Timeline 事件缺少元数据 " + key + ": " + event.id.value)
        val mergedIds = Json.parse(required(MERGED_RULE_IDS_JSON)).asArrOrNull
            ?.items
            ?.mapNotNull { it.stringOrNull() }
            ?: throw IllegalArgumentException("课程 Timeline mergedRuleIds 元数据不是字符串数组: " + event.id.value)
        val evidence = TimeEvidence.valueOf(required(TIME_EVIDENCE))
        // hasResolvedTime 必须随证据等级还原：UNRESOLVED/INVALID 只保留排序锚点，
        // 缺省会意外回落为 true，把「时间待确认」冒充成精确时间。
        val hasResolvedTime = evidence.hasResolvedTime
        return ClassOccurrence(
            occurrenceId = required(OCCURRENCE_ID),
            courseId = required(COURSE_ID),
            ruleId = metadata[RULE_ID],
            overrideId = metadata[OVERRIDE_ID],
            courseName = event.title,
            teacher = metadata[TEACHER],
            colorArgb = required(COLOR_ARGB).toLong(),
            date = LocalDate.parse(required(DATE)),
            teachingWeek = metadata[TEACHING_WEEK]?.toInt(),
            weekday = LocalDate.parse(required(DATE)).dayOfWeek,
            startPeriod = required(START_PERIOD).toInt(),
            endPeriod = required(END_PERIOD).toInt(),
            startTime = LocalTime.parse(required(START_TIME)),
            endTime = LocalTime.parse(required(END_TIME)),
            rawRoom = required(RAW_ROOM),
            buildingId = metadata[BUILDING_ID],
            isMakeupOrOverridden = required(MAKEUP_OR_OVERRIDDEN).toBooleanStrict(),
            overrideReason = metadata[OVERRIDE_REASON],
            userModified = required(USER_MODIFIED).toBooleanStrict(),
            mergedRuleIds = mergedIds,
            hasResolvedTime = hasResolvedTime,
            timeEvidence = evidence
        )
    }
}
