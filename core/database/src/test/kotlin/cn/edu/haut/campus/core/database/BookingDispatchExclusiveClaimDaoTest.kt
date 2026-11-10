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

    @Test
    fun mergedCourseEdit_coversAllMergedRuleIds_andUpgradedPeriodTimesBackfill11To12() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dataStoreFile = java.io.File.createTempFile("merged_edit_periods_", ".preferences_pb")
        dataStoreFile.deleteOnExit()
        val testDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
            produceFile = { dataStoreFile }
        )
        val storage = isolatedAccountStoragePartition("prof_merged_periods_01")
        val repo = CampusRepository(
            appContext = context,
            database = database,
            preferences = testDataStore,
            accountProfile = AccountProfileEntity(
                id = "prof_merged_periods_01",
                accountId = "acct_merged_01",
                schoolId = "haut",
                schoolName = "河南工业大学",
                province = "河南省",
                city = "郑州市",
                campusId = "lianhuajie",
                campusName = "莲花街校区",
                timeZoneId = "Asia/Shanghai",
                label = "连堂编辑与11-12节补齐测试",
                isLegacy = false,
                databaseName = storage.databaseName,
                preferencesName = storage.preferencesName,
                createdAtEpochMs = 1_000L
            )
        )
        repo.activateSession(1L)

        val campusDao = database.campusDao()
        // Simulate upgraded user whose DB only had periods 1..10 (with a customized p1 start time 08:25 that must NOT be overwritten!)
        val legacyTenPeriods = HautMockSeedData.defaultPeriodTimes
            .filter { it.periodNo <= 10 }
            .map { if (it.periodNo == 1) it.copy(startLocalTime = java.time.LocalTime.of(8, 25)) else it }
        campusDao.upsertPeriodTimes(legacyTenPeriods.map { it.toEntity() })
        campusDao.upsertBuildings(HautMockSeedData.lianhuajieBuildings.map { it.toEntity() })
        campusDao.upsertBuildingAliases(HautMockSeedData.defaultBuildingAliases.map { it.toEntity() })
        val term = cn.edu.haut.campus.core.model.Term(
            id = "2026-2027-1",
            academicYear = "2026-2027",
            termNumber = 1,
            startDate = java.time.LocalDate.of(2026, 8, 31),
            weekCount = 20,
            timeTableId = HautMockSeedData.DEFAULT_TIMETABLE_ID,
            current = true
        )
        campusDao.upsertTerms(listOf(term.toEntity()))

        // Item #10: Backfill periods 11 & 12 without overwriting customized period 1!
        repo.ensureExtendedPeriodTimes11To12IfNeeded()
        val upgradedPeriods = campusDao.observePeriodTimes().first().map { it.toDomain() }.associateBy { it.periodNo }
        assertEquals(12, upgradedPeriods.size)
        assertEquals(java.time.LocalTime.of(8, 25), upgradedPeriods[1]?.startLocalTime)
        assertEquals(java.time.LocalTime.of(21, 15), upgradedPeriods[11]?.startLocalTime)
        assertEquals(java.time.LocalTime.of(22, 50), upgradedPeriods[12]?.endLocalTime)

        // Item #9: Merged 1-4节 course (r_m1: 1-2节 + r_m2: 3-4节)
        val course = cn.edu.haut.campus.core.model.Course(
            id = "c_merged_lab",
            name = "计算机组成实验",
            teacher = "刘老师",
            colorArgb = 0xFF053968,
            source = cn.edu.haut.campus.core.model.CourseSource.OFFICIAL_JW
        )
        val r1 = cn.edu.haut.campus.core.model.CourseScheduleRule(
            id = "r_m1",
            courseId = course.id,
            termId = term.id,
            weekday = java.time.DayOfWeek.TUESDAY,
            startPeriod = 1,
            endPeriod = 2,
            weeks = (1..16).toSet(),
            rawRoom = "4号教学楼4102",
            buildingId = "b_t4"
        )
        val r2 = r1.copy(id = "r_m2", startPeriod = 3, endPeriod = 4)
        campusDao.upsertCourse(course.toEntity())
        campusDao.upsertRules(listOf(r1.toEntity(), r2.toEntity()))

        val tuesdayW2 = java.time.LocalDate.of(2026, 9, 8)
        val tuesdayW3 = java.time.LocalDate.of(2026, 9, 15)
        val allPeriods = upgradedPeriods.values.toList()

        // 9a: Single-day edit of merged 1-4节 on W2 to 5-8节 @ 5号教学楼5201 -> MUST NOT leave r_m2 (3-4节) overlapping on W2, while W3 stays 1-4节!
        val initialW2 = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            date = tuesdayW2,
            term = term,
            courses = listOf(course),
            rules = campusDao.getRulesSnapshot().map { it.toDomain() },
            periodTimes = allPeriods
        ).single()
        assertEquals(listOf("r_m1", "r_m2"), initialW2.mergedRuleIds)

        repo.saveSingleDayOccurrenceEdit(
            occ = initialW2,
            termId = term.id,
            newStartPeriod = 5,
            newEndPeriod = 8,
            newRoom = "5号教学楼5201"
        )
        val editedW2List = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            date = tuesdayW2,
            term = term,
            courses = listOf(course),
            rules = campusDao.getRulesSnapshot().map { it.toDomain() },
            periodTimes = allPeriods,
            occurrenceOverrides = campusDao.getOccurrenceOverridesSnapshot().map { it.toDomain() }
        )
        assertEquals("Must have ONLY 5-8节 on W2 with zero residual 3-4节 overlap", 1, editedW2List.size)
        assertEquals(5, editedW2List.single().startPeriod)
        assertEquals(8, editedW2List.single().endPeriod)
        assertEquals("5号教学楼5201", editedW2List.single().rawRoom)

        // Internal MERGED_INTO suppression must NOT pollute resolveCanceledOccurrencesForDate while editedW2 is active!
        val fakeCanceledDuringActiveEdit = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveCanceledOccurrencesForDate(
            date = tuesdayW2,
            term = term,
            courses = listOf(course),
            rules = campusDao.getRulesSnapshot().map { it.toDomain() },
            periodTimes = allPeriods,
            occurrenceOverrides = campusDao.getOccurrenceOverridesSnapshot().map { it.toDomain() }
        )
        assertEquals(0, fakeCanceledDuringActiveEdit.size)

        // 9b: Canceling the single-day edited 5-8节 occurrence cancels BOTH r_m1 and r_m2, and restoring brings back full 1-4节!
        repo.cancelClassOccurrenceOnDate(editedW2List.single(), term.id, "临时停课")
        val afterCancelW2 = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            date = tuesdayW2,
            term = term,
            courses = listOf(course),
            rules = campusDao.getRulesSnapshot().map { it.toDomain() },
            periodTimes = allPeriods,
            occurrenceOverrides = campusDao.getOccurrenceOverridesSnapshot().map { it.toDomain() }
        )
        assertEquals(0, afterCancelW2.size)
        val canceledListW2 = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveCanceledOccurrencesForDate(
            date = tuesdayW2,
            term = term,
            courses = listOf(course),
            rules = campusDao.getRulesSnapshot().map { it.toDomain() },
            periodTimes = allPeriods,
            occurrenceOverrides = campusDao.getOccurrenceOverridesSnapshot().map { it.toDomain() }
        )
        assertEquals(1, canceledListW2.size)
        assertEquals(listOf("r_m1", "r_m2"), canceledListW2.single().mergedRuleIds)

        repo.restoreCanceledOccurrence(
            date = tuesdayW2,
            ruleId = canceledListW2.single().ruleId,
            overrideId = canceledListW2.single().overrideId,
            mergedRuleIds = canceledListW2.single().mergedRuleIds
        )
        val restoredW2 = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            date = tuesdayW2,
            term = term,
            courses = listOf(course),
            rules = campusDao.getRulesSnapshot().map { it.toDomain() },
            periodTimes = allPeriods,
            occurrenceOverrides = campusDao.getOccurrenceOverridesSnapshot().map { it.toDomain() }
        )
        assertEquals(1, restoredW2.size)
        assertEquals(1, restoredW2.single().startPeriod)
        assertEquals(4, restoredW2.single().endPeriod)

        // 9c: Full-semester edit of merged 1-4节 to 11-12节 @ 3号教学楼3201 removes residual r_m2 and schedules cleanly at 21:15–22:50!
        repo.saveUserMergedCourseRuleEdit(
            course = course,
            primaryRule = r1.copy(startPeriod = 11, endPeriod = 12, rawRoom = "3号教学楼3201"),
            mergedRuleIds = restoredW2.single().mergedRuleIds
        )
        val remainingRules = campusDao.getRulesSnapshot().map { it.toDomain() }
        assertEquals(1, remainingRules.size)
        val afterFullEditW3 = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            date = tuesdayW3,
            term = term,
            courses = listOf(course),
            rules = remainingRules,
            periodTimes = allPeriods
        )
        assertEquals(1, afterFullEditW3.size)
        assertEquals(11, afterFullEditW3.single().startPeriod)
        assertEquals(12, afterFullEditW3.single().endPeriod)
        assertEquals(java.time.LocalTime.of(21, 15), afterFullEditW3.single().startTime)
        assertEquals(java.time.LocalTime.of(22, 50), afterFullEditW3.single().endTime)
    }

    private fun buildInMemoryTestRepo(profileSeed: String): CampusRepository {
        val context = RuntimeEnvironment.getApplication()
        val dataStoreFile = java.io.File.createTempFile("booking_test_${profileSeed}_", ".preferences_pb")
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

    @Test
    fun syncAndReconfirmIsolateAccountsAndNullOwners() = runBlocking {
        java.io.File(RuntimeEnvironment.getApplication().filesDir, "cloud_auth_provenance").deleteRecursively()
        val repo = buildInMemoryTestRepo("sync_reconfirm_iso")
        val jwtA = testCloudJwt("cloud_user_A")
        val jwtB = testCloudJwt("cloud_user_B")

        // Seed unattributed null-owner legacy row + Account A rows before switching to Account B
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_null_owner_legacy",
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
                fireStatus = "CONFIRMED",
                preferredAreaName = "五层中区",
                preferredSeatLabel = "09桌 A座",
                allowAlternativeSeat = true,
                cloudJobId = "job_legacy_null_01",
                externalBookingId = "bk_legacy_null_01",
                evidenceSha256 = "sha_legacy_null_01",
                priceMicropeach = 10_000L,
                paymentState = "PAID",
                status = "CONFIRMED",
                stateVersion = 1,
                generation = 1,
                lastDiagnosticMessage = "旧版无归属记录",
                createdAtEpochMillis = 500L,
                updatedAtEpochMillis = 500L,
                ownerCloudUserId = null,
                ownerStudentId = null
            )
        )
        // Logged-out view sees preserved confirmed evidence locally, and unattributed flow captures null-owner row
        org.junit.Assert.assertTrue(
            repo.libraryBookingPlansFlow.first().any { it.id == "plan_null_owner_legacy" }
        )
        org.junit.Assert.assertTrue(
            repo.unattributedLegacyBookingPlansFlow.first().any { it.id == "plan_null_owner_legacy" }
        )

        // Login Account A (20240001) and seed A's plans (CONFIRMED, NEEDS_RECONFIRMATION, CANCELLED)
        repo.saveAuthorizedSyncCredentials("20240001", "pw_A", "2026", "3")
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
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_A_confirmed",
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
                fireStatus = "CONFIRMED",
                preferredAreaName = "五层中区",
                preferredSeatLabel = "01桌 A座",
                allowAlternativeSeat = true,
                cloudJobId = "job_A_conf_1",
                externalBookingId = "bk_A_conf_1",
                evidenceSha256 = "sha_A_conf_1",
                priceMicropeach = 10_000L,
                paymentState = "PAID",
                status = "CONFIRMED",
                stateVersion = 2,
                generation = 1,
                lastDiagnosticMessage = "A已确认",
                createdAtEpochMillis = 1_000L,
                updatedAtEpochMillis = 1_000L,
                ownerCloudUserId = "cloud_user_A",
                ownerStudentId = "20240001"
            )
        )
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_A_reconfirm",
                schoolId = "haut",
                executionMode = "CLOUD_HOSTED",
                targetLocalDate = "2026-11-27",
                targetLocalTime = "07:00:00",
                timezoneId = "Asia/Shanghai",
                precheckAtEpochMillis = 1_000L,
                finalCheckAtEpochMillis = 2_000L,
                fireAtEpochMillis = 3_000L,
                precheckStatus = "ARMED",
                finalCheckStatus = "ARMED",
                fireStatus = "ARMED",
                preferredAreaName = "五层中区",
                preferredSeatLabel = "02桌 A座",
                allowAlternativeSeat = true,
                cloudJobId = "job_A_reconf_1",
                externalBookingId = null,
                evidenceSha256 = null,
                priceMicropeach = 10_000L,
                paymentState = "DEBIT_PENDING",
                status = "NEEDS_RECONFIRMATION",
                stateVersion = 3,
                generation = 1,
                lastDiagnosticMessage = "A待重新确认",
                createdAtEpochMillis = 1_000L,
                updatedAtEpochMillis = 1_000L,
                ownerCloudUserId = "cloud_user_A",
                ownerStudentId = "20240001"
            )
        )
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_A_cancelled",
                schoolId = "haut",
                executionMode = "CLOUD_HOSTED",
                targetLocalDate = "2026-11-28",
                targetLocalTime = "07:00:00",
                timezoneId = "Asia/Shanghai",
                precheckAtEpochMillis = 1_000L,
                finalCheckAtEpochMillis = 2_000L,
                fireAtEpochMillis = 3_000L,
                precheckStatus = "ARMED",
                finalCheckStatus = "ARMED",
                fireStatus = "CANCELLED",
                preferredAreaName = "五层中区",
                preferredSeatLabel = "03桌 A座",
                allowAlternativeSeat = true,
                cloudJobId = "job_A_cancelled_1",
                externalBookingId = null,
                evidenceSha256 = null,
                priceMicropeach = 10_000L,
                paymentState = "DEBIT_PENDING",
                status = "CANCELLED",
                stateVersion = 2,
                generation = 2,
                lastDiagnosticMessage = "A已取消",
                createdAtEpochMillis = 1_000L,
                updatedAtEpochMillis = 1_000L,
                ownerCloudUserId = "cloud_user_A",
                ownerStudentId = "20240001"
            )
        )

        // Switch A -> B (cloud_user_B + student 20240002)
        repo.saveAuthorizedSyncCredentials("20240002", "pw_B", "2026", "3")
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
        // Null-owner legacy row & Account A's rows MUST be invisible to ordinary Account B UI!
        val visibleForBInitial = repo.libraryBookingPlansFlow.first()
        org.junit.Assert.assertTrue(visibleForBInitial.none { it.id == "plan_null_owner_legacy" })
        org.junit.Assert.assertTrue(visibleForBInitial.none { it.id.startsWith("plan_A_") })

        // Also verify an unsynced NEEDS_RECONFIRMATION row owned by Account A in DB is NOT touched or armed when B reconfirms!
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_A_unsynced_reconfirm",
                schoolId = "haut",
                executionMode = "CLOUD_HOSTED",
                targetLocalDate = "2026-11-29",
                targetLocalTime = "07:00:00",
                timezoneId = "Asia/Shanghai",
                precheckAtEpochMillis = 1_000L,
                finalCheckAtEpochMillis = 2_000L,
                fireAtEpochMillis = 3_000L,
                precheckStatus = "ARMED",
                finalCheckStatus = "ARMED",
                fireStatus = "ARMED",
                preferredAreaName = "五层中区",
                preferredSeatLabel = "04桌 A座",
                allowAlternativeSeat = true,
                cloudJobId = null,
                externalBookingId = null,
                evidenceSha256 = null,
                priceMicropeach = 10_000L,
                paymentState = "DEBIT_PENDING",
                status = "NEEDS_RECONFIRMATION",
                stateVersion = 1,
                generation = 1,
                lastDiagnosticMessage = "A本地待重新确认",
                createdAtEpochMillis = 1_000L,
                updatedAtEpochMillis = 1_000L,
                ownerCloudUserId = "cloud_user_A",
                ownerStudentId = "20240001"
            )
        )

        // Add Account B's own NEEDS_RECONFIRMATION plan
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_B_reconfirm",
                schoolId = "haut",
                executionMode = "CLOUD_HOSTED",
                targetLocalDate = "2026-11-26",
                targetLocalTime = "07:00:00",
                timezoneId = "Asia/Shanghai",
                precheckAtEpochMillis = 1_000L,
                finalCheckAtEpochMillis = 2_000L,
                fireAtEpochMillis = 3_000L,
                precheckStatus = "ARMED",
                finalCheckStatus = "ARMED",
                fireStatus = "ARMED",
                preferredAreaName = "五层中区",
                preferredSeatLabel = "08桌 B座",
                allowAlternativeSeat = true,
                cloudJobId = null,
                externalBookingId = null,
                evidenceSha256 = null,
                priceMicropeach = 10_000L,
                paymentState = "DEBIT_PENDING",
                status = "NEEDS_RECONFIRMATION",
                stateVersion = 1,
                generation = 1,
                lastDiagnosticMessage = "B待确认",
                createdAtEpochMillis = 2_000L,
                updatedAtEpochMillis = 2_000L,
                ownerCloudUserId = "cloud_user_B",
                ownerStudentId = "20240002"
            )
        )

        var lastSyncPostBody = ""
        repo.cloudHttpTransportForTest = { method, url, body, _, _ ->
            when {
                method == "POST" && url.endsWith("/api/v1/me/consents") ->
                    """{"ok":true,"data":{"id":"con_B_v2","auth_version":2}}"""
                method == "POST" && url.endsWith("/api/v1/school-bindings") ->
                    """{"ok":true,"data":{"id":"bind_B_v2"}}"""
                method == "POST" && url.endsWith("/complete") ->
                    """{"ok":true,"data":{"status":"DELEGATED_ACTIVE"}}"""
                method == "GET" && url.endsWith("/api/v1/sync/snapshot") ->
                    """{"ok":true,"data":{"auth_version":2,"user":{"cloud_user_id":"cloud_user_B"},"consents":[{"id":"con_B_v2","school_id":"haut","auth_version":2}],"school_bindings":[{"id":"bind_B_v2","school_id":"haut","student_no":"20240002","consent_id":"con_B_v2","auth_version":2}]}}"""
                method == "POST" && url.endsWith("/api/v1/sync/snapshot") -> {
                    lastSyncPostBody = body.orEmpty()
                    """
                    {
                      "ok": true,
                      "data": {
                        "auth_version": 2,
                        "user": { "cloud_user_id": "cloud_user_B", "display_name": "User B" },
                        "wallet": { "peach_balance": 5.0 },
                        "consents": [{ "id": "con_B_v2", "school_id": "haut", "auth_version": 2 }],
                        "school_bindings": [{ "id": "bind_B_v2", "school_id": "haut", "student_no": "20240002", "consent_id": "con_B_v2", "auth_version": 2 }],
                        "reservation_plans": [],
                        "reservation_jobs": [
                          { "id": "job_B_remote_26", "plan_id": "plan_B_reconfirm", "booking_local_date": "2026-11-26", "target_local_time": "07:00:00", "state": "ARMED", "display_status": "ARMED", "user_intent_state": "ACTIVE", "external_execution_state": "NOT_DISPATCHED", "school_cancellation_state": "NOT_REQUESTED", "candidate_seats": ["08桌 B座"] }
                        ],
                        "orders": [],
                        "parcels": []
                      }
                    }
                    """.trimIndent()
                }
                else -> """{"ok":true,"data":{}}"""
            }
        }

        // Reconfirm ONLY B -> must NOT send A's plans/cancellations, must NOT arm A's NEEDS_RECONFIRMATION row, and must NOT merge into plan_null_owner_legacy!
        val reconfRes = repo.reconfirmAndRestoreCloudBookingPlans()
        org.junit.Assert.assertTrue("B reconfirm should succeed: ${reconfRes.exceptionOrNull()}", reconfRes.isSuccess)
        org.junit.Assert.assertFalse("Must NOT send A's cancelled job", lastSyncPostBody.contains("job_A_cancelled_1"))
        org.junit.Assert.assertFalse("Must NOT send A's plan", lastSyncPostBody.contains("plan_A_reconfirm"))
        org.junit.Assert.assertFalse("Must NOT send A's unsynced reconfirm plan", lastSyncPostBody.contains("plan_A_unsynced_reconfirm"))
        org.junit.Assert.assertTrue("Must send B's reconfirmed plan", lastSyncPostBody.contains("plan_B_reconfirm"))
        assertEquals("ARMED", dao.getLibraryBookingPlanById("plan_B_reconfirm")?.status)
        assertEquals("DELEGATION_STOPPED_RESULT_UNKNOWN", dao.getLibraryBookingPlanById("plan_A_reconfirm")?.status)
        assertEquals("NEEDS_RECONFIRMATION", dao.getLibraryBookingPlanById("plan_A_unsynced_reconfirm")?.status)
        assertEquals("cloud_user_A", dao.getLibraryBookingPlanById("plan_A_unsynced_reconfirm")?.ownerCloudUserId)
        assertEquals(null, dao.getLibraryBookingPlanById("plan_null_owner_legacy")?.ownerCloudUserId)
        assertEquals("bk_legacy_null_01", dao.getLibraryBookingPlanById("plan_null_owner_legacy")?.externalBookingId)

        // Mid-sync account switch (B -> A during POST /api/v1/sync/snapshot): must abort and NOT write B's snapshot onto A!
        repo.cloudHttpTransportForTest = { method, url, _, _, _ ->
            if (method == "POST" && url.endsWith("/api/v1/sync/snapshot")) {
                kotlinx.coroutines.runBlocking {
                    repo.saveAuthorizedSyncCredentials("20240001", "pw_A", "2026", "3")
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
                            linkedAtEpochMillis = 3_000L
                        )
                    )
                }
                """{"ok":true,"data":{"auth_version":2,"user":{"cloud_user_id":"cloud_user_B"},"wallet":{"peach_balance":99.0},"reservation_jobs":[{"id":"job_B_late","plan_id":"p_B_late","booking_local_date":"2026-11-30","state":"ARMED"}]}}"""
            } else {
                """{"ok":true,"data":{"auth_version":2,"user":{"cloud_user_id":"cloud_user_B"}}}"""
            }
        }
        val midSwitchSync = repo.syncWithCampusCloud()
        org.junit.Assert.assertTrue("Mid-sync switch must reject stale write", midSwitchSync.isFailure)
        org.junit.Assert.assertTrue(
            dao.getLibraryBookingPlansSnapshot().none { it.cloudJobId == "job_B_late" }
        )

        // Verify A -> B -> A: Account A sees its own preserved CONFIRMED & NEEDS_RECONFIRMATION rows intact and never sees B's rows!
        val visibleBackOnA = repo.libraryBookingPlansFlow.first()
        org.junit.Assert.assertTrue(visibleBackOnA.any { it.id == "plan_A_confirmed" && it.externalBookingId == "bk_A_conf_1" })
        org.junit.Assert.assertTrue(visibleBackOnA.any { it.id == "plan_A_unsynced_reconfirm" && it.status == "NEEDS_RECONFIRMATION" })
        org.junit.Assert.assertTrue(visibleBackOnA.none { it.id == "plan_B_reconfirm" })
    }

    @Test
    fun stopDelegationRaceWithFireNeverCallsLiveSeatCancel() = runBlocking {
        val repo = buildInMemoryTestRepo("stop_race_zero_cancel")
        var liveSeatCancelCount = 0
        repo.liveSeatCancelObserverForTest = { _, _ ->
            liveSeatCancelCount += 1
        }

        // UI saw plan in EXECUTING and user clicked "停止委托", but right before click handler runs, FIRE succeeded -> CONFIRMED!
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_race_stop_conf",
                schoolId = "haut",
                executionMode = "LOCAL_ASSISTED",
                targetLocalDate = "2026-11-26",
                targetLocalTime = "07:00:00",
                timezoneId = "Asia/Shanghai",
                precheckAtEpochMillis = 1_000L,
                finalCheckAtEpochMillis = 2_000L,
                fireAtEpochMillis = 3_000L,
                precheckStatus = "PRECHECK_OK",
                finalCheckStatus = "FINAL_CHECK_OK",
                fireStatus = "CONFIRMED",
                preferredAreaName = "五层中区",
                preferredSeatLabel = "05桌 A座",
                allowAlternativeSeat = true,
                cloudJobId = null,
                externalBookingId = "bk_race_991122",
                evidenceSha256 = "sha_race_991122",
                priceMicropeach = 0L,
                paymentState = "NOT_REQUIRED",
                status = "CONFIRMED",
                stateVersion = 3,
                generation = 1,
                lastDiagnosticMessage = "刚刚抢座成功",
                createdAtEpochMillis = 1_000L,
                updatedAtEpochMillis = 2_500L,
                repeatDays = 3,
                remainingDays = 2,
                ownerStudentId = "20240001"
            )
        )

        val stopped = repo.stopInFlightOrQueuedDelegation("plan_race_stop_conf")
        assertEquals("Zero live seat cancel calls allowed on stop-delegation!", 0, liveSeatCancelCount)
        assertEquals("DELEGATION_STOPPED_SCHOOL_CONFIRMED", stopped?.status)
        assertEquals(0, stopped?.remainingDays)
        assertEquals("bk_race_991122", stopped?.externalBookingId)

        // Even calling legacy cancelDualEngineBookingPlan MUST NEVER call cancelActiveLibrarySeatBooking!
        repo.cancelDualEngineBookingPlan("plan_race_stop_conf")
        assertEquals(0, liveSeatCancelCount)
        assertEquals("DELEGATION_STOPPED_SCHOOL_CONFIRMED", dao.getLibraryBookingPlanById("plan_race_stop_conf")?.status)
    }

    @Test
    fun engineSwitchCoordinatesStaleFailedChildWithActiveParent() = runBlocking {
        val repo = buildInMemoryTestRepo("switch_stale_failed_parent")
        val jwtA = testCloudJwt("cloud_user_A")
        repo.saveAuthorizedSyncCredentials("20240001", "pw_A", "2026", "3")
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

        // Day-1 child job (2026-11-25) is FAILED locally (remainingDays=0), while parent plan repeatDays=3 covers 2026-11-26 and has rolled-over child job on Cloud!
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_multi_day1_failed",
                schoolId = "haut",
                executionMode = "CLOUD_HOSTED",
                targetLocalDate = "2026-11-25",
                targetLocalTime = "07:00:00",
                timezoneId = "Asia/Shanghai",
                precheckAtEpochMillis = 1_000L,
                finalCheckAtEpochMillis = 2_000L,
                fireAtEpochMillis = 3_000L,
                precheckStatus = "PRECHECK_OK",
                finalCheckStatus = "FINAL_CHECK_OK",
                fireStatus = "FAILED",
                preferredAreaName = "五层中区",
                preferredSeatLabel = "05桌 A座",
                allowAlternativeSeat = true,
                cloudJobId = "job_day1_failed",
                externalBookingId = null,
                evidenceSha256 = null,
                priceMicropeach = 10_000L,
                paymentState = "DEBIT_PENDING",
                status = "FAILED",
                stateVersion = 2,
                generation = 1,
                lastDiagnosticMessage = "首日未抢到",
                createdAtEpochMillis = 1_000L,
                updatedAtEpochMillis = 2_000L,
                repeatDays = 3,
                remainingDays = 0,
                ownerCloudUserId = "cloud_user_A",
                ownerStudentId = "20240001"
            )
        )

        // 5a: Cloud parent plan has rolled-over Day-2 child job in EXECUTING/RESULT_UNKNOWN -> MUST NOT skip coordination and MUST block local plan creation!
        var coordinationInvoked = false
        repo.cloudHttpTransportForTest = { method, url, _, _, _ ->
            coordinationInvoked = true
            when {
                method == "GET" && url.endsWith("/api/v1/sync/snapshot") ->
                    """
                    {
                      "ok": true,
                      "data": {
                        "reservation_plans": [
                          { "id": "plan_cloud_parent_1", "enabled": true, "user_intent_state": "ACTIVE", "target_local_date": "2026-11-26" }
                        ],
                        "reservation_jobs": [
                          { "id": "job_day1_failed", "plan_id": "plan_cloud_parent_1", "booking_local_date": "2026-11-25", "state": "FAILED", "external_execution_state": "SCHOOL_REJECTED" },
                          { "id": "job_day2_inflight", "plan_id": "plan_cloud_parent_1", "booking_local_date": "2026-11-26", "state": "RESULT_UNKNOWN", "external_execution_state": "RESULT_UNKNOWN" }
                        ]
                      }
                    }
                    """.trimIndent()
                method == "DELETE" && url.endsWith("/reservation-plans/plan_cloud_parent_1") ->
                    """{"ok":true,"data":{"plan_id":"plan_cloud_parent_1","enabled":false}}"""
                else -> """{"ok":false,"error":{"code":"JOB_ALREADY_TERMINAL","message":"terminal"}}"""
            }
        }

        val nowMs = java.time.ZonedDateTime.of(2026, 11, 25, 22, 0, 0, 0, java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val blockedCreate = repo.createDualEngineBookingPlan(
            executionMode = cn.edu.haut.campus.core.campusservices.BookingExecutionMode.LOCAL_DEVICE,
            targetDate = java.time.LocalDate.of(2026, 11, 26),
            targetTimeText = "07:00:00",
            preferredAreaName = "五层中区",
            preferredSeatLabel = "05桌 A座",
            nowEpochMillis = nowMs
        )
        org.junit.Assert.assertTrue("Coordination MUST have been called despite local FAILED status", coordinationInvoked)
        org.junit.Assert.assertTrue("Must block local creation when rolled-over child is RESULT_UNKNOWN", blockedCreate.isFailure)
        org.junit.Assert.assertTrue(
            dao.getLibraryBookingPlansSnapshot().none {
                it.executionMode != "CLOUD_HOSTED" && it.targetLocalDate == "2026-11-26"
            }
        )

        // 5b: Cloud disables parent plan (enabled=false) and cancels queued Day-2 child -> safe to create local plan!
        var parentStopped = false
        repo.cloudHttpTransportForTest = { method, url, _, _, _ ->
            when {
                method == "DELETE" && url.endsWith("/reservation-plans/plan_cloud_parent_1") -> {
                    parentStopped = true
                    """{"ok":true,"data":{"plan_id":"plan_cloud_parent_1","enabled":false}}"""
                }
                method == "GET" && url.endsWith("/api/v1/sync/snapshot") -> {
                    val en = !parentStopped
                    val d2State = if (parentStopped) "CANCELLED" else "ARMED"
                    """
                    {
                      "ok": true,
                      "data": {
                        "reservation_plans": [
                          { "id": "plan_cloud_parent_1", "enabled": $en, "user_intent_state": "${if (en) "ACTIVE" else "STOP_REQUESTED"}", "target_local_date": "2026-11-26" }
                        ],
                        "reservation_jobs": [
                          { "id": "job_day1_failed", "plan_id": "plan_cloud_parent_1", "booking_local_date": "2026-11-25", "state": "FAILED", "external_execution_state": "SCHOOL_REJECTED" },
                          { "id": "job_day2_queued", "plan_id": "plan_cloud_parent_1", "booking_local_date": "2026-11-26", "state": "$d2State", "external_execution_state": "NOT_DISPATCHED" }
                        ]
                      }
                    }
                    """.trimIndent()
                }
                else -> """{"ok":true,"data":{"id":"job_day2_queued","state":"CANCELLED"}}"""
            }
        }
        val allowedCreate = repo.createDualEngineBookingPlan(
            executionMode = cn.edu.haut.campus.core.campusservices.BookingExecutionMode.LOCAL_DEVICE,
            targetDate = java.time.LocalDate.of(2026, 11, 26),
            targetTimeText = "07:00:00",
            preferredAreaName = "五层中区",
            preferredSeatLabel = "05桌 A座",
            nowEpochMillis = nowMs
        )
        org.junit.Assert.assertTrue("Should succeed after Cloud parent & Day-2 child stopped: ${allowedCreate.exceptionOrNull()}", allowedCreate.isSuccess)
    }

    @Test
    fun conditionalRestoreNeverOverwritesCancelEditOrAccountSwitch() = runBlocking {
        val repo = buildInMemoryTestRepo("cas_restore_handoff")
        val jwtA = testCloudJwt("cloud_user_A")
        val jwtB = testCloudJwt("cloud_user_B")
        repo.saveAuthorizedSyncCredentials("20240001", "pw_A", "2026", "3")
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
        val nowMs = java.time.ZonedDateTime.of(2026, 11, 25, 22, 0, 0, 0, java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

        // 6a: User edits/cancels local plan WHILE Cloud creation is pending, then Cloud rejects pre-persist -> user's new decision MUST NOT be overwritten!
        val local27 = repo.createDualEngineBookingPlan(
            executionMode = cn.edu.haut.campus.core.campusservices.BookingExecutionMode.LOCAL_DEVICE,
            targetDate = java.time.LocalDate.of(2026, 11, 27),
            targetTimeText = "07:00:00",
            preferredAreaName = "五层中区",
            preferredSeatLabel = "01桌 A座",
            nowEpochMillis = nowMs
        ).getOrThrow()

        repo.cloudHttpTransportForTest = { method, url, _, _, _ ->
            if (method == "POST" && url.endsWith("/api/v1/me/consents")) {
                // Simulate user explicitly cancelling/modifying the plan while Cloud request is in flight!
                kotlinx.coroutines.runBlocking {
                    val cur = dao.getLibraryBookingPlanById(local27.id)!!
                    dao.upsertLibraryBookingPlan(
                        cur.copy(
                            status = "CANCELLED",
                            preferredSeatLabel = "用户手动取消不恢复",
                            stateVersion = cur.stateVersion + 1,
                            generation = cur.generation + 1,
                            lastDiagnosticMessage = "用户主动取消，禁止补偿恢复"
                        )
                    )
                }
                """{"ok":false,"error":{"code":"FORBIDDEN","message":"委托已拒绝"}}"""
            } else {
                """{"ok":false,"error":{"code":"FORBIDDEN","message":"拒绝"}}"""
            }
        }
        val rejected27 = repo.createDualEngineBookingPlan(
            executionMode = cn.edu.haut.campus.core.campusservices.BookingExecutionMode.CLOUD_HOSTED,
            targetDate = java.time.LocalDate.of(2026, 11, 27),
            targetTimeText = "07:00:00",
            preferredAreaName = "五层中区",
            preferredSeatLabel = "01桌 A座",
            nowEpochMillis = nowMs + 1_000L
        )
        org.junit.Assert.assertTrue(rejected27.isFailure)
        val after27 = dao.getLibraryBookingPlanById(local27.id)!!
        assertEquals("CANCELLED", after27.status)
        assertEquals("用户主动取消，禁止补偿恢复", after27.lastDiagnosticMessage)

        // 6b: User switches account A -> B WHILE Cloud request is pending, then Cloud times out & reconciles SAFE_TO_SWITCH_STOPPED -> MUST NOT restore A's plan under B!
        val local28 = repo.createDualEngineBookingPlan(
            executionMode = cn.edu.haut.campus.core.campusservices.BookingExecutionMode.LOCAL_DEVICE,
            targetDate = java.time.LocalDate.of(2026, 11, 28),
            targetTimeText = "07:00:00",
            preferredAreaName = "五层中区",
            preferredSeatLabel = "02桌 A座",
            nowEpochMillis = nowMs
        ).getOrThrow()

        repo.cloudHttpTransportForTest = { method, url, _, _, _ ->
            when {
                method == "POST" && url.endsWith("/api/v1/me/consents") ->
                    """{"ok":true,"data":{"id":"con_A_28","auth_version":1}}"""
                method == "POST" && url.endsWith("/api/v1/school-bindings") ->
                    """{"ok":true,"data":{"id":"bind_A_28"}}"""
                method == "POST" && url.endsWith("/complete") ->
                    """{"ok":true,"data":{"status":"DELEGATED_ACTIVE"}}"""
                method == "POST" && url.endsWith("/api/v1/reservation-plans") -> {
                    // User switches A -> B while waiting for Cloud response!
                    kotlinx.coroutines.runBlocking {
                        repo.saveAuthorizedSyncCredentials("20240002", "pw_B", "2026", "3")
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
                                linkedAtEpochMillis = nowMs + 2_000L
                            )
                        )
                    }
                    throw java.net.SocketTimeoutException("Timed out during POST /reservation-plans")
                }
                method == "GET" && url.endsWith("/api/v1/sync/snapshot") ->
                    """{"ok":true,"data":{"reservation_plans":[],"reservation_jobs":[]}}"""
                method == "DELETE" && url.contains("/api/v1/reservation-plans/") ->
                    """{"ok":false,"error":{"code":"NOT_FOUND","message":"not found"}}"""
                else -> """{"ok":false,"error":{"code":"NOT_FOUND","message":"not found"}}"""
            }
        }
        val switchedMidCloud = repo.createDualEngineBookingPlan(
            executionMode = cn.edu.haut.campus.core.campusservices.BookingExecutionMode.CLOUD_HOSTED,
            targetDate = java.time.LocalDate.of(2026, 11, 28),
            targetTimeText = "07:00:00",
            preferredAreaName = "五层中区",
            preferredSeatLabel = "02桌 A座",
            nowEpochMillis = nowMs + 1_500L
        )
        org.junit.Assert.assertTrue(switchedMidCloud.isFailure)
        val after28 = dao.getLibraryBookingPlanById(local28.id)!!
        assertEquals("Account A's paused local plan must remain CANCELLED after switching to B", "CANCELLED", after28.status)
    }

    @Test
    fun fireConflictIsolatesStudentsAndBlocksSameStudentAcrossCloud() = runBlocking {
        val repo = buildInMemoryTestRepo("fire_conflict_scope")
        val nowMs = java.time.ZonedDateTime.of(2026, 11, 29, 7, 0, 0, 0, java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

        // Seed Account A (student 20240001) with CONFIRMED record on 2026-11-29
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_fire_A_confirmed",
                schoolId = "haut",
                executionMode = "CLOUD_HOSTED",
                targetLocalDate = "2026-11-29",
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
                cloudJobId = "job_fire_A_1",
                externalBookingId = "bk_fire_A_1",
                evidenceSha256 = "sha_fire_A_1",
                priceMicropeach = 10_000L,
                paymentState = "PAID",
                status = "CONFIRMED",
                stateVersion = 2,
                generation = 1,
                lastDiagnosticMessage = "A已成功锁定",
                createdAtEpochMillis = 1_000L,
                updatedAtEpochMillis = 1_000L,
                ownerCloudUserId = "cloud_user_A",
                ownerStudentId = "20240001"
            )
        )

        // 8a: Independent Student B (20240002) runs LOCAL_DEVICE FIRE on 2026-11-29 (with no library token so it reaches token check AFTER conflictSameDay!)
        // If A's record falsely blocked B, B would become RESULT_UNKNOWN with "检测到同一学号/账号在同日存在另一笔有效或待核对的预约任务"!
        // Because B is isolated from A, B passes conflictSameDay and fails pre-dispatch as FAILED (no library session), NEVER RESULT_UNKNOWN!
        repo.saveAuthorizedSyncCredentials("20240002", "pw_B", "2026", "3")
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_fire_B_local",
                schoolId = "haut",
                executionMode = "LOCAL_ASSISTED",
                targetLocalDate = "2026-11-29",
                targetLocalTime = "07:00:00",
                timezoneId = "Asia/Shanghai",
                precheckAtEpochMillis = 1_000L,
                finalCheckAtEpochMillis = 2_000L,
                fireAtEpochMillis = 3_000L,
                precheckStatus = "PRECHECK_OK",
                finalCheckStatus = "FINAL_CHECK_OK",
                fireStatus = "ARMED",
                preferredAreaName = "五层中区",
                preferredSeatLabel = "02桌 B座",
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
                createdAtEpochMillis = 2_000L,
                updatedAtEpochMillis = 2_000L,
                ownerCloudUserId = null,
                ownerStudentId = "20240002"
            )
        )
        val fireResB = repo.advanceBookingPlanStage(
            planId = "plan_fire_B_local",
            stage = cn.edu.haut.campus.core.campusservices.TripleScheduleStage.FIRE,
            nowEpochMillis = nowMs
        ).getOrThrow()
        org.junit.Assert.assertNotEquals(
            "Student B must NOT be blocked into RESULT_UNKNOWN by Student A's confirmed record",
            "RESULT_UNKNOWN",
            fireResB.status
        )
        org.junit.Assert.assertFalse(
            fireResB.lastDiagnosticMessage.orEmpty().contains("检测到同一学号/账号在同日存在另一笔")
        )

        // 8b: SAME Student (20240001) under a different cloud account (cloud_user_B) runs LOCAL_DEVICE FIRE on 2026-11-29 -> MUST be blocked by conflictSameDay!
        repo.saveAuthorizedSyncCredentials("20240001", "pw_A", "2026", "3")
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_fire_same_stu_cross_cloud",
                schoolId = "haut",
                executionMode = "LOCAL_ASSISTED",
                targetLocalDate = "2026-11-29",
                targetLocalTime = "07:00:00",
                timezoneId = "Asia/Shanghai",
                precheckAtEpochMillis = 1_000L,
                finalCheckAtEpochMillis = 2_000L,
                fireAtEpochMillis = 3_000L,
                precheckStatus = "PRECHECK_OK",
                finalCheckStatus = "FINAL_CHECK_OK",
                fireStatus = "ARMED",
                preferredAreaName = "五层中区",
                preferredSeatLabel = "03桌 A座",
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
                createdAtEpochMillis = 3_000L,
                updatedAtEpochMillis = 3_000L,
                ownerCloudUserId = "cloud_user_B",
                ownerStudentId = "20240001"
            )
        )
        val fireResSameStu = repo.advanceBookingPlanStage(
            planId = "plan_fire_same_stu_cross_cloud",
            stage = cn.edu.haut.campus.core.campusservices.TripleScheduleStage.FIRE,
            nowEpochMillis = nowMs
        ).getOrThrow()
        assertEquals("RESULT_UNKNOWN", fireResSameStu.status)
        org.junit.Assert.assertTrue(
            fireResSameStu.lastDiagnosticMessage.orEmpty().contains("检测到同一学号/账号在同日存在另一笔")
        )
    }

    @Test
    fun allDiningCanteensMatchMapSeedBuildingCoordinates() {
        val allMapBuildings = (HautMockSeedData.lianhuajieBuildings +
            cn.edu.haut.campus.core.domain.ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS)
            .associateBy { it.id }

        for (canteen in cn.edu.haut.campus.core.domain.CampusDiningRecommender.CANTEENS) {
            val mapBuilding = org.junit.Assert.assertNotNull(
                "Missing map Building for canteen ${canteen.buildingId}",
                allMapBuildings[canteen.buildingId]
            ).let { allMapBuildings.getValue(canteen.buildingId) }
            assertEquals(
                "Latitude mismatch on ${canteen.buildingId}",
                mapBuilding.latitude,
                canteen.latitude,
                0.0
            )
            assertEquals(
                "Longitude mismatch on ${canteen.buildingId}",
                mapBuilding.longitude,
                canteen.longitude,
                0.0
            )
        }
    }

    @Test
    fun sameCloudAccountStudentSwitchPreservesImmutableJobStudentOwner() = runBlocking {
        // P1 #2 exact scenario:
        // Same Profile & same Cloud account C ("cloud_user_C"), school student switches A ("20240001") -> B ("20240002") -> A ("20240001").
        // Sync returns:
        // 1) A's existing cloud job ("job_C_stuA_01") whose derived candidatePlanId matches A's local row id;
        // 2) A historical unmapped job ("job_C_unknown_legacy") with no verifiable student_no/binding;
        // 3) Same-date ("2026-11-30") distinct jobs for A and B ("job_C_sameDate_A", "job_C_sameDate_B").
        java.io.File(RuntimeEnvironment.getApplication().filesDir, "cloud_auth_provenance").deleteRecursively()
        val repo = buildInMemoryTestRepo("same_cloud_stu_switch_p1")
        val jwtC = testCloudJwt("cloud_user_C")

        repo.saveAuthorizedSyncCredentials("20240001", "pw_A", "2026", "3")
        repo.saveVerifiedXpeachCloudAccount(
            cn.edu.haut.campus.core.campusservices.XpeachCloudAccountProfile(
                cloudUserId = "cloud_user_C",
                ssoIssuer = "https://auth.yellowpeachxgp.com",
                ssoSubject = "sub_C",
                displayName = "Shared Cloud C",
                peachBalanceMicropeach = 50_000L,
                holdSupported = false,
                accessToken = jwtC,
                refreshToken = "ctseal1:ref_C",
                linkedAtEpochMillis = 1_000L
            )
        )

        val jobIdA = "job_C_stuA_01"
        val derivedIdForJobA = "plan_${jobIdA.replace("-", "").take(14)}"
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = derivedIdForJobA,
                schoolId = "haut",
                executionMode = "CLOUD_HOSTED",
                targetLocalDate = "2026-11-28",
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
                cloudJobId = jobIdA,
                externalBookingId = "bk_C_A_01",
                evidenceSha256 = "sha_C_A_01",
                priceMicropeach = 10_000L,
                paymentState = "PAID",
                status = "CONFIRMED",
                stateVersion = 2,
                generation = 1,
                lastDiagnosticMessage = "A在同云账号C下的已确认预约",
                createdAtEpochMillis = 1_000L,
                updatedAtEpochMillis = 1_000L,
                ownerCloudUserId = "cloud_user_C",
                ownerStudentId = "20240001"
            )
        )

        // Switch school student A -> B while keeping Cloud account C unchanged!
        repo.saveAuthorizedSyncCredentials("20240002", "pw_B", "2026", "3")

        // Seed B's own unsynced plan on 2026-11-30 so we also test same-date A vs B coexistence
        dao.upsertLibraryBookingPlan(
            LibraryBookingPlanEntity(
                id = "plan_C_stuB_sameDate_local",
                schoolId = "haut",
                executionMode = "CLOUD_HOSTED",
                targetLocalDate = "2026-11-30",
                targetLocalTime = "07:00:00",
                timezoneId = "Asia/Shanghai",
                precheckAtEpochMillis = 1_000L,
                finalCheckAtEpochMillis = 2_000L,
                fireAtEpochMillis = 3_000L,
                precheckStatus = "ARMED",
                finalCheckStatus = "ARMED",
                fireStatus = "ARMED",
                preferredAreaName = "四层南区",
                preferredSeatLabel = "08桌 B座",
                allowAlternativeSeat = true,
                cloudJobId = null,
                externalBookingId = null,
                evidenceSha256 = null,
                priceMicropeach = 10_000L,
                paymentState = "DEBIT_PENDING",
                status = "ARMED",
                stateVersion = 1,
                generation = 1,
                lastDiagnosticMessage = "B同日计划",
                createdAtEpochMillis = 2_000L,
                updatedAtEpochMillis = 2_000L,
                ownerCloudUserId = "cloud_user_C",
                ownerStudentId = "20240002"
            )
        )

        repo.cloudHttpTransportForTest = { _, _, _, _, _ ->
            """
            {
              "ok": true,
              "data": {
                "auth_version": 2,
                "user": { "cloud_user_id": "cloud_user_C", "display_name": "Shared Cloud C" },
                "wallet": { "peach_balance": 5.0 },
                "consents": [{ "id": "con_C_2", "school_id": "haut", "auth_version": 2 }],
                "school_bindings": [
                  { "id": "bind_C_B", "school_id": "haut", "student_no": "20240002", "consent_id": "con_C_2", "auth_version": 2 }
                ],
                "reservation_plans": [],
                "reservation_jobs": [
                  {
                    "id": "$jobIdA",
                    "plan_id": "rplan_A_01",
                    "booking_local_date": "2026-11-28",
                    "target_local_time": "07:00:00",
                    "state": "CONFIRMED",
                    "display_status": "SCHOOL_CONFIRMED",
                    "user_intent_state": "ACTIVE",
                    "external_execution_state": "SCHOOL_CONFIRMED",
                    "school_cancellation_state": "NOT_REQUESTED",
                    "candidate_seats": ["01桌 A座"],
                    "school_reservation": {
                      "external_booking_id": "bk_C_A_01",
                      "seat_alias": "01桌 A座",
                      "region_alias": "五层中区",
                      "evidence_sha256": "sha_C_A_01",
                      "official_status": "CONFIRMED"
                    }
                  },
                  {
                    "id": "job_C_unknown_legacy",
                    "plan_id": "rplan_legacy_unmapped",
                    "booking_local_date": "2026-11-29",
                    "target_local_time": "07:00:00",
                    "state": "ARMED",
                    "display_status": "ARMED",
                    "user_intent_state": "ACTIVE",
                    "external_execution_state": "NOT_DISPATCHED",
                    "school_cancellation_state": "NOT_REQUESTED",
                    "candidate_seats": ["02桌 A座"]
                  },
                  {
                    "id": "job_C_sameDate_A",
                    "plan_id": "rplan_sameDate_A",
                    "school_binding_id": "bind_C_A_revoked",
                    "student_no": "20240001",
                    "booking_local_date": "2026-11-30",
                    "target_local_time": "07:00:00",
                    "state": "CONFIRMED",
                    "display_status": "SCHOOL_CONFIRMED",
                    "user_intent_state": "ACTIVE",
                    "external_execution_state": "SCHOOL_CONFIRMED",
                    "school_cancellation_state": "NOT_REQUESTED",
                    "candidate_seats": ["03桌 A座"],
                    "school_reservation": {
                      "external_booking_id": "bk_sameDate_A",
                      "seat_alias": "03桌 A座",
                      "region_alias": "五层中区",
                      "evidence_sha256": "sha_sameDate_A",
                      "official_status": "CONFIRMED"
                    }
                  },
                  {
                    "id": "job_C_sameDate_B",
                    "plan_id": "rplan_sameDate_B",
                    "school_binding_id": "bind_C_B",
                    "booking_local_date": "2026-11-30",
                    "target_local_time": "07:00:00",
                    "state": "ARMED",
                    "display_status": "ARMED",
                    "user_intent_state": "ACTIVE",
                    "external_execution_state": "NOT_DISPATCHED",
                    "school_cancellation_state": "NOT_REQUESTED",
                    "candidate_seats": ["08桌 B座"]
                  }
                ],
                "orders": [],
                "parcels": []
              }
            }
            """.trimIndent()
        }

        val syncRes = repo.syncWithCampusCloud()
        org.junit.Assert.assertTrue("Sync on C+B should succeed: ${syncRes.exceptionOrNull()}", syncRes.isSuccess)

        // 1) A's existing row MUST keep ownerStudentId = "20240001" (NEVER re-attributed to "20240002"!)
        val rowJobA = dao.getLibraryBookingPlanById(derivedIdForJobA)!!
        assertEquals("20240001", rowJobA.ownerStudentId)
        assertEquals("bk_C_A_01", rowJobA.externalBookingId)

        // 2) Historical job without verifiable student_no/binding MUST stay isolated (ownerStudentId = null, invisible to B!)
        val allRows = dao.getLibraryBookingPlansSnapshot()
        val unknownLegacyRow = allRows.single { it.cloudJobId == "job_C_unknown_legacy" }
        assertEquals(null, unknownLegacyRow.ownerStudentId)

        // 3) Same-date ("2026-11-30") A and B jobs MUST NOT clobber each other!
        val sameDateRowA = allRows.single { it.cloudJobId == "job_C_sameDate_A" }
        val sameDateRowB = allRows.single { it.cloudJobId == "job_C_sameDate_B" }
        assertEquals("20240001", sameDateRowA.ownerStudentId)
        assertEquals("20240002", sameDateRowB.ownerStudentId)
        assertEquals("plan_C_stuB_sameDate_local", sameDateRowB.id)

        val visibleWhileB = repo.libraryBookingPlansFlow.first()
        org.junit.Assert.assertTrue(visibleWhileB.none { it.cloudJobId == jobIdA })
        org.junit.Assert.assertTrue(visibleWhileB.none { it.cloudJobId == "job_C_unknown_legacy" })
        org.junit.Assert.assertTrue(visibleWhileB.none { it.cloudJobId == "job_C_sameDate_A" })
        org.junit.Assert.assertTrue(visibleWhileB.any { it.cloudJobId == "job_C_sameDate_B" })

        // 4) Switch back B -> A on same Cloud account C: A sees its preserved jobs ("job_C_stuA_01" & "job_C_sameDate_A") intact!
        repo.saveAuthorizedSyncCredentials("20240001", "pw_A", "2026", "3")
        val visibleBackOnA = repo.libraryBookingPlansFlow.first()
        org.junit.Assert.assertTrue(visibleBackOnA.any { it.cloudJobId == jobIdA && it.externalBookingId == "bk_C_A_01" })
        org.junit.Assert.assertTrue(visibleBackOnA.any { it.cloudJobId == "job_C_sameDate_A" && it.externalBookingId == "bk_sameDate_A" })
        org.junit.Assert.assertTrue(visibleBackOnA.none { it.cloudJobId == "job_C_sameDate_B" })
    }

    @Test
    fun mergedRuleEditShortenWeeksChangeDayPartialOverlapAndSingleDayChain() = runBlocking {
        val repo = buildInMemoryTestRepo("merged_rule_regressions_5_6")
        val campusDao = database.campusDao()
        val term = HautMockSeedData.defaultTerms.first { it.id == "2026-2027-1" }
        val periodTimes = HautMockSeedData.defaultPeriodTimes
        campusDao.upsertTerms(listOf(term.toEntity()))
        campusDao.upsertPeriodTimes(periodTimes.map { it.toEntity() })
        campusDao.upsertBuildings(HautMockSeedData.lianhuajieBuildings.map { it.toEntity() })
        campusDao.upsertBuildingAliases(HautMockSeedData.defaultBuildingAliases.map { it.toEntity() })

        // --- Item #9 check: F01 in HautMockSeedData must NOT be EXACT_VERIFIED (zone-level approximate only) ---
        val f01Res = cn.edu.haut.campus.core.domain.ClassroomBuildingResolver.resolve(
            rawRoom = "莲花街校区 学生宿舍F01 W111",
            buildings = HautMockSeedData.lianhuajieBuildings,
            aliases = HautMockSeedData.defaultBuildingAliases
        )
        assertEquals(
            "F01 alias pointing to generic E/F zone must NOT claim EXACT_VERIFIED",
            cn.edu.haut.campus.core.model.BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
            f01Res.status
        )
        assertEquals("b_dorm_ef", f01Res.building?.id)

        // --- Item #5a: Shortening weeks on merged [r1(1-2, W1..16), r2(3-4, W1..16)] to W1..8 must NOT leave r2 in W9! ---
        val c1 = cn.edu.haut.campus.core.model.Course(
            id = "c_short",
            name = "编译原理实验",
            teacher = "王老师",
            colorArgb = 0xFF2563EB,
            source = cn.edu.haut.campus.core.model.CourseSource.OFFICIAL_JW
        )
        val r1 = cn.edu.haut.campus.core.model.CourseScheduleRule(
            id = "r_s1",
            courseId = c1.id,
            termId = term.id,
            weekday = java.time.DayOfWeek.TUESDAY,
            startPeriod = 1,
            endPeriod = 2,
            weeks = (1..16).toSet(),
            rawRoom = "4号教学楼4102",
            buildingId = "b_t4"
        )
        val r2 = r1.copy(id = "r_s2", startPeriod = 3, endPeriod = 4)
        campusDao.upsertCourse(c1.toEntity())
        campusDao.upsertRules(listOf(r1.toEntity(), r2.toEntity()))

        val w2Tue = java.time.LocalDate.of(2026, 9, 8) // Week 2 Tue
        val w9Tue = java.time.LocalDate.of(2026, 10, 27) // Week 9 Tue
        val mergedW2 = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            date = w2Tue,
            term = term,
            courses = listOf(c1),
            rules = campusDao.getRulesSnapshot().map { it.toDomain() },
            periodTimes = periodTimes
        ).single()
        assertEquals(listOf("r_s1", "r_s2"), mergedW2.mergedRuleIds)

        repo.saveUserMergedCourseRuleEdit(
            course = c1,
            primaryRule = r1.copy(startPeriod = 1, endPeriod = 4, weeks = (1..8).toSet()),
            mergedRuleIds = mergedW2.mergedRuleIds
        )
        val w9AfterShorten = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            date = w9Tue,
            term = term,
            courses = listOf(c1),
            rules = campusDao.getRulesSnapshot().map { it.toDomain() },
            periodTimes = periodTimes
        )
        assertEquals("Week 9 must have ZERO residual r_s2 3-4节 after shortening merged rule to W1..8!", 0, w9AfterShorten.size)

        // --- Item #5b & #5c: Partial overlap [r_p1(W1..12), r_p2(W5..16)] + unrelated r_other(Fri W1..16), moving merged rule to Thursday W1..8 ---
        val c2 = c1.copy(id = "c_partial", name = "算法设计与分析")
        val rp1 = r1.copy(id = "r_p1", courseId = c2.id, weeks = (1..12).toSet())
        val rp2 = r1.copy(id = "r_p2", courseId = c2.id, startPeriod = 3, endPeriod = 4, weeks = (5..16).toSet())
        val rOther = r1.copy(id = "r_other_fri", courseId = c2.id, weekday = java.time.DayOfWeek.FRIDAY, startPeriod = 5, endPeriod = 6, weeks = (1..16).toSet())
        campusDao.upsertCourse(c2.toEntity())
        campusDao.upsertRules(listOf(rp1.toEntity(), rp2.toEntity(), rOther.toEntity()))

        val w6Tue = java.time.LocalDate.of(2026, 10, 6) // Week 6 Tue (both rp1 and rp2 active -> merged!)
        val w10Tue = java.time.LocalDate.of(2026, 11, 3) // Week 10 Tue (was in rp1..12 merged scope -> must be removed!)
        val w14Tue = java.time.LocalDate.of(2026, 12, 1) // Week 14 Tue (standalone rp2 outside rp1's W1..12 -> must be preserved!)
        val w6Thu = java.time.LocalDate.of(2026, 10, 8) // Week 6 Thu (new weekday!)
        val w6Fri = java.time.LocalDate.of(2026, 10, 9) // Week 6 Fri (unrelated rule!)

        val mergedW6 = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            date = w6Tue,
            term = term,
            courses = listOf(c2),
            rules = campusDao.getRulesSnapshot().map { it.toDomain() },
            periodTimes = periodTimes
        ).single()
        assertEquals(listOf("r_p1", "r_p2"), mergedW6.mergedRuleIds)

        repo.saveUserMergedCourseRuleEdit(
            course = c2,
            primaryRule = rp1.copy(weekday = java.time.DayOfWeek.THURSDAY, startPeriod = 1, endPeriod = 4, weeks = (1..8).toSet()),
            mergedRuleIds = mergedW6.mergedRuleIds
        )
        val rulesAfterPartial = campusDao.getRulesSnapshot().map { it.toDomain() }
        assertEquals(0, cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(w10Tue, term, listOf(c2), rulesAfterPartial, periodTimes).size)
        val w14Preserved = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(w14Tue, term, listOf(c2), rulesAfterPartial, periodTimes).single()
        assertEquals(3, w14Preserved.startPeriod)
        assertEquals(4, w14Preserved.endPeriod)
        val w6ThuMoved = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(w6Thu, term, listOf(c2), rulesAfterPartial, periodTimes).single()
        assertEquals(1, w6ThuMoved.startPeriod)
        assertEquals(4, w6ThuMoved.endPeriod)
        assertEquals(1, cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(w6Fri, term, listOf(c2), rulesAfterPartial, periodTimes).size)

        // --- Item #6: Single-day edit on merged [r_c1, r_c2] -> preserves mergedRuleIds -> subsequent full-semester edit & delete cover BOTH rules! ---
        val c3 = c1.copy(id = "c_chain", name = "计算机体系结构")
        val rc1 = r1.copy(id = "r_c1", courseId = c3.id, weeks = (1..16).toSet())
        val rc2 = r1.copy(id = "r_c2", courseId = c3.id, startPeriod = 3, endPeriod = 4, weeks = (1..16).toSet())
        campusDao.upsertCourse(c3.toEntity())
        campusDao.upsertRules(listOf(rc1.toEntity(), rc2.toEntity()))

        val w3Tue = java.time.LocalDate.of(2026, 9, 15)
        val initialChainW2 = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            w2Tue, term, listOf(c3), campusDao.getRulesSnapshot().map { it.toDomain() }, periodTimes
        ).single()
        assertEquals(listOf("r_c1", "r_c2"), initialChainW2.mergedRuleIds)

        // Step 1: Single-day edit W2 to 5-8节
        repo.saveSingleDayOccurrenceEdit(initialChainW2, term.id, 5, 8, "5号教学楼5201")
        val afterSingleEditW2 = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            w2Tue,
            term,
            listOf(c3),
            campusDao.getRulesSnapshot().map { it.toDomain() },
            periodTimes,
            occurrenceOverrides = campusDao.getOccurrenceOverridesSnapshot().map { it.toDomain() }
        ).single()
        assertEquals(
            "Single-day edited occurrence MUST retain absorbed mergedRuleIds [r_c1, r_c2]!",
            listOf("r_c1", "r_c2"),
            afterSingleEditW2.mergedRuleIds
        )

        // Step 2: Re-edit single-day W2 to 7-8节 -> still retains [r_c1, r_c2]!
        repo.saveSingleDayOccurrenceEdit(afterSingleEditW2, term.id, 7, 8, "5号教学楼5202")
        val afterSecondSingleEditW2 = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            w2Tue,
            term,
            listOf(c3),
            campusDao.getRulesSnapshot().map { it.toDomain() },
            periodTimes,
            occurrenceOverrides = campusDao.getOccurrenceOverridesSnapshot().map { it.toDomain() }
        ).single()
        assertEquals(listOf("r_c1", "r_c2"), afterSecondSingleEditW2.mergedRuleIds)

        // Step 3: From that single-day-edited W2 occurrence, user performs FULL-SEMESTER edit to 11-12节 -> W3 MUST ONLY have 11-12节 (zero old r_c2 3-4节)!
        repo.saveUserMergedCourseRuleEdit(
            course = c3,
            primaryRule = rc1.copy(startPeriod = 11, endPeriod = 12, rawRoom = "3号教学楼3201"),
            mergedRuleIds = afterSecondSingleEditW2.mergedRuleIds
        )
        val w3AfterFullFromSingle = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            w3Tue,
            term,
            listOf(c3),
            campusDao.getRulesSnapshot().map { it.toDomain() },
            periodTimes,
            occurrenceOverrides = campusDao.getOccurrenceOverridesSnapshot().map { it.toDomain() }
        )
        assertEquals("W3 must NOT have leftover r_c2 3-4节!", 1, w3AfterFullFromSingle.size)
        assertEquals(11, w3AfterFullFromSingle.single().startPeriod)
        assertEquals(12, w3AfterFullFromSingle.single().endPeriod)

        // Step 4: Also verify single-day edit -> deleteCourseScheduleRules deletes BOTH rules!
        val c4 = c1.copy(id = "c_del_chain", name = "编译实验删除链")
        val rd1 = r1.copy(id = "r_d1", courseId = c4.id, weeks = (1..16).toSet())
        val rd2 = r1.copy(id = "r_d2", courseId = c4.id, startPeriod = 3, endPeriod = 4, weeks = (1..16).toSet())
        campusDao.upsertCourse(c4.toEntity())
        campusDao.upsertRules(listOf(rd1.toEntity(), rd2.toEntity()))
        val initDelW2 = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            w2Tue, term, listOf(c4), campusDao.getRulesSnapshot().map { it.toDomain() }, periodTimes
        ).single()
        repo.saveSingleDayOccurrenceEdit(initDelW2, term.id, 5, 8, "5号教学楼5201")
        val singleEditedForDel = cn.edu.haut.campus.core.domain.ScheduleEngine.resolveOccurrencesForDate(
            w2Tue,
            term,
            listOf(c4),
            campusDao.getRulesSnapshot().map { it.toDomain() },
            periodTimes,
            occurrenceOverrides = campusDao.getOccurrenceOverridesSnapshot().map { it.toDomain() }
        ).single()
        repo.deleteCourseScheduleRules(singleEditedForDel.mergedRuleIds)
        assertEquals(
            0,
            campusDao.getRulesSnapshot().count { it.courseId == c4.id }
        )
    }
}
