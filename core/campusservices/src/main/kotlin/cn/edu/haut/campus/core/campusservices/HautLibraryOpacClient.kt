package cn.edu.haut.campus.core.campusservices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * 馆藏检索字段枚举（严格对应汇文 OPAC `/meta-local/opac/search/search_confs/m` 定义）。
 */
enum class LibrarySearchField(
    val fieldCode: String,
    val operator: String,
    val label: String,
    val placeholder: String
) {
    ALL("all", "*", "全部", "输入书名、作者、ISBN 或主题词"),
    TITLE("titles", "*", "书名", "输入图书正题名或副题名"),
    AUTHOR("authors", "*", "作者", "输入著者、编者或译者姓名"),
    ISBN("isbns", "=", "ISBN", "输入标准书号（如 978-7-115-57666-8）"),
    CALL_NO("callno", "<=", "索书号", "输入索书号前缀（如 TP311）"),
    SUBJECT("subjects", "*", "主题词", "输入学科或主题关键词")
}

/**
 * 馆藏检索首页聚合数据（实时热搜词 + 热门借阅图书榜单）。
 */
data class LibraryHomeOverview(
    val hotKeywords: List<String>,
    val hotBooks: List<CatalogBook>
)

/**
 * 馆藏分页检索响应。
 */
data class LibrarySearchPageResult(
    val keyword: String,
    val field: LibrarySearchField,
    val page: Int,
    val pageSize: Int,
    val totalCount: Int,
    val books: List<CatalogBook>,
    val hasMore: Boolean
)

/**
 * 河南工业大学图书馆移动馆藏（`libspace.haut.edu.cn`）原生 HTTPS JSON 客户端。
 *
 * 直接对接汇文 OPAC `/meta-local/opac/` 公开只读接口：
 * - 零 Cookie、零账号凭据、强制 HTTPS 与官方域名校验；
 * - 针对 `libspace.haut.edu.cn` 权威 DNS 存在不可达 IPv6 (`2001:250:4803::31`) 导致 Android 默认
 *   `HttpURLConnection` 阻塞超时且不回退 IPv4 的问题，采用 IPv4 优先 + 严格 TLS SNI 与系统证书主机名校验；
 * - 毫秒级返回书目列表、实时在馆/可借册数、单册条码级馆藏位置及封面图。
 */
object HautLibraryOpacClient {

    private const val OPAC_BASE_URL = "https://libspace.haut.edu.cn/meta-local/opac"
    private const val TIMEOUT_MS = 8_000
    private const val COVERS_TIMEOUT_MS = 3_500
    private const val TCP_CONNECT_TIMEOUT_MS = 4_000
    private const val MAX_RESPONSE_CHARS = 1_500_000
    private const val MAX_RESPONSE_BYTES = 3 * 1024 * 1024
    private const val OPAC_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val HAUT_GATEWAY_IPV4_BYTES = byteArrayOf(
        202.toByte(),
        196.toByte(),
        16.toByte(),
        31.toByte()
    )

    val DEFAULT_RECOMMENDED_KEYWORDS = listOf(
        "数据结构",
        "高等数学",
        "Python",
        "算法导论",
        "深入理解计算机系统",
        "人工智能",
        "粮食储藏",
        "机械设计"
    )

    /**
     * 获取馆藏首页热搜趋势词与热门借阅图书。
     */
    suspend fun fetchHomeOverview(): LibraryHomeOverview = withContext(Dispatchers.IO) {
        coroutineScope {
            val trendDeferred = async {
                runCatching {
                    val raw = getJson("$OPAC_BASE_URL/commend/top_search_trend?size=10&range=30")
                    parseTopSearchTrends(raw)
                }.getOrDefault(emptyList())
            }
            val hotBooksDeferred = async {
                runCatching {
                    val raw = getJson("$OPAC_BASE_URL/commend/hot1?count=8")
                    val books = parseHotBooks(raw)
                    withTimeoutOrNull(COVERS_TIMEOUT_MS.toLong()) {
                        runCatching { enrichBooksWithCovers(books) }.getOrNull()
                    } ?: books
                }.getOrDefault(emptyList())
            }
            val trends = trendDeferred.await().ifEmpty { DEFAULT_RECOMMENDED_KEYWORDS }
            val hotBooks = hotBooksDeferred.await()
            LibraryHomeOverview(
                hotKeywords = trends,
                hotBooks = hotBooks
            )
        }
    }

