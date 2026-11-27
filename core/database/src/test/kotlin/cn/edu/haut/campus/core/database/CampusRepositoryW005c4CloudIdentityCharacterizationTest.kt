package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * W0-05-c4 云身份/学籍锁定/绑定簇搬迁特征冻结。
 * 只使用可控假传输与内存凭据仓，零真实网络、零真实学校账号；
 * 冻结解绑/换号/换学号隔离的精确状态码与诊断文案、世代单调性、凭据清除与可见性规则。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CampusRepositoryW005c4CloudIdentityCharacterizationTest {
    private lateinit var database: HautDatabase
    private lateinit var dao: CloudAndParcelDao
    private lateinit var dataStore: DataStore<Preferences>

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

    private fun buildInMemoryTestRepo(profileSeed: String): CampusRepository {
        val context = RuntimeEnvironment.getApplication()
        val dataStoreFile = java.io.File.createTempFile("w005c4_test_${profileSeed}_", ".preferences_pb")
        dataStoreFile.deleteOnExit()
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { dataStoreFile }
        )
        val keysMap = java.util.concurrent.ConcurrentHashMap<String, javax.crypto.SecretKey>()
        val storageMap = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val vault = AcademicCredentialVault(
            keys = object : AcademicCredentialKeyProvider {
                override fun existing(scope: AcademicCredentialScope) = keysMap[scope.storageKey]
                override fun getOrCreate(scope: AcademicCredentialScope) = keysMap.getOrPut(scope.storageKey) {
                    val kg = javax.crypto.KeyGenerator.getInstance("AES")
                    kg.init(256)
                    kg.generateKey()
                }
                override fun delete(scope: AcademicCredentialScope) { keysMap.remove(scope.storageKey) }
            },
            storage = object : AcademicCredentialStorage {
                override fun read(scope: AcademicCredentialScope) = storageMap[scope.storageKey]
                override fun write(scope: AcademicCredentialScope, envelope: ByteArray) { storageMap[scope.storageKey] = envelope }
                override fun delete(scope: AcademicCredentialScope) { storageMap.remove(scope.storageKey) }
            }
        )
        val storage = isolatedAccountStoragePartition("prof_$profileSeed")
        return CampusRepository(
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
                label = "c4特征_$profileSeed",
                isLegacy = false,
                databaseName = storage.databaseName,
                preferencesName = storage.preferencesName,
                createdAtEpochMs = 1_000L
            ),
            academicCredentialVaultOverride = vault
        ).also { it.activateSession(1L) }
    }

    private fun testCloudJwt(sub: String): String {
        val header = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"$sub","exp":4102444800}""".toByteArray())
        return "ctseal1:$header.$payload.sig"
    }

    private fun loginProfile(
        cloudUserId: String,
        jwt: String,
        peach: Long = 50_000L,
        displayName: String = "用户"
    ) = cn.edu.haut.campus.core.campusservices.XpeachCloudAccountProfile(
        cloudUserId = cloudUserId,
        ssoIssuer = "https://auth.example.invalid",
        ssoSubject = "sub_$cloudUserId",
        displayName = displayName,
        peachBalanceMicropeach = peach,
        holdSupported = false,
        accessToken = jwt,
        refreshToken = "ctseal1:ref_$cloudUserId",
        linkedAtEpochMillis = 3_000L
    )

    private fun inertTransport(repo: CampusRepository) {
        repo.cloudHttpTransportForTest = { _, _, _, _, _ -> """{"ok":true,"data":{}}""" }
    }

    private fun cloudPlan(
        id: String,
        ownerCloudUserId: String?,
        status: String,
        fireStatus: String = status,
        cloudJobId: String? = "job_srv_1",
        ownerStudentId: String? = null
    ) = LibraryBookingPlanEntity(
        id = id,
        schoolId = "haut",
        executionMode = "CLOUD_HOSTED",
        targetLocalDate = "2026-11-26",
        targetLocalTime = "07:00:00",
        timezoneId = "Asia/Shanghai",
        precheckAtEpochMillis = 1_000L,
        finalCheckAtEpochMillis = 2_000L,
        fireAtEpochMillis = 3_000L,
        precheckStatus = "PRECHECK_OK",
        finalCheckStatus = "FINAL_CHECK_OK",
        fireStatus = fireStatus,
        preferredAreaName = "五层中区",
        preferredSeatLabel = "05桌 A座",
        allowAlternativeSeat = true,
        cloudJobId = cloudJobId,
        externalBookingId = null,
        evidenceSha256 = null,
        priceMicropeach = 10_000L,
        paymentState = "DEBIT_PENDING",
        status = status,
        stateVersion = 1,
        generation = 1,
        lastDiagnosticMessage = "云端托管中",
        createdAtEpochMillis = 500L,
        updatedAtEpochMillis = 2_000L,
        ownerCloudUserId = ownerCloudUserId,
        ownerStudentId = ownerStudentId
    )

    private fun localPlan(
        id: String,
        ownerStudentId: String?,
        status: String,
        fireStatus: String = status
    ) = cloudPlan(id, null, status, fireStatus, cloudJobId = null, ownerStudentId = ownerStudentId)
        .copy(executionMode = "LOCAL_DEVICE")

    @Test
    fun `unbound identity snapshot is null and wallet refresh is null`() = runBlocking {
        val repo = buildInMemoryTestRepo("unbound")
        inertTransport(repo)
        assertNull(repo.snapshotCloudIdentitySession())
        assertNull(repo.refreshXpeachWalletBalance())
        assertNull(dao.getCloudIdentityBindingSnapshot())
        repo.cloudHttpTransportForTest = null
    }

    @Test
    fun `save verified cloud account writes binding with display fallback and sealed tokens`() = runBlocking {
        val repo = buildInMemoryTestRepo("save_binding")
        inertTransport(repo)
        val jwt = testCloudJwt("cloud_user_A")
        val entity = repo.saveVerifiedXpeachCloudAccount(
            loginProfile("cloud_user_A", jwt, displayName = "   ")
        )
        assertEquals("空白 displayName 必须回退 ssoSubject", "sub_cloud_user_A", entity.displayName)
        assertEquals("cloud_user_A", entity.cloudUserId)
        assertEquals(50_000L, entity.peachBalanceMicropeach)
        val persisted = dao.getCloudIdentityBindingSnapshot()!!
        assertEquals("cloud_user_A", persisted.cloudUserId)
        assertEquals("sub_cloud_user_A", persisted.ssoSubject)
        val prefs = dataStore.data.first()
        val sealedAt = prefs[CampusRepositoryPrefKeys.CLOUD_ACCESS_TOKEN].orEmpty()
        val sealedRt = prefs[CampusRepositoryPrefKeys.CLOUD_REFRESH_TOKEN].orEmpty()
        assertTrue("访问令牌必须密封落盘", sealedAt.startsWith("ctseal1:"))
        assertTrue("刷新令牌必须密封落盘", sealedRt.startsWith("ctseal1:"))
        val snapshot = repo.snapshotCloudIdentitySession()!!
        assertEquals("cloud_user_A", snapshot.cloudUserId)
        assertEquals("sub_cloud_user_A", snapshot.ssoSubject)
        assertTrue(snapshot.generation > 0L)
        assertTrue(repo.isCloudIdentitySnapshotCurrent(snapshot))
        repo.cloudHttpTransportForTest = null
    }

    @Test
    fun `relogin same account bumps generation strictly`() = runBlocking {
        val repo = buildInMemoryTestRepo("relogin_bump")
        inertTransport(repo)
        val jwt = testCloudJwt("cloud_user_A")
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwt))
        val g1 = repo.snapshotCloudIdentitySession()!!.generation
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwt, peach = 70_000L))
        val g2 = repo.snapshotCloudIdentitySession()!!.generation
        assertTrue("同号重登必须推进世代: g1=$g1 g2=$g2", g2 > g1)
        repo.cloudHttpTransportForTest = null
    }

    @Test
    fun `unlink clears binding tokens and scrubs moments ownership keys`() = runBlocking {
        val repo = buildInMemoryTestRepo("unlink_scrub")
        inertTransport(repo)
        val jwt = testCloudJwt("cloud_user_A")
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwt))
        dataStore.edit {
            it[CampusRepositoryPrefKeys.MOMENTS_MY_POST_IDS] = "p1,p2"
            it[CampusRepositoryPrefKeys.MOMENTS_MY_BOOK_IDS] = "b1"
            it[CampusRepositoryPrefKeys.MOMENTS_MY_TAG_CODES] = "t1"
            it[CampusRepositoryPrefKeys.MOMENTS_FAVORITE_POST_IDS] = "f1"
            it[CampusRepositoryPrefKeys.MOMENTS_FAVORITE_BOOK_IDS] = "fb1"
        }
        repo.unlinkXpeachCloudAccountAndRevokeCloudPlans()
        assertNull(dao.getCloudIdentityBindingSnapshot())
        assertNull(repo.snapshotCloudIdentitySession())
        val after = dataStore.data.first()
        assertNull(after[CampusRepositoryPrefKeys.CLOUD_ACCESS_TOKEN])
        assertNull(after[CampusRepositoryPrefKeys.CLOUD_REFRESH_TOKEN])
        assertNull(after[CampusRepositoryPrefKeys.MOMENTS_MY_POST_IDS])
        assertNull(after[CampusRepositoryPrefKeys.MOMENTS_MY_BOOK_IDS])
        assertNull(after[CampusRepositoryPrefKeys.MOMENTS_MY_TAG_CODES])
        assertNull(after[CampusRepositoryPrefKeys.MOMENTS_FAVORITE_POST_IDS])
        assertNull(after[CampusRepositoryPrefKeys.MOMENTS_FAVORITE_BOOK_IDS])
        repo.cloudHttpTransportForTest = null
    }

    @Test
    fun `unlink isolates cloud plans with exact statuses and diagnostic messages`() = runBlocking {
        val repo = buildInMemoryTestRepo("unlink_plans")
        inertTransport(repo)
        val jwt = testCloudJwt("cloud_user_A")
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwt))
        dao.upsertLibraryBookingPlan(cloudPlan("plan_armed", "cloud_user_A", "ARMED"))
        dao.upsertLibraryBookingPlan(cloudPlan("plan_executing", "cloud_user_A", "EXECUTING"))
        dao.upsertLibraryBookingPlan(cloudPlan("plan_confirmed", "cloud_user_A", "CONFIRMED"))
        dao.upsertLibraryBookingPlan(cloudPlan("plan_foreign", "cloud_user_B", "ARMED"))

        repo.unlinkXpeachCloudAccountAndRevokeCloudPlans()

        val armed = dao.getLibraryBookingPlanById("plan_armed")!!
        assertEquals("CANCELLED", armed.status)
        assertEquals("CANCELLED", armed.fireStatus)
        assertEquals("已解绑 XPEACH 账号并撤回未执行的云端托管授权", armed.lastDiagnosticMessage)
        assertEquals("cloud_user_A", armed.ownerCloudUserId)
        assertEquals(2, armed.stateVersion)
        assertEquals(0, armed.remainingDays)

        val executing = dao.getLibraryBookingPlanById("plan_executing")!!
        assertEquals("DELEGATION_STOPPED_RESULT_UNKNOWN", executing.status)
        assertEquals("DELEGATION_STOPPED_RESULT_UNKNOWN", executing.fireStatus)
        assertEquals("已在任务派发后退出云端账号，结果待核对（核实前禁止同日重复提交）", executing.lastDiagnosticMessage)

        val confirmed = dao.getLibraryBookingPlanById("plan_confirmed")!!
        assertEquals("已确认状态严禁覆盖", "CONFIRMED", confirmed.status)

        val foreign = dao.getLibraryBookingPlanById("plan_foreign")!!
        assertEquals("他人云任务严禁触动", "ARMED", foreign.status)
        assertEquals("cloud_user_B", foreign.ownerCloudUserId)
        repo.cloudHttpTransportForTest = null
    }

    @Test
    fun `offline unlink marks real cloud jobs result unknown instead of cancelled`() = runBlocking {
        val repo = buildInMemoryTestRepo("unlink_offline")
        inertTransport(repo)
        val jwt = testCloudJwt("cloud_user_A")
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwt))
        repo.cloudHttpTransportForTest = { _, _, _, _, _ -> throw java.io.IOException("offline") }
        dao.upsertLibraryBookingPlan(cloudPlan("plan_real_job", "cloud_user_A", "ARMED", cloudJobId = "srv_job_9"))
        dao.upsertLibraryBookingPlan(cloudPlan("plan_cjob", "cloud_user_A", "ARMED", cloudJobId = "cjob_local"))

        repo.unlinkXpeachCloudAccountAndRevokeCloudPlans()

        val realJob = dao.getLibraryBookingPlanById("plan_real_job")!!
        assertEquals("RESULT_UNKNOWN", realJob.status)
        assertEquals("RESULT_UNKNOWN", realJob.fireStatus)
        assertEquals("离线退出云端账号：无法联网确认云端排队任务是否已停止，请核实前勿重复提交同日抢座", realJob.lastDiagnosticMessage)

        val cjob = dao.getLibraryBookingPlanById("plan_cjob")!!
        assertEquals("CANCELLED", cjob.status)
        assertEquals("已解绑 XPEACH 账号并撤回未执行的云端托管授权", cjob.lastDiagnosticMessage)
        repo.cloudHttpTransportForTest = null
    }

    @Test
    fun `cloud account switch isolates previous account plans with exact messages`() = runBlocking {
        val repo = buildInMemoryTestRepo("switch_cloud")
        inertTransport(repo)
        val jwtA = testCloudJwt("cloud_user_A")
        val jwtB = testCloudJwt("cloud_user_B")
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwtA))
        dao.upsertLibraryBookingPlan(cloudPlan("plan_a_armed", "cloud_user_A", "ARMED"))
        dao.upsertLibraryBookingPlan(cloudPlan("plan_a_cjob", "cloud_user_A", "ARMED", cloudJobId = "cjob_loc"))
        dao.upsertLibraryBookingPlan(cloudPlan("plan_a_executing", "cloud_user_A", "EXECUTING"))
        dao.upsertLibraryBookingPlan(cloudPlan("plan_a_confirmed", "cloud_user_A", "CONFIRMED"))

        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_B", jwtB))

        // 测试装配下假传输跳过联网登出确认：真实云端排队任务按"离线切换"分支隔离待核对
        val armed = dao.getLibraryBookingPlanById("plan_a_armed")!!
        assertEquals("DELEGATION_STOPPED_RESULT_UNKNOWN", armed.status)
        assertEquals("离线切换云端账号：未能联网确认原账号排队任务是否已停止，已隔离保留待核对记录", armed.lastDiagnosticMessage)
        assertEquals("cloud_user_A", armed.ownerCloudUserId)

        val cjob = dao.getLibraryBookingPlanById("plan_a_cjob")!!
        assertEquals("CANCELLED", cjob.status)
        assertEquals("已切换云端账号，原账号未派发的排队计划已停止", cjob.lastDiagnosticMessage)

        val executing = dao.getLibraryBookingPlanById("plan_a_executing")!!
        assertEquals("DELEGATION_STOPPED_RESULT_UNKNOWN", executing.status)
        assertEquals("切换云端账号时该任务正在派发或待核对，已保留原账号（cloud_user_A）证据供追溯", executing.lastDiagnosticMessage)

        val confirmed = dao.getLibraryBookingPlanById("plan_a_confirmed")!!
        assertEquals("CONFIRMED", confirmed.status)

        val binding = dao.getCloudIdentityBindingSnapshot()!!
        assertEquals("cloud_user_B", binding.cloudUserId)
        repo.cloudHttpTransportForTest = null
    }

    @Test
    fun `student switch isolates local plans with exact diagnostic messages`() = runBlocking {
        val repo = buildInMemoryTestRepo("switch_student")
        inertTransport(repo)
        repo.saveAuthorizedSyncCredentials("2021001", "pw-a", "2021", "1")
        dao.upsertLibraryBookingPlan(localPlan("local_armed", "2021001", "ARMED"))
        dao.upsertLibraryBookingPlan(localPlan("local_executing", "2021001", "EXECUTING"))
        dao.upsertLibraryBookingPlan(localPlan("local_confirmed", "2021001", "CONFIRMED"))
        dao.upsertLibraryBookingPlan(localPlan("local_other", "2021999", "ARMED"))

        repo.saveAuthorizedSyncCredentials("2021002", "pw-b", "2021", "1")

        val armed = dao.getLibraryBookingPlanById("local_armed")!!
        assertEquals("CANCELLED", armed.status)
        assertEquals("已切换学校学号，原学号（2021001）未派发的本机排队计划已安全停止", armed.lastDiagnosticMessage)
        assertEquals("2021001", armed.ownerStudentId)

        val executing = dao.getLibraryBookingPlanById("local_executing")!!
        assertEquals("DELEGATION_STOPPED_RESULT_UNKNOWN", executing.status)
        assertEquals("已在任务派发期间切换学号（2021001），原任务已停止并保留待核对证据", executing.lastDiagnosticMessage)

        val confirmed = dao.getLibraryBookingPlanById("local_confirmed")!!
        assertEquals("CONFIRMED", confirmed.status)

        val other = dao.getLibraryBookingPlanById("local_other")!!
        assertEquals("他人学号任务严禁触动", "ARMED", other.status)
        repo.cloudHttpTransportForTest = null
    }

    @Test
    fun `booking plan visibility rules freeze for cloud and local identities`() = runBlocking {
        val repo = buildInMemoryTestRepo("visibility")
        inertTransport(repo)
        val jwtA = testCloudJwt("cloud_user_A")
        repo.saveAuthorizedSyncCredentials("2021001", "pw-a", "2021", "1")
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwtA))
        dao.upsertLibraryBookingPlan(cloudPlan("vis_mine", "cloud_user_A", "ARMED", ownerStudentId = "2021001"))
        dao.upsertLibraryBookingPlan(cloudPlan("vis_other_stu", "cloud_user_A", "ARMED", ownerStudentId = "2021999"))
        dao.upsertLibraryBookingPlan(cloudPlan("vis_foreign", "cloud_user_B", "ARMED", ownerStudentId = "2021001"))
        dao.upsertLibraryBookingPlan(localPlan("vis_local_mine", "2021001", "ARMED"))
        dao.upsertLibraryBookingPlan(localPlan("vis_local_other", "2021999", "ARMED"))

        val ids = repo.libraryBookingPlansFlow.first().map { it.id }.toSet()
        assertEquals(setOf("vis_mine", "vis_local_mine"), ids)
        repo.cloudHttpTransportForTest = null
    }
}
