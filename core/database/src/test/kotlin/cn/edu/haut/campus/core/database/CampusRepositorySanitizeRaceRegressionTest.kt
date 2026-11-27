package cn.edu.haut.campus.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * AUTO-033：启动 null-literal 清洗与学号切换隔离写入的确定性竞态回归。
 * 全部使用内存 Room/DataStore/凭据仓；清洗写前 hook 精确注入隔离写入，零真实学校账号与网络。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CampusRepositorySanitizeRaceRegressionTest {
    private lateinit var database: HautDatabase
    private lateinit var dao: CloudAndParcelDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            HautDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.cloudAndParcelDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private data class RepoFixture(
        val repository: CampusRepository,
        private val storeFile: File
    ) {
        fun close() {
            runCatching { storeFile.delete() }
        }
    }

    private fun inMemoryVault(): AcademicCredentialVault {
        val keysMap = ConcurrentHashMap<String, SecretKey>()
        val storageMap = ConcurrentHashMap<String, ByteArray>()
        return AcademicCredentialVault(
            keys = object : AcademicCredentialKeyProvider {
                override fun existing(scope: AcademicCredentialScope) = keysMap[scope.storageKey]
                override fun getOrCreate(scope: AcademicCredentialScope) = keysMap.getOrPut(scope.storageKey) {
                    KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                }
                override fun delete(scope: AcademicCredentialScope) {
                    keysMap.remove(scope.storageKey)
                }
            },
            storage = object : AcademicCredentialStorage {
                override fun read(scope: AcademicCredentialScope) = storageMap[scope.storageKey]
                override fun write(scope: AcademicCredentialScope, envelope: ByteArray) {
                    storageMap[scope.storageKey] = envelope
                }
                override fun delete(scope: AcademicCredentialScope) {
                    storageMap.remove(scope.storageKey)
                }
            }
        )
    }

    private fun buildRepo(
        profileSeed: String,
        sanitizeHook: (suspend (String) -> Unit)? = null
    ): RepoFixture {
        val context = RuntimeEnvironment.getApplication()
        val storeFile = File.createTempFile("sanitize_race_${profileSeed}_", ".preferences_pb")
        storeFile.deleteOnExit()
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { storeFile }
        )
        val storage = isolatedAccountStoragePartition("prof_$profileSeed")
        val repo = CampusRepository(
            appContext = context,
            database = database,
            preferences = dataStore,
            accountProfile = AccountProfileEntity(
                id = "prof_$profileSeed",
                accountId = "acct_$profileSeed",
                schoolId = "haut",
                schoolName = "河南工业大学",
                province = "河南省",
                city = "郑州市",
                campusId = "lianhuajie",
                campusName = "莲花街校区",
                timeZoneId = "Asia/Shanghai",
                label = "清洗竞态合成账号",
                isLegacy = false,
                databaseName = storage.databaseName,
                preferencesName = storage.preferencesName,
                createdAtEpochMs = 1_000L
            ),
            academicCredentialVaultOverride = inMemoryVault(),
            sanitizeBookingPlanWriteHookForTest = sanitizeHook
        ).also { it.activateSession(1L) }
        return RepoFixture(repo, storeFile)
    }

    private fun bookingPlan(
        id: String,
        preferredAreaName: String,
        preferredSeatLabel: String = "05桌 A座",
        externalBookingId: String? = null,
        evidenceSha256: String? = null,
        lastDiagnosticMessage: String? = null,
        ownerStudentId: String = "20240001"
    ) = LibraryBookingPlanEntity(
        id = id,
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
        preferredAreaName = preferredAreaName,
        preferredSeatLabel = preferredSeatLabel,
        allowAlternativeSeat = true,
        cloudJobId = null,
        externalBookingId = externalBookingId,
        evidenceSha256 = evidenceSha256,
        priceMicropeach = 0L,
        paymentState = "NOT_REQUIRED",
        status = "ARMED",
        stateVersion = 7,
        generation = 1,
        lastDiagnosticMessage = lastDiagnosticMessage,
        createdAtEpochMillis = 500L,
        updatedAtEpochMillis = 2_000L,
        repeatDays = 1,
        remainingDays = 1,
        ownerStudentId = ownerStudentId
    )

    @Test
    fun auto033_startup_sanitizer_still_cleans_null_literals_when_row_is_unchanged() = runBlocking {
        dao.upsertLibraryBookingPlan(
            bookingPlan(
                id = "plan_sanitize_normal",
                preferredAreaName = "null",
                preferredSeatLabel = "undefined",
                externalBookingId = "none",
                evidenceSha256 = "nil",
                lastDiagnosticMessage = "null"
            )
        )
        val afterWrite = CompletableDeferred<Unit>()
        val fixture = buildRepo("normal_clean") { phase ->
            if (phase == "after_write") afterWrite.complete(Unit)
        }
        try {
            withTimeout(10_000) { afterWrite.await() }
            val row = dao.getLibraryBookingPlanById("plan_sanitize_normal")!!
            assertEquals("五层中区", row.preferredAreaName)
            assertEquals("001", row.preferredSeatLabel)
            assertNull(row.externalBookingId)
            assertNull(row.evidenceSha256)
            assertEquals("", row.lastDiagnosticMessage)
            assertEquals("ARMED", row.status)
            assertEquals(7, row.stateVersion)
            assertEquals(1, row.generation)
            assertEquals(2_000L, row.updatedAtEpochMillis)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun auto033_startup_sanitizer_cannot_overwrite_student_switch_cancellation() = runBlocking {
        dao.upsertLibraryBookingPlan(
            bookingPlan(
                id = "plan_sanitize_race",
                preferredAreaName = "null",
                ownerStudentId = "20240001"
            )
        )
        val repoReady = CompletableDeferred<CampusRepository>()
        val beforeWrite = CompletableDeferred<Unit>()
        val afterWrite = CompletableDeferred<Unit>()
        val hook: suspend (String) -> Unit = { phase ->
            when (phase) {
                "before_write" -> {
                    beforeWrite.complete(Unit)
                    val repo = repoReady.await()
                    repo.saveAuthorizedSyncCredentials("20240001", "pw-a", "2026", "3")
                    repo.saveAuthorizedSyncCredentials("20240002", "pw-b", "2026", "3")
                }
                "after_write" -> afterWrite.complete(Unit)
            }
        }
        val fixture = buildRepo("switch_race", hook)
        try {
            fixture.repository.let { repoReady.complete(it) }
            withTimeout(10_000) { beforeWrite.await() }
            withTimeout(10_000) { afterWrite.await() }

            val row = dao.getLibraryBookingPlanById("plan_sanitize_race")!!
            assertEquals("CANCELLED", row.status)
            assertEquals("CANCELLED", row.fireStatus)
            assertEquals(0, row.remainingDays)
            assertEquals(8, row.stateVersion)
            assertEquals(2, row.generation)
            assertEquals("20240001", row.ownerStudentId)
            assertEquals(
                "已切换学校学号，原学号（20240001）未派发的本机排队计划已安全停止",
                row.lastDiagnosticMessage
            )
            assertEquals("null", row.preferredAreaName)
        } finally {
            fixture.close()
        }
    }
}
