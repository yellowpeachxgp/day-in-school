/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.feature.settings

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.edu.haut.campus.core.campusservices.CatalogBook
import cn.edu.haut.campus.core.campusservices.CatalogBookDetail
import cn.edu.haut.campus.core.campusservices.HautLibraryOpacClient
import cn.edu.haut.campus.core.campusservices.LibraryCatalogSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySearchField
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LibraryCatalogScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var keywordInput by rememberSaveable { mutableStateOf("") }
    var selectedField by rememberSaveable { mutableStateOf(LibrarySearchField.ALL) }
    var showOfficialWebMode by rememberSaveable { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // 首页热搜词与热门借阅
    var hotKeywords by remember { mutableStateOf(HautLibraryOpacClient.DEFAULT_RECOMMENDED_KEYWORDS) }
    var hotBooks by remember { mutableStateOf<List<CatalogBook>>(emptyList()) }
    var isHomeLoading by remember { mutableStateOf(true) }

    // 检索结果状态
    var hasSearched by rememberSaveable { mutableStateOf(false) }
    var activeQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var currentPage by rememberSaveable { mutableIntStateOf(1) }
    var totalCount by rememberSaveable { mutableIntStateOf(0) }
    var hasMorePages by rememberSaveable { mutableStateOf(false) }
    var resultBooks by remember { mutableStateOf<List<CatalogBook>>(emptyList()) }

    // 图书详情状态
    var selectedBook by remember { mutableStateOf<CatalogBook?>(null) }
    var activeBookDetail by remember { mutableStateOf<CatalogBookDetail?>(null) }
    var isDetailLoading by remember { mutableStateOf(false) }

    var searchJob by remember { mutableStateOf<Job?>(null) }
    var detailJob by remember { mutableStateOf<Job?>(null) }

    // 首次进入加载全校实时热搜词与热门借阅榜单
    LaunchedEffect(Unit) {
        isHomeLoading = true
        val overview = HautLibraryOpacClient.fetchHomeOverview()
        if (overview.hotKeywords.isNotEmpty()) {
            hotKeywords = overview.hotKeywords
        }
        hotBooks = overview.hotBooks
        isHomeLoading = false
    }

    fun executeSearch(
        rawQuery: String = keywordInput,
        field: LibrarySearchField = selectedField,
        page: Int = 1
    ) {
        val clean = rawQuery.trim()
        if (clean.isEmpty()) return
        focusManager.clearFocus()
        keywordInput = clean
        selectedField = field
        selectedBook = null
        activeBookDetail = null

        if (showOfficialWebMode) {
            val url = LibraryCatalogSnapshot.buildSearchUrl(clean, field.fieldCode)
            webViewRef?.loadUrl(url)
        }

        searchJob?.cancel()
        searchJob = scope.launch {
            if (page == 1) {
                hasSearched = true
                activeQuery = clean
                isSearching = true
                searchError = null
                currentPage = 1
            } else {
                isLoadingMore = true
            }

            val res = HautLibraryOpacClient.searchBooks(
                keyword = clean,
                field = field,
                page = page,
                pageSize = 20
            )
            isSearching = false
            isLoadingMore = false

            res.onSuccess { pageData ->
                currentPage = pageData.page
                totalCount = pageData.totalCount
                hasMorePages = pageData.hasMore
                resultBooks = if (page == 1) {
                    pageData.books
                } else {
                    (resultBooks + pageData.books).distinctBy { it.bibId.ifBlank { "${it.index}_${it.title}" } }
                }
            }.onFailure { err ->
                if (page == 1) {
                    searchError = err.message ?: "检索失败，请检查网络连接或稍后重试"
                } else {
                    Toast.makeText(context, err.message ?: "加载更多失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun openBookDetail(book: CatalogBook) {
        focusManager.clearFocus()
        selectedBook = book
        // 立即展示列表中已附带的馆藏快照，同时后台拉取完整出版项与最新单册状态
        activeBookDetail = CatalogBookDetail(
            title = book.title,
            coverUrl = book.coverUrl,
            bibliography = book.bibliography,
            description = book.summary,
            holdings = book.inlineHoldings
        )
        if (showOfficialWebMode && book.bibId.isNotBlank()) {
            webViewRef?.loadUrl(LibraryCatalogSnapshot.buildDetailUrl(book.bibId))
        }
        detailJob?.cancel()
        detailJob = scope.launch {
            isDetailLoading = true
            HautLibraryOpacClient.fetchBookDetail(book).onSuccess { detail ->
                activeBookDetail = detail
            }
            isDetailLoading = false
        }
    }

    fun handleBackNavigation() {
        when {
            showOfficialWebMode && webViewRef?.canGoBack() == true -> {
                webViewRef?.goBack()
            }
            selectedBook != null -> {
                selectedBook = null
                activeBookDetail = null
            }
            hasSearched -> {
                hasSearched = false
                searchError = null
            }
            else -> onBack()
        }
    }

    BackHandler(enabled = selectedBook != null || hasSearched || (showOfficialWebMode && webViewRef?.canGoBack() == true)) {
        handleBackNavigation()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "图书馆馆藏检索",
            subtitle = "河南工业大学图书馆 · 莲花街 / 嵩山路馆藏",
            navigationIcon = {
                XPeachIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = ::handleBackNavigation
                )
            },
            actions = {
                XPeachFilterPill(
                    label = if (showOfficialWebMode) "原生极速视图" else "官方网页视图",
                    selected = showOfficialWebMode,
                    onClick = { showOfficialWebMode = !showOfficialWebMode }
                )
            }
        )

        // 顶部常驻搜索栏与字段切换胶囊
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
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
                    placeholder = {
                        Text(
                            text = selectedField.placeholder,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (keywordInput.isNotBlank()) {
                                executeSearch(keywordInput, selectedField, page = 1)
                            }
                        }
                    ),
                    trailingIcon = {
                        if (keywordInput.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    keywordInput = ""
                                    if (selectedBook == null) {
                                        hasSearched = false
                                        searchError = null
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "清空关键词"
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = keywordInput.isNotBlank() && !isSearching,
                    onClick = { executeSearch(keywordInput, selectedField, page = 1) },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("检索", maxLines = 1, softWrap = false)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (field in LibrarySearchField.entries) {
                    XPeachFilterPill(
                        label = field.label,
                        selected = selectedField == field,
                        onClick = {
                            selectedField = field
                            if (keywordInput.isNotBlank() && hasSearched) {
                                executeSearch(keywordInput, field, page = 1)
                            }
                        }
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (showOfficialWebMode) {
                val initialWebUrl = remember(selectedBook?.bibId, activeQuery, selectedField) {
                    val bookBibId = selectedBook?.bibId.orEmpty()
                    when {
                        bookBibId.isNotBlank() -> LibraryCatalogSnapshot.buildDetailUrl(bookBibId)
                        activeQuery.isNotBlank() -> LibraryCatalogSnapshot.buildSearchUrl(activeQuery, selectedField.fieldCode)
                        keywordInput.isNotBlank() -> LibraryCatalogSnapshot.buildSearchUrl(keywordInput, selectedField.fieldCode)
                        else -> OfficialServiceRoutePolicy.CATALOG_INDEX_URL
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            SchoolWebSessionManager.configureHardenedSchoolWebView(this)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    return SafeWebNavigationPolicy.shouldBlockNavigation(request, view)
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
                            }
                            loadUrl(initialWebUrl)
                            webViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val detailToShow = activeBookDetail
                    when {
                        selectedBook != null && detailToShow != null -> {
                            CatalogBookDetailView(
                                detail = detailToShow,
                                callNo = selectedBook?.callNo.orEmpty(),
                                isRefreshing = isDetailLoading,
                                onCopyCallNo = { callNo ->
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("索书号", callNo))
                                    Toast.makeText(context, "已复制索书号：$callNo", Toast.LENGTH_SHORT).show()
                                },
                                onBackToResults = {
                                    selectedBook = null
                                    activeBookDetail = null
                                }
                            )
                        }

                        isSearching -> {
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
                                        text = "正在检索「$activeQuery」馆藏书目与在馆状态...",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        searchError != null -> {
                            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = searchError!!,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { executeSearch(keywordInput, selectedField, page = 1) },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("重试检索")
                                    }
                                    FilledTonalButton(
                                        onClick = { showOfficialWebMode = true },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("打开官方网页视图")
                                    }
                                }
                            }
                        }

                        hasSearched -> {
                            if (resultBooks.isEmpty()) {
                                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "未找到与「$activeQuery」匹配的馆藏书目",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "建议切换检索字段为「全部」或缩短关键词重试。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "共找到 $totalCount 本相关馆藏 · 已显示 ${resultBooks.size} 本",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "点击查看索书号与书库位置",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                for (book in resultBooks) {
                                    CatalogBookItemCard(
                                        book = book,
                                        onClick = { openBookDetail(book) }
                                    )
                                }

                                if (hasMorePages) {
                                    OutlinedButton(
                                        enabled = !isLoadingMore,
                                        onClick = {
                                            executeSearch(activeQuery, selectedField, page = currentPage + 1)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        if (isLoadingMore) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("正在加载下一页...")
                                        } else {
                                            Text("加载更多结果（已加载 ${resultBooks.size} / $totalCount）")
                                        }
                                    }
                                }
                            }
                        }

                        else -> {
                            // 首页：热门检索词 + 热门借阅图书榜单
                            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "全校热门检索",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "点击任意关键词即可直接查询莲花街与嵩山路校区实时在馆状态",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    for (rec in hotKeywords) {
                                        XPeachFilterPill(
                                            label = rec,
                                            selected = false,
                                            onClick = {
                                                executeSearch(rec, LibrarySearchField.ALL, page = 1)
                                            }
                                        )
                                    }
                                }
                            }

                            if (isHomeLoading && hotBooks.isEmpty()) {
                                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            text = "正在同步馆藏热借榜单...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else if (hotBooks.isNotEmpty()) {
                                Text(
                                    text = "近期热门借阅图书",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                for (book in hotBooks) {
                                    CatalogBookItemCard(
                                        book = book,
                                        onClick = { openBookDetail(book) }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
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
    val hasAvailableCopies = book.availableCount > 0
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (book.summary.isNotBlank()) {
                    Text(
                        text = book.summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (book.resources.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        XPeachStatusBadge(
                            text = book.resources,
                            containerColor = if (hasAvailableCopies) {
                                Color(0xFFDCFCE7)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (hasAvailableCopies) {
                                Color(0xFF15803D)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogBookDetailView(
    detail: CatalogBookDetail,
    callNo: String,
    isRefreshing: Boolean,
    onCopyCallNo: (String) -> Unit,
    onBackToResults: () -> Unit
) {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "书目与馆藏明细",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            OutlinedButton(
                onClick = onBackToResults,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("返回列表")
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
                verticalArrangement = Arrangement.spacedBy(6.dp)
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val avail = detail.availableHoldingsCount
                    XPeachStatusBadge(
                        text = "可借 $avail / 馆藏 ${detail.totalHoldingsCount} 册",
                        containerColor = if (avail > 0) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (avail > 0) Color(0xFF15803D) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (callNo.isNotBlank()) {
                        XPeachFilterPill(
                            label = "复制索书号",
                            selected = false,
                            leadingIcon = Icons.Default.ContentCopy,
                            onClick = { onCopyCallNo(callNo) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "纸本单册馆藏分布 (${detail.holdings.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (detail.holdings.isEmpty()) {
            XPeachWell(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isRefreshing) "正在读取各校区书库在馆状态..." else "暂无纸本单册记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in detail.holdings) {
                    val isAvail = h.isAvailable
                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = h.location,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            XPeachStatusBadge(
                                text = h.status,
                                containerColor = if (isAvail) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isAvail) Color(0xFF15803D) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "内容简介与文献附注",
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
            .size(width = 64.dp, height = 88.dp)
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
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
