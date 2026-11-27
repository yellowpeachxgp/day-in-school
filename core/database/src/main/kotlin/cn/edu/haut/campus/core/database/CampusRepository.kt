package cn.edu.haut.campus.core.database

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.edu.haut.campus.core.academic.HautAcademicEndpoints
import cn.edu.haut.campus.core.academic.HautLiveAcademicClient
import cn.edu.haut.campus.core.academic.HautLiveSyncResult
import cn.edu.haut.campus.core.academic.AcademicReadException
import cn.edu.haut.campus.core.campusservices.AutomatedSeatBookingResult
import cn.edu.haut.campus.core.campusservices.BookingExecutionMode
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.campusservices.CloudSyncSnapshotResult
import cn.edu.haut.campus.core.campusservices.CloudTokenSecureVault
import cn.edu.haut.campus.core.campusservices.CloudSyncedParcelDto
import cn.edu.haut.campus.core.campusservices.DualEngineReservationPlanner
import cn.edu.haut.campus.core.campusservices.HautLibrarySeatAuthClient
import cn.edu.haut.campus.core.campusservices.LibraryRegionSeatsSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatActionOutcome
import cn.edu.haut.campus.core.campusservices.LibrarySeatActionType
import cn.edu.haut.campus.core.campusservices.LibrarySeatOverviewSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionExpiredException
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionState
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionVault
import cn.edu.haut.campus.core.campusservices.LibraryStudyRegionItem
import cn.edu.haut.campus.core.campusservices.OnlineUtilityRoomSnapshot
import cn.edu.haut.campus.core.campusservices.ParcelLifecycleStatus
import cn.edu.haut.campus.core.campusservices.SchoolWebSessionManager
import cn.edu.haut.campus.core.campusservices.TripleScheduleStage
import cn.edu.haut.campus.core.campusservices.UtilityAccountReadResult
import cn.edu.haut.campus.core.campusservices.UtilityAccountSecureStore
import cn.edu.haut.campus.core.campusservices.UtilityDailySample
import cn.edu.haut.campus.core.campusservices.UtilitySamplingPolicy
import cn.edu.haut.campus.core.campusservices.WanxiaoUtilityClient
import cn.edu.haut.campus.core.campusservices.XiaomiAccountCredential
import cn.edu.haut.campus.core.campusservices.XiaomiAccountSecureVault
import cn.edu.haut.campus.core.campusservices.XiaomiApiSimpleResult
import cn.edu.haut.campus.core.campusservices.XiaomiSyncSummary
import cn.edu.haut.campus.core.campusservices.XiaomiTrackPoint
import cn.edu.haut.campus.core.campusservices.cleanNullLiteral
import cn.edu.haut.campus.core.campusservices.cleanNullableLiteral
import cn.edu.haut.campus.core.campusservices.optCleanString
import cn.edu.haut.campus.core.campusservices.optNullableCleanString
import cn.edu.haut.campus.core.domain.CampusDiningRecommender
import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.domain.ImportDiffEngine
import cn.edu.haut.campus.core.domain.ReminderRevision
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.AcademicTermResolver
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
import cn.edu.haut.campus.core.model.HomeCardModule
import cn.edu.haut.campus.core.model.ImportAudit
import cn.edu.haut.campus.core.model.ImportChangeType
import cn.edu.haut.campus.core.model.ImportCourseCandidate
import cn.edu.haut.campus.core.model.ImportDiffPreview
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.PresetMirrorNode
import cn.edu.haut.campus.core.model.ProxyMode
import cn.edu.haut.campus.core.model.ReminderEventOverride
import cn.edu.haut.campus.core.model.ReminderGlobalConfig
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.ThemeVisualDefaults
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

val DEFAULT_HOME_CARD_ORDER: List<String> = HomeCardModule.defaultOrderIds

data class AppPreferencesState(
    val gpaPrivacyShieldEnabled: Boolean = false,
    val useDynamicColor: Boolean = false,
    val themeMode: String = "SYSTEM",
    val uiDesignStyle: String = "FLUTTER_M3",
    val themePaletteKey: String = "HAUT_BLUE",
    val customThemeSeedArgb: Long = 0xFF053968,
    val customWallpaperPath: String? = null,
    val wallpaperAlpha: Float = ThemeVisualDefaults.DEFAULT_WALLPAPER_ALPHA,
    val wallpaperBlurDp: Float = ThemeVisualDefaults.DEFAULT_WALLPAPER_BLUR_DP,
    val wallpaperCardAlpha: Float = ThemeVisualDefaults.DEFAULT_WALLPAPER_CARD_ALPHA,
    val extractedWallpaperColors: List<Long> = emptyList(),
    val homeCardOrder: List<String> = HomeCardModule.defaultOrderIds,
    val hiddenHomeCards: Set<String> = HomeCardModule.defaultHiddenIds,
    val fullWidthHomeCards: Set<String> = HomeCardModule.defaultFullWidthIds,
    val timelineZoomScale: Float = ThemeVisualDefaults.DEFAULT_TIMELINE_ZOOM_SCALE,
    val widgetDefaultCompact: Boolean = false,
    val widgetDefaultPrivacyMode: Boolean = false,
    val officialGpaValuePlain: String? = null,
    val officialGpaNote: String? = null,
    val hasSeededInitialMockData: Boolean = false,
    val hasCompletedOnboarding: Boolean = false,
    val autoSyncEnabled: Boolean = true,
    val autoCheckUpdateEnabled: Boolean = true,
    val savedStudentId: String? = null,
    val hasSavedCredentials: Boolean = false,
    val academicReauthenticationRequired: Boolean = false,
    val syncXnm: String = AcademicTermResolver.defaultAcademicYearCode(),
    val syncXqm: String = AcademicTermResolver.defaultSemesterCode(),
    val lastAutoSyncEpochMillis: Long? = null,
    val customChannelUrls: Map<String, String> = emptyMap(),
    val customClubGroupNumbers: Map<String, String> = emptyMap(),
    val campusCommunityCatalog: cn.edu.haut.campus.core.model.CampusCommunityCloudCatalog = cn.edu.haut.campus.core.model.CampusCommunityCloudCatalog(),
    val lastCampusContentSyncEpochMillis: Long? = null,
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
    val utilityDailyNotifyEnabled: Boolean = true,
    val utilityDailyNotifyHour: Int = 20,
    val utilityLowBalanceThreshold: Float = 15f,
    val lastUtilityDailyNotifyDate: String? = null,
    val mealRecommendEnabled: Boolean = true,
    val lastMealRecommendBlockKey: String? = null,
    val canteenDishReviews: List<cn.edu.haut.campus.core.domain.CanteenDishReview> = emptyList(),
    val campusMomentsSnapshot: cn.edu.haut.campus.core.model.CampusMomentsSnapshot = cn.edu.haut.campus.core.model.CampusMomentsSnapshot(),
    val selectedFocusMinutes: Int = 25,
    val networkProxyConfig: NetworkProxyConfig = NetworkProxyConfig(),
    val selectedCampusId: String = "auto",
    val customCampusZonesJson: String = "[]"
)

/** 微桃 → 🍑 整数/小数自适应显示（1.0 显示为 "1"，0.5 显示为 "0.5"） */
private fun formatMicropeachAsPeach(micropeach: Long): String {
    val whole = micropeach / 10_000L
    val rem = micropeach % 10_000L
    return if (rem == 0L) whole.toString() else (micropeach / 10_000.0).toString()
}

/**
 * 云端预约计划报价漂移冲突（R3 §5 价格钉死诚实再确认）：
 * 客户端按创建时向用户展示的价格提交 expected_price_micropeach，云端调价后以 409 拒绝。
 * UI 必须如实向用户展示新旧价格并等待其重新发起创建——绝不自动按新价静默重试，
 * 否则会在用户不知情的情况下改签合同价格。
 */
class CloudPlanPriceDriftException(
    /** 创建时向用户展示/钉入请求的价格（微桃） */
    val expectedPriceMicropeach: Long,
    /** 云端错误 detail 中返回的现行价格（微桃），云端未提供时为 null */
    val currentPriceMicropeach: Long?,
    serverReason: String?,
    /** 预取报价短路时带回的完整新报价（quote_id/截止时间），409 路径为 null */
    val proposedQuote: cn.edu.haut.campus.core.campusservices.CloudReservationQuote? = null
) : IllegalStateException(
    buildString {
        append("云端抢座价格已调整")
        if (currentPriceMicropeach != null && currentPriceMicropeach != expectedPriceMicropeach) {
            append("：您创建时看到的价格为 ")
            append(formatMicropeachAsPeach(expectedPriceMicropeach))
            append(" 🍑，云端最新价格为 ")
            append(formatMicropeachAsPeach(currentPriceMicropeach))
            append(" 🍑")
        }
        serverReason?.takeIf { it.isNotBlank() }?.let { append("（").append(it).append("）") }
        append("。请核实最新价格后重新创建计划")
    }
)

