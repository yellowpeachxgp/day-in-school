package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.edu.haut.campus.core.academic.HautAcademicEndpoints
import cn.edu.haut.campus.core.academic.HautLiveAcademicClient
import cn.edu.haut.campus.core.academic.HautLiveSyncResult
import cn.edu.haut.campus.core.campusservices.OnlineUtilityRoomSnapshot
import cn.edu.haut.campus.core.campusservices.SchoolWebSessionManager
import cn.edu.haut.campus.core.campusservices.UtilityAccountReadResult
import cn.edu.haut.campus.core.campusservices.UtilityAccountSecureStore
import cn.edu.haut.campus.core.campusservices.UtilityDailySample
import cn.edu.haut.campus.core.campusservices.UtilitySamplingPolicy
import cn.edu.haut.campus.core.campusservices.WanxiaoUtilityClient
import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.domain.ImportDiffEngine
import cn.edu.haut.campus.core.domain.ReminderRevision
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingAlias
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.FocusSessionDomain
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.ImportAudit
import cn.edu.haut.campus.core.model.ImportChangeType
import cn.edu.haut.campus.core.model.ImportCourseCandidate
import cn.edu.haut.campus.core.model.ImportDiffPreview
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.ReminderEventOverride
import cn.edu.haut.campus.core.model.ReminderGlobalConfig
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.UtilityMeterDomain
import cn.edu.haut.campus.core.model.UtilityOnlineRoomDomain
import cn.edu.haut.campus.core.model.UtilityReadingDomain
import cn.edu.haut.campus.core.model.WidgetInstanceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val Context.appDataStore by preferencesDataStore(name = "haut_campus_prefs")

data class AppPreferencesState(
    val gpaPrivacyShieldEnabled: Boolean = false,
    val useDynamicColor: Boolean = false,
    val timelineZoomScale: Float = 2.0f,
    val officialGpaValuePlain: String? = null,
    val officialGpaNote: String? = null,
    val hasSeededInitialMockData: Boolean = false,
    val hasCompletedOnboarding: Boolean = false,
    val autoSyncEnabled: Boolean = true,
    val autoCheckUpdateEnabled: Boolean = true,
    val savedStudentId: String? = null,
    val hasSavedCredentials: Boolean = false,
    val syncXnm: String = "2025",
    val syncXqm: String = "12",
    val lastAutoSyncEpochMillis: Long? = null,
    val customChannelUrls: Map<String, String> = emptyMap(),
    val customClubGroupNumbers: Map<String, String> = emptyMap(),
    val reminderConfig: ReminderGlobalConfig = ReminderGlobalConfig(),
    val reminderPolicyRevision: String = "v1",
    val lastReminderReconcileEpochMillis: Long? = null,
    val lastReminderReconcileSummary: String? = null,
    val reminderDiagnosticLogs: List<String> = emptyList(),
    val utilitiesEnabled: Boolean = true,
    val libraryNativeEnabled: Boolean = true,
    val librarySeatEnabled: Boolean = true,
    val focusEnabled: Boolean = true,
    val showTodayFocusQuickPill: Boolean = true,
    val timetableInterchangeEnabled: Boolean = true,
    val allowUnverifiedUtilityTransport: Boolean = false,
    val lastUtilitySampleEpochMillis: Long? = null,
    val selectedFocusMinutes: Int = 25
)

