/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Copyright (c) 2026 rainthink-vanilla (Univ Timetable v1 contract)
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.core.academic

import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.WeeksSetParser
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.ImportCourseCandidate
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.Term
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * `univ-timetable/v1` 课表文件双向互通适配器：
 * 1. 支持导入标准 `univ-timetable/v1` JSON、Markdown（含内嵌 JSON 代码块或课程明细表格）、正方 `kbList` JSON；
 * 2. 自动将不连续节次拆分为连续区间 [ImportCourseCandidate]，无缝接入 [cn.edu.haut.campus.core.domain.ImportDiffEngine]；
 * 3. 支持脱敏导出为 `univ-timetable/v1` JSON 与可读 Markdown 文件（不含姓名、学号、成绩、水电账号与会话令牌）。
 */
object TimetableInterchangeAdapter {

    const val CONTRACT_VERSION = "univ-timetable/v1"
    const val MAX_FILE_CHARS = 2_000_000
    private const val MAX_COURSE_ROWS = 2000
    private const val MAX_PERIOD_ROWS = 20

    data class InterchangeSectionTime(
        val periodNo: Int,
        val startText: String,
        val endText: String
    ) {
        fun toPeriodTime(timeTableId: String = "haut_lianhuajie_default"): PeriodTime {
            return PeriodTime(
                id = "p_${periodNo}",
                timeTableId = timeTableId,
                periodNo = periodNo,
                startLocalTime = LocalTime.parse(startText),
                endLocalTime = LocalTime.parse(endText)
            )
        }
    }

    data class InterchangeParseResult(
        val candidates: List<ImportCourseCandidate>,
        val skippedCount: Int,
        val semesterStart: LocalDate? = null,
        val totalWeeks: Int? = null,
        val sectionTimes: List<InterchangeSectionTime> = emptyList(),
        val warnings: List<String> = emptyList(),
        val sourceLabel: String = "JSON"
    ) {
        val hasCalendarMetadata: Boolean
            get() = semesterStart != null || totalWeeks != null || sectionTimes.isNotEmpty()
    }

    fun parse(rawText: String): InterchangeParseResult {
        require(rawText.length <= MAX_FILE_CHARS) { "课表文件超过 2 MB 限制" }
        val trimmed = rawText.removePrefix("\uFEFF").trim()
        require(trimmed.isNotEmpty()) { "课表文件内容为空" }

        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return parseMarkdown(trimmed)
        }

        val rootObj = if (trimmed.startsWith("{")) JSONObject(trimmed) else null
        if (rootObj?.has("contract") == true) {
            val contract = rootObj.optString("contract")
            require(contract == CONTRACT_VERSION) { "暂不支持该课表文件版本：$contract" }
        }

        val rawList: JSONArray = when {
            rootObj == null -> JSONArray(trimmed)
            rootObj.optJSONArray("kbList") != null -> rootObj.getJSONArray("kbList")
            rootObj.optJSONObject("data")?.optJSONArray("kbList") != null ->
                rootObj.getJSONObject("data").getJSONArray("kbList")
            rootObj.optJSONArray("courses") != null -> rootObj.getJSONArray("courses")
            else -> throw IllegalArgumentException("未找到课程列表字段（courses 或 kbList）")
        }
        require(rawList.length() <= MAX_COURSE_ROWS) { "课程条目超过 $MAX_COURSE_ROWS 条上限" }

        val warnings = mutableListOf<String>()
        val calendar = parseCalendarMetadata(rootObj ?: JSONObject(), warnings)
        val candidates = mutableListOf<ImportCourseCandidate>()
        var skipped = 0

