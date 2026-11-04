package cn.edu.haut.campus.core.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/** 使用导出的 v4 schema 与合成数据校验真实 SQLite 事务，不包含任何学生数据。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class UniversalDatabaseMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val databaseName = "universal-migration-test.db"

    @After
    fun cleanDatabase() {
        context.deleteDatabase(databaseName)
        context.deleteDatabase(LEGACY_DATABASE_NAME)
    }

    @Test
    fun `迁移到 v5 与 v6 保留 v4 全部表的字段与记录且重复打开不改变数据`() {
        val before = createVersionFourDatabase()
        withRoom { database ->
            val sqlite = database.openHelper.writableDatabase
            assertEquals(7, sqlite.version)
            assertEquals(before, snapshot(sqlite))
            for (tableName in listOf(
                "unscheduled_academic_courses",
                "cloud_identity_binding",
                "library_booking_plans",
                "parcel_items",
                "parcel_source_connections",
                "installed_extension_plugins"
            )) {
                sqlite.query("SELECT COUNT(*) FROM $tableName").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
        }
        withRoom { database ->
            assertEquals(before, snapshot(database.openHelper.writableDatabase))
        }
    }

    @Test
    fun `从 v6 升级到 v7 正常校验 identityHash 并为 library_booking_plans 补齐连续抢座字段`() {
        createVersionSixDatabase()
        withRoom { database ->
            val sqlite = database.openHelper.writableDatabase
            assertEquals(7, sqlite.version)
            val cols = mutableSetOf<String>()
            sqlite.query("PRAGMA table_info(`library_booking_plans`)").use { cursor ->
                val idx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    cols += cursor.getString(idx)
                }
            }
            assertTrue("repeatDays" in cols)
            assertTrue("remainingDays" in cols)
        }
    }

    @Test
    fun `迁移事务中断会回滚新增表并保留 v4 数据随后可再次升级`() {
        val before = createVersionFourDatabase()
        val interrupted = Room.databaseBuilder(context, HautDatabase::class.java, databaseName)
            .addMigrations(object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    MIGRATION_4_5.migrate(db)
                    error("合成迁移中断")
                }
            })
            .allowMainThreadQueries()
            .build()
        try {
            val failure = runCatching { interrupted.openHelper.writableDatabase }.exceptionOrNull()
            assertTrue("故障注入必须使迁移失败", failure != null)
        } finally {
            interrupted.close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(4, sqlite.version)
            sqlite.rawQuery("SELECT name FROM sqlite_master WHERE name = 'unscheduled_academic_courses'", null).use { cursor ->
                assertFalse("失败事务不应遗留新表", cursor.moveToFirst())
            }
        }
        withRoom { database ->
            assertEquals(before, snapshot(database.openHelper.writableDatabase))
        }
    }

    @Test
    fun `旧库 adoption 在 v4 迁移到 v5 后仍保留源版本和逐表计数`() {
        val before = createVersionFourDatabase(LEGACY_DATABASE_NAME)
        val profile = AccountProfileEntity(
            id = "legacy-profile",
            accountId = "legacy-account",
            schoolId = "haut",
            schoolName = "河南工业大学",
            province = "河南省",
            city = "郑州市",
            campusId = null,
            campusName = null,
            timeZoneId = "Asia/Shanghai",
            label = "原有身份",
            isLegacy = true,
            databaseName = LEGACY_DATABASE_NAME,
            preferencesName = LEGACY_PREFERENCES_NAME,
            createdAtEpochMs = 1L
        )
        val factory = AccountDatabaseFactory(context)

        val source = factory.inspectLegacy(profile)
        val evidence = factory.openAndVerifyLegacy(profile, source)

        assertEquals(4, source.oldVersion)
        assertEquals(4, evidence.oldVersion)
        assertEquals(7, evidence.newVersion)
        assertEquals(before.keys, source.tableCounts.keys)
        assertEquals(source.serializedTableCounts, evidence.serializedTableCounts)
        factory.open(profile).databaseForAdoption().close()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(LEGACY_DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { database ->
            assertEquals(7, database.version)
            assertEquals(before, snapshot(database))
        }
    }

    private fun withRoom(block: (HautDatabase) -> Unit) {
        val database = openRoom()
        try { block(database) } finally { database.close() }
    }

    private fun openRoom(): HautDatabase =
        Room.databaseBuilder(context, HautDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()

    private fun createVersionSixDatabase(name: String = databaseName) {
        context.deleteDatabase(name)
        val path = "cn.edu.haut.campus.core.database.HautDatabase/6.json"
        val content = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "缺少已提交的 Room v6 schema"
        }.bufferedReader().use { it.readText() }
        val schema = JSONObject(content).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { database ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (item in 0 until indices.length()) {
                    database.execSQL(indices.getJSONObject(item).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val queries = schema.getJSONArray("setupQueries")
            for (index in 0 until queries.length()) database.execSQL(queries.getString(index))
            database.version = 6
        }
    }

    private fun oldSchema(): JSONObject {
        val path = "cn.edu.haut.campus.core.database.HautDatabase/4.json"
        val content = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "缺少已提交的 Room v4 schema"
        }.bufferedReader().use { it.readText() }
        return JSONObject(content).getJSONObject("database")
    }

    private fun createVersionFourDatabase(name: String = databaseName): Map<String, String> {
        context.deleteDatabase(name)
        val schema = oldSchema()
        context.openOrCreateDatabase(name, 0, null).use { database ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (item in 0 until indices.length()) {
                    database.execSQL(indices.getJSONObject(item).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val fields = entity.getJSONArray("fields")
                val row = ContentValues()
                for (fieldIndex in 0 until fields.length()) {
                    val field = fields.getJSONObject(fieldIndex)
                    val name = field.getString("columnName")
                    when (field.getString("affinity")) {
                        "INTEGER" -> row.put(name, 1L)
                        "REAL" -> row.put(name, 1.25)
                        "BLOB" -> row.put(name, byteArrayOf(1, 2, 3))
                        else -> row.put(name, "合成数据_$name")
                    }
                }
                assertTrue(database.insertOrThrow(table, null, row) != -1L)
            }
            val queries = schema.getJSONArray("setupQueries")
            for (index in 0 until queries.length()) database.execSQL(queries.getString(index))
            database.version = 4
            return snapshotTables { table ->
                database.rawQuery("SELECT * FROM `$table`", null)
            }
        }
    }

    private fun snapshot(database: SupportSQLiteDatabase): Map<String, String> =
        snapshotTables { table -> database.query("SELECT * FROM `$table`") }

    private fun snapshot(database: SQLiteDatabase): Map<String, String> =
        snapshotTables { table -> database.rawQuery("SELECT * FROM `$table`", null) }

    private fun snapshotTables(query: (String) -> android.database.Cursor): Map<String, String> {
        val entities = oldSchema().getJSONArray("entities")
        return (0 until entities.length()).associate { index ->
            val table = entities.getJSONObject(index).getString("tableName")
            val content = query(table).use { cursor ->
                buildString {
                    append(cursor.columnNames.joinToString("|"))
                    append('\n')
                    while (cursor.moveToNext()) {
                        for (column in 0 until cursor.columnCount) {
                            val value = cursor.getString(column)
                            append(value?.length ?: -1).append(':').append(value).append('|')
                        }
                        append('\n')
                    }
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(StandardCharsets.UTF_8.encode(content))
            table to digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
