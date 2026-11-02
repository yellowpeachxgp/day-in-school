/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.core.campusservices

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 馆藏书目摘要项（携带 [pageGeneration] 防翻页后误点旧快照）。
 */
data class CatalogBook(
    val index: Int,
    val title: String,
    val bibliography: String,
    val summary: String,
    val resources: String,
    val coverUrl: String,
    val pageGeneration: Long = 0L,
    val bibId: String = "",
    val isbn: String = "",
    val callNo: String = "",
    val availableCount: Int = 0,
    val totalCount: Int = 0,
    val inlineHoldings: List<CatalogHolding> = emptyList()
)

/**
 * 单册实体馆藏明细（原样展示索书号、条码、馆藏地与借阅状态）。
 */
data class CatalogHolding(
    val location: String,
    val lines: List<String>,
    val status: String,
    val shelf: String
) {
    val isAvailable: Boolean
        get() = status.trim() == "可借"
}

/**
 * 书目详情与馆藏列表。
 */
data class CatalogBookDetail(
    val title: String,
    val coverUrl: String,
    val bibliography: String,
    val description: String,
    val holdings: List<CatalogHolding>
) {
    val totalHoldingsCount: Int
        get() = holdings.size

    val availableHoldingsCount: Int
        get() = holdings.count { it.isAvailable }
}

/**
 * 图书馆移动馆藏页面 DOM 快照状态。
 */
sealed interface CatalogPageSnapshot {
    data class Home(
        val recommendations: List<String>
    ) : CatalogPageSnapshot

    data class Results(
        val books: List<CatalogBook>,
        val finished: Boolean,
        val empty: Boolean,
        val loading: Boolean,
        val pageGeneration: Long
    ) : CatalogPageSnapshot

    data class Detail(
        val detail: CatalogBookDetail
    ) : CatalogPageSnapshot

    data object Loading : CatalogPageSnapshot

    data class OfficialFallback(
        val reason: String = "当前页面已切换至官方馆藏视图"
    ) : CatalogPageSnapshot
}

/**
 * 图书馆馆藏检索 DOM 快照纯函数解析器。
 *
 * 边界与安全约束：
 * - 单次快照 JSON 最大长度 `1,000,000` 字符
 * - 单页书目最大数量 `200` 条
 * - 单书馆藏最大数量 `150` 条
 * - 所有封面 URL 强制通过 [OfficialServiceRoutePolicy.sanitizeCoverUrl] 清洗
 */
object LibraryCatalogSnapshot {

    const val MAX_JSON_LENGTH = 1_000_000
    const val MAX_BOOKS = 200
    const val MAX_HOLDINGS = 150

    /**
     * 解码 `WebView.evaluateJavascript` 返回的双层或单层 JSON 字符串。
     */
    fun decodeEvaluateJsPayload(rawResult: String?): String? {
        val text = rawResult?.trim().orEmpty()
        if (text.isEmpty() || text == "null" || text == "undefined" || text.length > MAX_JSON_LENGTH) {
            return null
        }
        return if (text.startsWith("\"")) {
            runCatching { JSONTokener(text).nextValue() as? String }.getOrNull()
        } else {
            text
        }
    }

    /**
     * 解析 `catalog-snapshot.js` 产出的 JSON 载荷。
     */
    fun parseSnapshot(
        rawJson: String,
        pageGeneration: Long = 0L
    ): CatalogPageSnapshot {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_JSON_LENGTH) {
            return CatalogPageSnapshot.OfficialFallback("馆藏页面返回数据超出限制，可切换至官方检索页查看")
        }

