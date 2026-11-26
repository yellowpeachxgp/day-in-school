package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.model.AttemptType
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.CalendarOverrideType
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.StudentProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * W0-05a 特征快照：先冻结 CampusRepository 外观公开 API 与本阶段拆出方法的
 * 可观察行为（数据库、DataStore、通知顺序），再允许实现搬迁到领域 Repository。
 * 该测试不访问网络，也不依赖真实学校账号。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CampusRepositorySplitCharacterizationTest {
    private lateinit var fixture: RepairTestFixture
    private val repo get() = fixture.repository
    private val dao get() = fixture.dao

    @Before
    fun setUp() {
        fixture = RepairTestFixture.create(
            RuntimeEnvironment.getApplication(),
            "prof_split_characterization_" + System.nanoTime()
        )
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun w005a_public_instance_api_name_snapshot() {
        // 不能过滤 isSynthetic：若公开 Kotlin API 返回 inline/value class（如 Result），
        // JVM 只能以 synthetic 实现方法承载该签名；它们仍是调用方可达到的兼容 API。
        val declaredNames = CampusRepository::class.java.declaredMethods
            .map { it.name }
            .toSet()
        val expectedPublicMethods = listOf(
            "requireActiveSession",
            "registerBeforeWipeHook",
            "observeReminderTasksInWindow",
            "reminderDao",
            "utilityAndFocusDao",
            "utilityAccountSecureStore",
            "ensureInitialSeedIfNeeded",
            "completeOnboarding",
            "resetOnboardingForGuide",
            "resetToDemoSeedData",
            "ensureTermExistsAndUpdateCalendar",
            "selectCurrentTerm",
            "updateTermStartDate",
            "updatePeriodTime",
            "saveUserCourseEdit",
            "saveUserMergedCourseRuleEdit",
            "addManualCourseWithRule",
            "saveOccurrenceOverride",
            "saveSingleDayOccurrenceEdit",
            "resetSingleDayOccurrenceEdit",
            "cancelClassOccurrenceOnDate",
            "rescheduleSingleOccurrenceCrossDate",
            "deleteOccurrenceOverride",
            "unpauseOccurrence",
            "undoMove",
            "restoreRuleCancellation",
            "executeRestoreCommand",
            "restoreCanceledOccurrence",
            "deleteCourseScheduleRule",
            "deleteCourseScheduleRules",
            "scheduleUnscheduledPracticalCourse",
            "saveCalendarOverride",
            "deleteCalendarOverride",
            "confirmBuildingAliasForRoom",
            "previewCourseImportDiff",
            "commitCourseImportDiff",
            "saveImportedGrades",
            "saveStudentProfile",
            "saveImportedExams",
            "setAutoSyncEnabled",
            "saveAuthorizedSyncCredentials",
            "applyLiveSyncData",
            "performDynamicAcademicSync",
            "readCachedLibrarySeatSession",
            "clearLibrarySeatSession",
            "captureLibrarySeatWebToken",
            "ensureLibrarySeatSession",
            "saveLibrarySeatCredentialsAndLogin",
            "fetchLibrarySeatOverview",
            "executeLibrarySeatAction",
            "setGpaPrivacyShield",
            "setUseDynamicColor",
            "setThemeMode",
            "setUiDesignStyle",
            "applyCuratedThemePreset",
            "setThemePalette",
            "saveCustomWallpaperTheme",
            "saveExtractedDesktopSwatches",
            "updateWallpaperVisualParams",
            "resetWallpaperVisualParams",
            "clearCustomWallpaperTheme",
            "updateHomeCardLayout",
            "resetHomeCardLayout",
            "getActiveThemeAccentArgb",
            "setAutoCheckUpdateEnabled",
            "getNetworkProxyConfigSnapshot",
            "updateNetworkProxyConfig",
            "resetNetworkProxyConfig",
            "saveCustomChannelUrl",
            "saveCustomClubGroupNumber",
            "refreshCampusCommunityContent",
            "refreshCampusMomentsSnapshot",
            "publishMomentsPost",
            "addMomentsComment",
            "submitMomentsContactRequest",
            "fetchMomentsContactRequestsResult",
            "fetchMomentsContactRequests",
            "claimLegacyMomentsPostOwnership",
            "handleMomentsContactRequestAction",
            "reportMomentsPost",
            "toggleMomentsPostLike",
            "toggleMomentsPostFavorite",
            "toggleMomentsPostResolved",
            "deleteMomentsPost",
            "publishMomentsBookListing",
            "toggleMomentsBookFavorite",
            "toggleMomentsBookSold",
            "deleteMomentsBookListing",
            "upsertAntiLostTag",
            "reportAntiLostTagFound",
            "setTimelineZoomScale",
            "saveWidgetConfig",
            "getWidgetConfigOrDefault",
            "updateAllWidgetDefaults",
            "getReminderConfigSnapshot",
            "getExamSchedulesSnapshot",
            "getReminderEventOverridesSnapshot",
            "updateReminderGlobalConfig",
            "upsertReminderEventOverride",
            "removeReminderEventOverride",
            "appendReminderDiagnosticLog",
            "setCampusServiceFeatureFlag",
            "setAllowUnverifiedUtilityTransport",
            "setMealRecommendEnabled",
            "isMealRecommendEnabledSnapshot",
            "getLastMealRecommendBlockKeySnapshot",
            "markMealRecommendNotified",
            "getCanteenDishReviewsSnapshot",
            "saveCanteenDishReview",
            "deleteCanteenDishReview",
            "setSelectedFocusMinutes",
            "readUtilityAccountResult",
            "saveAndRefreshOnlineUtilities",
            "performPeriodicUtilitySampleIfEligible",
            "updateUtilityNotificationConfig",
            "notifyDailyUtilitySummaryIfEligible",
            "sendTestUtilityNotification",
            "updateOnlineRoomCampus",
            "clearUtilityAccountAndOnlineCache",
            "saveUtilityMeter",
            "deleteUtilityMeter",
            "addUtilityReading",
            "deleteUtilityReading",
            "applyInterchangeCalendarMetadata",
            "wipeAllPersonalData",
            "getDirectScheduleDataSnapshot",
            "setSelectedCampusId",
            "saveCustomCampusZonesJson",
            "snapshotCloudIdentitySession",
            "isCloudIdentitySnapshotCurrent",
            "saveVerifiedXpeachCloudAccount",
            "refreshXpeachWalletBalance",
            "syncWithCampusCloud",
            "reconfirmAndRestoreCloudBookingPlans",
            "unlinkXpeachCloudAccountAndRevokeCloudPlans",
            "getAllLibraryBookingPlansForAuditSnapshot",
            "snapshotBookingSubmissionIdentity",
            "createDualEngineBookingPlan",
            "loadLibraryPlanningRegions",
            "loadLibraryPlanningSeats",
            "fetchLibraryStudyRegions",
            "fetchLibraryRegionSeats",
            "advanceBookingPlanStage",
            "executeCloudBookingPlanNow",
            "stopInFlightOrQueuedDelegation",
            "cancelDualEngineBookingPlan",
            "cancelActiveLibrarySeatBooking",
            "rescheduleActiveLocalBookingPlans",
            "upsertParcelItemFromInput",
            "markParcelPickedUp",
            "markAllStationParcelsPickedUp",
            "deleteParcelById",
            "getXiaomiAccounts",
            "saveXiaomiAccountFromWebLogin",
            "sendXiaomiPhoneVerificationCode",
            "verifyAndBindXiaomiPhone",
            "addXiaomiPhoneDirectly",
            "unbindXiaomiPhone",
            "removeXiaomiAccount",
            "syncXiaomiExpressPackages",
            "fetchXiaomiParcelDetail",
            "ensureDefaultReferencePluginsInstalled",
            "upsertExtensionPlugin",
            "removeExtensionPlugin"
        )
        assertEquals(164, expectedPublicMethods.distinct().size)
        val missingMethods = expectedPublicMethods.filterNot { it in declaredNames }
        assertEquals("CampusRepository 公开实例方法不得减少", emptyList<String>(), missingMethods)

        val expectedAccessors = listOf(
            "isSessionActive",
            "getProfileId",
            "getSchoolId",
            "getSchool",
            "getTimeZone",
            "getSupportsHautServices",
            "getSessionGeneration",
            "getOnScheduleOrPolicyChangedListener",
            "setOnScheduleOrPolicyChangedListener",
            "getOnBeforeWipePersonalDataHook",
            "setOnBeforeWipePersonalDataHook",
            "getPreferencesFlow",
            "getStudentProfileFlow",
            "getTermsFlow",
            "getCurrentTermFlow",
            "getPeriodTimesFlow",
            "getCalendarOverridesFlow",
            "getCoursesFlow",
            "getRulesFlow",
            "getOccurrenceOverridesFlow",
            "getGradeAttemptsFlow",
            "getBuildingsFlow",
            "getBuildingAliasesFlow",
            "getWidgetConfigsFlow",
            "getImportAuditsFlow",
            "getExamTermReviewAuditsFlow",
            "getExamSchedulesFlow",
            "getUnscheduledCoursesFlow",
            "getActiveReminderTasksFlow",
            "getReminderEventOverridesFlow",
            "getUtilityDailySamplesFlow",
            "getUtilityOnlineRoomsFlow",
            "getUtilityMetersFlow",
            "getUtilityReadingsFlow",
            "getActiveFocusSessionFlow",
            "getCompletedFocusSessionsFlow",
            "getCloudIdentityBindingFlow",
            "getAllLibraryBookingPlansForAuditFlow",
            "getUnattributedLegacyBookingPlansFlow",
            "getLibraryBookingPlansFlow",
            "getParcelItemsFlow",
            "getParcelSourceConnectionsFlow",
            "getInstalledExtensionPluginsFlow"
        )
        val missingAccessors = expectedAccessors.filterNot { it in declaredNames }
        assertEquals("CampusRepository 公开实例属性访问器不得减少", emptyList<String>(), missingAccessors)
    }

    @Test
    fun w005a_split_target_suspend_method_jvm_shape_snapshot() {
        val declared = CampusRepository::class.java.declaredMethods.filterNot { it.isSynthetic }
        fun assertSuspendShape(name: String, parameterCount: Int) {
            assertTrue(
                name + " 应保留原 suspend JVM 参数形态",
                declared.any { it.name == name && it.parameterCount == parameterCount }
            )
        }
        assertSuspendShape("ensureInitialSeedIfNeeded", 1)
        assertSuspendShape("ensureTermExistsAndUpdateCalendar", 5)
        assertSuspendShape("selectCurrentTerm", 2)
        assertSuspendShape("updateTermStartDate", 3)
        assertSuspendShape("updatePeriodTime", 2)
        assertSuspendShape("saveCalendarOverride", 2)
        assertSuspendShape("deleteCalendarOverride", 2)
        assertSuspendShape("applyInterchangeCalendarMetadata", 5)
        assertSuspendShape("saveImportedGrades", 5)
        assertSuspendShape("saveStudentProfile", 2)
        assertSuspendShape("saveImportedExams", 6)
        assertSuspendShape("setGpaPrivacyShield", 2)
        assertSuspendShape("getExamSchedulesSnapshot", 1)
    }

    @Test
    fun w005a_term_grades_exam_behavior_snapshot() = runBlocking {
        val notifications = mutableListOf<String>()
        repo.onScheduleOrPolicyChangedListener = { reason -> notifications += reason }
        val termId = "2026-2027-1"

        repo.selectCurrentTerm(termId)
        repo.updateTermStartDate(termId, LocalDate.of(2026, 8, 31))
        repo.updatePeriodTime(
            PeriodTime(
                id = "pt_1",
                timeTableId = HautMockSeedData.DEFAULT_TIMETABLE_ID,
                periodNo = 1,
                startLocalTime = LocalTime.of(8, 0),
                endLocalTime = LocalTime.of(8, 45),
                sectionLabel = "上午"
            )
        )
        repo.saveCalendarOverride(
            CalendarOverride(
                id = "cal_keep",
                termId = termId,
                date = LocalDate.of(2026, 10, 1),
                overrideType = CalendarOverrideType.HOLIDAY_NO_CLASS,
                title = "国庆假期"
            )
        )
        repo.saveCalendarOverride(
            CalendarOverride(
                id = "cal_delete",
                termId = termId,
                date = LocalDate.of(2026, 10, 2),
                overrideType = CalendarOverrideType.HOLIDAY_NO_CLASS,
                title = "待删除假期"
            )
        )
        repo.applyInterchangeCalendarMetadata(
            termId = termId,
            semesterStart = null,
            totalWeeks = 22,
            sectionTimes = listOf(
                PeriodTime(
                    id = "pt_interchange_2",
                    timeTableId = HautMockSeedData.DEFAULT_TIMETABLE_ID,
                    periodNo = 2,
                    startLocalTime = LocalTime.of(9, 0),
                    endLocalTime = LocalTime.of(9, 45),
                    sectionLabel = "上午"
                )
            )
        )
        repo.saveImportedGrades(
            attempts = listOf(
                GradeAttempt(
                    id = "ga_characterization",
                    termId = termId,
                    academicYear = "2026-2027",
                    termNumber = 1,
                    courseKey = "CS101",
                    name = "特征快照课程",
                    category = CourseCategory.COMPULSORY,
                    credits = BigDecimal("3.50"),
                    rawGrade = "91",
                    numericScore = BigDecimal("91"),
                    gradePoint = BigDecimal("4.10"),
                    attemptType = AttemptType.NORMAL,
                    passed = true,
                    source = CourseSource.OFFICIAL_JW,
                    syncedAt = Instant.parse("2026-11-01T00:00:00Z")
                )
            ),
            officialGpa = BigDecimal("4.20"),
            officialNote = "官方快照说明",
            replaceExisting = true
        )
        repo.saveStudentProfile(
            StudentProfile(
                id = "stu_characterization",
                schoolId = "2024160101XX",
                displayName = "快照学生",
                major = "计算机科学与技术",
                enrollmentYear = 2024
            )
        )
        repo.setGpaPrivacyShield(true)
        repo.saveImportedExams(
            exams = listOf(
                ExamScheduleItem(
                    id = "exam_characterization",
                    termId = termId,
                    courseCode = "CS101",
                    courseName = "特征快照课程",
                    examBatchName = "期末考试",
                    examStageLabel = "期末集中考",
                    examFormat = "闭卷",
                    rawTimeText = "2026-06-20 09:00-11:00",
                    examDate = LocalDate.of(2026, 6, 20),
                    startTime = LocalTime.of(9, 0),
                    endTime = LocalTime.of(11, 0),
                    rawRoom = "1号教学楼1204",
                    seatNumber = "12",
                    campusName = "莲花街校区",
                    syncedAt = Instant.parse("2026-11-01T00:00:00Z")
                )
            ),
            replaceExisting = true,
            targetTermId = termId
        )
        repo.deleteCalendarOverride("cal_delete")

        val term = repo.termsFlow.first().single { it.id == termId }
        val currentTerm = repo.currentTermFlow.first()
        val periods = repo.periodTimesFlow.first().sortedBy { it.periodNo }
        val overrides = repo.calendarOverridesFlow.first().sortedBy { it.id }
        val grades = repo.gradeAttemptsFlow.first()
        val profile = repo.studentProfileFlow.first()
        val exams = repo.examSchedulesFlow.first()
        val prefs = repo.preferencesFlow.first()
        val snapshot = buildString {
            append("term=").append(
                listOf(
                    term.id,
                    term.academicYear,
                    term.termNumber.toString(),
                    term.startDate.toString(),
                    term.weekCount.toString(),
                    term.timeTableId,
                    term.current.toString()
                ).joinToString("|")
            ).append('\n')
            append("current=").append(currentTerm?.id).append('\n')
            append("periods=").append(
                periods.joinToString(";") { item ->
                    item.id + ":" + item.periodNo + ":" + item.startLocalTime + ":" + item.endLocalTime + ":" + item.sectionLabel
                }
            ).append('\n')
            append("overrides=").append(
                overrides.joinToString(";") { item ->
                    item.id + ":" + item.date + ":" + item.overrideType + ":" + item.title
                }
            ).append('\n')
            append("grades=").append(
                grades.joinToString(";") { item ->
                    item.id + ":" + item.courseKey + ":" + item.credits.toPlainString() + ":" + item.rawGrade + ":" + item.gradePoint?.toPlainString()
                }
            ).append('\n')
            append("profile=").append(
                listOf(
                    profile?.id,
                    profile?.schoolId,
                    profile?.displayName,
                    profile?.major,
                    profile?.enrollmentYear?.toString()
                ).joinToString("|")
            ).append('\n')
            append("officialGpa=").append(prefs.officialGpaValuePlain).append('/').append(prefs.officialGpaNote).append('\n')
            append("privacy=").append(prefs.gpaPrivacyShieldEnabled).append('\n')
            append("exams=").append(
                exams.joinToString(";") { item ->
                    item.id + ":" + item.termId + ":" + item.courseCode + ":" + item.examDate + ":" + item.startTime + ":" + item.endTime + ":" + item.originTermId
                }
            ).append('\n')
            append("notifications=").append(notifications.joinToString(","))
        }

        assertEquals(
            """
            term=2026-2027-1|2026-2027|1|2026-08-31|22|haut_lianhuajie_default|true
            current=2026-2027-1
            periods=pt_1:1:08:00:08:45:上午;pt_interchange_2:2:09:00:09:45:上午
            overrides=cal_keep:2026-10-01:HOLIDAY_NO_CLASS:国庆假期
            grades=ga_characterization:CS101:3.50:91:4.10
            profile=stu_characterization|2024160101XX|快照学生|计算机科学与技术|2024
            officialGpa=4.20/官方快照说明
            privacy=true
            exams=exam_characterization:2026-2027-1:CS101:2026-06-20:09:00:11:00:2026-2027-1
            notifications=TERM_CHANGED,TERM_START_DATE_CHANGED,PERIOD_TIME_CHANGED,CALENDAR_OVERRIDE_SAVED,CALENDAR_OVERRIDE_SAVED,INTERCHANGE_CALENDAR_APPLIED,EXAMS_IMPORTED,CALENDAR_OVERRIDE_DELETED
            """.trimIndent(),
            snapshot
        )
    }

    @Test
    fun w005a_grade_replace_and_official_gpa_clear_semantics() = runBlocking {
        fun attempt(id: String, key: String) = GradeAttempt(
            id = id,
            termId = "2026-2027-1",
            academicYear = "2026-2027",
            termNumber = 1,
            courseKey = key,
            name = "成绩" + key,
            credits = BigDecimal("2.0"),
            rawGrade = "80",
            syncedAt = Instant.parse("2026-11-01T00:00:00Z")
        )
        repo.saveImportedGrades(
            attempts = listOf(attempt("ga_old", "OLD")),
            officialGpa = BigDecimal("3.30"),
            officialNote = "旧 GPA",
            replaceExisting = true
        )
        repo.saveImportedGrades(
            attempts = listOf(attempt("ga_new", "NEW")),
            officialGpa = null,
            officialNote = null,
            replaceExisting = true
        )

        assertEquals(listOf("ga_new"), repo.gradeAttemptsFlow.first().map { it.id })
        val prefs = repo.preferencesFlow.first()
        assertEquals(null, prefs.officialGpaValuePlain)
        assertEquals(null, prefs.officialGpaNote)
        assertEquals(true, prefs.hasCompletedOnboarding)
    }

    @Test
    fun w005a_incomplete_target_term_exam_sync_semantics() = runBlocking {
        fun exam(id: String, termId: String) = ExamScheduleItem(
            id = id,
            termId = termId,
            courseCode = "CS" + id,
            courseName = "考试" + id,
            examBatchName = "期末考试",
            examStageLabel = "期末集中考",
            examFormat = "闭卷",
            rawTimeText = "2026-06-20 09:00-11:00",
            examDate = LocalDate.of(2026, 6, 20),
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            rawRoom = "1号教学楼1204",
            syncedAt = Instant.parse("2026-11-01T00:00:00Z")
        )
        val targetTerm = "2026-2027-1"
        val completedTerm = "2025-2026-2"
        dao.upsertExamSchedules(listOf(
            exam("existing_target", targetTerm).toEntity(),
            exam("existing_completed", completedTerm).toEntity()
        ))

        repo.saveImportedExams(
            exams = listOf(
                exam("incoming_target", targetTerm),
                exam("incoming_completed", completedTerm)
            ),
            replaceExisting = true,
            targetTermId = targetTerm,
            targetTermComplete = false,
            completedTermIds = setOf(completedTerm)
        )

        val rows = dao.getExamSchedulesSnapshot().associateBy { it.id }
        assertEquals(
            setOf("existing_target", "incoming_completed"),
            rows.keys
        )
        assertEquals(completedTerm, rows.getValue("incoming_completed").originTermId)
    }
}
