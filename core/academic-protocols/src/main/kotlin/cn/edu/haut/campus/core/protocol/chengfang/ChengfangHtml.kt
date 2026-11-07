package cn.edu.haut.campus.core.protocol.chengfang

import cn.edu.haut.campus.core.model.json.JsonValue

/**
 * UNVERIFIED：乘方教务页面形态的极简 HTML 读取工具，只用 JDK 标准库。
 *
 * 只覆盖适配器真正需要的三种结构：表单隐藏域、带表头的表格、分页文本。
 * 它不是通用 HTML 解析器：不做 CSS 选择器、不支持嵌套表格的数据抽取，
 * 页面残缺时宁可抛错或返回空集合，也不猜测缺失字段。
 */
internal object ChengfangHtml {

    /** 一张表格：表头行与数据行都按单元格纯文本保存，顺序与页面一致。 */
    data class Table(
        val id: String?,
        val header: List<String>,
        val rows: List<List<String>>
    ) {
        fun column(name: String): Int = header.indexOfFirst { it == name }

        /** 按表头名取值；表头里没有该列时返回 null，绝不按位置硬猜。 */
        fun cell(row: List<String>, name: String): String? {
            val index = column(name)
            if (index < 0) return null
            return row.getOrNull(index)?.takeIf { it.isNotBlank() }
        }
    }

    /** 分页条：三个数字都可能缺失，缺失即"页面没有声明该信息"。 */
    data class Pager(val current: Int?, val totalPages: Int?, val total: Int?)

    private data class Tag(
        val name: String,
        val closing: Boolean,
        val start: Int,
        val end: Int,
        val raw: String
    )

