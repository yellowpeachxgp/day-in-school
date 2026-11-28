package cn.edu.haut.campus.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * W0-05-c5 云同步编排搬迁特征冻结。
 * 只使用可控假传输与内存凭据仓，零真实网络、零真实学校账号；
 * 冻结快照合并、T−30/T−5/T 时间关系、补推本地云端计划、重新确认后的整量同步触发，
 * 以及提交屏障 hook 在「复核后、首写前」的精确调用位置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CampusRepositoryW005c5SyncCharacterizationTest {
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
        val dataStore: DataStore<Preferences>
    )

    private fun buildInMemoryTestRepo(profileSeed: String): RepoFixture {
        val context = RuntimeEnvironment.getApplication()
        val dataStoreFile = java.io.File.createTempFile("w005c5_test_" + profileSeed + "_", ".preferences_pb")
        dataStoreFile.deleteOnExit()
        val dataStore = PreferenceDataStoreFactory.create(
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
        val storage = isolatedAccountStoragePartition("prof_" + profileSeed)
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
                label = "c5特征_" + profileSeed,
                isLegacy = false,
                databaseName = storage.databaseName,
                preferencesName = storage.preferencesName,
                createdAtEpochMs = 1_000L
            ),
            academicCredentialVaultOverride = vault
        ).also { it.activateSession(1L) }
        return RepoFixture(repo, dataStore)
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
        refreshToken = "ctseal1:ref_" + cloudUserId,
        linkedAtEpochMillis = 3_000L
    )

    private fun syncPayloadWithJobAndParcel(cloudUserId: String): String = """
        {"ok":true,"data":{"auth_version":1,
          "user":{"cloud_user_id":"$cloudUserId","display_name":"用户"},
          "wallet":{"peach_balance":12.5},
          "school_bindings":[{"id":"bind_c5","student_no":"20240001"}],
          "reservation_jobs":[{"id":"job_c5_sync","plan_id":"plan_srv_c5",
            "area_code":"5F_MID","area_name":"五层中区","candidate_seats":["A101","A102"],
            "slot_window":"08:00-22:00","booking_local_date":"2099-01-02","target_local_time":"08:30:00",
            "state":"ARMED","repeat_days":2,"remaining_days":2,
            "student_no":"20240001","school_binding_id":"bind_c5"}],
          "orders":[{"id":"order_c5","job_id":"job_c5_sync","price_peach":0.5,
            "payment_state":"DEBIT_PENDING","paid_at":null,"created_at":"2026-11-27T00:00:00Z"}],
          "parcels":[{"id":"prc_srv_c5","tracking_number":"trk_c5_sync",
            "carrier_code":"CAINIAO","carrier_name":"校园快递","status":"READY_FOR_PICKUP",
            "pickup_code":"C5-01","station_name":"莲花街北苑菜鸟驿站","item_title":"同步特征件",
            "raw_notification_text":null}]}}
    """.trimIndent()

    /**
     * 热身快照只携带一个终态 job、不携带包裹。
     * 该 job 落库必然发生在同步提交屏障之后，因此它是「后台首次同步已经越过 hook 点」
     * 的确定性信号；终态 job 不参与后续活动计划上推，避免热身数据改变被测同步形状。
     */
    private fun syncPayloadWithTerminalWarmupJobOnly(cloudUserId: String): String = """
        {"ok":true,"data":{"auth_version":1,
          "user":{"cloud_user_id":"$cloudUserId","display_name":"用户"},
          "wallet":{"peach_balance":12.5},
          "school_bindings":[{"id":"bind_c5","student_no":"20240001"}],
          "reservation_jobs":[{"id":"job_c5_warmup","plan_id":"plan_srv_c5_warmup",
            "area_code":"5F_MID","area_name":"五层中区","candidate_seats":["W101"],
            "slot_window":"08:00-22:00","booking_local_date":"2099-01-04","target_local_time":"09:00:00",
            "state":"CANCELLED","repeat_days":1,"remaining_days":0,
            "student_no":"20240001","school_binding_id":"bind_c5"}],
          "orders":[],"parcels":[]}}
    """.trimIndent()

    private fun cloudPlan(
        id: String,
        ownerCloudUserId: String?,
        ownerStudentId: String?,
        executionMode: String = "CLOUD_HOSTED",
        status: String = "NEEDS_RECONFIRMATION",
        stateVersion: Int = 7
    ) = LibraryBookingPlanEntity(
        id = id,
        schoolId = "haut",
        executionMode = executionMode,
        targetLocalDate = "2099-01-03",
        targetLocalTime = "08:30:00",
        timezoneId = "Asia/Shanghai",
        precheckAtEpochMillis = 1_000L,
        finalCheckAtEpochMillis = 2_000L,
        fireAtEpochMillis = 3_000L,
        precheckStatus = "ARMED",
        finalCheckStatus = "ARMED",
        fireStatus = status,
        preferredAreaName = "五层中区",
        preferredSeatLabel = "05桌 A座",
        allowAlternativeSeat = true,
        cloudJobId = "job_$id",
        externalBookingId = null,
        evidenceSha256 = null,
        priceMicropeach = 10_000L,
        paymentState = "DEBIT_PENDING",
        status = status,
        stateVersion = stateVersion,
        generation = 1,
        lastDiagnosticMessage = "需要重新确认授权",
        createdAtEpochMillis = 500L,
        updatedAtEpochMillis = 2_000L,
        repeatDays = 2,
        remainingDays = 2,
        ownerCloudUserId = ownerCloudUserId,
        ownerStudentId = ownerStudentId
    )

    private suspend fun waitUntil(description: String, condition: suspend () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            delay(25)
        }
        fail("等待超时：" + description)
    }

    @Test
    fun cloud_snapshot_merge_freezes_booking_parcel_timing_and_followup_upload_shape() = runBlocking {
        val fixture = buildInMemoryTestRepo("snapshot_merge")
        val repo = fixture.repository
        val cloudUserId = "cloud_user_c5_snapshot"
        val jwt = testCloudJwt(cloudUserId)
        val snapshotBodies = ConcurrentLinkedQueue<String>()
        val notifyReasons = ConcurrentLinkedQueue<String>()
        repo.onScheduleOrPolicyChangedListener = { reason -> notifyReasons += reason }
        repo.cloudHttpTransportForTest = { method, url, body, _, _ ->
            when {
                method == "POST" && url.endsWith("/api/v1/sync/snapshot") -> {
                    body?.let(snapshotBodies::add)
                    syncPayloadWithJobAndParcel(cloudUserId)
                }
                method == "POST" && url.endsWith("/api/v1/me/consents") ->
                    """{"ok":true,"data":{"id":"consent_c5","auth_version":3}}"""
                method == "POST" && url.endsWith("/api/v1/school-bindings") ->
                    """{"ok":true,"data":{"id":"bind_c5"}}"""
                method == "POST" && url.endsWith("/api/v1/school-bindings/bind_c5/complete") ->
                    """{"ok":true,"data":{}}"""
                else -> """{"ok":true,"data":{}}"""
            }
        }
        repo.saveAuthorizedSyncCredentials("20240001", "pw-c5", "2026", "3")
        repo.saveVerifiedXpeachCloudAccount(loginProfile(cloudUserId, jwt, displayName = "用户"))

        waitUntil("登录后首次云同步合并远端计划") {
            dao.getLibraryBookingPlanById("plan_job_c5_sync") != null
        }
        waitUntil("登录后首次云同步合并远端包裹") {
            dao.getParcelByTrackingNumber("trk_c5_sync") != null
        }
        waitUntil("包裹变更触发原通知原因") {
            notifyReasons.contains("PARCEL_CLOUD_SYNCED")
        }

        val mergedPlan = dao.getLibraryBookingPlanById("plan_job_c5_sync")!!
        val fireAt = LocalDate.parse("2099-01-02")
            .atTime(LocalTime.of(8, 30))
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()
        assertEquals(cloudUserId, mergedPlan.ownerCloudUserId)
        assertEquals("20240001", mergedPlan.ownerStudentId)
        assertEquals("job_c5_sync", mergedPlan.cloudJobId)
        assertEquals("ARMED", mergedPlan.status)
        assertEquals("ARMED", mergedPlan.precheckStatus)
        assertEquals("ARMED", mergedPlan.finalCheckStatus)
        assertEquals("ARMED", mergedPlan.fireStatus)
        assertEquals(fireAt, mergedPlan.fireAtEpochMillis)
        assertEquals("T−30 初检时间必须逐毫秒保持", fireAt - 1_800_000L, mergedPlan.precheckAtEpochMillis)
        assertEquals("T−5 复检时间必须逐毫秒保持", fireAt - 300_000L, mergedPlan.finalCheckAtEpochMillis)
        assertEquals(2, mergedPlan.repeatDays)
        assertEquals(2, mergedPlan.remainingDays)
        assertEquals(5_000L, mergedPlan.priceMicropeach)
        assertEquals("DEBIT_PENDING", mergedPlan.paymentState)
        assertEquals("五层中区", mergedPlan.preferredAreaName)
        assertEquals("A101, A102", mergedPlan.preferredSeatLabel)

        val mergedParcel = dao.getParcelByTrackingNumber("trk_c5_sync")!!
        assertEquals("READY_FOR_PICKUP", mergedParcel.status)
        assertEquals("C5-01", mergedParcel.pickupCode)
        assertEquals("莲花街北苑菜鸟驿站", mergedParcel.stationName)
        assertEquals("同步特征件", mergedParcel.itemTitle)

        val versionBeforeExplicitSync = mergedPlan.stateVersion
        snapshotBodies.clear()
        val reconfirmed = repo.reconfirmAndRestoreCloudBookingPlans()
        assertTrue(reconfirmed.isSuccess)
        val afterExplicit = dao.getLibraryBookingPlanById("plan_job_c5_sync")!!
        assertEquals(versionBeforeExplicitSync + 1, afterExplicit.stateVersion)
        assertEquals("云端已有一行任务不得重复落库", 1, dao.getLibraryBookingPlansSnapshot().count { it.cloudJobId == "job_c5_sync" })

        val uploadBody = snapshotBodies.lastOrNull()?.let(::JSONObject)
        assertNotNull(uploadBody)
        val upserts = uploadBody!!.optJSONArray("reservation_plan_upserts")
        assertNotNull("已有云端活动计划的后续同步必须上推本地计划镜像", upserts)
        val firstUpsert = upserts!!.getJSONObject(0)
        assertEquals("plan_job_c5_sync", firstUpsert.optString("plan_id"))
        assertEquals("2099-01-02", firstUpsert.optString("target_local_date"))
        assertEquals("08:30:00", firstUpsert.optString("target_local_time"))
        assertEquals("五层中区", firstUpsert.optString("area_name"))
        assertEquals(2, firstUpsert.optInt("repeat_days"))
        assertEquals(2, firstUpsert.optInt("remaining_days"))
        assertTrue(firstUpsert.optBoolean("allow_alternative_seat"))
        repo.cloudHttpTransportForTest = null
    }

    @Test
    fun reconfirm_restores_only_scoped_plans_then_triggers_full_sync_in_original_order() = runBlocking {
        val fixture = buildInMemoryTestRepo("reconfirm_order")
        val repo = fixture.repository
        val cloudUserId = "cloud_user_c5_reconfirm"
        val jwt = testCloudJwt(cloudUserId)
        val calls = ConcurrentLinkedQueue<String>()
        repo.cloudHttpTransportForTest = { method, url, body, _, _ ->
            calls += method + " " + url
            when {
                method == "POST" && url.endsWith("/api/v1/sync/snapshot") ->
                    syncPayloadWithTerminalWarmupJobOnly(cloudUserId)
                method == "POST" && url.endsWith("/api/v1/me/consents") ->
                    """{"ok":true,"data":{"id":"consent_c5","auth_version":3}}"""
                method == "POST" && url.endsWith("/api/v1/school-bindings") ->
                    """{"ok":true,"data":{"id":"bind_c5"}}"""
                method == "POST" && url.endsWith("/api/v1/school-bindings/bind_c5/complete") ->
                    """{"ok":true,"data":{}}"""
                else -> """{"ok":true,"data":{}}"""
            }
        }
        repo.saveAuthorizedSyncCredentials("20240001", "pw-c5", "2026", "3")
        repo.saveVerifiedXpeachCloudAccount(loginProfile(cloudUserId, jwt, displayName = "用户"))
        waitUntil("登录后首次快照越过落库屏障") {
            dao.getLibraryBookingPlanById("plan_job_c5_warmup") != null
        }

        dao.upsertLibraryBookingPlan(
            cloudPlan(
                id = "plan_reconfirm_owned",
                ownerCloudUserId = cloudUserId,
                ownerStudentId = "20240001"
            )
        )
        dao.upsertLibraryBookingPlan(
            cloudPlan(
                id = "plan_reconfirm_other_student",
                ownerCloudUserId = cloudUserId,
                ownerStudentId = "20249999"
            )
        )
        dao.upsertLibraryBookingPlan(
            cloudPlan(
                id = "plan_reconfirm_local_only",
                ownerCloudUserId = null,
                ownerStudentId = "20240001",
                executionMode = "LOCAL_DEVICE"
            )
        )
        calls.clear()

        val result = repo.reconfirmAndRestoreCloudBookingPlans()
        assertTrue(result.isSuccess)

        val restored = dao.getLibraryBookingPlanById("plan_reconfirm_owned")!!
        assertEquals("ARMED", restored.status)
        assertEquals(8, restored.stateVersion)
        assertEquals(cloudUserId, restored.ownerCloudUserId)
        assertEquals("20240001", restored.ownerStudentId)
        assertEquals("job_plan_reconfirm_owned", restored.cloudJobId)
        assertEquals("已重新确认云端授权，正在同步恢复云端托管抢座", restored.lastDiagnosticMessage)

        val otherStudent = dao.getLibraryBookingPlanById("plan_reconfirm_other_student")!!
        assertEquals("NEEDS_RECONFIRMATION", otherStudent.status)
        assertEquals(7, otherStudent.stateVersion)
        val localOnly = dao.getLibraryBookingPlanById("plan_reconfirm_local_only")!!
        assertEquals("NEEDS_RECONFIRMATION", localOnly.status)
        assertEquals(7, localOnly.stateVersion)

        val orderedCalls = calls.toList()
        assertEquals(4, orderedCalls.size)
        assertTrue(orderedCalls[0].endsWith("/api/v1/me/consents"))
        assertTrue(orderedCalls[1].endsWith("/api/v1/school-bindings"))
        assertTrue(orderedCalls[2].contains("/api/v1/school-bindings/bind_c5/complete"))
        assertTrue("重新确认完成后必须原位触发一次整量同步", orderedCalls[3].endsWith("/api/v1/sync/snapshot"))
        repo.cloudHttpTransportForTest = null
    }

    @Test
    fun sync_commit_barrier_hook_runs_after_recheck_and_before_first_parcel_write() = runBlocking {
        val fixture = buildInMemoryTestRepo("commit_hook_order")
        val repo = fixture.repository
        val cloudUserId = "cloud_user_c5_commit_hook"
        val jwt = testCloudJwt(cloudUserId)
        val mode = AtomicReference("empty")
        repo.cloudHttpTransportForTest = { method, url, _, _, _ ->
            if (method == "POST" && url.endsWith("/api/v1/sync/snapshot")) {
                if (mode.get() == "parcel") {
                    syncPayloadWithJobAndParcel(cloudUserId)
                } else {
                    syncPayloadWithTerminalWarmupJobOnly(cloudUserId)
                }
            } else {
                """{"ok":true,"data":{}}"""
            }
        }
        repo.saveAuthorizedSyncCredentials("20240001", "pw-c5", "2026", "3")
        repo.saveVerifiedXpeachCloudAccount(loginProfile(cloudUserId, jwt, displayName = "用户"))
        // 终态热身 job 的落库位于提交屏障 hook 点之后；等到它再安装 hook，
        // 后台首次同步无论被调度得多慢都不会被计入下面的显式同步断言。
        waitUntil("登录后热身快照越过同步提交屏障") {
            dao.getLibraryBookingPlanById("plan_job_c5_warmup") != null
        }

        var hookCalls = 0
        var hookSawParcel = true
        repo.syncCommitBarrierHookForTest = {
            hookCalls += 1
            hookSawParcel = dao.getParcelByTrackingNumber("trk_c5_sync") != null
        }
        mode.set("parcel")
        val result = repo.syncWithCampusCloud()
        assertTrue(result.isSuccess)
        assertEquals(1, hookCalls)
        assertFalse("hook 必须位于复核后、首写前", hookSawParcel)
        assertNotNull(dao.getParcelByTrackingNumber("trk_c5_sync"))
        repo.syncCommitBarrierHookForTest = null
        repo.cloudHttpTransportForTest = null
    }
}
