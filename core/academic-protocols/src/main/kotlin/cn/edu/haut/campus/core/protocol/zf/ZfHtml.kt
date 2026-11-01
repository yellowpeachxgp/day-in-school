package cn.edu.haut.campus.core.protocol.zf

import java.util.Locale

/** 一个可点击链接：href 原样保存，链接文本用于识别菜单语义。 */
internal data class ZfLink(val href: String, val text: String)

/** 一个 option；value 缺省时退化为选项文本。 */
internal data class ZfOption(val value: String, val text: String, val selected: Boolean)

/**
 * 一个 form 的可提交状态：隐藏字段、下拉框选中值、提交按钮值。
 * 密码框的值永远不进入 fields，避免凭据被顺手写进日志或异常。
 */
internal class ZfForm(
    val action: String?,
    val method: String,
    val fields: Map<String, String>,
    val inputNames: Set<String>,
    val hasPassword: Boolean
) {
    val usernameField: String? = fieldOf(USERNAME_FIELDS)
    val passwordField: String? = fieldOf(PASSWORD_FIELDS)

    fun formBody(extra: Map<String, String> = emptyMap()): String {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(fields)
        merged.putAll(extra)
        return ZfUrls.formEncode(merged)
    }

    /** 凭据只在内存里拼进请求体；返回值包含密码，调用方不得记录或抛出。 */
    fun credentialBody(studentId: String, password: String, extra: Map<String, String> = emptyMap()): String {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(fields)
        merged[usernameField ?: "username"] = studentId
        merged[passwordField ?: "password"] = password
        merged.putAll(extra)
        return ZfUrls.formEncode(merged)
    }

    private fun fieldOf(candidates: List<String>): String? =
        inputNames.firstOrNull { name -> candidates.any { it == name.lowercase(Locale.ROOT) } }

    private companion object {
        val USERNAME_FIELDS = listOf("username", "textbox1", "userid", "account", "uname")
        val PASSWORD_FIELDS = listOf("password", "textbox2", "pwd", "passwd")
    }
}

/**
 * 最小 HTML 扫描器（UNVERIFIED）。
 *
 * 只按标签流处理，不构建 DOM：旧正方页面大量省略 </td>、</tr> 结束标签，
 * 严格树形解析会直接失败。这里只提供协议解析需要的四类提取：链接、表单、
 * 下拉框与表格。所有输入都是合成夹具或公开页面形态，未在真实学校核对。
 */
internal object ZfHtml {

    private val SPACES = Regex("""\s+""")
    private val VOID_TAGS = setOf("br", "hr", "img", "input", "meta", "link", "col", "area", "base")
    private val RAW_TEXT_TAGS = setOf("script", "style")

    /** 去掉标签后的可读文本，用于识别“验证码错误”“仅显示部分数据”这类提示。 */
    fun plainText(html: String): String {
        val builder = StringBuilder()
        tokenize(html).forEach { token ->
            when (token) {
                is ZfHtmlToken.Text -> builder.append(decodeEntities(token.raw))
                is ZfHtmlToken.StartTag -> if (token.name in BLOCK_TAGS) builder.append(' ')
                is ZfHtmlToken.EndTag -> if (token.name in BLOCK_TAGS) builder.append(' ')
            }
        }
        return builder.toString().replace(SPACES, " ").trim()
    }

