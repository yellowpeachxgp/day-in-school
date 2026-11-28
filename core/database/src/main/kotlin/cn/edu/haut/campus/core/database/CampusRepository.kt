package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import cn.edu.haut.campus.core.academic.HautLiveSyncResult
import cn.edu.haut.campus.core.campusservices.BookingExecutionMode
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.campusservices.CloudSyncSnapshotResult
import cn.edu.haut.campus.core.campusservices.CloudTokenSecureVault
import cn.edu.haut.campus.core.campusservices.LibraryRegionSeatsSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatActionOutcome
import cn.edu.haut.campus.core.campusservices.LibrarySeatActionType
import cn.edu.haut.campus.core.campusservices.LibrarySeatOverviewSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionState
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionVault
import cn.edu.haut.campus.core.campusservices.LibraryStudyRegionItem
import cn.edu.haut.campus.core.campusservices.OnlineUtilityRoomSnapshot
import cn.edu.haut.campus.core.campusservices.SchoolWebSessionManager
import cn.edu.haut.campus.core.campusservices.TripleScheduleStage
import cn.edu.haut.campus.core.campusservices.UtilityAccountReadResult
import cn.edu.haut.campus.core.campusservices.UtilityAccountSecureStore
import cn.edu.haut.campus.core.campusservices.UtilityDailySample
import cn.edu.haut.campus.core.campusservices.WanxiaoUtilityClient
import cn.edu.haut.campus.core.campusservices.XiaomiAccountCredential
import cn.edu.haut.campus.core.campusservices.XiaomiAccountSecureVault
import cn.edu.haut.campus.core.campusservices.XiaomiApiSimpleResult
import cn.edu.haut.campus.core.campusservices.XiaomiSyncSummary
import cn.edu.haut.campus.core.campusservices.XiaomiTrackPoint
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingAlias
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.FocusSessionDomain
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.ImportAudit
import cn.edu.haut.campus.core.model.ImportCourseCandidate
import cn.edu.haut.campus.core.model.ImportDiffPreview
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.core.model.OccurrenceOverride
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId



