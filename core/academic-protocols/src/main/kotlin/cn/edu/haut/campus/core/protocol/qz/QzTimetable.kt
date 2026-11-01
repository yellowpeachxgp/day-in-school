package cn.edu.haut.campus.core.protocol.qz

import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.protocol.RemoteCourse
import cn.edu.haut.campus.core.protocol.RemoteMeeting
import java.math.BigDecimal

/**
 * UNVERIFIED（未验证）：课表解析按公开 jsxsd 页面形态推导，未在任何真实学校站点验证。
 *
 * 同时支持两条通道：
 * 1. 网格 HTML（行列交叉、用 rowspan 表示跨节次）；
 * 2. JSON 数组（新版页面把 kbList 直接序列化成 JSON）。
 * 两条通道都归并到同一个 [CourseAccumulator]，保证"同一门课多个时段"只产生一条课程记录。
 */
internal object QzTimetableGrid {

    private class HeaderLayout(val row: Int, val weekdayColumns: Map<Int, Int>)

    /**
     * 解析课表网格。返回 null 表示"这根本不是课表页"（没有可识别的星期表头），
     * 与"课表为空"（返回空列表）严格区分，前者应上报 PAGE_CHANGED 而不是发布空快照。
     */
    fun parse(html: String): List<RemoteCourse>? {
        for (table in QzHtml.tables(html)) {
            val layout = headerLayout(table) ?: continue
            return readGrid(table, layout)
        }
        return null
    }

    private fun headerLayout(table: HtmlTable): HeaderLayout? {
        for (row in 0 until table.rowCount) {
            val columns = LinkedHashMap<Int, Int>()
            for (column in 0 until table.columnCount) {
                val text = table.textAt(row, column) ?: continue
                if (text.length > 6) continue
                val weekday = QzScheduleText.parseWeekday(text) ?: continue
                columns.putIfAbsent(column, weekday)
            }
            if (columns.size >= 3 && columns.values.toSet().size >= 3) return HeaderLayout(row, columns)
        }
        return null
    }

    private fun readGrid(table: HtmlTable, layout: HeaderLayout): List<RemoteCourse> {
        val accumulator = CourseAccumulator()
        for (origin in table.origins()) {
            if (origin.row <= layout.row) continue
            val weekday = layout.weekdayColumns[origin.column] ?: continue
            val section = origin.row - layout.row
            if (section !in 1..60) continue
            val endSection = section + origin.cell.rowSpan - 1
            if (endSection !in section..60) continue
            val cell = parseCell(origin.cell.text, weekday, section, endSection) ?: continue
            accumulator.add(
                sourceId = cell.sourceId,
                name = cell.name,
                teacher = cell.teacher,
                meeting = cell.meeting
            )
        }
        return accumulator.build()
    }

    private class ParsedCell(
        val sourceId: String,
        val name: String,
        val teacher: String?,
        val meeting: RemoteMeeting
    )

    /**
     * 单元格书写顺序（UNVERIFIED）：课程名 → 教师 → 周次 → 地点，用 <br> 分行。
     * 周次行缺失时退化为"课程名 → 教师 → 地点"。
     */
    private fun parseCell(text: String, weekday: Int, startSection: Int, endSection: Int): ParsedCell? {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val name = lines[0]
        if (name.isEmpty()) return null
        val weeksIndex = lines.indices.firstOrNull { index ->
            index > 0 && '周' in lines[index] && QzScheduleText.parseWeeks(lines[index]).isNotEmpty()
        } ?: -1
        val weeks = if (weeksIndex > 0) QzScheduleText.parseWeeks(lines[weeksIndex]) else emptySet()
        val location = when {
            weeksIndex > 0 -> lines.getOrNull(weeksIndex + 1)
            weeksIndex < 0 -> lines.getOrNull(2)
            else -> null
        }
        val teacher = when {
            weeksIndex > 1 -> lines[1]
            weeksIndex < 0 -> lines.getOrNull(1)
            else -> null
        }
        val meeting = RemoteMeeting(
            weekday = weekday,
            startSection = startSection,
            endSection = endSection,
            weeks = weeks,
            location = location,
            teacher = teacher
        )
        return ParsedCell(sourceId = courseKey(name, teacher), name = name, teacher = teacher, meeting = meeting)
    }

    fun courseKey(name: String, teacher: String?): String =
        "qz:" + name.trim() + "|" + (teacher?.trim().orEmpty())
}

