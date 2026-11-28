package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** 写入 search_docs/search_fts 的一份逻辑文档；原文保留在 search_docs，token 流由 R01 normalizer 生成。 */
data class SearchDocument(
    val ref: EntityRef,
    val type: EntityType,
    val title: String,
    val body: String,
    val code: String? = null,
    val aliases: List<String> = emptyList(),
    val termId: String? = null,
    val at: Instant? = null,
    val deeplink: String? = null,
    val facets: Map<String, String> = emptyMap()
) {
    init {
        require(type == ref.type) { "SearchDocument type 与 EntityRef type 不一致" }
        require(title.isNotBlank() && title.length <= 512) { "搜索标题为空或过长" }
        require(title.none { it.isISOControl() }) { "搜索标题不能包含控制字符" }
        require(body.length <= 32 * 1024) { "搜索正文过长" }
        require(code == null || code.length <= 256) { "搜索 code 过长" }
        require(aliases.all { it.isNotBlank() && it.length <= 128 && it.none { ch -> ch.isISOControl() } }) {
            "搜索别名为空、过长或含控制字符"
        }
        require(aliases.distinct().size == aliases.size) { "搜索别名不能重复" }
        require(termId == null || termId.isNotBlank()) { "termId 不能为空白" }
        require(deeplink == null || (deeplink.length <= 1024 && deeplink.none { it.isISOControl() || it.isWhitespace() })) {
            "deeplink 过长或含空白/控制字符"
        }
        require(facets.keys.all { it.isNotBlank() && it.length <= 64 && it.none { ch -> ch.isISOControl() || ch.isWhitespace() } }) {
            "搜索 facet 键非法"
        }
        require(facets.values.all { it.length <= 256 && it.none { ch -> ch.isISOControl() } }) { "搜索 facet 值非法" }
    }
}

sealed interface SearchDocumentChange {
    data class Upsert(val document: SearchDocument) : SearchDocumentChange
    data class Delete(val ref: EntityRef) : SearchDocumentChange
}

/** 领域搜索索引贡献者。首版 incremental 可为空：写入路径必须同事务直接调用 RoomSearchIndex 保持无幽灵结果。 */
interface SearchIndexContributor {
    val type: EntityType
    suspend fun fullRebuild(scope: SchoolAccountScope): Sequence<SearchDocument>
    fun incremental(scope: SchoolAccountScope): Flow<SearchDocumentChange> = emptyFlow()
}

data class SearchQuery(
    val scope: SchoolAccountScope,
    val text: String,
    val types: Set<EntityType> = emptySet(),
    val facetEquals: Map<String, String> = emptyMap(),
    val limit: Int = 20,
    val offset: Int = 0
) {
    init {
        require(text.length <= 512) { "搜索查询过长" }
        require(limit in 1..100) { "搜索 limit 必须在 1..100" }
        require(offset >= 0) { "搜索 offset 不能为负" }
        require(facetEquals.keys.all { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }) { "搜索 facet 键非法" }
        require(facetEquals.values.all { it.length <= 256 && it.none { ch -> ch.isISOControl() || ch == '\"' || ch == '\\' } }) {
            "搜索 facet 等值过滤值非法"
        }
    }
}

enum class SearchHitChannel {
    TITLE,
    ALIAS,
    CODE,
    BODY,
    FALLBACK
}

data class SearchHit(
    val document: SearchDocument,
    val snippet: String,
    val channel: SearchHitChannel,
    val updatedAt: Instant
)

data class SearchPage(
    val hits: List<SearchHit>,
    val totalCount: Int,
    val nextOffset: Int?
)

interface CampusSearch {
    suspend fun query(scope: SchoolAccountScope, q: SearchQuery): SearchPage
}

data class SearchIndexIntegrity(
    val activeDocCount: Int,
    val ftsRowCount: Int,
    val orphanFtsRowCount: Int,
    val missingFtsRowCount: Int
) {
    val isConsistent: Boolean
        get() = activeDocCount == ftsRowCount && orphanFtsRowCount == 0 && missingFtsRowCount == 0
}

data class SearchRebuildReport(
    val indexedDocCount: Int,
    val integrity: SearchIndexIntegrity
)
