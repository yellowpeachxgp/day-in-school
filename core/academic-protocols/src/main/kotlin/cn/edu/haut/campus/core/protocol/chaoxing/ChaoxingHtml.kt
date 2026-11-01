package cn.edu.haut.campus.core.protocol.chaoxing

import cn.edu.haut.campus.core.model.json.JsonValue

/**
 * UNVERIFIED：超星智慧教务页面形态的极简 HTML 读取工具，只用 JDK 标准库。
 *
 * 只抽取表单隐藏域、表格与分页文本三类结构。超星教务页面里还混有大量前端脚本，
 * 这里一律跳过 script/style；识别不了就返回空集合，不猜测缺失字段。
 */
internal object ChaoxingHtml {

    data class Table(val id: String?, val header: List<String>, val rows: List<List<String>>) {
        fun cell(row: List<String>, name: String): String? {
            val index = header.indexOfFirst { it == name }
            if (index < 0) return null
            return row.getOrNull(index)?.takeIf { it.isNotBlank() }
        }
    }

    data class Pager(val current: Int?, val pageSize: Int?)

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

    fun formAction(html: String): String? =
        scanTags(html).firstOrNull { it.name == "form" && !it.closing }
            ?.let { attr(it.raw, "action") }
            ?.takeIf { it.isNotBlank() }

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

    /** "每页 2 条，第 1 页"这类分页条；缺失的字段保持 null。 */
    fun pager(html: String): Pager = Pager(
        current = Regex("第\\s*(\\d+)\\s*页").find(html)?.groupValues?.get(1)?.toIntOrNull(),
        pageSize = Regex("每页\\s*(\\d+)\\s*条").find(html)?.groupValues?.get(1)?.toIntOrNull()
    )

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
