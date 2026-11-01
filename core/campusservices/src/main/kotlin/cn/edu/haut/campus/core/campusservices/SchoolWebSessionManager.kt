/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.core.campusservices

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Build
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/**
 * 学校官方网页共享会话与 WebView 安全基线管理器。
 *
 * 负责统一管理正方教务系统 (`jwglxt.haut.edu.cn`)、移动馆藏检索 (`libspace.haut.edu.cn`)、
 * 图书馆空间选座 (`wslib.haut.edu.cn`) 及学校统一身份认证 (`mapp.haut.edu.cn`) 的私有 WebView 会话。
 */
object SchoolWebSessionManager {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val cookieFlushScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )

    /**
     * 为指定 [WebView] 应用学校官方网页安全基线与共享会话设置。
     *
     * 安全不变量：
     * - 禁用文件与 Content Provider 访问（`allowFileAccess = false`、`allowContentAccess = false`）
     * - 严禁混合内容（`MIXED_CONTENT_NEVER_ALLOW`）
     * - 严禁第三方跨站 Cookie（`setAcceptThirdPartyCookies(webView, false)`）
     * - 严禁挂载任何 `addJavascriptInterface`
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configureHardenedSchoolWebView(
        webView: WebView,
        supportZoom: Boolean = false
    ) {
        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
        cookieManager?.setAcceptCookie(true)
        cookieManager?.setAcceptThirdPartyCookies(webView, false)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(supportZoom)
            builtInZoomControls = supportZoom
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            userAgentString = userAgentString
                .replace("; wv", "")
                .trim()
        }
    }

    /**
     * 在后台单线程串行刷写 CookieManager，绝不阻塞 UI 主线程。
     */
    fun persistCookiesAsync() {
        cookieFlushScope.launch {
            runCatching {
                CookieManager.getInstance().flush()
            }
        }
    }

    /**
     * 彻底清除学校网页共享会话（包括全部 WebView Cookie、WebStorage/DOM Storage 与 WebView 缓存）。
     * 注意：此方法仅清理学校网页会话，不触碰本地课表数据库或水电 Keystore 账号。
     */
    suspend fun clearAllSchoolWebSessions(activeWebView: WebView? = null) {
        withContext(Dispatchers.Main.immediate) {
            runCatching {
                activeWebView?.stopLoading()
                activeWebView?.clearHistory()
                activeWebView?.clearCache(true)
                activeWebView?.clearFormData()
            }
            runCatching {
                WebStorage.getInstance().deleteAllData()
            }
            val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
            if (cookieManager != null) {
                suspendCancellableCoroutine { cont ->
                    cookieManager.removeAllCookies {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
        }
        withContext(Dispatchers.IO) {
            runCatching {
                CookieManager.getInstance().flush()
            }
        }
    }
}

/**
 * WebView 导航与子资源安全拦截策略。
 */
object SafeWebNavigationPolicy {

    /**
     * 校验主框架或子框架导航请求是否允许在 WebView 内部继续加载。
     * 对于学校官方域名 (`*.haut.edu.cn`) 在统一身份认证跳回时产生的历史 HTTP GET 重定向，
     * 自动升级为 HTTPS 并在当前 WebView 中继续加载。
     * @return `true` 表示拦截（阻断原请求或已接管升级加载），`false` 表示直接放行。
     */
    fun shouldBlockNavigation(request: WebResourceRequest?, view: WebView? = null): Boolean {
        val url = request?.url?.toString().orEmpty()
        if (url.isBlank()) return true
        val isMainFrame = request?.isForMainFrame != false
        return if (isMainFrame) {
            if (OfficialServiceRoutePolicy.isAllowedMainFrameUrl(url)) {
                false
            } else {
                val upgraded = OfficialServiceRoutePolicy.maybeUpgradeLegacyTeachingGetUrl(
                    rawUrl = url,
                    method = request?.method ?: "GET"
                )
                if (upgraded != null && view != null) {
                    view.post { view.loadUrl(upgraded) }
                    true
                } else {
                    true
                }
            }
        } else {
            !OfficialServiceRoutePolicy.isAllowedSubFrameUrl(url)
        }
    }

    /**
     * 拦截非白名单子资源请求（返回 403 空响应）。
     * 主框架请求交由 [shouldBlockNavigation] 处理，避免阻断 CAS 302 跳转。
     */
    fun interceptSubresourceIfBlocked(
        request: WebResourceRequest?,
        allowCatalogCovers: Boolean = false
    ): WebResourceResponse? {
        if (request?.isForMainFrame == true) return null
        val url = request?.url?.toString().orEmpty()
        if (url.isBlank()) return blockedResponse()
        val trimmed = url.trim()
        if (trimmed.startsWith("data:", ignoreCase = true) || trimmed.startsWith("blob:", ignoreCase = true)) {
            return null
        }
        val allowed = if (allowCatalogCovers) {
            OfficialServiceRoutePolicy.isAllowedCatalogResourceUrl(trimmed)
        } else {
            OfficialServiceRoutePolicy.isAllowedSubFrameUrl(trimmed)
        }
        return if (allowed) null else blockedResponse()
    }

    /**
     * TLS/SSL 证书异常一律立即取消，严禁调用 `proceed()`。
     */
    fun cancelOnSslError(handler: SslErrorHandler?, @Suppress("UNUSED_PARAMETER") error: SslError?) {
        handler?.cancel()
    }

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked by OfficialServiceRoutePolicy",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0))
    )
}
