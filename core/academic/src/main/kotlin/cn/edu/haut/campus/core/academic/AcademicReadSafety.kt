package cn.edu.haut.campus.core.academic

import org.json.JSONArray
import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.security.MessageDigest

/** 仅返回脱敏错误；校方响应正文、认证票据和凭据不得进入错误 UI。 */
class AcademicReadException(val code: String, message: String) : IllegalStateException(message)

object HautAcademicUrlPolicy {
    const val ACADEMIC_HOST = "jwglxt.haut.edu.cn"
    const val CAS_HOST = "mapp.haut.edu.cn"
    private val hosts = setOf(ACADEMIC_HOST, CAS_HOST)

    fun validate(raw: String, credentialPost: Boolean = false): URI {
        val uri = try { URI(raw) } catch (_: Exception) { reject() }
        if (uri.scheme != "https" || uri.host !in hosts || uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443) || uri.rawFragment != null || raw.any { it.isISOControl() } ||
            '\\' in raw
        ) reject()
        if (credentialPost && (uri.host != CAS_HOST ||
                !(uri.path == "/cas/login" || uri.path.startsWith("/cas/login;")))) reject()
        return uri
    }

    fun resolve(location: String, currentUrl: String): String {
        val base = validate(currentUrl)
        val target = try {
            val relative = URI(location.trim())
            if (relative.rawFragment != null) reject()
            if (!relative.isAbsolute && relative.rawAuthority == null && relative.rawPath.isEmpty() && relative.rawQuery != null) {
                currentUrl.substringBefore('?') + "?" + relative.rawQuery
            } else base.resolve(relative).toString()
        } catch (_: Exception) { reject() }
        return validate(target).toString()
    }

    private fun reject(): Nothing = throw AcademicReadException(
        "UNTRUSTED_REDIRECT", "学校认证跳转到了未授权地址，已停止请求；请使用官方入口重新登录"
    )
}

/** 每次登录独立创建，并且只向精确来源发送符合路径/有效期的 Cookie。 */
internal class OfficialAcademicCookieJar(val host: String) {
    private val manager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    fun headers(uri: URI): Map<String, List<String>> {
        check(uri.host == host) { "认证会话来源不匹配" }
        return manager.get(uri, emptyMap())
    }
    fun accept(uri: URI, headers: Map<String, List<String>>) {
        check(uri.host == host) { "认证会话来源不匹配" }
        manager.put(uri, headers)
    }
}

/** 完整分页只在终止证据和去重计数均成立后返回，任何中途失败都不发布部分列表。 */
object AcademicPageReader {
    fun readAll(
        pageSize: Int = 200,
        maxPages: Int = 100,
        maxRecords: Int = 20_000,
        fetch: (page: Int, pageSize: Int) -> String
    ): String {
        require(pageSize > 0 && maxPages > 0 && maxRecords > 0)
        val rows = JSONArray()
        val seenPages = mutableSetOf<String>()
        val seenRows = mutableSetOf<String>()
        var expectedTotal: Int? = null
        var expectedPages: Int? = null
        for (page in 1..maxPages) {
            val response = fetch(page, pageSize)
            val root = try { JSONObject(response) }
            catch (_: org.json.JSONException) { fail("PAGE_CHANGED", "教务数据格式发生变化，已保留之前的数据") }
            val items = root.optJSONArray("items")
                ?: fail("PAGE_CHANGED", "教务未返回有效数据列表，已保留之前的数据")
            if (root.has("success") && root.opt("success") == false) {
                fail("ACCESS_DENIED", "学校未允许本次数据查询，已保留之前的数据")
            }
            val meta = root.optJSONObject("queryModel")
            fun number(vararg names: String): Int? {
                for (name in names) {
                    val owner = when {
                        root.has(name) && !root.isNull(name) -> root
                        meta?.has(name) == true && !meta.isNull(name) -> meta
                        else -> continue
                    }
                    return owner.opt(name)?.toString()?.toIntOrNull()?.takeIf { it >= 0 }
                        ?: fail("SOURCE_DATA_INVALID", "教务分页信息无效，已保留之前的数据")
                }
                return null
            }
            val sourcePage = number("currentPage", "page")
            if (sourcePage != null && sourcePage != page) fail("PARTIAL_RESPONSE", "教务返回的页码不一致，已保留之前的数据")
            val total = number("totalResult", "totalCount", "records")
            val pages = number("totalPage", "totalPages")
            val effectiveSize = number("showCount", "pageSize") ?: pageSize
            if (effectiveSize <= 0 || items.length() > effectiveSize) fail("SOURCE_DATA_INVALID", "教务分页大小无效")
            if (expectedTotal != null && total != expectedTotal || expectedPages != null && pages != expectedPages) {
                fail("STALE_SNAPSHOT", "教务数据在读取期间发生变化，请稍后重新同步")
            }
            if (page == 1) { expectedTotal = total; expectedPages = pages }
            if (total != null && total > maxRecords || pages != null && pages > maxPages) {
                fail("PARTIAL_RESPONSE", "教务数据超过本次读取上限，已保留之前的数据")
            }
            val canonicalRows = (0 until items.length()).map { index ->
                val item = items.optJSONObject(index) ?: fail("SOURCE_DATA_INVALID", "教务列表包含无效记录")
                canonical(item)
            }
            if (items.length() > 0 && !seenPages.add(stableAcademicId(*canonicalRows.sorted().toTypedArray()))) {
                fail("PAGINATION_LOOP", "教务重复返回同一页，已停止同步并保留之前的数据")
            }
            for (index in canonicalRows.indices) {
                if (!seenRows.add(canonicalRows[index])) fail("PARTIAL_RESPONSE", "教务分页包含重复记录，无法确认完整性")
                rows.put(items.getJSONObject(index))
            }
            if (rows.length() > maxRecords || total != null && rows.length() > total) fail("PARTIAL_RESPONSE", "教务数据总数与分页不一致")
            val terminal = when {
                total != null -> rows.length() == total
                pages != null -> page == pages || pages == 0 && rows.length() == 0
                else -> items.length() < effectiveSize
            }
            if (terminal) {
                if (pages != null && !(page == pages || pages == 0 && page == 1 && rows.length() == 0)) {
                    fail("PARTIAL_RESPONSE", "教务页数与记录总数不一致")
                }
                return JSONObject().put("items", rows).put("totalResult", rows.length()).toString()
            }
            if (items.length() == 0 || pages != null && page >= pages) fail("PARTIAL_RESPONSE", "教务分页提前结束，已保留之前的数据")
        }
        fail("PARTIAL_RESPONSE", "教务分页未能完整结束，已保留之前的数据")
    }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") {
            JSONObject.quote(it) + ":" + canonical(value.opt(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.opt(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun fail(code: String, message: String): Nothing = throw AcademicReadException(code, message)
}

internal fun stableAcademicId(vararg parts: String): String {
    val input = parts.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
