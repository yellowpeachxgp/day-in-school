package cn.edu.haut.campus.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import cn.edu.haut.campus.core.academic.HautLiveSyncResult
import cn.edu.haut.campus.core.model.AcademicTermResolver
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.ImportCourseCandidate
import cn.edu.haut.campus.core.model.Term
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * W0-05-c7 教务同步编排搬迁特征冻结。
 * 只使用内存 Room/DataStore 与内存 AES-GCM 凭据仓，零真实学校账号、零真实教务网络；
 * 冻结凭据轮换隔离、legacy 明文迁移、live sync 目标学期回退激活与自动同步门禁。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CampusRepositoryW005c7AcademicSyncCharacterizationTest {
    private lateinit var database: HautDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            HautDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private data class RepoFixture(
        val repository: CampusRepository,
        val dataStore: DataStore<Preferences>,
        val vault: AcademicCredentialVault,
        val storeFile: java.io.File
    ) {
        fun close() {
            runCatching { storeFile.delete() }
        }
    }

    private fun buildInMemoryVault(): AcademicCredentialVault {
        val keysMap = ConcurrentHashMap<String, SecretKey>()
        val storageMap = ConcurrentHashMap<String, ByteArray>()
        return AcademicCredentialVault(
            keys = object : AcademicCredentialKeyProvider {
                override fun existing(scope: AcademicCredentialScope) = keysMap[scope.storageKey]
                override fun getOrCreate(scope: AcademicCredentialScope) = keysMap.getOrPut(scope.storageKey) {
                    KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                }
                override fun delete(scope: AcademicCredentialScope) { keysMap.remove(scope.storageKey) }
            },
            storage = object : AcademicCredentialStorage {
                override fun read(scope: AcademicCredentialScope) = storageMap[scope.storageKey]
                override fun write(scope: AcademicCredentialScope, envelope: ByteArray) { storageMap[scope.storageKey] = envelope }
                override fun delete(scope: AcademicCredentialScope) { storageMap.remove(scope.storageKey) }
            }
        )
    }

    private fun buildRepo(profileSeed: String): RepoFixture {
        val context = RuntimeEnvironment.getApplication()
        val storeFile = java.io.File.createTempFile("w005c7_test_" + profileSeed + "_", ".preferences_pb")
        storeFile.deleteOnExit()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { storeFile }
        )
        val storage = isolatedAccountStoragePartition("prof_" + profileSeed)
        val vault = buildInMemoryVault()
        val repo = CampusRepository(
            appContext = context,
            database = database,
            preferences = dataStore,
            accountProfile = AccountProfileEntity(
                id = "prof_" + profileSeed,
                accountId = "acct_" + profileSeed,
                schoolId = "haut",
                schoolName = "河南工业大学",
                province = "河南省",
                city = "郑州市",
                campusId = "lianhuajie",
                campusName = "莲花街校区",
                timeZoneId = "Asia/Shanghai",
                label = "c7教务同步特征",
                isLegacy = false,
                databaseName = storage.databaseName,
                preferencesName = storage.preferencesName,
                createdAtEpochMs = 1_000L
            ),
            academicCredentialVaultOverride = vault
        ).also { it.activateSession(1L) }
        return RepoFixture(repo, dataStore, vault, storeFile)
    }

    private fun credentialScope(accountId: String) = AcademicCredentialScope(
        schoolId = "haut",
        accountId = accountId,
        providerId = "haut-zf-modern"
    )

    private fun bookingPlan(ownerStudentId: String) = LibraryBookingPlanEntity(
        id = "plan_w005c7_student_switch",
        schoolId = "haut",
        executionMode = "LOCAL_DEVICE",
        targetLocalDate = "2099-01-04",
        targetLocalTime = "08:30:00",
        timezoneId = "Asia/Shanghai",
        precheckAtEpochMillis = 1_000L,
        finalCheckAtEpochMillis = 2_000L,
        fireAtEpochMillis = 3_000L,
        precheckStatus = "ARMED",
        finalCheckStatus = "ARMED",
        fireStatus = "ARMED",
        preferredAreaName = "五层中区",
        preferredSeatLabel = "05桌 A座",
        allowAlternativeSeat = true,
        cloudJobId = null,
        externalBookingId = null,
        evidenceSha256 = null,
        priceMicropeach = 0L,
        paymentState = "NOT_REQUIRED",
        status = "ARMED",
        stateVersion = 7,
        generation = 1,
        lastDiagnosticMessage = null,
        createdAtEpochMillis = 500L,
        updatedAtEpochMillis = 2_000L,
        repeatDays = 1,
        remainingDays = 1,
        ownerStudentId = ownerStudentId
    )

    @Test
    fun w005c7_academic_sync_facade_api_shape_snapshot() {
        val declaredNames = CampusRepository::class.java.declaredMethods.map { it.name }.toSet()
        fun hasSourceApiName(sourceName: String): Boolean =
            declaredNames.any { it == sourceName || it.startsWith(sourceName + "-") }

        val missing = listOf(
            "setAutoSyncEnabled",
            "saveAuthorizedSyncCredentials",
            "applyLiveSyncData",
            "performDynamicAcademicSync"
        ).filterNot(::hasSourceApiName)
        assertEquals("教务同步公开实例方法不得减少", emptyList<String>(), missing)
    }

    @Test
    fun w005c7_credential_rotation_isolates_previous_student_local_plan() = runBlocking {
        val fixture = buildRepo("credential_rotation")
        try {
            val repo = fixture.repository
            val dao = database.cloudAndParcelDao()
            repo.saveAuthorizedSyncCredentials("20240001", "pw-a", "2026", "3")
            val firstAccountId = fixture.dataStore.data.first()[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID]
            assertNotNull(firstAccountId)
            dao.upsertLibraryBookingPlan(bookingPlan("20240001"))

            repo.saveAuthorizedSyncCredentials("20240002", "pw-b", "2026", "3")
            val prefs = fixture.dataStore.data.first()
            val secondAccountId = prefs[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID]
            assertNotNull(secondAccountId)
            assertTrue(firstAccountId != secondAccountId)
            assertEquals("2026", prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM])
            assertEquals("3", prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM])
            assertEquals(false, prefs[CampusRepositoryPrefKeys.ACADEMIC_REAUTH_REQUIRED] ?: false)
            assertNull(prefs[CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID])
            assertNull(prefs[CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64])

            val firstRead = fixture.vault.read(credentialScope(firstAccountId!!))
            assertTrue("旧账号凭据必须在轮换后删除", firstRead is AcademicCredentialReadResult.Empty)
            val secondRead = fixture.vault.read(credentialScope(secondAccountId!!))
            assertTrue(secondRead is AcademicCredentialReadResult.Available)
            (secondRead as AcademicCredentialReadResult.Available).credentials.use {
                assertEquals("20240002", it.studentId)
                assertEquals("pw-b", it.passwordPlain)
            }

            val isolated = dao.getLibraryBookingPlanById("plan_w005c7_student_switch")!!
            assertEquals("CANCELLED", isolated.status)
            assertEquals("CANCELLED", isolated.fireStatus)
            assertEquals(0, isolated.remainingDays)
            assertEquals(8, isolated.stateVersion)
            assertEquals(2, isolated.generation)
            assertEquals("20240001", isolated.ownerStudentId)
            assertEquals(
                "已切换学校学号，原学号（20240001）未派发的本机排队计划已安全停止",
                isolated.lastDiagnosticMessage
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun w005c7_legacy_credentials_migrate_before_dynamic_sync_skip_gate() = runBlocking {
        val fixture = buildRepo("legacy_migration")
        try {
            val repo = fixture.repository
            val today = LocalDate.now(repo.timeZone)
            val seasonalTermId = AcademicTermResolver.defaultTermId(today)
            val seasonalXnm = AcademicTermResolver.defaultAcademicYearCode(today)
            val seasonalXqm = AcademicTermResolver.defaultSemesterCode(today)
            val parts = seasonalTermId.split("-")
            database.campusDao().upsertTerms(listOf(Term(
                id = seasonalTermId,
                academicYear = parts[0] + "-" + parts[1],
                termNumber = parts[2].toInt(),
                startDate = today.minusWeeks(1),
                weekCount = 20,
                current = true
            ).toEntity()))
            val legacyPassword = Base64.getEncoder().encodeToString("legacy-pw".toByteArray(Charsets.UTF_8))
            fixture.dataStore.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID] = "20239999"
                prefs[CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64] = legacyPassword
                prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM] = seasonalXnm
                prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM] = seasonalXqm
                prefs[CampusRepositoryPrefKeys.LAST_SEASONAL_ROLLOVER_TERM_ID] = seasonalTermId
                prefs[CampusRepositoryPrefKeys.LAST_AUTO_SYNC_MS] = System.currentTimeMillis()
            }

            val skipped = repo.performDynamicAcademicSync(force = false)
            assertNull("近窗口内 force=false 必须在迁移后按原门禁跳过", skipped)

            val prefs = fixture.dataStore.data.first()
            assertNull(prefs[CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID])
            assertNull(prefs[CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64])
            assertEquals(false, prefs[CampusRepositoryPrefKeys.ACADEMIC_REAUTH_REQUIRED] ?: false)
            val accountId = prefs[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID]
            assertNotNull(accountId)
            val migrated = fixture.vault.read(credentialScope(accountId!!))
            assertTrue(migrated is AcademicCredentialReadResult.Available)
            (migrated as AcademicCredentialReadResult.Available).credentials.use {
                assertEquals("20239999", it.studentId)
                assertEquals("legacy-pw", it.passwordPlain)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun w005c7_apply_live_sync_falls_back_to_newer_populated_sibling_term() = runBlocking {
        val fixture = buildRepo("live_sync_fallback")
        try {
            val repo = fixture.repository
            val targetTermId = "2025-2026-2"
            val siblingTermId = "2026-2027-1"
            val candidate = ImportCourseCandidate(
                externalId = "ext-w005c7-sibling",
                courseName = "教务同步特征课",
                teacher = "合成教师",
                credit = BigDecimal.ONE,
                weekday = DayOfWeek.MONDAY,
                startPeriod = 1,
                endPeriod = 2,
                weeks = setOf(1, 2),
                rawRoom = "W111"
            )

            repo.applyLiveSyncData(
                HautLiveSyncResult(
                    termId = targetTermId,
                    studentProfile = null,
                    timetableCandidates = emptyList(),
                    gradeAttempts = emptyList(),
                    semesterStart = LocalDate.of(2026, 3, 2),
                    totalWeeks = 20,
                    additionalTermTimetables = mapOf(siblingTermId to listOf(candidate)),
                    additionalTermCalendars = mapOf(siblingTermId to (LocalDate.of(2026, 8, 31) to 20))
                ),
                overwriteUserConflicts = false
            )

            val current = database.campusDao().getCurrentTerm()!!
            assertEquals(siblingTermId, current.id)
            assertTrue(database.campusDao().observeTerms().first().any { it.id == targetTermId })
            val siblingCourses = database.campusDao().getCoursesSnapshot()
                .filter { it.externalId == "ext-w005c7-sibling" }
            assertEquals(1, siblingCourses.size)
            assertEquals(CourseSource.OFFICIAL_JW.name, siblingCourses.single().source)
            val prefs = fixture.dataStore.data.first()
            assertEquals("2025", prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM])
            assertEquals("12", prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM])
            assertNotNull(prefs[CampusRepositoryPrefKeys.LAST_AUTO_SYNC_MS])
        } finally {
            fixture.close()
        }
    }

    @Test
    fun w005c7_dynamic_sync_respects_auto_sync_disabled_without_network_or_reauth() = runBlocking {
        val fixture = buildRepo("auto_sync_disabled")
        try {
            val repo = fixture.repository
            repo.saveAuthorizedSyncCredentials("20240001", "pw-a", "2026", "3")
            repo.setAutoSyncEnabled(false)

            val result = repo.performDynamicAcademicSync(force = false)
            assertNull(result)
            val prefs = fixture.dataStore.data.first()
            assertEquals(false, prefs[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED])
            assertFalse(prefs[CampusRepositoryPrefKeys.ACADEMIC_REAUTH_REQUIRED] ?: false)
        } finally {
            fixture.close()
        }
    }
}