        return runCatching {
            val root = JSONObject(trimmed)
            when (root.optString("kind")) {
                "home" -> {
                    val recs = root.optJSONArray("recommendations").toStringList(maxItems = 8, maxLen = 100)
                    CatalogPageSnapshot.Home(recommendations = recs)
                }

                "results" -> {
                    val booksArray = root.optJSONArray("books") ?: JSONArray()
                    val count = booksArray.length().coerceAtMost(MAX_BOOKS)
                    val books = (0 until count).mapNotNull { idx ->
                        val item = booksArray.optJSONObject(idx) ?: return@mapNotNull null
                        val title = item.optString("title").cleanText(300)
                        if (title.isBlank()) return@mapNotNull null
                        val domIndex = item.optInt("index", idx)
                        if (domIndex !in 0 until MAX_BOOKS) return@mapNotNull null
                        CatalogBook(
                            index = domIndex,
                            title = title,
                            bibliography = item.optString("bibliography").cleanText(500),
                            summary = item.optString("summary").cleanText(500),
                            resources = item.optString("resources").cleanText(300),
                            coverUrl = OfficialServiceRoutePolicy.sanitizeCoverUrl(item.optString("coverUrl")),
                            pageGeneration = pageGeneration
                        )
                    }
                    CatalogPageSnapshot.Results(
                        books = books,
                        finished = root.optBoolean("finished"),
                        empty = root.optBoolean("empty"),
                        loading = root.optBoolean("loading"),
                        pageGeneration = pageGeneration
                    )
                }

                "detail" -> {
                    val title = root.optString("title").cleanText(300)
                    if (title.isBlank()) {
                        return@runCatching CatalogPageSnapshot.OfficialFallback("未识别到图书详情标题")
                    }
                    val holdingsArray = root.optJSONArray("holdings") ?: JSONArray()
                    val holdingsCount = holdingsArray.length().coerceAtMost(MAX_HOLDINGS)
                    val holdings = (0 until holdingsCount).mapNotNull { idx ->
                        val row = holdingsArray.optJSONObject(idx) ?: return@mapNotNull null
                        val location = row.optString("location").cleanText(200)
                        val lines = row.optJSONArray("lines").toStringList(maxItems = 8, maxLen = 200)
                        val status = row.optString("status").cleanText(80)
                        val shelf = row.optString("shelf").cleanText(200)
                        if (location.isBlank() && lines.isEmpty() && status.isBlank()) {
                            return@mapNotNull null
                        }
                        CatalogHolding(
                            location = location.ifBlank { "馆藏位置待确认" },
                            lines = lines,
                            status = status.ifBlank { "状态以馆内为准" },
                            shelf = shelf
                        )
                    }
                    CatalogPageSnapshot.Detail(
                        detail = CatalogBookDetail(
                            title = title,
                            coverUrl = OfficialServiceRoutePolicy.sanitizeCoverUrl(root.optString("coverUrl")),
                            bibliography = root.optString("bibliography").cleanMultiline(2000),
                            description = root.optString("description").cleanMultiline(10000),
                            holdings = holdings
                        )
                    )
                }

                "loading" -> CatalogPageSnapshot.Loading
                else -> CatalogPageSnapshot.OfficialFallback()
            }
        }.getOrElse {
            CatalogPageSnapshot.OfficialFallback("馆藏页面结构已更新，可切换至官方检索页继续查询")
        }
    }

    /**
     * 生成安全检索 URL：对关键词做长度截断与 URI 编码（对接汇文 OPAC `/mspace/searchList?strSearchType=...&strText=...`）。
     */
    fun buildSearchUrl(rawKeyword: String, searchField: String = "keyword"): String {
        val clean = rawKeyword.trim().take(80)
        require(clean.isNotEmpty()) { "请输入检索关键词" }
        val safeField = when (searchField) {
            "all", "keyword" -> "keyword"
            "titles", "authors", "isbns", "callno", "subjects", "publisher" -> searchField
            else -> "keyword"
        }
        val encoded = java.net.URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
        return "https://libspace.haut.edu.cn/mspace/searchList?strSearchType=$safeField&strText=$encoded"
    }

    /**
     * 生成馆藏详情页 URL。
     */
    fun buildDetailUrl(bibId: String): String {
        val safeBibId = bibId.trim().replace(Regex("[^A-Za-z0-9_-]"), "")
        return if (safeBibId.isNotEmpty()) {
            "https://libspace.haut.edu.cn/mspace/searchDetailLocal/$safeBibId"
        } else {
            OfficialServiceRoutePolicy.CATALOG_INDEX_URL
        }
    }

    /**
     * 生成在结果列表中点击指定索引书目的只读导航脚本。
     */
    fun buildOpenBookDetailScript(bookIndex: Int): String {
        require(bookIndex in 0 until MAX_BOOKS) { "书目索引超出有效范围" }
        return """
            (function() {
                if (location.origin !== 'https://libspace.haut.edu.cn' || location.pathname !== '/mspace/searchList') {
                    return 'stale_route';
                }
                const root = document.querySelector('.search-list-wrapper .book-list');
                const cards = root ? [...root.querySelectorAll('.van-list > .van-cell')].filter(x => x.querySelector('.book-info')) : [];
                const target = cards[$bookIndex];
                const clickNode = target?.querySelector('.book-info .title') || target?.querySelector('.book-info');
                if (!clickNode) return 'missing_node';
                clickNode.click();
                return 'clicked';
            })();
        """.trimIndent()
    }

    /**
     * 生成在结果列表底部分页加载更多的脚本。
     */
    const val LOAD_MORE_RESULTS_SCRIPT = """
        (function() {
            if (location.origin !== 'https://libspace.haut.edu.cn' || location.pathname !== '/mspace/searchList') return false;
            window.scrollTo(0, document.body.scrollHeight);
            return true;
        })();
    """

    private fun JSONArray?.toStringList(maxItems: Int, maxLen: Int): List<String> {
        if (this == null) return emptyList()
        val limit = length().coerceAtMost(maxItems)
        return (0 until limit)
            .map { optString(it).cleanText(maxLen) }
            .filter { it.isNotEmpty() }
    }

    private fun String.cleanText(maxLen: Int): String =
        replace(Regex("\\s+"), " ").trim().take(maxLen)

    private fun String.cleanMultiline(maxLen: Int): String =
        lines()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .take(maxLen)
}
