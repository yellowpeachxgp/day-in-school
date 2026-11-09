package cn.edu.haut.campus.core.database

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class BookingDispatchExclusiveClaimDaoTest {
    private lateinit var database: HautDatabase
    private lateinit var dao: CloudAndParcelDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            HautDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        dao = database.cloudAndParcelDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `first claim atomically acquires exclusive ownership and blocks second caller while allowing same-attempt retry`() = runBlocking {
        val planId = "plan_exclusive_claim_01"
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = planId,
                schoolId = "haut",
                executionMode = "LOCAL_ASSISTED",
                targetLocalDate = "2026-11-11",
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
                cloudJobId = null,
                externalBookingId = null,
                evidenceSha256 = null,
                priceMicropeach = 0L,
                paymentState = "NOT_REQUIRED",
                status = "FINAL_CHECK_OK",
                stateVersion = 2,
                generation = 1,
                lastDiagnosticMessage = null,
                createdAtEpochMillis = 500L,
                updatedAtEpochMillis = 2_000L
            )
        )

        // 1. Alarm caller claims first -> succeeds (1)
        val alarmClaim = dao.claimBookingPlanForDispatch(
            planId = planId,
            expectedExecutionMode = "LOCAL_ASSISTED",
            expectedGeneration = 1,
            attemptId = "att_alarm_001",
            nowEpochMillis = 3_001L
        )
        assertEquals(1, alarmClaim)

        // 2. Concurrent UI caller attempts to claim the same plan & generation -> MUST fail (0)
        val uiDuplicateClaim = dao.claimBookingPlanForDispatch(
            planId = planId,
            expectedExecutionMode = "LOCAL_ASSISTED",
            expectedGeneration = 1,
            attemptId = "att_ui_002",
            nowEpochMillis = 3_002L
        )
        assertEquals(0, uiDuplicateClaim)

        // Even repeating first-time claim with the same attemptId MUST fail (0) once EXECUTING
        val repeatInitialClaim = dao.claimBookingPlanForDispatch(
            planId = planId,
            expectedExecutionMode = "LOCAL_ASSISTED",
            expectedGeneration = 1,
            attemptId = "att_alarm_001",
            nowEpochMillis = 3_003L
        )
        assertEquals(0, repeatInitialClaim)

        // 3. Retry / candidate wave check only succeeds for the winning attemptId ("att_alarm_001")
        assertEquals(
            0,
            dao.retryBookingPlanDispatchForAttempt(
                planId = planId,
                expectedExecutionMode = "LOCAL_ASSISTED",
                expectedGeneration = 1,
                attemptId = "att_ui_002",
                nowEpochMillis = 3_004L
            )
        )
        assertEquals(
            1,
            dao.retryBookingPlanDispatchForAttempt(
                planId = planId,
                expectedExecutionMode = "LOCAL_ASSISTED",
                expectedGeneration = 1,
                attemptId = "att_alarm_001",
                nowEpochMillis = 3_005L
            )
        )
        assertEquals(
            1,
            dao.isBookingPlanOwnedByAttempt(
                planId = planId,
                expectedExecutionMode = "LOCAL_ASSISTED",
                expectedGeneration = 1,
                attemptId = "att_alarm_001"
            )
        )
        assertEquals(
            0,
            dao.isBookingPlanOwnedByAttempt(
                planId = planId,
                expectedExecutionMode = "LOCAL_ASSISTED",
                expectedGeneration = 1,
                attemptId = "att_ui_002"
            )
        )

        // 4. Queued cancel SQL refuses to overwrite an already-claimed DISPATCH_INTENT:<attemptId> row as CANCELLED
        val queuedCancelWhileExecuting = dao.cancelQueuedBookingPlanAtomically(
            planId = planId,
            diagnosticMessage = "不应直接标为未派发取消",
            nowEpochMillis = 3_006L
        )
        assertEquals(0, queuedCancelWhileExecuting)

        // 5. Once cancelled mid-flight (generation incremented + DELEGATION_STOPPED_RESULT_UNKNOWN), winning attempt can no longer retry
        val current = dao.getLibraryBookingPlanById(planId)!!
        dao.upsertLibraryBookingPlan(
            current.copy(
                status = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                fireStatus = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                generation = current.generation + 1,
                stateVersion = current.stateVersion + 1
            )
        )
        assertEquals(
            0,
            dao.retryBookingPlanDispatchForAttempt(
                planId = planId,
                expectedExecutionMode = "LOCAL_ASSISTED",
                expectedGeneration = 1,
                attemptId = "att_alarm_001",
                nowEpochMillis = 3_007L
            )
        )
    }

    @Test
    fun `logout and account switch scrub unlocked ON_REQUEST contacts so offline guest and account B cannot read or copy them`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dataStoreFile = java.io.File.createTempFile("moments_logout_switch_", ".preferences_pb")
        dataStoreFile.deleteOnExit()
        val testDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
            produceFile = { dataStoreFile }
        )
        val storage = isolatedAccountStoragePartition("test-moments-isolation-profile")
        val profileEntity = AccountProfileEntity(
            id = "test-moments-isolation-profile",
            accountId = "acct-moments-01",
            schoolId = "haut",
            schoolName = "河南工业大学",
            province = "河南省",
            city = "郑州市",
            campusId = "lianhuajie",
            campusName = "莲花街校区",
            timeZoneId = "Asia/Shanghai",
            label = "测试档案",
            isLegacy = false,
            databaseName = storage.databaseName,
            preferencesName = storage.preferencesName,
            createdAtEpochMs = 1_000L
        )
        val repo = CampusRepository(
            appContext = context,
            database = database,
            preferences = testDataStore,
            accountProfile = profileEntity
        )

        val unlockedSnapshotForAccountA = """
            {
              "updated_at": "2026-11-10T01:30:00Z",
              "posts": [
                {
                  "id": "mp_on_request_secret",
                  "type": "LOST",
                  "title": "遗失校园一卡通",
                  "category": "证件",
                  "campus": "莲花街校区",
                  "location": "31号楼",
                  "description": "蓝色卡套",
                  "contact": "微信: secret_wx_for_account_A",
                  "contact_visibility": "ON_REQUEST",
                  "has_protected_contact": false,
                  "is_owner": true,
                  "can_manage": true,
                  "viewer_role": "OWNER",
                  "comments": [
                    {
                      "id": "mc_priv_1",
                      "author_name": "旧版私密留言",
                      "content": "【私密联系发起人】13900009999",
                      "contact_hint": "13900009999"
                    }
                  ]
                },
                {
                  "id": "mp_public_open",
                  "type": "FOUND",
                  "title": "招领雨伞",
                  "category": "水杯雨伞",
                  "campus": "莲花街校区",
                  "location": "二楼大厅",
                  "description": "灰色折叠伞",
                  "contact": "QQ: 66778899",
                  "contact_visibility": "PUBLIC",
                  "has_protected_contact": false,
                  "is_owner": false,
                  "can_manage": false,
                  "viewer_role": "AUTHENTICATED",
                  "comments": []
                }
              ]
            }
        """.trimIndent()

        // 1. Account A logs in and caches Moments snapshot with unlocked ON_REQUEST contact
        repo.saveVerifiedXpeachCloudAccount(
            cn.edu.haut.campus.core.campusservices.XpeachCloudAccountProfile(
                cloudUserId = "cloud_user_A",
                ssoIssuer = "https://auth.yellowpeachxgp.com",
                ssoSubject = "sub_A",
                displayName = "User A",
                peachBalanceMicropeach = 0L,
                holdSupported = false,
                accessToken = "ctseal1:token_user_A",
                refreshToken = "ctseal1:refresh_user_A",
                linkedAtEpochMillis = 2_000L
            )
        )
        val rawMomentsKey = androidx.datastore.preferences.core.stringPreferencesKey("cloud_moments_snapshot_json_v1")
        val rawMyPostIdsKey = androidx.datastore.preferences.core.stringPreferencesKey("moments_my_post_ids_v1")
        testDataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[rawMomentsKey] = unlockedSnapshotForAccountA
                this[rawMyPostIdsKey] = "mp_on_request_secret"
            }
        }

        val stateForA = repo.preferencesFlow.first().campusMomentsSnapshot
        val secretPostForA = stateForA.posts.first { it.id == "mp_on_request_secret" }
        assertEquals("微信: secret_wx_for_account_A", secretPostForA.contact)
        org.junit.Assert.assertTrue(secretPostForA.canViewerReadRealContact)
        org.junit.Assert.assertTrue(secretPostForA.isOwner)

        // 2. Account A logs out -> offline guest opens Moments: restricted contact & owner role MUST be scrubbed!
        repo.unlinkXpeachCloudAccountAndRevokeCloudPlans()
        val guestStateOffline = repo.preferencesFlow.first().campusMomentsSnapshot
        val secretPostForGuest = guestStateOffline.posts.first { it.id == "mp_on_request_secret" }
        val publicPostForGuest = guestStateOffline.posts.first { it.id == "mp_public_open" }

        assertEquals("需发起联系/认领申请后查看", secretPostForGuest.contact)
        org.junit.Assert.assertFalse(secretPostForGuest.canViewerReadRealContact)
        org.junit.Assert.assertFalse(secretPostForGuest.isOwner)
        org.junit.Assert.assertFalse(secretPostForGuest.canManage)
        org.junit.Assert.assertTrue(guestStateOffline.myPostIds.isEmpty())
        assertEquals("QQ: 66778899", publicPostForGuest.contact)
        org.junit.Assert.assertTrue(publicPostForGuest.canViewerReadRealContact)

        val storedJsonAfterLogout = testDataStore.data.first()[rawMomentsKey].orEmpty()
        org.junit.Assert.assertFalse(storedJsonAfterLogout.contains("secret_wx_for_account_A"))
        org.junit.Assert.assertFalse(storedJsonAfterLogout.contains("13900009999"))

        // 3. Account A logs back in, caches unlocked contact again, then switches directly to Account B while offline!
        repo.saveVerifiedXpeachCloudAccount(
            cn.edu.haut.campus.core.campusservices.XpeachCloudAccountProfile(
                cloudUserId = "cloud_user_A",
                ssoIssuer = "https://auth.yellowpeachxgp.com",
                ssoSubject = "sub_A",
                displayName = "User A",
                peachBalanceMicropeach = 0L,
                holdSupported = false,
                accessToken = "ctseal1:token_user_A",
                refreshToken = "ctseal1:refresh_user_A",
                linkedAtEpochMillis = 3_000L
            )
        )
        testDataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[rawMomentsKey] = unlockedSnapshotForAccountA
                this[rawMyPostIdsKey] = "mp_on_request_secret"
            }
        }

        // Switch directly to Account B (no logout in between, offline!)
        repo.saveVerifiedXpeachCloudAccount(
            cn.edu.haut.campus.core.campusservices.XpeachCloudAccountProfile(
                cloudUserId = "cloud_user_B",
                ssoIssuer = "https://auth.yellowpeachxgp.com",
                ssoSubject = "sub_B",
                displayName = "User B",
                peachBalanceMicropeach = 0L,
                holdSupported = false,
                accessToken = "ctseal1:token_user_B",
                refreshToken = "ctseal1:refresh_user_B",
                linkedAtEpochMillis = 4_000L
            )
        )

        val stateForBOffline = repo.preferencesFlow.first().campusMomentsSnapshot
        val secretPostForB = stateForBOffline.posts.first { it.id == "mp_on_request_secret" }
        assertEquals("需发起联系/认领申请后查看", secretPostForB.contact)
        org.junit.Assert.assertFalse(secretPostForB.canViewerReadRealContact)
        org.junit.Assert.assertFalse(secretPostForB.isOwner)
        org.junit.Assert.assertFalse(secretPostForB.canManage)
        org.junit.Assert.assertTrue(stateForBOffline.myPostIds.isEmpty())

        val storedJsonAfterSwitchToB = testDataStore.data.first()[rawMomentsKey].orEmpty()
        org.junit.Assert.assertFalse(storedJsonAfterSwitchToB.contains("secret_wx_for_account_A"))
    }
}
