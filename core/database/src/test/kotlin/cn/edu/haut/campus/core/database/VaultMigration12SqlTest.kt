package cn.edu.haut.campus.core.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 不伪造 W0-04 v11 业务 schema；只验证 MIGRATION_11_12 自身 SQL 在真实 SQLite 上可执行、
 * FTS DDL 与导出 v12 schema 一致且同步触发器行为正确。完整 v11→v12 旧库链验收待 v11 合入。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VaultMigration12SqlTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val databaseName = "vault-migration-12-sql-test.db"

    @After
    fun cleanDatabase() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationSqlCreatesVaultTablesAndWorkingExternalContentFts() {
        context.deleteDatabase(databaseName)
        val callback = object : SupportSQLiteOpenHelper.Callback(11) {
            override fun onConfigure(db: SupportSQLiteDatabase) = Unit
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(callback)
                .build()
        )
        try {
            val raw = helper.writableDatabase
            MIGRATION_11_12.migrate(raw)

            val tables = tableNames(raw)
            assertTrue(tables.containsAll(setOf(
                "personal_events", "entity_journal", "domain_outbox", "search_docs", "search_fts", "blob_store"
            )))
            val ftsSql = objectSql(raw, "search_fts")
            assertTrue(ftsSql.contains("FTS4"))
            assertTrue(ftsSql.contains("tokenchars=_"))
            assertSchemaMatchesExportedV12(ftsSql)

            val rowId = insertSearchDoc(raw, "擦除 除测 测试")
            assertEquals(1, ftsMatches(raw, "擦除"))
            updateTokenStream(raw, rowId, "离散 散数 数学")
            assertEquals(0, ftsMatches(raw, "擦除"))
            assertEquals(1, ftsMatches(raw, "离散"))
            raw.delete("search_docs", "rowid = ?", arrayOf(rowId.toString()))
            assertEquals(0, ftsMatches(raw, "离散"))
        } finally {
            helper.close()
        }
    }

    private fun assertSchemaMatchesExportedV12(actualSql: String) {
        val content = checkNotNull(javaClass.classLoader?.getResourceAsStream(
            "cn.edu.haut.campus.core.database.HautDatabase/12.json"
        )) { "缺少已提交的 Room v12 schema" }.bufferedReader().use { it.readText() }
        val entities = JSONObject(content).getJSONObject("database").getJSONArray("entities")
        var expected = ""
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            if (entity.getString("tableName") == "search_fts") {
                expected = entity.getString("createSql").replace("$" + "{TABLE_NAME}", "search_fts")
            }
        }
        assertTrue("导出 schema 必须包含 search_fts", expected.isNotBlank())
        val tick = "\u0060"
        for (fragment in listOf(
            "USING FTS4",
            tick + "tokenStream" + tick + " TEXT NOT NULL",
            "tokenize=unicode61",
            tick + "tokenchars=_" + tick,
            "content=" + tick + "search_docs" + tick
        )) {
            assertTrue("导出 schema 缺少 " + fragment, expected.contains(fragment))
            assertTrue("迁移 SQL 缺少 " + fragment + "：" + actualSql, actualSql.contains(fragment))
        }
    }

    private fun insertSearchDoc(raw: SupportSQLiteDatabase, tokenStream: String): Long {
        val values = ContentValues().apply {
            put("entityType", "PERSONAL_EVENT")
            put("entityId", "personal:migration-sql")
            put("scopeSchoolId", "haut")
            put("scopeAccountId", "acct")
            put("scopeProfileId", "prof")
            put("title", "擦除测试")
            put("body", "正文")
            put("aliasesJson", "[]")
            put("normalizedText", tokenStream)
            put("tokenStream", tokenStream)
            put("tokenStreamTitle", tokenStream)
            put("tokenStreamBody", "")
            put("tokenStreamCode", "")
            put("tokenStreamAlias", "")
            put("facetsJson", "{}")
            put("updatedAtEpochMs", 1L)
            put("deleted", false)
        }
        val rowId = raw.insert("search_docs", SQLiteDatabase.CONFLICT_ABORT, values)
        assertTrue(rowId > 0)
        return rowId
    }

    private fun updateTokenStream(raw: SupportSQLiteDatabase, rowId: Long, tokenStream: String) {
        val values = ContentValues().apply {
            put("normalizedText", tokenStream)
            put("tokenStream", tokenStream)
            put("tokenStreamTitle", tokenStream)
        }
        assertEquals(1, raw.update("search_docs", SQLiteDatabase.CONFLICT_REPLACE, values, "rowid = ?", arrayOf(rowId.toString())))
    }

    private fun ftsMatches(raw: SupportSQLiteDatabase, query: String): Int =
        raw.query(
            "SELECT COUNT(*) FROM search_docs JOIN search_fts ON search_fts.docid = search_docs.rowid WHERE search_fts MATCH ?",
            arrayOf(query)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun tableNames(raw: SupportSQLiteDatabase): Set<String> {
        val result = mutableSetOf<String>()
        raw.query("SELECT name FROM sqlite_master WHERE type IN ('table', 'virtual table') OR name IN ('search_fts')").use { cursor ->
            while (cursor.moveToNext()) result += cursor.getString(0)
        }
        return result
    }

    private fun objectSql(raw: SupportSQLiteDatabase, name: String): String =
        raw.query("SELECT sql FROM sqlite_master WHERE name = ?", arrayOf(name)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
}
