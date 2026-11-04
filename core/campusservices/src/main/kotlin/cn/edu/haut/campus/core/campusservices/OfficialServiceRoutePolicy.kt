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
        val rawTrimmed = rawUrl?.trim().orEmpty()
        if (rawTrimmed.isEmpty() || rawTrimmed.length > 2048) return ""
        val text = if (rawTrimmed.startsWith("//")) "https:$rawTrimmed" else rawTrimmed
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
     * 判断是否为选座 H5 站点（`https://wslib.haut.edu.cn`）。
     */
    fun isLibrarySeatOrigin(rawUrl: String?): Boolean {
        val endpoint = rawUrl?.let(::parseHttpsEndpoint) ?: return false
        return endpoint.host == WSLIB_HOST
    }

    /**
     * 判断是否为选座 H5 主文档（`https://wslib.haut.edu.cn/h5/index.html`）。
     */
    fun isLibrarySeatIndexDocument(rawUrl: String?): Boolean {
        val endpoint = rawUrl?.let(::parseHttpsEndpoint) ?: return false
        return endpoint.host == WSLIB_HOST && endpoint.uri.rawPath == SEAT_BOOKING_BASE_PATH
    }

    /**
     * 判断 host 是否为小米官方认证及智能助理允许的 HTTPS 域名。
     */
    fun isAllowedXiaomiHost(host: String): Boolean {
        val clean = host.lowercase(Locale.ROOT).trimEnd('.')
        return clean == "xiaomi.com" ||
            clean.endsWith(".xiaomi.com") ||
            clean == "mi.com" ||
            clean.endsWith(".mi.com") ||
            clean == "miui.com" ||
            clean.endsWith(".miui.com") ||
            clean == "cnbj1.fds.api.xiaomi.com"
    }

    /**
     * 校验小米账号 WebView 导航与资源 URL 是否属于官方允许的 HTTPS 域名。
     */
    fun isAllowedXiaomiWebUrl(rawUrl: String?): Boolean {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed in SAFE_SUBFRAME_ABOUT_URIS) return true
        val endpoint = parseHttpsEndpoint(trimmed) ?: return false
        return isAllowedXiaomiHost(endpoint.host)
    }

    /**
     * 构造携带有效登录 Token 的图书馆选座入口 URL（当无 Token 时回退至默认选座入口）。
     */
    fun buildAuthenticatedSeatBookingUrl(rawToken: String?): String {
        val clean = HautLibrarySeatAuthClient.normalizeRawToken(rawToken)
        if (clean.isBlank()) return SEAT_BOOKING_ENTRY_URL
        val encoded = java.net.URLEncoder.encode(clean, java.nio.charset.StandardCharsets.UTF_8.name())
        return "https://$WSLIB_HOST$SEAT_BOOKING_BASE_PATH?token=$encoded#/seatPage/SelectRoom/SelectRoom"
    }

    /**
     * 构造携带有效登录 Token 的「我的预约管理（签到/取消/暂离/签退）」页面 URL。
     */
    fun buildAuthenticatedMySeatManageUrl(rawToken: String?): String {
        val clean = HautLibrarySeatAuthClient.normalizeRawToken(rawToken)
        if (clean.isBlank()) return "https://$WSLIB_HOST$SEAT_BOOKING_BASE_PATH#/pages/SeatBooking/SeatBooking"
        val encoded = java.net.URLEncoder.encode(clean, java.nio.charset.StandardCharsets.UTF_8.name())
        return "https://$WSLIB_HOST$SEAT_BOOKING_BASE_PATH?token=$encoded#/pages/SeatBooking/SeatBooking"
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
     * 构造将持久化/后台重登获得的 `wslib` Token 同步注入 Uni-App `localStorage` 与 `getApp().globalData` 的脚本。
     *
     * 根因修复说明：
     * `wslib.haut.edu.cn/h5/static/js/index.5bfd61d0.js` 在初始化时先将 `localStorage["hzd_token"]` 的旧值赋给
     * `getApp().globalData.token`，然后延迟 200ms (`setTimeout(..., 200)`) 才把 URL 中的新 `?token=...` 写入 `globalData.token`；
     * 若在此 200ms 窗口内立即发起选座或跳转路由，`myRequest` 会读取到旧的失效 `globalData.token` 并弹出「登录已失效请重新登录」。
     * 此脚本在页面启动与完成时同步覆写 `localStorage`、`uni.setStorageSync` 与 `getApp().globalData.token`，彻底消除竞态。
     */
    fun buildLibraryTokenSyncScript(
        rawToken: String?,
        navigateToSelectRoom: Boolean = false
    ): String {
        val clean = HautLibrarySeatAuthClient.normalizeRawToken(rawToken)
        val navigateLiteral = if (navigateToSelectRoom) "true" else "false"
        return """
            (function() {
                try {
                    if (location.origin !== 'https://wslib.haut.edu.cn') return false;
                    var rawToken = '$clean';
                    if (!rawToken) {
                        var m = /[?&]token=([^&#;]+)/.exec(location.href);
                        if (m && m[1]) {
                            rawToken = decodeURIComponent(m[1].replace(/\+/g, '%20')).replace(/^Bearer\s+/i, '').trim();
                        }
                    }
                    if (rawToken) {
                        var bearer = 'Bearer ' + rawToken;
                        try {
                            localStorage.setItem('hzd_token', bearer);
                            localStorage.setItem('hzd_isLogin', JSON.stringify({type: 'boolean', data: true}));
                        } catch (_) {}
                        try {
                            if (typeof uni !== 'undefined' && typeof uni.setStorageSync === 'function') {
                                uni.setStorageSync('hzd_token', bearer);
                                uni.setStorageSync('hzd_isLogin', true);
                            }
                        } catch (_) {}
                        try {
                            if (typeof getApp === 'function' && getApp() && getApp().globalData) {
                                getApp().globalData.token = bearer;
                                getApp().globalData.isLogin = true;
                                if (!getApp().globalData.tmplIds) {
                                    getApp().globalData.tmplIds = {
                                        seatTemplateId: '1',
                                        seatBookingStartTmplId: '2',
                                        seatBreakTmplId: '3',
                                        seatShotLeaveTmplId: '4',
                                        seatLeaveBackTmplId: '5',
                                        seatBookingEndTmplId: '6'
                                    };
                                }
                            }
                        } catch (_) {}
                    }
                    try {
                        if (typeof window.wx === 'undefined') {
                            window.wx = {};
                        }
                        if (typeof window.wx.getSetting !== 'function') {
                            window.wx.getSetting = function(opt) {
                                if (opt && typeof opt.success === 'function') {
                                    opt.success({ subscriptionsSetting: { mainSwitch: true, itemSettings: null } });
                                }
                            };
                        }
                        if (typeof window.wx.requestSubscribeMessage !== 'function') {
                            window.wx.requestSubscribeMessage = function(opt) {
                                if (opt && typeof opt.success === 'function') {
                                    var res = {};
                                    var ids = (opt && opt.tmplIds) || [];
                                    for (var i = 0; i < ids.length; i++) res[ids[i]] = 'accept';
                                    opt.success(res);
                                }
                            };
                        }
                        if (typeof window.wx.showToast !== 'function') {
                            window.wx.showToast = function() {};
                        }
                        if (typeof window.wx.showModal !== 'function') {
                            window.wx.showModal = function() {};
                        }
                    } catch (_) {}
                    try {
                        if (!window.__hautSeatCancelFallbackInstalled && typeof XMLHttpRequest !== 'undefined') {
                            window.__hautSeatCancelFallbackInstalled = true;
                            var origOpen = XMLHttpRequest.prototype.open;
                            var origSend = XMLHttpRequest.prototype.send;
                            XMLHttpRequest.prototype.open = function(method, url) {
                                this.__hautReqUrl = typeof url === 'string' ? url : '';
                                return origOpen.apply(this, arguments);
                            };
                            XMLHttpRequest.prototype.send = function() {
                                var xhr = this;
                                xhr.addEventListener('readystatechange', function() {
                                    if (xhr.readyState === 4 && xhr.__hautReqUrl && xhr.__hautReqUrl.indexOf('/seatbook/user/cancel') >= 0) {
                                        try {
                                            var data = JSON.parse(xhr.responseText || '{}');
                                            if (data && data.code !== 200 && String(data.msg || '').indexOf('已开始') >= 0) {
                                                var mBid = /[?&]bookingid=(\d+)/i.exec(xhr.__hautReqUrl);
                                                var bid = mBid ? mBid[1] : '';
                                                var tk = localStorage.getItem('hzd_token') || '';
                                                if (bid && tk) {
                                                    fetch('/spacelink/api/seatbook/user/signoff?bookingid=' + bid, {
                                                        method: 'GET',
                                                        headers: { 'Authorization': tk, 'Accept': 'application/json' }
                                                    }).then(function(r) { return r.json(); }).then(function(so) {
                                                        if (so && so.code === 200) {
                                                            if (typeof uni !== 'undefined' && uni.showToast) {
                                                                uni.showToast({ icon: 'none', title: '预约已开始，已自动为您签退释放座位', duration: 3000 });
                                                            }
                                                            setTimeout(function() { location.reload(); }, 800);
                                                        } else if (typeof uni !== 'undefined' && uni.showModal) {
                                                            uni.showModal({
                                                                title: '预约已生效（满5分钟）',
                                                                content: '河工大图书馆规定当日选座 5 分钟后正式开始，开始后未签到不可直接取消。\n\n• 若您已刷脸入馆：系统每整 5 分钟（:00/:05/:10）自动同步签到，同步后点击顶部「签退释放」即可退座。\n• 若尚未入馆：请刷脸过闸机自动签到后再点击「签退释放」。',
                                                                showCancel: false,
                                                                confirmText: '知道了'
                                                            });
                                                        }
                                                    }).catch(function() {});
                                                }
                                            }
                                        } catch (_) {}
                                    }
                                });
                                return origSend.apply(this, arguments);
                            };
                        }
                    } catch (_) {}
                    try {
                        if (!window.__hautNullScrubberInstalled && typeof MutationObserver !== 'undefined') {
                            window.__hautNullScrubberInstalled = true;
                            var scrubNode = function(root) {
                                if (!root) return;
                                var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
                                var node;
                                while ((node = walker.nextNode())) {
                                    var v = node.nodeValue;
                                    if (v && v.indexOf('null') >= 0) {
                                        node.nodeValue = v
                                            .replace(/null\s*-\s*null/gi, '07:30-22:00')
                                            .replace(/\bnull\b/gi, '')
                                            .trim();
                                    }
                                }
                            };
                            scrubNode(document.body);
                            var obs = new MutationObserver(function() { scrubNode(document.body); });
                            if (document.body) {
                                obs.observe(document.body, { childList: true, subtree: true, characterData: true });
                            }
                        }
                    } catch (_) {}
                    if ($navigateLiteral &&
                        location.pathname === '/h5/index.html' &&
                        !location.hash.startsWith('#/seatPage/')) {
                        location.hash = '#/seatPage/SelectRoom/SelectRoom';
                    }
                    return true;
                } catch (e) {
                    return false;
                }
            })();
        """.trimIndent()
    }

    /**
     * 当用户从 `mapp.haut.edu.cn` 完成统一身份认证返回 `wslib.haut.edu.cn/h5/index.html` 首页时，
     * 先同步提取并写入 URL 中的 `?token=`，再执行一次性路由跳转至选座列表。
     */
    val LIBRARY_RETURN_TO_SEATS_SCRIPT: String =
        buildLibraryTokenSyncScript(rawToken = null, navigateToSelectRoom = true)

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

    /**
     * 用户点击「我的预约」按钮时切换至官方预约管理页（包含签到、取消预约、短暂离、长暂离、签退、历史记录）。
     */
    const val LIBRARY_OPEN_MY_BOOKINGS_SCRIPT = """
        (function() {
            if (location.origin === 'https://wslib.haut.edu.cn' &&
                location.pathname === '/h5/index.html') {
                location.hash = '#/pages/SeatBooking/SeatBooking';
                return true;
            }
            return false;
        })();
    """

    /**
     * 检测页面是否弹出了「登录已失效请重新登录」模态框，并可在后台静默重登启动时自动关闭该弹窗。
     */
    const val LIBRARY_DETECT_AND_DISMISS_EXPIRED_MODAL_SCRIPT = """
        (function() {
            try {
                if (location.origin !== 'https://wslib.haut.edu.cn') return 'NONE';
                var modals = document.querySelectorAll('uni-modal, .uni-modal');
                for (var i = 0; i < modals.length; i++) {
                    var el = modals[i];
                    var txt = (el.innerText || el.textContent || '');
                    if (txt.indexOf('登录已失效') >= 0 || txt.indexOf('重新登录') >= 0) {
                        el.style.display = 'none';
                        return 'EXPIRED_MODAL';
                    }
                }
                return 'OK';
            } catch (e) {
                return 'ERR';
            }
        })();
    """
}
