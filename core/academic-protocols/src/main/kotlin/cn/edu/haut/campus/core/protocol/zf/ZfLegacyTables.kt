package cn.edu.haut.campus.core.protocol.zf

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.protocol.RemoteCourse
import cn.edu.haut.campus.core.protocol.RemoteExam
import cn.edu.haut.campus.core.protocol.RemoteGrade
import cn.edu.haut.campus.core.protocol.RemoteMeeting
import cn.edu.haut.campus.core.protocol.TransportFailure

/**
 * 旧正方 ASP.NET 页面解析（UNVERIFIED）。
 *
 * 页面形态按公开协议与合成夹具推导：学年/学期下拉框、rowspan/colspan 课表、
 * 表头驱动的成绩表与考试表。**未在任何真实学校页面核对**，
 * 因此所有“找不到预期结构”的分支都返回 PAGE_CHANGED，而不是猜字段。
 */
internal object ZfLegacyTables {

    data class TermOptions(val years: List<ZfOption>, val semesters: List<ZfOption>)

    data class ParsedCourses(val courses: List<RemoteCourse>, val incomplete: Boolean)

    data class ParsedGrades(val grades: List<RemoteGrade>, val truncated: Boolean, val incomplete: Boolean)

    data class ParsedExams(val exams: List<RemoteExam>, val truncated: Boolean, val incomplete: Boolean)

    private val WEEKDAY = Regex("""(星期|周)([一二三四五六日天])""")

    /** 服务端明确提示“结果被截断”的文案；命中即不得发布为完整快照。 */
    private val TRUNCATION_MARKERS = listOf("仅显示部分", "只显示前", "结果过多", "数据过多", "已截断", "请缩小")


    fun termOptions(html: String): TermOptions {
        val selects = ZfHtml.selects(html)
        return TermOptions(
            years = selects["ddlXN"].orEmpty(),
            semesters = selects["ddlXQ"].orEmpty()
        )
    }

    fun timetable(html: String): ParsedCourses {
        val table = ZfHtml.tables(html).firstOrNull { candidate ->
            candidate.expand().any { row -> row.any { WEEKDAY.containsMatchIn(it) } }
        } ?: throw TransportFailure(AcademicError.PAGE_CHANGED, "课表页面缺少带星期表头的表格")

        val grid = table.expand()
        val headerIndex = grid.indexOfFirst { row -> row.any { WEEKDAY.containsMatchIn(it) } }
        if (headerIndex < 0) throw TransportFailure(AcademicError.PAGE_CHANGED, "课表页面缺少星期表头")
        val weekdayByColumn = grid[headerIndex]
            .mapIndexedNotNull { column, text -> weekdayOf(text)?.let { column to it } }
            .toMap()
        if (weekdayByColumn.isEmpty()) throw TransportFailure(AcademicError.PAGE_CHANGED, "课表表头没有任何星期列")

        var incomplete = false
        val builders = LinkedHashMap<String, CourseBuilder>()
        for (rowIndex in headerIndex + 1 until grid.size) {
            val row = grid[rowIndex]
            val sections = ZfParsing.sections(row.firstOrNull())
            if (sections == null) {
                // 没有节次标签的行（说明行、备注行）直接跳过，不算数据缺失
                continue
            }
            for ((column, weekday) in weekdayByColumn) {
                val text = row.getOrNull(column).orEmpty()
                if (text.isBlank()) continue
                val cell = parseCell(text)
                if (cell == null) {
                    incomplete = true
                    continue
                }
                if (!cell.weeksRecognized) incomplete = true
                val key = cell.name + "|" + cell.teacher.orEmpty()
                val builder = builders.getOrPut(key) { CourseBuilder("kb-legacy:" + key, cell.name, cell.teacher) }
                builder.meetings += RemoteMeeting(
                    weekday = weekday,
                    startSection = sections.first,
                    endSection = sections.second,
                    weeks = cell.weeks,
                    location = cell.location,
                    teacher = cell.teacher
                )
            }
        }
        return ParsedCourses(
            courses = builders.values.map { it.build() },
            incomplete = incomplete
        )
    }

    fun grades(html: String): ParsedGrades {
        val grid = findTable(html, "课程名称", "成绩页面缺少成绩表格")
        val headerIndex = grid.indexOfFirst { row -> row.any { it.contains("课程名称") } }
        if (headerIndex < 0) throw TransportFailure(AcademicError.PAGE_CHANGED, "成绩表格缺少课程名称表头")
        val header = grid[headerIndex]
        val nameColumn = header.indexOfFirst { it.contains("课程名称") }
        val creditColumn = header.indexOfFirst { it.contains("学分") }
        val scoreColumn = header.indexOfFirst { it.trim() == "成绩" }
            .takeIf { it >= 0 } ?: header.indexOfFirst { it.contains("成绩") && !it.contains("补考") && !it.contains("重修") }
        val pointColumn = header.indexOfFirst { it.contains("绩点") }
        val codeColumn = header.indexOfFirst { it.contains("课程代码") || it.contains("课程号") }
        val categoryColumn = header.indexOfFirst { it.contains("考核") || it.contains("课程性质") || it.contains("修读") }

        var incomplete = false
        val used = HashMap<String, Int>()
        val grades = ArrayList<RemoteGrade>()
        for (rowIndex in headerIndex + 1 until grid.size) {
            val row = grid[rowIndex]
            val name = cellAt(row, nameColumn)
            if (name.isNullOrBlank()) {
                if (row.any { it.isNotBlank() }) incomplete = true
                continue
            }
            val code = cellAt(row, codeColumn)
            val base = code ?: name
            val ordinal = (used[base] ?: 0) + 1
            used[base] = ordinal
            grades += RemoteGrade(
                sourceId = if (ordinal == 1) "cj-legacy:" + base else "cj-legacy:" + base + "#" + ordinal,
                courseName = name,
                credits = cellAt(row, creditColumn)?.toBigDecimalOrNull(),
                scoreText = cellAt(row, scoreColumn),
                gradePoint = cellAt(row, pointColumn)?.toBigDecimalOrNull(),
                attempt = cellAt(row, categoryColumn)
            )
        }
        return ParsedGrades(grades, truncated = isTruncated(html), incomplete = incomplete)
    }

