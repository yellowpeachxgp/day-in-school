package cn.edu.haut.campus.feature.map.ui.components

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.location.HautOfficialGisClient

/**
 * 河南工业大学官方 GIS 网页端 (`https://gis.haut.edu.cn/cmipsh5/#/`) 内嵌全屏浏览弹窗。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OfficialGisWebViewDialog(
    onDismiss: () -> Unit,
    onOpenExternalBrowser: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = XPeachTheme.dialogSurfaceColor
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                XPeachTopBar(
                    title = "网页版校园地图",
                    subtitle = "gis.haut.edu.cn",
                    navigationIcon = {
                        XPeachIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            onClick = onDismiss
                        )
                    },
                    actions = {
                        XPeachFilterPill(
                            label = "浏览器打开",
                            selected = false,
                            onClick = onOpenExternalBrowser
                        )
                    }
                )
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.setGeolocationEnabled(true)
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.setSupportZoom(false)
                            settings.builtInZoomControls = false
                            settings.displayZoomControls = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            webChromeClient = object : WebChromeClient() {
                                override fun onGeolocationPermissionsShowPrompt(
                                    origin: String?,
                                    callback: GeolocationPermissions.Callback?
                                ) {
                                    val allowed = origin?.let {
                                        val host = runCatching { android.net.Uri.parse(it).host }
                                            .getOrNull()
                                            ?.lowercase()
                                        host == "haut.edu.cn" || host?.endsWith(".haut.edu.cn") == true
                                    } == true
                                    callback?.invoke(origin, allowed, false)
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val host = request?.url?.host?.lowercase().orEmpty()
                                    return !(host == "haut.edu.cn" || host.endsWith(".haut.edu.cn"))
                                }
                            }
                            loadUrl(HautOfficialGisClient.OFFICIAL_GIS_WEB_URL)
                        }
                    }
                )
            }
        }
    }
}