    /** 提取 `<input type="hidden">` 的 name→value，用于登录与查询表单回填。 */
    fun hiddenFields(html: String): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        scanTags(html).forEach { tag ->
            if (tag.closing || tag.name != "input") return@forEach
            if (attr(tag.raw, "type")?.lowercase() != "hidden") return@forEach
            val name = attr(tag.raw, "name")?.takeIf { it.isNotBlank() } ?: return@forEach
            fields[name] = attr(tag.raw, "value").orEmpty()
        }
        return fields
    }

    /** 判断表单中是否存在指定 name 或 id 的 `<input>`（例如验证码输入框）。 */
    fun hasInput(html: String, fieldName: String): Boolean =
        scanTags(html).any { tag ->
            !tag.closing && tag.name == "input" && (
                attr(tag.raw, "name")?.equals(fieldName, ignoreCase = true) == true ||
                    attr(tag.raw, "id")?.equals(fieldName, ignoreCase = true) == true
                )
        }

    /** 按文档顺序返回全部表格；嵌套表格的内部行不会混进外层表格。 */
    fun tables(html: String): List<Table> {
        val result = ArrayList<Table>()
        var depth = 0
        var open: Tag? = null
        for (tag in scanTags(html)) {
            if (tag.name != "table") continue
            if (!tag.closing) {
                if (depth == 0) open = tag
                depth++
                continue
            }
            if (depth == 0) continue
            depth--
            if (depth == 0) {
                val start = open
                if (start != null) {
                    result += parseTable(attr(start.raw, "id"), html.substring(start.end, tag.start))
                    open = null
                }
            }
        }
        return result
    }

    /** 解析"共 N 条记录，第 X/Y 页"这类分页文本；识别不到时三个字段全为 null。 */
    fun pager(html: String): Pager {
        val total = Regex("共\\s*(\\d+)\\s*条").find(html)?.groupValues?.get(1)?.toIntOrNull()
        val pages = Regex("第\\s*(\\d+)\\s*/\\s*(\\d+)\\s*页").find(html)
        return Pager(
            current = pages?.groupValues?.get(1)?.toIntOrNull(),
            totalPages = pages?.groupValues?.get(2)?.toIntOrNull(),
            total = total
        )
    }

    /** 会话失效的页面证据：仍然出现密码输入框，说明被踢回了登录页。 */
    fun looksLikeLoginPage(html: String): Boolean =
        scanTags(html).any { !it.closing && it.name == "input" && attr(it.raw, "type").equals("password", ignoreCase = true) }

    /** 把一行表格转换成 JSON 对象，键取表头名，便于复用分页去重与规范化。 */
    fun row(header: List<String>, cells: List<String>): JsonValue.Obj {
        val fields = LinkedHashMap<String, JsonValue>()
        cells.forEachIndexed { index, cell ->
            val key = header.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "column" + index
            fields[key] = JsonValue.Str(cell)
        }
        return JsonValue.Obj(fields)
    }

    private fun parseTable(id: String?, inner: String): Table {
        val allRows = ArrayList<List<String>>()
        val headerFlags = ArrayList<Boolean>()
        var nested = 0
        var row: MutableList<String>? = null
        var cellOpen: Tag? = null
        for (tag in scanTags(inner)) {
            if (tag.name == "table") {
                if (tag.closing) nested-- else nested++
                continue
            }
            if (nested > 0) continue
            when (tag.name) {
                "tr" -> {
                    if (tag.closing) {
                        row = null
                    } else {
                        val created = ArrayList<String>()
                        allRows += created
                        headerFlags += true
                        row = created
                    }
                }
                "td", "th" -> {
                    val current = row ?: continue
                    if (!tag.closing) {
                        cellOpen = tag
                        if (tag.name == "td") headerFlags[headerFlags.lastIndex] = false
                    } else {
                        val openTag = cellOpen
                        val text = if (openTag == null) "" else text(inner.substring(openTag.end, tag.start))
                        current += text
                        cellOpen = null
                    }
                }
            }
        }
        return Table(id = id, header = headerOf(allRows, headerFlags), rows = dataRows(allRows, headerFlags))
    }

    private fun headerOf(rows: List<List<String>>, flags: List<Boolean>): List<String> {
        val index = flags.indexOfFirst { it }
        return if (index < 0) emptyList() else rows[index]
    }

    private fun dataRows(rows: List<List<String>>, flags: List<Boolean>): List<List<String>> =
        rows.filterIndexed { index, cells -> !flags[index] && cells.isNotEmpty() }

    private fun text(fragment: String): String {
        val builder = StringBuilder()
        var index = 0
        while (index < fragment.length) {
            val ch = fragment[index]
            if (ch != '<') {
                builder.append(ch)
                index++
                continue
            }
            val close = fragment.indexOf('>', index)
            if (close < 0) break
            if (tagName(fragment.substring(index, close + 1)) == "br") builder.append('\n')
            index = close + 1
        }
        return decodeEntities(builder.toString())
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun decodeEntities(raw: String): String {
        var value = raw
        value = value.replace("&nbsp;", " ")
        value = value.replace("&lt;", "<")
        value = value.replace("&gt;", ">")
        value = value.replace("&quot;", "\"")
        value = value.replace("&#39;", "'")
        value = Regex("&#(x?)([0-9A-Fa-f]+);").replace(value) { match ->
            val radix = if (match.groupValues[1].isEmpty()) 10 else 16
            val code = match.groupValues[2].toIntOrNull(radix)
            if (code == null || code < 0 || code > 0xFFFF) match.value else code.toChar().toString()
        }
        return value.replace("&amp;", "&")
    }

    private fun scanTags(html: String): List<Tag> {
        val tags = ArrayList<Tag>()
        var index = 0
        while (index < html.length) {
            val open = html.indexOf('<', index)
            if (open < 0) break
            if (html.startsWith("<!--", open)) {
                val end = html.indexOf("-->", open + 4)
                if (end < 0) break
                index = end + 3
                continue
            }
            val close = html.indexOf('>', open + 1)
            if (close < 0) break
            val raw = html.substring(open, close + 1)
            val name = tagName(raw)
            if (name.isEmpty()) {
                index = close + 1
                continue
            }
            val closing = raw.startsWith("</")
            if (!closing && (name == "script" || name == "style")) {
                val end = html.indexOf("</" + name, close + 1)
                if (end < 0) break
                val endClose = html.indexOf('>', end)
                index = if (endClose < 0) html.length else endClose + 1
                continue
            }
            tags += Tag(name, closing, open, close + 1, raw)
            index = close + 1
        }
        return tags
    }

    private fun tagName(raw: String): String {
        var index = 1
        if (index < raw.length && raw[index] == '/') index++
        val builder = StringBuilder()
        while (index < raw.length) {
            val ch = raw[index]
            if (!ch.isLetterOrDigit() && ch != '-' && ch != ':') break
            builder.append(ch.lowercaseChar())
            index++
        }
        return builder.toString()
    }

    /** 只按"属性名前必须是空白"判定，避免把 data-name、aria-label 误当成本属性。 */
    private fun attr(raw: String, name: String): String? {
        val lower = raw.lowercase()
        val target = name.lowercase()
        var index = 0
        while (index < lower.length) {
            val at = lower.indexOf(target, index)
            if (at < 0) return null
            index = at + target.length
            val before = if (at == 0) ' ' else lower[at - 1]
            if (!before.isWhitespace()) continue
            var cursor = at + target.length
            while (cursor < lower.length && lower[cursor].isWhitespace()) cursor++
            if (cursor >= lower.length || lower[cursor] != '=') continue
            cursor++
            while (cursor < lower.length && lower[cursor].isWhitespace()) cursor++
            if (cursor >= lower.length) return null
            val quote = raw[cursor]
            if (quote == '"' || quote == '\'') {
                val end = raw.indexOf(quote, cursor + 1)
                return if (end < 0) null else raw.substring(cursor + 1, end)
            }
            var end = cursor
            while (end < raw.length && !raw[end].isWhitespace() && raw[end] != '>' && raw[end] != '/') end++
            return raw.substring(cursor, end)
        }
        return null
    }
}
