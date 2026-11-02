package cn.edu.haut.campus.core.campusservices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
 * - 毫秒级返回书目列表、实时在馆/可借册数、单册条码级馆藏位置及封面图。
 */
object HautLibraryOpacClient {

    private const val OPAC_BASE_URL = "https://libspace.haut.edu.cn/meta-local/opac"
    private const val TIMEOUT_MS = 10_000
    private const val MAX_RESPONSE_CHARS = 1_500_000

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
                    enrichBooksWithCovers(books)
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
            val cleanKeyword = keyword.trim().take(80)
            require(cleanKeyword.isNotEmpty()) { "请输入检索关键词" }
            val safePage = page.coerceAtLeast(1)
            val safePageSize = pageSize.coerceIn(1, 50)

            val requestJson = buildSearchRequestPayload(
                keyword = cleanKeyword,
                field = field,
                page = safePage,
                pageSize = safePageSize
            )
            val rawResponse = postJson("$OPAC_BASE_URL/search/", requestJson)
            val parsed = parseSearchResponse(
                rawJson = rawResponse,
                keyword = cleanKeyword,
                field = field,
                page = safePage,
                pageSize = safePageSize,
                pageGeneration = pageGeneration
            )
            val enrichedBooks = runCatching {
                enrichBooksWithCovers(parsed.books)
            }.getOrDefault(parsed.books)

