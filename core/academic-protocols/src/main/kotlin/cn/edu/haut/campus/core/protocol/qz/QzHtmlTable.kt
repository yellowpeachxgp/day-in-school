package cn.edu.haut.campus.core.protocol.qz

/**
 * UNVERIFIED（未验证）：
 * 本包的 HTML 解析只依据公开 jsxsd 页面形态推导，从未在任何真实学校站点上运行验证；
 * 这里既不代表任何学校的真实页面结构，也不构成"某校可用"的结论。
 *
 * 设计约束：
 * 1. 只使用 JDK 标准库，不引入任何 HTML 解析第三方依赖；
 * 2. 解析器是**宽容**的：缺失闭合标签、嵌套表格、属性顺序任意都不应崩溃；
 * 3. rowspan/colspan 必须展开，因为课表/成绩表普遍用合并单元格表达跨节次与跨行学期。
 */

/** 一个已展开到网格里的单元格；rowspan/colspan 覆盖到的位置会引用同一个实例。 */
internal class HtmlCell(val text: String, val rowSpan: Int, val colSpan: Int) {
    override fun toString(): String = "HtmlCell(" + rowSpan + "x" + colSpan + ", " + text.length + "字)"
}

/**
 * 解析后的表格。同时保留"源顺序行"与"展开网格"两种视图：
 * 源顺序行适合逐行取值，展开网格适合计算合并区域（例如第 3 行第 2 列起跨 2 节的课）。
 */
internal class HtmlTable internal constructor(
    val rows: List<List<HtmlCell>>,
    private val gridCells: List<List<HtmlCell?>>,
    private val gridOrigins: List<List<Boolean>>
) {
    val rowCount: Int get() = gridCells.size
    val columnCount: Int get() = gridCells.maxOfOrNull { it.size } ?: 0

    /** 展开网格取值：被合并单元格覆盖的位置返回**起源**单元格，未覆盖返回 null。 */
    fun cellAt(row: Int, column: Int): HtmlCell? = gridCells.getOrNull(row)?.getOrNull(column)

    fun textAt(row: Int, column: Int): String? = cellAt(row, column)?.text

    /** 该位置是否是某个合并单元格的左上角起点。 */
    fun isOrigin(row: Int, column: Int): Boolean = gridOrigins.getOrNull(row)?.getOrNull(column) == true

    /** 遍历所有合并单元格的起点（行, 列, 单元格）。 */
    fun origins(): List<HtmlOrigin> = buildList {
        for (row in 0 until rowCount) {
            val width = gridCells.getOrNull(row)?.size ?: 0
            for (column in 0 until width) {
                if (isOrigin(row, column)) {
                    val cell = cellAt(row, column) ?: continue
                    add(HtmlOrigin(row, column, cell))
                }
            }
        }
    }
}

internal class HtmlOrigin(val row: Int, val column: Int, val cell: HtmlCell)

internal object QzHtml {

    /** 提取页面中所有（外层）表格；嵌套表格的文本归入外层单元格，不参与结构展开。 */
    fun tables(html: String): List<HtmlTable> {
        val result = ArrayList<HtmlTable>()
        var cursor = 0
        while (cursor < html.length) {
            val open = nextTag(html, cursor) ?: break
            if (open.close || open.name != "table") {
                cursor = open.end
                continue
            }
            val close = matchingClose(html, open.end, "table")
            val bodyEnd = close?.start ?: html.length
            result += parseTable(html.substring(open.end, bodyEnd))
            cursor = close?.end ?: html.length
        }
        return result
    }

