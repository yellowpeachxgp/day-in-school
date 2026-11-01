/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.feature.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.edu.haut.campus.core.campusservices.CatalogBook
import cn.edu.haut.campus.core.campusservices.CatalogBookDetail
import cn.edu.haut.campus.core.campusservices.CatalogPageSnapshot
import cn.edu.haut.campus.core.campusservices.LibraryCatalogSnapshot
import cn.edu.haut.campus.core.campusservices.OfficialLibraryWebAdapter
import cn.edu.haut.campus.core.campusservices.OfficialServiceRoutePolicy
import cn.edu.haut.campus.core.campusservices.SafeCatalogCoverLoader
import cn.edu.haut.campus.core.campusservices.SafeWebNavigationPolicy
import cn.edu.haut.campus.core.campusservices.SchoolWebSessionManager
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LibraryCatalogScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adapter = remember { OfficialLibraryWebAdapter(context, scope) }
    val snapshot by adapter.snapshotState.collectAsState()

    var keywordInput by remember { mutableStateOf("") }
    var showOfficialWebMode by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(adapter) {
        onDispose {
            adapter.dispose()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "图书馆馆藏检索",
            subtitle = "河南工业大学图书馆移动馆藏",
            navigationIcon = {
                XPeachIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = {
                        val wv = webViewRef
                        if (snapshot is CatalogPageSnapshot.Detail && wv?.canGoBack() == true) {
                            wv.goBack()
                            adapter.onNavigationOrActionTriggered(wv)
                        } else {
                            onBack()
                        }
                    }
                )
            },
            actions = {
                XPeachFilterPill(
                    label = if (showOfficialWebMode) "原生视图" else "官方检索页",
                    selected = showOfficialWebMode,
                    onClick = { showOfficialWebMode = !showOfficialWebMode }
                )
            }
        )

        // 搜索栏（始终显示在顶部，方便随时检索）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = keywordInput,
                    onValueChange = { keywordInput = it },
                    placeholder = { Text("输入书名、作者或 ISBN") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = keywordInput.isNotBlank(),
                    onClick = {
                        val url = LibraryCatalogSnapshot.buildSearchUrl(keywordInput)
                        webViewRef?.loadUrl(url)
                        adapter.onNavigationOrActionTriggered(webViewRef, newUrl = url)
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("检索")
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 受控官方馆藏 WebView（原生模式下隐藏在底部 1dp 尺寸，切换到「官方检索页」时全屏展示）
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        SchoolWebSessionManager.configureHardenedSchoolWebView(this)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                return SafeWebNavigationPolicy.shouldBlockNavigation(request)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                return SafeWebNavigationPolicy.interceptSubresourceIfBlocked(
                                    request = request,
                                    allowCatalogCovers = true
                                )
                            }

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?
                            ) {
                                SafeWebNavigationPolicy.cancelOnSslError(handler, error)
                            }

                            override fun doUpdateVisitedHistory(
                                view: WebView?,
                                url: String?,
                                isReload: Boolean
                            ) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                adapter.onNavigationOrActionTriggered(view, newUrl = url)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                adapter.onNavigationOrActionTriggered(
                                    view,
                                    newUrl = url,
                                    advanceGeneration = false
                                )
                            }
                        }
                        loadUrl(OfficialServiceRoutePolicy.CATALOG_INDEX_URL)
                        webViewRef = this
                        adapter.onNavigationOrActionTriggered(
                            this,
                            newUrl = OfficialServiceRoutePolicy.CATALOG_INDEX_URL
                        )
                    }
                },
                modifier = if (showOfficialWebMode) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .size(1.dp)
                        .alpha(0f)
                }
            )

            if (!showOfficialWebMode) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (val current = snapshot) {
                        is CatalogPageSnapshot.Home -> {
                            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "热门借阅与检索推荐",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                if (current.recommendations.isEmpty()) {
                                    Text(
                                        text = "输入书名、作者或关键词即可检索莲花街与嵩山路校区馆藏。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        for (rec in current.recommendations) {
                                            XPeachFilterPill(
                                                label = rec,
                                                selected = false,
                                                onClick = {
                                                    keywordInput = rec
                                                    val url = LibraryCatalogSnapshot.buildSearchUrl(rec)
                                                    webViewRef?.loadUrl(url)
                                                    adapter.onNavigationOrActionTriggered(webViewRef, newUrl = url)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        is CatalogPageSnapshot.Loading -> {
                            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = "正在同步馆藏页面数据...",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        is CatalogPageSnapshot.Results -> {
                            if (current.empty || (current.books.isEmpty() && !current.loading)) {
                                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "未找到匹配的馆藏书目",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "可尝试缩短关键词，或切换至「官方检索页」使用高级检索。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(
                                    text = "已加载 ${current.books.size} 条书目 · 点击卡片查看索书号与在馆状态",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                for (book in current.books) {
                                    CatalogBookItemCard(
                                        book = book,
                                        onClick = {
                                            adapter.openBookFromResults(webViewRef, book)
                                        }
                                    )
                                }
                                if (!current.finished) {
                                    OutlinedButton(
                                        onClick = {
                                            webViewRef?.evaluateJavascript(
                                                LibraryCatalogSnapshot.LOAD_MORE_RESULTS_SCRIPT,
                                                null
                                            )
                                            adapter.onNavigationOrActionTriggered(
                                                webViewRef,
                                                advanceGeneration = false
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(if (current.loading) "正在加载更多..." else "加载更多结果")
                                    }
                                }
                            }
                        }

                        is CatalogPageSnapshot.Detail -> {
                            CatalogBookDetailView(
                                detail = current.detail,
                                onBackToResults = {
                                    val wv = webViewRef
                                    if (wv?.canGoBack() == true) {
                                        wv.goBack()
                                        adapter.onNavigationOrActionTriggered(wv)
                                    }
                                }
                            )
                        }

                        is CatalogPageSnapshot.OfficialFallback -> {
                            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = current.reason,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                FilledTonalButton(
                                    onClick = { showOfficialWebMode = true },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("打开官方检索页")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogBookItemCard(
    book: CatalogBook,
    onClick: () -> Unit
) {
    XPeachCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            SafeCatalogCoverBox(coverUrl = book.coverUrl)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (book.bibliography.isNotBlank()) {
                    Text(
                        text = book.bibliography,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (book.summary.isNotBlank()) {
                    Text(
                        text = book.summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (book.resources.isNotBlank()) {
                    XPeachStatusBadge(text = book.resources)
                }
            }
        }
    }
}

@Composable
private fun CatalogBookDetailView(
    detail: CatalogBookDetail,
    onBackToResults: () -> Unit
) {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "书目与馆藏明细",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedButton(
                onClick = onBackToResults,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("返回检索结果")
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            SafeCatalogCoverBox(coverUrl = detail.coverUrl)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (detail.bibliography.isNotBlank()) {
                    Text(
                        text = detail.bibliography,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                XPeachStatusBadge(
                    text = "可借 ${detail.availableHoldingsCount} / 馆藏 ${detail.totalHoldingsCount} 册"
                )
            }
        }

        if (detail.holdings.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "纸本馆藏分布",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in detail.holdings) {
                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = h.location,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            XPeachStatusBadge(text = h.status)
                        }
                        if (h.lines.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = h.lines.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (h.shelf.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = h.shelf,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        if (detail.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "文献信息",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = detail.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SafeCatalogCoverBox(coverUrl: String) {
    var bitmap by remember(coverUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(coverUrl) {
        bitmap = SafeCatalogCoverLoader.loadCoverBitmap(coverUrl)
    }

    Box(
        modifier = Modifier
            .size(width = 60.dp, height = 84.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
