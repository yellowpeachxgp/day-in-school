package cn.edu.haut.campus.core.campusservices

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * 图书馆移动馆藏（`libspace.haut.edu.cn`）事件驱动 + 有界退避快照适配器。
 *
 * 相比上游 800ms 无限 Handler 轮询的核心改进：
 * 1. 仅在 `onPageFinished`、`doUpdateVisitedHistory`（Vue Router SPA 跳页）以及用户触发检索/详情点击时启动有限次数的退避探测（`80ms..3000ms`），一旦进入稳定态立即停止，后台零空转。
 * 2. 维护单调递增的 [pageGeneration]，每次导航或关键词切换自动递增世代；点击书目时严格比对 `book.pageGeneration == currentGeneration`，防止异步翻页后打开错误图书。
 */
class OfficialLibraryWebAdapter(
    context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        val BACKOFF_DELAYS_MS = longArrayOf(80L, 160L, 320L, 600L, 1000L, 1500L, 2200L, 3000L)
        private const val SNAPSHOT_ASSET_NAME = "catalog-snapshot.js"
    }

    private val snapshotScript: String by lazy {
        context.applicationContext.assets.open(SNAPSHOT_ASSET_NAME).bufferedReader().use { it.readText() }
    }

    private val generationCounter = AtomicLong(1L)
    private var probeJob: Job? = null

    private val _snapshotState = MutableStateFlow<CatalogPageSnapshot>(CatalogPageSnapshot.Loading)
    val snapshotState: StateFlow<CatalogPageSnapshot> = _snapshotState.asStateFlow()

    private val _currentUrl = MutableStateFlow(OfficialServiceRoutePolicy.CATALOG_INDEX_URL)
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    val currentGeneration: Long
        get() = generationCounter.get()

    /**
     * 当路由发生改变或发起新检索时调用，递增世代并启动有界退避探测。
     */
    fun onNavigationOrActionTriggered(
        webView: WebView?,
        newUrl: String? = null,
        advanceGeneration: Boolean = true
    ) {
        if (newUrl != null && newUrl.isNotBlank()) {
            _currentUrl.value = newUrl
        }
        val gen = if (advanceGeneration) {
            generationCounter.incrementAndGet()
        } else {
            generationCounter.get()
        }
        val wv = webView ?: return

        probeJob?.cancel()
        probeJob = scope.launch(Dispatchers.Main.immediate) {
            for (waitMs in BACKOFF_DELAYS_MS) {
                delay(waitMs)
                if (!isActive || generationCounter.get() != gen) return@launch
                val snap = evaluateSnapshotOnce(wv, gen)
                _snapshotState.value = snap
                if (isTerminalSnapshot(snap)) {
                    SchoolWebSessionManager.persistCookiesAsync()
                    return@launch
                }
            }
        }
    }

    /**
     * 安全点击结果列表中的指定图书：若 `book.pageGeneration` 已过期则拒绝点击并刷新当前快照。
     */
    fun openBookFromResults(webView: WebView?, book: CatalogBook): Boolean {
        val wv = webView ?: return false
        if (book.pageGeneration != generationCounter.get()) {
            onNavigationOrActionTriggered(wv, advanceGeneration = false)
            return false
        }
        _snapshotState.value = CatalogPageSnapshot.Loading
        val nextGen = generationCounter.incrementAndGet()
        wv.evaluateJavascript(LibraryCatalogSnapshot.buildOpenBookDetailScript(book.index)) { raw ->
            val outcome = LibraryCatalogSnapshot.decodeEvaluateJsPayload(raw)
            if (outcome == "clicked") {
                onNavigationOrActionTriggered(wv, advanceGeneration = false)
            } else if (generationCounter.get() == nextGen) {
                onNavigationOrActionTriggered(wv, advanceGeneration = false)
            }
        }
        return true
    }

    fun dispose() {
        probeJob?.cancel()
        probeJob = null
    }

    private fun isTerminalSnapshot(snapshot: CatalogPageSnapshot): Boolean = when (snapshot) {
        is CatalogPageSnapshot.Home -> snapshot.recommendations.isNotEmpty()
        is CatalogPageSnapshot.Results -> !snapshot.loading && (snapshot.books.isNotEmpty() || snapshot.empty || snapshot.finished)
        is CatalogPageSnapshot.Detail -> snapshot.detail.title.isNotBlank()
        is CatalogPageSnapshot.OfficialFallback -> true
        is CatalogPageSnapshot.Loading -> false
    }

    private suspend fun evaluateSnapshotOnce(
        webView: WebView,
        generation: Long
    ): CatalogPageSnapshot = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(snapshotScript) { raw ->
            if (!cont.isActive) return@evaluateJavascript
            val decoded = LibraryCatalogSnapshot.decodeEvaluateJsPayload(raw)
            val parsed = if (decoded != null) {
                LibraryCatalogSnapshot.parseSnapshot(decoded, pageGeneration = generation)
            } else {
                CatalogPageSnapshot.Loading
            }
            cont.resume(parsed)
        }
    }
}

/**
 * 零 Cookie、白名单 HTTPS 馆藏图书封面加载器（内存 LRU 缓存 + 采样解码）。
 * 严禁携带任何 WebView Cookie，严禁加载非白名单域名或含 Query 参数的链接。
 */
object SafeCatalogCoverLoader {
    private const val MAX_COVER_BYTES = 1_500_000
    private val memoryCache = object : android.util.LruCache<String, Bitmap>(24) {}

    suspend fun loadCoverBitmap(rawCoverUrl: String?): Bitmap? {
        val safeUrl = OfficialServiceRoutePolicy.sanitizeCoverUrl(rawCoverUrl)
        if (safeUrl.isEmpty()) return null
        synchronized(memoryCache) {
            memoryCache.get(safeUrl)?.let { return it }
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(safeUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    useCaches = true
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    // 显式不附带 Cookie
                    setRequestProperty("Accept", "image/*")
                }
                try {
                    if (connection.responseCode !in 200..299) return@runCatching null
                    val bytes = connection.inputStream.use { input ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(4096)
                        while (true) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            if (buffer.size() + count > MAX_COVER_BYTES) return@runCatching null
                            buffer.write(chunk, 0, count)
                        }
                        buffer.toByteArray()
                    }
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.also { bmp ->
                        synchronized(memoryCache) {
                            memoryCache.put(safeUrl, bmp)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
    }
}
