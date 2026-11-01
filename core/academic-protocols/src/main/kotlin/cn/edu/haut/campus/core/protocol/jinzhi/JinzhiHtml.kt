package cn.edu.haut.campus.core.protocol.jinzhi

import java.util.Locale

/**
 * UNVERIFIED（未验证）：金智各校导出页面的列名并不统一（课程名/课程名称/科目…），
 * 因此这里刻意**不写死列号**，而是把表头文字归一化后按别名定位列。
 * 该解析器只处理"一行一条记录"的列表型表格，不支持 rowspan 网格；
 * 金智的课表/成绩/考试页面属于列表型，网格型课表请使用其他协议适配器。
 */

internal class JinzhiTable(val header: List<String>, val rows: List<List<String>>) {

    fun column(vararg aliases: String): Int? {
        val normalized = header.map { normalize(it) }
        for (alias in aliases) {
            val target = normalize(alias)
            if (target.isEmpty()) continue
            val index = normalized.indexOf(target)
            if (index >= 0) return index
        }
        return null
    }

    fun hasAny(vararg aliases: String): Boolean = column(*aliases) != null

    fun value(row: List<String>, index: Int?): String? =
        index?.let { row.getOrNull(it)?.trim()?.ifEmpty { null } }

    companion object {
        fun normalize(raw: String): String = raw
            .replace(" ", "")
            .replace('\u3000', ' ')
            .replace("：", "")
            .replace(":", "")
            .replace("\n", "")
            .trim()
            .lowercase(Locale.ROOT)
    }
}

internal object JinzhiHtml {

    private val DENIED_MARKERS = listOf("没有权限", "无权限", "无权访问", "权限不足", "禁止访问", "未授权访问")
    private val PARTIAL_MARKERS = listOf("数据不完整", "尚未录入", "仅显示部分", "只显示前", "已截断")

    fun looksPartial(html: String): Boolean = PARTIAL_MARKERS.any { it in html }

    fun tables(html: String): List<JinzhiTable> {
        val result = ArrayList<JinzhiTable>()
        var cursor = 0
        while (cursor < html.length) {
            val open = nextTag(html, cursor) ?: break
            if (open.close || open.name != "table") {
                cursor = open.end
                continue
            }
            val close = matchingClose(html, open.end, "table")
            val bodyEnd = close?.start ?: html.length
            toTable(html.substring(open.end, bodyEnd))?.let { result += it }
            cursor = close?.end ?: html.length
        }
        return result
    }

    /** 页面正文文本，用于识别"无权限"这类软拒绝（HTTP 200 但内容拒绝）。 */
    fun text(html: String): String = stripTags(html)