            parsed.copy(books = enrichedBooks)
        }
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
                    holdings = book.inlineHoldings
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
            val word = item.optString("_1").cleanSingleLine(60)
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
            val title = item.optString("title").cleanSingleLine(200)
            if (title.isBlank()) continue
            val bibId = item.optString("bibId").cleanSingleLine(80)
            val author = item.optString("author").cleanSingleLine(150)
            val publisher = item.optString("publisher").cleanSingleLine(120)
            val pubYear = item.optString("pub_year").cleanSingleLine(40)
            val callNo = item.optString("callno").cleanSingleLine(80)
            val isbn = item.optString("isbn").cleanSingleLine(40)
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
            val msg = root.optString("msg").takeIf { it.isNotBlank() && it != "null" }
            msg ?: "馆藏检索服务暂时繁忙，请稍后重试"
        }

        val data = root.optJSONObject("data") ?: JSONObject()
        val total = data.optInt("total", 0)
        val dataList = data.optJSONArray("dataList") ?: JSONArray()
        val count = dataList.length().coerceAtMost(LibraryCatalogSnapshot.MAX_BOOKS)

        val books = (0 until count).mapNotNull { idx ->
            val item = dataList.optJSONObject(idx) ?: return@mapNotNull null
            val title = item.optString("title").stripHtmlAndClean(300)
            if (title.isBlank()) return@mapNotNull null

            val bibId = item.optString("bibId").cleanSingleLine(80)
            val author = item.optString("author").stripHtmlAndClean(200)
            val publisher = item.optString("publisher").stripHtmlAndClean(150)
            val pubYear = item.optString("pub_year").cleanSingleLine(40)
            val isbn = item.optString("isbn").cleanSingleLine(60)
            val docTypeDesc = item.optString("docTypeDesc").cleanSingleLine(40)
            val abstractText = item.optString("abstract").stripHtmlAndClean(500)

            val callNo = item.optJSONArray("callno")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).cleanSingleLine(80).takeIf { it.isNotBlank() }
                }.firstOrNull()
            } ?: item.optString("callno").cleanSingleLine(80).takeIf { !it.startsWith("[") }.orEmpty()

            val inlineHoldings = parseHoldingsArrayString(item.optString("holdings"))
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

            val rawThumb = item.optString("thumb")
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
        val bibLines = mutableListOf<String>()
        val descLines = mutableListOf<String>()

        if (!infoJson.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(infoJson)
                val mapObj = root.optJSONObject("data")?.optJSONObject("map")
                val baseMap = mapObj?.optJSONObject("baseInfo")?.optJSONObject("map")
                val detailMap = mapObj?.optJSONObject("detailInfo")?.optJSONObject("map")

                val remoteTitle = baseMap?.optString("title")?.stripHtmlAndClean(300).orEmpty()
                if (remoteTitle.isNotBlank()) {
                    title = remoteTitle
                }

                val callNoArr = baseMap?.optJSONArray("callno")
                val callNo = callNoArr?.optString(0)?.cleanSingleLine(80)
                    ?.takeIf { it.isNotBlank() }
                    ?: fallbackBook.callNo
                if (callNo.isNotBlank()) {
                    bibLines.add("索书号：$callNo")
                }

                if (detailMap != null) {
                    val primaryKeys = listOf("题名/责任者", "版本说明", "出版发行项", "ISBN及定价", "载体形态项", "丛编项", "学科主题")
                    for (key in primaryKeys) {
                        val v = detailMap.optString(key).stripHtmlAndClean(400)
                        if (v.isNotBlank()) {
                            bibLines.add("$key：$v")
                        }
                    }
                    val noteKeys = listOf("提要文摘附注", "一般附注", "内容附注")
                    for (key in noteKeys) {
                        val v = detailMap.optString(key).stripHtmlAndClean(4000)
                        if (v.isNotBlank()) {
                            descLines.add(v)
                        }
                    }
                }
            }
        }

        if (bibLines.isEmpty() && fallbackBook.bibliography.isNotBlank()) {
            bibLines.add(fallbackBook.bibliography)
            if (fallbackBook.callNo.isNotBlank()) {
                bibLines.add("索书号：${fallbackBook.callNo}")
            }
            if (fallbackBook.isbn.isNotBlank()) {
                bibLines.add("ISBN：${fallbackBook.isbn}")
            }
        }

        if (descLines.isEmpty() && fallbackBook.summary.isNotBlank()) {
            descLines.add(fallbackBook.summary)
        }

        val parsedHoldings = if (!holdingsJson.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(holdingsJson)
                val holdingsArrayStr = root.optJSONObject("data")?.optString("holdings").orEmpty()
                parseHoldingsArrayString(holdingsArrayStr)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val finalHoldings = parsedHoldings.ifEmpty { fallbackBook.inlineHoldings }

        return CatalogBookDetail(
            title = title,
            coverUrl = fallbackBook.coverUrl,
            bibliography = bibLines.joinToString("\n"),
            description = descLines.joinToString("\n\n"),
            holdings = finalHoldings
        )
    }

    internal fun parseHoldingsArrayString(rawHoldingsJson: String?): List<CatalogHolding> {
        val trimmed = rawHoldingsJson?.trim().orEmpty()
        if (trimmed.isEmpty() || !trimmed.startsWith("[")) return emptyList()
        return runCatching {
            val arr = JSONArray(trimmed)
            val count = arr.length().coerceAtMost(LibraryCatalogSnapshot.MAX_HOLDINGS)
            (0 until count).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                val permLocation = obj.optString("location").cleanSingleLine(150)
                val tempLocation = obj.optString("tempLocation").cleanSingleLine(150)
                val displayLocation = when {
                    tempLocation.isNotBlank() && permLocation.isNotBlank() && tempLocation != permLocation ->
                        "$tempLocation（原属：$permLocation）"
                    tempLocation.isNotBlank() -> tempLocation
                    permLocation.isNotBlank() -> permLocation
                    else -> obj.optString("locationName").cleanSingleLine(150).ifBlank { "馆藏位置待确认" }
                }

                val callNo = obj.optString("callNo").cleanSingleLine(80)
                val barCode = obj.optString("barCode").cleanSingleLine(60)
                val dueDate = obj.optString("dueDate").cleanSingleLine(40).takeIf { it != "null" }.orEmpty()
                val vol = obj.optString("vol").cleanSingleLine(40).takeIf { it != "null" }.orEmpty()
                val status = obj.optString("status").cleanSingleLine(60).ifBlank { "状态以馆内为准" }
                val shelfMark = obj.optString("shelfMark").cleanSingleLine(120).takeIf { it != "null" }.orEmpty()

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
            postJson("$OPAC_BASE_URL/search/extend3?coversOnly=true", payload)
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
                    val rawUrl = arr.optJSONObject(i)?.optString("imageUrl").orEmpty()
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

    private fun getJson(urlText: String): String {
        val endpoint = OfficialServiceRoutePolicy.parseHttpsEndpoint(urlText)
            ?: throw IllegalArgumentException("非法的图书馆 HTTPS 接口地址")
        check(endpoint.host == OfficialServiceRoutePolicy.LIBSPACE_HOST) {
            "仅允许请求河南工业大学图书馆官方域名"
        }

        val conn = (URL(endpoint.urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            check(code in 200..299) { "馆藏服务响应异常 (HTTP $code)" }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = reader.readText()
                if (text.length > MAX_RESPONSE_CHARS) text.take(MAX_RESPONSE_CHARS) else text
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(urlText: String, jsonBody: String): String {
        val endpoint = OfficialServiceRoutePolicy.parseHttpsEndpoint(urlText)
            ?: throw IllegalArgumentException("非法的图书馆 HTTPS 接口地址")
        check(endpoint.host == OfficialServiceRoutePolicy.LIBSPACE_HOST) {
            "仅允许请求河南工业大学图书馆官方域名"
        }

        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        val conn = (URL(endpoint.urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(bodyBytes.size)
        }
        return try {
            conn.outputStream.use { it.write(bodyBytes) }
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
        replace(Regex("\\s+"), " ").trim().take(maxLen)

    private fun String.stripHtmlAndClean(maxLen: Int): String =
        replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLen)
}