    fun links(html: String): List<ZfLink> {
        val tokens = tokenize(html)
        val result = ArrayList<ZfLink>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token !is ZfHtmlToken.StartTag || token.name != "a") {
                index++
                continue
            }
            val href = token.attributes["href"].orEmpty()
            val text = StringBuilder()
            var cursor = index + 1
            var depth = 1
            while (cursor < tokens.size) {
                val inner = tokens[cursor]
                if (inner is ZfHtmlToken.StartTag && inner.name == "a") {
                    depth++
                } else if (inner is ZfHtmlToken.EndTag && inner.name == "a") {
                    depth--
                    if (depth == 0) {
                        cursor++
                        break
                    }
                } else if (inner is ZfHtmlToken.Text) {
                    text.append(inner.raw)
                } else if (inner is ZfHtmlToken.StartTag && inner.name == "br") {
                    text.append(' ')
                }
                cursor++
            }
            if (href.isNotBlank()) result += ZfLink(href = href.trim(), text = normalizeText(text.toString()))
            index = cursor
        }
        return result
    }

    fun forms(html: String): List<ZfForm> {
        val tokens = tokenize(html)
        val result = ArrayList<ZfForm>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token !is ZfHtmlToken.StartTag || token.name != "form") {
                index++
                continue
            }
            val action = token.attributes["action"]?.trim()?.takeIf { it.isNotEmpty() }
            val method = (token.attributes["method"] ?: "get").lowercase(Locale.ROOT)
            val fields = LinkedHashMap<String, String>()
            val inputNames = LinkedHashSet<String>()
            var hasPassword = false
            var cursor = index + 1
            while (cursor < tokens.size) {
                val inner = tokens[cursor]
                if (inner is ZfHtmlToken.EndTag && inner.name == "form") {
                    cursor++
                    break
                }
                if (inner is ZfHtmlToken.StartTag && inner.name == "form") break
                if (inner is ZfHtmlToken.StartTag && inner.name == "input") {
                    val type = (inner.attributes["type"] ?: "text").lowercase(Locale.ROOT)
                    val name = inner.attributes["name"]?.trim()?.takeIf { it.isNotEmpty() }
                    val value = decodeEntities(inner.attributes["value"].orEmpty())
                    if (name != null) {
                        inputNames += name
                        when (type) {
                            "password" -> hasPassword = true
                            "hidden", "submit", "button" -> fields[name] = value
                            "text", "search", "email", "number" -> if (value.isNotEmpty()) fields[name] = value
                        }
                    } else if (type == "password") {
                        hasPassword = true
                    }
                } else if (inner is ZfHtmlToken.StartTag && inner.name == "select") {
                    val select = readSelect(tokens, cursor)
                    val name = inner.attributes["name"]?.trim()?.takeIf { it.isNotEmpty() }
                    if (name != null) {
                        inputNames += name
                        val chosen = select.options.firstOrNull { it.selected } ?: select.options.firstOrNull()
                        if (chosen != null) fields[name] = chosen.value
                    }
                    cursor = select.nextIndex
                    continue
                }
                cursor++
            }
            result += ZfForm(action, method, fields, inputNames, hasPassword)
            index = cursor
        }
        return result
    }

    fun selects(html: String): Map<String, List<ZfOption>> {
        val tokens = tokenize(html)
        val result = LinkedHashMap<String, List<ZfOption>>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token is ZfHtmlToken.StartTag && token.name == "select") {
                val select = readSelect(tokens, index)
                val name = token.attributes["name"]?.trim()?.takeIf { it.isNotEmpty() }
                if (name != null && select.options.isNotEmpty()) result[name] = select.options
                index = select.nextIndex
                continue
            }
            index++
        }
        return result
    }

    fun tables(html: String): List<ZfHtmlTable> {
        val tokens = tokenize(html)
        val tables = ArrayList<ZfHtmlTable>()
        var depth = 0
        var rows: MutableList<List<ZfHtmlCell>>? = null
        var row: MutableList<ZfHtmlCell>? = null
        var cell: ZfHtmlToken.StartTag? = null
        val cellText = StringBuilder()

        fun flushCell() {
            val open = cell ?: return
            row?.let { pushCell(it, open, cellText.toString()) }
            cell = null
            cellText.setLength(0)
        }

        fun flushRow() {
            flushCell()
            row?.let { rows?.add(it.toList()) }
            row = null
        }

        for (token in tokens) {
            if (token is ZfHtmlToken.StartTag) {
                when (token.name) {
                    "table" -> {
                        depth++
                        if (depth == 1) rows = mutableListOf()
                    }
                    "tr" -> if (depth == 1) {
                        flushRow()
                        row = mutableListOf()
                    }
                    "td", "th" -> if (depth == 1) {
                        flushCell()
                        cell = token
                    }
                    "br" -> if (depth == 1 && cell != null) cellText.append('\n')
                }
                continue
            }
            if (token is ZfHtmlToken.EndTag) {
                when (token.name) {
                    "table" -> {
                        if (depth == 1) {
                            flushRow()
                            rows?.let { tables += ZfHtmlTable(it) }
                            rows = null
                        }
                        depth = (depth - 1).coerceAtLeast(0)
                    }
                    "tr" -> if (depth == 1) flushRow()
                    "td", "th" -> if (depth == 1) flushCell()
                }
                continue
            }
            if (token is ZfHtmlToken.Text && depth == 1 && cell != null) cellText.append(token.raw)
        }
        return tables
    }

    fun decodeEntities(raw: String): String {
        if ('&' !in raw) return raw
        val builder = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch != '&') {
                builder.append(ch)
                index++
                continue
            }
            val end = raw.indexOf(';', index)
            if (end < 0 || end - index > 10) {
                builder.append(ch)
                index++
                continue
            }
            val decoded = decodeEntity(raw.substring(index + 1, end))
            if (decoded == null) {
                builder.append(ch)
                index++
            } else {
                builder.append(decoded)
                index = end + 1
            }
        }
        return builder.toString()
    }

    private fun decodeEntity(entity: String): String? = when {
        entity.equals("nbsp", ignoreCase = true) -> " "
        entity.equals("amp", ignoreCase = true) -> "&"
        entity.equals("lt", ignoreCase = true) -> "<"
        entity.equals("gt", ignoreCase = true) -> ">"
        entity.equals("quot", ignoreCase = true) -> "\""
        entity.equals("apos", ignoreCase = true) -> "'"
        entity.startsWith("#x", ignoreCase = true) ->
            entity.substring(2).toIntOrNull(16)?.let { codepoint -> Character.toChars(codepoint).concatToString() }
        entity.startsWith("#") ->
            entity.substring(1).toIntOrNull()?.let { codepoint -> Character.toChars(codepoint).concatToString() }
        else -> null
    }

    private data class SelectRead(val options: List<ZfOption>, val nextIndex: Int)

    private fun readSelect(tokens: List<ZfHtmlToken>, startIndex: Int): SelectRead {
        val options = ArrayList<ZfOption>()
        var open: ZfHtmlToken.StartTag? = null
        val text = StringBuilder()
        var index = startIndex + 1
        while (index < tokens.size) {
            val token = tokens[index]
            if (token is ZfHtmlToken.EndTag && token.name == "select") {
                index++
                break
            }
            if (token is ZfHtmlToken.StartTag && token.name == "select") break
            if (token is ZfHtmlToken.StartTag && token.name == "option") {
                open = token
                text.setLength(0)
            } else if (token is ZfHtmlToken.EndTag && token.name == "option") {
                open?.let { options += toOption(it, text.toString()) }
                open = null
            } else if (open != null) {
                when (token) {
                    is ZfHtmlToken.Text -> text.append(token.raw)
                    is ZfHtmlToken.StartTag -> if (token.name == "br") text.append(' ')
                    is ZfHtmlToken.EndTag -> Unit
                }
            }
            index++
        }
        open?.let { options += toOption(it, text.toString()) }
        return SelectRead(options, index)
    }

    private fun toOption(tag: ZfHtmlToken.StartTag, rawText: String): ZfOption {
        val text = normalizeText(rawText)
        val value = tag.attributes["value"]?.let { decodeEntities(it) } ?: text
        return ZfOption(value = value, text = text, selected = tag.attributes.containsKey("selected"))
    }

    private fun pushCell(target: MutableList<ZfHtmlCell>, tag: ZfHtmlToken.StartTag, rawText: String) {
        target += ZfHtmlCell(
            text = normalizeCellText(rawText),
            rowSpan = tag.attributes["rowspan"]?.trim()?.toIntOrNull() ?: 1,
            colSpan = tag.attributes["colspan"]?.trim()?.toIntOrNull() ?: 1
        )
    }

    private fun normalizeCellText(raw: String): String =
        decodeEntities(raw).split('\n').map { it.replace(SPACES, " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    private fun normalizeText(raw: String): String = decodeEntities(raw).replace(SPACES, " ").trim()

    // ---- 标签流 ----

    private sealed interface ZfHtmlToken {
        data class StartTag(val name: String, val attributes: Map<String, String>, val selfClosing: Boolean) : ZfHtmlToken
        data class EndTag(val name: String) : ZfHtmlToken
        data class Text(val raw: String) : ZfHtmlToken
    }

    private fun tokenize(html: String): List<ZfHtmlToken> {
        val tokens = ArrayList<ZfHtmlToken>()
        var index = 0
        while (index < html.length) {
            val next = html.indexOf('<', index)
            if (next < 0) {
                tokens += ZfHtmlToken.Text(html.substring(index))
                break
            }
            if (next > index) tokens += ZfHtmlToken.Text(html.substring(index, next))
            if (html.startsWith("<!--", next)) {
                val end = html.indexOf("-->", next + 4)
                index = if (end < 0) html.length else end + 3
                continue
            }
            if (html.startsWith("<!", next) || html.startsWith("<?", next)) {
                val end = html.indexOf('>', next)
                index = if (end < 0) html.length else end + 1
                continue
            }
            val tagEnd = findTagEnd(html, next + 1)
            if (tagEnd < 0) {
                tokens += ZfHtmlToken.Text(html.substring(next))
                break
            }
            val raw = html.substring(next + 1, tagEnd).trim()
            index = tagEnd + 1
            if (raw.isEmpty()) continue
            if (raw.startsWith("/")) {
                tokens += ZfHtmlToken.EndTag(raw.substring(1).trim().lowercase(Locale.ROOT))
                continue
            }
            val selfClosing = raw.endsWith("/")
            val body = if (selfClosing) raw.dropLast(1).trim() else raw
            val nameEnd = body.indexOfFirst { it.isWhitespace() }
            val name = (if (nameEnd < 0) body else body.substring(0, nameEnd)).lowercase(Locale.ROOT)
            if (name.isEmpty()) continue
            val attributes = parseAttributes(if (nameEnd < 0) "" else body.substring(nameEnd))
            tokens += ZfHtmlToken.StartTag(name, attributes, selfClosing || name in VOID_TAGS)
            if (name in RAW_TEXT_TAGS) {
                val close = html.indexOf("</" + name, index, ignoreCase = true)
                if (close < 0) {
                    index = html.length
                } else {
                    val closeEnd = html.indexOf('>', close)
                    index = if (closeEnd < 0) html.length else closeEnd + 1
                }
            }
        }
        return tokens
    }

    private fun findTagEnd(html: String, from: Int): Int {
        var index = from
        var quote: Char? = null
        while (index < html.length) {
            val ch = html[index]
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '>' -> return index
            }
            index++
        }
        return -1
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var index = 0
        while (index < raw.length) {
            while (index < raw.length && (raw[index].isWhitespace() || raw[index] == '/')) index++
            if (index >= raw.length) break
            val nameStart = index
            while (index < raw.length && raw[index] != '=' && !raw[index].isWhitespace() && raw[index] != '/') index++
            if (index == nameStart) {
                index++
                continue
            }
            val name = raw.substring(nameStart, index).lowercase(Locale.ROOT)
            while (index < raw.length && raw[index].isWhitespace()) index++
            var value = ""
            if (index < raw.length && raw[index] == '=') {
                index++
                while (index < raw.length && raw[index].isWhitespace()) index++
                if (index < raw.length && (raw[index] == '"' || raw[index] == '\'')) {
                    val quote = raw[index]
                    index++
                    val valueStart = index
                    while (index < raw.length && raw[index] != quote) index++
                    value = raw.substring(valueStart, index)
                    if (index < raw.length) index++
                } else {
                    val valueStart = index
                    while (index < raw.length && !raw[index].isWhitespace()) index++
                    value = raw.substring(valueStart, index)
                }
            }
            if (!result.containsKey(name)) result[name] = decodeEntities(value)
        }
        return result
    }

    private val BLOCK_TAGS = setOf("br", "p", "div", "li", "tr", "td", "th", "table", "h1", "h2", "h3")
}