/** 新版页面返回的 kbList JSON 通道。 */
internal object QzTimetableJson {

    fun parse(root: JsonValue): List<RemoteCourse> {
        val items = root.asArrOrNull?.items ?: root.arr("kbList") ?: root.arr("items").orEmpty()
        val accumulator = CourseAccumulator()
        for (item in items) {
            if (item.asObjOrNull == null) continue
            val name = QzJson.text(item, "kcmc", "courseName", "name")?.trim().orEmpty()
            if (name.isEmpty()) continue
            val teacher = QzJson.text(item, "xm", "teacher", "jsxm")?.trim()?.ifEmpty { null }
            val code = QzJson.text(item, "kch", "courseCode", "kcdm")?.trim()?.ifEmpty { null }
            val weekday = QzScheduleText.parseWeekday(QzJson.text(item, "xqj", "weekday", "xingqi"))
                ?: QzJson.int(item, "xqj", "weekday")
            if (weekday == null || weekday !in 1..7) continue
            val sections = QzScheduleText.parseSections(QzJson.text(item, "jcs", "jc", "sections"))
                ?: QzScheduleText.parseSections(QzJson.text(item, "jcor", "jcstr"))
                ?: continue
            val weeks = QzScheduleText.parseWeeks(QzJson.text(item, "zcd", "weeks", "zhouci"))
            val location = QzJson.text(item, "cdmc", "room", "place")?.trim()?.ifEmpty { null }
            val sourceId = code?.let { "qz:" + it } ?: QzTimetableGrid.courseKey(name, teacher)
            accumulator.add(
                sourceId = sourceId,
                name = name,
                teacher = teacher,
                code = code,
                meeting = RemoteMeeting(
                    weekday = weekday,
                    startSection = sections.first,
                    endSection = sections.last,
                    weeks = weeks,
                    location = location,
                    teacher = teacher
                )
            )
        }
        return accumulator.build()
    }
}

/** 按来源 ID 归并课程，保证契约要求的"课程来源 ID 不重复"。 */
internal class CourseAccumulator {

    private class Draft(
        val sourceId: String,
        val name: String,
        var teacher: String?,
        var code: String?,
        var credits: BigDecimal?,
        val meetings: MutableList<RemoteMeeting>
    )

    private val drafts = LinkedHashMap<String, Draft>()

    fun add(
        sourceId: String,
        name: String,
        teacher: String? = null,
        code: String? = null,
        credits: BigDecimal? = null,
        meeting: RemoteMeeting? = null
    ) {
        val key = sourceId.ifBlank { QzTimetableGrid.courseKey(name, teacher) }
        val draft = drafts.getOrPut(key) { Draft(key, name.trim(), teacher, code, credits, ArrayList()) }
        if (draft.teacher == null) draft.teacher = teacher
        if (draft.code == null) draft.code = code
        if (draft.credits == null) draft.credits = credits
        if (meeting != null) draft.meetings += meeting
    }

    fun build(): List<RemoteCourse> = drafts.values.map { draft ->
        RemoteCourse(
            sourceId = draft.sourceId,
            name = draft.name,
            code = draft.code,
            credits = draft.credits,
            teacher = draft.teacher,
            meetings = draft.meetings.toList()
        )
    }
}

/** 宽容取值：字段可能写成字符串，也可能写成数字；缺失或类型不符时返回 null。 */
internal object QzJson {

    fun text(value: JsonValue, vararg keys: String): String? {
        for (key in keys) {
            val field = value.obj(key) ?: continue
            val text = field.stringOrNull() ?: field.intOrNull()?.toString()
            if (text != null) return text
        }
        return null
    }

    fun int(value: JsonValue, vararg keys: String): Int? {
        for (key in keys) {
            val field = value.obj(key) ?: continue
            val number = field.intOrNull() ?: field.stringOrNull()?.trim()?.toIntOrNull()
            if (number != null) return number
        }
        return null
    }

    fun decimal(value: JsonValue, vararg keys: String): BigDecimal? {
        for (key in keys) {
            val field = value.obj(key) ?: continue
            val text = field.stringOrNull() ?: field.intOrNull()?.toString() ?: continue
            val trimmed = text.trim()
            if (trimmed.isEmpty()) continue
            runCatching { BigDecimal(trimmed) }.getOrNull()?.let { return it }
        }
        return null
    }
}