@Deprecated(
    message = "W0-05 拆分过渡外观：新代码应使用具体领域 Repository；本类仅为兼容委托门面。"
)
class CampusRepository internal constructor(
    private val appContext: Context,
    private val database: HautDatabase,
    private val preferences: DataStore<Preferences>,
    private val accountProfile: AccountProfileEntity,
    academicCredentialVaultOverride: AcademicCredentialVault? = null,
    sanitizeBookingPlanWriteHookForTest: (suspend (String) -> Unit)? = null
) {
    private val dao = database.campusDao()
    private val reminderDao = database.reminderDao()
    private val utilityAndFocusDao = database.utilityAndFocusDao()
    private val cloudAndParcelDao = database.cloudAndParcelDao()
    private val utilityAccountStore = UtilityAccountSecureStore(appContext, scopeId = accountProfile.id.takeUnless { accountProfile.isLegacy })
    private val librarySeatSessionVault = LibrarySeatSessionVault(appContext, scopeId = accountProfile.id.takeUnless { accountProfile.isLegacy })
    private val xiaomiAccountVault = XiaomiAccountSecureVault(appContext, scopeId = accountProfile.id)
    private val cloudTokenVault = CloudTokenSecureVault(appContext, scopeId = accountProfile.id)
    private val academicCredentialVault = academicCredentialVaultOverride ?: createAcademicCredentialVault(appContext)
    private val academicCredentialMutex = Mutex()

    /**
     * 云身份生命周期协调锁：统一 (binding, generation, apiBase) 快照读取顺序、世代推进与
     * 响应写回复核。持锁期间只做本地 DAO/DataStore 操作，绝不在锁内发起网络请求。
     */
    private val cloudIdentityMutex = Mutex()

    private val cloudTokenMutex = Mutex()
    private val cloudSyncMutex = Mutex()

    /**
     * 预约计划/包裹落库与云身份变更的提交屏障锁。
     *
     * 全局锁序约定（唯一合法顺序）：bookingPlanStateMutex → cloudIdentityMutex。
     * - 云端同步在持有本锁的临界区内复核身份快照（内部再取 identity 锁）后写入包裹/计划；
     * - 一切身份变更路径（解绑、登录/同号重登、换 base-url、全量擦除）必须先取本锁，
     *   再在本锁内取 cloudIdentityMutex 推进世代/改写绑定——
     *   保证已进临界区的同步写入整体先于身份变更生效，未进临界区的同步在复核处必然失败，
     *   二者之间不存在"世代校验通过后才发生的身份变更"窗口（E3 修复）。
     * - 严禁反向嵌套（identity → booking），严禁在任一锁内做网络等待。
     */
    private val bookingPlanStateMutex = Mutex()
    private val wanxiaoUtilityClient = WanxiaoUtilityClient()
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repositoryEnvironment = CampusRepositoryEnvironment(
        database = database,
        preferences = preferences,
        accountProfile = accountProfile,
        scheduleOrPolicyChangedNotifier = { reason -> notifyScheduleOrPolicyChanged(reason) }
    )
    val examRepository = ExamRepository(repositoryEnvironment)
    val gradesRepository = GradesRepository(repositoryEnvironment)
    val termRepository = TermRepository(repositoryEnvironment, examRepository)
    val timetableRepository = TimetableRepository(repositoryEnvironment, termRepository)
    private val momentsRuntimePort = object : MomentsRuntimePort {
        override val preferencesFlow: Flow<AppPreferencesState>
            get() = this@CampusRepository.preferencesFlow
        override val studentProfileFlow: Flow<StudentProfile?>
            get() = this@CampusRepository.studentProfileFlow

        override suspend fun resolveValidCloudAccessToken(forceRefresh: Boolean): String =
            cloudIdentityRepository.resolveValidCloudAccessToken(forceRefresh = forceRefresh)

        override fun cloudHttpTransportForTest(): CloudHttpTransportForTest? =
            this@CampusRepository.cloudHttpTransportForTest
    }
    private val momentsRepository = MomentsRepository(repositoryEnvironment, momentsRuntimePort)
    private val campusServicesRuntimePort = object : CampusServicesRuntimePort {
        override val preferencesFlow: Flow<AppPreferencesState>
            get() = this@CampusRepository.preferencesFlow
        override val activeSessionGeneration: Long?
            get() = this@CampusRepository.activeSessionGeneration

        override suspend fun migrateLegacyAcademicCredentialsIfNeeded() =
            syncOrchestrator.migrateLegacyAcademicCredentialsIfNeededForFacade()

        override fun readAcademicCredential(accountId: String): AcademicCredentialReadResult =
            this@CampusRepository.academicCredentialVault.read(
                syncOrchestrator.academicCredentialScopeForFacade(accountId)
            )

        override suspend fun <T> withCloudIdentityBarrier(block: suspend () -> T): T =
            bookingPlanStateMutex.withLock {
                cloudIdentityMutex.withLock {
                    block()
                }
            }

        override suspend fun bumpCloudIdentityGenerationForBaseUrlChange(cloudUserId: String?): Long =
            cloudIdentityRepository.bumpCloudIdentityGenerationLocked(
                cloudUserId = cloudUserId,
                markUnlinkTombstone = false
            )

        override suspend fun currentCloudUserIdInBarrier(): String? =
            this@CampusRepository.cloudAndParcelDao.getCloudIdentityBindingSnapshot()?.cloudUserId
    }
    val campusServicesRepository = CampusServicesRepository(
        environment = repositoryEnvironment,
        appContext = appContext,
        utilityAccountStore = utilityAccountStore,
        runtimePort = campusServicesRuntimePort
    )
    private val parcelHubRuntimePort = object : ParcelHubRuntimePort {
        override fun launchCloudSync() {
            repoScope.launch { runCatching { syncWithCampusCloud() } }
        }
    }
    private val parcelHubRepository = ParcelHubRepository(
        environment = repositoryEnvironment,
        appContext = appContext,
        xiaomiAccountVault = xiaomiAccountVault,
        runtimePort = parcelHubRuntimePort
    )

    private val librarySeatRuntimePort = object : LibrarySeatRuntimePort {
        override val isSessionActive: Boolean
            get() = this@CampusRepository.isSessionActive

        override fun requireActiveSession() =
            this@CampusRepository.requireActiveSession()

        override suspend fun migrateLegacyAcademicCredentialsIfNeeded() =
            syncOrchestrator.migrateLegacyAcademicCredentialsIfNeededForFacade()

        override suspend fun saveAuthorizedSyncCredentials(
            studentId: String,
            passwordPlain: String,
            xnm: String,
            xqm: String
        ) = this@CampusRepository.saveAuthorizedSyncCredentials(
            studentId = studentId,
            passwordPlain = passwordPlain,
            xnm = xnm,
            xqm = xqm
        )
    }
    private val librarySeatRepository = LibrarySeatRepository(
        environment = repositoryEnvironment,
        librarySeatSessionVault = librarySeatSessionVault,
        academicCredentialVault = academicCredentialVault,
        academicCredentialMutex = academicCredentialMutex,
        runtimePort = librarySeatRuntimePort
    )

    private val cloudIdentityRuntimePort = object : CloudIdentityRuntimePort {
        override val preferencesFlow: Flow<AppPreferencesState>
            get() = this@CampusRepository.preferencesFlow

        override fun launchCloudSync() {
            repoScope.launch { runCatching { syncWithCampusCloud() } }
        }

        override suspend fun syncWithCampusCloud(): Result<CloudSyncSnapshotResult> =
            this@CampusRepository.syncWithCampusCloud()

        override fun cloudHttpTransportForTest(): CloudHttpTransportForTest? =
            this@CampusRepository.cloudHttpTransportForTest
    }
    private val cloudIdentityRepository: CloudIdentityRepository = CloudIdentityRepository(
        environment = repositoryEnvironment,
        appContext = appContext,
        cloudTokenVault = cloudTokenVault,
        academicCredentialVault = academicCredentialVault,
        academicCredentialMutex = academicCredentialMutex,
        cloudIdentityMutex = cloudIdentityMutex,
        bookingPlanStateMutex = bookingPlanStateMutex,
        cloudTokenMutex = cloudTokenMutex,
        librarySeatRepository = librarySeatRepository,
        runtimePort = cloudIdentityRuntimePort
    )

    private val syncOrchestratorRuntimePort = object : SyncOrchestratorRuntimePort {
        override val preferencesFlow: Flow<AppPreferencesState>
            get() = this@CampusRepository.preferencesFlow

        override fun cloudHttpTransportForTest(): CloudHttpTransportForTest? =
            this@CampusRepository.cloudHttpTransportForTest

        override suspend fun notifyScheduleOrPolicyChanged(reason: String) =
            this@CampusRepository.notifyScheduleOrPolicyChanged(reason)

        override fun requireActiveSession() =
            this@CampusRepository.requireActiveSession()
    }
    private val syncOrchestrator = SyncOrchestrator(
        environment = repositoryEnvironment,
        appContext = appContext,
        cloudIdentityRepository = cloudIdentityRepository,
        termRepository = termRepository,
        gradesRepository = gradesRepository,
        examRepository = examRepository,
        timetableRepository = timetableRepository,
        academicCredentialVault = academicCredentialVault,
        academicCredentialMutex = academicCredentialMutex,
        cloudSyncMutex = cloudSyncMutex,
        bookingPlanStateMutex = bookingPlanStateMutex,
        runtimePort = syncOrchestratorRuntimePort
    )

    @Volatile
    private var activeSessionGeneration: Long? = null

    val isSessionActive: Boolean
        get() = activeSessionGeneration != null

    val profileId: String get() = accountProfile.id
    val schoolId: String get() = accountProfile.schoolId
    val school: cn.edu.haut.campus.core.model.SchoolProfile get() = accountProfile.school()
    val timeZone: ZoneId get() = ZoneId.of(accountProfile.timeZoneId)
    val supportsHautServices: Boolean get() = schoolId == SchoolDirectory.HAUT_SCHOOL_ID.value
    val sessionGeneration: Long? get() = activeSessionGeneration

    fun requireActiveSession() {
        check(isSessionActive) { "账号已切换，请在当前账号重新操作" }
    }

    internal fun activateSession(generation: Long) {
        require(generation > 0) { "账号会话世代无效" }
        activeSessionGeneration = generation
    }

    internal fun deactivateSession() {
        activeSessionGeneration = null
    }

    internal suspend fun initializeForSession() {
        database.openHelper.writableDatabase
        syncOrchestrator.migrateLegacyAcademicCredentialsIfNeededForFacade()
        ensureInitialSeedIfNeeded()
        runCatching { rescheduleActiveLocalBookingPlans() }
    }

    internal fun databaseForAdoption(): HautDatabase = database

    @Volatile
    var onScheduleOrPolicyChangedListener: (suspend (String) -> Unit)? = null

    /**
     * 测试注入点：occurrence 恢复/撤销命令（unpauseOccurrence/undoMove/restoreRuleCancellation）
     * 在事务内、写入判定完成后回调命令名。仅用于并发/回滚回归测试注入闩锁与故障，生产路径必须为 null。
     */
    internal var occurrenceCommandInTransactionHook: (suspend (command: String) -> Unit)?
        get() = timetableRepository.occurrenceWriteContext.occurrenceCommandInTransactionHook
        set(value) {
            timetableRepository.occurrenceWriteContext.occurrenceCommandInTransactionHook = value
        }

    /**
     * 测试注入点：恢复/撤销命令事务内每完成一条破坏性写入（删除行/盖印改写）回调
     * （命令名, 已写行 id）——覆盖「写中」阶段，可在删一半后注入故障验证整体回滚
     * 与零通知。仅用于回归测试，生产路径必须为 null。
     */
    internal var occurrenceCommandWriteProgressHook: (suspend (command: String, rowId: String) -> Unit)?
        get() = timetableRepository.occurrenceWriteContext.occurrenceCommandWriteProgressHook
        set(value) {
            timetableRepository.occurrenceWriteContext.occurrenceCommandWriteProgressHook = value
        }

    var onBeforeWipePersonalDataHook: (suspend () -> Unit)?
        get() = profileDataWiper.onBeforeWipePersonalDataHook
        set(value) {
            profileDataWiper.onBeforeWipePersonalDataHook = value
        }

    fun registerBeforeWipeHook(key: String, hook: suspend () -> Unit) =
        profileDataWiper.registerBeforeWipeHook(key, hook)


    private val profileDataWiper = ProfileDataWiper(
        appContext = appContext,
        database = database,
        preferences = preferences,
        dao = dao,
        reminderDao = reminderDao,
        utilityAndFocusDao = utilityAndFocusDao,
        cloudAndParcelDao = cloudAndParcelDao,
        academicCredentialVault = academicCredentialVault,
        academicCredentialMutex = academicCredentialMutex,
        cloudIdentityMutex = cloudIdentityMutex,
        cloudTokenMutex = cloudTokenMutex,
        bookingPlanStateMutex = bookingPlanStateMutex,
        cloudIdentityRepository = cloudIdentityRepository,
        syncOrchestrator = syncOrchestrator
    )

    init {
        registerBeforeWipeHook("campus_services_account_and_web_wipe") {
            runCatching { utilityAccountStore.wipeAll() }
            runCatching { librarySeatSessionVault.clear() }
            runCatching { xiaomiAccountVault.saveAccounts(emptyList()) }
            runCatching { SchoolWebSessionManager.clearAllSchoolWebSessions() }
        }
        registerBeforeWipeHook(CommunityCacheWipeRegistration.HOOK_KEY) {
            CommunityCacheWipeRegistration.wipeProfile(appContext.filesDir, accountProfile.id)
        }
        repoScope.launch {
            runCatching { profileDataWiper.sanitizePersistedNullLiteralsForFacade(sanitizeBookingPlanWriteHookForTest) }
        }
    }


    val preferencesFlow: Flow<AppPreferencesState> =
        campusServicesRepository.preferencesFlow

    val studentProfileFlow: Flow<StudentProfile?> =
        gradesRepository.studentProfileFlow

    val termsFlow: Flow<List<Term>> =
        termRepository.termsFlow

    val currentTermFlow: Flow<Term?> =
        termRepository.currentTermFlow

    val periodTimesFlow: Flow<List<PeriodTime>> =
        termRepository.periodTimesFlow

    val calendarOverridesFlow: Flow<List<CalendarOverride>> =
        termRepository.calendarOverridesFlow

    val coursesFlow: Flow<List<Course>> =
        timetableRepository.coursesFlow

    val rulesFlow: Flow<List<CourseScheduleRule>> =
        timetableRepository.rulesFlow

    val occurrenceOverridesFlow: Flow<List<OccurrenceOverride>> =
        timetableRepository.occurrenceOverridesFlow

    val gradeAttemptsFlow: Flow<List<GradeAttempt>> =
        gradesRepository.gradeAttemptsFlow

    val buildingsFlow: Flow<List<Building>> =
        timetableRepository.buildingsFlow

    val buildingAliasesFlow: Flow<List<BuildingAlias>> =
        timetableRepository.buildingAliasesFlow

    val widgetConfigsFlow: Flow<List<WidgetInstanceConfig>> =
        campusServicesRepository.widgetConfigsFlow

    val importAuditsFlow: Flow<List<ImportAudit>> =
        dao.observeImportAudits().map { list -> list.map { it.toDomain() } }

    /** 学期无法自动确认、需用户重新选择学期同步核对的考试记录审计（V2 逐条修复审计）。 */
    val examTermReviewAuditsFlow: Flow<List<ExamTermRepairAuditEntity>> =
        examRepository.examTermReviewAuditsFlow

    val examSchedulesFlow: Flow<List<ExamScheduleItem>> =
        examRepository.examSchedulesFlow

    val unscheduledCoursesFlow: Flow<List<UnscheduledAcademicCourseEntity>> =
        timetableRepository.unscheduledCoursesFlow

    val activeReminderTasksFlow: Flow<List<ReminderTaskDomain>> =
        campusServicesRepository.activeReminderTasksFlow

    val reminderEventOverridesFlow: Flow<List<ReminderEventOverride>> =
        campusServicesRepository.reminderEventOverridesFlow

    fun observeReminderTasksInWindow(windowStartMs: Long, windowEndMs: Long): Flow<List<ReminderTaskDomain>> =
        campusServicesRepository.observeReminderTasksInWindow(windowStartMs, windowEndMs)

    fun reminderDao(): ReminderDao = campusServicesRepository.reminderDao()
    fun utilityAndFocusDao(): UtilityAndFocusDao = campusServicesRepository.utilityAndFocusDao()
    fun utilityAccountSecureStore(): UtilityAccountSecureStore = campusServicesRepository.utilityAccountSecureStore()

    val utilityDailySamplesFlow: Flow<List<UtilityDailySample>> =
        campusServicesRepository.utilityDailySamplesFlow

    val utilityOnlineRoomsFlow: Flow<List<UtilityOnlineRoomDomain>> =
        campusServicesRepository.utilityOnlineRoomsFlow

    val utilityMetersFlow: Flow<List<UtilityMeterDomain>> =
        campusServicesRepository.utilityMetersFlow

    val utilityReadingsFlow: Flow<List<UtilityReadingDomain>> =
        campusServicesRepository.utilityReadingsFlow

    val activeFocusSessionFlow: Flow<FocusSessionDomain?> =
        campusServicesRepository.activeFocusSessionFlow

    val completedFocusSessionsFlow: Flow<List<FocusSessionDomain>> =
        campusServicesRepository.completedFocusSessionsFlow

    private suspend fun notifyScheduleOrPolicyChanged(reason: String) {
        runCatching { onScheduleOrPolicyChangedListener?.invoke(reason) }
    }

    suspend fun ensureInitialSeedIfNeeded() =
        termRepository.ensureInitialSeedIfNeeded()

    internal suspend fun ensureExamTermRepairV2IfNeeded() =
        examRepository.ensureExamTermRepairV2IfNeeded()

    internal suspend fun ensureExtendedPeriodTimes11To12IfNeeded() =
        termRepository.ensureExtendedPeriodTimes11To12IfNeeded()

    internal suspend fun ensureSeasonalTermRolloverIfNeeded(today: LocalDate = LocalDate.now(timeZone)) =
        termRepository.ensureSeasonalTermRolloverIfNeeded(today)

    suspend fun completeOnboarding() =
        campusServicesRepository.completeOnboarding()

    suspend fun resetOnboardingForGuide() =
        campusServicesRepository.resetOnboardingForGuide()

    suspend fun resetToDemoSeedData() =
        timetableRepository.resetToDemoSeedData()

    suspend fun ensureTermExistsAndUpdateCalendar(
        termId: String,
        semesterStart: LocalDate? = null,
        totalWeeks: Int? = null,
        setCurrent: Boolean = false
    ) = termRepository.ensureTermExistsAndUpdateCalendar(
        termId = termId,
        semesterStart = semesterStart,
        totalWeeks = totalWeeks,
        setCurrent = setCurrent
    )

    suspend fun selectCurrentTerm(termId: String) =
        termRepository.selectCurrentTerm(termId)

    suspend fun updateTermStartDate(termId: String, newStartDate: LocalDate) =
        termRepository.updateTermStartDate(termId, newStartDate)

    suspend fun updatePeriodTime(periodTime: PeriodTime) =
        termRepository.updatePeriodTime(periodTime)

    /**
     * 用户手动编辑课程属性与排课规则：自动标记 userModified = true，确保下次教务同步时不会静默覆盖。
     */
    suspend fun saveUserCourseEdit(
        course: Course,
        rule: CourseScheduleRule
    ) = timetableRepository.saveUserCourseEdit(course, rule)

    /**
     * 编辑可能由多条相邻规则纵向合并的全学期排课规则；行为委托 TimetableRepository，语义不变。
     */
    @JvmOverloads
    suspend fun saveUserMergedCourseRuleEdit(
        course: Course,
        primaryRule: CourseScheduleRule,
        mergedRuleIds: List<String>,
        contextDate: LocalDate? = null
    ) = timetableRepository.saveUserMergedCourseRuleEdit(
        course = course,
        primaryRule = primaryRule,
        mergedRuleIds = mergedRuleIds,
        contextDate = contextDate
    )

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
    ) = timetableRepository.addManualCourseWithRule(
        termId = termId,
        name = name,
        teacher = teacher,
        rawRoom = rawRoom,
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        weeks = weeks,
        colorArgb = colorArgb
    )

    suspend fun saveOccurrenceOverride(override: OccurrenceOverride) =
        timetableRepository.saveOccurrenceOverride(override)

    /**
     * 对单次课程执行当日改教室/改节次；行为委托 TimetableRepository，语义不变。
     */
    suspend fun saveSingleDayOccurrenceEdit(
        occ: cn.edu.haut.campus.core.model.ClassOccurrence,
        termId: String,
        newStartPeriod: Int,
        newEndPeriod: Int,
        newRoom: String,
        reason: String = "单次调课"
    ) = timetableRepository.saveSingleDayOccurrenceEdit(
        occ = occ,
        termId = termId,
        newStartPeriod = newStartPeriod,
        newEndPeriod = newEndPeriod,
        newRoom = newRoom,
        reason = reason
    )

    suspend fun resetSingleDayOccurrenceEdit(occ: cn.edu.haut.campus.core.model.ClassOccurrence) =
        timetableRepository.resetSingleDayOccurrenceEdit(occ)

    suspend fun cancelClassOccurrenceOnDate(
        occ: cn.edu.haut.campus.core.model.ClassOccurrence,
        termId: String,
        reason: String = "单次停课"
    ) = timetableRepository.cancelClassOccurrenceOnDate(
        occ = occ,
        termId = termId,
        reason = reason
    )

    suspend fun rescheduleSingleOccurrenceCrossDate(
        occ: cn.edu.haut.campus.core.model.ClassOccurrence,
        termId: String,
        targetDate: LocalDate,
        newStartPeriod: Int,
        newEndPeriod: Int,
        newRoom: String,
        reason: String? = null
    ) = timetableRepository.rescheduleSingleOccurrenceCrossDate(
        occ = occ,
        termId = termId,
        targetDate = targetDate,
        newStartPeriod = newStartPeriod,
        newEndPeriod = newEndPeriod,
        newRoom = newRoom,
        reason = reason
    )

    /**
     * 通用单条覆盖删除入口；行为委托 TimetableRepository，语义不变。
     */
    suspend fun deleteOccurrenceOverride(overrideId: String) =
        timetableRepository.deleteOccurrenceOverride(overrideId)

    /**
     * 单次「恢复上课」的结构化结果（[restoreCanceledOccurrence] 适配器返回值）：
     * - [restoredSourceOverrideIds] 被恢复的源停课覆盖行（含按 moveId 原子撤销的整组源）；
     * - [deletedTargetOverrideIds] 被精确删除的调入目标行（仅限同一 moveId 的 TARGET）；
     * - [restoredMoveIds] 被整体撤销的跨日调课关联；
     * - [unpausedOverrideIds] 仅取消暂停标记的纯覆盖行（暂停的调入目标/单日新增）；
     * - [legacyAmbiguousTargets] 旧数据（moveId==null）恢复时检出的疑似调入目标——绝不自动删除，
     *   交由 UI 提示用户显式选择删除或保留待核对。
     */
    data class OccurrenceRestoreResult(
        val restoredSourceOverrideIds: List<String> = emptyList(),
        val deletedTargetOverrideIds: List<String> = emptyList(),
        val restoredMoveIds: List<String> = emptyList(),
        val unpausedOverrideIds: List<String> = emptyList(),
        val legacyAmbiguousTargets: List<OccurrenceOverride> = emptyList()
    ) {
        val legacyReviewRequired: Boolean get() = legacyAmbiguousTargets.isNotEmpty()

        /** 本次调用是否产生了任何实际写入（恢复源/删除目标/取消暂停）。 */
        val changed: Boolean
            get() = restoredSourceOverrideIds.isNotEmpty() ||
                deletedTargetOverrideIds.isNotEmpty() ||
                restoredMoveIds.isNotEmpty() ||
                unpausedOverrideIds.isNotEmpty()
    }

    /**
     * 恢复/撤销命令的统一终态：调用方以点击瞬间固化的（termId, overrideId, moveId, 意图）发起，
     * 仓库绝不依据当前 canceled 状态反推用户意图。
     */
    enum class OccurrenceCommandStatus {
        /** 命令在单一事务内原子提交并产生实际状态变更（此时才会发出课表变更通知）。 */
        APPLIED,

        /** 目标行已不存在或已处于期望终态：安全幂等空操作，无任何写入、无通知。 */
        NO_CHANGE,

        /** 点击时捕获的身份（termId/moveId）与当前行不一致：陈旧点击整体拒绝，等待 UI 刷新重试。 */
        CONFLICT
    }

    /** 显式意图恢复/撤销命令的结构化结果。 */
    data class OccurrenceCommandResult(
        val status: OccurrenceCommandStatus,
        val unpausedOverrideIds: List<String> = emptyList(),
        val restoredSourceOverrideIds: List<String> = emptyList(),
        val deletedTargetOverrideIds: List<String> = emptyList(),
        val restoredMoveIds: List<String> = emptyList(),
        val legacyAmbiguousTargets: List<OccurrenceOverride> = emptyList()
    ) {
        val legacyReviewRequired: Boolean get() = legacyAmbiguousTargets.isNotEmpty()

        /** 仅 [OccurrenceCommandStatus.APPLIED] 视为实际变更。 */
        val changed: Boolean get() = status == OccurrenceCommandStatus.APPLIED
    }

    /** 恢复命令的显式意图：UI 在点击瞬间固化，仓库绝不从当前行状态反推意图。 */
    enum class RestoreIntent {
        /** 仅恢复暂停的纯覆盖行（暂停的调入目标/单日新增）：selectedRows 必须全部 ruleId==null。 */
        UNPAUSE,

        /** 仅按 moveId 整组撤销跨日调课：selectedRows 必须为空、selectedMoves 非空。 */
        UNDO_MOVES,

        /** 点击时选中的混合集合原子恢复：规则停课行删除 + 纯覆盖行解除暂停 + 调课组整组撤销。 */
        RESTORE_SELECTION
    }

    /**
     * 行级调课关联断言：[NoMove] 是显式断言「该行不得携带任何 moveId」，
     * 绝不与「不校验」共用 null；[Move] 要求行 moveId 精确相等。
     */
    sealed interface ExpectedMove {
        data object NoMove : ExpectedMove
        data class Move(val moveId: String) : ExpectedMove
    }

    /** 行身份锚点：稳定 id + 不可复用版本令牌（任何真实写入都会换新令牌）。 */
    data class RowIdentity(val id: String, val mutationToken: String)

    /** 点击时冻结的单行选择：id+令牌+结构断言（rule/date/move/role），绝不按 rule/date 扩展。 */
    data class SelectedOverride(
        val id: String,
        val mutationToken: String,
        val ruleId: String?,
        val dateIso: String,
        val expectedMove: ExpectedMove,
        val role: String?
    )

    /** 点击时冻结的整组调课选择：[expectedMembers] 必须覆盖该 moveId 在命令学期内的全部行。 */
    data class SelectedMove(
        val moveId: String,
        val expectedMembers: List<RowIdentity>
    )

    /**
     * 点击瞬间固化的不可变恢复命令：包含全部行/调课组选择与版本令牌断言。
     * 单事务执行：按选中 ID 精确读取（禁止 rule/date 扩展）→ 逐条验
     * token+term+rule/date+NoMove/Move+role+调课成员集合 → 任一冲突整组
     * [OccurrenceCommandStatus.CONFLICT] 零写；全部消失
     * [OccurrenceCommandStatus.NO_CHANGE]；部分消失 CONFLICT（保守）。
     * 不设持久回执表：验证「旧 ID 消失→NO_CHANGE、活跃行身份变更→CONFLICT」即可。
     */
    data class RestoreCommand(
        val commandId: String,
        val accountProfileId: String,
        val termId: String,
        val intent: RestoreIntent,
        val selectedRows: List<SelectedOverride> = emptyList(),
        val selectedMoves: List<SelectedMove> = emptyList()
    )

    /**
     * 「恢复暂停」命令；行为委托 TimetableRepository，语义不变。
     */
    suspend fun unpauseOccurrence(
        termId: String,
        overrideId: String,
        expectedMoveId: String? = null
    ): OccurrenceCommandResult = timetableRepository.unpauseOccurrence(
        termId = termId,
        overrideId = overrideId,
        expectedMoveId = expectedMoveId
    )

    /**
     * 「撤销调课」命令；行为委托 TimetableRepository，语义不变。
     */
    suspend fun undoMove(
        termId: String,
        moveId: String
    ): OccurrenceCommandResult = timetableRepository.undoMove(termId, moveId)

    /**
     * 「恢复规则停课」命令；行为委托 TimetableRepository，语义不变。
     */
    suspend fun restoreRuleCancellation(
        termId: String,
        ruleIds: List<String>,
        date: LocalDate,
        expectedMoveId: String? = null
    ): OccurrenceCommandResult = timetableRepository.restoreRuleCancellation(
        termId = termId,
        ruleIds = ruleIds,
        date = date,
        expectedMoveId = expectedMoveId
    )

    /**
     * 执行点击时冻结的不可变恢复命令；行为委托 TimetableRepository，语义不变。
     */
    suspend fun executeRestoreCommand(command: RestoreCommand): OccurrenceCommandResult =
        timetableRepository.executeRestoreCommand(command)

    /**
     * 旧兼容适配器（R5 起保守 no-op）；行为委托 TimetableRepository，语义不变。
     */
    @Deprecated(
        "模糊选择已于 R5 废止：改用 executeRestoreCommand(RestoreCommand) 提交点击时冻结的完整不可变命令"
    )
    suspend fun restoreCanceledOccurrence(
        date: LocalDate,
        ruleId: String?,
        overrideId: String? = null,
        mergedRuleIds: List<String> = emptyList()
    ): OccurrenceRestoreResult = timetableRepository.restoreCanceledOccurrence(
        date = date,
        ruleId = ruleId,
        overrideId = overrideId,
        mergedRuleIds = mergedRuleIds
    )

    suspend fun deleteCourseScheduleRule(ruleId: String) =
        timetableRepository.deleteCourseScheduleRule(ruleId)

    @JvmOverloads
    suspend fun deleteCourseScheduleRules(
        ruleIds: List<String>,
        contextDate: LocalDate? = null
    ) = timetableRepository.deleteCourseScheduleRules(
        ruleIds = ruleIds,
        contextDate = contextDate
    )

    /**
     * 为「时间待定」的实践环节课程设置排课时间与地点；行为委托 TimetableRepository，语义不变。
     */
    suspend fun scheduleUnscheduledPracticalCourse(
        unscheduled: UnscheduledAcademicCourseEntity,
        rawRoom: String,
        weekday: DayOfWeek,
        startPeriod: Int,
        endPeriod: Int,
        weeks: Set<Int>,
        colorArgb: Long = 0xFF0D9488
    ) = timetableRepository.scheduleUnscheduledPracticalCourse(
        unscheduled = unscheduled,
        rawRoom = rawRoom,
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        weeks = weeks,
        colorArgb = colorArgb
    )

    suspend fun saveCalendarOverride(override: CalendarOverride) =
        termRepository.saveCalendarOverride(override)

    suspend fun deleteCalendarOverride(id: String) =
        termRepository.deleteCalendarOverride(id)

    @JvmOverloads
    suspend fun confirmBuildingAliasForRoom(
        rawRoom: String,
        buildingId: String,
        activeCampusId: String? = null
    ) = timetableRepository.confirmBuildingAliasForRoom(
        rawRoom = rawRoom,
        buildingId = buildingId,
        activeCampusId = activeCampusId
    )

    suspend fun previewCourseImportDiff(
        termId: String,
        source: CourseSource,
        candidates: List<ImportCourseCandidate>
    ): ImportDiffPreview = timetableRepository.previewCourseImportDiff(
        termId = termId,
        source = source,
        candidates = candidates
    )

    /**
     * 确认提交课表导入结果；行为委托 TimetableRepository，语义不变。
     */
    suspend fun commitCourseImportDiff(
        preview: ImportDiffPreview,
        overwriteUserConflicts: Boolean = false,
        setAsCurrentTerm: Boolean = true
    ) = timetableRepository.commitCourseImportDiff(
        preview = preview,
        overwriteUserConflicts = overwriteUserConflicts,
        setAsCurrentTerm = setAsCurrentTerm
    )

    suspend fun saveImportedGrades(
        attempts: List<GradeAttempt>,
        officialGpa: BigDecimal? = null,
        officialNote: String? = null,
        replaceExisting: Boolean = false
    ) = gradesRepository.saveImportedGrades(
        attempts = attempts,
        officialGpa = officialGpa,
        officialNote = officialNote,
        replaceExisting = replaceExisting
    )

    suspend fun saveStudentProfile(profile: StudentProfile) =
        gradesRepository.saveStudentProfile(profile)

    suspend fun saveImportedExams(
        exams: List<ExamScheduleItem>,
        replaceExisting: Boolean = true,
        targetTermId: String? = null,
        targetTermComplete: Boolean = true,
        completedTermIds: Set<String> = emptySet()
    ) = examRepository.saveImportedExams(
        exams = exams,
        replaceExisting = replaceExisting,
        targetTermId = targetTermId,
        targetTermComplete = targetTermComplete,
        completedTermIds = completedTermIds
    )

    suspend fun setAutoSyncEnabled(enabled: Boolean) =
        syncOrchestrator.setAutoSyncEnabled(enabled)

    suspend fun saveAuthorizedSyncCredentials(
        studentId: String,
        passwordPlain: String,
        xnm: String,
        xqm: String
    ) = syncOrchestrator.saveAuthorizedSyncCredentials(
        studentId = studentId,
        passwordPlain = passwordPlain,
        xnm = xnm,
        xqm = xqm
    )

    /**
     * 应用全部实时教务同步数据。实现已迁至 SyncOrchestrator，门面仅立即委托。
     */
    suspend fun applyLiveSyncData(
        syncData: HautLiveSyncResult,
        overwriteUserConflicts: Boolean = false
    ) = syncOrchestrator.applyLiveSyncData(syncData, overwriteUserConflicts)

    /**
     * 后台或一键动态刷新。实现已迁至 SyncOrchestrator，门面仅立即委托。
     */
    suspend fun performDynamicAcademicSync(force: Boolean = false): Result<HautLiveSyncResult>? =
        syncOrchestrator.performDynamicAcademicSync(force)

    /**
     * 读取本地加密持久化的图书馆座位预约会话快照（不发起网络请求）。
     */
    fun readCachedLibrarySeatSession(): LibrarySeatSessionSnapshot? =
        librarySeatRepository.readCachedLibrarySeatSession()

    /**
     * 清理本地加密持久化的图书馆座位预约会话快照。
     */
    suspend fun clearLibrarySeatSession() =
        librarySeatRepository.clearLibrarySeatSession()

    /**
     * 当用户在 WebView 内手动完成统一身份认证后，捕获并持久化最新 Token 与 Cookie。
     */
    suspend fun captureLibrarySeatWebToken(rawToken: String): LibrarySeatSessionSnapshot? =
        librarySeatRepository.captureLibrarySeatWebToken(rawToken)

    /**
     * 确保图书馆座位预约拥有有效的持久化会话（三级保活）。
     */
    suspend fun ensureLibrarySeatSession(
        forceRefresh: Boolean = false
    ): LibrarySeatSessionState =
        librarySeatRepository.ensureLibrarySeatSession(forceRefresh)

    /**
     * 绑定/更新统一身份认证账号密码，并立即执行后台登录与 Cookie 持久化。
     */
    suspend fun saveLibrarySeatCredentialsAndLogin(
        studentId: String,
        passwordPlain: String
    ): LibrarySeatSessionState =
        librarySeatRepository.saveLibrarySeatCredentialsAndLogin(studentId, passwordPlain)

    /**
     * 拉取用户当前有效的图书馆座位预约、进行中预约列表、历史预约记录与暂离规则时长。
     */
    suspend fun fetchLibrarySeatOverview(): Result<LibrarySeatOverviewSnapshot> =
        librarySeatRepository.fetchLibrarySeatOverview()

    /**
     * 执行图书馆座位控制动作；遇到会话过期时按原路径后台重登并重试一次。
     */
    suspend fun executeLibrarySeatAction(
        action: LibrarySeatActionType,
        bookingId: Long? = null
    ): Result<LibrarySeatActionOutcome> =
        librarySeatRepository.executeLibrarySeatAction(action, bookingId)

    suspend fun setGpaPrivacyShield(enabled: Boolean) =
        gradesRepository.setGpaPrivacyShield(enabled)

    suspend fun setUseDynamicColor(enabled: Boolean) =
        campusServicesRepository.setUseDynamicColor(enabled)

    suspend fun setThemeMode(mode: String) =
        campusServicesRepository.setThemeMode(mode)

    suspend fun setUiDesignStyle(styleKey: String) =
        campusServicesRepository.setUiDesignStyle(styleKey)

    suspend fun applyCuratedThemePreset(
        designStyleKey: String,
        paletteKey: String,
        clearWallpaper: Boolean = false
    ) = campusServicesRepository.applyCuratedThemePreset(
        designStyleKey = designStyleKey,
        paletteKey = paletteKey,
        clearWallpaper = clearWallpaper
    )

    suspend fun setThemePalette(paletteKey: String, customSeedArgb: Long? = null) =
        campusServicesRepository.setThemePalette(paletteKey, customSeedArgb)

    suspend fun saveCustomWallpaperTheme(
        localPath: String,
        primarySeedArgb: Long,
        extractedSwatches: List<Long>,
        enableLiquidGlass: Boolean = false
    ) = campusServicesRepository.saveCustomWallpaperTheme(
        localPath = localPath,
        primarySeedArgb = primarySeedArgb,
        extractedSwatches = extractedSwatches,
        enableLiquidGlass = enableLiquidGlass
    )

    suspend fun saveExtractedDesktopSwatches(
        primarySeedArgb: Long,
        extractedSwatches: List<Long>
    ) = campusServicesRepository.saveExtractedDesktopSwatches(primarySeedArgb, extractedSwatches)

    suspend fun updateWallpaperVisualParams(
        alpha: Float? = null,
        blurDp: Float? = null,
        cardAlpha: Float? = null
    ) = campusServicesRepository.updateWallpaperVisualParams(alpha, blurDp, cardAlpha)

    suspend fun resetWallpaperVisualParams() =
        campusServicesRepository.resetWallpaperVisualParams()

    suspend fun clearCustomWallpaperTheme() =
        campusServicesRepository.clearCustomWallpaperTheme()

    suspend fun updateHomeCardLayout(
        order: List<String>,
        hidden: Set<String>,
        fullWidth: Set<String>
    ) = campusServicesRepository.updateHomeCardLayout(order, hidden, fullWidth)

    suspend fun resetHomeCardLayout() =
        campusServicesRepository.resetHomeCardLayout()

    suspend fun getActiveThemeAccentArgb(): Int =
        campusServicesRepository.getActiveThemeAccentArgb()

    suspend fun setAutoCheckUpdateEnabled(enabled: Boolean) =
        campusServicesRepository.setAutoCheckUpdateEnabled(enabled)

    suspend fun getNetworkProxyConfigSnapshot(): NetworkProxyConfig =
        campusServicesRepository.getNetworkProxyConfigSnapshot()

    suspend fun updateNetworkProxyConfig(config: NetworkProxyConfig) =
        campusServicesRepository.updateNetworkProxyConfig(config)

    suspend fun resetNetworkProxyConfig() =
        campusServicesRepository.resetNetworkProxyConfig()

    suspend fun saveCustomChannelUrl(channelId: String, inviteUrl: String) =
        campusServicesRepository.saveCustomChannelUrl(channelId, inviteUrl)

    suspend fun saveCustomClubGroupNumber(clubId: String, groupNumber: String) =
        campusServicesRepository.saveCustomClubGroupNumber(clubId, groupNumber)

    /**
     * 拉取最新校园频道与学生社团名录；行为委托 CampusServicesRepository，语义不变。
     */
    suspend fun refreshCampusCommunityContent(): Result<cn.edu.haut.campus.core.model.CampusCommunityCloudCatalog> =
        campusServicesRepository.refreshCampusCommunityContent()

    /**
     * 刷新校园互助广场云端快照；行为委托 MomentsRepository，语义不变。
     */
    suspend fun refreshCampusMomentsSnapshot(): Result<cn.edu.haut.campus.core.model.CampusMomentsSnapshot> =
        momentsRepository.refreshCampusMomentsSnapshot()

    suspend fun publishMomentsPost(
        type: cn.edu.haut.campus.core.model.MomentsPostType,
        title: String,
        category: String,
        campus: String,
        location: String,
        description: String,
        contact: String,
        rewardNote: String = "",
        contactVisibility: String = "PUBLIC"
    ): Result<cn.edu.haut.campus.core.model.MomentsPostItem> =
        momentsRepository.publishMomentsPost(
            type = type,
            title = title,
            category = category,
            campus = campus,
            location = location,
            description = description,
            contact = contact,
            rewardNote = rewardNote,
            contactVisibility = contactVisibility
        )

    suspend fun addMomentsComment(
        postId: String,
        content: String,
        contactHint: String = "",
        clientMessageId: String? = null
    ): Result<cn.edu.haut.campus.core.model.MomentsPostItem> =
        momentsRepository.addMomentsComment(
            postId = postId,
            content = content,
            contactHint = contactHint,
            clientMessageId = clientMessageId
        )

    suspend fun submitMomentsContactRequest(
        postId: String,
        message: String,
        contactInfo: String,
        clientMessageId: String? = null
    ): Result<CampusCloudOidcHelper.MomentsContactRequestDto> =
        momentsRepository.submitMomentsContactRequest(
            postId = postId,
            message = message,
            contactInfo = contactInfo,
            clientMessageId = clientMessageId
        )

    suspend fun fetchMomentsContactRequestsResult(
        postId: String
    ): CampusCloudOidcHelper.MomentsContactRequestsListResult =
        momentsRepository.fetchMomentsContactRequestsResult(postId)

    suspend fun fetchMomentsContactRequests(
        postId: String
    ): List<CampusCloudOidcHelper.MomentsContactRequestDto> =
        momentsRepository.fetchMomentsContactRequests(postId)

    suspend fun claimLegacyMomentsPostOwnership(
        postId: String,
        claimToken: String
    ): Result<cn.edu.haut.campus.core.model.MomentsPostItem> =
        momentsRepository.claimLegacyMomentsPostOwnership(
            postId = postId,
            claimToken = claimToken
        )

    suspend fun handleMomentsContactRequestAction(
        postId: String,
        requestId: String,
        action: String,
        replyMessage: String? = null,
        reason: String? = null,
        clientMessageId: String? = null
    ): Result<CampusCloudOidcHelper.MomentsContactRequestDto> =
        momentsRepository.handleMomentsContactRequestAction(
            postId = postId,
            requestId = requestId,
            action = action,
            replyMessage = replyMessage,
            reason = reason,
            clientMessageId = clientMessageId
        )

    suspend fun reportMomentsPost(
        postId: String,
        reason: String = "用户举报不良或虚假失物招领信息"
    ): Result<Boolean> = momentsRepository.reportMomentsPost(
        postId = postId,
        reason = reason
    )

    suspend fun toggleMomentsPostLike(postId: String) =
        momentsRepository.toggleMomentsPostLike(postId)

    suspend fun toggleMomentsPostFavorite(postId: String) =
        momentsRepository.toggleMomentsPostFavorite(postId)

    suspend fun toggleMomentsPostResolved(postId: String): Result<Boolean> =
        momentsRepository.toggleMomentsPostResolved(postId)

    suspend fun deleteMomentsPost(postId: String): Result<Boolean> =
        momentsRepository.deleteMomentsPost(postId)

    suspend fun publishMomentsBookListing(
        title: String,
        isbn: String,
        authorPublisher: String,
        courseName: String,
        majorTrack: cn.edu.haut.campus.core.model.CampusMajorTrack,
        campus: String,
        conditionLabel: String,
        priceYuan: Int,
        originalPriceYuan: Int,
        deliveryMode: String,
        contact: String
    ): Result<cn.edu.haut.campus.core.model.MomentsTextbookListing> =
        momentsRepository.publishMomentsBookListing(
            title = title,
            isbn = isbn,
            authorPublisher = authorPublisher,
            courseName = courseName,
            majorTrack = majorTrack,
            campus = campus,
            conditionLabel = conditionLabel,
            priceYuan = priceYuan,
            originalPriceYuan = originalPriceYuan,
            deliveryMode = deliveryMode,
            contact = contact
        )

    suspend fun toggleMomentsBookFavorite(bookId: String) =
        momentsRepository.toggleMomentsBookFavorite(bookId)

    suspend fun toggleMomentsBookSold(bookId: String): Result<Boolean> =
        momentsRepository.toggleMomentsBookSold(bookId)

    suspend fun deleteMomentsBookListing(bookId: String): Result<Boolean> =
        momentsRepository.deleteMomentsBookListing(bookId)

    suspend fun upsertAntiLostTag(
        tagCode: String = "",
        itemName: String,
        category: String,
        campus: String,
        ownerContact: String,
        returnNote: String,
        status: String = "NORMAL"
    ): Result<cn.edu.haut.campus.core.model.AntiLostTagItem> =
        momentsRepository.upsertAntiLostTag(
            tagCode = tagCode,
            itemName = itemName,
            category = category,
            campus = campus,
            ownerContact = ownerContact,
            returnNote = returnNote,
            status = status
        )

    suspend fun reportAntiLostTagFound(
        tagCode: String,
        finderName: String,
        foundLocation: String,
        finderContact: String,
        message: String
    ): Result<Boolean> = momentsRepository.reportAntiLostTagFound(
        tagCode = tagCode,
        finderName = finderName,
        foundLocation = foundLocation,
        finderContact = finderContact,
        message = message
    )

    suspend fun setTimelineZoomScale(scale: Float) =
        campusServicesRepository.setTimelineZoomScale(scale)

    suspend fun saveWidgetConfig(config: WidgetInstanceConfig) =
        campusServicesRepository.saveWidgetConfig(config)

    suspend fun getWidgetConfigOrDefault(appWidgetId: Int, defaultType: cn.edu.haut.campus.core.model.WidgetType): WidgetInstanceConfig =
        campusServicesRepository.getWidgetConfigOrDefault(appWidgetId, defaultType)

    suspend fun updateAllWidgetDefaults(
        compact: Boolean? = null,
        privacyMode: Boolean? = null
    ) = campusServicesRepository.updateAllWidgetDefaults(compact, privacyMode)

    suspend fun getReminderConfigSnapshot(): ReminderGlobalConfig =
        campusServicesRepository.getReminderConfigSnapshot()

    suspend fun getExamSchedulesSnapshot(): List<ExamScheduleItem> =
        examRepository.getExamSchedulesSnapshot()

    suspend fun getReminderEventOverridesSnapshot(): List<ReminderEventOverride> =
        campusServicesRepository.getReminderEventOverridesSnapshot()

    suspend fun updateReminderGlobalConfig(transform: (ReminderGlobalConfig) -> ReminderGlobalConfig) =
        campusServicesRepository.updateReminderGlobalConfig(transform)

    suspend fun upsertReminderEventOverride(override: ReminderEventOverride) =
        campusServicesRepository.upsertReminderEventOverride(override)

    suspend fun removeReminderEventOverride(scopeKey: String) =
        campusServicesRepository.removeReminderEventOverride(scopeKey)

    suspend fun appendReminderDiagnosticLog(message: String, reconcileSummary: String? = null) =
        campusServicesRepository.appendReminderDiagnosticLog(message, reconcileSummary)

    suspend fun setCampusServiceFeatureFlag(
        utilitiesEnabled: Boolean? = null,
        libraryNativeEnabled: Boolean? = null,
        librarySeatEnabled: Boolean? = null,
        focusEnabled: Boolean? = null,
        showTodayFocusQuickPill: Boolean? = null,
        timetableInterchangeEnabled: Boolean? = null
    ) = campusServicesRepository.setCampusServiceFeatureFlag(
        utilitiesEnabled = utilitiesEnabled,
        libraryNativeEnabled = libraryNativeEnabled,
        librarySeatEnabled = librarySeatEnabled,
        focusEnabled = focusEnabled,
        showTodayFocusQuickPill = showTodayFocusQuickPill,
        timetableInterchangeEnabled = timetableInterchangeEnabled
    )

    suspend fun setAllowUnverifiedUtilityTransport(allowed: Boolean) =
        campusServicesRepository.setAllowUnverifiedUtilityTransport(allowed)

    suspend fun setMealRecommendEnabled(enabled: Boolean) =
        campusServicesRepository.setMealRecommendEnabled(enabled)

    suspend fun isMealRecommendEnabledSnapshot(): Boolean =
        campusServicesRepository.isMealRecommendEnabledSnapshot()

    suspend fun getLastMealRecommendBlockKeySnapshot(): String? =
        campusServicesRepository.getLastMealRecommendBlockKeySnapshot()

    suspend fun markMealRecommendNotified(blockKey: String) =
        campusServicesRepository.markMealRecommendNotified(blockKey)

    suspend fun getCanteenDishReviewsSnapshot(): List<cn.edu.haut.campus.core.domain.CanteenDishReview> =
        campusServicesRepository.getCanteenDishReviewsSnapshot()

    suspend fun saveCanteenDishReview(
        canteenBuildingId: String,
        dishName: String,
        stallLocation: String = "",
        priceYuan: Int? = null,
        stars: Int,
        tags: List<String> = emptyList(),
        comment: String = ""
    ) = campusServicesRepository.saveCanteenDishReview(
        canteenBuildingId = canteenBuildingId,
        dishName = dishName,
        stallLocation = stallLocation,
        priceYuan = priceYuan,
        stars = stars,
        tags = tags,
        comment = comment
    )

    suspend fun deleteCanteenDishReview(canteenBuildingId: String, dishName: String) =
        campusServicesRepository.deleteCanteenDishReview(canteenBuildingId, dishName)

    suspend fun setSelectedFocusMinutes(minutes: Int) =
        campusServicesRepository.setSelectedFocusMinutes(minutes)

    fun readUtilityAccountResult(): UtilityAccountReadResult =
        campusServicesRepository.readUtilityAccountResult()

    /**
     * 在线查询本人已绑定房间水电并同步缓存；行为委托 CampusServicesRepository，语义不变。
     */
    suspend fun saveAndRefreshOnlineUtilities(
        rawAccount: String? = null,
        allowUnverified: Boolean = false
    ): Result<List<OnlineUtilityRoomSnapshot>> = campusServicesRepository.saveAndRefreshOnlineUtilities(
        rawAccount = rawAccount,
        allowUnverified = allowUnverified
    )

    suspend fun performPeriodicUtilitySampleIfEligible(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean = campusServicesRepository.performPeriodicUtilitySampleIfEligible(nowEpochMs)

    suspend fun updateUtilityNotificationConfig(
        enabled: Boolean? = null,
        dailyHour: Int? = null,
        lowBalanceThreshold: Float? = null
    ) = campusServicesRepository.updateUtilityNotificationConfig(enabled, dailyHour, lowBalanceThreshold)

    suspend fun notifyDailyUtilitySummaryIfEligible(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean = campusServicesRepository.notifyDailyUtilitySummaryIfEligible(nowEpochMs)

    suspend fun sendTestUtilityNotification(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean = campusServicesRepository.sendTestUtilityNotification(nowEpochMs)

    suspend fun updateOnlineRoomCampus(roomId: String, campusCode: String?) =
        campusServicesRepository.updateOnlineRoomCampus(roomId, campusCode)

    suspend fun clearUtilityAccountAndOnlineCache() =
        campusServicesRepository.clearUtilityAccountAndOnlineCache()

    suspend fun saveUtilityMeter(
        name: String,
        campusName: String,
        roomName: String,
        unit: String,
        lowThreshold: Double
    ) = campusServicesRepository.saveUtilityMeter(name, campusName, roomName, unit, lowThreshold)

    suspend fun deleteUtilityMeter(meterId: String) =
        campusServicesRepository.deleteUtilityMeter(meterId)

    suspend fun addUtilityReading(
        meterId: String,
        value: Double,
        topUpValue: Double = 0.0,
        note: String = ""
    ) = campusServicesRepository.addUtilityReading(meterId, value, topUpValue, note)

    suspend fun deleteUtilityReading(readingId: String) =
        campusServicesRepository.deleteUtilityReading(readingId)

    suspend fun applyInterchangeCalendarMetadata(
        termId: String,
        semesterStart: LocalDate?,
        totalWeeks: Int?,
        sectionTimes: List<PeriodTime>
    ) = termRepository.applyInterchangeCalendarMetadata(
        termId = termId,
        semesterStart = semesterStart,
        totalWeeks = totalWeeks,
        sectionTimes = sectionTimes
    )

    /**
     * 完全清除本地个人课程、成绩、导入审计、水电账号与历史、自习专注及学校网页会话（保留公开的校区建筑字典与节次模板）。
     */
    suspend fun wipeAllPersonalData() =
        profileDataWiper.wipeAllPersonalData()

    suspend fun getDirectScheduleDataSnapshot(): DirectScheduleData =
        timetableRepository.getDirectScheduleDataSnapshot()

    suspend fun setSelectedCampusId(campusId: String) =
        campusServicesRepository.setSelectedCampusId(campusId)

    suspend fun saveCustomCampusZonesJson(json: String) =
        campusServicesRepository.saveCustomCampusZonesJson(json)

    // --- XPEACH SSO Cloud Account & Wallet Binding (Optional; Zero-SSO Local Mode Preserved) ---
    val cloudIdentityBindingFlow: Flow<CloudIdentityBindingEntity?> =
        cloudAndParcelDao.observeCloudIdentityBinding()


    /**
     * 云身份会话快照：请求开始时捕获 (profileId, cloudUserId, generation, apiBase, token subject)，
     * 网络响应写回前必须重新逐项比对——任一项不一致即丢弃，绝不写回或复活过期绑定。
     */
    data class CloudIdentitySessionSnapshot(
        val profileId: String,
        val cloudUserId: String,
        val generation: Long,
        val apiBaseUrl: String,
        val ssoSubject: String
    )

    /** 统一快照读取入口：身份锁内原子读取 (binding, generation, apiBase)。实现已迁至 CloudIdentityRepository，门面仅委托。 */
    suspend fun snapshotCloudIdentitySession(): CloudIdentitySessionSnapshot? =
        cloudIdentityRepository.snapshotCloudIdentitySession()

    /** 提交云委托/写回前的即时复核：切号、解绑、同号重登、换 base-url 一律视为已失效。实现已迁至 CloudIdentityRepository，门面仅委托。 */
    suspend fun isCloudIdentitySnapshotCurrent(snapshot: CloudIdentitySessionSnapshot): Boolean =
        cloudIdentityRepository.isCloudIdentitySnapshotCurrent(snapshot)


    @Volatile
    internal var cloudHttpTransportForTest: ((String, String, String?, String?, Map<String, String>) -> String)? = null

    /**
     * 仅测试用：同步提交屏障内的挂起闩锁，在身份复核通过之后、首个落库写之前调用。
     * 生产恒为 null（零开销）；测试注入后可确定性地把身份变更（解绑/换号/重登/换 base-url）
     * 插入"复核→写入"窗口，断言提交屏障按 booking→identity 锁序拦截过期写入。
     */
    internal var syncCommitBarrierHookForTest: (suspend () -> Unit)?
        get() = syncOrchestrator.syncCommitBarrierHookForTest
        set(value) {
            syncOrchestrator.syncCommitBarrierHookForTest = value
        }

    /**
     * 仅测试用：云端计划"401→令牌刷新→重提交"窗口的确定性闩锁（生产恒为 null）。
     * 在身份复验①之后、resolveValidCloudAccessToken(forceRefresh) 之前调用，
     * 供测试把切号/换址/换学号交错确定性地插入重提窗口。
     */
    internal var cloudSubmitRetryBarrierHookForTest: (suspend () -> Unit)?
        get() = libraryBookingPlanRepository.cloudSubmitRetryBarrierHookForTest
        set(value) {
            libraryBookingPlanRepository.cloudSubmitRetryBarrierHookForTest = value
        }

    suspend fun saveVerifiedXpeachCloudAccount(
        profile: cn.edu.haut.campus.core.campusservices.XpeachCloudAccountProfile
    ): CloudIdentityBindingEntity =
        cloudIdentityRepository.saveVerifiedXpeachCloudAccount(profile)

    suspend fun refreshXpeachWalletBalance(): Double? =
        cloudIdentityRepository.refreshXpeachWalletBalance()


    /**
     * 与 https://campus.yellowpeachxgp.com 双向同步。实现已迁至 SyncOrchestrator，门面仅立即委托。
     */
    suspend fun syncWithCampusCloud(): Result<CloudSyncSnapshotResult> =
        syncOrchestrator.syncWithCampusCloud()

    /** 显式重新确认学校绑定并恢复云端预约计划。实现已迁至 SyncOrchestrator，门面仅立即委托。 */
    suspend fun reconfirmAndRestoreCloudBookingPlans(): Result<CloudSyncSnapshotResult> =
        syncOrchestrator.reconfirmAndRestoreCloudBookingPlans()

    suspend fun unlinkXpeachCloudAccountAndRevokeCloudPlans() =
        cloudIdentityRepository.unlinkXpeachCloudAccountAndRevokeCloudPlans()

    // --- Dual-Engine Library Reservation Plans (ADR-005 Dynamic Target T: T-30m / T-5m / T) ---
    private val libraryBookingRuntimePort = object : LibraryBookingRuntimePort {
        override val preferencesFlow: Flow<AppPreferencesState>
            get() = this@CampusRepository.preferencesFlow

        override val repoScope: CoroutineScope
            get() = this@CampusRepository.repoScope

        override val isSessionActive: Boolean
            get() = this@CampusRepository.isSessionActive

        override fun requireActiveSession() =
            this@CampusRepository.requireActiveSession()

        override fun cloudHttpTransportForTest(): CloudHttpTransportForTest? =
            this@CampusRepository.cloudHttpTransportForTest

        override suspend fun syncWithCampusCloud(): Result<CloudSyncSnapshotResult> =
            this@CampusRepository.syncWithCampusCloud()
    }

    private val libraryBookingPlanRepository = LibraryBookingPlanRepository(
        environment = repositoryEnvironment,
        appContext = appContext,
        cloudIdentityRepository = cloudIdentityRepository,
        librarySeatRepository = librarySeatRepository,
        bookingPlanStateMutex = bookingPlanStateMutex,
        runtimePort = libraryBookingRuntimePort
    )

    private val libraryBookingExecutionEngine = LibraryBookingExecutionEngine(
        environment = repositoryEnvironment,
        appContext = appContext,
        cloudIdentityRepository = cloudIdentityRepository,
        librarySeatRepository = librarySeatRepository,
        bookingPlanStateMutex = bookingPlanStateMutex,
        runtimePort = libraryBookingRuntimePort
    )

    val allLibraryBookingPlansForAuditFlow: Flow<List<LibraryBookingPlanEntity>> =
        libraryBookingPlanRepository.allLibraryBookingPlansForAuditFlow

    /** 无法证明唯一归属的升级遗留记录（待认领/本地审计专用，普通登录账号页面与同步不可见） */
    val unattributedLegacyBookingPlansFlow: Flow<List<LibraryBookingPlanEntity>> =
        libraryBookingPlanRepository.unattributedLegacyBookingPlansFlow


    val libraryBookingPlansFlow: Flow<List<LibraryBookingPlanEntity>> =
        libraryBookingPlanRepository.libraryBookingPlansFlow

    suspend fun getAllLibraryBookingPlansForAuditSnapshot(): List<LibraryBookingPlanEntity> =
        libraryBookingPlanRepository.getAllLibraryBookingPlansForAuditSnapshot()

    /** UI 确认绑定的身份；仓库入口必须消费它，不能另取新身份执行旧确认。 */
    data class BookingSubmissionIdentity(
        val profileId: String,
        val cloudUserId: String,
        val generation: Long,
        val apiBaseUrl: String,
        val studentNo: String
    )

    suspend fun snapshotBookingSubmissionIdentity(): BookingSubmissionIdentity =
        libraryBookingPlanRepository.snapshotBookingSubmissionIdentity()

    suspend fun createDualEngineBookingPlan(
        executionMode: BookingExecutionMode,
        targetDate: LocalDate,
        targetTimeText: String,
        preferredAreaName: String,
        preferredSeatLabel: String,
        allowAlternativeSeat: Boolean = true,
        repeatDays: Int = 1,
        acceptedPriceMicropeach: Long? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
        expectedIdentity: BookingSubmissionIdentity? = null
    ): Result<LibraryBookingPlanEntity> =
        libraryBookingPlanRepository.createDualEngineBookingPlan(
            executionMode = executionMode,
            targetDate = targetDate,
            targetTimeText = targetTimeText,
            preferredAreaName = preferredAreaName,
            preferredSeatLabel = preferredSeatLabel,
            allowAlternativeSeat = allowAlternativeSeat,
            repeatDays = repeatDays,
            acceptedPriceMicropeach = acceptedPriceMicropeach,
            nowEpochMillis = nowEpochMillis,
            expectedIdentity = expectedIdentity
        )

    suspend fun loadLibraryPlanningRegions(date: String, refresh: Boolean = false): Result<List<LibraryStudyRegionItem>> =
        librarySeatRepository.loadLibraryPlanningRegions(date, refresh)

    suspend fun loadLibraryPlanningSeats(region: LibraryStudyRegionItem, date: String, refresh: Boolean = false): Result<LibraryRegionSeatsSnapshot> =
        librarySeatRepository.loadLibraryPlanningSeats(region, date, refresh)

    suspend fun fetchLibraryStudyRegions(
        bookingDate: String
    ): Result<List<LibraryStudyRegionItem>> =
        librarySeatRepository.fetchLibraryStudyRegions(bookingDate)

    suspend fun fetchLibraryRegionSeats(
        regionId: Long,
        bookingDate: String
    ): Result<LibraryRegionSeatsSnapshot> =
        librarySeatRepository.fetchLibraryRegionSeats(regionId, bookingDate)

    suspend fun advanceBookingPlanStage(
        planId: String,
        stage: TripleScheduleStage,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Result<LibraryBookingPlanEntity> =
        libraryBookingExecutionEngine.advanceBookingPlanStage(
            planId = planId,
            stage = stage,
            nowEpochMillis = nowEpochMillis
        )

    /**
     * 用户显式"现在执行"入口（与定时 stage API 分离）：仅接受 CLOUD_HOSTED 计划，
     * 由服务端窗口裁决是否可提前执行；客户端绝不替代云端定时引擎。
     */
    suspend fun executeCloudBookingPlanNow(
        planId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Result<LibraryBookingPlanEntity> =
        libraryBookingExecutionEngine.executeCloudBookingPlanNow(
            planId = planId,
            nowEpochMillis = nowEpochMillis
        )

    /**
     * 停止排队中或正在执行中的抢座委托（仅停止后续尝试与多日续期，绝不伪造学校已退座，也不自动取消已确认座位）：
     * - 若在未向学校发出外部写请求前停止，转入 CANCELLED；
     * - 若外部请求已发出或处于云端执行/未知状态，转入 DELEGATION_STOPPED_RESULT_UNKNOWN 保留待核对证据。
     */
    suspend fun stopInFlightOrQueuedDelegation(planId: String): LibraryBookingPlanEntity? =
        libraryBookingExecutionEngine.stopInFlightOrQueuedDelegation(planId)

    /**
     * 仅执行「停止委托 / 取消排队计划」，绝不根据刷新后的最新状态暗中升级为真实学校退座！
     * 如需释放学校端已锁定的座位，必须由用户在已确认状态下显式触发 [cancelActiveLibrarySeatBooking]。
     */
    suspend fun cancelDualEngineBookingPlan(planId: String) {
        libraryBookingExecutionEngine.cancelDualEngineBookingPlan(planId)
    }

    internal var liveSeatCancelObserverForTest: ((planId: String?, bookingId: String?) -> Unit)?
        get() = libraryBookingPlanRepository.liveSeatCancelObserverForTest
        set(value) {
            libraryBookingPlanRepository.liveSeatCancelObserverForTest = value
        }

    suspend fun cancelActiveLibrarySeatBooking(
        planId: String? = null,
        bookingId: String? = null
    ): Result<String> =
        libraryBookingPlanRepository.cancelActiveLibrarySeatBooking(
            planId = planId,
            bookingId = bookingId
        )

    suspend fun rescheduleActiveLocalBookingPlans() =
        libraryBookingExecutionEngine.rescheduleActiveLocalBookingPlans()

    // --- Parcel Hub (Multi-Source Express Aggregation, OTP Filter & Provenance Merge) ---
    val parcelItemsFlow: Flow<List<ParcelItemEntity>> =
        parcelHubRepository.parcelItemsFlow

    val parcelSourceConnectionsFlow: Flow<List<ParcelSourceConnectionEntity>> =
        parcelHubRepository.parcelSourceConnectionsFlow

    suspend fun upsertParcelItemFromInput(
        trackingNumberRaw: String,
        carrierName: String = "校园快递",
        providerId: String = "parcel.manual",
        pickupCodeInput: String? = null,
        stationNameInput: String? = null,
        itemTitleInput: String? = null,
        rawNotificationText: String? = null
    ): Result<ParcelItemEntity> = parcelHubRepository.upsertParcelItemFromInput(
        trackingNumberRaw = trackingNumberRaw,
        carrierName = carrierName,
        providerId = providerId,
        pickupCodeInput = pickupCodeInput,
        stationNameInput = stationNameInput,
        itemTitleInput = itemTitleInput,
        rawNotificationText = rawNotificationText
    )

    suspend fun markParcelPickedUp(parcelId: String) =
        parcelHubRepository.markParcelPickedUp(parcelId)

    suspend fun markAllStationParcelsPickedUp(stationName: String) =
        parcelHubRepository.markAllStationParcelsPickedUp(stationName)

    suspend fun deleteParcelById(parcelId: String) =
        parcelHubRepository.deleteParcelById(parcelId)

    // --- Xiaomi Smart Assistant Account, Phone Number SMS Binding & Express Sync ---
    fun getXiaomiAccounts(): List<XiaomiAccountCredential> =
        parcelHubRepository.getXiaomiAccounts()

    suspend fun saveXiaomiAccountFromWebLogin(
        token: String,
        cUserId: String,
        accountId: String,
        detectedPhones: List<String>
    ): Result<XiaomiAccountCredential> = parcelHubRepository.saveXiaomiAccountFromWebLogin(
        token = token,
        cUserId = cUserId,
        accountId = accountId,
        detectedPhones = detectedPhones
    )

    suspend fun sendXiaomiPhoneVerificationCode(
        accountIdOrId: String,
        phone: String
    ): Result<XiaomiApiSimpleResult> = parcelHubRepository.sendXiaomiPhoneVerificationCode(
        accountIdOrId = accountIdOrId,
        phone = phone
    )

    suspend fun verifyAndBindXiaomiPhone(
        accountIdOrId: String,
        phone: String,
        verificationCode: String
    ): Result<XiaomiAccountCredential> = parcelHubRepository.verifyAndBindXiaomiPhone(
        accountIdOrId = accountIdOrId,
        phone = phone,
        verificationCode = verificationCode
    )

    suspend fun addXiaomiPhoneDirectly(
        accountIdOrId: String,
        phone: String
    ): Result<XiaomiAccountCredential> = parcelHubRepository.addXiaomiPhoneDirectly(
        accountIdOrId = accountIdOrId,
        phone = phone
    )

    suspend fun unbindXiaomiPhone(
        accountIdOrId: String,
        phone: String
    ): Result<XiaomiAccountCredential> = parcelHubRepository.unbindXiaomiPhone(
        accountIdOrId = accountIdOrId,
        phone = phone
    )

    suspend fun removeXiaomiAccount(accountIdOrId: String) =
        parcelHubRepository.removeXiaomiAccount(accountIdOrId)

    suspend fun syncXiaomiExpressPackages(): Result<XiaomiSyncSummary> =
        parcelHubRepository.syncXiaomiExpressPackages()

    suspend fun fetchXiaomiParcelDetail(parcelId: String): Result<List<XiaomiTrackPoint>> =
        parcelHubRepository.fetchXiaomiParcelDetail(parcelId)

    // --- Unified Extension Plugins (dis-extension/v2 + dis-campus-plugin/v1) ---
    val installedExtensionPluginsFlow: Flow<List<InstalledExtensionPluginEntity>> =
        campusServicesRepository.installedExtensionPluginsFlow

    suspend fun ensureDefaultReferencePluginsInstalled() =
        campusServicesRepository.ensureDefaultReferencePluginsInstalled()

    suspend fun upsertExtensionPlugin(entity: InstalledExtensionPluginEntity) =
        campusServicesRepository.upsertExtensionPlugin(entity)

    suspend fun removeExtensionPlugin(pluginId: String) =
        campusServicesRepository.removeExtensionPlugin(pluginId)

    companion object {
        private const val LOG_TAG = "CampusRepository"

        /** 考试学期修复规则版本：审计行按 (ruleVersion, examRowId) 幂等。 */
        internal val EXAM_TERM_REPAIR_RULE_V2 = ExamRepository.EXAM_TERM_REPAIR_RULE_V2

        internal val MIGRATION_1_2 = CampusRepositoryMigrations.MIGRATION_1_2

        internal val MIGRATION_2_3 = CampusRepositoryMigrations.MIGRATION_2_3

        fun getInstance(context: Context): CampusRepository =
            checkNotNull(AccountSessionCoordinator.getInstance(context).currentSnapshot()?.repository) {
                "账号正在初始化或切换，请等待当前身份就绪"
            }

    }
}