        for (i in 0 until rawList.length()) {
            runCatching {
                val row = rawList.getJSONObject(i)
                val name = row.optString("kcmc", row.optString("name")).trim()
                require(name.isNotEmpty()) { "缺少课程名称" }

                val teacher = row.optString("xm", row.optString("teacher")).trim().ifBlank { null }
                val cdmc = row.optString("cdmc", row.optString("location")).trim()
                val lh = row.optString("lh").trim()
                val rawRoom = when {
                    cdmc.isNotBlank() && cdmc != "未排地点" -> cdmc
                    lh.isNotBlank() && lh != "无楼号" -> lh
                    else -> "待定教室"
                }

                val credit = row.optString("xf", row.optString("credit")).trim().toBigDecimalOrNull()
                val externalId = row.optString("kch_id", row.optString("kch")).trim().ifBlank { null }

                val dayInt = when {
                    row.has("xqj") -> row.optString("xqj").toIntOrNull() ?: row.getInt("xqj")
                    row.has("dayOfWeek") -> row.getInt("dayOfWeek")
                    else -> row.getInt("day")
                }
                require(dayInt in 1..7) { "星期超出 1–7 范围" }
                val weekday = DayOfWeek.of(dayInt)

                val weeksList: List<Int> = row.optJSONArray("weeks")?.let { arr ->
                    (0 until arr.length()).map { idx -> arr.getInt(idx) }
                } ?: parseWeeksStrict(row.optString("zcd", row.optString("weeks")))
                require(weeksList.isNotEmpty() && weeksList.all { it in 1..60 }) { "周次无效" }
                val weeksSet = weeksList.toSortedSet()

                val sectionValues: List<Int> = row.optJSONArray("sections")?.let { arr ->
                    (0 until arr.length()).map { idx -> arr.getInt(idx) }
                } ?: if (row.has("startPeriod")) {
                    val first = row.getInt("startPeriod")
                    val last = row.optInt("endPeriod", first)
                    require(first in 1..MAX_PERIOD_ROWS && last in first..MAX_PERIOD_ROWS) { "节次范围有误" }
                    (first..last).toList()
                } else {
                    parseSectionsStrict(
                        row.optString("jcs")
                            .ifBlank { row.optString("jcor") }
                            .ifBlank { row.optString("jc") }
                    )
                }
                require(sectionValues.isNotEmpty() && sectionValues.all { it in 1..MAX_PERIOD_ROWS }) { "节次无效" }

                for ((startP, endP) in splitIntoContiguousRanges(sectionValues)) {
                    candidates += ImportCourseCandidate(
                        externalId = externalId,
                        courseName = name,
                        teacher = teacher,
                        credit = credit,
                        weekday = weekday,
                        startPeriod = startP,
                        endPeriod = endP,
                        weeks = weeksSet,
                        rawRoom = rawRoom
                    )
                }
            }.onFailure {
                skipped++
            }
        }

        require(candidates.isNotEmpty()) {
            "未读到有效课程（共 $skipped 条无法解析），原课表已保留。"
        }
        if (rootObj?.has("theme") == true) {
            warnings += "文件中的主题配色未应用，已保留当前界面外观。"
        }

        val deduplicated = candidates.distinctBy {
            listOf(it.courseName, it.teacher, it.rawRoom, it.weekday, it.startPeriod, it.endPeriod, it.weeks)
        }
        val sourceLabel = if (rootObj?.optString("contract") == CONTRACT_VERSION) {
            "大学课表文件"
        } else {
            "课表 JSON"
        }

