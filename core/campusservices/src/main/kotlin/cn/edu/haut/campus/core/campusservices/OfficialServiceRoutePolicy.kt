/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.core.campusservices

import java.net.URI
import java.util.Locale

/**
 * 经过严格 URI 语法、HTTPS 协议、端口与主机名校验的安全端点。
 */
data class HttpsEndpoint(
    val uri: URI,
    val host: String
) {
    val urlText: String
        get() = uri.toASCIIString()
}

/**
 * 河南工业大学官方服务与校园服务域名路由安全策略。
 *
 * 安全不变量：
 * 1. 全程使用 [java.net.URI] 解析，禁止基于 String.contains / startsWith 绕过（如 `https://haut.edu.cn.evil.com`）。
 * 2. 强制要求 `scheme == "https"`、`userInfo == null`、`port in {-1, 443}`。
 * 3. 主框架仅允许 `haut.edu.cn` 及其子域名（或特定白名单网关）；子框架额外允许 `about:blank` / `about:srcdoc`。
 * 4. 馆藏封面 URL 仅允许 `libspace.haut.edu.cn`、`img.hwlsp.cn`、`*.ddimg.cn`，且强制剥离 Query、Fragment 与 UserInfo。
 */
object OfficialServiceRoutePolicy {

    const val HAUT_ROOT_DOMAIN = "haut.edu.cn"
    const val JWGLXT_HOST = "jwglxt.haut.edu.cn"
    const val LIBSPACE_HOST = "libspace.haut.edu.cn"
    const val WSLIB_HOST = "wslib.haut.edu.cn"
    const val MAPP_CAS_HOST = "mapp.haut.edu.cn"
    const val WANXIAO_HOST = "xqh5.17wanxiao.com"

    const val CATALOG_INDEX_URL = "https://libspace.haut.edu.cn/mspace/index"
    const val CATALOG_SEARCH_URL = "https://libspace.haut.edu.cn/mspace/search"
    const val SEAT_BOOKING_ENTRY_URL = "https://wslib.haut.edu.cn/h5/index.html#/seatPage/SelectRoom/SelectRoom"
    const val SEAT_BOOKING_BASE_PATH = "/h5/index.html"
    const val TEACHING_TIMETABLE_URL = "https://jwglxt.haut.edu.cn/jwglxt/kbcx/xskbcxZccx_cxXskbcxIndex.html?gnmkdm=N215304&layout=default"

    private val SAFE_SUBFRAME_ABOUT_URIS = setOf("about:blank", "about:srcdoc")

