package cn.edu.haut.campus.core.protocol.qz

import cn.edu.haut.campus.core.protocol.RemoteExam

/**
 * UNVERIFIED（未验证）：成绩/考试这类"一行一条记录"的页面按公开 jsxsd 表头写法推导，
 * 未在任何真实学校站点核对过全部列名。
 *
 * 关键点是**按表头文字定位列**，而不是按列号：老系统不同院系、不同年份的导出列顺序并不一致，
 * 写死列号会在真实数据上静默错位。
 */
internal class ColumnTable(val header: List<String>, val rows: List<List<String>>) {

    /** 按别名返回第一个命中的列号；表头被改写时返回 null，由调用方决定降级策略。 */
    fun indexOf(vararg aliases: String): Int? {
        val normalized = header.map { normalize(it) }
        for (alias in aliases) {
            val target = normalize(alias)
            if (target.isEmpty()) continue
            val index = normalized.indexOf(target)
            if (index >= 0) return index
        }
        return null
    }

    /** 表头里是否包含给定的任一别名。 */
    fun hasAny(vararg aliases: String): Boolean = indexOf(*aliases) != null

    fun value(row: List<String>, index: Int?): String? =
        index?.let { row.getOrNull(it)?.trim()?.ifEmpty { null } }

    companion object {
        fun normalize(raw: String): String =
            raw.replace(" ", "").replace('\u3000', ' ').replace("：", "").replace(":", "").trim().lowercase()
    }
}

internal object QzTables {

    /** 把页面里每张表转成"表头 + 数据行"；标题行、空行、单列表格都会被跳过。 */
    fun parse(html: String): List<ColumnTable> = QzHtml.tables(html).mapNotNull { toColumnTable(it) }

    private fun toColumnTable(table: HtmlTable): ColumnTable? {
        if (table.rowCount < 2) return null
        val width = table.columnCount
        if (width < 2) return null
        fun rowValues(row: Int): List<String> =
            (0 until width).map { column -> table.textAt(row, column).orEmpty().trim() }

        val headerIndex = (0 until table.rowCount).firstOrNull { row ->
            rowValues(row).count { it.isNotEmpty() } >= 2
        } ?: return null
        val header = rowValues(headerIndex)
        val rows = (headerIndex + 1 until table.rowCount)
            .map { row -> rowValues(row) }
            .filter { values -> values.any { it.isNotEmpty() } }
        return ColumnTable(header, rows)
    }
}

/**
 * 分页器解析：老系统的"共 N 页"既可能写成文字，也可能只体现在页码链接里。
 * 只从页码链接里取最大值，取不到就认为是单页，绝不臆造页数。
 */
internal object QzPager {

    fun totalPages(html: String): Int {
        var max = 1
        for (link in QzHtml.links(html)) {
            val page = QzQuery.parameters(link.href)["page"]?.toIntOrNull()
            if (page != null && page > max && page <= MAX_PAGES) max = page
            val text = PAGE_TEXT.find(link.text)?.groupValues?.get(1)?.toIntOrNull()
            if (text != null && text > max && text <= MAX_PAGES) max = text
        }
        return max
    }

    private const val MAX_PAGES = 500
    private val PAGE_TEXT = Regex("""^(\d{1,3})$""")
}
/** 生成页内唯一的来源记录 ID：同一业务键重复出现时追加序号，避免合同要求的 ID 重复。 */
internal class QzIdentity {

    private val counters = HashMap<String, Int>()

    fun of(base: String): String {
        val key = base.ifBlank { "unknown" }
        val count = (counters[key] ?: 0) + 1
        counters[key] = count
        return if (count == 1) key else key + "#" + count
    }
}
/**
 * 考试安排表解析：按表头别名定位列，缺列不报错（返回 null 字段），
 * 只要求"课程名"存在，否则认为不是考试页面。
 */
internal object QzExamTable {

    fun parse(html: String): List<RemoteExam>? {
        val table = QzTables.parse(html).firstOrNull { it.hasAny("课程名称", "课程", "科目", "考试科目") } ?: return null
        val courseColumn = table.indexOf("课程名称", "课程", "科目", "考试科目")
        val dateColumn = table.indexOf("考试日期", "日期")
        val timeColumn = table.indexOf("考试时间", "时间")
        val locationColumn = table.indexOf("考场", "考试地点", "地点", "教室")
        val seatColumn = table.indexOf("座位号", "座位")
        val kindColumn = table.indexOf("考试方式", "考核方式", "形式")
        val identity = QzIdentity()
        val exams = ArrayList<cn.edu.haut.campus.core.protocol.RemoteExam>()
        for (row in table.rows) {
            val course = table.value(row, courseColumn)?.takeUnless { it == "合计" } ?: continue
            val date = QzScheduleText.parseDate(table.value(row, dateColumn))
                ?: QzScheduleText.parseDate(table.value(row, timeColumn))
            val range = QzScheduleText.parseTimeRange(table.value(row, timeColumn))
            exams += RemoteExam(
                sourceId = identity.of("qz-exam:" + course + "@" + (date?.toString() ?: "unknown")),
                courseName = course,
                date = date,
                startTime = range?.first,
                endTime = range?.second,
                location = table.value(row, locationColumn),
                seat = table.value(row, seatColumn),
                kind = table.value(row, kindColumn)
            )
        }
        return exams
    }
}