        return InterchangeParseResult(
            candidates = deduplicated,
            skippedCount = skipped,
            semesterStart = calendar.semesterStart,
            totalWeeks = calendar.totalWeeks,
            sectionTimes = calendar.sectionTimes,
            warnings = warnings,
            sourceLabel = sourceLabel
        )
    }

    fun parseMarkdown(text: String): InterchangeParseResult {
        val codeBlocks = Regex("```(?:json|JSON)\\s*\\r?\\n([\\s\\S]*?)```")
            .findAll(text)
            .map { it.groupValues[1].trim() }
            .toList()
        val jsonCandidate = codeBlocks.firstOrNull { block ->
            runCatching {
                val obj = JSONObject(block)
                obj.has("courses") || obj.has("kbList")
            }.getOrDefault(false)
        }
        if (jsonCandidate != null) {
            val parsed = parse(jsonCandidate)
            return parsed.copy(sourceLabel = "Markdown 内嵌 JSON · ${parsed.sourceLabel}")
        }

        val rows = JSONArray()
        val periods = JSONArray()
        val meta = JSONObject()
        var courseHeader: List<String>? = null
        var inPeriodTable = false
        var rejected = 0

        for (line in text.lines()) {
            if (!line.trim().startsWith("|")) {
                courseHeader = null
                inPeriodTable = false
                continue
            }
            val values = parseMarkdownCells(line)
            if (isSeparatorRow(values)) continue

            if (values.size >= 2) {
                when (values[0]) {
                    "学期开始日期", "学期起始日", "semesterStart" -> {
                        val v = values[1].takeUnless { it == "-" }.orEmpty()
                        if (v.isNotBlank()) meta.put("semesterStart", v)
                    }
                    "学期总周数", "总周数", "totalWeeks" -> {
                        values[1].toIntOrNull()?.let { meta.put("totalWeeks", it) }
                    }
                }
            }

            if (values.any { it.contains("课程名称") || it == "课程" } &&
                values.any { it == "星期" || it == "星期几" }
            ) {
                courseHeader = values
                inPeriodTable = false
                continue
            }

            if (values.size >= 2 && values[0] == "节次" && (values[1] == "时间" || values[1].contains("开始"))) {
                courseHeader = null
                inPeriodTable = true
                continue
            }

            if (inPeriodTable && values.size >= 2) {
                val index = Regex("\\d+").find(values[0])?.value?.toIntOrNull()
                val span = Regex("(\\d{1,2}[:：]\\d{2})\\s*[-–—至到]\\s*(\\d{1,2}[:：]\\d{2})").find(values[1])
                if (index != null && span != null) {
                    periods.put(
                        JSONObject()
                            .put("index", index)
                            .put("start", span.groupValues[1])
                            .put("end", span.groupValues[2])
                    )
                } else if (index != null && values.size >= 3) {
                    periods.put(
                        JSONObject()
                            .put("index", index)
                            .put("start", values[1])
                            .put("end", values[2])
                    )
                }
                continue
            }

            val columns = courseHeader ?: continue
            fun field(vararg keys: String): String {
                val idx = columns.indexOfFirst { it in keys }
                return values.getOrNull(idx)?.takeUnless { it == "-" }.orEmpty()
            }

            val name = field("课程名称", "课程")
            if (name.isBlank()) continue

            runCatching {
                val weekdays = parseWeekdays(field("星期", "星期几"))
                require(weekdays.isNotEmpty()) { "缺少星期" }
                val sections = parseSectionsStrict(field("节次", "时段"))
                val weekText = field("周次", "周数")
                require(weekText.isNotBlank()) { "缺少周次" }
                val weeks = parseWeeksStrict(weekText)
                for (day in weekdays) {
                    rows.put(
                        JSONObject()
                            .put("name", name)
                            .put("teacher", field("教师", "老师"))
                            .put("location", field("地点", "教室"))
                            .put("day", day)
                            .put("weeks", JSONArray(weeks))
                            .put("sections", JSONArray(sections))
                            .put("note", field("备注"))
                    )
                }
            }.onFailure {
                rejected++
            }
            require(rows.length() <= MAX_COURSE_ROWS) { "课程条目过多" }
        }

        require(rows.length() > 0) {
            "未找到有效课程。支持大学课表 .md/.json 文件、Markdown 课程表及正方 JSON。"
        }

        val syntheticRoot = JSONObject()
            .put("courses", rows)
            .put("meta", meta)
            .put("periods", periods)
        val result = parse(syntheticRoot.toString())
        return result.copy(
            skippedCount = result.skippedCount + rejected,
            sourceLabel = "Markdown 课程明细表"
        )
    }

    fun exportJson(
        term: Term?,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        now: Instant = Instant.now()
    ): String {
        val root = buildExportJsonObject(term, courses, rules, periodTimes, now)
        return root.toString(2)
    }

    fun exportMarkdown(
        term: Term?,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        now: Instant = Instant.now()
    ): String {
        val root = buildExportJsonObject(term, courses, rules, periodTimes, now)
        val meta = root.getJSONObject("meta")
        val periodsArray = root.getJSONArray("periods")
        val coursesArray = root.getJSONArray("courses")

        return buildString {
            appendLine("# 大学课表数据文件")
            appendLine()
            appendLine("> 契约版本：`$CONTRACT_VERSION`　生成时间：${now}")
            appendLine()
            appendLine("## 一、课表元信息")
            appendLine()
            appendLine("| 项目 | 内容 |")
            appendLine("| --- | --- |")
            appendLine("| 学校 | ${meta.optString("school", "河南工业大学")} |")
            appendLine("| 教务系统地址 | - |")
            appendLine("| 学期 | ${meta.optString("semester", "-")} |")
            appendLine("| 学期开始日期 | ${meta.optString("semesterStart", "-")} |")
            appendLine("| 学期总周数 | ${meta.optInt("totalWeeks", 20)} |")
            appendLine("| 覆盖周次 | - |")
            appendLine("| 爬取范围 | - |")
            appendLine("| 学生 | - |")
            appendLine("| 学号 | - |")
            appendLine("| 爬取时间 | ${now} |")
            appendLine()
            appendLine("## 二、节次时间表（各校作息不同，请按学校实际填写）")
            appendLine()
            appendLine("| 节次 | 时间 |")
            appendLine("| --- | --- |")
            for (i in 0 until periodsArray.length()) {
                val p = periodsArray.getJSONObject(i)
                appendLine("| 第${p.getInt("index")}节 | ${p.getString("start")} - ${p.getString("end")} |")
            }
            appendLine()
            appendLine("## 三、课程明细")
            appendLine()
            appendLine("| 课程名称 | 教师 | 地点 | 星期 | 节次 | 周次 | 备注 |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- |")
            for (i in 0 until coursesArray.length()) {
                val c = coursesArray.getJSONObject(i)
                val name = c.optString("name").replace("|", "｜")
                val teacher = c.optString("teacher").ifBlank { "-" }.replace("|", "｜")
                val location = c.optString("location").ifBlank { "-" }.replace("|", "｜")
                val dayLabel = DayOfWeek.of(c.getInt("dayOfWeek").coerceIn(1, 7)).ChineseLabel
                val startP = c.getInt("startPeriod")
                val endP = c.getInt("endPeriod")
                val periodLabel = if (startP == endP) "第${startP}节" else "第${startP}-${endP}节"
                val weeksArr = c.getJSONArray("weeks")
                val weeksSet = (0 until weeksArr.length()).map { weeksArr.getInt(it) }.toSortedSet()
                val weeksText = WeeksSetParser.format(weeksSet)
                val note = c.optString("note").ifBlank { "-" }.replace("|", "｜")
                appendLine("| $name | $teacher | $location | $dayLabel | $periodLabel | $weeksText | $note |")
            }
            appendLine()
            appendLine("## 四、机器可读数据（App 直接读取这一段）")
            appendLine()
            appendLine("```json")
            appendLine(root.toString(2))
            appendLine("```")
        }
    }

    private fun buildExportJsonObject(
        term: Term?,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        now: Instant
    ): JSONObject {
        val courseById = courses.associateBy { it.id }
        val activeRules = if (term != null) {
            rules.filter { it.termId == term.id }
        } else {
            rules
        }.sortedWith(compareBy({ it.weekday.value }, { it.startPeriod }, { courseById[it.courseId]?.name.orEmpty() }))

        val rows = JSONArray()
        for (rule in activeRules) {
            val course = courseById[rule.courseId] ?: continue
            val sortedWeeks = rule.weeks.distinct().sorted()
            val campusCode = if (rule.rawRoom.contains("嵩山")) "SONGSHAN" else "LIANHUA"
            rows.put(
                JSONObject()
                    .put("name", course.name)
                    .put("teacher", course.teacher.orEmpty())
                    .put("location", rule.rawRoom)
                    .put("dayOfWeek", rule.weekday.value)
                    .put("startPeriod", rule.startPeriod)
                    .put("endPeriod", rule.endPeriod)
                    .put("weeks", JSONArray(sortedWeeks))
                    .put("note", "")
                    .put("campus", campusCode)
            )
        }

        val meta = JSONObject()
            .put("school", "河南工业大学")
            .put("semester", term?.displayName ?: "当前学期")
            .put("totalWeeks", (term?.weekCount ?: 20).coerceIn(1, 60))
            .put("scrapedAt", now.toString())
        if (term != null) {
            meta.put("semesterStart", term.startDate.toString())
        }

        val periodsJson = JSONArray(
            periodTimes.sortedBy { it.periodNo }.map { pt ->
                JSONObject()
                    .put("index", pt.periodNo)
                    .put("start", pt.startLocalTime.toString().take(5))
                    .put("end", pt.endLocalTime.toString().take(5))
            }
        )

        return JSONObject()
            .put("contract", CONTRACT_VERSION)
            .put("meta", meta)
            .put("periods", periodsJson)
            .put("courses", rows)
    }

    private data class CalendarMeta(
        val semesterStart: LocalDate?,
        val totalWeeks: Int?,
        val sectionTimes: List<InterchangeSectionTime>
    )

    private fun parseCalendarMetadata(root: JSONObject, warnings: MutableList<String>): CalendarMeta {
        val meta = root.optJSONObject("meta") ?: root
        val dateText = meta.optString("semesterStart").trim()
        val parsedStart = if (dateText.isBlank()) null else runCatching {
            val parts = dateText.split('-').map { it.toInt() }
            require(parts.size == 3)
            LocalDate.of(parts[0], parts[1], parts[2]).also {
                require(it.dayOfWeek == DayOfWeek.MONDAY)
            }
        }.getOrElse {
            warnings += "文件学期起始日不是有效周一，未导入该日期。"
            null
        }

        val totalWeeks = meta.optInt("totalWeeks", 0).takeIf { it in 1..60 }
        if (meta.has("totalWeeks") && totalWeeks == null) {
            warnings += "文件总周数超出 1–60，未应用。"
        }

        val periodsArray = root.optJSONArray("periods")
            ?: root.optJSONArray("periodTimes")
            ?: meta.optJSONArray("periods")
        val times = mutableListOf<InterchangeSectionTime>()
        if (periodsArray != null) {
            require(periodsArray.length() <= MAX_PERIOD_ROWS) { "节次时间条目超过 $MAX_PERIOD_ROWS" }
            for (i in 0 until periodsArray.length()) {
                runCatching {
                    val item = periodsArray.getJSONObject(i)
                    val periodNo = item.optInt("index", item.optInt("period", i + 1))
                    require(periodNo in 1..MAX_PERIOD_ROWS)
                    InterchangeSectionTime(
                        periodNo = periodNo,
                        startText = normalizeClock(item.getString("start")),
                        endText = normalizeClock(item.getString("end"))
                    ).also {
                        require(LocalTime.parse(it.startText) < LocalTime.parse(it.endText))
                    }
                }.onSuccess {
                    times += it
                }.onFailure {
                    warnings += "第 ${i + 1} 条节次时间有误，未应用整份作息表。"
                }
            }
        }

        val ordered = times.sortedBy { it.periodNo }
        val valid = ordered.distinctBy { it.periodNo }.size == ordered.size &&
            ordered.zipWithNext().all { (a, b) ->
                LocalTime.parse(a.endText) <= LocalTime.parse(b.startText)
            }
        if (!valid) {
            warnings += "文件节次时间重复、重叠或倒序，未应用整份作息表。"
        }
        val effectiveTimes = if (valid && (periodsArray == null || times.size == periodsArray.length())) {
            ordered
        } else {
            emptyList()
        }
        return CalendarMeta(parsedStart, totalWeeks, effectiveTimes)
    }

    private fun normalizeClock(text: String): String {
        val parts = text.trim().replace('：', ':').split(':')
        require(parts.size == 2)
        return LocalTime.of(parts[0].toInt(), parts[1].toInt()).toString().take(5)
    }

    private fun parseMarkdownCells(line: String): List<String> =
        line.trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split('|')
            .map { it.trim().replace("**", "").replace("`", "") }

    private fun isSeparatorRow(values: List<String>): Boolean =
        values.isNotEmpty() && values.all { Regex(":?-{2,}:?").matches(it) }

    fun parseWeeksStrict(text: String): List<Int> {
        val normalized = text
            .replace('，', ',')
            .replace('、', ',')
            .replace("到", "-")
            .replace('－', '-')
            .replace('–', '-')
            .replace('—', '-')
        require(normalized.isNotBlank()) { "缺少周次" }
        val result = sortedSetOf<Int>()
        for (part in normalized.split(Regex("[,;；]"))) {
            if (part.isBlank()) continue
            val match = Regex("^\\s*(?:第\\s*)?(\\d+)(?:\\s*-\\s*(\\d+))?\\s*(?:周)?\\s*(?:[（(]?([单双])[）)]?)?\\s*$")
                .matchEntire(part) ?: throw IllegalArgumentException("无法识别周次：$part")
            val first = match.groupValues[1].toInt()
            val last = match.groupValues[2].toIntOrNull() ?: first
            require(first in 1..60 && last in first..60) { "周次超出 1–60 范围" }
            val parity = match.groupValues[3]
            result += (first..last).filter {
                parity.isBlank() || (parity == "单" && it % 2 == 1) || (parity == "双" && it % 2 == 0)
            }
        }
        require(result.isNotEmpty()) { "没有有效周次" }
        return result.toList()
    }

    fun parseSectionsStrict(text: String): List<Int> {
        val result = sortedSetOf<Int>()
        val parts = text
            .replace("第", "")
            .replace("节", "")
            .replace('，', ',')
            .replace('、', ',')
            .replace('–', '-')
            .split(',')
        for (part in parts) {
            val match = Regex("^\\s*(\\d+)(?:\\s*-\\s*(\\d+))?\\s*$").matchEntire(part)
                ?: throw IllegalArgumentException("无法识别节次：$part")
            val first = match.groupValues[1].toInt()
            val last = match.groupValues[2].toIntOrNull() ?: first
            require(first in 1..MAX_PERIOD_ROWS && last in first..MAX_PERIOD_ROWS) { "节次超出 1–20 范围" }
            result += first..last
        }
        require(result.isNotEmpty()) { "没有有效节次" }
        return result.toList()
    }

    fun parseWeekdays(text: String): List<Int> {
        val normalized = text.trim().replace("星期", "").replace("周", "").replace("礼拜", "")
        if (normalized == "末" || normalized == "六日") return listOf(6, 7)
        val map = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)
        fun day(c: Char): Int? {
            val value = map[c] ?: c.digitToIntOrNull() ?: return null
            return (if (value == 0) 7 else value).takeIf { it in 1..7 }
        }
        val range = Regex("^([1-7一二三四五六日天])[-至到~]([1-7一二三四五六日天])$").matchEntire(normalized)
        if (range != null) {
            val start = day(range.groupValues[1][0])!!
            val end = day(range.groupValues[2][0])!!
            require(end >= start)
            return (start..end).toList()
        }
        require(normalized.all { it in map.keys || it in '0'..'7' || it in "、,，/ ／;；和 " }) { "星期格式无法识别" }
        return normalized.mapNotNull(::day).distinct().sorted()
    }

    fun splitIntoContiguousRanges(sections: List<Int>): List<Pair<Int, Int>> {
        val sorted = sections.distinct().sorted()
        if (sorted.isEmpty()) return emptyList()
        val ranges = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index < sorted.size) {
            val first = sorted[index]
            var last = first
            while (index + 1 < sorted.size && sorted[index + 1] == last + 1) {
                index++
                last = sorted[index]
            }
            ranges += first to last
            index++
        }
        return ranges
    }
}
