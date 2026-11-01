package cn.edu.haut.campus.core.protocol

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.json.JsonValue

/**
 * 完整分页读取。只有拿到终止证据（总数匹配 / 页数匹配 / 短页）并完成去重校验后才返回，
 * 任何中途不一致都抛结构化 PARTIAL_RESPONSE，调用方据此保留旧的完整快照。
 */
object PagedJsonReader {

    data class Page(
        val items: List<JsonValue>,
        val total: Int? = null,
        val totalPages: Int? = null,
        val currentPage: Int? = null,
        val pageSize: Int? = null
    )

    data class Result(val items: List<JsonValue>, val pagesRead: Int)

    suspend fun readAll(
        maxPages: Int = 100,
        maxRecords: Int = 20_000,
        fetch: suspend (page: Int) -> Page
    ): Result {
        require(maxPages > 0 && maxRecords > 0) { "分页上限必须为正数" }
        val collected = ArrayList<JsonValue>()
        val seen = HashSet<String>()
        var expectedTotal: Int? = null
        var expectedPages: Int? = null
        var page = 1
        while (page <= maxPages) {
            val current = try {
                fetch(page)
            } catch (error: TransportFailure) {
                if (page > 1) throw failure(AcademicError.PARTIAL_RESPONSE, "教务分页中断，已保留之前的完整快照")
                throw error
            }
            current.currentPage?.let {
                if (it != page) throw failure(AcademicError.PARTIAL_RESPONSE, "教务返回的页码与请求不一致")
            }
            if (expectedTotal != null && current.total != null && current.total != expectedTotal) {
                throw failure(AcademicError.STALE_SNAPSHOT, "教务数据在读取期间发生变化，请重新同步")
            }
            if (expectedPages != null && current.totalPages != null && current.totalPages != expectedPages) {
                throw failure(AcademicError.STALE_SNAPSHOT, "教务页数在读取期间发生变化，请重新同步")
            }
            if (page == 1) {
                expectedTotal = current.total
                expectedPages = current.totalPages
            }
            current.total?.let { if (it > maxRecords) throw failure(AcademicError.PARTIAL_RESPONSE, "教务数据超出本次读取上限") }
            current.totalPages?.let { if (it > maxPages) throw failure(AcademicError.PARTIAL_RESPONSE, "教务页数超出本次读取上限") }
            val effectiveSize = current.pageSize?.takeIf { it > 0 }
            if (effectiveSize != null && current.items.size > effectiveSize) {
                throw failure(AcademicError.SOURCE_DATA_INVALID, "教务分页大小无效")
            }
            current.items.forEach { item ->
                val canonical = cn.edu.haut.campus.core.model.json.Json.canonical(item)
                if (!seen.add(canonical)) throw failure(AcademicError.PARTIAL_RESPONSE, "教务分页包含重复记录，无法确认完整性")
                collected += item
            }
            if (collected.size > maxRecords) throw failure(AcademicError.PARTIAL_RESPONSE, "教务数据超出本次读取上限")
            expectedTotal?.let { if (collected.size > it) throw failure(AcademicError.PARTIAL_RESPONSE, "教务记录总数与分页不一致") }

            val complete = when {
                expectedTotal != null -> collected.size == expectedTotal
                expectedPages != null -> page == expectedPages || (expectedPages == 0 && collected.isEmpty())
                effectiveSize != null -> current.items.size < effectiveSize
                else -> current.items.isEmpty()
            }
            if (complete) {
                if (expectedPages != null && page != expectedPages && expectedPages != 0) {
                    throw failure(AcademicError.PARTIAL_RESPONSE, "教务页数与记录总数不一致")
                }
                return Result(collected, page)
            }
            if (current.items.isEmpty()) throw failure(AcademicError.PARTIAL_RESPONSE, "教务分页提前结束")
            if (expectedPages != null && page >= expectedPages) {
                throw failure(AcademicError.PARTIAL_RESPONSE, "教务页数与记录总数不一致")
            }
            page++
        }
        throw failure(AcademicError.PARTIAL_RESPONSE, "教务分页未能完整结束")
    }

    internal fun failure(error: AcademicError, message: String): TransportFailure = TransportFailure(error, message)
}