    fun exams(html: String): ParsedExams {
        val grid = findTable(html, "考试课程", "考试页面缺少考试表格")
        val headerIndex = grid.indexOfFirst { row -> row.any { it.contains("考试课程") } }
        if (headerIndex < 0) throw TransportFailure(AcademicError.PAGE_CHANGED, "考试表格缺少考试课程表头")
        val header = grid[headerIndex]
        val courseColumn = header.indexOfFirst { it.contains("考试课程") }
        val timeColumn = header.indexOfFirst { it.contains("考试时间") || it.contains("时间") }
        val roomColumn = header.indexOfFirst { it.contains("考场") || it.contains("考试地点") }
        val seatColumn = header.indexOfFirst { it.contains("座位") }
        val kindColumn = header.indexOfFirst { it.contains("考试性质") || it.contains("考核") }

        var incomplete = false
        val used = HashMap<String, Int>()
        val exams = ArrayList<RemoteExam>()
        for (rowIndex in headerIndex + 1 until grid.size) {
            val row = grid[rowIndex]
            val course = cellAt(row, courseColumn)
            if (course.isNullOrBlank()) {
                if (row.any { it.isNotBlank() }) incomplete = true
                continue
            }
            val timeText = cellAt(row, timeColumn)
            val slot = ZfParsing.examSlot(timeText)
            if (timeText != null && slot?.date == null) incomplete = true
            val kind = cellAt(row, kindColumn)
            val base = listOfNotNull(course, slot?.date?.toString(), kind).joinToString("|")
            val ordinal = (used[base] ?: 0) + 1
            used[base] = ordinal
            exams += RemoteExam(
                sourceId = if (ordinal == 1) "ks-legacy:" + base else "ks-legacy:" + base + "#" + ordinal,
                courseName = course,
                date = slot?.date,
                startTime = slot?.start,
                endTime = slot?.end,
                location = cellAt(row, roomColumn),
                seat = cellAt(row, seatColumn),
                kind = kind
            )
        }
        return ParsedExams(exams, truncated = isTruncated(html), incomplete = incomplete)
    }

    private class CourseBuilder(val sourceId: String, val name: String, val teacher: String?) {
        val meetings = mutableListOf<RemoteMeeting>()

        fun build(): RemoteCourse = RemoteCourse(
            sourceId = sourceId,
            name = name,
            teacher = teacher,
            meetings = meetings.sortedWith(compareBy({ it.weekday }, { it.startSection }))
        )
    }

    private class CourseCell(
        val name: String,
        val weeks: Set<Int>,
        val weeksRecognized: Boolean,
        val location: String?,
        val teacher: String?
    )

    /**
     * 单元格文本按 <br> 拆行：第一行是课程名，之后依次是周次、教室、教师。
     * 带“教师:”“教室:”前缀的行按标签归属，其余按位置补位；周次行靠形态识别。
     */
    private fun parseCell(text: String): CourseCell? {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val name = lines.first()
        var weeks: Set<Int> = emptySet()
        var weeksRecognized = true
        var location: String? = null
        var teacher: String? = null
        for (line in lines.drop(1)) {
            val labeledTeacher = stripLabel(line, "教师", "老师", "授课教师")
            val labeledLocation = stripLabel(line, "教室", "地点", "上课地点")
            when {
                labeledTeacher != null -> teacher = labeledTeacher
                labeledLocation != null -> location = labeledLocation
                ZfWeeks.looksLikeWeeks(line) -> {
                    val parsed = ZfWeeks.parseStrict(line)
                    if (parsed == null) weeksRecognized = false else weeks = parsed
                }
                location == null -> location = line
                teacher == null -> teacher = line
                else -> Unit
            }
        }
        return CourseCell(name, weeks, weeksRecognized, location, teacher)
    }

    private fun stripLabel(line: String, vararg labels: String): String? {
        for (label in labels) {
            for (separator in listOf(":", "：")) {
                val prefix = label + separator
                if (line.startsWith(prefix)) {
                    val value = line.removePrefix(prefix).trim()
                    if (value.isNotEmpty()) return value
                }
            }
        }
        return null
    }

    private fun weekdayOf(text: String): Int? {
        val match = WEEKDAY.find(text) ?: return null
        return when (match.groupValues[2]) {
            "一" -> 1
            "二" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            "六" -> 6
            "日" -> 7
            "天" -> 7
            else -> null
        }
    }

    private fun findTable(html: String, headerKeyword: String, message: String): List<List<String>> {
        val grid = ZfHtml.tables(html)
            .map { it.expand() }
            .firstOrNull { rows -> rows.any { row -> row.any { it.contains(headerKeyword) } } }
        return grid ?: throw TransportFailure(AcademicError.PAGE_CHANGED, message)
    }

    private fun cellAt(row: List<String>, column: Int): String? =
        if (column < 0) null else row.getOrNull(column)?.trim()?.takeIf { it.isNotEmpty() }

    private fun isTruncated(html: String): Boolean {
        val text = ZfHtml.plainText(html)
        return TRUNCATION_MARKERS.any { text.contains(it) }
    }
}
