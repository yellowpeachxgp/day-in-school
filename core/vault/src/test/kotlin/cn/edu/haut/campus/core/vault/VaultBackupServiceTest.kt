package cn.edu.haut.campus.core.vault

import androidx.room.Room
import cn.edu.haut.campus.core.database.CourseEntity
import cn.edu.haut.campus.core.database.CourseScheduleRuleEntity
import cn.edu.haut.campus.core.database.ExamScheduleEntity
import cn.edu.haut.campus.core.database.GradeAttemptEntity
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.database.OccurrenceOverrideEntity
import cn.edu.haut.campus.core.database.PersonalEventEntity
import cn.edu.haut.campus.core.database.UnscheduledAcademicCourseEntity
import cn.edu.haut.campus.core.database.UtilityMeterEntity
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VaultBackupServiceTest {
    private lateinit var database: HautDatabase
    private lateinit var journal: RoomVaultJournal
    private lateinit var service: VaultBackupService
    private var generatedId = 0
    private val passphrase = "backup-passphrase".toCharArray()
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct_backup"),
        profileId = ProfileId("prof_backup"),
        campusId = CampusId("lianhuajie")
    )

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            HautDatabase::class.java
        ).allowMainThreadQueries().build()
        val searchIndex = RoomSearchIndex(database, clock = { fixedInstant() })
        journal = RoomVaultJournal(
            database = database,
            outbox = RoomEventOutbox(database),
            snapshotStores = listOf(
                CourseEntitySnapshotStore(database),
                GradeAttemptSnapshotStore(database),
                ExamScheduleSnapshotStore(database),
                PersonalEventSnapshotStore(database, searchIndex)
            ),
            clock = { fixedInstant() },
            idGenerator = { "backup-test-" + (++generatedId) },
            searchIndex = searchIndex
        )
        service = newService()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun exportWipeReplaceRestoreHasZeroPortablePayloadDiffAndRestoreAudit() = runBlocking {
        seedPortableRows()
        val first = exportBackup()
        assertFalse(first.first.startsWithZipMagic())
        wipePortableTables()
        assertEquals(0L, countRows("courses"))
        assertEquals(0L, countRows("personal_events"))
        assertEquals(0L, countRows("blob_store"))

        val preview = service.inspect(ByteArrayInputStream(first.first), passphrase)
        assertEquals(0, preview.conflicts.size)
        val report = service.restore(preview, RestoreMode.REPLACE, emptyMap())

        assertTrue(report.journaledEntities > 0)
        assertTrue(database.vaultDao().countEntityJournal() > 0)
        assertTrue(database.vaultDao().countDomainOutbox() > 0)
        val restoreHistory = journal.history(EntityRef(EntityType.COURSE, "course-1", scope)).first()
        assertTrue(restoreHistory.any { it.actor == Actor.RESTORE })
        assertEquals("高等数学A（一）", database.campusDao().getCourseById("course-1")?.name)
        assertEquals("复习离散数学", database.vaultDao().getPersonalEvent("pe-1")?.title)
        assertNotNull(database.vaultDao().getBlob(database.vaultDao().getBlobsSnapshot().single().blobId))
        val hits = RoomSearchIndex(database).query(scope, SearchQuery(scope, "离散")).hits
        assertTrue(hits.any { it.document.ref.id == "pe-1" })
        val restoredToken = database.campusDao().getOccurrenceOverrideById("ov-1")?.mutationToken
        assertTrue(restoredToken?.isNotBlank() == true)
        assertNotEquals("frozen-token", restoredToken)

        val second = exportBackup()
        assertEquals(first.second.payloadSha256, second.second.payloadSha256)
        assertEquals(first.second.tables, second.second.tables)
    }

    @Test
    fun wrongPassphraseAndCorruptedCiphertextAreDistinctAndDoNotWrite() = runBlocking {
        seedPortableRows()
        val backup = exportBackup().first
        val journalBefore = database.vaultDao().countEntityJournal()
        val coursesBefore = countRows("courses")

        val wrong = runCatching {
            service.inspect(ByteArrayInputStream(backup), "wrong-passphrase".toCharArray())
        }.exceptionOrNull()
        assertTrue(wrong is BackupWrongPassphraseException)

        val corrupted = backup.copyOf()
        corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 0x01).toByte()
        val damaged = runCatching {
            service.inspect(ByteArrayInputStream(corrupted), passphrase)
        }.exceptionOrNull()
        assertTrue(damaged is BackupCorruptedException)

        assertEquals(journalBefore, database.vaultDao().countEntityJournal())
        assertEquals(coursesBefore, countRows("courses"))
        assertEquals("高等数学A（一）", database.campusDao().getCourseById("course-1")?.name)
    }

    @Test
    fun tooNewFormatVersionFailsBeforeAnyRestoreWrite() = runBlocking {
        seedPortableRows()
        val backup = exportBackup().first
        val modified = reencryptWithManifestVersion(backup, 2)
        val journalBefore = database.vaultDao().countEntityJournal()

        val error = runCatching {
            service.inspect(ByteArrayInputStream(modified), passphrase)
        }.exceptionOrNull()

        assertTrue(error is BackupVersionTooNewException)
        assertEquals("format", (error as BackupVersionTooNewException).component)
        assertEquals(journalBefore, database.vaultDao().countEntityJournal())
        assertEquals("高等数学A（一）", database.campusDao().getCourseById("course-1")?.name)
    }

    @Test
    fun tooNewSchemaVersionFailsBeforeAnyRestoreWrite() = runBlocking {
        seedPortableRows()
        val backup = exportBackup().first
        val modified = reencryptWithManifestVersion(backup, 999, fieldName = "schemaVersion")
        val journalBefore = database.vaultDao().countEntityJournal()

        val error = runCatching {
            service.inspect(ByteArrayInputStream(modified), passphrase)
        }.exceptionOrNull()

        assertTrue(error is BackupVersionTooNewException)
        assertEquals("schema", (error as BackupVersionTooNewException).component)
        assertEquals(journalBefore, database.vaultDao().countEntityJournal())
        assertEquals("高等数学A（一）", database.campusDao().getCourseById("course-1")?.name)
    }

    @Test
    fun stalePreviewFailsWithoutTouchingNewerLocalWrite() = runBlocking {
        seedPortableRows()
        val backup = exportBackup().first
        val preview = service.inspect(ByteArrayInputStream(backup), passphrase)
        database.campusDao().upsertCourse(courseRow().copy(name = "预览后的本地写入"))
        val journalBefore = database.vaultDao().countEntityJournal()

        val error = runCatching {
            service.restore(preview, RestoreMode.REPLACE, emptyMap())
        }.exceptionOrNull()

        assertTrue(error is BackupRestoreStaleException)
        assertEquals("预览后的本地写入", database.campusDao().getCourseById("course-1")?.name)
        assertEquals(journalBefore, database.vaultDao().countEntityJournal())
    }

    @Test
    fun identityMismatchIsExplicitAndDoesNotWrite() = runBlocking {
        seedPortableRows()
        val backup = exportBackup().first
        val otherScope = scope.copy(profileId = ProfileId("prof_other"))
        val otherService = newService(scopeOverride = otherScope)
        val journalBefore = database.vaultDao().countEntityJournal()

        val error = runCatching {
            otherService.inspect(ByteArrayInputStream(backup), passphrase)
        }.exceptionOrNull()

        assertTrue(error is BackupIdentityMismatchException)
        assertEquals(journalBefore, database.vaultDao().countEntityJournal())
        assertEquals("高等数学A（一）", database.campusDao().getCourseById("course-1")?.name)
    }

    @Test
    fun injectedFailureDuringReplaceRollsBackEveryRowAndLeavesNoRestoreJournal() = runBlocking {
        seedPortableRows()
        val backup = exportBackup().first
        val failingService = newService(mutationTokenGenerator = { error("injected restore failure") })
        val preview = failingService.inspect(ByteArrayInputStream(backup), passphrase)
        val journalBefore = database.vaultDao().countEntityJournal()
        val outboxBefore = database.vaultDao().countDomainOutbox()

        val error = runCatching {
            failingService.restore(preview, RestoreMode.REPLACE, emptyMap())
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals("injected restore failure", error?.message)
        assertEquals("高等数学A（一）", database.campusDao().getCourseById("course-1")?.name)
        assertEquals("frozen-token", database.campusDao().getOccurrenceOverrideById("ov-1")?.mutationToken)
        assertEquals("复习离散数学", database.vaultDao().getPersonalEvent("pe-1")?.title)
        assertEquals(1L, countRows("blob_store"))
        assertEquals(journalBefore, database.vaultDao().countEntityJournal())
        assertEquals(outboxBefore, database.vaultDao().countDomainOutbox())
    }

    @Test
    fun mergeRequiresExplicitConflictChoiceThenHonorsKeepOrUseBackup() = runBlocking {
        seedPortableRows()
        val backup = exportBackup().first
        database.campusDao().upsertCourse(courseRow().copy(name = "本地新名字"))
        val preview = service.inspect(ByteArrayInputStream(backup), passphrase)
        val conflict = preview.conflicts.single { it.key.table == "courses" }

        val missing = runCatching {
            service.restore(preview, RestoreMode.MERGE, emptyMap())
        }.exceptionOrNull()
        assertTrue(missing is BackupRestoreConflictException)
        assertEquals("本地新名字", database.campusDao().getCourseById("course-1")?.name)

        val keepReport = service.restore(
            preview,
            RestoreMode.MERGE,
            mapOf(conflict.key to BackupConflictResolution.KEEP_EXISTING)
        )
        assertEquals(1, keepReport.keptExistingRows)
        assertEquals("本地新名字", database.campusDao().getCourseById("course-1")?.name)

        val useReport = service.restore(
            preview,
            RestoreMode.MERGE,
            mapOf(conflict.key to BackupConflictResolution.USE_BACKUP)
        )
        assertEquals(1, useReport.updatedRows)
        assertEquals("高等数学A（一）", database.campusDao().getCourseById("course-1")?.name)
    }

    @Test
    fun backupSchemaContainsNoCredentialCookieAiKeyOrDeviceAuthTables() = runBlocking {
        seedPortableRows()
        val backup = exportBackup().first
        val plaintext = BackupEnvelopeCrypto.decrypt(backup, passphrase)
        val entries = BackupZip.read(plaintext)
        val manifestText = String(entries.getValue(BackupFormatConstants.MANIFEST_ENTRY), Charsets.UTF_8)
        val dataText = String(entries.getValue(BackupFormatConstants.DATA_ENTRY), Charsets.UTF_8)
        val combined = manifestText + dataText
        val schemaKeys = jsonKeys(Json.parse(manifestText)) + jsonKeys(Json.parse(dataText))
        val forbiddenMarkers = listOf(
            "password", "passwd", "credential", "cookie", "authorization", "token",
            "secret", "apikey", "aikey", "privatekey", "sessionid"
        )

        assertTrue(schemaKeys.none { key ->
            val normalized = key.lowercase().filter { it.isLetterOrDigit() }
            forbiddenMarkers.any { marker -> normalized.contains(marker) }
        })
        assertFalse(combined.contains("mutationToken"))
        assertFalse(combined.contains("frozen-token"))
        listOf(
            "cloud_identity_binding",
            "parcel_source_connections",
            "widget_instance_configs",
            "reminder_tasks",
            "installed_extension_plugins",
            "domain_outbox",
            "search_docs",
            "search_fts"
        ).forEach { excluded -> assertFalse(combined.contains(excluded)) }
        assertTrue(combined.contains("personal_events"))
        assertTrue(combined.contains("blob_store"))
    }

    private fun newService(
        scopeOverride: SchoolAccountScope = scope,
        mutationTokenGenerator: () -> String = { UUID.randomUUID().toString() }
    ): VaultBackupService = VaultBackupService(
        database = database,
        scope = scopeOverride,
        journal = journal,
        appVersion = "backup-test",
        clock = { fixedInstant() },
        pbkdf2Iterations = 1_000,
        mutationTokenGenerator = mutationTokenGenerator
    )

    private suspend fun seedPortableRows() {
        database.campusDao().upsertCourse(courseRow())
        database.campusDao().upsertRule(ruleRow())
        database.campusDao().upsertOccurrenceOverrides(listOf(overrideRow()))
        database.campusDao().upsertGradeAttempts(listOf(gradeRow()))
        database.campusDao().upsertExamSchedules(listOf(examRow()))
        database.campusDao().upsertUnscheduledCourses(listOf(unscheduledRow()))
        database.utilityAndFocusDao().upsertMeter(utilityMeterRow())
        database.vaultDao().upsertPersonalEvent(personalEventRow())
        VaultBlobStore(database, clock = { fixedInstant() }).put(
            content = "通知原文".toByteArray(Charsets.UTF_8),
            mimeType = "text/plain",
            scope = scope
        )
    }

    private suspend fun exportBackup(): Pair<ByteArray, BackupManifest> {
        val out = ByteArrayOutputStream()
        val manifest = service.export(scope, BackupSelection.ALL, passphrase, out)
        return out.toByteArray() to manifest
    }

    private fun reencryptWithManifestVersion(backup: ByteArray, version: Int, fieldName: String = "formatVersion"): ByteArray {
        val plaintext = BackupEnvelopeCrypto.decrypt(backup, passphrase)
        val entries = BackupZip.read(plaintext)
        val manifest = String(entries.getValue(BackupFormatConstants.MANIFEST_ENTRY), Charsets.UTF_8)
        val modifiedManifest = if (fieldName == "formatVersion") {
            manifest.replace("\"formatVersion\":1", "\"formatVersion\":" + version)
        } else {
            val schemaVersion = database.openHelper.writableDatabase.version
            manifest.replace("\"schemaVersion\":" + schemaVersion, "\"schemaVersion\":" + version)
        }
        require(modifiedManifest != manifest) { "测试 manifest 未找到 " + fieldName }
        val blobs = entries.filterKeys { it.startsWith(BackupFormatConstants.BLOB_ENTRY_PREFIX) }
            .mapKeys { (path, _) -> path.removePrefix(BackupFormatConstants.BLOB_ENTRY_PREFIX) }
        val modifiedZip = BackupZip.create(
            modifiedManifest,
            String(entries.getValue(BackupFormatConstants.DATA_ENTRY), Charsets.UTF_8),
            blobs
        )
        return BackupEnvelopeCrypto.encrypt(modifiedZip, passphrase, 1_000)
    }

    private fun wipePortableTables() {
        BackupTableCatalog.policies.asReversed().forEach { policy ->
            database.openHelper.writableDatabase.execSQL("DELETE FROM \"" + policy.table + "\"")
        }
    }

    private fun countRows(table: String): Long =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM \"" + table + "\"").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    private fun jsonKeys(value: JsonValue): List<String> = when (value) {
        is JsonValue.Obj -> value.fields.flatMap { (key, child) -> listOf(key) + jsonKeys(child) }
        is JsonValue.Arr -> value.items.flatMap(::jsonKeys)
        else -> emptyList()
    }

    private fun fixedInstant(): Instant = Instant.parse("2026-01-02T03:04:05Z")

    private fun courseRow() = CourseEntity(
        id = "course-1",
        externalId = "ext-course-1",
        name = "高等数学A（一）",
        teacher = "张老师",
        colorArgb = 0xFFAA00L,
        creditPlain = "5.0",
        source = "OFFICIAL_JW",
        userModified = false,
        lastSyncEpochMilli = 100L
    )

    private fun ruleRow() = CourseScheduleRuleEntity(
        id = "rule-1",
        courseId = "course-1",
        termId = "2025-2026-1",
        weekdayIso = 1,
        startPeriod = 1,
        endPeriod = 2,
        weeksCsv = "1,2,3",
        rawRoom = "W111",
        buildingId = null,
        customStartTimeIso = null,
        customEndTimeIso = null,
        userModified = false
    )

    private fun overrideRow() = OccurrenceOverrideEntity(
        id = "ov-1",
        termId = "2025-2026-1",
        courseRuleId = "rule-1",
        courseId = "course-1",
        dateIso = "2026-01-05",
        startPeriod = 3,
        endPeriod = 4,
        startAtIso = null,
        endAtIso = null,
        room = "W112",
        buildingId = null,
        canceled = false,
        reason = "调课",
        moveId = null,
        moveRole = null,
        mutationToken = "frozen-token"
    )

    private fun gradeRow() = GradeAttemptEntity(
        id = "grade-1",
        termId = "2025-2026-1",
        academicYear = "2025-2026",
        termNumber = 1,
        courseKey = "MATH101",
        name = "离散数学",
        category = "REQUIRED",
        creditsPlain = "4.0",
        rawGrade = "92",
        numericScorePlain = "92",
        gradePointPlain = "4.2",
        attemptType = "FIRST",
        passed = true,
        isCourseReplacement = false,
        source = "OFFICIAL_JW",
        syncedAtEpochMilli = 100L
    )

    private fun examRow() = ExamScheduleEntity(
        id = "exam-1",
        termId = "2025-2026-1",
        courseCode = "MATH101",
        courseName = "离散数学",
        examBatchName = "期末考试",
        examStageLabel = "期末",
        examFormat = "闭卷",
        rawTimeText = "2026-01-10 09:00-11:00",
        examDateIso = "2026-01-10",
        startTimeIso = "09:00",
        endTimeIso = "11:00",
        rawRoom = "W111",
        seatNumber = "01",
        campusName = "莲花街校区",
        isRetake = false,
        syncedAtEpochMilli = 100L,
        originTermId = "2025-2026-1"
    )

    private fun unscheduledRow() = UnscheduledAcademicCourseEntity(
        termId = "2025-2026-1",
        sourceKey = "unscheduled-1",
        sourceId = "PW101",
        name = "实践周",
        teacher = "李老师",
        creditPlain = "2.0",
        rawWeeks = "16-17",
        category = "实践",
        syncedAtEpochMilli = 100L
    )

    private fun utilityMeterRow() = UtilityMeterEntity(
        id = "meter-1",
        name = "宿舍电表",
        campusName = "莲花街校区",
        roomName = "1号楼101",
        unit = "kWh",
        lowThreshold = 10.0,
        createdAtEpochMs = 100L
    )

    private fun personalEventRow() = PersonalEventEntity(
        id = "pe-1",
        scopeSchoolId = scope.schoolId.value,
        scopeAccountId = scope.accountId.value,
        scopeProfileId = scope.profileId.value,
        scopeCampusId = scope.campusId?.value,
        kind = "REVIEW_BLOCK",
        title = "复习离散数学",
        note = "第 3 章",
        startAtEpochMs = 1768000000000L,
        endAtEpochMs = 1768003600000L,
        timeSemantics = "TIMED",
        zoneId = "Asia/Shanghai",
        placeText = "图书馆",
        placeRefId = null,
        status = "CONFIRMED",
        busy = "BUSY",
        termId = "2025-2026-1",
        revision = 1L,
        provenanceJson = "{}",
        sourceEntityType = null,
        sourceEntityId = null,
        linksJson = "[]",
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L
    )

    private fun ByteArray.startsWithZipMagic(): Boolean =
        size >= 2 && this[0] == 'P'.code.toByte() && this[1] == 'K'.code.toByte()
}
