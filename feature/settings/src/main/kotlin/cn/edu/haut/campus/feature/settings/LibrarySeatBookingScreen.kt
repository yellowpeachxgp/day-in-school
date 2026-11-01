/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.feature.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.edu.haut.campus.core.campusservices.OfficialServiceRoutePolicy
import cn.edu.haut.campus.core.campusservices.OfficialWebScripts
import cn.edu.haut.campus.core.campusservices.SafeWebNavigationPolicy
import cn.edu.haut.campus.core.campusservices.SchoolWebSessionManager
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LibrarySeatBookingScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf(OfficialServiceRoutePolicy.SEAT_BOOKING_ENTRY_URL) }
    var pendingReturnToSeatSelection by remember { mutableStateOf(true) }
    var showClearSessionDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "图书馆座位预约",
            subtitle = if (OfficialServiceRoutePolicy.isSchoolCasAuthUrl(currentUrl)) {
                "统一身份认证 (mapp.haut.edu.cn)"
            } else {
                "学校官方空间预约系统 (wslib.haut.edu.cn)"
            },
            navigationIcon = {
                XPeachIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack
                )
            }
        )

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    SchoolWebSessionManager.configureHardenedSchoolWebView(this, supportZoom = true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val targetUrl = request?.url?.toString().orEmpty()
                            if (OfficialServiceRoutePolicy.isSchoolCasAuthUrl(targetUrl)) {
                                // 进入统一身份认证后标记待回跳选座页
                                pendingReturnToSeatSelection = true
                            }
                            return SafeWebNavigationPolicy.shouldBlockNavigation(request)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            return SafeWebNavigationPolicy.interceptSubresourceIfBlocked(
                                request = request,
                                allowCatalogCovers = false
                            )
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            SafeWebNavigationPolicy.cancelOnSslError(handler, error)
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            super.onPageStarted(view, url, favicon)
                            isLoading = true
                            if (!url.isNullOrBlank()) {
                                currentUrl = url
                            }
                            if (OfficialServiceRoutePolicy.isSchoolCasAuthUrl(url)) {
                                pendingReturnToSeatSelection = true
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                            if (!url.isNullOrBlank()) {
                                currentUrl = url
                            }
                            // 仅在从统一身份认证返回 wslib 首页时执行一次性选座路由跳转
                            if (pendingReturnToSeatSelection &&
                                OfficialServiceRoutePolicy.isLibrarySeatIndexDocument(url)
                            ) {
                                pendingReturnToSeatSelection = false
                                view?.evaluateJavascript(
                                    OfficialWebScripts.LIBRARY_RETURN_TO_SEATS_SCRIPT,
                                    null
                                )
                            }
                            SchoolWebSessionManager.persistCookiesAsync()
                        }
                    }
                    loadUrl(OfficialServiceRoutePolicy.SEAT_BOOKING_ENTRY_URL)
                    webViewRef = this
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        // 底部 5 键辅助导航栏：后退、刷新、继续选座、浏览器、清会话
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    val wv = webViewRef
                    if (wv?.canGoBack() == true) {
                        wv.goBack()
                    } else {
                        onBack()
                    }
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("后退", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = { webViewRef?.reload() },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("刷新", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
            }

            FilledTonalButton(
                onClick = {
                    val wv = webViewRef ?: return@FilledTonalButton
                    if (OfficialServiceRoutePolicy.isLibrarySeatIndexDocument(wv.url)) {
                        wv.evaluateJavascript(
                            OfficialWebScripts.LIBRARY_FORCE_CONTINUE_SEATS_SCRIPT,
                            null
                        )
                    } else {
                        pendingReturnToSeatSelection = true
                        wv.loadUrl(OfficialServiceRoutePolicy.SEAT_BOOKING_ENTRY_URL)
                    }
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1.25f)
            ) {
                Text("继续选座", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = {
                    val target = currentUrl.takeIf {
                        OfficialServiceRoutePolicy.isAllowedMainFrameUrl(it)
                    } ?: OfficialServiceRoutePolicy.SEAT_BOOKING_ENTRY_URL
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("浏览器", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = { showClearSessionDialog = true },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("清会话", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    if (showClearSessionDialog) {
        AlertDialog(
            onDismissRequest = { showClearSessionDialog = false },
            title = { Text("清除学校网页登录会话") },
            text = {
                Text("将清除内嵌网页中的统一身份认证、选座及教务网页 Cookie 与本地网页缓存。本地已保存的课表、成绩与水电账号不受影响。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearSessionDialog = false
                        scope.launch {
                            SchoolWebSessionManager.clearAllSchoolWebSessions(webViewRef)
                            pendingReturnToSeatSelection = true
                            webViewRef?.loadUrl(OfficialServiceRoutePolicy.SEAT_BOOKING_ENTRY_URL)
                        }
                    }
                ) {
                    Text("确认清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearSessionDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