class CampusRepository private constructor(
    private val appContext: Context,
    private val database: HautDatabase
) {
    private val dao = database.campusDao()
    private val reminderDao = database.reminderDao()
    private val utilityAndFocusDao = database.utilityAndFocusDao()
    private val utilityAccountStore = UtilityAccountSecureStore(appContext)
    private val wanxiaoUtilityClient = WanxiaoUtilityClient()
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var onScheduleOrPolicyChangedListener: (suspend (String) -> Unit)? = null

    private val beforeWipeHooks = ConcurrentHashMap<String, suspend () -> Unit>()

    var onBeforeWipePersonalDataHook: (suspend () -> Unit)?
        get() = beforeWipeHooks["reminder_coordinator_hook"]
        set(value) {
            if (value == null) {
                beforeWipeHooks.remove("reminder_coordinator_hook")
            } else {
                beforeWipeHooks["reminder_coordinator_hook"] = value
            }
        }

    fun registerBeforeWipeHook(key: String, hook: suspend () -> Unit) {
        beforeWipeHooks[key] = hook
    }

    private object PrefKeys {
        val SEEDED = booleanPreferencesKey("seeded_initial_mock_data")
        val CLEAN_BASE_V2 = booleanPreferencesKey("clean_base_dictionary_initialized_v3")
        val TERM_MISMATCH_REPAIRED_V2 = booleanPreferencesKey("term_mismatch_repaired_v2")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("completed_onboarding_guide_v1")
        val GPA_PRIVACY = booleanPreferencesKey("gpa_privacy_shield_enabled")
        val DYNAMIC_COLOR = booleanPreferencesKey("use_material_you_dynamic_color")
        val TIMELINE_SCALE = floatPreferencesKey("timeline_zoom_scale")
        val OFFICIAL_GPA = stringPreferencesKey("official_gpa_value")
        val OFFICIAL_GPA_NOTE = stringPreferencesKey("official_gpa_note")
        val AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_jw_enabled")
        val AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_github_release_enabled")
        val SAVED_STUDENT_ID = stringPreferencesKey("saved_jw_student_id")
        val SAVED_PASSWORD_OBF = stringPreferencesKey("saved_jw_password_local")
        val SAVED_SYNC_XNM = stringPreferencesKey("saved_jw_sync_xnm")
        val SAVED_SYNC_XQM = stringPreferencesKey("saved_jw_sync_xqm")
        val LAST_AUTO_SYNC_MS = longPreferencesKey("last_auto_sync_epoch_millis")
        val CUSTOM_CHANNEL_URLS = stringPreferencesKey("custom_qq_channel_urls_v1")
        val CUSTOM_CLUB_GROUPS = stringPreferencesKey("custom_club_qq_groups_v1")
        val REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        val CLASS_LEAD_MINUTES = intPreferencesKey("class_lead_minutes")
        val CLASS_SNOOZE_DEFAULT_MINUTES = intPreferencesKey("class_snooze_default_minutes")
        val CLASS_SNOOZE_MAX_COUNT = intPreferencesKey("class_snooze_max_count")
        val CLASS_SNOOZE_CUTOFF_MINUTES = intPreferencesKey("class_snooze_cutoff_minutes")
        val EXAM_DAY_BEFORE_ENABLED = booleanPreferencesKey("exam_day_before_enabled")
        val EXAM_HOUR_BEFORE_ENABLED = booleanPreferencesKey("exam_hour_before_enabled")
        val EXAM_SNOOZE_MAX_COUNT = intPreferencesKey("exam_snooze_max_count")
        val PRECISE_REMINDER_PREFERRED = booleanPreferencesKey("precise_reminder_preferred")
        val REMINDER_LOCKSCREEN_PRIVATE = booleanPreferencesKey("reminder_lockscreen_private")
        val REMINDER_QUIET_MODE = stringPreferencesKey("reminder_quiet_mode")
        val REMINDER_POLICY_REVISION = stringPreferencesKey("reminder_policy_revision")
        val LAST_REMINDER_RECONCILE_MS = longPreferencesKey("last_reminder_reconcile_ms")
        val LAST_REMINDER_RECONCILE_SUMMARY = stringPreferencesKey("last_reminder_reconcile_summary")
        val REMINDER_DIAGNOSTIC_LOGS = stringPreferencesKey("reminder_diagnostic_logs_v1")
        val FF_UTILITIES_ENABLED = booleanPreferencesKey("ff_utilities_enabled_v1")
        val FF_LIBRARY_NATIVE_ENABLED = booleanPreferencesKey("ff_library_native_enabled_v1")
        val FF_LIBRARY_SEAT_ENABLED = booleanPreferencesKey("ff_library_seat_enabled_v1")
        val FF_FOCUS_ENABLED = booleanPreferencesKey("ff_focus_enabled_v1")
        val FF_TODAY_FOCUS_PILL = booleanPreferencesKey("ff_show_today_focus_pill_v1")
        val FF_TIMETABLE_INTERCHANGE = booleanPreferencesKey("ff_timetable_interchange_v1")
        val ALLOW_UNVERIFIED_UTILITY_TRANSPORT = booleanPreferencesKey("allow_unverified_utility_transport_v1")
        val LAST_UTILITY_SAMPLE_MS = longPreferencesKey("last_utility_sample_epoch_ms_v1")
        val SELECTED_FOCUS_MINUTES = intPreferencesKey("selected_focus_minutes_v1")
    }

    init {
        registerBeforeWipeHook("campus_services_account_and_web_wipe") {
            runCatching { utilityAccountStore.wipeAll() }
            runCatching { SchoolWebSessionManager.clearAllSchoolWebSessions() }
        }
        repoScope.launch {
            ensureInitialSeedIfNeeded()
        }
    }

    val preferencesFlow: Flow<AppPreferencesState> = appContext.appDataStore.data.map { prefs ->
        val reminderConfig = extractReminderConfig(prefs)
        AppPreferencesState(
            gpaPrivacyShieldEnabled = prefs[PrefKeys.GPA_PRIVACY] ?: false,
            useDynamicColor = prefs[PrefKeys.DYNAMIC_COLOR] ?: false,
            timelineZoomScale = prefs[PrefKeys.TIMELINE_SCALE] ?: 2.0f,
            officialGpaValuePlain = prefs[PrefKeys.OFFICIAL_GPA],
            officialGpaNote = prefs[PrefKeys.OFFICIAL_GPA_NOTE],
            hasSeededInitialMockData = prefs[PrefKeys.SEEDED] ?: false,
            hasCompletedOnboarding = prefs[PrefKeys.ONBOARDING_COMPLETED] ?: false,
            autoSyncEnabled = prefs[PrefKeys.AUTO_SYNC_ENABLED] ?: true,
            autoCheckUpdateEnabled = prefs[PrefKeys.AUTO_CHECK_UPDATE] ?: true,
            savedStudentId = prefs[PrefKeys.SAVED_STUDENT_ID],
            hasSavedCredentials = !prefs[PrefKeys.SAVED_STUDENT_ID].isNullOrBlank() &&
                !prefs[PrefKeys.SAVED_PASSWORD_OBF].isNullOrBlank(),
            syncXnm = prefs[PrefKeys.SAVED_SYNC_XNM] ?: "2025",
            syncXqm = prefs[PrefKeys.SAVED_SYNC_XQM] ?: "12",
            lastAutoSyncEpochMillis = prefs[PrefKeys.LAST_AUTO_SYNC_MS],
            customChannelUrls = decodeKeyValueMap(prefs[PrefKeys.CUSTOM_CHANNEL_URLS]),
            customClubGroupNumbers = decodeKeyValueMap(prefs[PrefKeys.CUSTOM_CLUB_GROUPS]),
            reminderConfig = reminderConfig,
            reminderPolicyRevision = prefs[PrefKeys.REMINDER_POLICY_REVISION]
                ?: ReminderRevision.computePolicyRevision(
                    enabled = reminderConfig.enabled,
                    classLeadMinutes = reminderConfig.classLeadMinutes,
                    maxSnoozes = reminderConfig.classSnoozeMaxCount,
                    cutoffMinutesBeforeStart = reminderConfig.classSnoozeCutoffMinutes,
                    examDayEnabled = reminderConfig.examDayBeforeEnabled,
                    examHourEnabled = reminderConfig.examHourBeforeEnabled
                ),
            lastReminderReconcileEpochMillis = prefs[PrefKeys.LAST_REMINDER_RECONCILE_MS],
            lastReminderReconcileSummary = prefs[PrefKeys.LAST_REMINDER_RECONCILE_SUMMARY],
            reminderDiagnosticLogs = prefs[PrefKeys.REMINDER_DIAGNOSTIC_LOGS]
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                .orEmpty(),
            utilitiesEnabled = prefs[PrefKeys.FF_UTILITIES_ENABLED] ?: true,
            libraryNativeEnabled = prefs[PrefKeys.FF_LIBRARY_NATIVE_ENABLED] ?: true,
            librarySeatEnabled = prefs[PrefKeys.FF_LIBRARY_SEAT_ENABLED] ?: true,
            focusEnabled = prefs[PrefKeys.FF_FOCUS_ENABLED] ?: true,
            showTodayFocusQuickPill = prefs[PrefKeys.FF_TODAY_FOCUS_PILL] ?: true,
            timetableInterchangeEnabled = prefs[PrefKeys.FF_TIMETABLE_INTERCHANGE] ?: true,
            allowUnverifiedUtilityTransport = prefs[PrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT] ?: false,
            lastUtilitySampleEpochMillis = prefs[PrefKeys.LAST_UTILITY_SAMPLE_MS],
            selectedFocusMinutes = (prefs[PrefKeys.SELECTED_FOCUS_MINUTES] ?: 25).coerceIn(1, 180)
        )
    }

    private fun extractReminderConfig(prefs: androidx.datastore.preferences.core.Preferences): ReminderGlobalConfig {
        val quietMode = prefs[PrefKeys.REMINDER_QUIET_MODE] ?: "FOLLOW_SYSTEM"
        return ReminderGlobalConfig(
            enabled = prefs[PrefKeys.REMINDERS_ENABLED] ?: true,
            classLeadMinutes = (prefs[PrefKeys.CLASS_LEAD_MINUTES] ?: 20).coerceIn(5, 60),
            classSnoozeDefaultMinutes = (prefs[PrefKeys.CLASS_SNOOZE_DEFAULT_MINUTES] ?: 5).coerceIn(5, 30),
            classSnoozeMaxCount = (prefs[PrefKeys.CLASS_SNOOZE_MAX_COUNT] ?: 2).coerceIn(0, 5),
            classSnoozeCutoffMinutes = (prefs[PrefKeys.CLASS_SNOOZE_CUTOFF_MINUTES] ?: 2).coerceIn(1, 15),
            examDayBeforeEnabled = prefs[PrefKeys.EXAM_DAY_BEFORE_ENABLED] ?: true,
            examHourBeforeEnabled = prefs[PrefKeys.EXAM_HOUR_BEFORE_ENABLED] ?: true,
            examSnoozeMaxCount = (prefs[PrefKeys.EXAM_SNOOZE_MAX_COUNT] ?: 2).coerceIn(0, 5),
            preciseReminderPreferred = prefs[PrefKeys.PRECISE_REMINDER_PREFERRED] ?: true,
            lockscreenPrivate = prefs[PrefKeys.REMINDER_LOCKSCREEN_PRIVATE] ?: true,
            quietMode = quietMode
        )
    }

    val studentProfileFlow: Flow<StudentProfile?> =
        dao.observeStudentProfile().map { it?.toDomain() }

    val termsFlow: Flow<List<Term>> =
        dao.observeTerms().map { list -> list.map { it.toDomain() } }

    val currentTermFlow: Flow<Term?> =
        termsFlow.map { terms -> terms.firstOrNull { it.current } ?: terms.firstOrNull() }

    val periodTimesFlow: Flow<List<PeriodTime>> =
        dao.observePeriodTimes().map { list -> list.map { it.toDomain() } }

    val calendarOverridesFlow: Flow<List<CalendarOverride>> =
        dao.observeCalendarOverrides().map { list -> list.map { it.toDomain() } }

    val coursesFlow: Flow<List<Course>> =
        dao.observeCourses().map { list -> list.map { it.toDomain() } }

    val rulesFlow: Flow<List<CourseScheduleRule>> =
        dao.observeRules().map { list -> list.map { it.toDomain() } }

    val occurrenceOverridesFlow: Flow<List<OccurrenceOverride>> =
        dao.observeOccurrenceOverrides().map { list -> list.map { it.toDomain() } }

    val gradeAttemptsFlow: Flow<List<GradeAttempt>> =
        dao.observeGradeAttempts().map { list -> list.map { it.toDomain() } }

    val buildingsFlow: Flow<List<Building>> =
        dao.observeBuildings().map { list -> list.map { it.toDomain() } }

    val buildingAliasesFlow: Flow<List<BuildingAlias>> =
        dao.observeBuildingAliases().map { list -> list.map { it.toDomain() } }

    val widgetConfigsFlow: Flow<List<WidgetInstanceConfig>> =
        dao.observeWidgetConfigs().map { list -> list.map { it.toDomain() } }

    val importAuditsFlow: Flow<List<ImportAudit>> =
        dao.observeImportAudits().map { list -> list.map { it.toDomain() } }

    val examSchedulesFlow: Flow<List<ExamScheduleItem>> =
        dao.observeExamSchedules().map { list -> list.map { it.toDomain() } }

    val activeReminderTasksFlow: Flow<List<ReminderTaskDomain>> =
        reminderDao.observeActiveTasks().map { list -> list.map { it.toDomain() } }

    val reminderEventOverridesFlow: Flow<List<ReminderEventOverride>> =
        reminderDao.observeEventPrefs().map { list -> list.map { it.toDomain() } }

    fun observeReminderTasksInWindow(windowStartMs: Long, windowEndMs: Long): Flow<List<ReminderTaskDomain>> =
        reminderDao.observeTasksInEventWindow(windowStartMs, windowEndMs).map { list ->
            list.map { it.toDomain() }
        }

    fun reminderDao(): ReminderDao = reminderDao
    fun utilityAndFocusDao(): UtilityAndFocusDao = utilityAndFocusDao
    fun utilityAccountSecureStore(): UtilityAccountSecureStore = utilityAccountStore

    val utilityDailySamplesFlow: Flow<List<UtilityDailySample>> =
        utilityAndFocusDao.observeDailySamples().map { list -> list.map { it.toDomain() } }

    val utilityOnlineRoomsFlow: Flow<List<UtilityOnlineRoomDomain>> =
        utilityAndFocusDao.observeOnlineRooms().map { list -> list.map { it.toDomain() } }

    val utilityMetersFlow: Flow<List<UtilityMeterDomain>> =
        utilityAndFocusDao.observeMeters().map { list -> list.map { it.toDomain() } }

    val utilityReadingsFlow: Flow<List<UtilityReadingDomain>> =
        utilityAndFocusDao.observeReadings().map { list -> list.map { it.toDomain() } }

    val activeFocusSessionFlow: Flow<FocusSessionDomain?> =
        utilityAndFocusDao.observeActiveRunningFocusSession().map { it?.toDomain() }

    val completedFocusSessionsFlow: Flow<List<FocusSessionDomain>> =
        utilityAndFocusDao.observeCompletedFocusSessions().map { list -> list.map { it.toDomain() } }

    private suspend fun notifyScheduleOrPolicyChanged(reason: String) {
        runCatching { onScheduleOrPolicyChangedListener?.invoke(reason) }
    }

    /**
     * 首次启动仅初始化公开的校历学期、1–10 节作息时间表与莲花街校区楼宇字典，
     * 绝不自动塞入模拟课程或模拟成绩，确保用户首次打开直接进入干净的欢迎引导页。
     */
    suspend fun ensureInitialSeedIfNeeded() {
        val prefs = appContext.appDataStore.data.first()
        if (prefs[PrefKeys.CLEAN_BASE_V2] != true) {
            database.withTransaction {
                if (prefs[PrefKeys.ONBOARDING_COMPLETED] != true) {
                    dao.clearStudentProfile()
                    dao.clearCourses()
                    dao.clearRules()
                    dao.clearOccurrenceOverrides()
                    dao.clearGradeAttempts()
                }
                seedBaseCampusDictionaryInternal()
            }
            appContext.appDataStore.edit {
                it[PrefKeys.SEEDED] = true
                it[PrefKeys.CLEAN_BASE_V2] = true
            }
        }
        if (prefs[PrefKeys.TERM_MISMATCH_REPAIRED_V2] != true) {
            database.withTransaction {
                val existingTerms = dao.observeTerms().first().map { it.toDomain() }
                val existingById = existingTerms.associateBy { it.id }
                val hasCurrent = existingTerms.any { it.current }
                val mergedTerms = HautMockSeedData.defaultTerms.map { seedTerm ->
                    val prev = existingById[seedTerm.id]
                    if (prev != null) {
                        val repairedStart = if (
                            prev.id == "2026-2027-1" &&
                            prev.startDate == LocalDate.of(2026, 9, 7)
                        ) {
                            LocalDate.of(2026, 8, 31)
                        } else {
                            prev.startDate
                        }
                        prev.copy(startDate = repairedStart).toEntity()
                    } else {
                        seedTerm.copy(current = if (hasCurrent) false else seedTerm.current).toEntity()
                    }
                }
                dao.upsertTerms(mergedTerms)

                // 修复旧版本因 currentTerm 写死 2026-2027-1 导致 2025-2026-2 同步课表被错存到 2026-2027-1 的问题
                val savedXnm = prefs[PrefKeys.SAVED_SYNC_XNM] ?: "2025"
                val savedXqm = prefs[PrefKeys.SAVED_SYNC_XQM] ?: "12"
                val expectedTermId = HautAcademicEndpoints.toTermId(savedXnm, savedXqm)
                val allRules = dao.getRulesSnapshot()
                val rulesIn26 = allRules.filter { it.termId == "2026-2027-1" && it.id.startsWith("r_imp_") }
                val rulesInExpected = allRules.filter { it.termId == expectedTermId }
                if (expectedTermId != "2026-2027-1" && rulesIn26.isNotEmpty() && rulesInExpected.isEmpty()) {
                    dao.upsertRules(rulesIn26.map { it.copy(termId = expectedTermId) })
                    dao.setCurrentTerm(expectedTermId)
                }
            }
            appContext.appDataStore.edit {
                it[PrefKeys.TERM_MISMATCH_REPAIRED_V2] = true
                it.remove(PrefKeys.LAST_AUTO_SYNC_MS)
            }
        }
    }

    suspend fun completeOnboarding() {
        appContext.appDataStore.edit {
            it[PrefKeys.ONBOARDING_COMPLETED] = true
            it[PrefKeys.CLEAN_BASE_V2] = true
        }
    }

    suspend fun resetOnboardingForGuide() {
        appContext.appDataStore.edit {
            it[PrefKeys.ONBOARDING_COMPLETED] = false
        }
    }

    suspend fun resetToDemoSeedData() {
        database.withTransaction {
            dao.clearCourses()
            dao.clearRules()
            dao.clearOccurrenceOverrides()
            dao.clearGradeAttempts()
            seedDemoDataInternal()
        }
        appContext.appDataStore.edit {
            it[PrefKeys.SEEDED] = true
            it[PrefKeys.CLEAN_BASE_V2] = true
            it[PrefKeys.ONBOARDING_COMPLETED] = true
            it.remove(PrefKeys.OFFICIAL_GPA)
            it.remove(PrefKeys.OFFICIAL_GPA_NOTE)
        }
        notifyScheduleOrPolicyChanged("RESET_DEMO_SEED_DATA")
    }

    private suspend fun seedBaseCampusDictionaryInternal() {
        dao.upsertTerms(HautMockSeedData.defaultTerms.map { it.toEntity() })
        dao.upsertPeriodTimes(HautMockSeedData.defaultPeriodTimes.map { it.toEntity() })
        dao.upsertBuildings(HautMockSeedData.lianhuajieBuildings.map { it.toEntity() })
        dao.upsertBuildingAliases(HautMockSeedData.defaultBuildingAliases.map { it.toEntity() })
        dao.upsertCalendarOverrides(HautMockSeedData.defaultCalendarOverrides.map { it.toEntity() })
    }

    private suspend fun seedDemoDataInternal() {
        seedBaseCampusDictionaryInternal()
        dao.upsertStudentProfile(HautMockSeedData.defaultStudent.toEntity())
        dao.upsertCourses(HautMockSeedData.defaultCourses.map { it.toEntity() })
        dao.upsertRules(HautMockSeedData.defaultRules.map { it.toEntity() })
        dao.upsertOccurrenceOverrides(HautMockSeedData.defaultOccurrenceOverrides.map { it.toEntity() })
        dao.upsertGradeAttempts(HautMockSeedData.defaultGradeAttempts.map { it.toEntity() })
    }

    suspend fun ensureTermExistsAndUpdateCalendar(
        termId: String,
        semesterStart: LocalDate? = null,
        totalWeeks: Int? = null,
        setCurrent: Boolean = false
    ) {
        val existingTerms = dao.observeTerms().first().map { it.toDomain() }
        val existing = existingTerms.firstOrNull { it.id == termId }
        val seedDefault = HautMockSeedData.defaultTerms.firstOrNull { it.id == termId }
        val parts = termId.split("-")
        val academicYear = if (parts.size >= 2) "${parts[0]}-${parts[1]}" else "2026-2027"
        val termNumber = parts.getOrNull(2)?.toIntOrNull() ?: 1
        val defaultStart = seedDefault?.startDate ?: if (termNumber == 1) {
            LocalDate.of(parts.firstOrNull()?.toIntOrNull() ?: 2026, 9, 1)
        } else {
            LocalDate.of(parts.getOrNull(1)?.toIntOrNull() ?: 2026, 3, 2)
        }
        val updatedTerm = Term(
            id = termId,
            academicYear = existing?.academicYear ?: academicYear,
            termNumber = existing?.termNumber ?: termNumber,
            startDate = semesterStart ?: existing?.startDate ?: defaultStart,
            weekStartDay = existing?.weekStartDay ?: DayOfWeek.MONDAY,
            weekCount = (totalWeeks ?: existing?.weekCount ?: seedDefault?.weekCount ?: 20).coerceIn(1, 30),
            timeTableId = existing?.timeTableId ?: HautMockSeedData.DEFAULT_TIMETABLE_ID,
            current = if (setCurrent) true else (existing?.current ?: false)
        )
        dao.upsertTerms(listOf(updatedTerm.toEntity()))
        if (setCurrent) {
            dao.setCurrentTerm(termId)
        }
    }

    suspend fun selectCurrentTerm(termId: String) {
        ensureTermExistsAndUpdateCalendar(termId = termId, setCurrent = true)
        val (xnm, xqm) = HautAcademicEndpoints.fromTermId(termId)
        appContext.appDataStore.edit { prefs ->
            prefs[PrefKeys.SAVED_SYNC_XNM] = xnm
            prefs[PrefKeys.SAVED_SYNC_XQM] = xqm
        }
        notifyScheduleOrPolicyChanged("TERM_CHANGED")
    }

    suspend fun updateTermStartDate(termId: String, newStartDate: LocalDate) {
        val terms = dao.observeTerms().first().map { it.toDomain() }
        val target = terms.firstOrNull { it.id == termId } ?: return
        dao.upsertTerms(listOf(target.copy(startDate = newStartDate).toEntity()))
        notifyScheduleOrPolicyChanged("TERM_START_DATE_CHANGED")
    }

    suspend fun updatePeriodTime(periodTime: PeriodTime) {
        dao.upsertPeriodTimes(listOf(periodTime.toEntity()))
        notifyScheduleOrPolicyChanged("PERIOD_TIME_CHANGED")
    }

    /**
     * 用户手动编辑课程属性与排课规则：自动标记 userModified = true，确保下次教务同步时不会静默覆盖。
     */
    suspend fun saveUserCourseEdit(
        course: Course,
        rule: CourseScheduleRule
    ) {
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val resolvedBuilding = ClassroomBuildingResolver.resolve(rule.rawRoom, buildings, aliases)
        val verifiedBuildingId = if (resolvedBuilding.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED) {
            resolvedBuilding.building?.id
        } else {
            rule.buildingId
        }

        database.withTransaction {
            dao.upsertCourse(course.copy(userModified = true).toEntity())
            dao.upsertRule(
                rule.copy(
                    buildingId = verifiedBuildingId,
                    userModified = true
                ).toEntity()
            )
        }
        notifyScheduleOrPolicyChanged("USER_COURSE_EDIT")
    }

    suspend fun addManualCourseWithRule(
        termId: String,
        name: String,
        teacher: String?,
        rawRoom: String,
        weekday: DayOfWeek,
        startPeriod: Int,
        endPeriod: Int,
        weeks: Set<Int>,
        colorArgb: Long
    ) {
        val courseId = "c_manual_${UUID.randomUUID().toString().take(8)}"
        val ruleId = "r_manual_${UUID.randomUUID().toString().take(8)}"
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val resolved = ClassroomBuildingResolver.resolve(rawRoom, buildings, aliases)

        val course = Course(
            id = courseId,
            name = name,
            teacher = teacher,
            colorArgb = colorArgb,
            source = CourseSource.MANUAL,
            userModified = true,
            lastSyncAt = Instant.now()
        )
        val rule = CourseScheduleRule(
            id = ruleId,
            courseId = courseId,
            termId = termId,
            weekday = weekday,
            startPeriod = startPeriod,
            endPeriod = endPeriod,
            weeks = weeks,
            rawRoom = rawRoom,
            buildingId = resolved.building?.id,
            userModified = true
        )
        database.withTransaction {
            dao.upsertCourse(course.toEntity())
            dao.upsertRule(rule.toEntity())
        }
        notifyScheduleOrPolicyChanged("MANUAL_COURSE_ADDED")
    }

    suspend fun saveOccurrenceOverride(override: OccurrenceOverride) {
        dao.upsertOccurrenceOverride(override.toEntity())
        notifyScheduleOrPolicyChanged("OCCURRENCE_OVERRIDE_SAVED")
    }

    suspend fun saveCalendarOverride(override: CalendarOverride) {
        dao.upsertCalendarOverrides(listOf(override.toEntity()))
        notifyScheduleOrPolicyChanged("CALENDAR_OVERRIDE_SAVED")
    }

    suspend fun deleteCalendarOverride(id: String) {
        dao.deleteCalendarOverrideById(id)
        notifyScheduleOrPolicyChanged("CALENDAR_OVERRIDE_DELETED")
    }

    suspend fun confirmBuildingAliasForRoom(rawRoom: String, buildingId: String) {
        val alias = BuildingAlias(
            id = "al_user_${rawRoom.hashCode()}",
            buildingId = buildingId,
            rawNameOrPattern = rawRoom.trim(),
            confidence = 1.0f,
            verified = true
        )
        dao.upsertBuildingAliases(listOf(alias.toEntity()))
    }

    suspend fun previewCourseImportDiff(
        termId: String,
        source: CourseSource,
        candidates: List<ImportCourseCandidate>
    ): ImportDiffPreview {
        val existingCourses = dao.getCoursesSnapshot().map { it.toDomain() }
        val existingRules = dao.getRulesSnapshot().map { it.toDomain() }
        return ImportDiffEngine.computeDiff(
            termId = termId,
            source = source,
            existingCourses = existingCourses,
            existingRules = existingRules,
            incomingCandidates = candidates
        )
    }

    /**
     * 确认提交课表导入结果。
     * @param overwriteUserConflicts 若为 false（默认），所有 CONFLICT_USER_MODIFIED 项均保留用户本地修改。
     * @param setAsCurrentTerm 若为 true（默认），同时将目标学期切换为当前激活学期。
     */
    suspend fun commitCourseImportDiff(
        preview: ImportDiffPreview,
        overwriteUserConflicts: Boolean = false,
        setAsCurrentTerm: Boolean = true
    ) {
        val palette = listOf(
            0xFF0072BC, 0xFF0284C7, 0xFF0D9488, 0xFF16A34A,
            0xFF4F46E5, 0xFF7C3AED, 0xFFD97706, 0xFFE11D48
        )
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val now = Instant.now()

        ensureTermExistsAndUpdateCalendar(
            termId = preview.termId,
            setCurrent = setAsCurrentTerm
        )

        database.withTransaction {
            var colorIdx = 0
            for (entry in preview.entries) {
                val candidate = entry.incoming
                when (entry.changeType) {
                    ImportChangeType.ADDED -> {
                        if (candidate != null) {
                            val courseId = "c_imp_${UUID.randomUUID().toString().take(8)}"
                            val ruleId = "r_imp_${UUID.randomUUID().toString().take(8)}"
                            val resolved = ClassroomBuildingResolver.resolve(candidate.rawRoom, buildings, aliases)
                            val course = Course(
                                id = courseId,
                                externalId = candidate.externalId,
                                name = candidate.courseName,
                                teacher = candidate.teacher,
                                colorArgb = palette[colorIdx++ % palette.size],
                                credit = candidate.credit,
                                source = preview.source,
                                userModified = false,
                                lastSyncAt = now
                            )
                            val rule = CourseScheduleRule(
                                id = ruleId,
                                courseId = courseId,
                                termId = preview.termId,
                                weekday = candidate.weekday,
                                startPeriod = candidate.startPeriod,
                                endPeriod = candidate.endPeriod,
                                weeks = candidate.weeks,
                                rawRoom = candidate.rawRoom,
                                buildingId = if (resolved.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED) {
                                    resolved.building?.id
                                } else null,
                                userModified = false
                            )
                            dao.upsertCourse(course.toEntity())
                            dao.upsertRule(rule.toEntity())
                        }
                    }
                    ImportChangeType.MODIFIED -> {
                        val existingCourse = entry.existingCourse
                        val existingRule = entry.existingRule
                        if (candidate != null && existingCourse != null && existingRule != null) {
                            val resolved = ClassroomBuildingResolver.resolve(candidate.rawRoom, buildings, aliases)
                            dao.upsertCourse(
                                existingCourse.copy(
                                    name = candidate.courseName,
                                    teacher = candidate.teacher,
                                    credit = candidate.credit ?: existingCourse.credit,
                                    lastSyncAt = now
                                ).toEntity()
                            )
                            dao.upsertRule(
                                existingRule.copy(
                                    weeks = candidate.weeks,
                                    rawRoom = candidate.rawRoom,
                                    buildingId = resolved.building?.id ?: existingRule.buildingId
                                ).toEntity()
                            )
                        }
                    }
                    ImportChangeType.CONFLICT_USER_MODIFIED -> {
                        val existingCourse = entry.existingCourse
                        val existingRule = entry.existingRule
                        if (overwriteUserConflicts && candidate != null && existingCourse != null && existingRule != null) {
                            dao.upsertCourse(
                                existingCourse.copy(
                                    name = candidate.courseName,
                                    teacher = candidate.teacher,
                                    userModified = false,
                                    lastSyncAt = now
                                ).toEntity()
                            )
                            dao.upsertRule(
                                existingRule.copy(
                                    weeks = candidate.weeks,
                                    rawRoom = candidate.rawRoom,
                                    userModified = false
                                ).toEntity()
                            )
                        }
                        // 默认保留用户本地修改，不做覆盖
                    }
                    ImportChangeType.REMOVED_FROM_REMOTE -> {
                        val existingRule = entry.existingRule
                        if (existingRule != null && (!existingRule.userModified || overwriteUserConflicts)) {
                            dao.deleteRuleById(existingRule.id)
                        }
                    }
                    ImportChangeType.UNCHANGED -> Unit
                }
            }

            val removedCount = preview.entries.count { it.changeType == ImportChangeType.REMOVED_FROM_REMOTE }
            dao.insertImportAudit(
                ImportAudit(
                    id = "aud_${UUID.randomUUID().toString().take(8)}",
                    termId = preview.termId,
                    importSource = preview.source,
                    importedAt = now,
                    rowCount = preview.entries.size,
                    conflictCount = preview.conflictUserEditCount,
                    outcome = "新增${preview.addedCount}/更新${preview.modifiedCount}/移除${removedCount}/保留用户修改${preview.conflictUserEditCount}"
                ).toEntity()
            )
        }
        appContext.appDataStore.edit {
            it[PrefKeys.ONBOARDING_COMPLETED] = true
            it[PrefKeys.CLEAN_BASE_V2] = true
            if (setAsCurrentTerm) {
                val (xnm, xqm) = HautAcademicEndpoints.fromTermId(preview.termId)
                it[PrefKeys.SAVED_SYNC_XNM] = xnm
                it[PrefKeys.SAVED_SYNC_XQM] = xqm
            }
        }
        notifyScheduleOrPolicyChanged("COURSE_IMPORT_COMMITTED")
    }

    suspend fun saveImportedGrades(
        attempts: List<GradeAttempt>,
        officialGpa: BigDecimal? = null,
        officialNote: String? = null,
        replaceExisting: Boolean = false
    ) {
        database.withTransaction {
            if (replaceExisting) {
                dao.clearGradeAttempts()
            }
            dao.upsertGradeAttempts(attempts.map { it.toEntity() })
        }
        appContext.appDataStore.edit { prefs ->
            prefs[PrefKeys.ONBOARDING_COMPLETED] = true
            prefs[PrefKeys.CLEAN_BASE_V2] = true
            if (officialGpa != null) {
                prefs[PrefKeys.OFFICIAL_GPA] = officialGpa.toPlainString()
                prefs[PrefKeys.OFFICIAL_GPA_NOTE] = officialNote ?: "正方教务系统返回平均学分绩点"
            } else {
                prefs.remove(PrefKeys.OFFICIAL_GPA)
                prefs.remove(PrefKeys.OFFICIAL_GPA_NOTE)
            }
        }
    }

    suspend fun saveStudentProfile(profile: StudentProfile) {
        dao.upsertStudentProfile(profile.toEntity())
    }

    suspend fun saveImportedExams(
        exams: List<ExamScheduleItem>,
        replaceExisting: Boolean = true
    ) {
        database.withTransaction {
            if (replaceExisting) {
                dao.clearExamSchedules()
            }
            dao.upsertExamSchedules(exams.map { it.toEntity() })
        }
        notifyScheduleOrPolicyChanged("EXAMS_IMPORTED")
    }

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        appContext.appDataStore.edit { it[PrefKeys.AUTO_SYNC_ENABLED] = enabled }
    }

    suspend fun saveAuthorizedSyncCredentials(
        studentId: String,
        passwordPlain: String,
        xnm: String,
        xqm: String
    ) {
        val encoded = java.util.Base64.getEncoder().encodeToString(
            passwordPlain.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        )
        appContext.appDataStore.edit { prefs ->
            prefs[PrefKeys.SAVED_STUDENT_ID] = studentId.trim()
            prefs[PrefKeys.SAVED_PASSWORD_OBF] = encoded
            prefs[PrefKeys.SAVED_SYNC_XNM] = xnm
            prefs[PrefKeys.SAVED_SYNC_XQM] = xqm
            prefs[PrefKeys.LAST_AUTO_SYNC_MS] = System.currentTimeMillis()
        }
    }

    /**
     * 应用全部实时教务同步数据（学籍名片 + 课表差异合并 + 成绩明细 + 期初/期中/期末全部考试安排）。
     */
    suspend fun applyLiveSyncData(
        syncData: HautLiveSyncResult,
        overwriteUserConflicts: Boolean = false
    ) {
        syncData.studentProfile?.let { profile ->
            saveStudentProfile(profile)
        }
        if (syncData.gradeAttempts.isNotEmpty()) {
            saveImportedGrades(
                attempts = syncData.gradeAttempts,
                replaceExisting = true
            )
        }
        if (syncData.examItems.isNotEmpty()) {
            saveImportedExams(
                exams = syncData.examItems,
                replaceExisting = true
            )
        }
        for ((sibTermId, sibCandidates) in syncData.additionalTermTimetables) {
            if (sibTermId == syncData.termId) continue
            val sibCal = syncData.additionalTermCalendars[sibTermId]
            ensureTermExistsAndUpdateCalendar(
                termId = sibTermId,
                semesterStart = sibCal?.first,
                totalWeeks = sibCal?.second,
                setCurrent = false
            )
            val sibDiff = previewCourseImportDiff(
                termId = sibTermId,
                source = CourseSource.OFFICIAL_JW,
                candidates = sibCandidates
            )
            commitCourseImportDiff(
                preview = sibDiff,
                overwriteUserConflicts = overwriteUserConflicts,
                setAsCurrentTerm = false
            )
        }
        val targetTermId = syncData.termId
        ensureTermExistsAndUpdateCalendar(
            termId = targetTermId,
            semesterStart = syncData.semesterStart,
            totalWeeks = syncData.totalWeeks,
            setCurrent = true
        )
        val diff = previewCourseImportDiff(
            termId = targetTermId,
            source = CourseSource.OFFICIAL_JW,
            candidates = syncData.timetableCandidates
        )
        commitCourseImportDiff(
            preview = diff,
            overwriteUserConflicts = overwriteUserConflicts,
            setAsCurrentTerm = true
        )
        val (syncXnm, syncXqm) = HautAcademicEndpoints.fromTermId(targetTermId)
        appContext.appDataStore.edit { prefs ->
            prefs[PrefKeys.SAVED_SYNC_XNM] = syncXnm
            prefs[PrefKeys.SAVED_SYNC_XQM] = syncXqm
            prefs[PrefKeys.LAST_AUTO_SYNC_MS] = System.currentTimeMillis()
        }
    }

    /**
     * 后台或一键动态刷新：使用本地已授权保存的教务凭据，从正方教务系统拉取最新课表、期初/期中/期末考试安排与成绩。
     * @param force 若为 false，则距离上次自动同步不足 15 分钟时跳过，避免频繁请求。
     */
    suspend fun performDynamicAcademicSync(force: Boolean = false): Result<HautLiveSyncResult>? {
        val prefs = appContext.appDataStore.data.first()
        val autoEnabled = prefs[PrefKeys.AUTO_SYNC_ENABLED] ?: true
        if (!force && !autoEnabled) return null

        val studentId = prefs[PrefKeys.SAVED_STUDENT_ID]?.takeIf { it.isNotBlank() } ?: return null
        val encodedPwd = prefs[PrefKeys.SAVED_PASSWORD_OBF]?.takeIf { it.isNotBlank() } ?: return null
        val passwordPlain = runCatching {
            String(java.util.Base64.getDecoder().decode(encodedPwd), java.nio.charset.StandardCharsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        val lastSyncMs = prefs[PrefKeys.LAST_AUTO_SYNC_MS] ?: 0L
        val nowMs = System.currentTimeMillis()
        if (!force && (nowMs - lastSyncMs) < 15 * 60 * 1000L) {
            return null
        }

        val xnm = prefs[PrefKeys.SAVED_SYNC_XNM] ?: "2025"
        val xqm = prefs[PrefKeys.SAVED_SYNC_XQM] ?: "12"

        val result = HautLiveAcademicClient.fetchLiveAcademicData(
            studentId = studentId,
            passwordPlain = passwordPlain,
            xnm = xnm,
            xqm = xqm
        )
        result.onSuccess { syncData ->
            applyLiveSyncData(syncData, overwriteUserConflicts = false)
        }
        return result
    }

    suspend fun setGpaPrivacyShield(enabled: Boolean) {
        appContext.appDataStore.edit { it[PrefKeys.GPA_PRIVACY] = enabled }
    }

    suspend fun setUseDynamicColor(enabled: Boolean) {
        appContext.appDataStore.edit { it[PrefKeys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setAutoCheckUpdateEnabled(enabled: Boolean) {
        appContext.appDataStore.edit { it[PrefKeys.AUTO_CHECK_UPDATE] = enabled }
    }

    suspend fun saveCustomChannelUrl(channelId: String, inviteUrl: String) {
        appContext.appDataStore.edit { prefs ->
            val current = decodeKeyValueMap(prefs[PrefKeys.CUSTOM_CHANNEL_URLS]).toMutableMap()
            if (inviteUrl.isBlank()) {
                current.remove(channelId)
            } else {
                current[channelId] = inviteUrl.trim()
            }
            prefs[PrefKeys.CUSTOM_CHANNEL_URLS] = encodeKeyValueMap(current)
        }
    }

    suspend fun saveCustomClubGroupNumber(clubId: String, groupNumber: String) {
        appContext.appDataStore.edit { prefs ->
            val current = decodeKeyValueMap(prefs[PrefKeys.CUSTOM_CLUB_GROUPS]).toMutableMap()
            if (groupNumber.isBlank()) {
                current.remove(clubId)
            } else {
                current[clubId] = groupNumber.trim()
            }
            prefs[PrefKeys.CUSTOM_CLUB_GROUPS] = encodeKeyValueMap(current)
        }
    }

    private fun decodeKeyValueMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split("\n").mapNotNull { line ->
            val idx = line.indexOf('\t')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()
    }

    private fun encodeKeyValueMap(map: Map<String, String>): String {
        return map.entries.joinToString("\n") { (k, v) ->
            "${k.replace("\t", " ").replace("\n", " ")}\t${v.replace("\n", "").trim()}"
        }
    }

    suspend fun setTimelineZoomScale(scale: Float) {
        appContext.appDataStore.edit { it[PrefKeys.TIMELINE_SCALE] = scale.coerceIn(1.2f, 3.6f) }
    }

    suspend fun saveWidgetConfig(config: WidgetInstanceConfig) {
        dao.upsertWidgetConfig(config.toEntity())
    }

    suspend fun getWidgetConfigOrDefault(appWidgetId: Int, defaultType: cn.edu.haut.campus.core.model.WidgetType): WidgetInstanceConfig {
        return dao.getWidgetConfig(appWidgetId)?.toDomain()
            ?: WidgetInstanceConfig(appWidgetId = appWidgetId, widgetType = defaultType)
    }

    suspend fun getReminderConfigSnapshot(): ReminderGlobalConfig {
        val prefs = appContext.appDataStore.data.first()
        return extractReminderConfig(prefs)
    }

    suspend fun getExamSchedulesSnapshot(): List<ExamScheduleItem> {
        return dao.getExamSchedulesSnapshot().map { it.toDomain() }
    }

    suspend fun getReminderEventOverridesSnapshot(): List<ReminderEventOverride> {
        return reminderDao.getEventPrefsSnapshot().map { it.toDomain() }
    }

    suspend fun updateReminderGlobalConfig(transform: (ReminderGlobalConfig) -> ReminderGlobalConfig) {
        appContext.appDataStore.edit { prefs ->
            val current = extractReminderConfig(prefs)
            val updated = transform(current)
            prefs[PrefKeys.REMINDERS_ENABLED] = updated.enabled
            prefs[PrefKeys.CLASS_LEAD_MINUTES] = updated.classLeadMinutes.coerceIn(5, 60)
            prefs[PrefKeys.CLASS_SNOOZE_DEFAULT_MINUTES] = updated.classSnoozeDefaultMinutes.coerceIn(5, 30)
            prefs[PrefKeys.CLASS_SNOOZE_MAX_COUNT] = updated.classSnoozeMaxCount.coerceIn(0, 5)
            prefs[PrefKeys.CLASS_SNOOZE_CUTOFF_MINUTES] = updated.classSnoozeCutoffMinutes.coerceIn(1, 15)
            prefs[PrefKeys.EXAM_DAY_BEFORE_ENABLED] = updated.examDayBeforeEnabled
            prefs[PrefKeys.EXAM_HOUR_BEFORE_ENABLED] = updated.examHourBeforeEnabled
            prefs[PrefKeys.EXAM_SNOOZE_MAX_COUNT] = updated.examSnoozeMaxCount.coerceIn(0, 5)
            prefs[PrefKeys.PRECISE_REMINDER_PREFERRED] = updated.preciseReminderPreferred
            prefs[PrefKeys.REMINDER_LOCKSCREEN_PRIVATE] = updated.lockscreenPrivate
            prefs[PrefKeys.REMINDER_QUIET_MODE] = updated.quietMode
            prefs[PrefKeys.REMINDER_POLICY_REVISION] = ReminderRevision.computePolicyRevision(
                enabled = updated.enabled,
                classLeadMinutes = updated.classLeadMinutes,
                maxSnoozes = updated.classSnoozeMaxCount,
                cutoffMinutesBeforeStart = updated.classSnoozeCutoffMinutes,
                examDayEnabled = updated.examDayBeforeEnabled,
                examHourEnabled = updated.examHourBeforeEnabled
            )
        }
        notifyScheduleOrPolicyChanged("REMINDER_GLOBAL_CONFIG_UPDATED")
    }

    suspend fun upsertReminderEventOverride(override: ReminderEventOverride) {
        reminderDao.upsertEventPref(override.toEntity())
        notifyScheduleOrPolicyChanged("REMINDER_EVENT_OVERRIDE_UPSERTED:${override.eventScopeKey}")
    }

    suspend fun removeReminderEventOverride(scopeKey: String) {
        reminderDao.deleteEventPref(scopeKey)
        notifyScheduleOrPolicyChanged("REMINDER_EVENT_OVERRIDE_REMOVED:$scopeKey")
    }

    suspend fun appendReminderDiagnosticLog(message: String, reconcileSummary: String? = null) {
        val nowMs = System.currentTimeMillis()
        val timeText = java.time.Instant.ofEpochMilli(nowMs)
            .atZone(HAUT_ZONE_ID)
            .toLocalTime()
            .withNano(0)
            .toString()
        val formatted = "[$timeText] $message"
        appContext.appDataStore.edit { prefs ->
            val existing = prefs[PrefKeys.REMINDER_DIAGNOSTIC_LOGS]
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                .orEmpty()
            val updated = (listOf(formatted) + existing).take(30)
            prefs[PrefKeys.REMINDER_DIAGNOSTIC_LOGS] = updated.joinToString("\n")
            if (reconcileSummary != null) {
                prefs[PrefKeys.LAST_REMINDER_RECONCILE_MS] = nowMs
                prefs[PrefKeys.LAST_REMINDER_RECONCILE_SUMMARY] = reconcileSummary
            }
        }
    }

    suspend fun setCampusServiceFeatureFlag(
        utilitiesEnabled: Boolean? = null,
        libraryNativeEnabled: Boolean? = null,
        librarySeatEnabled: Boolean? = null,
        focusEnabled: Boolean? = null,
        showTodayFocusQuickPill: Boolean? = null,
        timetableInterchangeEnabled: Boolean? = null
    ) {
        appContext.appDataStore.edit { prefs ->
            utilitiesEnabled?.let { prefs[PrefKeys.FF_UTILITIES_ENABLED] = it }
            libraryNativeEnabled?.let { prefs[PrefKeys.FF_LIBRARY_NATIVE_ENABLED] = it }
            librarySeatEnabled?.let { prefs[PrefKeys.FF_LIBRARY_SEAT_ENABLED] = it }
            focusEnabled?.let { prefs[PrefKeys.FF_FOCUS_ENABLED] = it }
            showTodayFocusQuickPill?.let { prefs[PrefKeys.FF_TODAY_FOCUS_PILL] = it }
            timetableInterchangeEnabled?.let { prefs[PrefKeys.FF_TIMETABLE_INTERCHANGE] = it }
        }
    }

    suspend fun setAllowUnverifiedUtilityTransport(allowed: Boolean) {
        appContext.appDataStore.edit { prefs ->
            prefs[PrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT] = allowed
        }
    }

    suspend fun setSelectedFocusMinutes(minutes: Int) {
        appContext.appDataStore.edit { prefs ->
            prefs[PrefKeys.SELECTED_FOCUS_MINUTES] = minutes.coerceIn(1, 180)
        }
    }

    fun readUtilityAccountResult(): UtilityAccountReadResult =
        utilityAccountStore.readAccountResult()

    /**
     * 在线查询本人已绑定房间水电并同步更新缓存与每日电表采样。
     */
    suspend fun saveAndRefreshOnlineUtilities(
        rawAccount: String? = null,
        allowUnverified: Boolean = false
    ): Result<List<OnlineUtilityRoomSnapshot>> = runCatching {
        val accountToUse = if (!rawAccount.isNullOrBlank()) {
            val clean = UtilityAccountSecureStore.validateAccountFormat(rawAccount)
            var switched = false
            utilityAccountStore.saveOrSwitchAccount(clean) {
                switched = true
            }
            if (switched) {
                database.withTransaction {
                    utilityAndFocusDao.clearAllOnlineRooms()
                    utilityAndFocusDao.clearAllDailySamples()
                }
            }
            clean
        } else {
            when (val saved = utilityAccountStore.readAccountResult()) {
                is UtilityAccountReadResult.Active -> saved.plainAccount
                is UtilityAccountReadResult.InvalidatedAndReset ->
                    throw IllegalStateException("安全密钥已重置，请重新输入本人校园卡账号")
                is UtilityAccountReadResult.Empty ->
                    throw IllegalArgumentException("请先填写本人校园卡账号（通常为学号）")
            }
        }

        val startedAtMs = System.currentTimeMillis()
        val snapshots = wanxiaoUtilityClient.queryOwnBoundRooms(
            account = accountToUse,
            allowUnverifiedTransport = allowUnverified
        )
        val finishedAtMs = System.currentTimeMillis()

        database.withTransaction {
            val existingRooms = utilityAndFocusDao.getOnlineRoomsSnapshot().associateBy { it.roomId }
            val updatedEntities = snapshots.map { snap ->
                val prev = existingRooms[snap.roomId]
                if (snap.failed && prev != null) {
                    prev.copy(failed = true)
                } else {
                    UtilityOnlineRoomEntity(
                        roomId = snap.roomId,
                        roomName = snap.roomName,
                        campusCode = prev?.campusCode,
                        devicesJson = encodeOnlineDevicesJson(snap.devices),
                        updatedAtEpochMs = if (snap.fetchedAtEpochMs > 0L) snap.fetchedAtEpochMs else finishedAtMs,
                        failed = snap.failed
                    )
                }
            }
            utilityAndFocusDao.clearAllOnlineRooms()
            utilityAndFocusDao.upsertOnlineRooms(updatedEntities)

            val existingSamples = utilityAndFocusDao.getDailySamplesSnapshot().map { it.toDomain() }
            val mergedSamples = UtilitySamplingPolicy.mergeDailySamples(
                existingSamples = existingSamples,
                rooms = snapshots,
                startedAtEpochMs = startedAtMs,
                finishedAtEpochMs = finishedAtMs
            )
            utilityAndFocusDao.clearAllDailySamples()
            utilityAndFocusDao.upsertDailySamples(mergedSamples.map { it.toEntity() })
        }

        if (snapshots.any { !it.failed }) {
            appContext.appDataStore.edit { prefs ->
                prefs[PrefKeys.LAST_UTILITY_SAMPLE_MS] = finishedAtMs
            }
        }
        snapshots
    }

    /**
     * 供后台周期 Worker 或前台恢复时调用的按需水电采样：
     * 仅在已开启水电功能、已开启在线传输许可、存在有效加密账号且满足采样间隔时触发。
     */
    suspend fun performPeriodicUtilitySampleIfEligible(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = appContext.appDataStore.data.first()
        val utilitiesEnabled = prefs[PrefKeys.FF_UTILITIES_ENABLED] ?: true
        val allowUnverified = prefs[PrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT] ?: false
        if (!utilitiesEnabled || !allowUnverified) return false

        val lastSampleMs = prefs[PrefKeys.LAST_UTILITY_SAMPLE_MS] ?: 0L
        if (!UtilitySamplingPolicy.shouldSample(lastSampleMs, nowEpochMs)) return false

        val activeAccount = (utilityAccountStore.readAccountResult() as? UtilityAccountReadResult.Active)
            ?.plainAccount ?: return false

        return saveAndRefreshOnlineUtilities(
            rawAccount = activeAccount,
            allowUnverified = true
        ).isSuccess
    }

    suspend fun updateOnlineRoomCampus(roomId: String, campusCode: String?) {
        utilityAndFocusDao.updateOnlineRoomCampus(roomId, campusCode)
    }

    suspend fun clearUtilityAccountAndOnlineCache() {
        runCatching { utilityAccountStore.wipeAll() }
        database.withTransaction {
            utilityAndFocusDao.clearAllOnlineRooms()
            utilityAndFocusDao.clearAllDailySamples()
        }
        appContext.appDataStore.edit { prefs ->
            prefs.remove(PrefKeys.LAST_UTILITY_SAMPLE_MS)
        }
    }

    suspend fun saveUtilityMeter(
        name: String,
        campusName: String,
        roomName: String,
        unit: String,
        lowThreshold: Double
    ) {
        val cleanName = name.trim()
        val cleanRoom = roomName.trim()
        val cleanUnit = unit.trim()
        require(cleanName.isNotEmpty() && cleanRoom.isNotEmpty() && cleanUnit.isNotEmpty()) {
            "请完整填写表计名称、房间号与单位"
        }
        require(lowThreshold.isFinite() && lowThreshold >= 0.0) {
            "低量提醒阈值不能为负数"
        }
        val entity = UtilityMeterEntity(
            id = UUID.randomUUID().toString(),
            name = cleanName.take(40),
            campusName = campusName.trim().ifBlank { "莲花街校区" },
            roomName = cleanRoom.take(40),
            unit = cleanUnit.take(12),
            lowThreshold = lowThreshold,
            createdAtEpochMs = System.currentTimeMillis()
        )
        utilityAndFocusDao.upsertMeter(entity)
    }

    suspend fun deleteUtilityMeter(meterId: String) {
        database.withTransaction {
            utilityAndFocusDao.deleteReadingsByMeterId(meterId)
            utilityAndFocusDao.deleteMeterById(meterId)
        }
    }

    suspend fun addUtilityReading(
        meterId: String,
        value: Double,
        topUpValue: Double = 0.0,
        note: String = ""
    ) {
        require(value.isFinite() && value >= 0.0) { "当前读数不能为负数" }
        require(topUpValue.isFinite() && topUpValue >= 0.0) { "充值/购入量不能为负数" }
        val entity = UtilityReadingEntity(
            id = UUID.randomUUID().toString(),
            meterId = meterId,
            value = value,
            topUpValue = topUpValue,
            recordedAtEpochMs = System.currentTimeMillis(),
            note = note.trim().take(80)
        )
        utilityAndFocusDao.upsertReading(entity)
    }

    suspend fun deleteUtilityReading(readingId: String) {
        utilityAndFocusDao.deleteReadingById(readingId)
    }

    suspend fun applyInterchangeCalendarMetadata(
        termId: String,
        semesterStart: LocalDate?,
        totalWeeks: Int?,
        sectionTimes: List<PeriodTime>
    ) {
        database.withTransaction {
            if (semesterStart != null || totalWeeks != null) {
                val terms = dao.observeTerms().first().map { it.toDomain() }
                val target = terms.firstOrNull { it.id == termId }
                if (target != null) {
                    dao.upsertTerms(
                        listOf(
                            target.copy(
                                startDate = semesterStart ?: target.startDate,
                                weekCount = totalWeeks ?: target.weekCount
                            ).toEntity()
                        )
                    )
                }
            }
            if (sectionTimes.isNotEmpty()) {
                dao.upsertPeriodTimes(sectionTimes.map { it.toEntity() })
            }
        }
        notifyScheduleOrPolicyChanged("INTERCHANGE_CALENDAR_APPLIED")
    }

    /**
     * 完全清除本地个人课程、成绩、导入审计、水电账号与历史、自习专注及学校网页会话（保留公开的校区建筑字典与节次模板）。
     */
    suspend fun wipeAllPersonalData() {
        for (hook in beforeWipeHooks.values) {
            runCatching { hook.invoke() }
        }
        database.withTransaction {
            dao.clearStudentProfile()
            dao.clearCourses()
            dao.clearRules()
            dao.clearOccurrenceOverrides()
            dao.clearGradeAttempts()
            dao.clearExamSchedules()
            dao.clearImportAudits()
            dao.clearWidgetConfigs()
            reminderDao.clearAllTasks()
            reminderDao.clearAllEventPrefs()
            utilityAndFocusDao.clearAllDailySamples()
            utilityAndFocusDao.clearAllOnlineRooms()
            utilityAndFocusDao.clearAllReadings()
            utilityAndFocusDao.clearAllMeters()
            utilityAndFocusDao.clearAllFocusSessions()
        }
        appContext.appDataStore.edit { prefs ->
            prefs.remove(PrefKeys.OFFICIAL_GPA)
            prefs.remove(PrefKeys.OFFICIAL_GPA_NOTE)
            prefs.remove(PrefKeys.SAVED_STUDENT_ID)
            prefs.remove(PrefKeys.SAVED_PASSWORD_OBF)
            prefs.remove(PrefKeys.LAST_AUTO_SYNC_MS)
            prefs.remove(PrefKeys.LAST_REMINDER_RECONCILE_MS)
            prefs.remove(PrefKeys.LAST_REMINDER_RECONCILE_SUMMARY)
            prefs.remove(PrefKeys.REMINDER_DIAGNOSTIC_LOGS)
            prefs.remove(PrefKeys.LAST_UTILITY_SAMPLE_MS)
            prefs.remove(PrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT)
            prefs[PrefKeys.SEEDED] = true
            prefs[PrefKeys.CLEAN_BASE_V2] = true
            prefs[PrefKeys.ONBOARDING_COMPLETED] = false
        }
    }

    suspend fun getDirectScheduleDataSnapshot(): DirectScheduleData {
        ensureInitialSeedIfNeeded()
        val term = dao.getCurrentTerm()?.toDomain()
        val courses = dao.getCoursesSnapshot().map { it.toDomain() }
        val rules = dao.getRulesSnapshot().map { it.toDomain() }
        val periods = dao.getPeriodTimesSnapshot().map { it.toDomain() }
        val calOverrides = dao.getCalendarOverridesSnapshot().map { it.toDomain() }
        val occOverrides = dao.getOccurrenceOverridesSnapshot().map { it.toDomain() }
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        return DirectScheduleData(
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periods,
            calendarOverrides = calOverrides,
            occurrenceOverrides = occOverrides,
            buildings = buildings
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: CampusRepository? = null

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `exam_schedules` (
                        `id` TEXT NOT NULL,
                        `termId` TEXT NOT NULL,
                        `courseCode` TEXT NOT NULL,
                        `courseName` TEXT NOT NULL,
                        `examBatchName` TEXT NOT NULL,
                        `examStageLabel` TEXT NOT NULL,
                        `examFormat` TEXT NOT NULL,
                        `rawTimeText` TEXT NOT NULL,
                        `examDateIso` TEXT,
                        `startTimeIso` TEXT,
                        `endTimeIso` TEXT,
                        `rawRoom` TEXT NOT NULL,
                        `seatNumber` TEXT,
                        `campusName` TEXT NOT NULL,
                        `isRetake` INTEGER NOT NULL,
                        `syncedAtEpochMilli` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reminder_tasks` (
                        `id` TEXT NOT NULL,
                        `taskKey` TEXT NOT NULL,
                        `eventKey` TEXT NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `reminderKind` TEXT NOT NULL,
                        `eventRevision` TEXT NOT NULL,
                        `policyRevision` TEXT NOT NULL,
                        `eventStartAtEpochMs` INTEGER NOT NULL,
                        `originalScheduledAtEpochMs` INTEGER NOT NULL,
                        `scheduledAtEpochMs` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `schedulerState` TEXT NOT NULL,
                        `generation` INTEGER NOT NULL,
                        `snoozeCount` INTEGER NOT NULL,
                        `maxSnoozes` INTEGER NOT NULL,
                        `claimedAtEpochMs` INTEGER,
                        `deliveredAtEpochMs` INTEGER,
                        `lastActionAtEpochMs` INTEGER,
                        `scheduleMode` TEXT NOT NULL,
                        `lastErrorCode` TEXT,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_reminder_tasks_taskKey` ON `reminder_tasks` (`taskKey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reminder_tasks_status_scheduledAtEpochMs` ON `reminder_tasks` (`status`, `scheduledAtEpochMs`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reminder_tasks_eventKey_status` ON `reminder_tasks` (`eventKey`, `status`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reminder_tasks_schedulerState_scheduledAtEpochMs` ON `reminder_tasks` (`schedulerState`, `scheduledAtEpochMs`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reminder_event_prefs` (
                        `eventScopeKey` TEXT NOT NULL,
                        `enabledOverride` INTEGER,
                        `leadMinutesOverride` INTEGER,
                        `maxSnoozesOverride` INTEGER,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`eventScopeKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): CampusRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appCtx = context.applicationContext
                    val db = Room.databaseBuilder(
                        appCtx,
                        HautDatabase::class.java,
                        "haut_campus_assistant.db"
                    )
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                        .build()
                    CampusRepository(appCtx, db).also { INSTANCE = it }
                }
            }
        }
    }
}

data class DirectScheduleData(
    val term: Term?,
    val courses: List<Course>,
    val rules: List<CourseScheduleRule>,
    val periodTimes: List<PeriodTime>,
    val calendarOverrides: List<CalendarOverride>,
    val occurrenceOverrides: List<OccurrenceOverride>,
    val buildings: List<Building>
)
