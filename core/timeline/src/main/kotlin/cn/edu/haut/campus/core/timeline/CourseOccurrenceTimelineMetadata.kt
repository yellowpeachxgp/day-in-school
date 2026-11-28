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
 *
 * 自由文本（课程名、教师、教室、覆盖原因）不允许把既有合法输入直接塞进受控信封：
 * 安全短文本保持原样以减少开销；含 ISO 控制字符或超出单值上限的文本统一用
 * JSON 转义分片保存，截断时由调用侧权威输入快照兜底，绝不允许投影 flow 因
 * TimelineEvent/sourceMetadata require 而死亡。
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
    const val COURSE_NAME = "courseOccurrence.courseName"
    const val COURSE_NAME_JSON_CHUNK_COUNT = "courseOccurrence.courseName.json.chunkCount"
    const val COURSE_NAME_JSON_TRUNCATED = "courseOccurrence.courseName.json.truncated"
    const val COURSE_NAME_JSON_CHUNK_PREFIX = "courseOccurrence.courseName.json.chunk."

    /**
     * TimelineEvent 信封标题的安全投影：控制字符替换为 U+FFFD、超长截断，
     * 空/全控制输入仍给非空占位。原文不在这里丢失——需要时由 metadata 的
     * JSON 转义分片或调用侧课程快照无损还原。
     */
    internal fun safeTimelineTitle(raw: String): String =
        TimelineTextSafety.safeTimelineTitle(raw, blankFallback = "课程")

    private fun decodeLosslessText(metadata: Map<String, String>, baseKey: String): String? =
        TimelineTextSafety.decodeLosslessText(metadata, baseKey)

    private fun hasTruncatedLosslessText(metadata: Map<String, String>): Boolean =
        TimelineTextSafety.hasTruncatedLosslessText(metadata)

    fun encode(occurrence: ClassOccurrence): Map<String, String> = buildMap {
        put(OCCURRENCE_ID, occurrence.occurrenceId)
        put(COURSE_ID, occurrence.courseId)
        occurrence.ruleId?.let { put(RULE_ID, it) }
        occurrence.overrideId?.let { put(OVERRIDE_ID, it) }
        put(COLOR_ARGB, occurrence.colorArgb.toString())
        put(DATE, occurrence.date.toString())
        occurrence.teachingWeek?.let { put(TEACHING_WEEK, it.toString()) }
        put(START_PERIOD, occurrence.startPeriod.toString())
        put(END_PERIOD, occurrence.endPeriod.toString())
        put(START_TIME, occurrence.startTime.toString())
        put(END_TIME, occurrence.endTime.toString())
        occurrence.buildingId?.let { put(BUILDING_ID, it) }
        put(MAKEUP_OR_OVERRIDDEN, occurrence.isMakeupOrOverridden.toString())
        put(USER_MODIFIED, occurrence.userModified.toString())
        put(
            MERGED_RULE_IDS_JSON,
            Json.canonical(JsonValue.Arr(occurrence.mergedRuleIds.map { JsonValue.Str(it) }))
        )
        put(TIME_EVIDENCE, occurrence.timeEvidence.name)

        // 三个既有自由文本与课程名共用同一 JSON 分片协议。先把安全短文本放入原 key，
        // 再对需要分片的字段做全局 64 条硬预算：任何字段都不能先把预算吃满导致后续 put 崩流；
        // 装不下的尾部真正丢弃并显式 truncated，还原侧只有看到这个标记才启用权威输入兜底。
        val textInputs = buildList {
            add(RAW_ROOM to occurrence.rawRoom)
            occurrence.teacher?.let { add(TEACHER to it) }
            occurrence.overrideReason?.let { add(OVERRIDE_REASON to it) }
            if (safeTimelineTitle(occurrence.courseName) != occurrence.courseName) {
                add(COURSE_NAME to occurrence.courseName)
            }
        }
        TimelineTextSafety.encodeLosslessTextFields(this, textInputs)
    }

    /** 还原 Timeline 投影时的原始领域字段；buildingName、internalBreaks 与冲突分栏仍由课表 engine 统一后处理。 */
    fun decode(
        event: TimelineEvent,
        courseNamesById: Map<String, String> = emptyMap(),
        fallbackOccurrence: (String) -> ClassOccurrence? = { null }
    ): ClassOccurrence {
        val metadata = event.sourceMetadata
        fun required(key: String): String = metadata[key]
            ?: throw IllegalArgumentException("课程 Timeline 事件缺少元数据 " + key + ": " + event.id.value)
        val mergedIds = Json.parse(required(MERGED_RULE_IDS_JSON)).asArrOrNull
            ?.items
            ?.mapNotNull { it.stringOrNull() }
            ?: throw IllegalArgumentException("课程 Timeline mergedRuleIds 元数据不是字符串数组: " + event.id.value)
        val evidence = TimeEvidence.valueOf(required(TIME_EVIDENCE))
        val occurrenceId = required(OCCURRENCE_ID)
        val fallback = if (hasTruncatedLosslessText(metadata)) fallbackOccurrence(occurrenceId) else null
        val courseId = required(COURSE_ID)
        val restoredCourseName = decodeLosslessText(metadata, COURSE_NAME)
            ?: fallback?.courseName
            ?: courseNamesById[courseId]
            ?: event.title
        val restoredTeacher = decodeLosslessText(metadata, TEACHER)
            ?: fallback?.teacher
        val restoredRawRoom = decodeLosslessText(metadata, RAW_ROOM)
            ?: fallback?.rawRoom
            ?: ""
        val restoredOverrideReason = decodeLosslessText(metadata, OVERRIDE_REASON)
            ?: fallback?.overrideReason
        // hasResolvedTime 必须随证据等级还原：UNRESOLVED/INVALID 只保留排序锚点，
        // 缺省会意外回落为 true，把「时间待确认」冒充成精确时间。
        val hasResolvedTime = evidence.hasResolvedTime
        return ClassOccurrence(
            occurrenceId = occurrenceId,
            courseId = courseId,
            ruleId = metadata[RULE_ID],
            overrideId = metadata[OVERRIDE_ID],
            courseName = restoredCourseName,
            teacher = restoredTeacher,
            colorArgb = required(COLOR_ARGB).toLong(),
            date = LocalDate.parse(required(DATE)),
            teachingWeek = metadata[TEACHING_WEEK]?.toInt(),
            weekday = LocalDate.parse(required(DATE)).dayOfWeek,
            startPeriod = required(START_PERIOD).toInt(),
            endPeriod = required(END_PERIOD).toInt(),
            startTime = LocalTime.parse(required(START_TIME)),
            endTime = LocalTime.parse(required(END_TIME)),
            rawRoom = restoredRawRoom,
            buildingId = metadata[BUILDING_ID],
            isMakeupOrOverridden = required(MAKEUP_OR_OVERRIDDEN).toBooleanStrict(),
            overrideReason = restoredOverrideReason,
            userModified = required(USER_MODIFIED).toBooleanStrict(),
            mergedRuleIds = mergedIds,
            hasResolvedTime = hasResolvedTime,
            timeEvidence = evidence
        )
    }
}