    /**
     * 按关键词与字段执行馆藏分页检索。
     */
    suspend fun searchBooks(
        keyword: String,
        field: LibrarySearchField = LibrarySearchField.ALL,
        page: Int = 1,
        pageSize: Int = 20,
        pageGeneration: Long = 1L
    ): Result<LibrarySearchPageResult> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedKeyword = normalizeSearchKeyword(keyword, field)
            require(normalizedKeyword.isNotEmpty()) { "请输入检索关键词" }
            val safePage = page.coerceAtLeast(1)
            val safePageSize = pageSize.coerceIn(1, 50)

            var parsed = executeSingleSearchPage(
                keyword = normalizedKeyword,
                field = field,
                page = safePage,
                pageSize = safePageSize,
                pageGeneration = pageGeneration
            )

            // 首页结果为空时的智能容错回退（如带横杠/无横杠 ISBN 互转，或特定字段无命中时回退全字段检索）
            if (safePage == 1 && parsed.books.isEmpty()) {
                if (field == LibrarySearchField.ISBN && normalizedKeyword.contains('-')) {
                    val compactIsbn = normalizedKeyword.replace("-", "").trim()
                    if (compactIsbn.isNotEmpty() && compactIsbn != normalizedKeyword) {
                        val retryIsbn = runCatching {
                            executeSingleSearchPage(
                                keyword = compactIsbn,
                                field = LibrarySearchField.ISBN,
                                page = 1,
                                pageSize = safePageSize,
                                pageGeneration = pageGeneration
                            )
                        }.getOrNull()
                        if (retryIsbn != null && retryIsbn.books.isNotEmpty()) {
                            parsed = retryIsbn
                        }
                    }
                }
                if (parsed.books.isEmpty() && field != LibrarySearchField.ALL) {
                    val fallbackKeyword = normalizeSearchKeyword(keyword, LibrarySearchField.ALL)
                    if (fallbackKeyword.isNotEmpty()) {
                        val fallbackAll = runCatching {
                            executeSingleSearchPage(
                                keyword = fallbackKeyword,
                                field = LibrarySearchField.ALL,
                                page = 1,
                                pageSize = safePageSize,
                                pageGeneration = pageGeneration
                            )
                        }.getOrNull()
                        if (fallbackAll != null && fallbackAll.books.isNotEmpty()) {
                            parsed = fallbackAll
                        }
                    }
                }
            }

            val enrichedBooks = withTimeoutOrNull(COVERS_TIMEOUT_MS.toLong()) {
                runCatching { enrichBooksWithCovers(parsed.books) }.getOrNull()
            } ?: parsed.books

