package cn.edu.haut.campus.core.vault

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.database.SearchDocEntity
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.json.JsonValue
import java.time.Instant
import kotlin.math.max

/** Room FTS4 external-content 索引实现；写入/删除文档与业务写入可在同一 Room 事务内调用。 */
class RoomSearchIndex(
    private val database: HautDatabase,
    private val clock: () -> Instant = Instant::now
) : CampusSearch {
    private val dao = database.vaultDao()

    suspend fun upsert(document: SearchDocument) {
        val normalized = SearchTextNormalizer.normalizePlain(
            listOfNotNull(document.title, document.body, document.code).plus(document.aliases).joinToString(" ")
        )
        val titleStream = SearchTextNormalizer.tokenStreamFor(document.title)
        val bodyStream = SearchTextNormalizer.tokenStreamFor(document.body)
        val codeStream = SearchTextNormalizer.tokenStreamFor(document.code)
        val aliasStream = SearchTextNormalizer.tokenStreamFor(*document.aliases.toTypedArray())
        val tokenStream = listOf(titleStream, bodyStream, codeStream, aliasStream)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val existing = dao.getSearchDoc(
            entityType = document.type.name,
            entityId = document.ref.id,
            schoolId = document.ref.scope.schoolId.value,
            accountId = document.ref.scope.accountId.value,
            profileId = document.ref.scope.profileId.value
        )
        dao.upsertSearchDoc(
            SearchDocEntity(
                rowId = existing?.rowId ?: 0,
                entityType = document.type.name,
                entityId = document.ref.id,
                scopeSchoolId = document.ref.scope.schoolId.value,
                scopeAccountId = document.ref.scope.accountId.value,
                scopeProfileId = document.ref.scope.profileId.value,
                scopeCampusId = document.ref.scope.campusId?.value,
                title = document.title,
                body = document.body,
                code = document.code,
                aliasesJson = encodeStringList(document.aliases),
                normalizedText = normalized,
                tokenStream = tokenStream,
                tokenStreamTitle = titleStream,
                tokenStreamBody = bodyStream,
                tokenStreamCode = codeStream,
                tokenStreamAlias = aliasStream,
                termId = document.termId,
                atEpochMs = document.at?.toEpochMilli(),
                deeplink = document.deeplink,
                facetsJson = encodeFacets(document.facets),
                updatedAtEpochMs = clock().toEpochMilli(),
                deleted = false
            )
        )
    }

    suspend fun delete(ref: EntityRef): Int = dao.deleteSearchDoc(
        entityType = ref.type.name,
        entityId = ref.id,
        schoolId = ref.scope.schoolId.value,
        accountId = ref.scope.accountId.value,
        profileId = ref.scope.profileId.value
    )

    override suspend fun query(scope: SchoolAccountScope, q: SearchQuery): SearchPage {
        require(q.scope == scope) { "SearchQuery scope 与调用 scope 不一致" }
        val rewrite = SearchTextNormalizer.rewriteQuery(q.text)
        if (rewrite.isEmpty || (rewrite.ftsQuery == null && rewrite.singleCharFallbacks.isEmpty())) {
            return SearchPage(hits = emptyList(), totalCount = 0, nextOffset = null)
        }
        val sql = buildSearchSql(scope, q, rewrite.normalizedQuery, rewrite.ftsQuery, rewrite.singleCharFallbacks)
        val rows = dao.searchDocsRaw(SimpleSQLiteQuery(sql.selectSql, sql.args.map { it as Any }.toTypedArray()))
        val totalCount = querySingleLong(sql.countSql, sql.countArgs).toInt()
        val ranked = rows.mapIndexedNotNull { index, row ->
            val document = row.toDocument()
            val channel = classifyHit(row, rewrite.normalizedQuery)
                ?: return@mapIndexedNotNull null
            RankedHit(row = row, document = document, channel = channel, sqliteOrder = index)
        }.sortedWith(
            compareBy<RankedHit> { it.channel.ordinal }
                .thenBy { it.sqliteOrder }
                .thenByDescending { it.row.updatedAtEpochMs }
                .thenBy { it.row.rowId }
        )
        val pageRows = ranked.drop(q.offset).take(q.limit)
        val nextOffset = if (q.offset + pageRows.size < totalCount) q.offset + pageRows.size else null
        return SearchPage(
            hits = pageRows.map { it.toHit() },
            totalCount = totalCount,
            nextOffset = nextOffset
        )
    }

    suspend fun rebuild(scope: SchoolAccountScope, contributors: List<SearchIndexContributor>): SearchRebuildReport =
        database.withTransaction {
            dao.deleteSearchDocsForScope(
                schoolId = scope.schoolId.value,
                accountId = scope.accountId.value,
                profileId = scope.profileId.value
            )
            var count = 0
            contributors.forEach { contributor ->
                contributor.fullRebuild(scope).forEach { document ->
                    require(document.ref.scope == scope) { "contributor 返回跨 scope 文档: " + contributor.type }
                    require(document.type == contributor.type) { "contributor 返回类型不一致文档: " + contributor.type }
                    upsert(document)
                    count++
                }
            }
            dao.rebuildSearchFts()
            val integrity = verifyIntegrity()
            if (!integrity.isConsistent) {
                throw IllegalStateException("search FTS rebuild 后计数不一致: " + integrity)
            }
            SearchRebuildReport(indexedDocCount = count, integrity = integrity)
        }

    suspend fun verifyIntegrity(): SearchIndexIntegrity {
        val activeDocs = dao.countActiveSearchDocs()
        val ftsRows = dao.countSearchFtsRows()
        val orphan = querySingleLong(
            "SELECT COUNT(*) FROM search_fts LEFT JOIN search_docs " +
                "ON search_fts.docid = search_docs.rowid AND search_docs.deleted = 0 " +
                "WHERE search_docs.rowid IS NULL",
            emptyList()
        ).toInt()
        val missing = querySingleLong(
            "SELECT COUNT(*) FROM search_docs LEFT JOIN search_fts " +
                "ON search_fts.docid = search_docs.rowid " +
                "WHERE search_docs.deleted = 0 AND search_fts.docid IS NULL",
            emptyList()
        ).toInt()
        return SearchIndexIntegrity(
            activeDocCount = activeDocs,
            ftsRowCount = ftsRows,
            orphanFtsRowCount = orphan,
            missingFtsRowCount = missing
        )
    }

    private fun buildSearchSql(
        scope: SchoolAccountScope,
        query: SearchQuery,
        normalizedQuery: String,
        ftsQuery: String?,
        singleCharFallbacks: List<String>
    ): SearchSql {
        val args = mutableListOf<Any?>()
        val countArgs = mutableListOf<Any?>()
        val fromWhere = StringBuilder()
        if (ftsQuery != null) {
            fromWhere.append("FROM search_docs JOIN search_fts ON search_fts.docid = search_docs.rowid WHERE search_fts MATCH ?")
            args += ftsQuery
            countArgs += ftsQuery
        } else {
            fromWhere.append("FROM search_docs WHERE 1 = 1")
        }
        singleCharFallbacks.forEach { fallback ->
            fromWhere.append(" AND instr(search_docs.normalizedText, ?) > 0")
            args += fallback
            countArgs += fallback
        }
        appendFieldAttributionPredicate(
            fromWhere = fromWhere,
            args = args,
            countArgs = countArgs,
            normalizedQuery = normalizedQuery
        )
        fromWhere.append(" AND search_docs.scopeSchoolId = ? AND search_docs.scopeAccountId = ? AND search_docs.scopeProfileId = ?")
        listOf(scope.schoolId.value, scope.accountId.value, scope.profileId.value).forEach {
            args += it
            countArgs += it
        }
        scope.campusId?.let { campus ->
            fromWhere.append(" AND search_docs.scopeCampusId = ?")
            args += campus.value
            countArgs += campus.value
        }
        fromWhere.append(" AND search_docs.deleted = 0")
        query.facetEquals.forEach { (key, value) ->
            // facetsJson 由 canonical JSON 写入；键/值在本模型中禁止引号与反斜线，因此精确键值子串安全。
            fromWhere.append(" AND instr(search_docs.facetsJson, ?) > 0")
            val exactPair = "\"" + key + "\":\"" + value + "\""
            args += exactPair
            countArgs += exactPair
        }
        if (query.types.isNotEmpty()) {
            fromWhere.append(" AND search_docs.entityType IN (")
            query.types.forEachIndexed { index, type ->
                if (index > 0) fromWhere.append(',')
                fromWhere.append('?')
                args += type.name
                countArgs += type.name
            }
            fromWhere.append(")")
        }
        val candidateLimit = max((query.offset + query.limit) * 4, 100).coerceAtMost(500)
        // bm25/matchinfo 在 Android FTS4/Robolectric 组合中不是可移植能力（实测 no such function）。
        // 首版按 R01 采用确定性 SQL 顺序 + Kotlin title/alias/code/body 二级排序。
        val order = " ORDER BY search_docs.updatedAtEpochMs DESC, search_docs.rowid ASC"
        val selectSql = "SELECT search_docs.* " + fromWhere + order + " LIMIT " + candidateLimit
        val countSql = "SELECT COUNT(*) " + fromWhere
        return SearchSql(selectSql = selectSql, args = args, countSql = countSql, countArgs = countArgs)
    }

    /**
     * 用现有分字段 tokenStream 约束候选行。合并 tokenStream 只作为 FTS 粗筛，不再决定逻辑命中：
     * 一个查询的全部词元必须归因到同一个 title/body/code/alias 字段，跨字段拼合不会产生结果。
     */
    private fun appendFieldAttributionPredicate(
        fromWhere: StringBuilder,
        args: MutableList<Any?>,
        countArgs: MutableList<Any?>,
        normalizedQuery: String
    ) {
        val units = structuredQueryUnits(normalizedQuery)
        if (units.isEmpty()) return
        val columns = listOf("tokenStreamTitle", "tokenStreamBody", "tokenStreamCode", "tokenStreamAlias")
        val conditionArgs = mutableListOf<String>()
        val fieldConditions = columns.map { column ->
            val unitConditions = units.map { unit ->
                val runConditions = unit.runs.mapNotNull { run ->
                    if (run.kind == SearchTextNormalizer.ScriptKind.CJK && run.text.codePointCount(0, run.text.length) == 1) {
                        // 多字 CJK 在 tokenStream 中保存为重叠 bigram，单字查询通常没有独立 token；
                        // 但目标字只会作为孤立 token 或 bigram 的一部分出现，字段内 substring
                        // 与 normalized plain 中的单字存在性等价，因此也必须参与字段归因。
                        conditionArgs += run.text
                        "instr(search_docs." + column + ", ?) > 0"
                    } else {
                        val tokenConditions = run.tokens.distinct().map { token ->
                            val pattern = if (run.tailPrefix) " " + token else " " + token + " "
                            conditionArgs += pattern
                            "instr(' ' || search_docs." + column + " || ' ', ?) > 0"
                        }
                        tokenConditions.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
                    }
                }
                if (runConditions.isEmpty()) "1 = 1" else runConditions.joinToString(" AND ")
            }
            "(" + unitConditions.joinToString(" AND ") + ")"
        }
        fromWhere.append(" AND ").append(fieldConditions.joinToString(" OR ", "(", ")"))
        args.addAll(conditionArgs)
        countArgs.addAll(conditionArgs)
    }

    private fun structuredQueryUnits(normalizedQuery: String): List<FieldQueryUnit> {
        val units = normalizedQuery.split(' ').filter { it.isNotBlank() }
        return units.mapIndexed { unitIndex, unit ->
            val runs = SearchTextNormalizer.splitScriptRuns(unit)
            FieldQueryUnit(
                runs = runs.mapIndexed { runIndex, run ->
                    FieldQueryRun(
                        kind = run.kind,
                        text = run.text,
                        tokens = SearchTextNormalizer.normalize(run.text).tokens,
                        tailPrefix = unitIndex == units.lastIndex &&
                            runIndex == runs.lastIndex &&
                            run.kind == SearchTextNormalizer.ScriptKind.WORD &&
                            run.text.length >= 3
                    )
                }
            )
        }
    }

    private fun classifyHit(row: SearchDocEntity, normalizedQuery: String): SearchHitChannel? {
        val units = structuredQueryUnits(normalizedQuery)
        fun containsAll(normalized: String): Boolean = normalized.isNotBlank() && units.all { unit ->
            unit.runs.all { run -> queryRunMatches(normalized, run) }
        }
        val normalizedTitle = SearchTextNormalizer.normalizePlain(row.title)
        val normalizedCode = row.code?.let(SearchTextNormalizer::normalizePlain).orEmpty()
        val normalizedBody = SearchTextNormalizer.normalizePlain(row.body)
        val aliases = decodeStringList(row.aliasesJson).map(SearchTextNormalizer::normalizePlain)
        return when {
            containsAll(normalizedTitle) -> SearchHitChannel.TITLE
            aliases.any(::containsAll) -> SearchHitChannel.ALIAS
            containsAll(normalizedCode) -> SearchHitChannel.CODE
            containsAll(normalizedBody) -> SearchHitChannel.BODY
            else -> null
        }
    }

    private fun queryRunMatches(normalizedText: String, run: FieldQueryRun): Boolean = when (run.kind) {
        SearchTextNormalizer.ScriptKind.CJK -> normalizedText.contains(run.text)
        SearchTextNormalizer.ScriptKind.WORD -> wordRunMatches(normalizedText, run.text, run.tailPrefix)
    }

    private fun wordRunMatches(normalizedText: String, unit: String, tailPrefix: Boolean): Boolean {
        var index = normalizedText.indexOf(unit)
        while (index >= 0) {
            val end = index + unit.length
            val leftBoundary = index == 0 ||
                normalizedText[index - 1].isWhitespace() ||
                isCjkCharacter(normalizedText[index - 1])
            val rightBoundary = end == normalizedText.length ||
                normalizedText[end].isWhitespace() ||
                isCjkCharacter(normalizedText[end])
            val tailPrefixContinuation = tailPrefix &&
                end < normalizedText.length &&
                isWordContinuationCharacter(normalizedText[end])
            if (leftBoundary && (rightBoundary || tailPrefixContinuation)) return true
            index = normalizedText.indexOf(unit, index + 1)
        }
        return false
    }

    private fun isWordContinuationCharacter(char: Char): Boolean {
        val type = Character.getType(char.code)
        return char.isLetterOrDigit() || char == '_' ||
            type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    private fun isCjkCharacter(char: Char): Boolean = when (Character.UnicodeScript.of(char.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL -> true
        else -> false
    }

    private data class FieldQueryUnit(
        val runs: List<FieldQueryRun>
    )

    private data class FieldQueryRun(
        val kind: SearchTextNormalizer.ScriptKind,
        val text: String,
        val tokens: List<String>,
        val tailPrefix: Boolean
    )

    private fun querySingleLong(sql: String, args: List<Any?>): Long {
        val bindArgs = arrayOfNulls<Any?>(args.size)
        args.forEachIndexed { index, value ->
            bindArgs[index] = when (value) {
                null -> null
                is String -> value
                is Int -> value.toLong()
                is Long -> value
                is Boolean -> if (value) 1L else 0L
                else -> value.toString()
            }
        }
        database.openHelper.writableDatabase.query(sql, bindArgs).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun SearchDocEntity.toDocument(): SearchDocument = SearchDocument(
        ref = EntityRef(
            type = EntityType.valueOf(entityType),
            id = entityId,
            scope = SchoolAccountScope(
                schoolId = SchoolId(scopeSchoolId),
                accountId = AccountId(scopeAccountId),
                profileId = ProfileId(scopeProfileId),
                campusId = scopeCampusId?.let(::CampusId)
            )
        ),
        type = EntityType.valueOf(entityType),
        title = title,
        body = body,
        code = code,
        aliases = decodeStringList(aliasesJson),
        termId = termId,
        at = atEpochMs?.let(Instant::ofEpochMilli),
        deeplink = deeplink,
        facets = decodeFacets(facetsJson)
    )

    private fun RankedHit.toHit(): SearchHit = SearchHit(
        document = document,
        snippet = makeSnippet(document),
        channel = channel,
        updatedAt = Instant.ofEpochMilli(row.updatedAtEpochMs)
    )

    private fun makeSnippet(document: SearchDocument): String {
        val source = when {
            document.body.isNotBlank() -> document.body
            document.code != null -> document.title + " " + document.code
            else -> document.title
        }
        return source.replace(Regex("\\s+"), " ").take(160)
    }

    private fun encodeStringList(values: List<String>): String =
        cn.edu.haut.campus.core.model.json.Json.canonical(
            JsonValue.Arr(values.map(JsonValue::Str))
        )

    private fun decodeStringList(raw: String): List<String> =
        cn.edu.haut.campus.core.model.json.Json.parse(raw).asArrOrNull?.items?.mapNotNull { it.stringOrNull() } ?: emptyList()

    private fun encodeFacets(facets: Map<String, String>): String =
        cn.edu.haut.campus.core.model.json.Json.canonical(
            JsonValue.Obj(facets.mapValues { (_, value) -> JsonValue.Str(value) })
        )

    private fun decodeFacets(raw: String): Map<String, String> =
        cn.edu.haut.campus.core.model.json.Json.parse(raw).asObjOrNull?.fields?.mapValues { (_, value) ->
            value.stringOrNull() ?: ""
        } ?: emptyMap()

    private data class SearchSql(
        val selectSql: String,
        val args: List<Any?>,
        val countSql: String,
        val countArgs: List<Any?>
    )

    private data class RankedHit(
        val row: SearchDocEntity,
        val document: SearchDocument,
        val channel: SearchHitChannel,
        val sqliteOrder: Int
    )
}
