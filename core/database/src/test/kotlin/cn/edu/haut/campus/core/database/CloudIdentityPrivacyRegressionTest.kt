package cn.edu.haut.campus.core.database

import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A2 身份世代守卫 + A3 云端 FIRE 仅同步 + A4 私信幂等键回归：
 * 全部使用可控假传输（可挂起闩锁），零真实网络。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CloudIdentityPrivacyRegressionTest {
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

    private fun buildInMemoryTestRepo(profileSeed: String): CampusRepository {
        val context = RuntimeEnvironment.getApplication()
        val dataStoreFile = java.io.File.createTempFile("idguard_test_${profileSeed}_", ".preferences_pb")
        dataStoreFile.deleteOnExit()
        val testDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined
            ),
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
            preferences = testDataStore,
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
                label = "测试身份_$profileSeed",
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
        ssoIssuer = "https://auth.yellowpeachxgp.com",
        ssoSubject = "sub_$cloudUserId",
        displayName = displayName,
        peachBalanceMicropeach = peach,
        holdSupported = false,
        accessToken = jwt,
        refreshToken = "ctseal1:ref_$cloudUserId",
        linkedAtEpochMillis = 3_000L
    )

    private fun syncPayloadFor(cloudUserId: String, displayName: String, peach: Double): String = """
        {"ok":true,"data":{"auth_version":1,
          "user":{"cloud_user_id":"$cloudUserId","display_name":"$displayName"},
          "wallet":{"peach_balance":$peach},
          "school_bindings":[],"reservation_jobs":[],"orders":[],"parcels":[]}}
    """.trimIndent()

    // ---------- A2: 世代守卫 ----------

    @Test
    fun `identity snapshot currency detects switch unlink relogin and base url change`() = runBlocking {
        val repo = buildInMemoryTestRepo("snap_currency")
        val jwtA = testCloudJwt("cloud_user_A")
        repo.cloudHttpTransportForTest = { _, _, _, _, _ -> """{"ok":true,"data":{}}""" }

        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwtA, displayName = "User A"))
        val s1 = repo.snapshotCloudIdentitySession()!!
        assertTrue(repo.isCloudIdentitySnapshotCurrent(s1))

        // 同账号重新登录也推进世代 -> 旧快照失效（ABA 防护核心）
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwtA, peach = 60_000L, displayName = "User A"))
        assertFalse(repo.isCloudIdentitySnapshotCurrent(s1))
        val s2 = repo.snapshotCloudIdentitySession()!!
        assertTrue(repo.isCloudIdentitySnapshotCurrent(s2))

        // 换 base-url -> 旧快照失效
        repo.updateNetworkProxyConfig(
            cn.edu.haut.campus.core.model.NetworkProxyConfig(
                customCloudApiBaseUrl = "https://mirror.example.com"
            )
        )
        assertFalse(repo.isCloudIdentitySnapshotCurrent(s2))
        val s3 = repo.snapshotCloudIdentitySession()!!
        assertTrue(repo.isCloudIdentitySnapshotCurrent(s3))

        // 解绑 -> 快照失效且绑定消失
        repo.unlinkXpeachCloudAccountAndRevokeCloudPlans()
        assertFalse(repo.isCloudIdentitySnapshotCurrent(s3))
        assertNull(repo.snapshotCloudIdentitySession())
        assertNull(dao.getCloudIdentityBindingSnapshot())

        // ABA 重绑同账号 -> 全新世代，此前任何快照都失效
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwtA, displayName = "User A"))
        assertFalse(repo.isCloudIdentitySnapshotCurrent(s1))
        assertFalse(repo.isCloudIdentitySnapshotCurrent(s2))
        assertFalse(repo.isCloudIdentitySnapshotCurrent(s3))
        val s4 = repo.snapshotCloudIdentitySession()!!
        assertTrue(repo.isCloudIdentitySnapshotCurrent(s4))
        repo.cloudHttpTransportForTest = null
    }

    @Test
    fun `mid-sync account switch drops stale write and never overwrites new binding`() = runBlocking {
        val repo = buildInMemoryTestRepo("mid_sync_ab")
        val jwtA = testCloudJwt("cloud_user_A")
        val jwtB = testCloudJwt("cloud_user_B")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gateOnce = AtomicBoolean(true)
        repo.cloudHttpTransportForTest = { method, url, _, token, _ ->
            if (method == "POST" && url.endsWith("/api/v1/sync/snapshot") &&
                token == jwtA && gateOnce.compareAndSet(true, false)
            ) {
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            when {
                method == "POST" && url.endsWith("/api/v1/sync/snapshot") ->
                    if (token == jwtA) syncPayloadFor("cloud_user_A", "User A", 33.0)
                    else syncPayloadFor("cloud_user_B", "User B", 9.0)
                else -> """{"ok":true,"data":{}}"""
            }
        }
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwtA, displayName = "User A"))
        val inFlight = async(Dispatchers.IO) { repo.syncWithCampusCloud() }
        assertTrue("A 同步应在传输层挂起", entered.await(10, TimeUnit.SECONDS))

        // 同步响应在途期间 A -> B：登录 B 推进世代
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_B", jwtB, peach = 40_000L, displayName = "User B"))
        release.countDown()
        inFlight.await()

        // A 的迟到响应必须被丢弃；最终绑定为 B 且余额来自 B 自己的响应/登录值
        withTimeout(10_000) {
            while (dao.getCloudIdentityBindingSnapshot()?.peachBalanceMicropeach != 90_000L &&
                dao.getCloudIdentityBindingSnapshot()?.peachBalanceMicropeach != 40_000L
            ) {
                delay(30)
            }
        }
        val finalBinding = dao.getCloudIdentityBindingSnapshot()!!
        assertEquals("cloud_user_B", finalBinding.cloudUserId)
        assertEquals("User B", finalBinding.displayName)
        assertNotEquals(330_000L, finalBinding.peachBalanceMicropeach)
        repo.cloudHttpTransportForTest = null
    }

    @Test
    fun `mid-sync unlink drops stale write without resurrecting binding row`() = runBlocking {
        val repo = buildInMemoryTestRepo("mid_sync_unlink")
        val jwtA = testCloudJwt("cloud_user_A")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gateOnce = AtomicBoolean(true)
        repo.cloudHttpTransportForTest = { method, url, _, token, _ ->
            if (method == "POST" && url.endsWith("/api/v1/sync/snapshot") &&
                token == jwtA && gateOnce.compareAndSet(true, false)
            ) {
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            when {
                method == "POST" && url.endsWith("/api/v1/sync/snapshot") ->
                    syncPayloadFor("cloud_user_A", "User A", 33.0)
                else -> """{"ok":true,"data":{}}"""
            }
        }
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwtA, displayName = "User A"))
        val inFlight = async(Dispatchers.IO) { repo.syncWithCampusCloud() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        repo.unlinkXpeachCloudAccountAndRevokeCloudPlans()
        release.countDown()
        inFlight.await()

        // 迟到响应绝不可 INSERT 复活已解绑的绑定行
        delay(300)
        assertNull(dao.getCloudIdentityBindingSnapshot())
        repo.cloudHttpTransportForTest = null
    }

    @Test
    fun `wallet refresh write-back is generation guarded after mid-flight unlink and ABA relogin`() = runBlocking {
        val repo = buildInMemoryTestRepo("wallet_guard")
        val jwtA = testCloudJwt("cloud_user_A")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gateOnce = AtomicBoolean(true)
        repo.cloudHttpTransportForTest = { method, url, _, _, _ ->
            when {
                url.endsWith("/api/v1/sync/snapshot") -> """{"ok":false,"error":{"code":"OFFLINE"}}"""
                method == "GET" && url.endsWith("/api/v1/me/wallet") -> {
                    if (gateOnce.compareAndSet(true, false)) {
                        entered.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                    """{"ok":true,"data":{"peach_balance":77.0}}"""
                }
                else -> """{"ok":true,"data":{}}"""
            }
        }
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwtA, displayName = "User A"))
        val refresh = async(Dispatchers.IO) { repo.refreshXpeachWalletBalance() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        // 响应在途期间解绑 -> 余额写回被丢弃，绑定行不可复活
        repo.unlinkXpeachCloudAccountAndRevokeCloudPlans()
        release.countDown()
        refresh.await()
        delay(300)
        assertNull(dao.getCloudIdentityBindingSnapshot())

        // ABA：重绑同账号 -> 旧世代响应同样不得写回
        gateOnce.set(true)
        val entered2 = CountDownLatch(1)
        val release2 = CountDownLatch(1)
        repo.cloudHttpTransportForTest = { method, url, _, _, _ ->
            when {
                url.endsWith("/api/v1/sync/snapshot") -> """{"ok":false}"""
                method == "GET" && url.endsWith("/api/v1/me/wallet") -> {
                    if (gateOnce.compareAndSet(true, false)) {
                        entered2.countDown()
                        release2.await(30, TimeUnit.SECONDS)
                    }
                    """{"ok":true,"data":{"peach_balance":88.0}}"""
                }
                else -> """{"ok":true,"data":{}}"""
            }
        }
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwtA, peach = 11_000L, displayName = "User A"))
        val refresh2 = async(Dispatchers.IO) { repo.refreshXpeachWalletBalance() }
        assertTrue(entered2.await(10, TimeUnit.SECONDS))
        repo.unlinkXpeachCloudAccountAndRevokeCloudPlans()
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwtA, peach = 11_000L, displayName = "User A"))
        release2.countDown()
        refresh2.await()
        delay(300)
        // ABA 后旧世代 88.0 写回必须被丢弃，余额保持登录时 11_000
        val binding = dao.getCloudIdentityBindingSnapshot()!!
        assertEquals("cloud_user_A", binding.cloudUserId)
        assertEquals(11_000L, binding.peachBalanceMicropeach)
        repo.cloudHttpTransportForTest = null
    }

    // ---------- A3: 云端托管 FIRE 绝不本地派发 ----------

    private fun armedCloudPlan(id: String, owner: String, jobId: String = "job_srv_1") = LibraryBookingPlanEntity(
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
        fireStatus = "ARMED",
        preferredAreaName = "五层中区",
        preferredSeatLabel = "05桌 A座",
        allowAlternativeSeat = true,
        cloudJobId = jobId,
        externalBookingId = null,
        evidenceSha256 = null,
        priceMicropeach = 10_000L,
        paymentState = "DEBIT_PENDING",
        status = "FINAL_CHECK_OK",
        stateVersion = 1,
        generation = 1,
        lastDiagnosticMessage = "云端托管中",
        createdAtEpochMillis = 500L,
        updatedAtEpochMillis = 2_000L,
        ownerCloudUserId = owner,
        ownerStudentId = null
    )

    @Test
    fun `scheduled cloud fire only syncs server state and never dispatches execute-now`() = runBlocking {
        val repo = buildInMemoryTestRepo("cloud_fire_sync_only")
        val jwtA = testCloudJwt("cloud_user_A")
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        repo.cloudHttpTransportForTest = { method, url, _, token, _ ->
            calls.add("$method $url")
            when {
                method == "POST" && url.endsWith("/api/v1/sync/snapshot") ->
                    syncPayloadFor("cloud_user_A", "User A", 5.0)
                else -> """{"ok":true,"data":{}}"""
            }
        }
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwtA, displayName = "User A"))
        dao.upsertLibraryBookingPlan(armedCloudPlan("plan_cloud_1", "cloud_user_A"))

        // 定时入口（闹钟 pending intent 等价路径）触发 FIRE：只允许 GET 同步，严禁 execute-now/本地派发
        val updated = repo.advanceBookingPlanStage(
            planId = "plan_cloud_1",
            stage = cn.edu.haut.campus.core.campusservices.TripleScheduleStage.FIRE
        ).getOrThrow()

        assertTrue("legacy cloud FIRE must not call execute-now", calls.none { it.contains("execute-now") })
        val latest = dao.getLibraryBookingPlanById("plan_cloud_1")!!
        assertFalse("cloud plan must not enter local DISPATCH_INTENT", latest.fireStatus.startsWith("DISPATCH_INTENT"))
        assertNotEquals("EXECUTING", latest.status)
        assertEquals(latest.status, updated.status)
        repo.cloudHttpTransportForTest = null
    }

    @Test
    fun `cloud fire for foreign-owned plan does not even sync`() = runBlocking {
        val repo = buildInMemoryTestRepo("cloud_fire_foreign")
        val jwtB = testCloudJwt("cloud_user_B")
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        repo.cloudHttpTransportForTest = { method, url, _, _, _ ->
            calls.add("$method $url")
            """{"ok":true,"data":{}}"""
        }
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_B", jwtB, displayName = "User B"))
        dao.upsertLibraryBookingPlan(armedCloudPlan("plan_cloud_foreign", "cloud_user_A"))
        // 登录触发的后台同步不属于本次断言范围：清空后再走 FIRE 定时入口
        delay(300)
        calls.clear()

        repo.advanceBookingPlanStage(
            planId = "plan_cloud_foreign",
            stage = cn.edu.haut.campus.core.campusservices.TripleScheduleStage.FIRE
        )
        assertTrue(calls.none { it.contains("execute-now") })
        assertTrue(calls.none { it.contains("/api/v1/sync/snapshot") })
        val latest = dao.getLibraryBookingPlanById("plan_cloud_foreign")!!
        assertEquals("FINAL_CHECK_OK", latest.status)
        repo.cloudHttpTransportForTest = null
    }

    @Test
    fun `explicit execute-now entry is gated to cloud plans`() = runBlocking {
        val repo = buildInMemoryTestRepo("execute_now_gate")
        dao.upsertLibraryBookingPlan(armedCloudPlan("plan_cloud_1", "cloud_user_A").copy(executionMode = "LOCAL_DEVICE"))
        val res = repo.executeCloudBookingPlanNow("plan_cloud_1")
        assertTrue(res.isFailure)
    }

    // ---------- A4: 私信 clientMessageId 幂等键 ----------

    @Test
    fun `contact request sends carry stable client message id keys not body hashes`() = runBlocking {
        val repo = buildInMemoryTestRepo("chat_idem")
        val jwtA = testCloudJwt("cloud_user_A")
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        repo.cloudHttpTransportForTest = { _, url, body, _, _ ->
            bodies.add("$url :: ${body.orEmpty()}")
            """{"ok":true,"data":{"id":"req_srv_1","status":"PENDING"}}"""
        }
        repo.saveVerifiedXpeachCloudAccount(loginProfile("cloud_user_A", jwtA, displayName = "User A"))

        // 同一条消息正文用户主动发送两次：幂等键必须不同（不能用正文 hash 去重）
        repo.submitMomentsContactRequest("post_x", "同一段认领说明", "wx:user", clientMessageId = "uuid_send_1")
        repo.submitMomentsContactRequest("post_x", "同一段认领说明", "wx:user", clientMessageId = "uuid_send_2")
        val submitBodies = bodies.filter { it.contains("/contact-requests") && !it.contains("/action") }
        assertEquals(2, submitBodies.size)
        assertTrue(submitBodies[0].contains("mcr_post_x_uuid_send_1"))
        assertTrue(submitBodies[1].contains("mcr_post_x_uuid_send_2"))

        // 不传 clientMessageId 时每次自动生成新键
        repo.submitMomentsContactRequest("post_x", "同一段认领说明", "wx:user")
        repo.submitMomentsContactRequest("post_x", "同一段认领说明", "wx:user")
        val allSubmits = bodies.filter { it.contains("/contact-requests") && !it.contains("/action") }
        assertEquals(4, allSubmits.size)
        val keys = allSubmits.map {
            org.json.JSONObject(it.substringAfter(" :: ")).optString("client_request_id")
        }
        assertEquals(4, keys.distinct().size)
        assertTrue(keys.all { it.startsWith("mcr_post_x_") })

        // REPLY 携带 client_message_id
        bodies.clear()
        repo.handleMomentsContactRequestAction(
            postId = "post_x",
            requestId = "req_srv_1",
            action = "REPLY",
            replyMessage = "好的，明天见",
            clientMessageId = "uuid_reply_1"
        )
        assertTrue(bodies.single().contains("uuid_reply_1"))

        // 公开留言端点请求体只含公开字段：私密联系方式/幂等键结构上无法进入公开 API
        bodies.clear()
        repo.addMomentsComment("post_x", "公开线索：在 3 教服务台", "")
        val commentCall = bodies.single()
        assertTrue(commentCall.contains("/comments"))
        val commentBody = org.json.JSONObject(commentCall.substringAfter(" :: "))
        assertEquals("公开线索：在 3 教服务台", commentBody.optString("content"))
        assertFalse(commentBody.has("contact_info"))
        assertFalse(commentBody.has("client_request_id"))
        assertFalse(commentBody.has("client_message_id"))
        repo.cloudHttpTransportForTest = null
    }
}