@Deprecated(
    message = "W0-05 拆分过渡外观：新代码应使用具体领域 Repository；本类仅为兼容委托门面。"
)
class CampusRepository internal constructor(
    private val appContext: Context,
    private val database: HautDatabase,
    private val preferences: DataStore<Preferences>,
    private val accountProfile: AccountProfileEntity,
    academicCredentialVaultOverride: AcademicCredentialVault? = null
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
    private val examRepository = ExamRepository(repositoryEnvironment)
    private val gradesRepository = GradesRepository(repositoryEnvironment)
    private val termRepository = TermRepository(repositoryEnvironment, examRepository)
    private val timetableRepository = TimetableRepository(repositoryEnvironment, termRepository)
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
            this@CampusRepository.migrateLegacyAcademicCredentialsIfNeeded()

        override fun readAcademicCredential(accountId: String): AcademicCredentialReadResult =
            this@CampusRepository.academicCredentialVault.read(
                this@CampusRepository.academicCredentialScope(accountId)
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
    private val campusServicesRepository = CampusServicesRepository(
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
            this@CampusRepository.migrateLegacyAcademicCredentialsIfNeeded()

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
    }
    private val syncOrchestrator = SyncOrchestrator(
        environment = repositoryEnvironment,
        appContext = appContext,
        cloudIdentityRepository = cloudIdentityRepository,
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
        migrateLegacyAcademicCredentialsIfNeeded()
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


    init {
        registerBeforeWipeHook("campus_services_account_and_web_wipe") {
            runCatching { utilityAccountStore.wipeAll() }
            runCatching { librarySeatSessionVault.clear() }
            runCatching { xiaomiAccountVault.saveAccounts(emptyList()) }
            runCatching { SchoolWebSessionManager.clearAllSchoolWebSessions() }
        }
        repoScope.launch {
            runCatching { sanitizePersistedNullLiterals() }
        }
    }

    private suspend fun sanitizePersistedNullLiterals() {
        val parcels = cloudAndParcelDao.getParcelItemsSnapshot()
        for (p in parcels) {
            val cleanPickup = p.pickupCode.cleanNullableLiteral()
            val cleanStation = p.stationName.cleanNullableLiteral() ?: "莲花街北苑菜鸟驿站"
            val cleanCarrier = p.carrierName.cleanNullLiteral("校园快递")
            val cleanTitle = p.itemTitle.cleanNullableLiteral()
            val nextStatus = if (p.pickupCode != null && cleanPickup == null && p.status == ParcelLifecycleStatus.READY_FOR_PICKUP.wireName) {
                ParcelLifecycleStatus.IN_TRANSIT.wireName
            } else {
                p.status.cleanNullLiteral(ParcelLifecycleStatus.IN_TRANSIT.wireName)
            }
            val cleanEstimated = p.estimatedDeliveryText.cleanNullLiteral(
                when (nextStatus) {
                    ParcelLifecycleStatus.PICKED_UP.wireName -> "本人已取件签收"
                    ParcelLifecycleStatus.READY_FOR_PICKUP.wireName -> "已到达驿站/快递柜，请凭取件码取件"
                    else -> "运输中，预计 1-2 日送达校园驿站"
                }
            )
            if (p.pickupCode != cleanPickup ||
                p.stationName != cleanStation ||
                p.carrierName != cleanCarrier ||
                p.itemTitle != cleanTitle ||
                p.status != nextStatus ||
                p.estimatedDeliveryText != cleanEstimated
            ) {
                cloudAndParcelDao.upsertParcelItem(
                    p.copy(
                        carrierName = cleanCarrier,
                        status = nextStatus,
                        pickupCode = cleanPickup,
                        stationName = cleanStation,
                        itemTitle = cleanTitle,
                        estimatedDeliveryText = cleanEstimated
                    )
                )
            }
        }

        val plans = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
        for (plan in plans) {
            val cleanArea = plan.preferredAreaName.cleanNullLiteral("五层中区")
            val cleanSeat = plan.preferredSeatLabel.cleanNullLiteral("001")
            val cleanExtId = plan.externalBookingId.cleanNullableLiteral()
            val cleanEvidence = plan.evidenceSha256.cleanNullableLiteral()
            val cleanDiag = plan.lastDiagnosticMessage.cleanNullLiteral()
            if (plan.preferredAreaName != cleanArea ||
                plan.preferredSeatLabel != cleanSeat ||
                plan.externalBookingId != cleanExtId ||
                plan.evidenceSha256 != cleanEvidence ||
                plan.lastDiagnosticMessage != cleanDiag
            ) {
                cloudAndParcelDao.upsertLibraryBookingPlan(
                    plan.copy(
                        preferredAreaName = cleanArea,
                        preferredSeatLabel = cleanSeat,
                        externalBookingId = cleanExtId,
                        evidenceSha256 = cleanEvidence,
                        lastDiagnosticMessage = cleanDiag
                    )
                )
            }
        }

        // Purge any legacy sample plugins or unapproved sample moments records
        runCatching {
            cloudAndParcelDao.deleteInstalledExtensionPlugin("org.example.hello-campus")
            cloudAndParcelDao.deleteInstalledExtensionPlugin("org.example.parcel-station")
        }
        val legacyMomentsSampleIds = setOf(
            "mp_found_card_01",
            "mp_lost_airpods_02",
            "mp_found_keys_03",
            "mp_lost_cup_04",
            "mb_math_01",
            "mb_ds_02",
            "mb_circuit_03",
            "mb_food_04",
            "tag_default_01"
        )
        preferences.edit { prefs ->
            val rawMoments = prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON]
            if (!rawMoments.isNullOrBlank()) {
                val parsed = CampusCloudOidcHelper.parseMomentsSnapshotJsonString(rawMoments)
                if (parsed != null) {
                    val cleanedSnap = parsed.copy(
                        posts = parsed.posts.filterNot { it.id in legacyMomentsSampleIds },
                        bookListings = parsed.bookListings.filterNot { it.id in legacyMomentsSampleIds },
                        antiLostTags = parsed.antiLostTags.filterNot { it.id in legacyMomentsSampleIds || it.tagCode == "HAUT-TAG-7E2A9B" }
                    )
                    prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                        CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(cleanedSnap)
                }
            }
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

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        preferences.edit { it[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED] = enabled }
    }

    suspend fun saveAuthorizedSyncCredentials(
        studentId: String,
        passwordPlain: String,
        xnm: String,
        xqm: String
    ) = withContext(Dispatchers.IO) {
        requireActiveSession()
        check(supportsHautServices) { "当前学校教务登录尚未适配，可使用离线导入" }
        academicCredentialMutex.withLock {
            val previousId = preferences.data.first()[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID]
            val accountId = UUID.randomUUID().toString()
            val scope = academicCredentialScope(accountId)
            try {
                val previousStudentId = previousId?.let { prevAccId ->
                    (academicCredentialVault.read(academicCredentialScope(prevAccId)) as? AcademicCredentialReadResult.Available)
                        ?.credentials?.use { it.studentId }
                }?.trim().orEmpty()
                academicCredentialVault.save(scope, studentId, passwordPlain)
                preferences.edit { prefs ->
                    prefs[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] = accountId
                    prefs[CampusRepositoryPrefKeys.ACADEMIC_REAUTH_REQUIRED] = false
                    prefs.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID)
                    prefs.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64)
                    prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM] = xnm
                    prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM] = xqm
                    prefs[CampusRepositoryPrefKeys.LAST_AUTO_SYNC_MS] = System.currentTimeMillis()
                }
                val nextStudentId = studentId.trim()
                if (previousStudentId.isNotEmpty() && previousStudentId != nextStudentId) {
                    cloudIdentityRepository.isolateBookingPlansForStudentSwitch(previousStudentId, System.currentTimeMillis())
                }
            } catch (error: Exception) {
                runCatching { academicCredentialVault.delete(scope) }
                if (error is kotlinx.coroutines.CancellationException) throw error
                throw IllegalStateException("教务凭据无法安全保存，请重新登录；本地课表和成绩已保留")
            }
            previousId?.let { runCatching { academicCredentialVault.delete(academicCredentialScope(it)) } }
        }
    }

    private fun academicCredentialScope(accountId: String) = AcademicCredentialScope(
        schoolId = schoolId,
        accountId = accountId,
        providerId = if (supportsHautServices) "haut-zf-modern" else "offline"
    )

    /** 先保留稳定迁移引用；AES-GCM 密文回读成功后，在同一 DataStore 写入中删除旧学号和 Base64。 */
    private suspend fun migrateLegacyAcademicCredentialsIfNeeded() = withContext(Dispatchers.IO) {
        val current = preferences.data.first()
        if (current[CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64] == null &&
            current[CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID] == null
        ) {
            return@withContext
        }
        academicCredentialMutex.withLock {
            val original = preferences.data.first()
            if (original[CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64] == null &&
                original[CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID] == null
            ) {
                return@withLock
            }
            val accountId = original[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] ?: UUID.randomUUID().toString()
            preferences.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] = accountId
            }
            val migrated = migrateLegacyAcademicCredentials(
                vault = academicCredentialVault,
                scope = academicCredentialScope(accountId),
                studentId = original[CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID],
                encodedPassword = original[CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64]
            )
            preferences.edit { prefs ->
                prefs.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID)
                prefs.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64)
                prefs[CampusRepositoryPrefKeys.ACADEMIC_REAUTH_REQUIRED] = !migrated
                if (!migrated) prefs[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED] = false
            }
        }
    }

    /**
     * 应用全部实时教务同步数据（学籍名片 + 课表差异合并 + 成绩明细 + 期初/期中/期末全部考试安排）。
     */
    suspend fun applyLiveSyncData(
        syncData: HautLiveSyncResult,
        overwriteUserConflicts: Boolean = false
    ) {
        requireActiveSession()
        check(supportsHautServices) { "当前学校不接受工大教务同步数据" }
        syncData.studentProfile?.let { profile ->
            saveStudentProfile(profile)
        }
        if (syncData.gradesComplete) {
            saveImportedGrades(
                attempts = syncData.gradeAttempts,
                replaceExisting = true
            )
        }
        if (syncData.examsComplete || syncData.targetTermExamsComplete) {
            saveImportedExams(
                exams = syncData.examItems,
                replaceExisting = true,
                targetTermId = syncData.termId,
                targetTermComplete = syncData.targetTermExamsComplete,
                completedTermIds = syncData.completedExamTermIds
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
        database.withTransaction {
            val allRules = dao.getRulesSnapshot()
            val allCourses = dao.getCoursesSnapshot()
            if (overwriteUserConflicts) {
                val oldPracticalCourses = allCourses.filter {
                    it.source == CourseSource.OFFICIAL_JW.name &&
                        it.externalId?.startsWith("sjk:$targetTermId:") == true
                }
                val oldPracticalCourseIds = oldPracticalCourses.map { it.id }.toSet()
                val oldPracticalRules = allRules.filter {
                    it.termId == targetTermId && (it.id.startsWith("r_prac_") || it.courseId in oldPracticalCourseIds)
                }
                oldPracticalRules.forEach { r ->
                    dao.deleteRuleById(r.id)
                    dao.deleteOccurrenceOverridesByRuleId(r.id)
                }
                oldPracticalCourses.forEach { c ->
                    dao.deleteCourseById(c.id)
                }
            }
            val scheduledUserCourseIds = dao.getRulesSnapshot()
                .filter { it.termId == targetTermId && it.userModified }
                .map { it.courseId }
                .toSet()
            val scheduledPracticalExternalIds = dao.getCoursesSnapshot()
                .filter {
                    it.id in scheduledUserCourseIds &&
                        it.source == CourseSource.OFFICIAL_JW.name &&
                        it.userModified &&
                        it.externalId?.startsWith("sjk:$targetTermId:") == true
                }
                .mapNotNull { it.externalId }
                .toSet()
            val incomingUnscheduledEntities = syncData.unscheduledCourses.toUnscheduledEntities(targetTermId)
            val remainingUnscheduledEntities = if (overwriteUserConflicts) {
                incomingUnscheduledEntities
            } else {
                incomingUnscheduledEntities.filter {
                    "sjk:$targetTermId:${it.sourceKey}" !in scheduledPracticalExternalIds
                }
            }
            dao.clearUnscheduledCoursesForTerm(targetTermId)
            dao.upsertUnscheduledCourses(remainingUnscheduledEntities)
        }
        val seasonalTermId = AcademicTermResolver.defaultTermId(LocalDate.now(timeZone))
        val targetHasAnyAcademicContent = syncData.timetableCandidates.isNotEmpty() ||
            syncData.unscheduledCourses.isNotEmpty() ||
            syncData.examItems.any { it.termId == targetTermId } ||
            targetTermId == seasonalTermId
        val fallbackPopulatedSiblingTermId = if (!targetHasAnyAcademicContent) {
            syncData.additionalTermTimetables.entries.firstOrNull { (sibId, cands) ->
                sibId != targetTermId && cands.isNotEmpty() && !termRepository.isTermOlderThan(sibId, targetTermId)
            }?.key
        } else null
        val activateTargetTerm = fallbackPopulatedSiblingTermId == null

        ensureTermExistsAndUpdateCalendar(
            termId = targetTermId,
            semesterStart = syncData.semesterStart,
            totalWeeks = syncData.totalWeeks,
            setCurrent = activateTargetTerm
        )
        val diff = previewCourseImportDiff(
            termId = targetTermId,
            source = CourseSource.OFFICIAL_JW,
            candidates = syncData.timetableCandidates
        )
        commitCourseImportDiff(
            preview = diff,
            overwriteUserConflicts = overwriteUserConflicts,
            setAsCurrentTerm = activateTargetTerm
        )
        if (fallbackPopulatedSiblingTermId != null) {
            ensureTermExistsAndUpdateCalendar(termId = fallbackPopulatedSiblingTermId, setCurrent = true)
        }
        val (syncXnm, syncXqm) = HautAcademicEndpoints.fromTermId(targetTermId)
        preferences.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM] = syncXnm
            prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM] = syncXqm
            prefs[CampusRepositoryPrefKeys.LAST_AUTO_SYNC_MS] = System.currentTimeMillis()
        }
    }

    /**
     * 后台或一键动态刷新：使用本地已授权保存的教务凭据，从正方教务系统拉取最新课表、期初/期中/期末考试安排与成绩。
     * @param force 若为 false，则距离上次自动同步不足 15 分钟时跳过，避免频繁请求。
     */
    suspend fun performDynamicAcademicSync(force: Boolean = false): Result<HautLiveSyncResult>? = withContext(Dispatchers.IO) {
        requireActiveSession()
        if (!supportsHautServices) return@withContext Result.failure(IllegalStateException("当前学校教务登录尚未适配，可使用离线导入"))
        migrateLegacyAcademicCredentialsIfNeeded()
        ensureSeasonalTermRolloverIfNeeded()
        academicCredentialMutex.withLock {
        val prefs = preferences.data.first()
        val autoEnabled = prefs[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED] ?: true
        if (!force && !autoEnabled) return@withLock null

        val lastSyncMs = prefs[CampusRepositoryPrefKeys.LAST_AUTO_SYNC_MS] ?: 0L
        val nowMs = System.currentTimeMillis()
        if (!force && (nowMs - lastSyncMs) < 15 * 60 * 1000L) {
            return@withLock null
        }

        val accountId = prefs[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] ?: return@withLock null
        val credentialResult = academicCredentialVault.read(academicCredentialScope(accountId))
        if (credentialResult !is AcademicCredentialReadResult.Available) {
            preferences.edit {
                it[CampusRepositoryPrefKeys.ACADEMIC_REAUTH_REQUIRED] = true
                it[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED] = false
            }
            return@withLock Result.failure(IllegalStateException("安全凭据已失效，请重新登录；本地课表和成绩已保留"))
        }

        val currentTermEntity = dao.getCurrentTerm()
        val defaultPair = currentTermEntity?.id?.let { HautAcademicEndpoints.fromTermId(it) }
            ?: (AcademicTermResolver.defaultAcademicYearCode() to AcademicTermResolver.defaultSemesterCode())
        val xnm = prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM] ?: defaultPair.first
        val xqm = prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM] ?: defaultPair.second

        credentialResult.credentials.use { credentials ->
            val result = HautLiveAcademicClient.fetchLiveAcademicData(
                studentId = credentials.studentId,
                passwordPlain = credentials.passwordPlain,
                xnm = xnm,
                xqm = xqm
            )
            val failure = result.exceptionOrNull()
            if (failure is AcademicReadException && failure.code in setOf(
                    "AUTH_REJECTED", "CAPTCHA_REQUIRED", "SESSION_EXPIRED", "MANUAL_VERIFICATION"
                )) {
                preferences.edit {
                    it[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED] = false
                    it[CampusRepositoryPrefKeys.ACADEMIC_REAUTH_REQUIRED] = true
                }
            }
            result.fold(
                onSuccess = { syncData ->
                    try {
                        applyLiveSyncData(syncData, overwriteUserConflicts = false)
                        Result.success(syncData)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Result.failure(IllegalStateException("教务数据保存失败，请稍后重试；已保留尚未完成更新的数据"))
                    }
                },
                onFailure = { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Result.failure(error)
                }
            )
        }
        }
    }

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
    suspend fun wipeAllPersonalData() {
        academicCredentialMutex.withLock {
            val prefs = preferences.data.first()
            prefs[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID]?.let { accountId ->
                withContext(Dispatchers.IO) { academicCredentialVault.delete(academicCredentialScope(accountId)) }
            }
            preferences.edit {
                it.remove(CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID)
                it.remove(CampusRepositoryPrefKeys.ACADEMIC_REAUTH_REQUIRED)
                it.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID)
                it.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64)
                it[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED] = false
            }
        }
        for (hook in beforeWipeHooks.values) {
            runCatching { hook.invoke() }
        }
        runCatching {
            cloudAndParcelDao.getLibraryBookingPlansSnapshot().forEach { plan ->
                LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
            }
        }
        runCatching {
            java.io.File(appContext.filesDir, "cloud_auth_provenance").deleteRecursively()
        }
        // 全量清除同样推进世代并留存 tombstone，阻断清除前发出的在途响应写回。
        // 身份变更必须先过提交屏障（全局锁序 booking→identity）：
        // 世代推进先于 Room 事务执行（避免在事务内嵌套身份锁），
        // 整个擦除事务与同步的"复核→写入"临界区互斥——在途同步要么整体先落库、要么在复核处失败。
        bookingPlanStateMutex.withLock {
            cloudIdentityMutex.withLock {
                cloudIdentityRepository.bumpCloudIdentityGenerationLocked(
                    cloudAndParcelDao.getCloudIdentityBindingSnapshot()?.cloudUserId,
                    markUnlinkTombstone = true
                )
            }
            database.withTransaction {
                dao.clearStudentProfile()
                dao.clearCourses()
                dao.clearRules()
                dao.clearOccurrenceOverrides()
                dao.clearGradeAttempts()
                dao.clearExamSchedules()
                dao.clearExamTermRepairAudits()
                dao.clearUnscheduledCourses()
                dao.clearImportAudits()
                dao.clearWidgetConfigs()
                reminderDao.clearAllTasks()
                reminderDao.clearAllEventPrefs()
                utilityAndFocusDao.clearAllDailySamples()
                utilityAndFocusDao.clearAllOnlineRooms()
                utilityAndFocusDao.clearAllReadings()
                utilityAndFocusDao.clearAllMeters()
                utilityAndFocusDao.clearAllFocusSessions()
                cloudAndParcelDao.clearCloudIdentityBinding()
                cloudAndParcelDao.clearLibraryBookingPlans()
                cloudAndParcelDao.clearParcelItems()
            }
        }
        cloudTokenMutex.withLock {
            cloudIdentityRepository.clearCachedCloudTokensLocked()
            preferences.edit { prefs ->
                prefs.remove(CampusRepositoryPrefKeys.OFFICIAL_GPA)
                prefs.remove(CampusRepositoryPrefKeys.OFFICIAL_GPA_NOTE)
                prefs.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID)
                prefs.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64)
                prefs.remove(CampusRepositoryPrefKeys.SAVED_SYNC_XNM)
                prefs.remove(CampusRepositoryPrefKeys.SAVED_SYNC_XQM)
                prefs.remove(CampusRepositoryPrefKeys.USER_MANUAL_TERM_SEASON)
                prefs.remove(CampusRepositoryPrefKeys.LAST_SEASONAL_ROLLOVER_TERM_ID)
                prefs.remove(CampusRepositoryPrefKeys.LAST_AUTO_SYNC_MS)
                prefs.remove(CampusRepositoryPrefKeys.LAST_REMINDER_RECONCILE_MS)
                prefs.remove(CampusRepositoryPrefKeys.LAST_REMINDER_RECONCILE_SUMMARY)
                prefs.remove(CampusRepositoryPrefKeys.REMINDER_DIAGNOSTIC_LOGS)
                prefs.remove(CampusRepositoryPrefKeys.LAST_UTILITY_SAMPLE_MS)
                prefs.remove(CampusRepositoryPrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT)
                prefs.remove(CampusRepositoryPrefKeys.CLOUD_ACCESS_TOKEN)
                prefs.remove(CampusRepositoryPrefKeys.CLOUD_REFRESH_TOKEN)
                prefs.remove(CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON)
                prefs.remove(CampusRepositoryPrefKeys.MOMENTS_MY_POST_IDS)
                prefs.remove(CampusRepositoryPrefKeys.MOMENTS_MY_BOOK_IDS)
                prefs.remove(CampusRepositoryPrefKeys.MOMENTS_MY_TAG_CODES)
                prefs.remove(CampusRepositoryPrefKeys.MOMENTS_FAVORITE_POST_IDS)
                prefs.remove(CampusRepositoryPrefKeys.MOMENTS_FAVORITE_BOOK_IDS)
                prefs[CampusRepositoryPrefKeys.SEEDED] = true
                prefs[CampusRepositoryPrefKeys.CLEAN_BASE_V2] = true
                prefs[CampusRepositoryPrefKeys.ONBOARDING_COMPLETED] = false
            }
        }
    }

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

    private val activeInProcessFireAttempts = ConcurrentHashMap<String, String>()
    private val activeDispatchedFireAttempts = ConcurrentHashMap<String, Boolean>()

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
    @Volatile
    internal var cloudSubmitRetryBarrierHookForTest: (suspend () -> Unit)? = null

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
    val allLibraryBookingPlansForAuditFlow: Flow<List<LibraryBookingPlanEntity>> =
        cloudAndParcelDao.observeLibraryBookingPlans()

    /** 无法证明唯一归属的升级遗留记录（待认领/本地审计专用，普通登录账号页面与同步不可见） */
    val unattributedLegacyBookingPlansFlow: Flow<List<LibraryBookingPlanEntity>> =
        cloudAndParcelDao.observeLibraryBookingPlans().map { plans ->
            plans.filter(cloudIdentityRepository::isUnattributedLegacyBookingPlan)
        }


    val libraryBookingPlansFlow: Flow<List<LibraryBookingPlanEntity>> =
        combine(
            cloudAndParcelDao.observeLibraryBookingPlans(),
            cloudAndParcelDao.observeCloudIdentityBinding(),
            preferencesFlow
        ) { plans, binding, prefs ->
            plans.filter { plan ->
                cloudIdentityRepository.isBookingPlanVisibleToIdentity(
                    plan = plan,
                    activeCloudUserId = binding?.cloudUserId,
                    activeStudentId = prefs.savedStudentId
                )
            }
        }

    suspend fun getAllLibraryBookingPlansForAuditSnapshot(): List<LibraryBookingPlanEntity> =
        cloudAndParcelDao.getLibraryBookingPlansSnapshot()

    /** UI 确认绑定的身份；仓库入口必须消费它，不能另取新身份执行旧确认。 */
    data class BookingSubmissionIdentity(
        val profileId: String,
        val cloudUserId: String,
        val generation: Long,
        val apiBaseUrl: String,
        val studentNo: String
    )

    suspend fun snapshotBookingSubmissionIdentity(): BookingSubmissionIdentity {
        val session = snapshotCloudIdentitySession()
        val prefs = preferencesFlow.first()
        return BookingSubmissionIdentity(
            profileId, session?.cloudUserId.orEmpty(), session?.generation ?: -1L,
            session?.apiBaseUrl ?: NetworkProxyConfig.normalizeCloudApiBaseUrl(prefs.networkProxyConfig.customCloudApiBaseUrl),
            (cloudIdentityRepository.buildDelegatedCredentialBundle().first ?: prefs.savedStudentId.orEmpty()).trim()
        )
    }

    suspend fun createDualEngineBookingPlan(
        executionMode: BookingExecutionMode,
        targetDate: LocalDate,
        targetTimeText: String,
        preferredAreaName: String,
        preferredSeatLabel: String,
        allowAlternativeSeat: Boolean = true,
        repeatDays: Int = 1,
        /**
         * 用户已重新确认的成交价（微桃）：云端调价 409 之后由 UI 诚实再确认路径传入。
         * 一旦存在用户确认的报价，本次创建钉入并持久化该价，绝不再回落覆盖为
         * executionMode 常量价；仅 CLOUD_HOSTED 模式消费该值（本机/提醒恒为 0），
         * 防止上一轮云端报价跨引擎/跨身份残留进入新的本机计划。
         */
        acceptedPriceMicropeach: Long? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
        expectedIdentity: BookingSubmissionIdentity? = null
    ): Result<LibraryBookingPlanEntity> = runCatching {
        CampusCloudOidcHelper.configurePersistentAuthStorageDir(
            java.io.File(appContext.filesDir, "cloud_auth_provenance")
        )
        val normalizedRepeatDays = repeatDays.coerceIn(1, 30)
        // 本次创建实际钉入/计费的单价：存在用户确认报价时一律用确认价，
        // 否则回退为该执行模式向用户展示过的常量价（绝不静默按云端新价改签）。
        val effectiveExpectedPriceMicropeach = if (executionMode == BookingExecutionMode.CLOUD_HOSTED) {
            (acceptedPriceMicropeach ?: executionMode.priceMicropeach).coerceAtLeast(0L)
        } else {
            executionMode.priceMicropeach
        }
        val targetDateStr = targetDate.toString()
        val prefsSnap = preferencesFlow.first()
        val activeBindingSnap = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
        // 统一身份快照（profileId + cloudUserId + generation + apiBase + ssoSubject）：
        // 云端分支的余额写回与委托提交前复核均以此为准
        val identitySessionAtStart = snapshotCloudIdentitySession()
        val (studentNoBundlePre, delegatedBundlePre) = cloudIdentityRepository.buildDelegatedCredentialBundle()
        val currentStudentNo = (studentNoBundlePre ?: prefsSnap.savedStudentId.orEmpty()).trim()
        if (expectedIdentity != null) {
            requireActiveSession()
            val actualIdentity = BookingSubmissionIdentity(
                profileId, identitySessionAtStart?.cloudUserId.orEmpty(),
                identitySessionAtStart?.generation ?: -1L,
                identitySessionAtStart?.apiBaseUrl ?: NetworkProxyConfig.normalizeCloudApiBaseUrl(prefsSnap.networkProxyConfig.customCloudApiBaseUrl),
                currentStudentNo
            )
            check(actualIdentity == expectedIdentity) { "确认身份已变化，请刷新页面后重新确认创建" }
        }

        // 0) 跨引擎切换与同日同人防重协调：统一按真实学校身份（学号）与明确 owner 归属判重（不同人隔离不误伤，同一学号跨账号仍严格防重！）
        val allPlansSnapshot = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
        val existingForDate = allPlansSnapshot.filter { plan ->
            cloudIdentityRepository.isSamePersonBookingConflictCandidate(
                candidate = plan,
                targetLocalDate = targetDateStr,
                activeCloudUserId = activeBindingSnap?.cloudUserId,
                activeStudentNo = currentStudentNo
            )
        }

        val confirmedForDate = existingForDate.firstOrNull {
            it.status in setOf("CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED")
        }
        if (confirmedForDate != null) {
            throw IllegalStateException(
                "该日期（$targetDateStr）已在学校端成功锁定座位（状态：${confirmedForDate.status}），无需切换引擎或重复提交，请先核实当前预约记录"
            )
        }

        val unknownOrInFlightForDate = existingForDate.firstOrNull {
            it.status in setOf(
                "RESULT_UNKNOWN",
                "DELEGATION_STOPPED_RESULT_UNKNOWN",
                "EXECUTING",
                "DISPATCH_INTENT",
                "CANCEL_RESULT_UNKNOWN"
            ) || it.fireStatus.startsWith("DISPATCH_INTENT")
        }
        if (unknownOrInFlightForDate != null) {
            throw IllegalStateException(
                "该日期（$targetDateStr）已有已发出或结果待核对的抢座请求（状态：${unknownOrInFlightForDate.status}），在核实学校端结果前禁止切换引擎或重复预约"
            )
        }

        val cloudBaseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
            prefsSnap.networkProxyConfig.customCloudApiBaseUrl
        )
        val parsedTime = DualEngineReservationPlanner.parseTargetLocalTime(targetTimeText)
        val planId = "plan_${UUID.randomUUID().toString().replace("-", "").take(14)}"
        val schedule = DualEngineReservationPlanner.computeSchedule(
            planId = planId,
            targetDate = targetDate,
            targetTime = parsedTime,
            generation = 1,
            nowEpochMillis = nowEpochMillis
        )
        val initialStatus = when {
            schedule.finalCheckInitialStatus == "FINAL_CHECK_LATE_OK" -> "FINAL_CHECK_OK"
            schedule.precheckInitialStatus == "PRECHECK_LATE_OK" -> "PRECHECK_OK"
            else -> "ARMED"
        }

        var remoteCloudJobId: String? = null
        var planOwnerCloudUserId: String? = null

        // 若目标为本机抢座/提醒：必须按「父计划关联与续期可能性」选取全部相关云端计划（包括旧 FAILED/EXPIRED 子任务及跨日连续计划），
        // 先向云端执行权威核查与父计划停止，绝不因本地旧子任务已终态而跳过协调！
        if (executionMode != BookingExecutionMode.CLOUD_HOSTED) {
            val cloudPlansRequiringCoordination = allPlansSnapshot.filter { plan ->
                if (plan.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName) return@filter false
                val belongsToSamePerson =
                    cloudIdentityRepository.doesCloudPlanBelongToActiveAccount(plan, activeBindingSnap?.cloudUserId, currentStudentNo) ||
                        (currentStudentNo.isNotEmpty() && plan.ownerStudentId?.trim() == currentStudentNo)
                if (!belongsToSamePerson) return@filter false
                // 仅排除已在本地完成过云端父计划协调停止且 remainingDays == 0 的 CANCELLED/SCHOOL_CANCELLED 记录；
                // FAILED / EXPIRED 以及跨日连续计划（repeatDays > 1 或 remainingDays > 0）必须纳入云端父计划权威核查！
                val alreadyCoordinatedStopped =
                    (plan.status == "CANCELLED" && plan.remainingDays <= 0 &&
                        plan.lastDiagnosticMessage?.contains("已与云端协调停止父计划") == true) ||
                        plan.status == "SCHOOL_CANCELLED"
                if (alreadyCoordinatedStopped) return@filter false

                val planStartDate = runCatching { LocalDate.parse(plan.targetLocalDate) }.getOrNull()
                val spanDays = plan.repeatDays.coerceAtLeast(1).toLong()
                val coversTargetDate = plan.targetLocalDate == targetDateStr ||
                    plan.remainingDays > 0 ||
                    (planStartDate != null &&
                        !targetDate.isBefore(planStartDate) &&
                        !targetDate.isAfter(planStartDate.plusDays(spanDays - 1)))
                if (!coversTargetDate) return@filter false

                plan.status != "CANCELLED" || !plan.cloudJobId.isNullOrBlank() || plan.repeatDays > 1
            }

            for (cloudPlan in cloudPlansRequiringCoordination) {
                var cloudToken = cloudIdentityRepository.resolveValidCloudAccessToken(forceRefresh = false)
                if (cloudToken.isBlank()) {
                    cloudToken = cloudIdentityRepository.resolveValidCloudAccessToken(forceRefresh = true)
                }
                val checkRes = withContext(Dispatchers.IO) {
                    CampusCloudOidcHelper.coordinateStopCloudJobBeforeEngineSwitchDetailed(
                        accessToken = cloudToken,
                        cloudJobId = cloudPlan.cloudJobId,
                        planId = cloudPlan.id,
                        targetLocalDate = targetDateStr,
                        cloudBaseUrl = cloudBaseUrl,
                        httpTransport = cloudHttpTransportForTest
                    )
                }
                val isSameTargetDateRow = cloudPlan.targetLocalDate == targetDateStr
                when (checkRes.outcome) {
                    CampusCloudOidcHelper.EngineSwitchCloudCheckOutcome.SAFE_TO_SWITCH_STOPPED -> {
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, cloudPlan.id)
                        bookingPlanStateMutex.withLock {
                            val nextStatus = if (cloudPlan.status in setOf("FAILED", "EXPIRED", "CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED") &&
                                (!isSameTargetDateRow || cloudPlan.status in setOf("FAILED", "EXPIRED"))
                            ) {
                                if (cloudPlan.status == "CONFIRMED") "DELEGATION_STOPPED_SCHOOL_CONFIRMED" else cloudPlan.status
                            } else {
                                "CANCELLED"
                            }
                            cloudAndParcelDao.upsertLibraryBookingPlan(
                                cloudPlan.copy(
                                    status = nextStatus,
                                    fireStatus = nextStatus,
                                    generation = cloudPlan.generation + 1,
                                    stateVersion = cloudPlan.stateVersion + 1,
                                    remainingDays = 0,
                                    lastDiagnosticMessage = "已与云端协调停止父计划及排队子任务，安全切换为本机模式",
                                    updatedAtEpochMillis = nowEpochMillis
                                )
                            )
                        }
                    }
                    CampusCloudOidcHelper.EngineSwitchCloudCheckOutcome.BLOCKED_ALREADY_CONFIRMED -> {
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, cloudPlan.id)
                        if (isSameTargetDateRow) {
                            bookingPlanStateMutex.withLock {
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    cloudPlan.copy(
                                        status = checkRes.resolvedStatus,
                                        fireStatus = checkRes.resolvedStatus,
                                        externalBookingId = checkRes.externalBookingId ?: cloudPlan.externalBookingId,
                                        evidenceSha256 = checkRes.evidenceSha256 ?: cloudPlan.evidenceSha256,
                                        preferredAreaName = checkRes.bookedRegionAlias?.takeIf { it.isNotBlank() } ?: cloudPlan.preferredAreaName,
                                        preferredSeatLabel = checkRes.bookedSeatAlias?.takeIf { it.isNotBlank() } ?: cloudPlan.preferredSeatLabel,
                                        remainingDays = 0,
                                        stateVersion = cloudPlan.stateVersion + 1,
                                        lastDiagnosticMessage = checkRes.reason,
                                        updatedAtEpochMillis = nowEpochMillis
                                    )
                                )
                            }
                        }
                        throw IllegalStateException(checkRes.reason)
                    }
                    CampusCloudOidcHelper.EngineSwitchCloudCheckOutcome.BLOCKED_ALREADY_DISPATCHED_OR_UNKNOWN -> {
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, cloudPlan.id)
                        if (isSameTargetDateRow) {
                            bookingPlanStateMutex.withLock {
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    cloudPlan.copy(
                                        status = checkRes.resolvedStatus,
                                        fireStatus = checkRes.resolvedStatus,
                                        remainingDays = 0,
                                        stateVersion = cloudPlan.stateVersion + 1,
                                        lastDiagnosticMessage = checkRes.reason,
                                        updatedAtEpochMillis = nowEpochMillis
                                    )
                                )
                            }
                        }
                        throw IllegalStateException(checkRes.reason)
                    }
                    CampusCloudOidcHelper.EngineSwitchCloudCheckOutcome.BLOCKED_OFFLINE_UNCOORDINATED -> {
                        throw IllegalStateException(checkRes.reason)
                    }
                }
            }
        } else {
            val binding = activeBindingSnap
                ?: throw IllegalStateException("使用云端抢座需先登录 XPeach 云账号；您也可随时切换为「本机抢座」（无需登录云账号）")
            if (cloudHttpTransportForTest == null) {
                if (currentStudentNo.isBlank()) {
                    throw IllegalStateException("开启云端准点抢座需先登录并保存校园学号与统一认证密码")
                }
                val hasSavedPassword = !delegatedBundlePre.isNullOrBlank() && runCatching {
                    org.json.JSONObject(delegatedBundlePre).optString("password").isNotBlank()
                }.getOrDefault(false)
                if (!hasSavedPassword) {
                    throw IllegalStateException("开启云端准点抢座需保存校园统一认证密码（避免凌晨登录态过期），请重新验证并保存学号与密码")
                }
            }
            var token = cloudIdentityRepository.resolveValidCloudAccessToken(
                forceRefresh = false,
                expectedIdentity = identitySessionAtStart,
                expectedStudentNo = currentStudentNo
            )
            if (token.isBlank()) {
                token = cloudIdentityRepository.resolveValidCloudAccessToken(
                    forceRefresh = true,
                    expectedIdentity = identitySessionAtStart,
                    expectedStudentNo = currentStudentNo
                )
            }
            if (token.isBlank()) {
                throw IllegalStateException("云端会话已过期或当前离线，无法创建云端抢座计划；原有的本机排队计划已为您保留")
            }
            var effectiveBalanceMicropeach = binding.peachBalanceMicropeach
            if (effectiveBalanceMicropeach < effectiveExpectedPriceMicropeach) {
                val livePeach = withContext(Dispatchers.IO) {
                    CampusCloudOidcHelper.fetchLivePeachBalance(
                        accessToken = token,
                        cloudBaseUrl = cloudBaseUrl,
                        httpTransport = cloudHttpTransportForTest
                    )
                }
                if (livePeach != null) {
                    effectiveBalanceMicropeach = kotlin.math.round(livePeach * 10_000.0).toLong()
                    // 世代守卫写回：等待云端响应期间解绑/换号/重登的，丢弃旧身份余额结果，
                    // 绝不 upsert binding.copy 覆盖新账号或复活已解绑绑定行
                    if (identitySessionAtStart != null) {
                        cloudIdentityRepository.writeBackCloudBindingIfCurrent(
                            snapshot = identitySessionAtStart,
                            newPeachBalanceMicropeach = effectiveBalanceMicropeach
                        )
                    }
                }
            }
            if (effectiveBalanceMicropeach < effectiveExpectedPriceMicropeach) {
                throw IllegalStateException(
                    "当前 XPeach 账户余额不足（云端抢座成功需 ${formatMicropeachAsPeach(effectiveExpectedPriceMicropeach)} 🍑，未抢中不扣费），请先前往 XPeach 账户补充余额，或改用「本机抢座」"
                )
            }
            planOwnerCloudUserId = binding.cloudUserId

            data class StoppedLocalPlanLease(
                val snapshotBeforeStop: LibraryBookingPlanEntity,
                val expectedStoppedGeneration: Int,
                val expectedStoppedStateVersion: Int,
                val expectedOwnerCloudUserId: String?,
                val expectedOwnerStudentId: String?,
                val handoffDiagnosticMarker: String
            )

            // 1) 严格交接顺序：先在互斥锁与事务内原子停止并围栏隔离同日本机排队计划，记录临时停止租约标识，验证撤回成功后才允许向云端发起创建！
            val queuedLocalPlans = existingForDate.filter {
                it.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName &&
                    it.status != "REMINDER_SENT"
            }
            val handoffOpToken = "HANDOFF_TO_CLOUD:$planId:${UUID.randomUUID().toString().replace("-", "").take(8)}"
            val handoffDiagnostic = "正在切换至云端托管抢座，已先行原子停止本机排队计划 [$handoffOpToken]"
            val stoppedLocalLeases = mutableListOf<StoppedLocalPlanLease>()

            suspend fun restoreStoppedLocalPlansSafely() {
                if (stoppedLocalLeases.isEmpty()) return
                val liveBindingNow = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
                val liveStuNow = (cloudIdentityRepository.buildDelegatedCredentialBundle().first ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                if (liveBindingNow?.cloudUserId?.trim() != binding.cloudUserId.trim() ||
                    (currentStudentNo.isNotEmpty() && liveStuNow != currentStudentNo) ||
                    (currentStudentNo.isEmpty() && liveStuNow.isNotEmpty())
                ) {
                    // 等待云端响应期间已切换云账号或学号：严禁恢复旧快照或重排闹钟！
                    return
                }
                val restoredForAlarm = mutableListOf<LibraryBookingPlanEntity>()
                bookingPlanStateMutex.withLock {
                    val liveBindingLocked = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
                    val liveStuLocked = (cloudIdentityRepository.buildDelegatedCredentialBundle().first ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                    if (liveBindingLocked?.cloudUserId?.trim() != binding.cloudUserId.trim() ||
                        (currentStudentNo.isNotEmpty() && liveStuLocked != currentStudentNo) ||
                        (currentStudentNo.isEmpty() && liveStuLocked.isNotEmpty())
                    ) {
                        return@withLock
                    }
                    database.withTransaction {
                        val restoreTs = System.currentTimeMillis()
                        for (lease in stoppedLocalLeases) {
                            val currentDbRow = cloudAndParcelDao.getLibraryBookingPlanById(lease.snapshotBeforeStop.id)
                                ?: continue
                            val unchangedSinceTempStop =
                                currentDbRow.status == "CANCELLED" &&
                                    currentDbRow.generation == lease.expectedStoppedGeneration &&
                                    currentDbRow.stateVersion == lease.expectedStoppedStateVersion &&
                                    currentDbRow.ownerCloudUserId.orEmpty() == lease.expectedOwnerCloudUserId.orEmpty() &&
                                    currentDbRow.ownerStudentId.orEmpty() == lease.expectedOwnerStudentId.orEmpty() &&
                                    currentDbRow.lastDiagnosticMessage == lease.handoffDiagnosticMarker
                            if (!unchangedSinceTempStop) {
                                // 等待期间用户已取消、编辑或变更了该计划：保留用户最新决定，绝不用旧快照覆盖！
                                continue
                            }
                            val restored = lease.snapshotBeforeStop.copy(
                                generation = currentDbRow.generation + 1,
                                stateVersion = currentDbRow.stateVersion + 1,
                                updatedAtEpochMillis = restoreTs
                            )
                            cloudAndParcelDao.upsertLibraryBookingPlan(restored)
                            val reread = cloudAndParcelDao.getLibraryBookingPlanById(restored.id)
                            if (reread != null && reread.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")) {
                                restoredForAlarm += reread
                            }
                        }
                    }
                }
                for (rereadPlan in restoredForAlarm) {
                    val latestVerify = cloudAndParcelDao.getLibraryBookingPlanById(rereadPlan.id)
                    if (latestVerify != null &&
                        latestVerify.generation == rereadPlan.generation &&
                        latestVerify.stateVersion == rereadPlan.stateVersion &&
                        latestVerify.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")
                    ) {
                        LibrarySeatAlarmSubsystem.schedulePlanAlarms(appContext, latestVerify)
                    }
                }
            }

            for (localPlan in queuedLocalPlans) {
                LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, localPlan.id)
                var stopFailedReason: String? = null
                bookingPlanStateMutex.withLock {
                    database.withTransaction {
                        val cancelledRows = cloudAndParcelDao.cancelQueuedBookingPlanAtomically(
                            planId = localPlan.id,
                            diagnosticMessage = handoffDiagnostic,
                            nowEpochMillis = nowEpochMillis
                        )
                        if (cancelledRows > 0) {
                            val stoppedRow = cloudAndParcelDao.getLibraryBookingPlanById(localPlan.id)
                            if (stoppedRow != null) {
                                stoppedLocalLeases += StoppedLocalPlanLease(
                                    snapshotBeforeStop = localPlan,
                                    expectedStoppedGeneration = stoppedRow.generation,
                                    expectedStoppedStateVersion = stoppedRow.stateVersion,
                                    expectedOwnerCloudUserId = stoppedRow.ownerCloudUserId,
                                    expectedOwnerStudentId = stoppedRow.ownerStudentId,
                                    handoffDiagnosticMarker = handoffDiagnostic
                                )
                            }
                        } else {
                            val latestLocal = cloudAndParcelDao.getLibraryBookingPlanById(localPlan.id)
                            if (latestLocal == null || latestLocal.status !in setOf("CANCELLED", "REMINDER_SENT")) {
                                stopFailedReason = "本机同日抢座计划已进入执行或待核对状态（状态：${latestLocal?.status ?: "UNKNOWN"}），已拒绝创建云端任务以避免双引擎重复预约"
                            }
                        }
                    }
                }
                if (stopFailedReason != null) {
                    restoreStoppedLocalPlansSafely()
                    throw IllegalStateException(stopFailedReason!!)
                }
            }

            // 2) 本机已 100% 原子停止后，向云端提交托管计划（带幂等键 planId）
            //    提交前即时复核：身份/世代/base-url 与学号必须仍与开始快照一致，
            //    切号期间的排队提交一律停止并恢复原有本机计划
            if (identitySessionAtStart == null ||
                !isCloudIdentitySnapshotCurrent(identitySessionAtStart) ||
                (expectedIdentity != null && !isSessionActive)
            ) {
                restoreStoppedLocalPlansSafely()
                throw IllegalStateException("云端账号在任务创建期间已切换或解绑，已停止向云端提交旧会话委托")
            }
            val liveStudentBeforeSubmit = (cloudIdentityRepository.buildDelegatedCredentialBundle().first
                ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
            if (liveStudentBeforeSubmit != currentStudentNo) {
                restoreStoppedLocalPlansSafely()
                throw IllegalStateException("校园学号在任务创建期间已切换，已停止向云端提交旧学号委托")
            }
            val formattedTime = DualEngineReservationPlanner.formatLocalTimeHms(parsedTime)
            val cleanArea = preferredAreaName.trim().ifBlank { "五层中区" }
            val cleanSeat = preferredSeatLabel.trim().ifBlank { "01桌 B座" }
            var submitOutcome = withContext(Dispatchers.IO) {
                CampusCloudOidcHelper.submitCloudHostedReservationPlanDetailed(
                    accessToken = token,
                    studentNo = currentStudentNo,
                    delegatedSecret = delegatedBundlePre.orEmpty(),
                    targetDate = targetDateStr,
                    targetExecuteAtIso = formattedTime,
                    preferredAreaName = cleanArea,
                    preferredSeatLabel = cleanSeat,
                    planIdempotencyKey = planId,
                    cloudBaseUrl = cloudBaseUrl,
                    repeatDays = normalizedRepeatDays,
                    // 报价钉死：把创建时向用户展示/用户已重新确认的价格写入 expected_price_micropeach，
                    // 云端调价即 409 走诚实再确认（下同），绝不静默按新价改签；
                    // 存在用户确认报价时绝不再回落覆盖为 executionMode 常量价
                    expectedPriceMicropeach = effectiveExpectedPriceMicropeach,
                    // 报价归属校验：服务端返回他人 quote 必须保守拒绝重新取价
                    expectedCloudUserId = identitySessionAtStart?.cloudUserId,
                    httpTransport = cloudHttpTransportForTest
                )
            }
            if (submitOutcome is CampusCloudOidcHelper.CloudPlanSubmissionOutcome.RejectedPrePersist && submitOutcome.isAuthExpired) {
                // R5-C 令牌刷新重提前的身份/学号复验（复验①·发起前）：
                // 首交后被 401 拒绝只能证明 pre-persist，切号/换址/换学号期间的旧会话提交
                // 绝不得携新令牌重试——refresh 发起、返回、凭据写回三点再验（resolver 内），
                // 此处先做业务侧复核，身份不符即恢复本机计划并中止
                val liveStudentBeforeRetry = (cloudIdentityRepository.buildDelegatedCredentialBundle().first
                    ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                if (!isCloudIdentitySnapshotCurrent(identitySessionAtStart) ||
                    liveStudentBeforeRetry != currentStudentNo
                ) {
                    restoreStoppedLocalPlansSafely()
                    throw IllegalStateException("云端账号或学号在任务提交期间已切换，已停止旧会话的令牌刷新与重提交")
                }
                // 测试闩锁（生产为 null）：确定性覆盖"401→refresh"窗口的切号/换址交错
                cloudSubmitRetryBarrierHookForTest?.invoke()
                token = cloudIdentityRepository.resolveValidCloudAccessToken(
                    forceRefresh = true,
                    failedAccessToken = token,
                    expectedIdentity = identitySessionAtStart,
                    expectedStudentNo = currentStudentNo
                )
                if (token.isBlank() && !isCloudIdentitySnapshotCurrent(identitySessionAtStart)) {
                    // resolver 以身份失效为由拒绝：旧提交绝不得携新 token 重试
                    restoreStoppedLocalPlansSafely()
                    throw IllegalStateException("云端账号、学号或网络地址在任务提交期间已切换，已停止旧会话重提交")
                }
                if (token.isNotBlank()) {
                    // 复验③·重提前：拿回的令牌必须仍属于开始时的身份会话与学号——
                    // 切号后旧提交不得携新 token 重试，网络地址也继续沿用已验证 base
                    val liveStudentAfterRetry = (cloudIdentityRepository.buildDelegatedCredentialBundle().first
                        ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                    if (!isCloudIdentitySnapshotCurrent(identitySessionAtStart) ||
                        liveStudentAfterRetry != currentStudentNo
                    ) {
                        restoreStoppedLocalPlansSafely()
                        throw IllegalStateException("令牌刷新期间云端账号或学号已切换，已停止携新令牌重提旧提交")
                    }
                    submitOutcome = withContext(Dispatchers.IO) {
                        CampusCloudOidcHelper.submitCloudHostedReservationPlanDetailed(
                            accessToken = token,
                            studentNo = currentStudentNo,
                            delegatedSecret = delegatedBundlePre.orEmpty(),
                            targetDate = targetDateStr,
                            targetExecuteAtIso = formattedTime,
                            preferredAreaName = cleanArea,
                            preferredSeatLabel = cleanSeat,
                            planIdempotencyKey = planId,
                            cloudBaseUrl = cloudBaseUrl,
                            repeatDays = normalizedRepeatDays,
                            // 令牌刷新重提交仍钉入同一成交价（用户确认价优先于常量价），绝不漂移
                            expectedPriceMicropeach = effectiveExpectedPriceMicropeach,
                            expectedCloudUserId = identitySessionAtStart.cloudUserId,
                            httpTransport = cloudHttpTransportForTest
                        )
                    }
                }
            }

            when (val finalOutcome = submitOutcome) {
                is CampusCloudOidcHelper.CloudPlanSubmissionOutcome.Accepted -> {
                    remoteCloudJobId = finalOutcome.remoteJobOrPlanId
                }
                is CampusCloudOidcHelper.CloudPlanSubmissionOutcome.RejectedPrePersist -> {
                    // 云端在持久化计划前明确拒绝（4xx 或前置授权失败），云端无半途任务，安全补偿恢复原有本机排队计划
                    restoreStoppedLocalPlansSafely()
                    throw IllegalStateException("云端未能受理该抢座计划（${finalOutcome.reason}）；已为您恢复原有本机排队计划")
                }
                is CampusCloudOidcHelper.CloudPlanSubmissionOutcome.QuoteUnusable -> {
                    // 报价端点存在但返回坏数据/未知错误：提示重新获取报价，绝不静默降级
                    // 到仅 expected_price 的 legacy 钉价路径；此时计划与绑定写均未发生
                    restoreStoppedLocalPlansSafely()
                    throw IllegalStateException("云端报价获取异常（${finalOutcome.reason}），请重新获取报价后再创建；已为您恢复原有本机排队计划")
                }
                is CampusCloudOidcHelper.CloudPlanSubmissionOutcome.PriceDriftConflict -> {
                    // 报价漂移 409：云端价格已变更——恢复本机计划后抛出专用异常，
                    // UI 必须向用户如实展示新旧价格并要求"重新确认创建"，绝不自动按新价重试
                    restoreStoppedLocalPlansSafely()
                    throw CloudPlanPriceDriftException(
                        expectedPriceMicropeach = finalOutcome.expectedPriceMicropeach
                            ?: effectiveExpectedPriceMicropeach,
                        currentPriceMicropeach = finalOutcome.currentPriceMicropeach,
                        serverReason = finalOutcome.reason,
                        proposedQuote = finalOutcome.quote
                    )
                }
                is CampusCloudOidcHelper.CloudPlanSubmissionOutcome.ResponseAmbiguous -> {
                    // 云端 POST /reservation-plans 已发出但响应丢失/超时：可能已在云端落库！
                    // 先尝试向云端查询并撤回该半途计划；仅当云端权威确认已撤回且无在途任务时才恢复本机，否则严格失败闭合为 RESULT_UNKNOWN（保持本机停止，绝不双端并跑！）
                    val reconcile = withContext(Dispatchers.IO) {
                        CampusCloudOidcHelper.coordinateStopCloudJobBeforeEngineSwitchDetailed(
                            accessToken = token,
                            cloudJobId = null,
                            planId = planId,
                            targetLocalDate = targetDateStr,
                            cloudBaseUrl = cloudBaseUrl,
                            httpTransport = cloudHttpTransportForTest
                        )
                    }
                    if (reconcile.outcome == CampusCloudOidcHelper.EngineSwitchCloudCheckOutcome.SAFE_TO_SWITCH_STOPPED) {
                        restoreStoppedLocalPlansSafely()
                        throw IllegalStateException("云端创建响应超时，已向云端核实并安全撤回未执行请求，已为您恢复原有本机计划")
                    }
                    val closedStatus = when (reconcile.outcome) {
                        CampusCloudOidcHelper.EngineSwitchCloudCheckOutcome.BLOCKED_ALREADY_CONFIRMED -> reconcile.resolvedStatus
                        CampusCloudOidcHelper.EngineSwitchCloudCheckOutcome.BLOCKED_ALREADY_DISPATCHED_OR_UNKNOWN -> reconcile.resolvedStatus
                        else -> "RESULT_UNKNOWN"
                    }
                    val ambiguousEntity = LibraryBookingPlanEntity(
                        id = planId,
                        schoolId = schoolId,
                        executionMode = BookingExecutionMode.CLOUD_HOSTED.wireName,
                        targetLocalDate = targetDateStr,
                        targetLocalTime = formattedTime,
                        timezoneId = DualEngineReservationPlanner.HAUT_ZONE.id,
                        precheckAtEpochMillis = schedule.precheckAtEpochMillis,
                        finalCheckAtEpochMillis = schedule.finalCheckAtEpochMillis,
                        fireAtEpochMillis = schedule.fireAtEpochMillis,
                        precheckStatus = schedule.precheckInitialStatus,
                        finalCheckStatus = schedule.finalCheckInitialStatus,
                        fireStatus = closedStatus,
                        preferredAreaName = reconcile.bookedRegionAlias?.takeIf { it.isNotBlank() } ?: cleanArea,
                        preferredSeatLabel = reconcile.bookedSeatAlias?.takeIf { it.isNotBlank() } ?: cleanSeat,
                        allowAlternativeSeat = allowAlternativeSeat,
                        cloudJobId = null,
                        externalBookingId = reconcile.externalBookingId,
                        evidenceSha256 = reconcile.evidenceSha256,
                        // 响应中断待核对单也必须记录本次钉入价，后续核对按原成交价对账
                        priceMicropeach = effectiveExpectedPriceMicropeach,
                        paymentState = "HELD_PENDING_REVIEW",
                        status = closedStatus,
                        stateVersion = 1,
                        generation = 1,
                        lastDiagnosticMessage = "云端创建响应中断（${finalOutcome.reason}），无法确认云端未受理；已保持本机计划停止并记录待核对状态以防双引擎重复预约",
                        createdAtEpochMillis = nowEpochMillis,
                        updatedAtEpochMillis = nowEpochMillis,
                        repeatDays = normalizedRepeatDays,
                        remainingDays = 0,
                        ownerCloudUserId = planOwnerCloudUserId,
                        ownerStudentId = currentStudentNo.ifBlank { null }
                    )
                    bookingPlanStateMutex.withLock {
                        cloudAndParcelDao.upsertLibraryBookingPlan(ambiguousEntity)
                    }
                    throw IllegalStateException(ambiguousEntity.lastDiagnosticMessage!!)
                }
            }
        }

        val repeatHint = if (normalizedRepeatDays > 1) {
            "（已开启连续 $normalizedRepeatDays 天自动抢座，每日完成后自动续期次日任务）"
        } else {
            ""
        }

        val entity = LibraryBookingPlanEntity(
            id = planId,
            schoolId = schoolId,
            executionMode = executionMode.wireName,
            targetLocalDate = targetDateStr,
            targetLocalTime = DualEngineReservationPlanner.formatLocalTimeHms(parsedTime),
            timezoneId = DualEngineReservationPlanner.HAUT_ZONE.id,
            precheckAtEpochMillis = schedule.precheckAtEpochMillis,
            finalCheckAtEpochMillis = schedule.finalCheckAtEpochMillis,
            fireAtEpochMillis = schedule.fireAtEpochMillis,
            precheckStatus = schedule.precheckInitialStatus,
            finalCheckStatus = schedule.finalCheckInitialStatus,
            fireStatus = schedule.fireInitialStatus,
            preferredAreaName = preferredAreaName.trim().ifBlank { "五层中区" },
            preferredSeatLabel = preferredSeatLabel.trim().ifBlank { "01桌 B座" },
            allowAlternativeSeat = allowAlternativeSeat,
            cloudJobId = remoteCloudJobId,
            externalBookingId = null,
            evidenceSha256 = null,
            // 持久化本次钉入/用户确认的成交价：后续核对、续期与再分派一律以该原成交价为准，
            // 绝不因云端后续调价而改写历史计划行
            priceMicropeach = effectiveExpectedPriceMicropeach,
            paymentState = if (effectiveExpectedPriceMicropeach == 0L) "NOT_REQUIRED" else "DEBIT_PENDING",
            status = initialStatus,
            stateVersion = 1,
            generation = 1,
            lastDiagnosticMessage = when (executionMode) {
                BookingExecutionMode.CLOUD_HOSTED ->
                    "已交由云端托管（任务号 ${remoteCloudJobId ?: planId.takeLast(8)}）：将在 ${targetDate} ${DualEngineReservationPlanner.formatLocalTimeHms(parsedTime)} 自动登录并准点抢座$repeatHint"
                BookingExecutionMode.MANUAL_REMINDER ->
                    "已开启到点提醒：将在 ${targetDate} ${DualEngineReservationPlanner.formatLocalTimeHms(parsedTime)} 弹出选座通知$repeatHint"
                BookingExecutionMode.LOCAL_DEVICE ->
                    "已开启本机准点抢座：将在 ${targetDate} ${DualEngineReservationPlanner.formatLocalTimeHms(parsedTime)} 唤醒本机执行自动抢座$repeatHint"
            },
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
            repeatDays = normalizedRepeatDays,
            remainingDays = normalizedRepeatDays,
            ownerCloudUserId = planOwnerCloudUserId,
            ownerStudentId = currentStudentNo.ifBlank { null }
        )
        bookingPlanStateMutex.withLock {
            // 云端已受理时必须保留原归属回执；本机尚未创建则在实际落库前复核旧确认。
            if (expectedIdentity != null && executionMode != BookingExecutionMode.CLOUD_HOSTED) {
                requireActiveSession()
                check(snapshotBookingSubmissionIdentity() == expectedIdentity) { "确认身份已变化，请刷新页面后重新确认创建" }
            }
            cloudAndParcelDao.upsertLibraryBookingPlan(entity)
        }
        if (executionMode == BookingExecutionMode.CLOUD_HOSTED) {
            LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, entity.id)
            repoScope.launch { runCatching { syncWithCampusCloud() } }
        } else {
            LibrarySeatAlarmSubsystem.schedulePlanAlarms(appContext, entity)
            if (executionMode == BookingExecutionMode.LOCAL_DEVICE) {
                repoScope.launch {
                    runCatching {
                        val token = readCachedLibrarySeatSession()?.rawToken
                            ?: (ensureLibrarySeatSession(forceRefresh = false) as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
                        if (!token.isNullOrBlank()) {
                            HautLibrarySeatAuthClient.prewarmAutomatedSeatTargets(
                                rawToken = token,
                                targetDate = entity.targetLocalDate,
                                preferredAreaName = entity.preferredAreaName,
                                preferredSeatLabel = entity.preferredSeatLabel,
                                planId = entity.id,
                                planVersion = entity.generation
                            )
                        }
                    }
                }
            }
        }
        entity
    }

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
        advanceBookingPlanStageInternal(
            planId = planId,
            stage = stage,
            allowCloudExecuteNow = false,
            nowEpochMillis = nowEpochMillis
        )

    /**
     * 用户显式"现在执行"入口（与定时 stage API 分离）：仅接受 CLOUD_HOSTED 计划，
     * 由服务端窗口裁决是否可提前执行；客户端绝不替代云端定时引擎。
     */
    suspend fun executeCloudBookingPlanNow(
        planId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Result<LibraryBookingPlanEntity> {
        val plan = cloudAndParcelDao.getLibraryBookingPlanById(planId)
            ?: return Result.failure(IllegalArgumentException("未找到预约计划 $planId"))
        if (plan.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName) {
            return Result.failure(IllegalStateException("立即执行入口仅适用于云端托管抢座计划"))
        }
        return advanceBookingPlanStageInternal(
            planId = planId,
            stage = TripleScheduleStage.FIRE,
            allowCloudExecuteNow = true,
            nowEpochMillis = nowEpochMillis
        )
    }

    private suspend fun advanceBookingPlanStageInternal(
        planId: String,
        stage: TripleScheduleStage,
        allowCloudExecuteNow: Boolean,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Result<LibraryBookingPlanEntity> = runCatching {
        val current = cloudAndParcelDao.getLibraryBookingPlanById(planId)
            ?: throw IllegalArgumentException("未找到预约计划 $planId")
        if (current.status in setOf(
                "CONFIRMED",
                "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                "SCHOOL_CANCELLED",
                "CANCEL_RESULT_UNKNOWN",
                "FAILED",
                "CANCELLED",
                "EXPIRED",
                "REMINDER_SENT",
                "RESULT_UNKNOWN",
                "DELEGATION_STOPPED_RESULT_UNKNOWN",
                "NEEDS_RECONFIRMATION"
            )
        ) {
            return@runCatching current
        }
        if (stage != TripleScheduleStage.FIRE &&
            current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName
        ) {
            runCatching { syncWithCampusCloud() }
            return@runCatching cloudAndParcelDao.getLibraryBookingPlanById(planId) ?: current
        }
        // 定时入口（含旧版本排定的 pending intent、开机/时区/升级/账号切换恢复）命中云端托管
        // 计划的 FIRE：校验 owner 与当前身份归属后仅做 GET 同步合并服务端权威状态——
        // 严禁 execute-now、严禁本地续期、严禁改写服务端权威执行状态。
        if (stage == TripleScheduleStage.FIRE &&
            current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
            !allowCloudExecuteNow
        ) {
            val activeCloudId = cloudAndParcelDao.getCloudIdentityBindingSnapshot()?.cloudUserId?.trim()
            val liveStuForFire = (cloudIdentityRepository.buildDelegatedCredentialBundle().first
                ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
            val belongsToActiveIdentity = cloudIdentityRepository.isBookingPlanVisibleToIdentity(
                plan = current,
                activeCloudUserId = activeCloudId,
                activeStudentId = liveStuForFire
            )
            if (belongsToActiveIdentity &&
                current.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK", "EXECUTING")
            ) {
                runCatching { syncWithCampusCloud() }
            }
            return@runCatching cloudAndParcelDao.getLibraryBookingPlanById(planId) ?: current
        }
        if (stage != TripleScheduleStage.FIRE &&
            (current.status == "EXECUTING" || current.fireStatus.startsWith("DISPATCH_INTENT"))
        ) {
            return@runCatching current
        }
        val activeFireAttemptIdHolder = arrayOfNulls<String>(1)
        try {
        val updated = when (stage) {
            TripleScheduleStage.PRECHECK -> {
                val sessionState = ensureLibrarySeatSession(forceRefresh = false)
                if (sessionState is LibrarySeatSessionState.Authenticated) {
                    runCatching {
                        HautLibrarySeatAuthClient.prewarmAutomatedSeatTargets(
                            rawToken = sessionState.snapshot.rawToken,
                            targetDate = current.targetLocalDate,
                            preferredAreaName = current.preferredAreaName,
                            preferredSeatLabel = current.preferredSeatLabel,
                            planId = current.id,
                            planVersion = current.generation
                        )
                    }
                }
                val diag = when (sessionState) {
                    is LibrarySeatSessionState.Authenticated ->
                        "已提前为您检查登录状态并预热候选座位（${sessionState.snapshot.userInfo.userName.ifBlank { sessionState.snapshot.userInfo.userNo }} 已在线）"
                    else ->
                        "已检查抢座时间配置（建议先保存学校账号密码以便自动登录）"
                }
                current.copy(
                    precheckStatus = "PRECHECK_OK",
                    status = "PRECHECK_OK",
                    stateVersion = current.stateVersion + 1,
                    lastDiagnosticMessage = diag,
                    updatedAtEpochMillis = nowEpochMillis
                )
            }
            TripleScheduleStage.FINAL_CHECK -> {
                val sessionState = ensureLibrarySeatSession(forceRefresh = true)
                if (sessionState is LibrarySeatSessionState.Authenticated) {
                    runCatching {
                        HautLibrarySeatAuthClient.prewarmAutomatedSeatTargets(
                            rawToken = sessionState.snapshot.rawToken,
                            targetDate = current.targetLocalDate,
                            preferredAreaName = current.preferredAreaName,
                            preferredSeatLabel = current.preferredSeatLabel,
                            planId = current.id,
                            planVersion = current.generation
                        )
                    }
                }
                val diag = when (sessionState) {
                    is LibrarySeatSessionState.Authenticated ->
                        "已为您刷新最新登录状态并锁定候选座位号，等待 ${current.targetLocalTime} 准点开抢"
                    else ->
                        "倒计时已就绪，等待 ${current.targetLocalTime} 准点开抢"
                }
                current.copy(
                    precheckStatus = "PRECHECK_OK",
                    finalCheckStatus = "FINAL_CHECK_OK",
                    status = "FINAL_CHECK_OK",
                    stateVersion = current.stateVersion + 1,
                    lastDiagnosticMessage = diag,
                    updatedAtEpochMillis = nowEpochMillis
                )
            }
            TripleScheduleStage.FIRE -> {
                if (current.executionMode == BookingExecutionMode.MANUAL_REMINDER.wireName) {
                    current.copy(
                        precheckStatus = "PRECHECK_OK",
                        finalCheckStatus = "FINAL_CHECK_OK",
                        fireStatus = "REMINDER_SENT",
                        status = "REMINDER_SENT",
                        stateVersion = current.stateVersion + 1,
                        lastDiagnosticMessage = "已到点发送选座提醒：请点击上方选座面板一键预约 ${current.preferredAreaName} · ${current.preferredSeatLabel}",
                        updatedAtEpochMillis = nowEpochMillis
                    )
                } else {
                    // 原子唯一领取：页面手动触发与系统闹钟并发进入时，仅首个成功抢占唯一 attemptId 的调用者获得派发权；
                    // 同时注册本进程活跃 attemptId，防止 ON_RESUME 崩溃恢复误中断正在执行的 07:00 实时抢座！
                    val attemptId = UUID.randomUUID().toString().replace("-", "")
                    val claimedExclusively = bookingPlanStateMutex.withLock {
                        database.withTransaction {
                            val rows = cloudAndParcelDao.claimBookingPlanForDispatch(
                                planId = current.id,
                                expectedExecutionMode = current.executionMode,
                                expectedGeneration = current.generation,
                                attemptId = attemptId,
                                nowEpochMillis = nowEpochMillis
                            )
                            if (rows > 0) {
                                activeInProcessFireAttempts[current.id] = attemptId
                                activeFireAttemptIdHolder[0] = attemptId
                            }
                            rows
                        }
                    }
                    if (claimedExclusively <= 0) {
                        return@runCatching cloudAndParcelDao.getLibraryBookingPlanById(planId) ?: current
                    }

                    val hadExternalWriteAttempt = java.util.concurrent.atomic.AtomicBoolean(false)
                    val liveBooking: AutomatedSeatBookingResult? =
                        if (current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
                            // 云端托管计划严格校验归属当前云账号且只走云端执行与核验，严禁在云端返回失败、缺数据或未知状态时退回本机发起预约
                            val activeCloudId = cloudAndParcelDao.getCloudIdentityBindingSnapshot()?.cloudUserId?.trim().orEmpty()
                            val planOwnerCloud = current.ownerCloudUserId?.trim().orEmpty()
                            val validCloudJobId = current.cloudJobId?.takeIf { it.isNotBlank() && !it.startsWith("cjob_") }
                            val cloudOwnerMatched = planOwnerCloud.isNotEmpty() && activeCloudId.isNotEmpty() && planOwnerCloud == activeCloudId
                            val cloudToken = if (validCloudJobId != null && cloudOwnerMatched) cloudIdentityRepository.resolveValidCloudAccessToken() else ""
                            if (!cloudOwnerMatched) {
                                AutomatedSeatBookingResult(
                                    confirmed = false,
                                    bookingId = "",
                                    seatTitle = "",
                                    evidenceSha256 = "",
                                    diagnosticMessage = "当前登录的云端账号与预约计划归属不一致或归属未知，已在发起请求前终止执行",
                                    outcomeState = "FAILED",
                                    externalExecutionState = "NOT_DISPATCHED"
                                )
                            } else if (validCloudJobId != null && cloudToken.isNotBlank()) {
                                hadExternalWriteAttempt.set(true)
                                activeDispatchedFireAttempts[current.id] = true
                                withContext(Dispatchers.IO) {
                                    CampusCloudOidcHelper.executeCloudHostedReservationJobNow(
                                        accessToken = cloudToken,
                                        jobId = validCloudJobId,
                                        httpTransport = cloudHttpTransportForTest
                                    )
                                }
                            } else {
                                AutomatedSeatBookingResult(
                                    confirmed = false,
                                    bookingId = "",
                                    seatTitle = "",
                                    evidenceSha256 = "",
                                    diagnosticMessage = "云端任务状态未知或凭证未就绪，已停止执行以避免本机重复写入",
                                    outcomeState = "RESULT_UNKNOWN",
                                    externalExecutionState = "RESULT_UNKNOWN"
                                )
                            }
                        } else {
                            // 本机抢座在发起实际外部写入前：1) 核验计划归属学号与当前活跃学号一致；2) 按实际学校身份与 owner 核对同日冲突（不同人隔离，同一学号跨云账号仍严格防重！）
                            val activeCloudIdForFire = cloudAndParcelDao.getCloudIdentityBindingSnapshot()?.cloudUserId?.trim()
                            val liveStuForFire = (cloudIdentityRepository.buildDelegatedCredentialBundle().first ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                            val planOwnerStu = current.ownerStudentId?.trim().orEmpty()
                            val effectiveStudentForConflict = planOwnerStu.ifEmpty { liveStuForFire }

                            if (planOwnerStu.isNotEmpty() && liveStuForFire.isNotEmpty() && planOwnerStu != liveStuForFire) {
                                AutomatedSeatBookingResult(
                                    confirmed = false,
                                    bookingId = "",
                                    seatTitle = "",
                                    evidenceSha256 = "",
                                    diagnosticMessage = "当前登录的图书馆学号与本机预约计划归属学号不一致，已在发起学校请求前终止执行",
                                    outcomeState = "FAILED",
                                    externalExecutionState = "NOT_DISPATCHED"
                                )
                            } else {
                                val conflictSameDay = cloudAndParcelDao.getLibraryBookingPlansSnapshot().firstOrNull { cand ->
                                    cloudIdentityRepository.isSamePersonBookingConflictCandidate(
                                        candidate = cand,
                                        targetLocalDate = current.targetLocalDate,
                                        activeCloudUserId = current.ownerCloudUserId?.takeIf { it.isNotBlank() } ?: activeCloudIdForFire,
                                        activeStudentNo = effectiveStudentForConflict,
                                        excludePlanId = current.id
                                    )
                                }
                                if (conflictSameDay != null) {
                                    AutomatedSeatBookingResult(
                                        confirmed = false,
                                        bookingId = "",
                                        seatTitle = "",
                                        evidenceSha256 = "",
                                        diagnosticMessage = "检测到同一学号/账号在同日存在另一笔有效或待核对的预约任务（${conflictSameDay.executionMode} · ${conflictSameDay.status}），已停止本机发起重复写入",
                                        outcomeState = "RESULT_UNKNOWN",
                                        externalExecutionState = "RESULT_UNKNOWN"
                                    )
                                } else {
                                val checkStillActive: () -> Boolean = {
                                    kotlinx.coroutines.runBlocking {
                                        cloudAndParcelDao.isBookingPlanOwnedByAttempt(
                                            planId = current.id,
                                            expectedExecutionMode = current.executionMode,
                                            expectedGeneration = current.generation,
                                            attemptId = attemptId
                                        ) > 0
                                    }
                                }
                                val beforeWriteGuard: () -> Unit = {
                                    if (!checkStillActive()) {
                                        throw java.util.concurrent.CancellationException("计划已取消、切换引擎或非当前有效执行轮次，已在学校写入前终止")
                                    }
                                }
                                val onBeforeWriteIntent: () -> Unit = {
                                    kotlinx.coroutines.runBlocking {
                                        bookingPlanStateMutex.withLock {
                                            database.withTransaction {
                                                val owned = cloudAndParcelDao.retryBookingPlanDispatchForAttempt(
                                                    planId = current.id,
                                                    expectedExecutionMode = current.executionMode,
                                                    expectedGeneration = current.generation,
                                                    attemptId = attemptId,
                                                    nowEpochMillis = System.currentTimeMillis()
                                                )
                                                if (owned <= 0) {
                                                    throw java.util.concurrent.CancellationException(
                                                        "未能校验唯一派发权：计划已取消、已切换引擎或不属于当前 attempt，已在发起学校写入前终止"
                                                    )
                                                }
                                                hadExternalWriteAttempt.set(true)
                                                activeDispatchedFireAttempts[current.id] = true
                                            }
                                        }
                                    }
                                }
                                // 若 T-5m (FINAL_CHECK) 已在最近 15 分钟内刷新过 Token，直接使用缓存 Token 零延迟开抢，省去 07:00:00 的在线校验往返
                                val recentCachedSnap = readCachedLibrarySeatSession()?.takeIf {
                                    it.rawToken.isNotBlank() && (nowEpochMillis - it.updatedAtEpochMs) in 0L..(15 * 60 * 1000L)
                                }
                                val token0 = recentCachedSnap?.rawToken ?: run {
                                    val sessionState = ensureLibrarySeatSession(forceRefresh = false)
                                    (sessionState as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
                                        ?: readCachedLibrarySeatSession()?.rawToken.orEmpty()
                                }
                                val firstAttemptResult = if (token0.isNotBlank()) {
                                    HautLibrarySeatAuthClient.executeAutomatedSeatBooking(
                                        rawToken = token0,
                                        targetDate = current.targetLocalDate,
                                        preferredAreaName = current.preferredAreaName,
                                        preferredSeatLabel = current.preferredSeatLabel,
                                        planId = current.id,
                                        planVersion = current.generation,
                                        beforeEachWriteCheck = beforeWriteGuard,
                                        onBeforeExternalWrite = onBeforeWriteIntent,
                                        isStillActiveForPlan = checkStillActive
                                    )
                                } else {
                                    null
                                }
                                val firstAttempt = firstAttemptResult?.getOrNull()
                                // 仅当未持有 Token 或首轮因 Token 401 过期抛出 LibrarySeatSessionExpiredException 时才在同一个 attemptId 下重登重试；
                                // 若首轮已正常执行（含因未知写结果熔断停止），严禁二次调用 executeAutomatedSeatBooking 造成重复写入
                                if (firstAttempt != null) {
                                    firstAttempt
                                } else if (firstAttemptResult?.exceptionOrNull() is java.util.concurrent.CancellationException) {
                                    if (hadExternalWriteAttempt.get()) {
                                        AutomatedSeatBookingResult(
                                            confirmed = false,
                                            bookingId = "",
                                            seatTitle = "",
                                            evidenceSha256 = "",
                                            diagnosticMessage = "委托已停止，但此前在同一执行轮次中已发出学校预约请求，结果待核对",
                                            outcomeState = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                            userIntentState = "STOP_REQUESTED",
                                            externalExecutionState = "RESULT_UNKNOWN",
                                            billingState = "NOT_BILLED"
                                        )
                                    } else {
                                        AutomatedSeatBookingResult(
                                            confirmed = false,
                                            bookingId = "",
                                            seatTitle = "",
                                            evidenceSha256 = "",
                                            diagnosticMessage = "已在发起学校预约写入前安全停止本机任务",
                                            outcomeState = "DELEGATION_STOPPED",
                                            userIntentState = "STOP_REQUESTED",
                                            externalExecutionState = "NOT_DISPATCHED",
                                            billingState = "NOT_BILLED"
                                        )
                                    }
                                } else if (token0.isBlank() || firstAttemptResult?.exceptionOrNull() is LibrarySeatSessionExpiredException) {
                                    val refreshedState = ensureLibrarySeatSession(forceRefresh = true)
                                    val token1 = (refreshedState as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken.orEmpty()
                                    if (token1.isNotBlank()) {
                                        val retryRes = HautLibrarySeatAuthClient.executeAutomatedSeatBooking(
                                            rawToken = token1,
                                            targetDate = current.targetLocalDate,
                                            preferredAreaName = current.preferredAreaName,
                                            preferredSeatLabel = current.preferredSeatLabel,
                                            planId = current.id,
                                            planVersion = current.generation,
                                            beforeEachWriteCheck = beforeWriteGuard,
                                            onBeforeExternalWrite = onBeforeWriteIntent,
                                            isStillActiveForPlan = checkStillActive
                                        )
                                        if (retryRes.exceptionOrNull() is java.util.concurrent.CancellationException) {
                                            if (hadExternalWriteAttempt.get()) {
                                                AutomatedSeatBookingResult(
                                                    confirmed = false,
                                                    bookingId = "",
                                                    seatTitle = "",
                                                    evidenceSha256 = "",
                                                    diagnosticMessage = "委托已停止，但此前已发出学校预约请求，结果待核对",
                                                    outcomeState = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                                    userIntentState = "STOP_REQUESTED",
                                                    externalExecutionState = "RESULT_UNKNOWN",
                                                    billingState = "NOT_BILLED"
                                                )
                                            } else {
                                                AutomatedSeatBookingResult(
                                                    confirmed = false,
                                                    bookingId = "",
                                                    seatTitle = "",
                                                    evidenceSha256 = "",
                                                    diagnosticMessage = "已在发起学校预约写入前安全停止本机任务",
                                                    outcomeState = "DELEGATION_STOPPED",
                                                    userIntentState = "STOP_REQUESTED",
                                                    externalExecutionState = "NOT_DISPATCHED",
                                                    billingState = "NOT_BILLED"
                                                )
                                            }
                                        } else {
                                            retryRes.getOrNull()
                                        }
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                                }
                            }
                        }

                    // 统一归一化状态：严禁伪造预约编号或证据摘要！缺少完整预约凭据的确认结果一律降级为 RESULT_UNKNOWN / DELEGATION_STOPPED_RESULT_UNKNOWN
                    val isSchoolConfirmed = liveBooking?.isSchoolConfirmed == true
                    val isDelegationStopped = liveBooking?.isDelegationStopped == true
                    val isActiveConfirmed = isSchoolConfirmed && !isDelegationStopped
                    val verifiedExtId = liveBooking?.bookingId?.takeIf { isSchoolConfirmed && it.isNotBlank() }
                    val verifiedEvidenceHash = liveBooking?.evidenceSha256?.takeIf { isSchoolConfirmed && it.isNotBlank() }
                    val diagMsg = liveBooking?.diagnosticMessage ?: when {
                        isSchoolConfirmed && isDelegationStopped ->
                            "委托已停止，但学校预约已成功（座位：${current.preferredAreaName} ${current.preferredSeatLabel}，预约号：$verifiedExtId）。系统未自动扣费且已停止后续计划，请核对或按需退座"
                        isActiveConfirmed ->
                            "已为您抢到 ${current.preferredAreaName} ${current.preferredSeatLabel}，请按规定时间到馆刷脸签到"
                        hadExternalWriteAttempt.get() ->
                            "选座请求结果待核对，已停止重试以避免重复预约"
                        else ->
                            "未能锁定 ${current.preferredAreaName} ${current.preferredSeatLabel}：请先绑定/登录河工大统一身份认证账号"
                    }

                    val finalStatus = when {
                        isSchoolConfirmed && isDelegationStopped -> "DELEGATION_STOPPED_SCHOOL_CONFIRMED"
                        isActiveConfirmed -> "CONFIRMED"
                        liveBooking != null && (liveBooking.outcomeState == "DELEGATION_STOPPED_RESULT_UNKNOWN" || (liveBooking.isResultUnknown && isDelegationStopped)) ->
                            "DELEGATION_STOPPED_RESULT_UNKNOWN"
                        liveBooking != null && liveBooking.isResultUnknown -> "RESULT_UNKNOWN"
                        liveBooking == null && (current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName || hadExternalWriteAttempt.get()) ->
                            "RESULT_UNKNOWN"
                        isDelegationStopped -> "CANCELLED"
                        else -> "FAILED"
                    }
                    val nextPaymentState = when {
                        finalStatus in setOf("DELEGATION_STOPPED_SCHOOL_CONFIRMED", "RESULT_UNKNOWN", "DELEGATION_STOPPED_RESULT_UNKNOWN") &&
                            current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName ->
                            liveBooking?.billingState?.takeIf { it.isNotBlank() } ?: "HELD_PENDING_REVIEW"
                        liveBooking != null && liveBooking.billingState.isNotBlank() && current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName ->
                            liveBooking.billingState
                        finalStatus == "CONFIRMED" && current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName && current.priceMicropeach > 0L ->
                            "PAID"
                        else -> current.paymentState
                    }
                    current.copy(
                        precheckStatus = "PRECHECK_OK",
                        finalCheckStatus = "FINAL_CHECK_OK",
                        fireStatus = finalStatus,
                        status = finalStatus,
                        externalBookingId = verifiedExtId,
                        evidenceSha256 = verifiedEvidenceHash,
                        paymentState = nextPaymentState,
                        stateVersion = current.stateVersion + 1,
                        lastDiagnosticMessage = diagMsg,
                        updatedAtEpochMillis = nowEpochMillis
                    )
                }
            }
        }
        val finalEntity = bookingPlanStateMutex.withLock {
            database.withTransaction {
                val latestInDb = cloudAndParcelDao.getLibraryBookingPlanById(planId) ?: return@withTransaction current
                // 若在 PRECHECK / FINAL_CHECK 期间计划已被取消、切换引擎或代次更新，严禁回写覆盖最新状态
                if (stage != TripleScheduleStage.FIRE) {
                    if (latestInDb.generation != current.generation ||
                        latestInDb.executionMode != current.executionMode ||
                        latestInDb.status !in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")
                    ) {
                        return@withTransaction latestInDb
                    }
                    val stageUpdated = updated.copy(
                        generation = latestInDb.generation,
                        stateVersion = latestInDb.stateVersion + 1
                    )
                    cloudAndParcelDao.upsertLibraryBookingPlan(stageUpdated)
                    return@withTransaction stageUpdated
                }

                // FIRE 阶段完成回写：若未发起任何外部写入前计划已被取消，保持 CANCELLED 绝不回写覆盖
                if (latestInDb.status == "CANCELLED" && updated.status == "CANCELLED") {
                    return@withTransaction latestInDb
                }
                val stoppedMidFlight =
                    latestInDb.generation != current.generation ||
                        latestInDb.status in setOf(
                            "CANCELLED",
                            "DELEGATION_STOPPED_RESULT_UNKNOWN",
                            "DELEGATION_STOPPED_SCHOOL_CONFIRMED"
                        )
                val reconciledUpdated = if (stoppedMidFlight) {
                    when (updated.status) {
                        "CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED" ->
                            updated.copy(
                                status = "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                                fireStatus = "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                                paymentState = "HELD_PENDING_REVIEW",
                                remainingDays = 0,
                                generation = latestInDb.generation,
                                stateVersion = latestInDb.stateVersion + 1
                            )
                        "RESULT_UNKNOWN", "DELEGATION_STOPPED_RESULT_UNKNOWN" ->
                            updated.copy(
                                status = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                fireStatus = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                remainingDays = 0,
                                generation = latestInDb.generation,
                                stateVersion = latestInDb.stateVersion + 1
                            )
                        else ->
                            latestInDb.copy(
                                status = "CANCELLED",
                                fireStatus = "CANCELLED",
                                remainingDays = 0,
                                stateVersion = latestInDb.stateVersion + 1,
                                updatedAtEpochMillis = nowEpochMillis
                            )
                    }
                } else {
                    updated.copy(
                        generation = latestInDb.generation,
                        stateVersion = latestInDb.stateVersion + 1
                    )
                }

                // 仅当委托处于有效状态且今日结果为明确的 CONFIRMED 或 FAILED 时才允许推进次日多日计划；
                // 若为 DELEGATION_STOPPED_SCHOOL_CONFIRMED、RESULT_UNKNOWN、DELEGATION_STOPPED_RESULT_UNKNOWN 或 CANCELLED，一律禁止续期次日任务！
                if (!stoppedMidFlight &&
                    current.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName &&
                    reconciledUpdated.status in setOf("CONFIRMED", "FAILED", "REMINDER_SENT") &&
                    current.remainingDays > 1 &&
                    latestInDb.status !in setOf("CANCELLED", "NEEDS_RECONFIRMATION")
                ) {
                    val nextDate = runCatching { LocalDate.parse(current.targetLocalDate).plusDays(1) }
                        .getOrElse { LocalDate.now(DualEngineReservationPlanner.HAUT_ZONE).plusDays(1) }
                    val parsedTime = runCatching { DualEngineReservationPlanner.parseTargetLocalTime(current.targetLocalTime) }
                        .getOrDefault(LocalTime.of(7, 0))
                    val nextGen = latestInDb.generation + 1
                    val nextRem = (current.remainingDays - 1).coerceAtLeast(1)
                    val nextSchedule = runCatching {
                        DualEngineReservationPlanner.computeSchedule(
                            planId = current.id,
                            targetDate = nextDate,
                            targetTime = parsedTime,
                            generation = nextGen,
                            nowEpochMillis = nowEpochMillis
                        )
                    }.getOrNull()
                    if (nextSchedule != null) {
                        val dayArchiveId = "${current.id}_d_${current.targetLocalDate.replace("-", "")}"
                        cloudAndParcelDao.upsertLibraryBookingPlan(
                            reconciledUpdated.copy(
                                id = dayArchiveId,
                                remainingDays = 0,
                                updatedAtEpochMillis = nowEpochMillis
                            )
                        )
                        val nextCloudJobId = if (current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
                            "cjob_${current.id.takeLast(8)}_${nextDate.toString().replace("-", "")}"
                        } else {
                            null
                        }
                        val rolledOver = current.copy(
                            targetLocalDate = nextDate.toString(),
                            precheckAtEpochMillis = nextSchedule.precheckAtEpochMillis,
                            finalCheckAtEpochMillis = nextSchedule.finalCheckAtEpochMillis,
                            fireAtEpochMillis = nextSchedule.fireAtEpochMillis,
                            precheckStatus = "ARMED",
                            finalCheckStatus = "ARMED",
                            fireStatus = "ARMED",
                            status = "ARMED",
                            cloudJobId = nextCloudJobId,
                            externalBookingId = null,
                            evidenceSha256 = null,
                            paymentState = if (current.priceMicropeach == 0L) "NOT_REQUIRED" else "DEBIT_PENDING",
                            remainingDays = nextRem,
                            generation = nextGen,
                            stateVersion = reconciledUpdated.stateVersion + 1,
                            lastDiagnosticMessage = "${reconciledUpdated.lastDiagnosticMessage.orEmpty()} · 已自动续期次日 ($nextDate ${current.targetLocalTime}) 抢座（剩余 $nextRem/${current.repeatDays} 天）",
                            updatedAtEpochMillis = nowEpochMillis
                        )
                        cloudAndParcelDao.upsertLibraryBookingPlan(rolledOver)
                        LibrarySeatAlarmSubsystem.schedulePlanAlarms(appContext, rolledOver)
                        rolledOver
                    } else {
                        val halted = reconciledUpdated.copy(remainingDays = 0)
                        cloudAndParcelDao.upsertLibraryBookingPlan(halted)
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, halted.id)
                        halted
                    }
                } else {
                    val halted = if (reconciledUpdated.status !in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")) {
                        reconciledUpdated.copy(remainingDays = 0)
                    } else {
                        reconciledUpdated
                    }
                    cloudAndParcelDao.upsertLibraryBookingPlan(halted)
                    if (halted.status !in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")) {
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, halted.id)
                    }
                    halted
                }
            }
        }
        if (current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
            repoScope.launch { runCatching { syncWithCampusCloud() } }
        }
        finalEntity
        } finally {
            val activeAttempt = activeFireAttemptIdHolder[0]
            if (activeAttempt != null) {
                activeInProcessFireAttempts.remove(current.id, activeAttempt)
                activeDispatchedFireAttempts.remove(current.id)
            }
        }
    }

    /**
     * 停止排队中或正在执行中的抢座委托（仅停止后续尝试与多日续期，绝不伪造学校已退座，也不自动取消已确认座位）：
     * - 若在未向学校发出外部写请求前停止，转入 CANCELLED；
     * - 若外部请求已发出或处于云端执行/未知状态，转入 DELEGATION_STOPPED_RESULT_UNKNOWN 保留待核对证据。
     */
    suspend fun stopInFlightOrQueuedDelegation(planId: String): LibraryBookingPlanEntity? {
        val current = cloudAndParcelDao.getLibraryBookingPlanById(planId) ?: return null
        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, planId)
        val nowMs = System.currentTimeMillis()
        var cloudStopSent = false
        if (current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
            val token = cloudIdentityRepository.resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isNotBlank()) {
                val cloudBaseUrl = runCatching {
                    NetworkProxyConfig.normalizeCloudApiBaseUrl(
                        preferencesFlow.first().networkProxyConfig.customCloudApiBaseUrl
                    )
                }.getOrDefault(CampusCloudOidcHelper.DEFAULT_CLOUD_BASE_URL)
                cloudStopSent = withContext(Dispatchers.IO) {
                    CampusCloudOidcHelper.stopCloudDelegationWithoutLiveSeatCancel(
                        accessToken = token,
                        cloudJobId = current.cloudJobId,
                        planId = current.id,
                        cloudBaseUrl = cloudBaseUrl,
                        httpTransport = cloudHttpTransportForTest
                    )
                }
            }
        }

        val updated = bookingPlanStateMutex.withLock {
            database.withTransaction {
                val cancelledQueued = cloudAndParcelDao.cancelQueuedBookingPlanAtomically(
                    planId = planId,
                    diagnosticMessage = "已停止委托并取消排队中的预约计划",
                    nowEpochMillis = nowMs
                )
                if (cancelledQueued > 0) {
                    val latestAfterCancel = cloudAndParcelDao.getLibraryBookingPlanById(planId)
                    if (latestAfterCancel != null &&
                        latestAfterCancel.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
                        !latestAfterCancel.cloudJobId.isNullOrBlank() &&
                        !latestAfterCancel.cloudJobId.startsWith("cjob_") &&
                        !cloudStopSent
                    ) {
                        // 离线停止已有云端真实 jobId 的排队任务：不能假定云端已停，转入 DELEGATION_STOPPED_RESULT_UNKNOWN
                        val guarded = latestAfterCancel.copy(
                            status = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                            fireStatus = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                            lastDiagnosticMessage = "本机已记录停止委托，但当前未联网确认云端任务已停止，结果待核对",
                            updatedAtEpochMillis = nowMs
                        )
                        cloudAndParcelDao.upsertLibraryBookingPlan(guarded)
                        return@withTransaction guarded
                    }
                    return@withTransaction latestAfterCancel
                }

                val latest = cloudAndParcelDao.getLibraryBookingPlanById(planId) ?: return@withTransaction null
                val inProcessPreWriteLocal =
                    latest.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName &&
                        activeInProcessFireAttempts.containsKey(planId) &&
                        activeDispatchedFireAttempts[planId] != true

                when {
                    inProcessPreWriteLocal && latest.status == "EXECUTING" -> {
                        val stoppedPreWrite = latest.copy(
                            status = "CANCELLED",
                            fireStatus = "CANCELLED",
                            generation = latest.generation + 1,
                            stateVersion = latest.stateVersion + 1,
                            remainingDays = 0,
                            lastDiagnosticMessage = "已在发起学校预约写入前停止本机抢座委托（后续尝试与多日续期已终止）",
                            updatedAtEpochMillis = nowMs
                        )
                        cloudAndParcelDao.upsertLibraryBookingPlan(stoppedPreWrite)
                        stoppedPreWrite
                    }
                    latest.status in setOf("CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED") -> {
                        // 点击「停止委托」与在途 FIRE 成功返回发生交错竞态：仅停止后续委托与多日续期，严禁越权代调学校退座接口！
                        val stoppedConfirmed = latest.copy(
                            status = "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                            fireStatus = "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                            generation = latest.generation + 1,
                            stateVersion = latest.stateVersion + 1,
                            remainingDays = 0,
                            lastDiagnosticMessage = "已停止后续委托与自动续期；本次在途抢座已在学校端成功锁定座位（未发起退座），如需释放请点击「学校退座」",
                            updatedAtEpochMillis = nowMs
                        )
                        cloudAndParcelDao.upsertLibraryBookingPlan(stoppedConfirmed)
                        stoppedConfirmed
                    }
                    latest.status == "CANCEL_RESULT_UNKNOWN" -> {
                        // 已处于退座结果待核对状态：停止委托不得覆盖退座待核对证据
                        val preservedUnknownCancel = latest.copy(
                            remainingDays = 0,
                            stateVersion = latest.stateVersion + 1,
                            updatedAtEpochMillis = nowMs
                        )
                        cloudAndParcelDao.upsertLibraryBookingPlan(preservedUnknownCancel)
                        preservedUnknownCancel
                    }
                    latest.status in setOf("EXECUTING", "RESULT_UNKNOWN", "DELEGATION_STOPPED_RESULT_UNKNOWN") ||
                        latest.fireStatus.startsWith("DISPATCH_INTENT") -> {
                        val stoppedUnknown = latest.copy(
                            status = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                            fireStatus = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                            generation = latest.generation + 1,
                            stateVersion = latest.stateVersion + 1,
                            remainingDays = 0,
                            lastDiagnosticMessage = "已停止后续尝试与多日续期；此前已发出的请求结果待核对（不代表学校已退座）",
                            updatedAtEpochMillis = nowMs
                        )
                        cloudAndParcelDao.upsertLibraryBookingPlan(stoppedUnknown)
                        stoppedUnknown
                    }
                    latest.status == "NEEDS_RECONFIRMATION" -> {
                        val stoppedReconfirm = latest.copy(
                            status = "CANCELLED",
                            fireStatus = "CANCELLED",
                            generation = latest.generation + 1,
                            stateVersion = latest.stateVersion + 1,
                            remainingDays = 0,
                            lastDiagnosticMessage = "已取消待确认授权的预约计划",
                            updatedAtEpochMillis = nowMs
                        )
                        cloudAndParcelDao.upsertLibraryBookingPlan(stoppedReconfirm)
                        stoppedReconfirm
                    }
                    else -> latest
                }
            }
        }
        repoScope.launch { runCatching { syncWithCampusCloud() } }
        return updated
    }

    /**
     * 仅执行「停止委托 / 取消排队计划」，绝不根据刷新后的最新状态暗中升级为真实学校退座！
     * 如需释放学校端已锁定的座位，必须由用户在已确认状态下显式触发 [cancelActiveLibrarySeatBooking]。
     */
    suspend fun cancelDualEngineBookingPlan(planId: String) {
        stopInFlightOrQueuedDelegation(planId)
    }

    @Volatile
    internal var liveSeatCancelObserverForTest: ((planId: String?, bookingId: String?) -> Unit)? = null

    suspend fun cancelActiveLibrarySeatBooking(
        planId: String? = null,
        bookingId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        liveSeatCancelObserverForTest?.invoke(planId, bookingId)
        runCatching {
            val plan = planId?.let { cloudAndParcelDao.getLibraryBookingPlanById(it) }
            val hasCloudJob = plan != null && !plan.cloudJobId.isNullOrBlank() && !plan.cloudJobId.startsWith("cjob_")
            var cloudCancelConfirmed = false
            if (hasCloudJob && plan != null) {
                val cloudToken = cloudIdentityRepository.resolveValidCloudAccessToken()
                if (cloudToken.isNotBlank()) {
                    val cloudBaseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                        preferencesFlow.first().networkProxyConfig.customCloudApiBaseUrl
                    )
                    cloudCancelConfirmed = CampusCloudOidcHelper.cancelCloudHostedReservationSeat(
                        accessToken = cloudToken,
                        jobId = plan.cloudJobId!!,
                        cloudBaseUrl = cloudBaseUrl
                    )
                }
            }
            val currentToken = readCachedLibrarySeatSession()?.rawToken
                ?: (ensureLibrarySeatSession(forceRefresh = false) as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
            val targetBookingId = bookingId ?: plan?.externalBookingId
            val msg = if (cloudCancelConfirmed) {
                "云端已通过学校权威回查确认取消该笔座位预约"
            } else {
                val validLocalToken = currentToken ?: throw IllegalStateException(
                    if (hasCloudJob) "云端退座未确认完成且本地未登录图书馆账号，退座结果待核对"
                    else "请先登录图书馆账号"
                )
                val firstRes = HautLibrarySeatAuthClient.cancelActiveSeatBooking(validLocalToken, targetBookingId)
                if (firstRes.isSuccess) {
                    firstRes.getOrThrow()
                } else {
                    val refreshed = ensureLibrarySeatSession(forceRefresh = true)
                    val nextToken = (refreshed as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
                        ?: throw (firstRes.exceptionOrNull() ?: IllegalStateException("退座失败"))
                    HautLibrarySeatAuthClient.cancelActiveSeatBooking(nextToken, targetBookingId).getOrThrow()
                }
            }
            if (plan != null) {
                LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
                bookingPlanStateMutex.withLock {
                    database.withTransaction {
                        val latest = cloudAndParcelDao.getLibraryBookingPlanById(plan.id) ?: plan
                        cloudAndParcelDao.upsertLibraryBookingPlan(
                            latest.copy(
                                status = "SCHOOL_CANCELLED",
                                generation = latest.generation + 1,
                                stateVersion = latest.stateVersion + 1,
                                remainingDays = 0,
                                lastDiagnosticMessage = msg,
                                updatedAtEpochMillis = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
            msg
        }
    }

    suspend fun rescheduleActiveLocalBookingPlans() {
        val now = System.currentTimeMillis()
        val activeBinding = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
        val activeStudentId = runCatching { preferencesFlow.first().savedStudentId }.getOrNull()
        bookingPlanStateMutex.withLock {
            val all = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
            for (plan in all) {
                // 若该计划正由当前进程内的协程活跃执行（activeInProcessFireAttempts 匹配），绝不误判为崩溃中断！
                val liveAttemptInThisProcess = activeInProcessFireAttempts[plan.id]
                if (liveAttemptInThisProcess != null &&
                    plan.fireStatus == "DISPATCH_INTENT:$liveAttemptInThisProcess"
                ) {
                    continue
                }
                // 崩溃恢复：若进程中断前计划遗留在 EXECUTING 或 DISPATCH_INTENT，立即恢复为 RESULT_UNKNOWN 并取消重复闹钟（完整保留原账号归属与证据）
                if (plan.status == "EXECUTING" || plan.fireStatus.startsWith("DISPATCH_INTENT")) {
                    LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
                    cloudAndParcelDao.upsertLibraryBookingPlan(
                        plan.copy(
                            status = "RESULT_UNKNOWN",
                            fireStatus = "RESULT_UNKNOWN",
                            remainingDays = 0,
                            stateVersion = plan.stateVersion + 1,
                            lastDiagnosticMessage = "进程中断恢复：此前抢座请求可能已发出，已转入结果待核对状态（禁止重复提交）",
                            updatedAtEpochMillis = now
                        )
                    )
                    continue
                }
                val belongsToActiveAccount = cloudIdentityRepository.isBookingPlanVisibleToIdentity(
                    plan = plan,
                    activeCloudUserId = activeBinding?.cloudUserId,
                    activeStudentId = activeStudentId
                )
                // 恢复入口与定时入口同规则：CLOUD_HOSTED 绝不本地重排闹钟（schedulePlanAlarms 入口
                // 亦自带拦截），仅撤销旧 pending intent；本机三阶段闹钟仅限非云端计划
                if (belongsToActiveAccount &&
                    plan.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName &&
                    plan.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")
                ) {
                    LibrarySeatAlarmSubsystem.schedulePlanAlarms(appContext, plan)
                } else {
                    LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
                }
            }
        }
    }

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

        fun getInstance(context: Context): CampusRepository =
            checkNotNull(AccountSessionCoordinator.getInstance(context).currentSnapshot()?.repository) {
                "账号正在初始化或切换，请等待当前身份就绪"
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