    fun looksDenied(html: String): Boolean = DENIED_MARKERS.any { it in html }

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
            val end = indexOfTagEnd(html, lt + 1)
            if (end < 0) {
                sb.append(html, lt, html.length)
                break
            }
            val head = html.substring(lt + 1, minOf(end, lt + 10)).trim().lowercase(Locale.ROOT)
            if (head.startsWith("br") || head.startsWith("p") || head.startsWith("/p")) sb.append('\n')
            i = end + 1
        }
        return decodeEntities(sb.toString())
            .split('\n')
            .map { line -> line.trim().replace(Regex("[ \t\u00a0]+"), " ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

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
            val body = text.substring(i + 1, semi)
            val decoded = when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let { codepoint(it) }
                body.startsWith("#") -> body.substring(1).toIntOrNull()?.let { codepoint(it) }
                else -> NAMED[body.lowercase(Locale.ROOT)]
            }
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

    fun attributes(tagRaw: String): Map<String, String> {
        val body = tagRaw.trim().removePrefix("<").removeSuffix(">")
        if ('=' !in body) return emptyMap()
        val result = LinkedHashMap<String, String>()
        ATTRIBUTE.findAll(body).forEach { match ->
            val key = match.groupValues[1].lowercase(Locale.ROOT)
            val value = match.groupValues[2].ifEmpty {
                match.groupValues[3].ifEmpty { match.groupValues[4] }
            }
            result.putIfAbsent(key, decodeEntities(value))
        }
        return result
    }

    private fun codepoint(code: Int): String? =
        if (code in 0..0x10FFFF && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else null

    private val NAMED = mapOf(
        "nbsp" to " ",
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "middot" to "·",
        "hellip" to "…"
    )

    private val ATTRIBUTE = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")

    private class Tag(
        val name: String,
        val close: Boolean,
        val start: Int,
        val end: Int,
        val raw: String
    )

    private fun toTable(body: String): JinzhiTable? {
        val rows = ArrayList<List<String>>()
        var current: MutableList<String>? = null
        var cellStart = -1
        var cursor = 0

        fun flushCell(end: Int) {
            if (cellStart < 0) return
            current?.add(stripTags(body.substring(cellStart, end)))
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
                    current?.let { rows += it }
                    current = if (tag.close) null else ArrayList()
                }
                "td", "th" -> {
                    flushCell(tag.start)
                    if (!tag.close) cellStart = tag.end
                }
                else -> Unit
            }
        }
        flushCell(body.length)
        current?.let { rows += it }

        val filled = rows.filter { row -> row.any { it.isNotEmpty() } }
        if (filled.isEmpty()) return null
        val headerIndex = filled.indexOfFirst { row -> row.count { it.isNotEmpty() } >= 2 }
        if (headerIndex < 0) return null
        return JinzhiTable(filled[headerIndex], filled.drop(headerIndex + 1))
    }

    private fun nextTag(html: String, from: Int): Tag? {
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
            val name = html.substring(nameStart, j).lowercase(Locale.ROOT)
            val end = indexOfTagEnd(html, j)
            if (end < 0) return null
            if (name.isEmpty()) {
                i = lt + 1
                continue
            }
            return Tag(name, close, lt, end + 1, html.substring(lt, end + 1))
        }
    }

    private fun matchingClose(html: String, from: Int, name: String): Tag? {
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

/**
 * 登录表单识别：既支持金智统一身份认证的 CAS 表单，也支持教务系统自带表单。
 * 只读取字段名与提交地址，不读取、不缓存任何用户输入的密码。
 */
internal class JinzhiLoginForm private constructor(
    val action: String?,
    val usernameField: String,
    val passwordField: String
) {

    fun buildBody(studentId: String, password: String): String =
        encode(usernameField) + "=" + encode(studentId) + "&" + encode(passwordField) + "=" + encode(password)

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        private val USERNAME_ALIASES = setOf("username", "j_username", "account", "userid", "loginname", "xh")
        private val PASSWORD_ALIASES = setOf("password", "j_password", "pwd", "mm")
        private val CAPTCHA_ALIASES = setOf("captcha", "verifycode", "checkcode", "randcode")

        fun parse(html: String): JinzhiLoginForm? {
            val inputs = JinzhiHtml.inputs(html)
            val password = inputs.firstOrNull { input ->
                val name = input["name"]?.lowercase(Locale.ROOT)
                input["type"]?.equals("password", ignoreCase = true) == true ||
                    (name != null && name in PASSWORD_ALIASES)
            } ?: return null
            val passwordField = password["name"]?.trim().orEmpty()
            if (passwordField.isEmpty()) return null
            val username = inputs.firstOrNull { input ->
                val name = input["name"]?.lowercase(Locale.ROOT) ?: return@firstOrNull false
                name in USERNAME_ALIASES && input["type"]?.equals("hidden", ignoreCase = true) != true
            }
            val usernameField = username?.get("name")?.trim().orEmpty().ifEmpty { "username" }
            return JinzhiLoginForm(JinzhiHtml.formAction(html), usernameField, passwordField)
        }

        fun requiresCaptcha(html: String): Boolean = JinzhiHtml.inputs(html).any { input ->
            val name = input["name"]?.lowercase(Locale.ROOT) ?: return@any false
            name in CAPTCHA_ALIASES
        }

        fun looksRejected(html: String): Boolean =
            listOf("用户名或密码", "密码错误", "认证失败", "账号或密码", "登录失败").any { it in html }
    }
}