    /**
     * 解析并校验标准 HTTPS 端点（无 userInfo，端口仅限默认或 443）。
     */
    fun parseHttpsEndpoint(rawUrl: String): HttpsEndpoint? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > 4096) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https") return null
        if (uri.rawUserInfo != null || uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if (host.isEmpty()) return null
        return HttpsEndpoint(uri = uri, host = host)
    }

    /**
     * 判断 host 是否为河南工业大学官方域名（`haut.edu.cn` 或 `*.haut.edu.cn`）。
     */
    fun isOfficialHautHost(host: String): Boolean {
        val clean = host.lowercase(Locale.ROOT).trimEnd('.')
        return clean == HAUT_ROOT_DOMAIN || clean.endsWith(".$HAUT_ROOT_DOMAIN")
    }

    /**
     * 校验内嵌学校 WebView 主框架导航 URL：仅允许 `haut.edu.cn` 及 `*.haut.edu.cn` 的 HTTPS 地址。
     */
    fun isAllowedMainFrameUrl(rawUrl: String): Boolean {
        val endpoint = parseHttpsEndpoint(rawUrl) ?: return false
        return isOfficialHautHost(endpoint.host)
    }

    /**
     * [isAllowedMainFrameUrl] 的简写别名。
     */
    fun isAllowedWebUrl(rawUrl: String): Boolean = isAllowedMainFrameUrl(rawUrl)

    /**
     * 校验子框架 (iframe) URL：允许 `about:blank` / `about:srcdoc` 或学校官方 HTTPS 域名。
     */
    fun isAllowedSubFrameUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed in SAFE_SUBFRAME_ABOUT_URIS) return true
        return isAllowedMainFrameUrl(trimmed)
    }

    /**
     * 校验馆藏检索页面子资源：允许学校官方 HTTPS 域名及公开封面静态域名。
     */
    fun isAllowedCatalogResourceUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed in SAFE_SUBFRAME_ABOUT_URIS) return true
        val endpoint = parseHttpsEndpoint(trimmed) ?: return false
        return isOfficialHautHost(endpoint.host) || isAllowedCoverHost(endpoint.host)
    }

    /**
     * 校验是否为馆藏封面允许的静态图床域名。
     */
    fun isAllowedCoverHost(host: String): Boolean {
        val clean = host.lowercase(Locale.ROOT).trimEnd('.')
        return clean == LIBSPACE_HOST ||
            clean == "img.hwlsp.cn" ||
            clean == "ddimg.cn" ||
            clean.endsWith(".ddimg.cn")
    }

    /**
     * 清洗并校验馆藏图书封面 URL：
     * - 仅允许 `libspace.haut.edu.cn`、`img.hwlsp.cn`、`*.ddimg.cn` 的 HTTPS 链接
     * - 强制剥离 query、fragment、userInfo 与显式端口，仅保留纯净 path
     */
    fun sanitizeCoverUrl(rawUrl: String?): String {
        val text = rawUrl?.trim().orEmpty()
        if (text.isEmpty() || text.length > 2048) return ""
        val endpoint = parseHttpsEndpoint(text) ?: return ""
        if (!isAllowedCoverHost(endpoint.host)) return ""
        val rawPath = endpoint.uri.rawPath ?: return ""
        if (rawPath.isEmpty() || !rawPath.startsWith("/") || rawPath.contains("..")) return ""
        return runCatching {
            URI("https", null, endpoint.host, -1, rawPath, null, null).toASCIIString()
        }.getOrDefault("")
    }

    /**
     * 提取安全 Origin（仅限学校官方 HTTPS 站点）。
     */
    fun sanitizeOrigin(rawUrl: String?): String? {
        val endpoint = rawUrl?.let(::parseHttpsEndpoint) ?: return null
        if (!isOfficialHautHost(endpoint.host)) return null
        return "https://${endpoint.host}"
    }

    /**
     * 教务系统历史 HTTP 链接仅对幂等 `GET` 请求允许升级为 HTTPS（`POST` 等非幂等请求直接拒绝）。
     */
    fun maybeUpgradeLegacyTeachingGetUrl(rawUrl: String, method: String = "GET"): String? {
        if (!method.equals("GET", ignoreCase = true)) return null
        val trimmed = rawUrl.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if (!isOfficialHautHost(host)) return null
        if (uri.rawUserInfo != null || uri.userInfo != null) return null
        return when (scheme) {
            "https" -> if (uri.port == -1 || uri.port == 443) uri.toASCIIString() else null
            "http" -> if (uri.port == -1 || uri.port == 80) {
                buildString {
                    append("https://")
                    append(host)
                    if (!uri.rawPath.isNullOrEmpty()) append(uri.rawPath)
                    if (uri.rawQuery != null) {
                        append('?')
                        append(uri.rawQuery)
                    }
                    if (uri.rawFragment != null) {
                        append('#')
                        append(uri.rawFragment)
                    }
                }
            } else {
                null
            }
            else -> null
        }
    }

    /**
     * 判断是否为选座 H5 主文档（`https://wslib.haut.edu.cn/h5/index.html`）。
     */
    fun isLibrarySeatIndexDocument(rawUrl: String?): Boolean {
        val endpoint = rawUrl?.let(::parseHttpsEndpoint) ?: return false
        return endpoint.host == WSLIB_HOST && endpoint.uri.rawPath == SEAT_BOOKING_BASE_PATH
    }

    /**
     * 判断是否为学校统一身份认证站点（`https://mapp.haut.edu.cn`）。
     */
    fun isSchoolCasAuthUrl(rawUrl: String?): Boolean {
        val endpoint = rawUrl?.let(::parseHttpsEndpoint) ?: return false
        return endpoint.host == MAPP_CAS_HOST
    }
}

/**
 * 图书馆选座与正方教务页面前端只读适配脚本定义。
 */
object OfficialWebScripts {
    const val SEAT_SELECTION_HASH = "#/seatPage/SelectRoom/SelectRoom"

    /**
     * 当用户从 `mapp.haut.edu.cn` 完成统一身份认证返回 `wslib.haut.edu.cn/h5/index.html` 首页时，
     * 仅在未处于 `seatPage` 子路由时执行一次性路由跳转至选座列表。
     */
    const val LIBRARY_RETURN_TO_SEATS_SCRIPT = """
        (function() {
            if (location.origin === 'https://wslib.haut.edu.cn' &&
                location.pathname === '/h5/index.html' &&
                !location.hash.startsWith('#/seatPage/')) {
                location.hash = '#/seatPage/SelectRoom/SelectRoom';
                return true;
            }
            return false;
        })();
    """

    /**
     * 用户主动点击底部「继续选座」按钮时强制切回选座区域列表。
     */
    const val LIBRARY_FORCE_CONTINUE_SEATS_SCRIPT = """
        (function() {
            if (location.origin === 'https://wslib.haut.edu.cn' &&
                location.pathname === '/h5/index.html') {
                location.hash = '#/seatPage/SelectRoom/SelectRoom';
                return true;
            }
            return false;
        })();
    """
}
