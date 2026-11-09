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

        // 4. Unlink cloud account MUST preserve DELEGATION_STOPPED_SCHOOL_CONFIRMED & RESULT_UNKNOWN (never overwrite to CANCELLED!),
        //    MANUAL_REMINDER fires as REMINDER_SENT (never CONFIRMED), and wipeAllPersonalData clears Moments cache completely!
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_cloud_stopped_confirmed",
                schoolId = "haut",
                executionMode = "CLOUD_HOSTED",
                targetLocalDate = "2026-11-15",
                targetLocalTime = "07:00:00",
                timezoneId = "Asia/Shanghai",
                precheckAtEpochMillis = 1_000L,
                finalCheckAtEpochMillis = 2_000L,
                fireAtEpochMillis = 3_000L,
                precheckStatus = "PRECHECK_OK",
                finalCheckStatus = "FINAL_CHECK_OK",
                fireStatus = "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                preferredAreaName = "五层中区",
                preferredSeatLabel = "05桌 A座",
                allowAlternativeSeat = true,
                cloudJobId = "job_real_uuid_01",
                externalBookingId = "88776655",
                evidenceSha256 = "sha256_proof",
                priceMicropeach = 10_000L,
                paymentState = "HELD_PENDING_REVIEW",
                status = "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                stateVersion = 3,
                generation = 1,
                lastDiagnosticMessage = "委托已停但学校已约成功",
                createdAtEpochMillis = 500L,
                updatedAtEpochMillis = 3_000L,
                repeatDays = 1,
                remainingDays = 0
            )
        )
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_manual_reminder_01",
                schoolId = "haut",
                executionMode = "MANUAL_REMINDER",
                targetLocalDate = "2026-11-16",
                targetLocalTime = "07:00:00",
                timezoneId = "Asia/Shanghai",
                precheckAtEpochMillis = 1_000L,
                finalCheckAtEpochMillis = 2_000L,
                fireAtEpochMillis = 3_000L,
                precheckStatus = "ARMED",
                finalCheckStatus = "ARMED",
                fireStatus = "ARMED",
                preferredAreaName = "五层中区",
                preferredSeatLabel = "01桌 A座",
                allowAlternativeSeat = true,
                cloudJobId = null,
                externalBookingId = null,
                evidenceSha256 = null,
                priceMicropeach = 0L,
                paymentState = "NOT_REQUIRED",
                status = "ARMED",
                stateVersion = 1,
                generation = 1,
                lastDiagnosticMessage = null,
                createdAtEpochMillis = 500L,
                updatedAtEpochMillis = 500L
            )
        )

        repo.unlinkXpeachCloudAccountAndRevokeCloudPlans()
        val stoppedAfterUnlink = dao.getLibraryBookingPlanById("plan_cloud_stopped_confirmed")!!
        assertEquals("DELEGATION_STOPPED_SCHOOL_CONFIRMED", stoppedAfterUnlink.status)
        assertEquals("88776655", stoppedAfterUnlink.externalBookingId)

        // Same-day duplicate booking on 2026-11-15 is STILL blocked after unlink!
        val dupSameDay = repo.createDualEngineBookingPlan(
            executionMode = cn.edu.haut.campus.core.campusservices.BookingExecutionMode.LOCAL_DEVICE,
            targetDate = java.time.LocalDate.of(2026, 11, 15),
            targetTimeText = "07:00:00",
            preferredAreaName = "五层中区",
            preferredSeatLabel = "02桌 B座"
        )
        org.junit.Assert.assertTrue(dupSameDay.isFailure)

        // MANUAL_REMINDER fires as REMINDER_SENT, NOT CONFIRMED!
        val firedReminder = repo.advanceBookingPlanStage(
            planId = "plan_manual_reminder_01",
            stage = cn.edu.haut.campus.core.campusservices.TripleScheduleStage.FIRE
        ).getOrThrow()
        assertEquals("REMINDER_SENT", firedReminder.status)

        // Wipe removes all Moments snapshot & personal keys from DataStore
        repo.wipeAllPersonalData()
        org.junit.Assert.assertEquals(null, testDataStore.data.first()[rawMomentsKey])
    }

    @Test
    fun `upgraded user on 2025-2026-2 rolls over to 2026-2027-1 and single-day cancel after edit replaces override cleanly`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dataStoreFile = java.io.File.createTempFile("haut_term_rollover_", ".preferences_pb")
        dataStoreFile.deleteOnExit()
        val testDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
            produceFile = { dataStoreFile }
        )
        val campusDao = database.campusDao()
        campusDao.upsertBuildings(HautMockSeedData.lianhuajieBuildings.map { it.toEntity() })
        campusDao.upsertBuildingAliases(HautMockSeedData.defaultBuildingAliases.map { it.toEntity() })
        campusDao.upsertPeriodTimes(HautMockSeedData.defaultPeriodTimes.map { it.toEntity() })
        campusDao.upsertTerms(
            listOf(
                cn.edu.haut.campus.core.model.Term(
                    id = "2025-2026-2",
                    academicYear = "2025-2026",
                    termNumber = 2,
                    startDate = java.time.LocalDate.of(2026, 3, 2),
                    weekCount = 20,
                    timeTableId = HautMockSeedData.DEFAULT_TIMETABLE_ID,
                    current = true
                ).toEntity()
            )
        )
        val savedXnmKey = androidx.datastore.preferences.core.stringPreferencesKey("saved_jw_sync_xnm")
        val savedXqmKey = androidx.datastore.preferences.core.stringPreferencesKey("saved_jw_sync_xqm")
        testDataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[savedXnmKey] = "2025"
                this[savedXqmKey] = "12"
            }
        }

        val storage = isolatedAccountStoragePartition("prof_rollover_01")
        val repo = CampusRepository(
            appContext = context,
            database = database,
            preferences = testDataStore,
            accountProfile = AccountProfileEntity(
                id = "prof_rollover_01",
                accountId = "acct_rollover_01",
                schoolId = "haut",
                schoolName = "河南工业大学",
                province = "河南省",
                city = "郑州市",
                campusId = "lianhuajie",
                campusName = "莲花街校区",
                timeZoneId = "Asia/Shanghai",
                label = "测试升级账号",
                isLegacy = false,
                databaseName = storage.databaseName,
                preferencesName = storage.preferencesName,
                createdAtEpochMs = 1_000L
            )
        )
        repo.activateSession(1L)
        repo.ensureSeasonalTermRolloverIfNeeded(today = java.time.LocalDate.of(2026, 10, 15))

        assertEquals("2026-2027-1", campusDao.getCurrentTerm()?.id)
        assertEquals("2026", testDataStore.data.first()[savedXnmKey])
        assertEquals("3", testDataStore.data.first()[savedXqmKey])

        // Verify single-day edit -> single-day cancel deduplicates override and resolves buildingId
        val activeTerm = campusDao.getCurrentTerm()!!.toDomain()
        val course = cn.edu.haut.campus.core.model.Course(
            id = "c_test_01",
            name = "数据结构",
            teacher = "张老师",
            colorArgb = 0xFF053968,
            source = cn.edu.haut.campus.core.model.CourseSource.MANUAL,
            userModified = true
        )
        val rule1 = cn.edu.haut.campus.core.model.CourseScheduleRule(
            id = "r_test_1_2",
            courseId = course.id,
            termId = activeTerm.id,
            weekday = java.time.DayOfWeek.MONDAY,
            startPeriod = 1,
            endPeriod = 2,
            weeks = setOf(1, 2, 3),
            rawRoom = "莲4号教学楼101",
            buildingId = "b_t4"
        )
        val rule2 = rule1.copy(id = "r_test_3_4", startPeriod = 3, endPeriod = 4)
        campusDao.upsertCourse(course.toEntity())
        campusDao.upsertRules(listOf(rule1.toEntity(), rule2.toEntity()))

        val monday = java.time.LocalDate.of(2026, 9, 7)
        // 1) Single-day room modification resolves buildingId ("莲5号202" -> "b_t5")
        repo.saveOccurrenceOverride(
            cn.edu.haut.campus.core.model.OccurrenceOverride(
                id = "ov_mod_${monday}_r_test_1_2",
                termId = activeTerm.id,
                courseId = course.id,
                courseRuleId = rule1.id,
                date = monday,
                startPeriod = 1,
                endPeriod = 2,
                room = "5号教学楼202",
                canceled = false,
                reason = "单次调课"
            )
        )
        val afterEdit = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            date = monday,
            term = activeTerm,
            courses = listOf(course),
            rules = listOf(rule1),
            periodTimes = HautMockSeedData.defaultPeriodTimes,
            occurrenceOverrides = campusDao.getOccurrenceOverridesSnapshot().map { it.toDomain() },
            buildings = HautMockSeedData.lianhuajieBuildings
        )
        assertEquals(1, afterEdit.size)
        assertEquals("b_t5", afterEdit.single().buildingId)

        // 2) Cancel after edit replaces ov_mod_... cleanly so Loop 2 does NOT resurrect it!
        repo.cancelClassOccurrenceOnDate(
            occ = afterEdit.single(),
            termId = activeTerm.id,
            reason = "单次停课"
        )
        val afterCancel = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            date = monday,
            term = activeTerm,
            courses = listOf(course),
            rules = listOf(rule1),
            periodTimes = HautMockSeedData.defaultPeriodTimes,
            occurrenceOverrides = campusDao.getOccurrenceOverridesSnapshot().map { it.toDomain() },
            buildings = HautMockSeedData.lianhuajieBuildings
        )
        assertEquals(0, afterCancel.size)

        // 3) Merged 1-4节 occurrence cancels BOTH r_test_1_2 and r_test_3_4 in one action
        val nextMonday = java.time.LocalDate.of(2026, 9, 14)
        val mergedBeforeCancel = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            date = nextMonday,
            term = activeTerm,
            courses = listOf(course),
            rules = listOf(rule1, rule2),
            periodTimes = HautMockSeedData.defaultPeriodTimes
        )
        assertEquals(1, mergedBeforeCancel.size)
        assertEquals(listOf("r_test_1_2", "r_test_3_4"), mergedBeforeCancel.single().mergedRuleIds)

        repo.cancelClassOccurrenceOnDate(
            occ = mergedBeforeCancel.single(),
            termId = activeTerm.id,
            reason = "连堂停课"
        )
        val mergedAfterCancel = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            date = nextMonday,
            term = activeTerm,
            courses = listOf(course),
            rules = listOf(rule1, rule2),
            periodTimes = HautMockSeedData.defaultPeriodTimes,
            occurrenceOverrides = campusDao.getOccurrenceOverridesSnapshot().map { it.toDomain() }
        )
        assertEquals(0, mergedAfterCancel.size)
    }

    @Test
    fun accountSwitchIsolation_localToCloudHandoff_andInFlightStop() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dataStoreFile = java.io.File.createTempFile("booking_handoff_isolation_", ".preferences_pb")
        dataStoreFile.deleteOnExit()
        val testDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
            produceFile = { dataStoreFile }
        )
        val storage = isolatedAccountStoragePartition("prof_handoff_isolation_01")
        val repo = CampusRepository(
            appContext = context,
            database = database,
            preferences = testDataStore,
            accountProfile = AccountProfileEntity(
                id = "prof_handoff_isolation_01",
                accountId = "acct_handoff_01",
                schoolId = "haut",
                schoolName = "河南工业大学",
                province = "河南省",
                city = "郑州市",
                campusId = "lianhuajie",
                campusName = "莲花街校区",
                timeZoneId = "Asia/Shanghai",
                label = "交接与换号隔离测试",
                isLegacy = false,
                databaseName = storage.databaseName,
                preferencesName = storage.preferencesName,
                createdAtEpochMs = 1_000L
            )
        )
        repo.activateSession(1L)

        // Build non-expired synthetic JWTs for user_A and user_B so resolveValidCloudAccessToken accepts them offline
        fun fakeJwt(sub: String): String {
            val header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
            val payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("""{"sub":"$sub","exp":4102444800}""".toByteArray())
            return "ctseal1:$header.$payload.sig"
        }
        val jwtA = fakeJwt("cloud_user_A")
        val jwtB = fakeJwt("cloud_user_B")

        repo.saveVerifiedXpeachCloudAccount(
            cn.edu.haut.campus.core.campusservices.XpeachCloudAccountProfile(
                cloudUserId = "cloud_user_A",
                ssoIssuer = "https://auth.yellowpeachxgp.com",
                ssoSubject = "sub_A",
                displayName = "User A",
                peachBalanceMicropeach = 50_000L,
                holdSupported = false,
                accessToken = jwtA,
                refreshToken = "ctseal1:ref_A",
                linkedAtEpochMillis = 1_000L
            )
        )

        // --- Item #4: User A has CONFIRMED, RESULT_UNKNOWN, and EXECUTING plans; switching to User B MUST NOT delete User A's records or evidence! ---
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_userA_confirmed",
                schoolId = "haut",
                executionMode = "CLOUD_HOSTED",
                targetLocalDate = "2026-11-21",
                targetLocalTime = "07:00:00",
                timezoneId = "Asia/Shanghai",
                precheckAtEpochMillis = 1_000L,
                finalCheckAtEpochMillis = 2_000L,
                fireAtEpochMillis = 3_000L,
                precheckStatus = "PRECHECK_OK",
                finalCheckStatus = "FINAL_CHECK_OK",
                fireStatus = "CONFIRMED",
                preferredAreaName = "五层中区",
                preferredSeatLabel = "01桌 A座",
                allowAlternativeSeat = true,
                cloudJobId = "job_A_conf",
                externalBookingId = "bk_A_990011",
                evidenceSha256 = "sha_A_990011",
                priceMicropeach = 10_000L,
                paymentState = "PAID",
                status = "CONFIRMED",
                stateVersion = 2,
                generation = 1,
                lastDiagnosticMessage = "A已确认",
                createdAtEpochMillis = 500L,
                updatedAtEpochMillis = 1_000L,
                repeatDays = 1,
                remainingDays = 0,
                ownerCloudUserId = "cloud_user_A",
                ownerStudentId = "20240001"
            )
        )
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_userA_executing",
                schoolId = "haut",
                executionMode = "CLOUD_HOSTED",
                targetLocalDate = "2026-11-22",
                targetLocalTime = "07:00:00",
                timezoneId = "Asia/Shanghai",
                precheckAtEpochMillis = 1_000L,
                finalCheckAtEpochMillis = 2_000L,
                fireAtEpochMillis = 3_000L,
                precheckStatus = "PRECHECK_OK",
                finalCheckStatus = "FINAL_CHECK_OK",
                fireStatus = "DISPATCH_INTENT:att_A1",
                preferredAreaName = "五层中区",
                preferredSeatLabel = "02桌 B座",
                allowAlternativeSeat = true,
                cloudJobId = "job_A_exec",
                externalBookingId = null,
                evidenceSha256 = null,
                priceMicropeach = 10_000L,
                paymentState = "DEBIT_PENDING",
                status = "EXECUTING",
                stateVersion = 2,
                generation = 1,
                lastDiagnosticMessage = "A执行中",
                createdAtEpochMillis = 500L,
                updatedAtEpochMillis = 1_000L,
                repeatDays = 2,
                remainingDays = 2,
                ownerCloudUserId = "cloud_user_A",
                ownerStudentId = "20240001"
            )
        )

        // Switch directly from User A to User B!
        repo.saveVerifiedXpeachCloudAccount(
            cn.edu.haut.campus.core.campusservices.XpeachCloudAccountProfile(
                cloudUserId = "cloud_user_B",
                ssoIssuer = "https://auth.yellowpeachxgp.com",
                ssoSubject = "sub_B",
                displayName = "User B",
                peachBalanceMicropeach = 50_000L,
                holdSupported = false,
                accessToken = jwtB,
                refreshToken = "ctseal1:ref_B",
                linkedAtEpochMillis = 2_000L
            )
        )

        // User B's active UI flow does NOT see User A's plans:
        val visibleForB = repo.libraryBookingPlansFlow.first()
        org.junit.Assert.assertTrue(visibleForB.none { it.id.startsWith("plan_userA_") })

        // Audit snapshot retains 100% of User A's plans & evidence (EXECUTING transitioned to DELEGATION_STOPPED_RESULT_UNKNOWN, CONFIRMED untouched):
        val auditAll = repo.getAllLibraryBookingPlansForAuditSnapshot()
        val preservedAConf = auditAll.single { it.id == "plan_userA_confirmed" }
        assertEquals("CONFIRMED", preservedAConf.status)
        assertEquals("bk_A_990011", preservedAConf.externalBookingId)
        assertEquals("sha_A_990011", preservedAConf.evidenceSha256)
        assertEquals("cloud_user_A", preservedAConf.ownerCloudUserId)

        val preservedAExec = auditAll.single { it.id == "plan_userA_executing" }
        assertEquals("DELEGATION_STOPPED_RESULT_UNKNOWN", preservedAExec.status)
        assertEquals("cloud_user_A", preservedAExec.ownerCloudUserId)

        // Restart recovery (rescheduleActiveLocalBookingPlans) preserves both records intact:
        repo.rescheduleActiveLocalBookingPlans()
        assertEquals("bk_A_990011", dao.getLibraryBookingPlanById("plan_userA_confirmed")?.externalBookingId)
        assertEquals("DELEGATION_STOPPED_RESULT_UNKNOWN", dao.getLibraryBookingPlanById("plan_userA_executing")?.status)

        // --- Item #5: Local -> Cloud handoff order, failure closure & compensation ---
        val nowCreateMs = java.time.ZonedDateTime.of(2026, 11, 24, 21, 0, 0, 0, java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val targetDate25 = java.time.LocalDate.of(2026, 11, 25)

        // 5a: If local plan on 2026-11-25 is ALREADY EXECUTING / DISPATCH_INTENT, switching to Cloud MUST abort BEFORE making ANY cloud HTTP request!
        var cloudHttpCalls = 0
        repo.cloudHttpTransportForTest = { _, _, _, _, _ ->
            cloudHttpCalls += 1
            throw IllegalStateException("Should not call cloud when local is already dispatched")
        }
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_local_inflight_25",
                schoolId = "haut",
                executionMode = "LOCAL_DEVICE",
                targetLocalDate = "2026-11-25",
                targetLocalTime = "07:00:00",
                timezoneId = "Asia/Shanghai",
                precheckAtEpochMillis = nowCreateMs + 10_000L,
                finalCheckAtEpochMillis = nowCreateMs + 20_000L,
                fireAtEpochMillis = nowCreateMs + 30_000L,
                precheckStatus = "PRECHECK_OK",
                finalCheckStatus = "FINAL_CHECK_OK",
                fireStatus = "DISPATCH_INTENT:att_local_25",
                preferredAreaName = "五层中区",
                preferredSeatLabel = "03桌 A座",
                allowAlternativeSeat = true,
                cloudJobId = null,
                externalBookingId = null,
                evidenceSha256 = null,
                priceMicropeach = 0L,
                paymentState = "NOT_REQUIRED",
                status = "EXECUTING",
                stateVersion = 2,
                generation = 1,
                lastDiagnosticMessage = "本机派发中",
                createdAtEpochMillis = nowCreateMs,
                updatedAtEpochMillis = nowCreateMs
            )
        )
        val blockedByLocalInFlight = repo.createDualEngineBookingPlan(
            executionMode = cn.edu.haut.campus.core.campusservices.BookingExecutionMode.CLOUD_HOSTED,
            targetDate = targetDate25,
            targetTimeText = "07:00:00",
            preferredAreaName = "五层中区",
            preferredSeatLabel = "03桌 A座",
            nowEpochMillis = nowCreateMs
        )
        org.junit.Assert.assertTrue(blockedByLocalInFlight.isFailure)
        assertEquals(0, cloudHttpCalls)

        // 5b: Queued local plan on 2026-11-26 -> Cloud POST /reservation-plans succeeds on server BUT response drops (timeout), and follow-up snapshot also fails ->
        //     MUST keep local plan CANCELLED and record Cloud RESULT_UNKNOWN (NEVER leaving both local and cloud active!)
        val targetDate26 = java.time.LocalDate.of(2026, 11, 26)
        val localQueued26 = repo.createDualEngineBookingPlan(
            executionMode = cn.edu.haut.campus.core.campusservices.BookingExecutionMode.LOCAL_DEVICE,
            targetDate = targetDate26,
            targetTimeText = "07:00:00",
            preferredAreaName = "五层中区",
            preferredSeatLabel = "06桌 A座",
            nowEpochMillis = nowCreateMs
        ).getOrThrow()
        assertEquals("ARMED", localQueued26.status)

        var localStatusObservedDuringCloudPost = ""
        repo.cloudHttpTransportForTest = { method, url, _, _, _ ->
            when {
                method == "POST" && url.endsWith("/api/v1/me/consents") ->
                    """{"ok":true,"data":{"id":"con_B_01","auth_version":1}}"""
                method == "POST" && url.endsWith("/api/v1/school-bindings") ->
                    """{"ok":true,"data":{"id":"bind_B_01"}}"""
                method == "POST" && url.endsWith("/complete") ->
                    """{"ok":true,"data":{"status":"DELEGATED_ACTIVE"}}"""
                method == "POST" && url.endsWith("/api/v1/reservation-plans") -> {
                    // Verify local plan was ALREADY stopped BEFORE Cloud POST /reservation-plans was invoked!
                    localStatusObservedDuringCloudPost =
                        kotlinx.coroutines.runBlocking { dao.getLibraryBookingPlanById(localQueued26.id)?.status.orEmpty() }
                    throw java.net.SocketTimeoutException("Read timed out after server accepted plan")
                }
                else -> throw java.net.SocketTimeoutException("Still offline during reconciliation")
            }
        }
        val lostRespSwitch = repo.createDualEngineBookingPlan(
            executionMode = cn.edu.haut.campus.core.campusservices.BookingExecutionMode.CLOUD_HOSTED,
            targetDate = targetDate26,
            targetTimeText = "07:00:00",
            preferredAreaName = "五层中区",
            preferredSeatLabel = "06桌 A座",
            nowEpochMillis = nowCreateMs + 1_000L
        )
        org.junit.Assert.assertTrue(lostRespSwitch.isFailure)
        assertEquals("CANCELLED", localStatusObservedDuringCloudPost)
        assertEquals("CANCELLED", dao.getLibraryBookingPlanById(localQueued26.id)?.status)
        val plansOn26 = repo.libraryBookingPlansFlow.first().filter { it.targetLocalDate == "2026-11-26" }
        val unknownCloud26 = plansOn26.single { it.executionMode == "CLOUD_HOSTED" }
        assertEquals("RESULT_UNKNOWN", unknownCloud26.status)

        // 5c: Queued local plan on 2026-11-27 -> Cloud explicitly rejects with 400 BAD_REQUEST before persisting -> safely compensates & restores local ARMED plan!
        val targetDate27 = java.time.LocalDate.of(2026, 11, 27)
        val localQueued27 = repo.createDualEngineBookingPlan(
            executionMode = cn.edu.haut.campus.core.campusservices.BookingExecutionMode.LOCAL_DEVICE,
            targetDate = targetDate27,
            targetTimeText = "07:00:00",
            preferredAreaName = "五层中区",
            preferredSeatLabel = "08桌 B座",
            nowEpochMillis = nowCreateMs
        ).getOrThrow()
        repo.cloudHttpTransportForTest = { method, url, _, _, _ ->
            when {
                method == "POST" && url.endsWith("/api/v1/me/consents") ->
                    """{"ok":true,"data":{"id":"con_B_01","auth_version":1}}"""
                method == "POST" && url.endsWith("/api/v1/school-bindings") ->
                    """{"ok":true,"data":{"id":"bind_B_01"}}"""
                method == "POST" && url.endsWith("/complete") ->
                    """{"ok":true,"data":{"status":"DELEGATED_ACTIVE"}}"""
                method == "POST" && url.endsWith("/api/v1/reservation-plans") ->
                    """{"ok":false,"error":{"code":"BAD_REQUEST","message":"invalid slot"}}"""
                else -> """{"ok":true,"data":{}}"""
            }
        }
        val explicitRejectSwitch = repo.createDualEngineBookingPlan(
            executionMode = cn.edu.haut.campus.core.campusservices.BookingExecutionMode.CLOUD_HOSTED,
            targetDate = targetDate27,
            targetTimeText = "07:00:00",
            preferredAreaName = "五层中区",
            preferredSeatLabel = "08桌 B座",
            nowEpochMillis = nowCreateMs + 2_000L
        )
        org.junit.Assert.assertTrue(explicitRejectSwitch.isFailure)
        assertEquals("ARMED", dao.getLibraryBookingPlanById(localQueued27.id)?.status)

        // --- Item #6: Stopping an EXECUTING plan stops future retries/rollover (DELEGATION_STOPPED_RESULT_UNKNOWN) and NEVER fakes SCHOOL_CANCELLED ---
        val stoppedExec = repo.stopInFlightOrQueuedDelegation("plan_local_inflight_25")
        assertEquals("DELEGATION_STOPPED_RESULT_UNKNOWN", stoppedExec?.status)
        assertEquals(0, stoppedExec?.remainingDays)
        repo.cloudHttpTransportForTest = null
    }
}
