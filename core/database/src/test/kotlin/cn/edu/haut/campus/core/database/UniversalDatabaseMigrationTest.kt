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
            assertEquals(12, sqlite.version)
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
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                """
                INSERT INTO `library_booking_plans` VALUES (
                    'plan_v6_armed', 'haut', 'LOCAL_DEVICE', '2026-11-12', '07:00:00', 'Asia/Shanghai',
                    1000, 2000, 3000, 'ARMED', 'ARMED', 'ARMED', '五层中区', '05桌 A座', 1,
                    NULL, NULL, NULL, 0, 'NOT_REQUIRED', 'ARMED', 1, 1, 'v6 armed', 500, 500
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `library_booking_plans` VALUES (
                    'plan_v6_confirmed', 'haut', 'LOCAL_DEVICE', '2026-11-11', '07:00:00', 'Asia/Shanghai',
                    1000, 2000, 3000, 'PRECHECK_OK', 'FINAL_CHECK_OK', 'CONFIRMED', '五层中区', '06桌 B座', 1,
                    NULL, '88990011', 'sha_ok', 0, 'NOT_REQUIRED', 'CONFIRMED', 2, 1, 'v6 confirmed', 500, 600
                )
                """.trimIndent()
            )
        }
        withRoom { database ->
            val sqlite = database.openHelper.writableDatabase
            assertEquals(12, sqlite.version)
            val cols = mutableSetOf<String>()
            sqlite.query("PRAGMA table_info(`library_booking_plans`)").use { cursor ->
                val idx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    cols += cursor.getString(idx)
                }
            }
            assertTrue("repeatDays" in cols)
            assertTrue("remainingDays" in cols)
            assertTrue("ownerCloudUserId" in cols)
            assertTrue("ownerStudentId" in cols)

            val armed = kotlinx.coroutines.runBlocking {
                database.cloudAndParcelDao().getLibraryBookingPlanById("plan_v6_armed")
            }
            val confirmed = kotlinx.coroutines.runBlocking {
                database.cloudAndParcelDao().getLibraryBookingPlanById("plan_v6_confirmed")
            }
            assertEquals(1, armed?.repeatDays)
            assertEquals(1, armed?.remainingDays)
            assertEquals(1, confirmed?.repeatDays)
            assertEquals(0, confirmed?.remainingDays)
            assertEquals("88990011", confirmed?.externalBookingId)
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
        assertEquals(12, evidence.newVersion)
        assertEquals(before.keys, source.tableCounts.keys)
        assertEquals(source.serializedTableCounts, evidence.serializedTableCounts)
        factory.open(profile).databaseForAdoption().close()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(LEGACY_DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { database ->
            assertEquals(12, database.version)
            assertEquals(before, snapshot(database))
        }
    }

    private fun withRoom(block: (HautDatabase) -> Unit) {
        val database = openRoom()
        try { block(database) } finally { database.close() }
    }

    private fun openRoom(): HautDatabase =
        Room.databaseBuilder(context, HautDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
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
            return snapshotTables { table, columns ->
                val cols = columns.joinToString(", ") { "`$it`" }
                database.rawQuery("SELECT $cols FROM `$table`", null)
            }
        }
    }

    @Test
    fun migration7To8BackfillsSingleOwnerAndIsolatesUnprovableRows() {
        val dbProvable = "migration_v7_provable.db"
        val dbUnprovable = "migration_v7_unprovable.db"
        try {
            // Case A: Provable single cloud_identity_binding (cloud_user_A) + single student_profile (20240001)
            createVersionSevenDatabase(dbProvable) { db ->
                db.execSQL(
                    """
                    INSERT INTO `cloud_identity_binding`
                    (`profileId`, `cloudUserId`, `ssoIssuer`, `ssoSubject`, `displayName`, `peachBalanceMicropeach`, `holdSupported`, `linkedAtEpochMillis`, `updatedAtEpochMillis`)
                    VALUES ('singleton', 'cloud_user_A', 'https://sso.xpeach.codes', 'sub_A', 'User A', 50000, 0, 1000, 1000)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `student_profile` (`id`, `schoolId`, `displayName`, `major`, `enrollmentYear`)
                    VALUES ('stu_1', '20240001', '张三', '计算机科学与技术', 2024)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `library_booking_plans`
                    (`id`, `schoolId`, `executionMode`, `targetLocalDate`, `targetLocalTime`, `timezoneId`, `precheckAtEpochMillis`, `finalCheckAtEpochMillis`, `fireAtEpochMillis`, `precheckStatus`, `finalCheckStatus`, `fireStatus`, `preferredAreaName`, `preferredSeatLabel`, `allowAlternativeSeat`, `cloudJobId`, `externalBookingId`, `evidenceSha256`, `priceMicropeach`, `paymentState`, `status`, `stateVersion`, `generation`, `lastDiagnosticMessage`, `createdAtEpochMillis`, `updatedAtEpochMillis`, `repeatDays`, `remainingDays`)
                    VALUES ('plan_v7_provable', 'haut', 'CLOUD_HOSTED', '2026-11-25', '07:00:00', 'Asia/Shanghai', 1000, 2000, 3000, 'ARMED', 'ARMED', 'ARMED', '五层中区', '01桌 A座', 1, 'job_v7_1', NULL, NULL, 10000, 'DEBIT_PENDING', 'ARMED', 1, 1, NULL, 1000, 1000, 2, 2)
                    """.trimIndent()
                )
            }
            val migratedProvable = Room.databaseBuilder(context, HautDatabase::class.java, dbProvable)
                .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                .allowMainThreadQueries()
                .build()
            try {
                val p = kotlinx.coroutines.runBlocking {
                    migratedProvable.cloudAndParcelDao().getLibraryBookingPlanById("plan_v7_provable")
                }!!
                assertEquals("cloud_user_A", p.ownerCloudUserId)
                assertEquals("20240001", p.ownerStudentId)
                assertEquals("ARMED", p.status)
            } finally {
                migratedProvable.close()
            }

            // Case B: Unprovable ownership (upgraded while logged out: 0 cloud_identity_binding rows, 0 student_profile rows)
            createVersionSevenDatabase(dbUnprovable) { db ->
                db.execSQL(
                    """
                    INSERT INTO `library_booking_plans`
                    (`id`, `schoolId`, `executionMode`, `targetLocalDate`, `targetLocalTime`, `timezoneId`, `precheckAtEpochMillis`, `finalCheckAtEpochMillis`, `fireAtEpochMillis`, `precheckStatus`, `finalCheckStatus`, `fireStatus`, `preferredAreaName`, `preferredSeatLabel`, `allowAlternativeSeat`, `cloudJobId`, `externalBookingId`, `evidenceSha256`, `priceMicropeach`, `paymentState`, `status`, `stateVersion`, `generation`, `lastDiagnosticMessage`, `createdAtEpochMillis`, `updatedAtEpochMillis`, `repeatDays`, `remainingDays`)
                    VALUES
                    ('plan_v7_orphan_queued', 'haut', 'CLOUD_HOSTED', '2026-11-26', '07:00:00', 'Asia/Shanghai', 1000, 2000, 3000, 'ARMED', 'ARMED', 'ARMED', '五层中区', '02桌 A座', 1, 'job_v7_orphan', NULL, NULL, 10000, 'DEBIT_PENDING', 'ARMED', 1, 1, NULL, 1000, 1000, 3, 3),
                    ('plan_v7_orphan_conf', 'haut', 'CLOUD_HOSTED', '2026-11-26', '07:00:00', 'Asia/Shanghai', 1000, 2000, 3000, 'PRECHECK_OK', 'FINAL_CHECK_OK', 'CONFIRMED', '五层中区', '03桌 A座', 1, 'job_v7_conf', 'bk_v7_88', 'sha_v7_88', 10000, 'PAID', 'CONFIRMED', 2, 1, NULL, 1000, 1000, 1, 0)
                    """.trimIndent()
                )
            }
            val migratedUnprovable = Room.databaseBuilder(context, HautDatabase::class.java, dbUnprovable)
                .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                .allowMainThreadQueries()
                .build()
            try {
                val orphanQueued = kotlinx.coroutines.runBlocking {
                    migratedUnprovable.cloudAndParcelDao().getLibraryBookingPlanById("plan_v7_orphan_queued")
                }!!
                val orphanConf = kotlinx.coroutines.runBlocking {
                    migratedUnprovable.cloudAndParcelDao().getLibraryBookingPlanById("plan_v7_orphan_conf")
                }!!
                assertEquals(null, orphanQueued.ownerCloudUserId)
                assertEquals(null, orphanQueued.ownerStudentId)
                assertEquals("NEEDS_RECONFIRMATION", orphanQueued.status)
                assertEquals(0, orphanQueued.remainingDays)
                assertEquals(null, orphanConf.ownerCloudUserId)
                assertEquals("CONFIRMED", orphanConf.status)
                assertEquals("bk_v7_88", orphanConf.externalBookingId)
            } finally {
                migratedUnprovable.close()
            }
        } finally {
            context.deleteDatabase(dbProvable)
            context.deleteDatabase(dbUnprovable)
        }
    }

    @Test
    fun `migration8To9AddsMoveColumnsAndExamAuditPreservingLegacyRows`() {
        val dbName = "migration_v8_move_exam.db"
        try {
            createVersionEightDatabase(dbName) { db ->
                // v8 旧调课行：无 moveId/moveRole 列，验证迁移后保持 NULL 不被猜测回填
                db.execSQL(
                    """
                    INSERT INTO `occurrence_overrides`
                    (`id`, `termId`, `courseRuleId`, `courseId`, `dateIso`, `startPeriod`, `endPeriod`, `startAtIso`, `endAtIso`, `room`, `buildingId`, `canceled`, `reason`)
                    VALUES
                    ('ov_v8_src', '2026-2027-1', 'r_1', 'c_1', '2026-10-12', NULL, NULL, NULL, NULL, NULL, NULL, 1, '已调至 2026-10-14'),
                    ('ov_v8_tgt', '2026-2027-1', NULL, 'c_1', '2026-10-14', 1, 2, NULL, NULL, '旧教室', NULL, 0, '由 2026-10-12 调入'),
                    ('ov_v8_plain', '2026-2027-1', 'r_2', 'c_1', '2026-10-13', NULL, NULL, NULL, NULL, NULL, NULL, 1, '临时停课')
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `exam_schedules`
                    (`id`, `termId`, `courseCode`, `courseName`, `examBatchName`, `examStageLabel`, `examFormat`, `rawTimeText`, `examDateIso`, `startTimeIso`, `endTimeIso`, `rawRoom`, `seatNumber`, `campusName`, `isRetake`, `syncedAtEpochMilli`)
                    VALUES ('ex_v8', '2026-2027-1', 'K1', '合成考试', '期末考试', '期末集中考', '闭卷', '时间待定', NULL, NULL, NULL, '待定考场', NULL, '莲花街校区', 0, 1000)
                    """.trimIndent()
                )
            }
            val migrated = Room.databaseBuilder(context, HautDatabase::class.java, dbName)
                .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                .allowMainThreadQueries()
                .build()
            try {
                val sqlite = migrated.openHelper.writableDatabase
                assertEquals(12, sqlite.version)
                // 新列存在且旧行保持 NULL
                val cols = mutableSetOf<String>()
                sqlite.query("PRAGMA table_info(`occurrence_overrides`)").use { cursor ->
                    val idx = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) cols += cursor.getString(idx)
                }
                assertTrue(cols.containsAll(listOf("moveId", "moveRole")))
                sqlite.query("SELECT id, moveId, moveRole FROM `occurrence_overrides` ORDER BY id").use { cursor ->
                    var count = 0
                    while (cursor.moveToNext()) {
                        count++
                        assertTrue(cursor.isNull(1))
                        assertTrue(cursor.isNull(2))
                    }
                    assertEquals(3, count)
                }
                // 索引已创建
                sqlite.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_occurrence_overrides_termId_moveId_moveRole'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                }
                // exam_schedules 增加 originTermId 且旧行为 NULL
                sqlite.query("SELECT originTermId FROM `exam_schedules` WHERE id='ex_v8'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.isNull(0))
                }
                // 审计表已创建且为空
                sqlite.query("SELECT COUNT(*) FROM `exam_term_repair_audits`").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migration10To11PreservesPeriodRowsAndCreatesSchemeStructures() {
        val dbName = "migration_v10_schedule_scheme.db"
        try {
            createVersionedDatabase(dbName, 10) { db ->
                db.execSQL(
                    """
                    INSERT INTO period_times
                    (id, timeTableId, periodNo, startLocalTimeIso, endLocalTimeIso, sectionLabel)
                    VALUES
                    ('pt_1', 'haut_lianhuajie_default', 1, '08:30', '09:15', '上午'),
                    ('pt_user_custom', 'haut_lianhuajie_default', 2, '09:21', '10:06', '上午'),
                    ('manual_period_1', 'manual_default', 1, '08:00', '08:45', '上午')
                    """.trimIndent()
                )
            }
            val expectedSemantics = mutableMapOf<String, List<String?>>()
            SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY).use { pre ->
                pre.rawQuery("SELECT id, timeTableId, periodNo, startLocalTimeIso, endLocalTimeIso, sectionLabel FROM period_times ORDER BY id", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        expectedSemantics[cursor.getString(0)] = (1 until cursor.columnCount).map {
                            if (cursor.isNull(it)) null else cursor.getString(it)
                        }
                    }
                }
            }

            val migrated = Room.databaseBuilder(context, HautDatabase::class.java, dbName)
                .addMigrations(MIGRATION_10_11, MIGRATION_11_12)
                .allowMainThreadQueries()
                .build()
            try {
                val sqlite = migrated.openHelper.writableDatabase
                assertEquals(12, sqlite.version)
                val cols = mutableSetOf<String>()
                sqlite.query("PRAGMA table_info(period_times)").use { cursor ->
                    val idx = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) cols += cursor.getString(idx)
                }
                assertTrue(cols.containsAll(listOf("schemeId", "termId", "validStartDateIso", "validEndDateIso", "userModified", "updatedAtEpochMillis")))

                sqlite.query("SELECT id, timeTableId, periodNo, startLocalTimeIso, endLocalTimeIso, sectionLabel, schemeId, termId, validStartDateIso, validEndDateIso, userModified, updatedAtEpochMillis FROM period_times ORDER BY id").use { cursor ->
                    var count = 0
                    while (cursor.moveToNext()) {
                        count++
                        val actual = (1..5).map { if (cursor.isNull(it)) null else cursor.getString(it) }
                        assertEquals("旧 period_times 语义字段必须原样保留", expectedSemantics[cursor.getString(0)], actual)
                        assertTrue(cursor.isNull(6))
                        assertTrue(cursor.isNull(7))
                        assertTrue(cursor.isNull(8))
                        assertTrue(cursor.isNull(9))
                        assertEquals(0, cursor.getInt(10))
                        assertEquals(0, cursor.getLong(11))
                    }
                    assertEquals(expectedSemantics.size, count)
                }
                sqlite.query("SELECT COUNT(*) FROM period_time_schemes").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                sqlite.query("SELECT name FROM sqlite_master WHERE type='index' AND name IN ('index_period_times_schemeId','index_period_times_termId','index_period_time_schemes_termId','index_period_time_schemes_timeTableId')").use { cursor ->
                    var count = 0
                    while (cursor.moveToNext()) count++
                    assertEquals(4, count)
                }
            } finally {
                migrated.close()
            }

            val reopened = Room.databaseBuilder(context, HautDatabase::class.java, dbName)
                .addMigrations(MIGRATION_10_11, MIGRATION_11_12)
                .allowMainThreadQueries()
                .build()
            try {
                assertEquals(12, reopened.openHelper.writableDatabase.version)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migration10To11RollbackKeepsV10RowsAndAllowsRetry() {
        val dbName = "migration_v10_schedule_scheme_rollback.db"
        try {
            createVersionedDatabase(dbName, 10) { db ->
                db.execSQL(
                    """
                    INSERT INTO period_times
                    (id, timeTableId, periodNo, startLocalTimeIso, endLocalTimeIso, sectionLabel)
                    VALUES ('pt_keep', 'tt', 1, '08:00', '08:45', '上午')
                    """.trimIndent()
                )
            }
            val failing = Room.databaseBuilder(context, HautDatabase::class.java, dbName)
                .addMigrations(object : Migration(10, 11) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        MIGRATION_10_11.migrate(db)
                        error("合成 v11 迁移中断")
                    }
                }, MIGRATION_11_12)
                .allowMainThreadQueries()
                .build()
            try {
                assertTrue(runCatching { failing.openHelper.writableDatabase }.isFailure)
            } finally {
                failing.close()
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                assertEquals(10, sqlite.version)
                sqlite.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='period_time_schemes'", null).use { cursor ->
                    assertFalse("失败事务不得遗留 period_time_schemes", cursor.moveToFirst())
                }
                sqlite.rawQuery("PRAGMA table_info(period_times)", null).use { cursor ->
                    val idx = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        assertFalse("失败事务不得遗留 schemeId 列", cursor.getString(idx) == "schemeId")
                    }
                }
                sqlite.rawQuery("SELECT startLocalTimeIso, endLocalTimeIso FROM period_times WHERE id='pt_keep'", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("08:00", cursor.getString(0))
                    assertEquals("08:45", cursor.getString(1))
                }
            }

            val retried = Room.databaseBuilder(context, HautDatabase::class.java, dbName)
                .addMigrations(MIGRATION_10_11, MIGRATION_11_12)
                .allowMainThreadQueries()
                .build()
            try {
                val sqlite = retried.openHelper.writableDatabase
                assertEquals(12, sqlite.version)
                sqlite.query("SELECT startLocalTimeIso, endLocalTimeIso, schemeId FROM period_times WHERE id='pt_keep'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("08:00", cursor.getString(0))
                    assertEquals("08:45", cursor.getString(1))
                    assertTrue(cursor.isNull(2))
                }
            } finally {
                retried.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * v9 -> v10 真实旧库升级（MigrationTestHelper 等价契约）：建 v9 schema 实体库 →
     * 写入全类别覆盖行 → 经 Room 真实迁移链打开并校验结构 + 数据 + 令牌分配。
     * 覆盖矩阵：普通取消 / 调课多源一目标 / 暂停的调入目标 / 同 ID 历史风格行 / 旧版模糊目标。
     */
    @Test
    fun migration9To10AssignsUniqueMutationTokensAndPreservesEveryOverrideRow() {
        val dbName = "migration_v9_token.db"
        val uuidShape = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        try {
            createVersionNineDatabase(dbName) { db ->
                db.execSQL(
                    """
                    INSERT INTO occurrence_overrides
                    (id, termId, courseRuleId, courseId, dateIso, startPeriod, endPeriod, startAtIso, endAtIso, room, buildingId, canceled, reason, moveId, moveRole)
                    VALUES
                    ('ov_cancel_2026-10-12_r_1', '2026-2027-1', 'r_1', 'c_1', '2026-10-12', NULL, NULL, NULL, NULL, NULL, NULL, 1, '临时停课', NULL, NULL),
                    ('ov_src_a', '2026-2027-1', 'r_1', 'c_1', '2026-10-19', NULL, NULL, NULL, NULL, NULL, NULL, 1, '已调至 2026-10-21', 'mv_1', 'SOURCE'),
                    ('ov_src_b', '2026-2027-1', 'r_2', 'c_1', '2026-10-19', NULL, NULL, NULL, NULL, NULL, NULL, 1, '已调至 2026-10-21', 'mv_1', 'SOURCE'),
                    ('ov_tgt_mv1', '2026-2027-1', NULL, 'c_1', '2026-10-21', 1, 4, NULL, NULL, '合成大教室', NULL, 0, '由 2026-10-19 调入', 'mv_1', 'TARGET'),
                    ('ov_src_mv2', '2026-2027-1', 'r_3', 'c_2', '2026-10-20', NULL, NULL, NULL, NULL, NULL, NULL, 1, '已调至 2026-10-22', 'mv_2', 'SOURCE'),
                    ('ov_tgt_mv2_paused', '2026-2027-1', NULL, 'c_2', '2026-10-22', 5, 6, NULL, NULL, '合成教室', NULL, 1, '由 2026-10-20 调入', 'mv_2', 'TARGET'),
                    ('ov_legacy_tgt', '2026-2027-1', NULL, 'c_1', '2026-10-15', 1, 2, NULL, NULL, '旧教室', NULL, 0, '由 2026-10-12 调入', NULL, NULL),
                    ('ov_xterm', '2026-2027-2', 'r_9', 'c_9', '2026-10-13', NULL, NULL, NULL, NULL, NULL, NULL, 1, '已调至 2026-10-14', 'mv_x', 'SOURCE')
                    """.trimIndent()
                )
            }
            // 迁移前快照全部语义字段，迁移后逐行对比一字不差
            val expectedSemantics = mutableMapOf<String, List<String?>>()
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY
            ).use { pre ->
                pre.rawQuery(
                    "SELECT id, termId, courseRuleId, courseId, dateIso, startPeriod, endPeriod, startAtIso, endAtIso, room, buildingId, canceled, reason, moveId, moveRole FROM occurrence_overrides ORDER BY id",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        expectedSemantics[cursor.getString(0)] = (1 until cursor.columnCount).map {
                            if (cursor.isNull(it)) null else cursor.getString(it)
                        }
                    }
                }
            }

            val migrated = Room.databaseBuilder(context, HautDatabase::class.java, dbName)
                .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                .allowMainThreadQueries()
                .build()
            try {
                val sqlite = migrated.openHelper.writableDatabase
                assertEquals(12, sqlite.version)
                // 结构校验：mutationToken 非空列已建立
                val cols = mutableSetOf<String>()
                sqlite.query("PRAGMA table_info(occurrence_overrides)").use { cursor ->
                    val idx = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) cols += cursor.getString(idx)
                }
                assertTrue(cols.contains("mutationToken"))

                // 每行令牌：非空、UUID 形态、全局唯一不重复
                val tokens = mutableMapOf<String, String>()
                sqlite.query("SELECT id, mutationToken FROM occurrence_overrides").use { cursor ->
                    while (cursor.moveToNext()) {
                        val token = cursor.getString(1)
                        assertTrue("旧行令牌必须已分配", token.isNotBlank())
                        assertTrue("令牌必须是 UUID 形态: " + token, uuidShape.matches(token))
                        tokens[cursor.getString(0)] = token
                    }
                }
                assertEquals(expectedSemantics.size, tokens.size)
                assertEquals("令牌必须全局唯一", tokens.size, tokens.values.toSet().size)

                // 语义字段逐行保留：普通取消/调课多源/暂停目标/同 ID 历史/旧模糊目标/他学期行原样
                sqlite.query(
                    "SELECT id, termId, courseRuleId, courseId, dateIso, startPeriod, endPeriod, startAtIso, endAtIso, room, buildingId, canceled, reason, moveId, moveRole FROM occurrence_overrides ORDER BY id"
                ).use { cursor ->
                    var count = 0
                    while (cursor.moveToNext()) {
                        count++
                        val actual = (1 until cursor.columnCount).map {
                            if (cursor.isNull(it)) null else cursor.getString(it)
                        }
                        assertEquals(
                            "行 " + cursor.getString(0) + " 语义字段必须原样保留",
                            expectedSemantics[cursor.getString(0)], actual
                        )
                    }
                    assertEquals(expectedSemantics.size, count)
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migration11To12PreservesV11RowsAndCreatesVaultTables() {
        val dbName = "migration_v11_vault.db"
        try {
            createVersionedDatabase(dbName, 11) { db ->
                db.execSQL(
                    """
                    INSERT INTO period_times
                    (id, timeTableId, periodNo, startLocalTimeIso, endLocalTimeIso, sectionLabel)
                    VALUES ('pt_v11_keep', 'haut_lianhuajie_default', 1, '08:30', '09:15', '上午')
                    """.trimIndent()
                )
            }

            val migrated = Room.databaseBuilder(context, HautDatabase::class.java, dbName)
                .addMigrations(MIGRATION_11_12)
                .allowMainThreadQueries()
                .build()
            try {
                val sqlite = migrated.openHelper.writableDatabase
                assertEquals(12, sqlite.version)
                sqlite.query("SELECT startLocalTimeIso, endLocalTimeIso, userModified, updatedAtEpochMillis FROM period_times WHERE id='pt_v11_keep'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("08:30", cursor.getString(0))
                    assertEquals("09:15", cursor.getString(1))
                    assertEquals(0, cursor.getInt(2))
                    assertEquals(0, cursor.getLong(3))
                }
                for (tableName in listOf("personal_events", "entity_journal", "domain_outbox", "search_docs", "blob_store")) {
                    sqlite.query("SELECT COUNT(*) FROM " + tableName).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
                }
                sqlite.query("SELECT COUNT(*) FROM search_fts").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            } finally {
                migrated.close()
            }

            val reopened = Room.databaseBuilder(context, HautDatabase::class.java, dbName)
                .addMigrations(MIGRATION_11_12)
                .allowMainThreadQueries()
                .build()
            try {
                assertEquals(12, reopened.openHelper.writableDatabase.version)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migration11To12RollbackKeepsV11RowsAndAllowsRetry() {
        val dbName = "migration_v11_vault_rollback.db"
        try {
            createVersionedDatabase(dbName, 11) { db ->
                db.execSQL(
                    """
                    INSERT INTO period_times
                    (id, timeTableId, periodNo, startLocalTimeIso, endLocalTimeIso, sectionLabel)
                    VALUES ('pt_v11_rollback_keep', 'tt', 1, '08:00', '08:45', '上午')
                    """.trimIndent()
                )
            }
            val failing = Room.databaseBuilder(context, HautDatabase::class.java, dbName)
                .addMigrations(object : Migration(11, 12) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        MIGRATION_11_12.migrate(db)
                        error("合成 v12 迁移中断")
                    }
                })
                .allowMainThreadQueries()
                .build()
            try {
                assertTrue(runCatching { failing.openHelper.writableDatabase }.isFailure)
            } finally {
                failing.close()
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                assertEquals(11, sqlite.version)
                for (tableName in listOf("personal_events", "entity_journal", "domain_outbox", "search_docs", "search_fts", "blob_store")) {
                    sqlite.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tableName)).use { cursor ->
                        assertFalse("失败事务不得遗留 " + tableName, cursor.moveToFirst())
                    }
                }
                sqlite.rawQuery("SELECT startLocalTimeIso FROM period_times WHERE id='pt_v11_rollback_keep'", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("08:00", cursor.getString(0))
                }
            }

            val retried = Room.databaseBuilder(context, HautDatabase::class.java, dbName)
                .addMigrations(MIGRATION_11_12)
                .allowMainThreadQueries()
                .build()
            try {
                val sqlite = retried.openHelper.writableDatabase
                assertEquals(12, sqlite.version)
                sqlite.query("SELECT startLocalTimeIso FROM period_times WHERE id='pt_v11_rollback_keep'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("08:00", cursor.getString(0))
                }
            } finally {
                retried.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    private fun createVersionNineDatabase(
        name: String,
        seed: (SQLiteDatabase) -> Unit
    ) = createVersionedDatabase(name, 9, seed)

    private fun createVersionEightDatabase(
        name: String,
        seed: (SQLiteDatabase) -> Unit
    ) {
        context.deleteDatabase(name)
        val path = "cn.edu.haut.campus.core.database.HautDatabase/8.json"
        val content = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "缺少已提交的 Room v8 schema"
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
            seed(database)
            database.version = 8
        }
    }

    /**
     * 与 MigrationTestHelper 契约等价的最小实现：按导出的旧版 schema 建真实 SQLite 库并置版本号，
     * 再经 Room.databaseBuilder + addMigrations 走真实迁移链打开校验（离线环境无 room-testing 依赖）。
     */
    private fun createVersionedDatabase(
        name: String,
        version: Int,
        seed: (SQLiteDatabase) -> Unit
    ) {
        context.deleteDatabase(name)
        val path = "cn.edu.haut.campus.core.database.HautDatabase/" + version + ".json"
        val content = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "缺少已提交的 Room v" + version + " schema"
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
            seed(database)
            database.version = version
        }
    }

    private fun createVersionSevenDatabase(
        name: String,
        seed: (SQLiteDatabase) -> Unit
    ) {
        context.deleteDatabase(name)
        val path = "cn.edu.haut.campus.core.database.HautDatabase/7.json"
        val content = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "缺少已提交的 Room v7 schema"
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
            seed(database)
            database.version = 7
        }
    }

    private fun snapshot(database: SupportSQLiteDatabase): Map<String, String> =
        snapshotTables { table, columns ->
            val cols = columns.joinToString(", ") { "`$it`" }
            database.query("SELECT $cols FROM `$table`")
        }

    private fun snapshot(database: SQLiteDatabase): Map<String, String> =
        snapshotTables { table, columns ->
            val cols = columns.joinToString(", ") { "`$it`" }
            database.rawQuery("SELECT $cols FROM `$table`", null)
        }

    private fun snapshotTables(queryForTable: (String, List<String>) -> android.database.Cursor): Map<String, String> {
        val entities = oldSchema().getJSONArray("entities")
        return (0 until entities.length()).associate { index ->
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            val fields = entity.getJSONArray("fields")
            val columns = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
            val content = queryForTable(table, columns).use { cursor ->
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