    /** 去标签取文本：块级边界（br/p/div/tr）转成换行，压缩空白并丢弃空行。 */
    fun stripTags(html: String): String {
        val sb = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                sb.append(html, i, html.length)
                break
            }
            sb.append(html, i, lt)
            i = skipTag(html, lt, sb) ?: run {
                sb.append(html, lt, html.length)
                html.length
            }
        }
        return normalizeLines(decodeEntities(sb.toString()))
    }

    fun singleLine(html: String): String = stripTags(html).replace('\n', ' ').trim()

    fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch != '&') {
                sb.append(ch)
                i++
                continue
            }
            val semi = text.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                sb.append(ch)
                i++
                continue
            }
            val decoded = decodeEntity(text.substring(i + 1, semi))
            if (decoded == null) {
                sb.append(ch)
                i++
            } else {
                sb.append(decoded)
                i = semi + 1
            }
        }
        return sb.toString()
    }

    /** 解析标签属性（小写键名）；容忍无引号、单引号与多余空白。 */
    fun attributes(tagRaw: String): Map<String, String> {
        val body = tagRaw.trim().removePrefix("<").removeSuffix(">")
        if ('=' !in body) return emptyMap()
        val result = LinkedHashMap<String, String>()
        ATTRIBUTE.findAll(body).forEach { match ->
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2].ifEmpty {
                match.groupValues[3].ifEmpty { match.groupValues[4] }
            }
            result.putIfAbsent(key, decodeEntities(value))
        }
        return result
    }

    /** 提取 <a href> 链接：返回（href 原值, 链接文本）序列。 */
    fun links(html: String): List<HtmlLink> {
        val result = ArrayList<HtmlLink>()
        var cursor = 0
        while (cursor < html.length) {
            val open = nextTag(html, cursor) ?: break
            cursor = open.end
            if (open.close || open.name != "a") continue
            val close = matchingClose(html, open.end, "a") ?: continue
            val href = attributes(open.raw)["href"].orEmpty()
            val text = singleLine(html.substring(open.end, close.start))
            result += HtmlLink(href, text)
            cursor = close.end
        }
        return result
    }

    /** 解析 <select name="…"> 的 option 列表（值, 文本）；找不到该 select 时返回空表。 */
    fun selectOptions(html: String, selectName: String): List<HtmlOption> {
        var cursor = 0
        while (cursor < html.length) {
            val open = nextTag(html, cursor) ?: break
            cursor = open.end
            if (open.close || open.name != "select") continue
            val close = matchingClose(html, open.end, "select") ?: continue
            val attrs = attributes(open.raw)
            val matched = attrs["name"] == selectName || attrs["id"] == selectName
            cursor = close.end
            if (!matched) continue
            val body = html.substring(open.end, close.start)
            val options = ArrayList<HtmlOption>()
            var optionCursor = 0
            while (optionCursor < body.length) {
                val tag = nextTag(body, optionCursor) ?: break
                optionCursor = tag.end
                if (tag.close || tag.name != "option") continue
                val optionClose = matchingClose(body, tag.end, "option") ?: continue
                val optionAttrs = attributes(tag.raw)
                val text = singleLine(body.substring(tag.end, optionClose.start))
                val value = optionAttrs["value"] ?: text
                options += HtmlOption(value.trim(), text, "selected" in optionAttrs)
                optionCursor = optionClose.end
            }
            return options
        }
        return emptyList()
    }

    /** 所有 <input> 的属性集合（键名小写），登录表单与隐藏字段回填都依赖它。 */
    fun inputs(html: String): List<Map<String, String>> {
        val result = ArrayList<Map<String, String>>()
        var cursor = 0
        while (cursor < html.length) {
            val tag = nextTag(html, cursor) ?: break
            cursor = tag.end
            if (tag.close || tag.name != "input") continue
            result += attributes(tag.raw)
        }
        return result
    }

    /** 表单字段：name → value，供登录与查询表单回填。 */
    fun formFields(html: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        inputs(html).forEach { attrs ->
            val name = attrs["name"]?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            result.putIfAbsent(name, attrs["value"].orEmpty())
        }
        return result
    }

    /** 第一个 <form> 的 action 原值（可能为空或相对路径）。 */
    fun formAction(html: String): String? {
        var cursor = 0
        while (cursor < html.length) {
            val tag = nextTag(html, cursor) ?: return null
            cursor = tag.end
            if (tag.close || tag.name != "form") continue
            return attributes(tag.raw)["action"]
        }
        return null
    }

    private class HtmlTag(
        val name: String,
        val close: Boolean,
        val start: Int,
        val end: Int,
        val raw: String
    )

    private val ATTRIBUTE = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")

    private val NAMED_ENTITIES = mapOf(
        "nbsp" to " ",
        "ensp" to " ",
        "emsp" to " ",
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "ldquo" to "“",
        "rdquo" to "”",
        "lsquo" to "‘",
        "rsquo" to "’",
        "hellip" to "…",
        "middot" to "·",
        "times" to "×",
        "divide" to "÷"
    )

    private fun decodeEntity(raw: String): String? {
        if (raw.isEmpty()) return null
        if (raw.startsWith("#x") || raw.startsWith("#X")) {
            val code = raw.substring(2).toIntOrNull(16) ?: return null
            return if (code in 0..0x10FFFF && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else null
        }
        if (raw.startsWith("#")) {
            val code = raw.substring(1).toIntOrNull() ?: return null
            return if (code in 0..0x10FFFF && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else null
        }
        return NAMED_ENTITIES[raw.lowercase()]
    }

    private fun normalizeLines(text: String): String {
        if ('\n' !in text) return collapseHorizontal(text).trim()
        return text.split('\n')
            .map { collapseHorizontal(it).trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun collapseHorizontal(text: String): String {
        val sb = StringBuilder(text.length)
        var previousSpace = false
        for (ch in text) {
            val space = ch == ' ' || ch == '\t' || ch == '\u00a0' || ch == '\r'
            if (space) {
                if (!previousSpace) sb.append(' ')
            } else {
                sb.append(ch)
            }
            previousSpace = space
        }
        return sb.toString()
    }

    /** 跳过从 lt 开始的标签；返回下一个位置的游标。块级标签先向 out 追加换行。 */
    private fun skipTag(html: String, lt: Int, out: StringBuilder): Int? {
        if (html.startsWith("<!--", lt)) {
            val end = html.indexOf("-->", lt + 4)
            return if (end < 0) null else end + 3
        }
        val end = indexOfTagEnd(html, lt + 1)
        if (end < 0) return null
        val head = html.substring(lt + 1, minOf(end, lt + 12)).trim().lowercase()
        if (head.startsWith("br") || head.startsWith("p") && !head.startsWith("pre") ||
            head.startsWith("/p") || head.startsWith("div") || head.startsWith("/div") ||
            head.startsWith("/tr") || head.startsWith("li") || head.startsWith("/li")
        ) {
            out.append('\n')
        }
        return end + 1
    }

    private fun parseTable(body: String): HtmlTable {
        val rows = ArrayList<MutableList<HtmlCell>>()
        var current: MutableList<HtmlCell>? = null
        var cellStart = -1
        var cellTag = ""
        var cursor = 0

        fun flushCell(end: Int) {
            if (cellStart < 0) return
            val text = stripTags(body.substring(cellStart, end))
            val attrs = attributes(cellTag)
            current?.add(
                HtmlCell(
                    text = text,
                    rowSpan = span(attrs["rowspan"]),
                    colSpan = span(attrs["colspan"])
                )
            )
            cellStart = -1
        }

        while (true) {
            val tag = nextTag(body, cursor) ?: break
            cursor = tag.end
            when (tag.name) {
                "table" -> if (!tag.close) {
                    val close = matchingClose(body, cursor, "table")
                    if (close != null) cursor = close.end
                }
                "tr" -> {
                    flushCell(tag.start)
                    if (tag.close) {
                        current?.let { rows += it }
                        current = null
                    } else {
                        current?.let { rows += it }
                        current = ArrayList()
                    }
                }
                "td", "th" -> {
                    flushCell(tag.start)
                    if (!tag.close) {
                        cellStart = tag.end
                        cellTag = tag.raw
                    }
                }
                else -> Unit
            }
        }
        flushCell(body.length)
        current?.let { rows += it }

        val (cells, origins) = expand(rows)
        return HtmlTable(rows.map { it.toList() }, cells, origins)
    }

    private fun expand(rows: List<List<HtmlCell>>): Pair<List<List<HtmlCell?>>, List<List<Boolean>>> {
        val cells = ArrayList<MutableList<HtmlCell?>>()
        val origins = ArrayList<MutableList<Boolean>>()

        fun ensure(row: Int) {
            while (cells.size <= row) {
                cells += ArrayList<HtmlCell?>()
                origins += ArrayList<Boolean>()
            }
        }

        rows.forEachIndexed { rowIndex, row ->
            ensure(rowIndex)
            var column = 0
            for (cell in row) {
                ensure(rowIndex)
                while (cells[rowIndex].getOrNull(column) != null) column++
                for (dr in 0 until cell.rowSpan) {
                    val target = rowIndex + dr
                    ensure(target)
                    for (dc in 0 until cell.colSpan) {
                        val slot = column + dc
                        while (cells[target].size <= slot) {
                            cells[target] += null
                            origins[target] += false
                        }
                        cells[target][slot] = cell
                        origins[target][slot] = dr == 0 && dc == 0
                    }
                }
                column += cell.colSpan
            }
        }
        return cells.map { it.toList() } to origins.map { it.toList() }
    }

    private fun span(raw: String?): Int =
        raw?.trim()?.toIntOrNull()?.coerceIn(1, 200) ?: 1

    private fun nextTag(html: String, from: Int): HtmlTag? {
        var i = from
        while (true) {
            val lt = html.indexOf('<', i)
            if (lt < 0) return null
            if (html.startsWith("<!--", lt)) {
                val end = html.indexOf("-->", lt + 4)
                if (end < 0) return null
                i = end + 3
                continue
            }
            val next = html.getOrNull(lt + 1)
            if (next == '!' || next == '?') {
                val end = indexOfTagEnd(html, lt + 1)
                if (end < 0) return null
                i = end + 1
                continue
            }
            val close = next == '/'
            var j = lt + if (close) 2 else 1
            val nameStart = j
            while (j < html.length && (html[j].isLetterOrDigit() || html[j] == '-')) j++
            val name = html.substring(nameStart, j).lowercase()
            val end = indexOfTagEnd(html, j)
            if (end < 0) return null
            if (name.isEmpty()) {
                i = lt + 1
                continue
            }
            return HtmlTag(name, close, lt, end + 1, html.substring(lt, end + 1))
        }
    }

    private fun matchingClose(html: String, from: Int, name: String): HtmlTag? {
        var depth = 1
        var cursor = from
        while (true) {
            val tag = nextTag(html, cursor) ?: return null
            cursor = tag.end
            if (tag.name != name) continue
            if (tag.close) {
                depth--
                if (depth == 0) return tag
            } else {
                depth++
            }
        }
    }

    private fun indexOfTagEnd(html: String, from: Int): Int {
        var quote = 0.toChar()
        var i = from
        while (i < html.length) {
            val ch = html[i]
            when {
                quote != 0.toChar() -> if (ch == quote) quote = 0.toChar()
                ch == '"' || ch == '\'' -> quote = ch
                ch == '>' -> return i
            }
            i++
        }
        return -1
    }
}

internal class HtmlLink(val href: String, val text: String)

internal class HtmlOption(val value: String, val text: String, val selected: Boolean)