            parsed.copy(books = enrichedBooks)
        }
    }

    private fun executeSingleSearchPage(
        keyword: String,
        field: LibrarySearchField,
        page: Int,
        pageSize: Int,
        pageGeneration: Long
    ): LibrarySearchPageResult {
        val requestJson = buildSearchRequestPayload(
            keyword = keyword,
            field = field,
            page = page,
            pageSize = pageSize
        )
        val rawResponse = postJson("$OPAC_BASE_URL/search/", requestJson)
        return parseSearchResponse(
            rawJson = rawResponse,
            keyword = keyword,
            field = field,
            page = page,
            pageSize = pageSize,
            pageGeneration = pageGeneration
        )
    }

    /**
     * 规范化用户输入的检索词（去除书名号包裹、ISBN/索书号前缀与多余空白）。
     */
    internal fun normalizeSearchKeyword(rawKeyword: String, field: LibrarySearchField): String {
        var text = rawKeyword.trim()
        if (text.length >= 3 && text.startsWith("《") && text.endsWith("》") && !text.substring(1, text.length - 1).contains("《")) {
            text = text.substring(1, text.length - 1).trim()
        }
        when (field) {
            LibrarySearchField.ISBN -> {
                text = text
                    .replace(Regex("^(?:ISBN|isbn)\\s*[:：]?\\s*"), "")
                    .replace(Regex("\\s+"), "")
            }
            LibrarySearchField.CALL_NO -> {
                text = text
                    .replace(Regex("^(?:索书号|分类号)\\s*[:：]?\\s*"), "")
                    .trim()
                    .uppercase(Locale.ROOT)
            }
            else -> {
                text = text.replace(Regex("\\s+"), " ").trim()
            }
        }
        return text.take(80)
    }

    /**
     * 获取单本图书的完整书目详情与单册馆藏分布。
     */
    suspend fun fetchBookDetail(book: CatalogBook): Result<CatalogBookDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val safeBibId = book.bibId.trim().replace(Regex("[^A-Za-z0-9_-]"), "")
            if (safeBibId.isEmpty()) {
                return@runCatching CatalogBookDetail(
                    title = book.title,
                    coverUrl = book.coverUrl,
                    bibliography = book.bibliography,
                    description = book.summary,
                    holdings = book.inlineHoldings,
                    callNo = book.callNo
                )
            }

            coroutineScope {
                val infoDeferred = async {
                    runCatching {
                        val encId = URLEncoder.encode(safeBibId, "UTF-8")
                        getJson("$OPAC_BASE_URL/bibs/$encId/infos?detail=true&isMobile=1")
                    }.getOrNull()
                }
                val holdingsDeferred = async {
                    runCatching {
                        val encId = URLEncoder.encode(safeBibId, "UTF-8")
                        getJson("$OPAC_BASE_URL/bibs/$encId/holdings?isMobile=1&relateStat=1")
                    }.getOrNull()
                }

                val infoRaw = infoDeferred.await()
                val holdingsRaw = holdingsDeferred.await()
                parseBookDetailPayloads(
                    fallbackBook = book,
                    infoJson = infoRaw,
                    holdingsJson = holdingsRaw
                )
            }
        }
    }

    internal fun buildSearchRequestPayload(
        keyword: String,
        field: LibrarySearchField,
        page: Int,
        pageSize: Int
    ): String {
        val queryItem = JSONObject()
            .put("logic", 0)
            .put("field", field.fieldCode)
            .put("values", JSONArray().put(keyword))
            .put("operator", field.operator)

        return JSONObject()
            .put("page", page)
            .put("pageSize", pageSize)
            .put("indexName", "idx.opac")
            .put("sortField", "relevance")
            .put("sortType", "desc")
            .put("collapseField", "groupId")
            .put("queryFieldList", JSONArray().put(queryItem))
            .put("filterFieldList", JSONArray())
            .toString()
    }

    internal fun parseTopSearchTrends(rawJson: String): List<String> {
        val root = JSONObject(rawJson)
        if (root.optInt("code", -1) != 0) return emptyList()
        val arr = root.optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until arr.length().coerceAtMost(12)) {
            val item = arr.optJSONObject(i) ?: continue
            val word = item.optCleanString("_1").cleanSingleLine(60)
            if (word.isNotBlank() && word !in result) {
                result.add(word)
            }
        }
        return result
    }

    internal fun parseHotBooks(rawJson: String): List<CatalogBook> {
        val root = JSONObject(rawJson)
        if (root.optInt("code", -1) != 0) return emptyList()
        val arr = root.optJSONArray("data") ?: return emptyList()
        val books = mutableListOf<CatalogBook>()
        for (i in 0 until arr.length().coerceAtMost(10)) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optCleanString("title").stripHtmlAndClean(200)
            if (title.isBlank()) continue
            val bibId = item.optCleanString("bibId").cleanSingleLine(80)
            val author = item.optCleanString("author").stripHtmlAndClean(150)
            val publisher = item.optCleanString("publisher").stripHtmlAndClean(120)
            val pubYear = item.optCleanString("pub_year").cleanSingleLine(40)
            val callNo = item.optCleanString("callno").cleanSingleLine(80)
            val isbn = item.optCleanString("isbn").cleanSingleLine(40)
            val hotValue = item.optInt("hotValue", 0)

            val bibParts = listOfNotNull(
                author.takeIf { it.isNotBlank() },
                publisher.takeIf { it.isNotBlank() },
                pubYear.takeIf { it.isNotBlank() }
            )
            val summaryParts = listOfNotNull(
                callNo.takeIf { it.isNotBlank() }?.let { "索书号：$it" },
                isbn.takeIf { it.isNotBlank() }?.let { "ISBN：$it" }
            )

            books.add(
                CatalogBook(
                    index = i,
                    title = title,
                    bibliography = bibParts.joinToString(" · "),
                    summary = summaryParts.joinToString(" · "),
                    resources = if (hotValue > 0) "近期热门借阅 ($hotValue 次)" else "热门馆藏推荐",
                    coverUrl = "",
                    pageGeneration = 0L,
                    bibId = bibId,
                    isbn = isbn,
                    callNo = callNo
                )
            )
        }
        return books
    }

    internal fun parseSearchResponse(
        rawJson: String,
        keyword: String,
        field: LibrarySearchField,
        page: Int,
        pageSize: Int,
        pageGeneration: Long = 1L
    ): LibrarySearchPageResult {
        val root = JSONObject(rawJson)
        val code = root.optInt("code", -1)
        check(code == 0) {
            val msg = root.optCleanString("msg")
            msg.ifBlank { "馆藏检索服务暂时繁忙，请稍后重试" }
        }

        val data = root.optJSONObject("data") ?: JSONObject()
        val total = data.optInt("total", 0)
        val dataList = data.optJSONArray("dataList") ?: JSONArray()
        val count = dataList.length().coerceAtMost(LibraryCatalogSnapshot.MAX_BOOKS)

        val books = (0 until count).mapNotNull { idx ->
            val item = dataList.optJSONObject(idx) ?: return@mapNotNull null
            val title = item.optCleanString("title").stripHtmlAndClean(300)
            if (title.isBlank()) return@mapNotNull null

            val bibId = item.optCleanString("bibId").cleanSingleLine(80)
            val author = item.optCleanString("author").stripHtmlAndClean(200)
            val publisher = item.optCleanString("publisher").stripHtmlAndClean(150)
            val pubYear = item.optCleanString("pub_year").cleanSingleLine(40)
            val isbn = item.optCleanString("isbn").cleanSingleLine(60)
            val docTypeDesc = item.optCleanString("docTypeDesc").cleanSingleLine(40)
            val abstractText = item.optCleanString("abstract").stripHtmlAndClean(500)

            val inlineHoldings = parseHoldingsArrayString(item.optCleanString("holdings"))
            val callNo = item.optJSONArray("callno")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    if (arr.isNull(i)) null else arr.optString(i, "").cleanNullLiteral("").cleanSingleLine(80).takeIf { it.isNotBlank() }
                }.firstOrNull()
            } ?: item.optCleanString("callno").cleanSingleLine(80).takeIf { !it.startsWith("[") }
                ?: inlineHoldings.firstNotNullOfOrNull { h ->
                    h.lines.firstOrNull { it.startsWith("索书号：") }?.removePrefix("索书号：")?.trim()?.takeIf { it.isNotBlank() }
                }.orEmpty()

            val itemCount = item.optInt("itemCount", inlineHoldings.size).coerceAtLeast(inlineHoldings.size)
            val circCountFromApi = item.optInt("circCount", -1)
            val availableCount = if (circCountFromApi >= 0) {
                circCountFromApi
            } else {
                inlineHoldings.count { it.isAvailable }
            }

            val bibliographyParts = listOfNotNull(
                author.takeIf { it.isNotBlank() },
                publisher.takeIf { it.isNotBlank() },
                pubYear.takeIf { it.isNotBlank() }
            )

            val locationPreview = inlineHoldings
                .map { it.location }
                .distinct()
                .take(2)
                .joinToString(" / ")

            val summaryParts = listOfNotNull(
                callNo.takeIf { it.isNotBlank() }?.let { "索书号：$it" },
                locationPreview.takeIf { it.isNotBlank() },
                abstractText.takeIf { it.isNotBlank() }
            )

            val resourceBadge = buildString {
                if (docTypeDesc.isNotBlank()) {
                    append(docTypeDesc)
                    append(" · ")
                }
                append("可借 $availableCount / 馆藏 $itemCount 册")
            }

            val rawThumb = item.optCleanString("thumb")
            CatalogBook(
                index = (page - 1) * pageSize + idx,
                title = title,
                bibliography = bibliographyParts.joinToString(" · "),
                summary = summaryParts.joinToString(" · ").take(360),
                resources = resourceBadge,
                coverUrl = OfficialServiceRoutePolicy.sanitizeCoverUrl(rawThumb),
                pageGeneration = pageGeneration,
                bibId = bibId,
                isbn = isbn,
                callNo = callNo,
                availableCount = availableCount,
                totalCount = itemCount,
                inlineHoldings = inlineHoldings
            )
        }

        val loadedSoFar = (page - 1) * pageSize + books.size
        val hasMore = books.isNotEmpty() && loadedSoFar < total

        return LibrarySearchPageResult(
            keyword = keyword,
            field = field,
            page = page,
            pageSize = pageSize,
            totalCount = total,
            books = books,
            hasMore = hasMore
        )
    }

    internal fun parseBookDetailPayloads(
        fallbackBook: CatalogBook,
        infoJson: String?,
        holdingsJson: String?
    ): CatalogBookDetail {
        var title = fallbackBook.title
        var resolvedCallNo = fallbackBook.callNo
        val bibLines = mutableListOf<String>()
        val descLines = mutableListOf<String>()

        if (!infoJson.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(infoJson)
                val mapObj = root.optJSONObject("data")?.optJSONObject("map")
                val baseMap = mapObj?.optJSONObject("baseInfo")?.optJSONObject("map")
                val detailMap = mapObj?.optJSONObject("detailInfo")?.optJSONObject("map")

                val remoteTitle = baseMap?.optCleanString("title")?.stripHtmlAndClean(300).orEmpty()
                if (remoteTitle.isNotBlank()) {
                    title = remoteTitle
                }

                val callNoArr = baseMap?.optJSONArray("callno")
                val remoteCallNo = callNoArr?.let { arr ->
                    (0 until arr.length()).mapNotNull { idx ->
                        if (arr.isNull(idx)) null else arr.optString(idx, "").cleanNullLiteral("").cleanSingleLine(80).takeIf { it.isNotBlank() }
                    }.firstOrNull()
                }
                if (!remoteCallNo.isNullOrBlank()) {
                    resolvedCallNo = remoteCallNo
                }
                if (resolvedCallNo.isNotBlank()) {
                    bibLines.add("索书号：$resolvedCallNo")
                }

                if (detailMap != null) {
                    val primaryKeys = listOf("题名/责任者", "版本说明", "出版发行项", "ISBN及定价", "载体形态项", "丛编项", "学科主题")
                    for (key in primaryKeys) {
                        val v = detailMap.optCleanString(key).stripHtmlAndClean(400)
                        if (v.isNotBlank()) {
                            bibLines.add("$key：$v")
                        }
                    }
                    val noteKeys = listOf("提要文摘附注", "内容附注", "责任者附注", "一般附注")
                    for (key in noteKeys) {
                        val v = detailMap.optCleanString(key).stripHtmlAndClean(4000)
                        if (v.isNotBlank() && v !in descLines) {
                            descLines.add(v)
                        }
                    }
                }
            }
        }

        val parsedHoldings = if (!holdingsJson.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(holdingsJson)
                val holdingsArrayStr = root.optJSONObject("data")?.optCleanString("holdings").orEmpty()
                parseHoldingsArrayString(holdingsArrayStr)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val finalHoldings = parsedHoldings.ifEmpty { fallbackBook.inlineHoldings }
        if (resolvedCallNo.isBlank()) {
            resolvedCallNo = finalHoldings.firstNotNullOfOrNull { h ->
                h.lines.firstOrNull { it.startsWith("索书号：") }?.removePrefix("索书号：")?.trim()?.takeIf { it.isNotBlank() }
            }.orEmpty()
        }

        if (bibLines.isEmpty()) {
            if (fallbackBook.bibliography.isNotBlank()) {
                bibLines.add(fallbackBook.bibliography)
            }
            if (resolvedCallNo.isNotBlank()) {
                bibLines.add("索书号：$resolvedCallNo")
            }
            if (fallbackBook.isbn.isNotBlank()) {
                bibLines.add("ISBN：${fallbackBook.isbn}")
            }
        }

        if (descLines.isEmpty() && fallbackBook.summary.isNotBlank()) {
            descLines.add(fallbackBook.summary)
        }

        return CatalogBookDetail(
            title = title,
            coverUrl = fallbackBook.coverUrl,
            bibliography = bibLines.joinToString("\n"),
            description = descLines.joinToString("\n\n"),
            holdings = finalHoldings,
            callNo = resolvedCallNo
        )
    }

    internal fun parseHoldingsArrayString(rawHoldingsJson: String?): List<CatalogHolding> {
        val trimmed = rawHoldingsJson?.cleanNullLiteral("")?.trim().orEmpty()
        if (trimmed.isEmpty() || !trimmed.startsWith("[")) return emptyList()
        return runCatching {
            val arr = JSONArray(trimmed)
            val count = arr.length().coerceAtMost(LibraryCatalogSnapshot.MAX_HOLDINGS)
            (0 until count).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                val permLocation = obj.optCleanString("location").cleanSingleLine(150)
                val tempLocation = obj.optCleanString("tempLocation").cleanSingleLine(150)
                val displayLocation = when {
                    tempLocation.isNotBlank() && permLocation.isNotBlank() && tempLocation != permLocation ->
                        "$tempLocation（原属：$permLocation）"
                    tempLocation.isNotBlank() -> tempLocation
                    permLocation.isNotBlank() -> permLocation
                    else -> obj.optCleanString("locationName").cleanSingleLine(150).ifBlank { "馆藏位置待确认" }
                }

                val callNo = obj.optCleanString("callNo").cleanSingleLine(80)
                val barCode = obj.optCleanString("barCode").cleanSingleLine(60)
                val dueDate = obj.optCleanString("dueDate").cleanSingleLine(40)
                val vol = obj.optCleanString("vol").cleanSingleLine(40)
                val status = obj.optCleanString("status").cleanSingleLine(60).ifBlank { "状态以馆内为准" }
                val shelfMark = obj.optCleanString("shelfMark").cleanSingleLine(120)

                val lines = buildList {
                    if (callNo.isNotBlank()) add("索书号：$callNo")
                    if (barCode.isNotBlank()) add("条码号：$barCode")
                    if (vol.isNotBlank()) add("卷期：$vol")
                    if (dueDate.isNotBlank()) add("应还日期：$dueDate")
                }

                CatalogHolding(
                    location = displayLocation,
                    lines = lines,
                    status = status,
                    shelf = if (shelfMark.isNotBlank()) "架位：$shelfMark" else ""
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun enrichBooksWithCovers(books: List<CatalogBook>): List<CatalogBook> {
        val booksNeedingCovers = books.filter { it.coverUrl.isBlank() && (it.isbn.isNotBlank() || it.bibId.isNotBlank()) }
        if (booksNeedingCovers.isEmpty()) return books

        val payload = JSONObject()
            .put("isbns", JSONArray(booksNeedingCovers.map { it.isbn }))
            .put("bibIds", JSONArray(booksNeedingCovers.map { it.bibId }))
            .toString()

        val rawExtend = runCatching {
            postJson("$OPAC_BASE_URL/search/extend3?coversOnly=true", payload, timeoutMs = COVERS_TIMEOUT_MS)
        }.getOrNull() ?: return books

        val coverMap = parseExtend3CoverMap(rawExtend)
        if (coverMap.isEmpty()) return books

        return books.map { book ->
            val foundCover = coverMap[book.bibId]
            if (book.coverUrl.isBlank() && !foundCover.isNullOrBlank()) {
                book.copy(coverUrl = foundCover)
            } else {
                book
            }
        }
    }

    internal fun parseExtend3CoverMap(rawJson: String): Map<String, String> {
        return runCatching {
            val root = JSONObject(rawJson)
            if (root.optInt("code", -1) != 0) return emptyMap()
            val data = root.optJSONObject("data") ?: return emptyMap()
            val result = mutableMapOf<String, String>()
            val keys = data.keys()
            while (keys.hasNext()) {
                val bibId = keys.next()
                val arr = data.optJSONArray(bibId) ?: continue
                for (i in 0 until arr.length()) {
                    val rawUrl = arr.optJSONObject(i)?.optCleanString("imageUrl").orEmpty()
                    val safeUrl = OfficialServiceRoutePolicy.sanitizeCoverUrl(rawUrl)
                    if (safeUrl.isNotBlank()) {
                        result[bibId] = safeUrl
                        break
                    }
                }
            }
            result
        }.getOrDefault(emptyMap())
    }

    private fun getJson(urlText: String, timeoutMs: Int = TIMEOUT_MS): String =
        requestOpacJson(urlText = urlText, method = "GET", jsonBody = null, timeoutMs = timeoutMs)

    private fun postJson(urlText: String, jsonBody: String, timeoutMs: Int = TIMEOUT_MS): String =
        requestOpacJson(urlText = urlText, method = "POST", jsonBody = jsonBody, timeoutMs = timeoutMs)

    private fun requestOpacJson(
        urlText: String,
        method: String,
        jsonBody: String?,
        timeoutMs: Int
    ): String {
        val endpoint = OfficialServiceRoutePolicy.parseHttpsEndpoint(urlText)
            ?: throw IllegalArgumentException("非法的图书馆 HTTPS 接口地址")
        check(endpoint.host == OfficialServiceRoutePolicy.LIBSPACE_HOST) {
            "仅允许请求河南工业大学图书馆官方域名"
        }

        val bodyBytes = jsonBody?.toByteArray(StandardCharsets.UTF_8)

        // 优先走 IPv4 优先 + 严格 TLS SNI 直连，彻底规避 libspace.haut.edu.cn 死 IPv6 (2001:250:4803::31) 导致的 10s 超时且不回退问题
        val directAttempt = runCatching {
            executeDirectTlsHttp11(
                endpoint = endpoint,
                method = method,
                bodyBytes = bodyBytes,
                timeoutMs = timeoutMs
            )
        }
        directAttempt.getOrNull()?.let { return it }
        val directErr = directAttempt.exceptionOrNull()
        if (directErr is SSLPeerUnverifiedException || directErr is SecurityException) {
            throw directErr
        }

        // 回退至系统 HttpsURLConnection（不设置 setFixedLengthStreamingMode，保留 OkHttp RetryableSink）
        return executeUrlConnectionFallback(
            endpoint = endpoint,
            method = method,
            bodyBytes = bodyBytes,
            timeoutMs = timeoutMs
        )
    }

    private fun resolveIpv4PreferredAddresses(host: String): List<InetAddress> {
        val resolved = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        val ipv4 = resolved.filterIsInstance<Inet4Address>()
        val ipv6 = resolved.filterIsInstance<Inet6Address>()
        val ordered = mutableListOf<InetAddress>()
        ordered.addAll(ipv4)
        if (host == OfficialServiceRoutePolicy.LIBSPACE_HOST) {
            runCatching {
                val fallbackGateway = InetAddress.getByAddress(host, HAUT_GATEWAY_IPV4_BYTES)
                if (ordered.none { it.hostAddress == fallbackGateway.hostAddress }) {
                    ordered.add(fallbackGateway)
                }
            }
        }
        ordered.addAll(ipv6)
        return ordered
    }

    private fun executeDirectTlsHttp11(
        endpoint: HttpsEndpoint,
        method: String,
        bodyBytes: ByteArray?,
        timeoutMs: Int
    ): String {
        val candidates = resolveIpv4PreferredAddresses(endpoint.host)
        check(candidates.isNotEmpty()) { "无法解析图书馆服务器地址" }

        val perRouteConnectTimeout = timeoutMs.coerceIn(2_000, TCP_CONNECT_TIMEOUT_MS)
        var connectedRawSocket: Socket? = null
        var lastConnectError: Throwable? = null

        for (addr in candidates.take(4)) {
            val raw = Socket()
            try {
                raw.tcpNoDelay = true
                raw.soTimeout = timeoutMs
                raw.connect(InetSocketAddress(addr, 443), perRouteConnectTimeout)
                connectedRawSocket = raw
                break
            } catch (t: Throwable) {
                lastConnectError = t
                runCatching { raw.close() }
            }
        }

        val rawSocket = connectedRawSocket
            ?: throw (lastConnectError ?: IllegalStateException("无法连接图书馆服务器"))

        val sslFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val sslSocket = (sslFactory.createSocket(rawSocket, endpoint.host, 443, true) as SSLSocket)
        return sslSocket.use { tls ->
            tls.soTimeout = timeoutMs
            runCatching {
                val params = tls.sslParameters
                params.serverNames = listOf(SNIHostName(endpoint.host))
                tls.sslParameters = params
            }
            runCatching {
                tls.javaClass.getMethod("setHostname", String::class.java).invoke(tls, endpoint.host)
            }
            tls.startHandshake()

            val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
            if (!verifier.verify(endpoint.host, tls.session)) {
                throw SSLPeerUnverifiedException("图书馆 HTTPS 证书主机名校验未通过: ${endpoint.host}")
            }

            val rawPath = endpoint.uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            val requestTarget = if (endpoint.uri.rawQuery.isNullOrEmpty()) {
                rawPath
            } else {
                "$rawPath?${endpoint.uri.rawQuery}"
            }

            val headerBuilder = StringBuilder(512)
            headerBuilder.append(method).append(' ').append(requestTarget).append(" HTTP/1.1\r\n")
            headerBuilder.append("Host: ").append(endpoint.host).append("\r\n")
            headerBuilder.append("User-Agent: ").append(OPAC_USER_AGENT).append("\r\n")
            headerBuilder.append("Accept: application/json, text/plain, */*\r\n")
            headerBuilder.append("Accept-Encoding: identity\r\n")
            headerBuilder.append("X-Hw-Lang: chi\r\n")
            headerBuilder.append("X-Hw-explorer: m\r\n")
            headerBuilder.append("Origin: https://libspace.haut.edu.cn\r\n")
            headerBuilder.append("Referer: https://libspace.haut.edu.cn/mspace/searchList\r\n")
            if (bodyBytes != null) {
                headerBuilder.append("Content-Type: application/json; charset=UTF-8\r\n")
                headerBuilder.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            }
            headerBuilder.append("Connection: close\r\n\r\n")

            val out = tls.outputStream
            out.write(headerBuilder.toString().toByteArray(StandardCharsets.UTF_8))
            if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                out.write(bodyBytes)
            }
            out.flush()

            readHttp11Response(tls.inputStream)
        }
    }

    internal fun readHttp11Response(input: InputStream): String {
        val rawBytes = ByteArrayOutputStream(16_384)
        val buf = ByteArray(8_192)
        var headerEndIndex = -1
        var expectedBodyLength = -1
        var isChunked = false
        var isGzip = false
        var statusCode = -1

        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (rawBytes.size() + n > MAX_RESPONSE_BYTES) {
                break
            }
            rawBytes.write(buf, 0, n)

            if (headerEndIndex < 0) {
                val current = rawBytes.toByteArray()
                headerEndIndex = findHeaderDelimiterIndex(current)
                if (headerEndIndex >= 0) {
                    val headerText = String(current, 0, headerEndIndex, StandardCharsets.ISO_8859_1)
                    val headerLines = headerText.split("\r\n")
                    val statusLine = headerLines.firstOrNull().orEmpty()
                    statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
                    for (line in headerLines.drop(1)) {
                        val colon = line.indexOf(':')
                        if (colon <= 0) continue
                        val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                        val value = line.substring(colon + 1).trim().lowercase(Locale.ROOT)
                        when (name) {
                            "content-length" -> expectedBodyLength = value.toIntOrNull() ?: -1
                            "transfer-encoding" -> if (value.contains("chunked")) isChunked = true
                            "content-encoding" -> if (value.contains("gzip")) isGzip = true
                        }
                    }
                }
            }

            if (headerEndIndex >= 0 && expectedBodyLength >= 0) {
                val bodyReceived = rawBytes.size() - (headerEndIndex + 4)
                if (bodyReceived >= expectedBodyLength) {
                    break
                }
            }
        }

        val allBytes = rawBytes.toByteArray()
        check(headerEndIndex >= 0) { "馆藏服务响应格式不完整" }
        check(statusCode in 200..299) { "馆藏服务响应异常 (HTTP $statusCode)" }

        val bodyStart = headerEndIndex + 4
        val rawBody = if (expectedBodyLength >= 0) {
            val end = (bodyStart + expectedBodyLength).coerceAtMost(allBytes.size)
            allBytes.copyOfRange(bodyStart, end)
        } else {
            allBytes.copyOfRange(bodyStart, allBytes.size)
        }

        val decodedTransfer = if (isChunked) decodeChunkedBody(rawBody) else rawBody
        val decompressed = if (isGzip && decodedTransfer.isNotEmpty()) {
            GZIPInputStream(ByteArrayInputStream(decodedTransfer)).use { it.readBytes() }
        } else {
            decodedTransfer
        }

        val text = String(decompressed, StandardCharsets.UTF_8)
        return if (text.length > MAX_RESPONSE_CHARS) text.take(MAX_RESPONSE_CHARS) else text
    }

    private fun findHeaderDelimiterIndex(bytes: ByteArray): Int {
        val limit = bytes.size - 3
        for (i in 0 until limit) {
            if (bytes[i] == '\r'.code.toByte() &&
                bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() &&
                bytes[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    internal fun decodeChunkedBody(chunkedBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(chunkedBytes.size)
        var pos = 0
        val total = chunkedBytes.size
        while (pos < total) {
            var lineEnd = pos
            while (lineEnd + 1 < total &&
                !(chunkedBytes[lineEnd] == '\r'.code.toByte() && chunkedBytes[lineEnd + 1] == '\n'.code.toByte())
            ) {
                lineEnd++
            }
            if (lineEnd + 1 >= total) break
            val sizeHeader = String(chunkedBytes, pos, lineEnd - pos, StandardCharsets.ISO_8859_1)
                .substringBefore(';')
                .trim()
            val chunkSize = sizeHeader.toIntOrNull(16) ?: break
            pos = lineEnd + 2
            if (chunkSize <= 0) break
            val copyLen = chunkSize.coerceAtMost(total - pos)
            if (copyLen <= 0) break
            out.write(chunkedBytes, pos, copyLen)
            pos += copyLen + 2
        }
        return out.toByteArray()
    }

    private fun executeUrlConnectionFallback(
        endpoint: HttpsEndpoint,
        method: String,
        bodyBytes: ByteArray?,
        timeoutMs: Int
    ): String {
        val conn = (URL(endpoint.urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("User-Agent", OPAC_USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("X-Hw-Lang", "chi")
            setRequestProperty("X-Hw-explorer", "m")
            setRequestProperty("Origin", "https://libspace.haut.edu.cn")
            setRequestProperty("Referer", "https://libspace.haut.edu.cn/mspace/searchList")
            if (bodyBytes != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
        }
        return try {
            if (bodyBytes != null) {
                conn.outputStream.use { it.write(bodyBytes) }
            }
            val code = conn.responseCode
            check(code in 200..299) { "馆藏检索响应异常 (HTTP $code)" }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = reader.readText()
                if (text.length > MAX_RESPONSE_CHARS) text.take(MAX_RESPONSE_CHARS) else text
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun String.cleanSingleLine(maxLen: Int): String =
        cleanNullLiteral("").replace(Regex("\\s+"), " ").trim().take(maxLen)

    private fun String.stripHtmlAndClean(maxLen: Int): String =
        cleanNullLiteral("")
            .replace(Regex("</?(?:a|span|font|em|strong|b|i)\\b[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLen)
}
