package cn.edu.haut.campus.core.protocol.kingosoft

import cn.edu.haut.campus.core.model.json.JsonValue

/**
 * UNVERIFIED：青果教务页面形态的极简 HTML 读取工具，只用 JDK 标准库。
 *
 * 青果是 ASP.NET 部署形态：登录与查询都靠"表单 + 隐藏域 + 下拉框"回填，
 * 课表是"节次为行、星期为列"的网格表格。这里只实现这三件事，
 * 不支持嵌套表格与 JavaScript 渲染；识别不了就返回空结果，绝不填充猜测值。
 */
internal object KingosoftHtml {

    data class Table(val id: String?, val header: List<String>, val rows: List<List<String>>)

    data class Option(val value: String, val label: String, val selected: Boolean)

    private data class Tag(
        val name: String,
        val closing: Boolean,
        val start: Int,
        val end: Int,
        val raw: String
    )

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

    /** 取第一个 form 的 action；ASP.NET 的 PostBack 目标有时带查询串，必须原样保留。 */
    fun formAction(html: String): String? =
        scanTags(html).firstOrNull { it.name == "form" && !it.closing }
            ?.let { attr(it.raw, "action") }
            ?.takeIf { it.isNotBlank() }

    /** 按 name 或 id 定位下拉框，返回全部选项并保留 selected 标记。 */
    fun options(html: String, selectName: String): List<Option> {
        val result = ArrayList<Option>()
        val tags = scanTags(html)
        var inside = false
        var pending: Tag? = null
        for (tag in tags) {
            if (tag.name == "select") {
                if (!tag.closing) {
                    if (inside) continue
                    val name = attr(tag.raw, "name")
                    val id = attr(tag.raw, "id")
                    if (name == selectName || id == selectName) inside = true
                } else if (inside) {
                    break
                }
                continue
            }
            if (!inside || tag.name != "option") continue
            if (!tag.closing) {
                pending = tag
                continue
            }
            val open = pending ?: continue
            val label = text(html.substring(open.end, tag.start))
            val value = attr(open.raw, "value")?.takeIf { it.isNotBlank() } ?: label
            result += Option(value = value, label = label.ifBlank { value }, selected = isSelected(open.raw))
            pending = null
        }
        return result
    }

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

    fun looksLikeLoginPage(html: String): Boolean =
        html.contains("type=\"password\"", ignoreCase = true)

    fun row(header: List<String>, cells: List<String>): JsonValue.Obj {
        val fields = LinkedHashMap<String, JsonValue>()
        cells.forEachIndexed { index, cell ->
            val key = header.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "column" + index
            fields[key] = JsonValue.Str(cell)
        }
        return JsonValue.Obj(fields)
    }

    private fun isSelected(raw: String): Boolean {
        val lower = raw.lowercase()
        val at = lower.indexOf("selected")
        if (at < 0) return false
        var cursor = at + "selected".length
        while (cursor < lower.length && lower[cursor].isWhitespace()) cursor++
        return cursor >= lower.length || lower[cursor] != '='
    }

    private fun parseTable(id: String?, inner: String): Table {
        val rows = ArrayList<List<String>>()
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
                        rows += created
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
                        current += if (openTag == null) "" else text(inner.substring(openTag.end, tag.start))
                        cellOpen = null
                    }
                }
            }
        }
        val headerIndex = headerFlags.indexOfFirst { it }
        return Table(
            id = id,
            header = if (headerIndex < 0) emptyList() else rows[headerIndex],
            rows = rows.filterIndexed { index, cells -> !headerFlags[index] && cells.isNotEmpty() }
        )
    }

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
