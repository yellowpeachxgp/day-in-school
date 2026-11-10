package cn.edu.haut.campus.core.database

import android.content.Context
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
import cn.edu.haut.campus.core.campusservices.LibrarySeatAuthException
import cn.edu.haut.campus.core.campusservices.LibrarySeatOverviewSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatRefreshSource
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionExpiredException
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionState
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionVault
import cn.edu.haut.campus.core.campusservices.LibraryStudyRegionItem
import cn.edu.haut.campus.core.campusservices.OnlineUtilityRoomSnapshot
import cn.edu.haut.campus.core.campusservices.ParcelHubEngine
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
import cn.edu.haut.campus.core.campusservices.XiaomiExpressApi
import cn.edu.haut.campus.core.campusservices.XiaomiPassportClient
import cn.edu.haut.campus.core.campusservices.XiaomiSyncSummary
import cn.edu.haut.campus.core.campusservices.XiaomiTrackPoint
import cn.edu.haut.campus.core.campusservices.cleanNullLiteral
import cn.edu.haut.campus.core.campusservices.cleanNullableLiteral
import cn.edu.haut.campus.core.campusservices.optCleanString
import cn.edu.haut.campus.core.campusservices.optNullableCleanString
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
    private val librarySeatAuthMutex = Mutex()
    private val wanxiaoUtilityClient = WanxiaoUtilityClient()
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        val GIS_DICTIONARY_SYNCED_V15 = booleanPreferencesKey("official_gis_dictionary_synced_v15")
        val GIS_DICTIONARY_SYNCED_V16 = booleanPreferencesKey("official_gis_dictionary_synced_v16")
        val GIS_DICTIONARY_SYNCED_V17 = booleanPreferencesKey("official_gis_dictionary_synced_v17")
        val GIS_DICTIONARY_SYNCED_V18 = booleanPreferencesKey("official_gis_dictionary_synced_v18")
        val PERIOD_TIMES_11_12_SYNCED_V1 = booleanPreferencesKey("period_times_11_12_synced_v1")
        val TERM_MISMATCH_REPAIRED_V2 = booleanPreferencesKey("term_mismatch_repaired_v2")
        val LAST_SEASONAL_ROLLOVER_TERM_ID = stringPreferencesKey("last_seasonal_rollover_term_id_v1")
        val USER_MANUAL_TERM_SEASON = stringPreferencesKey("user_manual_term_override_season_v1")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("completed_onboarding_guide_v1")
        val GPA_PRIVACY = booleanPreferencesKey("gpa_privacy_shield_enabled")
        val DYNAMIC_COLOR = booleanPreferencesKey("use_material_you_dynamic_color")
        val TIMELINE_SCALE = floatPreferencesKey("timeline_zoom_scale")
        val OFFICIAL_GPA = stringPreferencesKey("official_gpa_value")
        val OFFICIAL_GPA_NOTE = stringPreferencesKey("official_gpa_note")
        val AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_jw_enabled")
        val AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_github_release_enabled")
        val LEGACY_SAVED_STUDENT_ID = stringPreferencesKey("saved_jw_student_id")
        val LEGACY_SAVED_PASSWORD_BASE64 = stringPreferencesKey("saved_jw_password_local")
        val ACADEMIC_CREDENTIAL_ACCOUNT_ID = stringPreferencesKey("academic_credential_account_id_v1")
        val ACADEMIC_REAUTH_REQUIRED = booleanPreferencesKey("academic_reauthentication_required_v1")
        val SAVED_SYNC_XNM = stringPreferencesKey("saved_jw_sync_xnm")
        val SAVED_SYNC_XQM = stringPreferencesKey("saved_jw_sync_xqm")
        val LAST_AUTO_SYNC_MS = longPreferencesKey("last_auto_sync_epoch_millis")
        val CUSTOM_CHANNEL_URLS = stringPreferencesKey("custom_qq_channel_urls_v1")
        val CUSTOM_CLUB_GROUPS = stringPreferencesKey("custom_club_qq_groups_v1")
        val CLOUD_CAMPUS_CONTENT_JSON_LEGACY_V1 = stringPreferencesKey("cloud_campus_content_json_v1")
        val CLOUD_CAMPUS_CONTENT_JSON = stringPreferencesKey("cloud_campus_content_json_v2")
        val CLOUD_CAMPUS_CONTENT_SYNCED_MS = longPreferencesKey("cloud_campus_content_synced_ms_v2")
        val CLOUD_MOMENTS_SNAPSHOT_JSON = stringPreferencesKey("cloud_moments_snapshot_json_v1")
        val MOMENTS_FAVORITE_POST_IDS = stringPreferencesKey("moments_favorite_post_ids_v1")
        val MOMENTS_FAVORITE_BOOK_IDS = stringPreferencesKey("moments_favorite_book_ids_v1")
        val MOMENTS_MY_POST_IDS = stringPreferencesKey("moments_my_post_ids_v1")
        val MOMENTS_MY_BOOK_IDS = stringPreferencesKey("moments_my_book_ids_v1")
        val MOMENTS_MY_TAG_CODES = stringPreferencesKey("moments_my_tag_codes_v1")
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
        val UTILITY_DAILY_NOTIFY_ENABLED = booleanPreferencesKey("utility_daily_notify_enabled_v1")
        val UTILITY_DAILY_NOTIFY_HOUR = intPreferencesKey("utility_daily_notify_hour_v1")
        val UTILITY_LOW_BALANCE_THRESHOLD = floatPreferencesKey("utility_low_balance_threshold_v1")
        val LAST_UTILITY_DAILY_NOTIFY_DATE = stringPreferencesKey("last_utility_daily_notify_date_v1")
        val MEAL_RECOMMEND_ENABLED = booleanPreferencesKey("meal_recommend_enabled_v1")
        val LAST_MEAL_RECOMMEND_BLOCK_KEY = stringPreferencesKey("last_meal_recommend_block_key_v1")
        val CANTEEN_DISH_REVIEWS = stringPreferencesKey("canteen_dish_reviews_v1")
        val SELECTED_FOCUS_MINUTES = intPreferencesKey("selected_focus_minutes_v1")
        val THEME_MODE = stringPreferencesKey("theme_mode_v1")
        val UI_DESIGN_STYLE = stringPreferencesKey("ui_design_style_v1")
        val THEME_PALETTE_KEY = stringPreferencesKey("theme_palette_key_v1")
        val CUSTOM_THEME_SEED_ARGB = longPreferencesKey("custom_theme_seed_argb_v1")
        val CUSTOM_WALLPAPER_PATH = stringPreferencesKey("custom_wallpaper_path_v1")
        val WALLPAPER_ALPHA = floatPreferencesKey("wallpaper_alpha_v1")
        val WALLPAPER_BLUR_DP = floatPreferencesKey("wallpaper_blur_dp_v1")
        val WALLPAPER_CARD_ALPHA = floatPreferencesKey("wallpaper_card_alpha_v1")
        val EXTRACTED_WALLPAPER_COLORS = stringPreferencesKey("extracted_wallpaper_colors_v1")
        val HOME_CARD_ORDER = stringPreferencesKey("home_card_order_v1")
        val HIDDEN_HOME_CARDS = stringPreferencesKey("hidden_home_cards_v1")
        val FULL_WIDTH_HOME_CARDS = stringPreferencesKey("full_width_home_cards_v1")
        val PROXY_MODE = stringPreferencesKey("network_proxy_mode_v1")
        val PROXY_PRESET_MIRROR_KEY = stringPreferencesKey("network_proxy_preset_mirror_key_v1")
        val PROXY_CUSTOM_PREFIX = stringPreferencesKey("network_proxy_custom_prefix_v1")
        val PROXY_HOST = stringPreferencesKey("network_proxy_host_v1")
        val PROXY_PORT = intPreferencesKey("network_proxy_port_v1")
        val PROXY_AUTO_FALLBACK = booleanPreferencesKey("network_proxy_auto_fallback_v1")
        val CLOUD_API_BASE_URL = stringPreferencesKey("cloud_api_base_url_v1")
        val CLOUD_ACCESS_TOKEN = stringPreferencesKey("cloud_access_token_v1")
        val CLOUD_REFRESH_TOKEN = stringPreferencesKey("cloud_refresh_token_v1")
        val SELECTED_CAMPUS_ID = stringPreferencesKey("selected_campus_id_v1")
        val CUSTOM_CAMPUS_ZONES_JSON = stringPreferencesKey("custom_campus_zones_json_v1")
        val WIDGET_DEFAULT_COMPACT = booleanPreferencesKey("widget_default_compact_v1")
        val WIDGET_DEFAULT_PRIVACY_MODE = booleanPreferencesKey("widget_default_privacy_mode_v1")
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
            val rawMoments = prefs[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON]
            if (!rawMoments.isNullOrBlank()) {
                val parsed = CampusCloudOidcHelper.parseMomentsSnapshotJsonString(rawMoments)
                if (parsed != null) {
                    val cleanedSnap = parsed.copy(
                        posts = parsed.posts.filterNot { it.id in legacyMomentsSampleIds },
                        bookListings = parsed.bookListings.filterNot { it.id in legacyMomentsSampleIds },
                        antiLostTags = parsed.antiLostTags.filterNot { it.id in legacyMomentsSampleIds || it.tagCode == "HAUT-TAG-7E2A9B" }
                    )
                    prefs[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                        CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(cleanedSnap)
                }
            }
        }
    }

    val preferencesFlow: Flow<AppPreferencesState> = preferences.data
        .onStart { migrateLegacyAcademicCredentialsIfNeeded() }
        .map { prefs ->
        val credentialResult = prefs[PrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID]?.let { accountId ->
            academicCredentialVault.read(academicCredentialScope(accountId))
        } ?: AcademicCredentialReadResult.Empty
        val savedStudentId = (credentialResult as? AcademicCredentialReadResult.Available)
            ?.credentials?.use { it.studentId }
        val requiresReauthentication = prefs[PrefKeys.ACADEMIC_REAUTH_REQUIRED] == true ||
            credentialResult == AcademicCredentialReadResult.ReauthenticationRequired ||
            (prefs[PrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] != null && savedStudentId == null)
        val reminderConfig = extractReminderConfig(prefs)
        val rawOrder = prefs[PrefKeys.HOME_CARD_ORDER]
            ?.split(",")
            ?.map { it.trim() }
            .orEmpty()
        val normalizedOrder = HomeCardModule.normalizeOrderIds(rawOrder)
        val hiddenSet = HomeCardModule.normalizeHiddenIds(
            prefs[PrefKeys.HIDDEN_HOME_CARDS]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet()
        )
        val fullWidthSet = HomeCardModule.normalizeFullWidthIds(
            prefs[PrefKeys.FULL_WIDTH_HOME_CARDS]
                ?.let { raw ->
                    if (raw == HomeCardModule.EMPTY_SET_SENTINEL) setOf(HomeCardModule.EMPTY_SET_SENTINEL)
                    else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                }
        )

        AppPreferencesState(
            gpaPrivacyShieldEnabled = prefs[PrefKeys.GPA_PRIVACY] ?: false,
            useDynamicColor = prefs[PrefKeys.DYNAMIC_COLOR] ?: false,
            themeMode = prefs[PrefKeys.THEME_MODE] ?: "SYSTEM",
            uiDesignStyle = prefs[PrefKeys.UI_DESIGN_STYLE]
                ?: if (prefs[PrefKeys.THEME_PALETTE_KEY] == "LIQUID_GLASS") "LIQUID_GLASS" else "FRESH_BREEZE",
            themePaletteKey = prefs[PrefKeys.THEME_PALETTE_KEY] ?: "HAUT_BLUE",
            customThemeSeedArgb = run {
                val paletteKey = prefs[PrefKeys.THEME_PALETTE_KEY] ?: "HAUT_BLUE"
                val storedSeed = prefs[PrefKeys.CUSTOM_THEME_SEED_ARGB]
                when {
                    paletteKey == "HAUT_BLUE" && (storedSeed == null || storedSeed == 0xFF0066CCL) -> 0xFF053968L
                    storedSeed != null -> storedSeed
                    else -> 0xFF053968L
                }
            },
            customWallpaperPath = prefs[PrefKeys.CUSTOM_WALLPAPER_PATH]?.takeIf { it.isNotBlank() },
            wallpaperAlpha = ThemeVisualDefaults.clampWallpaperAlpha(
                prefs[PrefKeys.WALLPAPER_ALPHA] ?: ThemeVisualDefaults.DEFAULT_WALLPAPER_ALPHA
            ),
            wallpaperBlurDp = ThemeVisualDefaults.clampWallpaperBlurDp(
                prefs[PrefKeys.WALLPAPER_BLUR_DP] ?: ThemeVisualDefaults.DEFAULT_WALLPAPER_BLUR_DP
            ),
            wallpaperCardAlpha = ThemeVisualDefaults.clampWallpaperCardAlpha(
                prefs[PrefKeys.WALLPAPER_CARD_ALPHA] ?: ThemeVisualDefaults.DEFAULT_WALLPAPER_CARD_ALPHA
            ),
            extractedWallpaperColors = prefs[PrefKeys.EXTRACTED_WALLPAPER_COLORS]
                ?.split(",")
                ?.mapNotNull { it.trim().toLongOrNull() }
                .orEmpty(),
            homeCardOrder = normalizedOrder,
            hiddenHomeCards = hiddenSet,
            fullWidthHomeCards = fullWidthSet,
            timelineZoomScale = ThemeVisualDefaults.clampTimelineZoomScale(
                prefs[PrefKeys.TIMELINE_SCALE] ?: ThemeVisualDefaults.DEFAULT_TIMELINE_ZOOM_SCALE
            ),
            widgetDefaultCompact = prefs[PrefKeys.WIDGET_DEFAULT_COMPACT] ?: false,
            widgetDefaultPrivacyMode = prefs[PrefKeys.WIDGET_DEFAULT_PRIVACY_MODE] ?: false,
            officialGpaValuePlain = prefs[PrefKeys.OFFICIAL_GPA],
            officialGpaNote = prefs[PrefKeys.OFFICIAL_GPA_NOTE],
            hasSeededInitialMockData = prefs[PrefKeys.SEEDED] ?: false,
            hasCompletedOnboarding = prefs[PrefKeys.ONBOARDING_COMPLETED] ?: false,
            autoSyncEnabled = prefs[PrefKeys.AUTO_SYNC_ENABLED] ?: true,
            autoCheckUpdateEnabled = prefs[PrefKeys.AUTO_CHECK_UPDATE] ?: true,
            savedStudentId = savedStudentId,
            hasSavedCredentials = savedStudentId != null && !requiresReauthentication,
            academicReauthenticationRequired = requiresReauthentication,
            syncXnm = prefs[PrefKeys.SAVED_SYNC_XNM] ?: AcademicTermResolver.defaultAcademicYearCode(),
            syncXqm = prefs[PrefKeys.SAVED_SYNC_XQM] ?: AcademicTermResolver.defaultSemesterCode(),
            lastAutoSyncEpochMillis = prefs[PrefKeys.LAST_AUTO_SYNC_MS],
            customChannelUrls = decodeKeyValueMap(prefs[PrefKeys.CUSTOM_CHANNEL_URLS]),
            customClubGroupNumbers = decodeKeyValueMap(prefs[PrefKeys.CUSTOM_CLUB_GROUPS]),
            campusCommunityCatalog = CampusCloudOidcHelper.parseCampusContentJsonString(
                prefs[PrefKeys.CLOUD_CAMPUS_CONTENT_JSON]
            ) ?: cn.edu.haut.campus.core.model.CampusCommunityCloudCatalog(),
            lastCampusContentSyncEpochMillis = prefs[PrefKeys.CLOUD_CAMPUS_CONTENT_SYNCED_MS],
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
            utilitiesEnabled = supportsHautServices && (prefs[PrefKeys.FF_UTILITIES_ENABLED] ?: true),
            libraryNativeEnabled = supportsHautServices && (prefs[PrefKeys.FF_LIBRARY_NATIVE_ENABLED] ?: true),
            librarySeatEnabled = supportsHautServices && (prefs[PrefKeys.FF_LIBRARY_SEAT_ENABLED] ?: true),
            focusEnabled = prefs[PrefKeys.FF_FOCUS_ENABLED] ?: true,
            showTodayFocusQuickPill = prefs[PrefKeys.FF_TODAY_FOCUS_PILL] ?: true,
            timetableInterchangeEnabled = prefs[PrefKeys.FF_TIMETABLE_INTERCHANGE] ?: true,
            allowUnverifiedUtilityTransport = prefs[PrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT] ?: false,
            lastUtilitySampleEpochMillis = prefs[PrefKeys.LAST_UTILITY_SAMPLE_MS],
            utilityDailyNotifyEnabled = prefs[PrefKeys.UTILITY_DAILY_NOTIFY_ENABLED] ?: true,
            utilityDailyNotifyHour = (prefs[PrefKeys.UTILITY_DAILY_NOTIFY_HOUR] ?: 20).coerceIn(8, 23),
            utilityLowBalanceThreshold = (prefs[PrefKeys.UTILITY_LOW_BALANCE_THRESHOLD] ?: 15f).coerceIn(5f, 100f),
            lastUtilityDailyNotifyDate = prefs[PrefKeys.LAST_UTILITY_DAILY_NOTIFY_DATE],
            mealRecommendEnabled = supportsHautServices && (prefs[PrefKeys.MEAL_RECOMMEND_ENABLED] ?: true),
            lastMealRecommendBlockKey = prefs[PrefKeys.LAST_MEAL_RECOMMEND_BLOCK_KEY],
            canteenDishReviews = cn.edu.haut.campus.core.domain.CanteenDishReviewCodec.decodeList(
                prefs[PrefKeys.CANTEEN_DISH_REVIEWS]
            ),
            campusMomentsSnapshot = run {
                val rawSnap = CampusCloudOidcHelper.parseMomentsSnapshotJsonString(
                    prefs[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON]
                ) ?: cn.edu.haut.campus.core.model.CampusMomentsSnapshot()
                val hasCloudSessionToken = !prefs[PrefKeys.CLOUD_ACCESS_TOKEN].isNullOrBlank()
                val baseSnap = if (hasCloudSessionToken) {
                    rawSnap
                } else {
                    CampusCloudOidcHelper.sanitizeMomentsSnapshotForViewerReset(rawSnap)
                }
                baseSnap.copy(
                    favoritePostIds = if (hasCloudSessionToken) decodeCsvSet(prefs[PrefKeys.MOMENTS_FAVORITE_POST_IDS]) else emptySet(),
                    favoriteBookIds = if (hasCloudSessionToken) decodeCsvSet(prefs[PrefKeys.MOMENTS_FAVORITE_BOOK_IDS]) else emptySet(),
                    myPostIds = if (hasCloudSessionToken) decodeCsvSet(prefs[PrefKeys.MOMENTS_MY_POST_IDS]) else emptySet(),
                    myBookIds = if (hasCloudSessionToken) decodeCsvSet(prefs[PrefKeys.MOMENTS_MY_BOOK_IDS]) else emptySet(),
                    myTagCodes = if (hasCloudSessionToken) decodeCsvSet(prefs[PrefKeys.MOMENTS_MY_TAG_CODES]) else emptySet()
                )
            },
            selectedFocusMinutes = (prefs[PrefKeys.SELECTED_FOCUS_MINUTES] ?: 25).coerceIn(1, 180),
            networkProxyConfig = extractNetworkProxyConfig(prefs),
            selectedCampusId = prefs[PrefKeys.SELECTED_CAMPUS_ID] ?: "auto",
            customCampusZonesJson = prefs[PrefKeys.CUSTOM_CAMPUS_ZONES_JSON] ?: "[]"
        )
    }.flowOn(Dispatchers.IO)

    private fun extractNetworkProxyConfig(prefs: androidx.datastore.preferences.core.Preferences): NetworkProxyConfig {
        return NetworkProxyConfig(
            mode = ProxyMode.fromKey(prefs[PrefKeys.PROXY_MODE]),
            presetMirrorKey = prefs[PrefKeys.PROXY_PRESET_MIRROR_KEY] ?: PresetMirrorNode.GHFAST_TOP.key,
            customReverseProxyPrefix = prefs[PrefKeys.PROXY_CUSTOM_PREFIX] ?: "",
            proxyHost = prefs[PrefKeys.PROXY_HOST]?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
            proxyPort = (prefs[PrefKeys.PROXY_PORT] ?: 7890).coerceIn(1, 65535),
            autoFallbackMirror = prefs[PrefKeys.PROXY_AUTO_FALLBACK] ?: true,
            customCloudApiBaseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(prefs[PrefKeys.CLOUD_API_BASE_URL])
        )
    }

    private fun extractReminderConfig(prefs: androidx.datastore.preferences.core.Preferences): ReminderGlobalConfig {
        val quietMode = prefs[PrefKeys.REMINDER_QUIET_MODE] ?: "FOLLOW_SYSTEM"
        return ReminderGlobalConfig(
            enabled = prefs[PrefKeys.REMINDERS_ENABLED] ?: true,
            classLeadMinutes = (prefs[PrefKeys.CLASS_LEAD_MINUTES] ?: 20).coerceIn(5, 120),
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
        combine(dao.observeBuildings(), preferences.data) { list, prefs ->
            val base = list.map { it.toDomain() }
            val songshan = if (supportsHautServices) ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS else emptyList()
            val customZones = cn.edu.haut.campus.core.model.CustomCampusZoneCodec.decodeList(
                prefs[PrefKeys.CUSTOM_CAMPUS_ZONES_JSON] ?: "[]"
            )
            val customBuildings = customZones.flatMap { it.buildings }
            (songshan + base.filter { it.campusId != "songshanlu" && it.id !in setOf("b_canteen2", "b_canteen3") } + customBuildings)
                .distinctBy { it.id }
        }

    val buildingAliasesFlow: Flow<List<BuildingAlias>> =
        dao.observeBuildingAliases().map { list ->
            val removedAliasIds = setOf(
                "al_4", "al_4b", "al_4c", "al_5",
                "al_cant2_a", "al_cant2_b", "al_cant2_c",
                "al_cant3_a", "al_cant3_b", "al_cant3_c"
            )
            val base = list.map { it.toDomain() }.filter { it.id !in removedAliasIds }
            val songshan = if (supportsHautServices) ClassroomBuildingResolver.HAUT_SONGSHANLU_ALIASES else emptyList()
            (songshan + base).distinctBy { it.id }
        }

    val widgetConfigsFlow: Flow<List<WidgetInstanceConfig>> =
        dao.observeWidgetConfigs().map { list -> list.map { it.toDomain() } }

    val importAuditsFlow: Flow<List<ImportAudit>> =
        dao.observeImportAudits().map { list -> list.map { it.toDomain() } }

    val examSchedulesFlow: Flow<List<ExamScheduleItem>> =
        dao.observeExamSchedules().map { list -> list.map { it.toDomain() } }

    val unscheduledCoursesFlow: Flow<List<UnscheduledAcademicCourseEntity>> =
        dao.observeUnscheduledCourses()

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
        val prefs = preferences.data.first()
        if (!accountProfile.isLegacy) {
            if (prefs[PrefKeys.CLEAN_BASE_V2] != true) {
                database.withTransaction {
                    if (supportsHautServices) seedBaseCampusDictionaryInternal() else seedOfflineDefaultsInternal()
                }
                preferences.edit {
                    it[PrefKeys.CLEAN_BASE_V2] = true
                    it[PrefKeys.SEEDED] = true
                    it[PrefKeys.ONBOARDING_COMPLETED] = true
                    it[PrefKeys.AUTO_SYNC_ENABLED] = false
                    it[PrefKeys.TERM_MISMATCH_REPAIRED_V2] = true
                    it[PrefKeys.GIS_DICTIONARY_SYNCED_V18] = true
                }
            } else if (supportsHautServices && prefs[PrefKeys.GIS_DICTIONARY_SYNCED_V18] != true) {
                syncCampusDictionaryV18Internal()
            }
            ensureExtendedPeriodTimes11To12IfNeeded()
            ensureSeasonalTermRolloverIfNeeded()
            return
        }
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
            preferences.edit {
                it[PrefKeys.SEEDED] = true
                it[PrefKeys.CLEAN_BASE_V2] = true
                it[PrefKeys.GIS_DICTIONARY_SYNCED_V15] = true
                it[PrefKeys.GIS_DICTIONARY_SYNCED_V16] = true
                it[PrefKeys.GIS_DICTIONARY_SYNCED_V17] = true
                it[PrefKeys.GIS_DICTIONARY_SYNCED_V18] = true
            }
        } else if (
            prefs[PrefKeys.GIS_DICTIONARY_SYNCED_V15] != true ||
            prefs[PrefKeys.GIS_DICTIONARY_SYNCED_V16] != true ||
            prefs[PrefKeys.GIS_DICTIONARY_SYNCED_V17] != true ||
            prefs[PrefKeys.GIS_DICTIONARY_SYNCED_V18] != true
        ) {
            syncCampusDictionaryV18Internal()
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

                // 修复旧版本因 currentTerm 写死 2026-2027-1 导致历史学期同步课表被错存到 2026-2027-1 的问题（仅当用户曾显式保存过旧同步学期配置时执行）
                val savedXnm = prefs[PrefKeys.SAVED_SYNC_XNM]
                val savedXqm = prefs[PrefKeys.SAVED_SYNC_XQM]
                if (!savedXnm.isNullOrBlank() && !savedXqm.isNullOrBlank()) {
                    val expectedTermId = HautAcademicEndpoints.toTermId(savedXnm, savedXqm)
                    val allRules = dao.getRulesSnapshot()
                    val rulesIn26 = allRules.filter { it.termId == "2026-2027-1" && it.id.startsWith("r_imp_") }
                    val rulesInExpected = allRules.filter { it.termId == expectedTermId }
                    if (expectedTermId != "2026-2027-1" && rulesIn26.isNotEmpty() && rulesInExpected.isEmpty()) {
                        dao.upsertRules(rulesIn26.map { it.copy(termId = expectedTermId) })
                        dao.setCurrentTerm(expectedTermId)
                    }
                }
            }
            preferences.edit {
                it[PrefKeys.TERM_MISMATCH_REPAIRED_V2] = true
                it.remove(PrefKeys.LAST_AUTO_SYNC_MS)
            }
        }
        ensureExtendedPeriodTimes11To12IfNeeded()
        ensureSeasonalTermRolloverIfNeeded()
    }

    /**
     * 为升级老用户补齐 11–12 节晚间默认作息时间（不覆盖用户已自定义编辑的 1–10 节作息）：
     * - 莲花街默认作息表（`haut_lianhuajie_default`）：21:15–22:00、22:05–22:50（若第 10 节被改晚则自动顺延）；
     * - 通用离线作息表（`manual_default` 或自定义表）：紧接已有最晚节次顺延 10 分钟，每节 45 分钟。
     */
    internal suspend fun ensureExtendedPeriodTimes11To12IfNeeded() {
        val prefs = preferences.data.first()
        val existingEntities = dao.observePeriodTimes().first()
        val byTimetable = existingEntities.map { it.toDomain() }.groupBy { it.timeTableId }
        val toInsert = mutableListOf<PeriodTimeEntity>()

        for ((ttId, list) in byTimetable) {
            if (ttId.isBlank()) continue
            val existingNos = list.map { it.periodNo }.toSet()
            if (11 in existingNos && 12 in existingNos) continue
            val p10End = list.filter { it.periodNo <= 10 }.maxByOrNull { it.periodNo }?.endLocalTime
            val defaultP11Start = if (ttId == HautMockSeedData.DEFAULT_TIMETABLE_ID) {
                val base = LocalTime.of(21, 15)
                if (p10End != null && !p10End.isBefore(base)) {
                    val mins = (p10End.hour * 60 + p10End.minute + 5).coerceAtMost(22 * 60 + 10)
                    LocalTime.of(mins / 60, mins % 60)
                } else base
            } else {
                val base = p10End ?: LocalTime.of(20, 40)
                val mins = (base.hour * 60 + base.minute + 10).coerceAtMost(22 * 60 + 10)
                LocalTime.of(mins / 60, mins % 60)
            }
            val p11EndMins = (defaultP11Start.hour * 60 + defaultP11Start.minute + 45).coerceAtMost(23 * 60 + 5)
            val p11End = LocalTime.of(p11EndMins / 60, p11EndMins % 60)
            val p12StartMins = (p11EndMins + 5).coerceAtMost(23 * 60 + 10)
            val p12Start = LocalTime.of(p12StartMins / 60, p12StartMins % 60)
            val p12EndMins = (p12StartMins + 45).coerceAtMost(23 * 60 + 55)
            val p12End = LocalTime.of(p12EndMins / 60, p12EndMins % 60)

            val idPrefix = if (ttId == HautMockSeedData.DEFAULT_TIMETABLE_ID) "pt" else "${ttId}_period"
            if (11 !in existingNos) {
                toInsert += PeriodTime("${idPrefix}_11", ttId, 11, defaultP11Start, p11End, "晚间").toEntity()
            }
            if (12 !in existingNos) {
                val actual11End = list.firstOrNull { it.periodNo == 11 }?.endLocalTime ?: p11End
                val s12Mins = (actual11End.hour * 60 + actual11End.minute + 5).coerceAtMost(23 * 60 + 10)
                val e12Mins = (s12Mins + 45).coerceAtMost(23 * 60 + 55)
                toInsert += PeriodTime(
                    "${idPrefix}_12",
                    ttId,
                    12,
                    LocalTime.of(s12Mins / 60, s12Mins % 60),
                    LocalTime.of(e12Mins / 60, e12Mins % 60),
                    "晚间"
                ).toEntity()
            }
        }

        if (toInsert.isNotEmpty()) {
            dao.upsertPeriodTimes(toInsert)
        }
        if (prefs[PrefKeys.PERIOD_TIMES_11_12_SYNCED_V1] != true) {
            preferences.edit { it[PrefKeys.PERIOD_TIMES_11_12_SYNCED_V1] = true }
        }
    }

    private fun parseTermSortKey(termId: String): Pair<Int, Int>? {
        val parts = termId.trim().split("-")
        if (parts.size < 3) return null
        val startYear = parts[0].toIntOrNull() ?: return null
        val semNo = parts[2].toIntOrNull() ?: return null
        return startYear to semNo
    }

    private fun isTermOlderThan(candidateTermId: String, referenceTermId: String): Boolean {
        val cand = parseTermSortKey(candidateTermId) ?: return false
        val ref = parseTermSortKey(referenceTermId) ?: return false
        return if (cand.first != ref.first) cand.first < ref.first else cand.second < ref.second
    }

    /**
     * 自然学期跨季自动演进：
     * 当升级老用户仍停留在上一自然学期（如 2025-2026-2 + SAVED_SYNC_XNM=2025/XQM=12），
     * 且未在本学期主动手动锁定查看旧学期时，自动推进 currentTerm 与默认同步参数至当前自然学期（如 2026-2027-1）。
     */
    internal suspend fun ensureSeasonalTermRolloverIfNeeded(today: LocalDate = LocalDate.now(timeZone)) {
        val seasonalTermId = AcademicTermResolver.defaultTermId(today)
        val seasonalXnm = AcademicTermResolver.defaultAcademicYearCode(today)
        val seasonalXqm = AcademicTermResolver.defaultSemesterCode(today)
        val prefs = preferences.data.first()
        val manualSeason = prefs[PrefKeys.USER_MANUAL_TERM_SEASON]
        val lastRolled = prefs[PrefKeys.LAST_SEASONAL_ROLLOVER_TERM_ID]
        val currentTermEntity = dao.getCurrentTerm()
        val savedXnm = prefs[PrefKeys.SAVED_SYNC_XNM]
        val savedXqm = prefs[PrefKeys.SAVED_SYNC_XQM]
        val savedSyncTermId = if (!savedXnm.isNullOrBlank() && !savedXqm.isNullOrBlank()) {
            HautAcademicEndpoints.toTermId(savedXnm, savedXqm)
        } else null

        val needsTermAdvance = manualSeason != seasonalTermId &&
            (currentTermEntity == null || isTermOlderThan(currentTermEntity.id, seasonalTermId))
        val needsSyncParamAdvance = manualSeason != seasonalTermId &&
            (savedSyncTermId == null || isTermOlderThan(savedSyncTermId, seasonalTermId))

        if (lastRolled == seasonalTermId && !needsTermAdvance && !needsSyncParamAdvance) {
            return
        }

        val repairedStart = if (seasonalTermId == "2026-2027-1") {
            val existing = dao.observeTerms().first().firstOrNull { it.id == seasonalTermId }
            if (existing?.startDateIso == "2026-09-07") LocalDate.of(2026, 8, 31) else null
        } else null

        ensureTermExistsAndUpdateCalendar(
            termId = seasonalTermId,
            semesterStart = repairedStart,
            setCurrent = needsTermAdvance
        )
        preferences.edit { editPrefs ->
            editPrefs[PrefKeys.LAST_SEASONAL_ROLLOVER_TERM_ID] = seasonalTermId
            if (needsSyncParamAdvance || needsTermAdvance) {
                editPrefs[PrefKeys.SAVED_SYNC_XNM] = seasonalXnm
                editPrefs[PrefKeys.SAVED_SYNC_XQM] = seasonalXqm
                editPrefs.remove(PrefKeys.LAST_AUTO_SYNC_MS)
            }
        }
        if (needsTermAdvance) {
            notifyScheduleOrPolicyChanged("SEASONAL_TERM_ROLLOVER")
        }
    }

    suspend fun completeOnboarding() {
        preferences.edit {
            it[PrefKeys.ONBOARDING_COMPLETED] = true
            it[PrefKeys.CLEAN_BASE_V2] = true
        }
    }

    suspend fun resetOnboardingForGuide() {
        preferences.edit {
            it[PrefKeys.ONBOARDING_COMPLETED] = false
        }
    }

    suspend fun resetToDemoSeedData() {
        check(supportsHautServices) { "当前学校不提供工大示例数据" }
        database.withTransaction {
            dao.clearCourses()
            dao.clearRules()
            dao.clearOccurrenceOverrides()
            dao.clearGradeAttempts()
            seedDemoDataInternal()
        }
        preferences.edit {
            it[PrefKeys.SEEDED] = true
            it[PrefKeys.CLEAN_BASE_V2] = true
            it[PrefKeys.ONBOARDING_COMPLETED] = true
            it.remove(PrefKeys.OFFICIAL_GPA)
            it.remove(PrefKeys.OFFICIAL_GPA_NOTE)
        }
        notifyScheduleOrPolicyChanged("RESET_DEMO_SEED_DATA")
    }

    private suspend fun syncCampusDictionaryV18Internal() {
        database.withTransaction {
            dao.deleteBuildingsByIds(listOf("b_canteen2", "b_canteen3"))
            dao.deleteBuildingAliasesByIds(
                listOf(
                    "al_4", "al_4b", "al_4c", "al_5",
                    "al_cant2_a", "al_cant2_b", "al_cant2_c",
                    "al_cant3_a", "al_cant3_b", "al_cant3_c"
                )
            )
            dao.upsertBuildings(
                (HautMockSeedData.lianhuajieBuildings + ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS)
                    .map { it.toEntity() }
            )
            dao.upsertBuildingAliases(
                (HautMockSeedData.defaultBuildingAliases + ClassroomBuildingResolver.HAUT_SONGSHANLU_ALIASES)
                    .map { it.toEntity() }
            )
        }
        preferences.edit {
            it[PrefKeys.GIS_DICTIONARY_SYNCED_V15] = true
            it[PrefKeys.GIS_DICTIONARY_SYNCED_V16] = true
            it[PrefKeys.GIS_DICTIONARY_SYNCED_V17] = true
            it[PrefKeys.GIS_DICTIONARY_SYNCED_V18] = true
        }
    }

    private suspend fun seedBaseCampusDictionaryInternal() {
        dao.upsertTerms(HautMockSeedData.defaultTerms.map { it.toEntity() })
        dao.upsertPeriodTimes(HautMockSeedData.defaultPeriodTimes.map { it.toEntity() })
        dao.upsertBuildings(
            (HautMockSeedData.lianhuajieBuildings + ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS)
                .map { it.toEntity() }
        )
        dao.upsertBuildingAliases(
            (HautMockSeedData.defaultBuildingAliases + ClassroomBuildingResolver.HAUT_SONGSHANLU_ALIASES)
                .map { it.toEntity() }
        )
        dao.upsertCalendarOverrides(HautMockSeedData.defaultCalendarOverrides.map { it.toEntity() })
    }

    /** 通用离线模式只提供可编辑作息，不推定学校建筑、调休或官方开学日期。 */
    private suspend fun seedOfflineDefaultsInternal() {
        val today = LocalDate.now(timeZone)
        val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val year = if (today.monthValue >= 8) today.year else today.year - 1
        val termNo = if (today.monthValue in 2..7) 2 else 1
        val timetableId = "manual_default"
        dao.upsertTerms(listOf(Term(
            id = "$year-${year + 1}-$termNo", academicYear = "$year-${year + 1}", termNumber = termNo,
            startDate = monday, weekCount = 20, timeTableId = timetableId, current = true
        ).toEntity()))
        val starts = listOf(
            "08:00", "08:55", "10:00", "10:55",
            "14:00", "14:55", "16:00", "16:55",
            "19:00", "19:55", "20:50", "21:45"
        )
        dao.upsertPeriodTimes(starts.mapIndexed { index, raw ->
            val start = LocalTime.parse(raw)
            PeriodTime("manual_period_${index + 1}", timetableId, index + 1, start, start.plusMinutes(45)).toEntity()
        })
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
        val seasonalTermId = AcademicTermResolver.defaultTermId(LocalDate.now(timeZone))
        preferences.edit { prefs ->
            prefs[PrefKeys.SAVED_SYNC_XNM] = xnm
            prefs[PrefKeys.SAVED_SYNC_XQM] = xqm
            if (termId != seasonalTermId) {
                prefs[PrefKeys.USER_MANUAL_TERM_SEASON] = seasonalTermId
            } else {
                prefs.remove(PrefKeys.USER_MANUAL_TERM_SEASON)
            }
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
        saveUserMergedCourseRuleEdit(
            course = course,
            primaryRule = rule,
            mergedRuleIds = listOf(rule.id)
        )
    }

    /**
     * 编辑可能由多条相邻规则纵向合并（如 1-2节 + 3-4节 合并显示为 1-4节）的全学期排课规则：
     * - 更新主规则为目标星期、起止节次、教室与周次；
     * - 对其余被合并的子规则，减去本次编辑覆盖的周次集合（若剩余周次为空则移除子规则及其孤立单日覆盖），彻底消除残余 3-4节 重叠！
     */
    suspend fun saveUserMergedCourseRuleEdit(
        course: Course,
        primaryRule: CourseScheduleRule,
        mergedRuleIds: List<String>
    ) {
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val resolvedBuilding = ClassroomBuildingResolver.resolve(primaryRule.rawRoom, buildings, aliases)
        val verifiedBuildingId = if (resolvedBuilding.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED) {
            resolvedBuilding.building?.id
        } else {
            primaryRule.buildingId
        }
        val extraRuleIds = mergedRuleIds.distinct().filter { it.isNotBlank() && it != primaryRule.id }

        database.withTransaction {
            val allRulesById = dao.getRulesSnapshot().associateBy { it.id }
            dao.upsertCourse(course.copy(userModified = true).toEntity())
            dao.upsertRule(
                primaryRule.copy(
                    buildingId = verifiedBuildingId,
                    userModified = true
                ).toEntity()
            )
            for (extraId in extraRuleIds) {
                val extraEntity = allRulesById[extraId] ?: continue
                val extraDomain = extraEntity.toDomain()
                val remainingWeeks = extraDomain.weeks - primaryRule.weeks
                if (remainingWeeks.isEmpty()) {
                    dao.deleteRuleById(extraId)
                    dao.deleteOccurrenceOverridesByRuleId(extraId)
                } else {
                    dao.upsertRule(
                        extraDomain.copy(
                            weeks = remainingWeeks,
                            userModified = true
                        ).toEntity()
                    )
                }
            }
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
            buildingId = if (resolved.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED) {
                resolved.building?.id
            } else null,
            userModified = true
        )
        database.withTransaction {
            dao.upsertCourse(course.toEntity())
            dao.upsertRule(rule.toEntity())
        }
        notifyScheduleOrPolicyChanged("MANUAL_COURSE_ADDED")
    }

    suspend fun saveOccurrenceOverride(override: OccurrenceOverride) {
        val resolvedBuildingId = if (override.buildingId == null && !override.room.isNullOrBlank()) {
            val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
            val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
            val resolved = ClassroomBuildingResolver.resolve(override.room!!, buildings, aliases)
            if (resolved.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED) {
                resolved.building?.id
            } else null
        } else {
            override.buildingId
        }
        val normalized = override.copy(buildingId = resolvedBuildingId)
        database.withTransaction {
            val ruleId = normalized.courseRuleId
            if (!ruleId.isNullOrBlank()) {
                dao.deleteOccurrenceOverridesForRuleOnDate(ruleId, normalized.date.toString())
            }
            dao.upsertOccurrenceOverride(normalized.toEntity())
        }
        notifyScheduleOrPolicyChanged("OCCURRENCE_OVERRIDE_SAVED")
    }

    /**
     * 对单次课程（含由多条相邻规则合并而成的 1-4 节连堂课）执行当日改教室/改节次：
     * - 主规则写入修改后的起止节次与教室；
     * - 其余被合并的子规则在同一天写入 `MERGED_INTO:<primaryRuleId>` 吸收抑制记录，防止原 3-4 节残留在当日课表造成重叠。
     */
    suspend fun saveSingleDayOccurrenceEdit(
        occ: cn.edu.haut.campus.core.model.ClassOccurrence,
        termId: String,
        newStartPeriod: Int,
        newEndPeriod: Int,
        newRoom: String,
        reason: String = "单次调课"
    ) {
        val cleanRoom = newRoom.trim().ifBlank { occ.rawRoom }
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val resolved = ClassroomBuildingResolver.resolve(cleanRoom, buildings, aliases)
        val verifiedBuildingId = if (resolved.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED) {
            resolved.building?.id
        } else if (cleanRoom == occ.rawRoom) {
            occ.buildingId
        } else null

        val sp = newStartPeriod.coerceIn(1, 12)
        val ep = newEndPeriod.coerceIn(sp, 12)
        val ruleIds = occ.mergedRuleIds.distinct().filter { it.isNotBlank() }

        database.withTransaction {
            if (ruleIds.isNotEmpty()) {
                val primaryRuleId = ruleIds.first()
                dao.deleteOccurrenceOverridesForRuleOnDate(primaryRuleId, occ.date.toString())
                dao.upsertOccurrenceOverride(
                    OccurrenceOverride(
                        id = occ.overrideId ?: "ov_mod_${occ.date}_$primaryRuleId",
                        termId = termId,
                        courseId = occ.courseId,
                        courseRuleId = primaryRuleId,
                        date = occ.date,
                        startPeriod = sp,
                        endPeriod = ep,
                        room = cleanRoom,
                        buildingId = verifiedBuildingId,
                        canceled = false,
                        reason = reason
                    ).toEntity()
                )
                for (extraRuleId in ruleIds.drop(1)) {
                    dao.deleteOccurrenceOverridesForRuleOnDate(extraRuleId, occ.date.toString())
                    dao.upsertOccurrenceOverride(
                        OccurrenceOverride(
                            id = "ov_merged_sup_${occ.date}_$extraRuleId",
                            termId = termId,
                            courseId = occ.courseId,
                            courseRuleId = extraRuleId,
                            date = occ.date,
                            canceled = true,
                            reason = "MERGED_INTO:$primaryRuleId"
                        ).toEntity()
                    )
                }
            } else {
                dao.upsertOccurrenceOverride(
                    OccurrenceOverride(
                        id = occ.overrideId ?: "ov_mod_${occ.date}_${UUID.randomUUID().toString().take(8)}",
                        termId = termId,
                        courseId = occ.courseId,
                        courseRuleId = null,
                        date = occ.date,
                        startPeriod = sp,
                        endPeriod = ep,
                        room = cleanRoom,
                        buildingId = verifiedBuildingId,
                        canceled = false,
                        reason = reason
                    ).toEntity()
                )
            }
        }
        notifyScheduleOrPolicyChanged("OCCURRENCE_OVERRIDE_SAVED")
    }

    suspend fun resetSingleDayOccurrenceEdit(occ: cn.edu.haut.campus.core.model.ClassOccurrence) {
        val dateIso = occ.date.toString()
        val ruleIds = occ.mergedRuleIds.distinct().filter { it.isNotBlank() }.toSet()
        database.withTransaction {
            if (!occ.overrideId.isNullOrBlank()) {
                dao.deleteOccurrenceOverrideById(occ.overrideId!!)
            }
            for (rId in ruleIds) {
                dao.deleteOccurrenceOverridesForRuleOnDate(rId, dateIso)
            }
            val companions = dao.getOccurrenceOverridesSnapshot().filter { ov ->
                ov.dateIso == dateIso &&
                    ruleIds.any { rId -> ov.reason == "MERGED_INTO:$rId" }
            }
            for (comp in companions) {
                dao.deleteOccurrenceOverrideById(comp.id)
            }
        }
        notifyScheduleOrPolicyChanged("OCCURRENCE_OVERRIDE_DELETED")
    }

    suspend fun cancelClassOccurrenceOnDate(
        occ: cn.edu.haut.campus.core.model.ClassOccurrence,
        termId: String,
        reason: String = "单次停课"
    ) {
        val explicitRuleIds = occ.mergedRuleIds.distinct().filter { it.isNotBlank() }
        database.withTransaction {
            val companionRuleIds = if (explicitRuleIds.isNotEmpty()) {
                dao.getOccurrenceOverridesSnapshot()
                    .filter { ov ->
                        ov.dateIso == occ.date.toString() &&
                            ov.courseRuleId != null &&
                            explicitRuleIds.any { rId -> ov.reason == "MERGED_INTO:$rId" }
                    }
                    .mapNotNull { it.courseRuleId }
            } else emptyList()
            val allRuleIds = (explicitRuleIds + companionRuleIds).distinct()
            if (allRuleIds.isNotEmpty()) {
                for (rId in allRuleIds) {
                    dao.deleteOccurrenceOverridesForRuleOnDate(rId, occ.date.toString())
                    dao.upsertOccurrenceOverride(
                        OccurrenceOverride(
                            id = "ov_cancel_${occ.date}_$rId",
                            termId = termId,
                            courseId = occ.courseId,
                            courseRuleId = rId,
                            date = occ.date,
                            canceled = true,
                            reason = reason
                        ).toEntity()
                    )
                }
            } else if (!occ.overrideId.isNullOrBlank()) {
                dao.upsertOccurrenceOverride(
                    OccurrenceOverride(
                        id = occ.overrideId!!,
                        termId = termId,
                        courseId = occ.courseId,
                        courseRuleId = null,
                        date = occ.date,
                        startPeriod = occ.startPeriod,
                        endPeriod = occ.endPeriod,
                        startAt = if (occ.hasResolvedTime) occ.startTime else null,
                        endAt = if (occ.hasResolvedTime) occ.endTime else null,
                        room = occ.rawRoom,
                        buildingId = occ.buildingId,
                        canceled = true,
                        reason = reason
                    ).toEntity()
                )
            }
        }
        notifyScheduleOrPolicyChanged("OCCURRENCE_CANCELED")
    }

    suspend fun rescheduleSingleOccurrenceCrossDate(
        occ: cn.edu.haut.campus.core.model.ClassOccurrence,
        termId: String,
        targetDate: LocalDate,
        newStartPeriod: Int,
        newEndPeriod: Int,
        newRoom: String,
        reason: String? = null
    ) {
        val cleanRoom = newRoom.trim().ifBlank { occ.rawRoom }
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val resolved = ClassroomBuildingResolver.resolve(cleanRoom, buildings, aliases)
        val verifiedBuildingId = if (resolved.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED) {
            resolved.building?.id
        } else if (cleanRoom == occ.rawRoom) {
            occ.buildingId
        } else null

        val ruleIds = occ.mergedRuleIds.distinct().filter { it.isNotBlank() }
        database.withTransaction {
            if (ruleIds.isNotEmpty()) {
                for (rId in ruleIds) {
                    dao.deleteOccurrenceOverridesForRuleOnDate(rId, occ.date.toString())
                    dao.upsertOccurrenceOverride(
                        OccurrenceOverride(
                            id = "ov_cancel_${occ.date}_$rId",
                            termId = termId,
                            courseId = occ.courseId,
                            courseRuleId = rId,
                            date = occ.date,
                            canceled = true,
                            reason = "已调至 $targetDate"
                        ).toEntity()
                    )
                }
            } else if (!occ.overrideId.isNullOrBlank()) {
                dao.deleteOccurrenceOverrideById(occ.overrideId!!)
            }

            dao.upsertOccurrenceOverride(
                OccurrenceOverride(
                    id = "ov_move_${targetDate}_${UUID.randomUUID().toString().take(8)}",
                    termId = termId,
                    courseId = occ.courseId,
                    courseRuleId = null,
                    date = targetDate,
                    startPeriod = newStartPeriod.coerceIn(1, 12),
                    endPeriod = newEndPeriod.coerceIn(newStartPeriod.coerceIn(1, 12), 12),
                    room = cleanRoom,
                    buildingId = verifiedBuildingId,
                    canceled = false,
                    reason = reason?.trim()?.takeIf { it.isNotBlank() } ?: "由 ${occ.date} 调入"
                ).toEntity()
            )
        }
        notifyScheduleOrPolicyChanged("OCCURRENCE_MOVED_CROSS_DATE")
    }

    suspend fun deleteOccurrenceOverride(overrideId: String) {
        dao.deleteOccurrenceOverrideById(overrideId)
        notifyScheduleOrPolicyChanged("OCCURRENCE_OVERRIDE_DELETED")
    }

    suspend fun restoreCanceledOccurrence(
        date: LocalDate,
        ruleId: String?,
        overrideId: String? = null,
        mergedRuleIds: List<String> = emptyList()
    ) {
        val allRuleIds = (mergedRuleIds + listOfNotNull(ruleId)).distinct().filter { it.isNotBlank() }
        database.withTransaction {
            if (!overrideId.isNullOrBlank()) {
                val existing = dao.getOccurrenceOverridesSnapshot().firstOrNull { it.id == overrideId }
                if (existing != null && existing.courseRuleId == null && existing.canceled) {
                    dao.upsertOccurrenceOverride(existing.copy(canceled = false, reason = "临时调课/补课"))
                } else {
                    dao.deleteOccurrenceOverrideById(overrideId)
                }
            }
            for (rId in allRuleIds) {
                dao.deleteCanceledOccurrenceOverridesForRuleOnDate(rId, date.toString())
            }
        }
        notifyScheduleOrPolicyChanged("OCCURRENCE_RESTORED")
    }

    suspend fun deleteCourseScheduleRule(ruleId: String) {
        deleteCourseScheduleRules(listOf(ruleId))
    }

    suspend fun deleteCourseScheduleRules(ruleIds: List<String>) {
        val cleanIds = ruleIds.distinct().filter { it.isNotBlank() }.toSet()
        if (cleanIds.isEmpty()) return
        database.withTransaction {
            val rules = dao.getRulesSnapshot()
            val targetRules = rules.filter { it.id in cleanIds }
            if (targetRules.isEmpty()) return@withTransaction
            val affectedCourseIds = targetRules.map { it.courseId }.toSet()
            for (rId in cleanIds) {
                dao.deleteRuleById(rId)
                dao.deleteOccurrenceOverridesByRuleId(rId)
            }
            val coursesById = dao.getCoursesSnapshot().associateBy { it.id }
            for (cId in affectedCourseIds) {
                val remainingForCourse = rules.count { it.courseId == cId && it.id !in cleanIds }
                if (remainingForCourse == 0) {
                    val course = coursesById[cId]
                    if (course != null && (
                            course.source == CourseSource.MANUAL.name ||
                                course.externalId?.startsWith("sjk:") == true
                            )
                    ) {
                        dao.deleteCourseById(course.id)
                    }
                }
            }
        }
        notifyScheduleOrPolicyChanged("COURSE_RULE_DELETED")
    }

    /**
     * 为「时间待定」的实践环节课程设置排课时间与地点：
     * - 绑定原课程标识（`sourceKey` / `name`），避免产生无关联的重复课程；
     * - 标记 `userModified = true`，确保后续教务同步不会静默覆盖或再次退回待定列表。
     */
    suspend fun scheduleUnscheduledPracticalCourse(
        unscheduled: UnscheduledAcademicCourseEntity,
        rawRoom: String,
        weekday: DayOfWeek,
        startPeriod: Int,
        endPeriod: Int,
        weeks: Set<Int>,
        colorArgb: Long = 0xFF0D9488
    ) {
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val cleanRoom = rawRoom.trim().ifBlank { "实践场地" }
        val resolved = ClassroomBuildingResolver.resolve(cleanRoom, buildings, aliases)
        val now = Instant.now()
        val stablePracticalExternalId = "sjk:${unscheduled.termId}:${unscheduled.sourceKey}"

        database.withTransaction {
            val existingCourse = dao.getCoursesSnapshot()
                .map { it.toDomain() }
                .firstOrNull { c ->
                    c.source == CourseSource.OFFICIAL_JW && c.externalId == stablePracticalExternalId
                }
            val courseId = existingCourse?.id ?: "c_prac_${UUID.randomUUID().toString().take(8)}"
            val course = existingCourse?.copy(
                teacher = unscheduled.teacher?.takeIf { it.isNotBlank() } ?: existingCourse.teacher,
                credit = unscheduled.creditPlain?.toBigDecimalOrNull() ?: existingCourse.credit,
                userModified = true,
                lastSyncAt = now
            ) ?: Course(
                id = courseId,
                externalId = stablePracticalExternalId,
                name = unscheduled.name,
                teacher = unscheduled.teacher?.takeIf { it.isNotBlank() },
                colorArgb = colorArgb,
                credit = unscheduled.creditPlain?.toBigDecimalOrNull(),
                source = CourseSource.OFFICIAL_JW,
                userModified = true,
                lastSyncAt = now
            )
            val rule = CourseScheduleRule(
                id = "r_prac_${UUID.randomUUID().toString().take(8)}",
                courseId = courseId,
                termId = unscheduled.termId,
                weekday = weekday,
                startPeriod = startPeriod.coerceIn(1, 12),
                endPeriod = endPeriod.coerceIn(startPeriod.coerceIn(1, 12), 12),
                weeks = weeks.ifEmpty { (1..16).toSet() },
                rawRoom = cleanRoom,
                buildingId = resolved.building?.id,
                userModified = true
            )
            dao.upsertCourse(course.toEntity())
            dao.upsertRule(rule.toEntity())
            dao.deleteUnscheduledCourseByKey(unscheduled.termId, unscheduled.sourceKey)
        }
        notifyScheduleOrPolicyChanged("PRACTICAL_COURSE_SCHEDULED")
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
                                    buildingId = when {
                                        resolved.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED ->
                                            resolved.building?.id
                                        candidate.rawRoom == existingRule.rawRoom ->
                                            existingRule.buildingId
                                        else -> null
                                    }
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
        preferences.edit {
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
        preferences.edit { prefs ->
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
        replaceExisting: Boolean = true,
        targetTermId: String? = null,
        targetTermComplete: Boolean = true,
        completedTermIds: Set<String> = emptySet()
    ) {
        database.withTransaction {
            if (replaceExisting) {
                if (!targetTermId.isNullOrBlank()) {
                    if (targetTermComplete) {
                        dao.clearExamSchedulesForTerm(targetTermId)
                    }
                    val termsToClear = if (completedTermIds.isNotEmpty()) {
                        completedTermIds.filter { it.isNotBlank() && it != targetTermId }
                    } else {
                        exams.map { it.termId }.distinct().filter { it.isNotBlank() && it != targetTermId }
                    }
                    for (tId in termsToClear) {
                        dao.clearExamSchedulesForTerm(tId)
                    }
                } else {
                    dao.clearExamSchedules()
                }
            }
            val filteredToUpsert = if (!targetTermId.isNullOrBlank() && !targetTermComplete) {
                exams.filter { it.termId != targetTermId }
            } else {
                exams
            }
            dao.upsertExamSchedules(filteredToUpsert.map { it.toEntity() })
        }
        notifyScheduleOrPolicyChanged("EXAMS_IMPORTED")
    }

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        preferences.edit { it[PrefKeys.AUTO_SYNC_ENABLED] = enabled }
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
            val previousId = preferences.data.first()[PrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID]
            val accountId = UUID.randomUUID().toString()
            val scope = academicCredentialScope(accountId)
            try {
                val previousStudentId = previousId?.let { prevAccId ->
                    (academicCredentialVault.read(academicCredentialScope(prevAccId)) as? AcademicCredentialReadResult.Available)
                        ?.credentials?.use { it.studentId }
                }?.trim().orEmpty()
                academicCredentialVault.save(scope, studentId, passwordPlain)
                preferences.edit { prefs ->
                    prefs[PrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] = accountId
                    prefs[PrefKeys.ACADEMIC_REAUTH_REQUIRED] = false
                    prefs.remove(PrefKeys.LEGACY_SAVED_STUDENT_ID)
                    prefs.remove(PrefKeys.LEGACY_SAVED_PASSWORD_BASE64)
                    prefs[PrefKeys.SAVED_SYNC_XNM] = xnm
                    prefs[PrefKeys.SAVED_SYNC_XQM] = xqm
                    prefs[PrefKeys.LAST_AUTO_SYNC_MS] = System.currentTimeMillis()
                }
                val nextStudentId = studentId.trim()
                if (previousStudentId.isNotEmpty() && previousStudentId != nextStudentId) {
                    isolateBookingPlansForStudentSwitch(previousStudentId, System.currentTimeMillis())
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
        if (current[PrefKeys.LEGACY_SAVED_PASSWORD_BASE64] == null &&
            current[PrefKeys.LEGACY_SAVED_STUDENT_ID] == null
        ) {
            return@withContext
        }
        academicCredentialMutex.withLock {
            val original = preferences.data.first()
            if (original[PrefKeys.LEGACY_SAVED_PASSWORD_BASE64] == null &&
                original[PrefKeys.LEGACY_SAVED_STUDENT_ID] == null
            ) {
                return@withLock
            }
            val accountId = original[PrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] ?: UUID.randomUUID().toString()
            preferences.edit { prefs ->
                prefs[PrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] = accountId
            }
            val migrated = migrateLegacyAcademicCredentials(
                vault = academicCredentialVault,
                scope = academicCredentialScope(accountId),
                studentId = original[PrefKeys.LEGACY_SAVED_STUDENT_ID],
                encodedPassword = original[PrefKeys.LEGACY_SAVED_PASSWORD_BASE64]
            )
            preferences.edit { prefs ->
                prefs.remove(PrefKeys.LEGACY_SAVED_STUDENT_ID)
                prefs.remove(PrefKeys.LEGACY_SAVED_PASSWORD_BASE64)
                prefs[PrefKeys.ACADEMIC_REAUTH_REQUIRED] = !migrated
                if (!migrated) prefs[PrefKeys.AUTO_SYNC_ENABLED] = false
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
                sibId != targetTermId && cands.isNotEmpty() && !isTermOlderThan(sibId, targetTermId)
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
            prefs[PrefKeys.SAVED_SYNC_XNM] = syncXnm
            prefs[PrefKeys.SAVED_SYNC_XQM] = syncXqm
            prefs[PrefKeys.LAST_AUTO_SYNC_MS] = System.currentTimeMillis()
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
        val autoEnabled = prefs[PrefKeys.AUTO_SYNC_ENABLED] ?: true
        if (!force && !autoEnabled) return@withLock null

        val lastSyncMs = prefs[PrefKeys.LAST_AUTO_SYNC_MS] ?: 0L
        val nowMs = System.currentTimeMillis()
        if (!force && (nowMs - lastSyncMs) < 15 * 60 * 1000L) {
            return@withLock null
        }

        val accountId = prefs[PrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] ?: return@withLock null
        val credentialResult = academicCredentialVault.read(academicCredentialScope(accountId))
        if (credentialResult !is AcademicCredentialReadResult.Available) {
            preferences.edit {
                it[PrefKeys.ACADEMIC_REAUTH_REQUIRED] = true
                it[PrefKeys.AUTO_SYNC_ENABLED] = false
            }
            return@withLock Result.failure(IllegalStateException("安全凭据已失效，请重新登录；本地课表和成绩已保留"))
        }

        val currentTermEntity = dao.getCurrentTerm()
        val defaultPair = currentTermEntity?.id?.let { HautAcademicEndpoints.fromTermId(it) }
            ?: (AcademicTermResolver.defaultAcademicYearCode() to AcademicTermResolver.defaultSemesterCode())
        val xnm = prefs[PrefKeys.SAVED_SYNC_XNM] ?: defaultPair.first
        val xqm = prefs[PrefKeys.SAVED_SYNC_XQM] ?: defaultPair.second

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
                    it[PrefKeys.AUTO_SYNC_ENABLED] = false
                    it[PrefKeys.ACADEMIC_REAUTH_REQUIRED] = true
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
        librarySeatSessionVault.readSnapshot()

    /**
     * 清理本地加密持久化的图书馆座位预约会话快照。
     */
    suspend fun clearLibrarySeatSession() = withContext(Dispatchers.IO) {
        librarySeatSessionVault.clear()
    }

    /**
     * 当用户在 WebView 内手动完成统一身份认证（如输入验证码后）跳转回 `wslib.haut.edu.cn/h5/index.html?token=...` 时，
     * 自动捕获并持久化最新 Token 与 `CASTGC` Cookie 到本地 AES-256-GCM 加密仓。
     */
    suspend fun captureLibrarySeatWebToken(rawToken: String): LibrarySeatSessionSnapshot? = withContext(Dispatchers.IO) {
        if (!isSessionActive || !supportsHautServices) return@withContext null
        val clean = HautLibrarySeatAuthClient.normalizeRawToken(rawToken)
        if (clean.isBlank()) return@withContext null
        librarySeatAuthMutex.withLock {
            val userInfo = HautLibrarySeatAuthClient.validateTokenOnline(clean) ?: return@withLock null
            val casTgc = HautLibrarySeatAuthClient.extractCastgcFromWebView()
                ?: librarySeatSessionVault.readSnapshot()?.casTgcCookie
            val snapshot = LibrarySeatSessionSnapshot(
                rawToken = clean,
                casTgcCookie = casTgc,
                casJsessionId = librarySeatSessionVault.readSnapshot()?.casJsessionId,
                userInfo = userInfo,
                updatedAtEpochMs = System.currentTimeMillis(),
                refreshSource = LibrarySeatRefreshSource.WEBVIEW_CAPTURE
            )
            librarySeatSessionVault.saveSnapshot(snapshot)
            snapshot
        }
    }

    /**
     * 确保图书馆座位预约拥有有效的持久化会话（三级保活）：
     * 1. 若持久化 Token 在线校验有效（且 `forceRefresh == false`），直接复用并同步 Cookie；
     * 2. 若 Token 失效，尝试使用持久化的 `CASTGC` Cookie 静默换取新 Token；
     * 3. 若 `CASTGC` Cookie 亦失效，自动从加密数据库 (`AcademicCredentialVault`) 读取学号与密码执行后台登录，
     *    并持久化新 Cookie 与 Token。
     */
    suspend fun ensureLibrarySeatSession(
        forceRefresh: Boolean = false
    ): LibrarySeatSessionState = withContext(Dispatchers.IO) {
        requireActiveSession()
        if (!supportsHautServices) {
            return@withContext LibrarySeatSessionState.AuthError("当前学校暂不支持工大图书馆空间预约服务")
        }
        migrateLegacyAcademicCredentialsIfNeeded()
        librarySeatAuthMutex.withLock {
            val cached = librarySeatSessionVault.readSnapshot()

            // Level 1: 校验已持久化的 wslib Token
            if (!forceRefresh && cached != null) {
                val verifiedUser = HautLibrarySeatAuthClient.validateTokenOnline(cached.rawToken)
                if (verifiedUser != null) {
                    HautLibrarySeatAuthClient.syncCasCookiesToWebView(
                        casTgcCookie = cached.casTgcCookie,
                        casJsessionId = cached.casJsessionId
                    )
                    val refreshed = cached.copy(
                        userInfo = verifiedUser,
                        updatedAtEpochMs = System.currentTimeMillis(),
                        refreshSource = LibrarySeatRefreshSource.CACHED_TOKEN
                    )
                    librarySeatSessionVault.saveSnapshot(refreshed)
                    return@withLock LibrarySeatSessionState.Authenticated(refreshed)
                }
            }

            // Level 2: 使用持久化的 CASTGC Cookie 静默换取新 Token
            val candidateCastgc = cached?.casTgcCookie ?: HautLibrarySeatAuthClient.extractCastgcFromWebView()
            if (!candidateCastgc.isNullOrBlank()) {
                val exchanged = HautLibrarySeatAuthClient.tryExchangeTokenWithPersistedCookie(
                    casTgcCookie = candidateCastgc,
                    casJsessionId = cached?.casJsessionId
                )
                if (exchanged != null) {
                    librarySeatSessionVault.saveSnapshot(exchanged)
                    return@withLock LibrarySeatSessionState.Authenticated(exchanged)
                }
            }

            // Level 3: 从加密凭据仓读取用户学号与统一认证密码执行后台自动重登
            val credentialPair: Pair<String, String>? = academicCredentialMutex.withLock {
                val prefs = preferences.data.first()
                val accountId = prefs[PrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] ?: return@withLock null
                val readResult = academicCredentialVault.read(academicCredentialScope(accountId))
                if (readResult !is AcademicCredentialReadResult.Available) {
                    return@withLock null
                }
                readResult.credentials.use { creds ->
                    creds.studentId to creds.passwordPlain
                }
            }

            if (credentialPair == null) {
                return@withLock LibrarySeatSessionState.CredentialsRequired(
                    "尚未保存统一身份认证凭据；绑定后将加密保存在本地数据库中，Cookie 失效时自动后台登录"
                )
            }

            val loginResult = HautLibrarySeatAuthClient.loginWithCredentials(
                studentId = credentialPair.first,
                passwordPlain = credentialPair.second
            )
            loginResult.fold(
                onSuccess = { snapshot ->
                    librarySeatSessionVault.saveSnapshot(snapshot)
                    LibrarySeatSessionState.Authenticated(snapshot)
                },
                onFailure = { error ->
                    val authError = error as? LibrarySeatAuthException
                    LibrarySeatSessionState.AuthError(
                        message = error.message ?: "自动后台登录图书馆预约系统未完成",
                        requiresManualVerification = authError?.requiresManualVerification == true
                    )
                }
            )
        }
    }

    /**
     * 在图书馆座位预约页绑定/更新统一身份认证账号密码（加密存入 `AcademicCredentialVault`），并立即执行后台登录与 Cookie 持久化。
     */
    suspend fun saveLibrarySeatCredentialsAndLogin(
        studentId: String,
        passwordPlain: String
    ): LibrarySeatSessionState = withContext(Dispatchers.IO) {
        requireActiveSession()
        val cleanId = studentId.trim()
        if (cleanId.isBlank() || passwordPlain.isBlank()) {
            return@withContext LibrarySeatSessionState.AuthError("请输入学号和统一身份认证密码")
        }
        val prefs = preferences.data.first()
        val currentTermEntity = dao.getCurrentTerm()
        val defaultPair = currentTermEntity?.id?.let { HautAcademicEndpoints.fromTermId(it) }
            ?: (AcademicTermResolver.defaultAcademicYearCode() to AcademicTermResolver.defaultSemesterCode())
        val xnm = prefs[PrefKeys.SAVED_SYNC_XNM] ?: defaultPair.first
        val xqm = prefs[PrefKeys.SAVED_SYNC_XQM] ?: defaultPair.second
        saveAuthorizedSyncCredentials(
            studentId = cleanId,
            passwordPlain = passwordPlain,
            xnm = xnm,
            xqm = xqm
        )
        librarySeatAuthMutex.withLock {
            HautLibrarySeatAuthClient.loginWithCredentials(
                studentId = cleanId,
                passwordPlain = passwordPlain
            ).fold(
                onSuccess = { snapshot ->
                    librarySeatSessionVault.saveSnapshot(snapshot)
                    LibrarySeatSessionState.Authenticated(snapshot)
                },
                onFailure = { error ->
                    val authError = error as? LibrarySeatAuthException
                    LibrarySeatSessionState.AuthError(
                        message = error.message ?: "统一身份认证登录失败，请核对学号和密码",
                        requiresManualVerification = authError?.requiresManualVerification == true
                    )
                }
            )
        }
    }

    /**
     * 拉取用户当前有效的图书馆座位预约、进行中预约列表、历史预约记录与暂离规则时长。
     * 若 Token 过期（`401`），会自动使用持久化 Cookie 或本地加密数据库凭据后台重登并无感重试。
     */
    suspend fun fetchLibrarySeatOverview(): Result<LibrarySeatOverviewSnapshot> = withContext(Dispatchers.IO) {
        val state0 = ensureLibrarySeatSession(forceRefresh = false)
        val token0 = (state0 as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
            ?: return@withContext Result.failure(
                IllegalStateException(
                    when (state0) {
                        is LibrarySeatSessionState.CredentialsRequired -> state0.message
                        is LibrarySeatSessionState.AuthError -> state0.message
                        else -> "尚未建立有效的图书馆选座登录会话"
                    }
                )
            )
        val firstTry = HautLibrarySeatAuthClient.fetchSeatBookingOverview(token0)
        if (firstTry.isSuccess || firstTry.exceptionOrNull() !is LibrarySeatSessionExpiredException) {
            return@withContext firstTry
        }
        val stateRetry = ensureLibrarySeatSession(forceRefresh = true)
        val tokenRetry = (stateRetry as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
            ?: return@withContext Result.failure(IllegalStateException("自动后台重登失败，请检查账号或网络"))
        HautLibrarySeatAuthClient.fetchSeatBookingOverview(tokenRetry)
    }

    /**
     * 执行图书馆座位控制动作（签到、快速签到、取消预约、短暂离、长暂离、暂离返回、续座、签退释放）。
     * 若执行时遇到 `401` 会话过期，自动触发加密凭据后台重登并重试一次。
     */
    suspend fun executeLibrarySeatAction(
        action: LibrarySeatActionType,
        bookingId: Long? = null
    ): Result<LibrarySeatActionOutcome> = withContext(Dispatchers.IO) {
        val state0 = ensureLibrarySeatSession(forceRefresh = false)
        val token0 = (state0 as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
            ?: return@withContext Result.failure(
                IllegalStateException(
                    when (state0) {
                        is LibrarySeatSessionState.CredentialsRequired -> state0.message
                        is LibrarySeatSessionState.AuthError -> state0.message
                        else -> "请先完成统一身份认证登录"
                    }
                )
            )
        val firstTry = HautLibrarySeatAuthClient.performSeatAction(
            rawToken = token0,
            action = action,
            bookingId = bookingId
        )
        if (firstTry.isSuccess || firstTry.exceptionOrNull() !is LibrarySeatSessionExpiredException) {
            return@withContext firstTry
        }
        val stateRetry = ensureLibrarySeatSession(forceRefresh = true)
        val tokenRetry = (stateRetry as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
            ?: return@withContext Result.failure(IllegalStateException("自动后台重登失败，请重试"))
        HautLibrarySeatAuthClient.performSeatAction(
            rawToken = tokenRetry,
            action = action,
            bookingId = bookingId
        )
    }

    suspend fun setGpaPrivacyShield(enabled: Boolean) {
        preferences.edit { it[PrefKeys.GPA_PRIVACY] = enabled }
    }

    suspend fun setUseDynamicColor(enabled: Boolean) {
        preferences.edit { it[PrefKeys.DYNAMIC_COLOR] = enabled }
        notifyScheduleOrPolicyChanged("THEME_UPDATED")
    }

    suspend fun setThemeMode(mode: String) {
        preferences.edit { it[PrefKeys.THEME_MODE] = mode }
        notifyScheduleOrPolicyChanged("THEME_UPDATED")
    }

    suspend fun setUiDesignStyle(styleKey: String) {
        val normalized = when {
            styleKey.equals("LIQUID_GLASS", ignoreCase = true) -> "LIQUID_GLASS"
            styleKey.equals("FRESH_BREEZE", ignoreCase = true) -> "FRESH_BREEZE"
            styleKey.equals("CREAM_PASTEL", ignoreCase = true) -> "CREAM_PASTEL"
            else -> "FLUTTER_M3"
        }
        preferences.edit { prefs ->
            prefs[PrefKeys.UI_DESIGN_STYLE] = normalized
            val currentPalette = prefs[PrefKeys.THEME_PALETTE_KEY]
            when (normalized) {
                "FRESH_BREEZE" -> {
                    if (currentPalette == null || currentPalette == "LIQUID_GLASS" || currentPalette == "HAUT_BLUE") {
                        prefs[PrefKeys.THEME_PALETTE_KEY] = "MINT_BREEZE"
                    }
                }
                "CREAM_PASTEL" -> {
                    if (currentPalette == null || currentPalette == "LIQUID_GLASS" || currentPalette == "HAUT_BLUE" || currentPalette == "MINT_BREEZE") {
                        prefs[PrefKeys.THEME_PALETTE_KEY] = "PEACH_OOLONG"
                    }
                }
                "FLUTTER_M3" -> {
                    if (currentPalette == "LIQUID_GLASS") {
                        prefs[PrefKeys.THEME_PALETTE_KEY] = "HAUT_BLUE"
                    }
                }
                "LIQUID_GLASS" -> {
                    if (prefs[PrefKeys.WALLPAPER_CARD_ALPHA] == null) {
                        prefs[PrefKeys.WALLPAPER_CARD_ALPHA] = ThemeVisualDefaults.DEFAULT_WALLPAPER_CARD_ALPHA
                    }
                    if (prefs[PrefKeys.WALLPAPER_BLUR_DP] == null) {
                        prefs[PrefKeys.WALLPAPER_BLUR_DP] = ThemeVisualDefaults.DEFAULT_WALLPAPER_BLUR_DP
                    }
                }
            }
        }
        notifyScheduleOrPolicyChanged("THEME_UPDATED")
    }

    suspend fun applyCuratedThemePreset(
        designStyleKey: String,
        paletteKey: String,
        clearWallpaper: Boolean = false
    ) {
        val normalizedStyle = when {
            designStyleKey.equals("LIQUID_GLASS", ignoreCase = true) -> "LIQUID_GLASS"
            designStyleKey.equals("FRESH_BREEZE", ignoreCase = true) -> "FRESH_BREEZE"
            designStyleKey.equals("CREAM_PASTEL", ignoreCase = true) -> "CREAM_PASTEL"
            else -> "FLUTTER_M3"
        }
        preferences.edit { prefs ->
            prefs[PrefKeys.UI_DESIGN_STYLE] = normalizedStyle
            prefs[PrefKeys.THEME_PALETTE_KEY] = paletteKey
            prefs[PrefKeys.DYNAMIC_COLOR] = false
            if (normalizedStyle == "LIQUID_GLASS") {
                if (prefs[PrefKeys.WALLPAPER_CARD_ALPHA] == null) {
                    prefs[PrefKeys.WALLPAPER_CARD_ALPHA] = ThemeVisualDefaults.DEFAULT_WALLPAPER_CARD_ALPHA
                }
                if (prefs[PrefKeys.WALLPAPER_BLUR_DP] == null) {
                    prefs[PrefKeys.WALLPAPER_BLUR_DP] = ThemeVisualDefaults.DEFAULT_WALLPAPER_BLUR_DP
                }
            }
            if (clearWallpaper) {
                prefs.remove(PrefKeys.CUSTOM_WALLPAPER_PATH)
            }
        }
        notifyScheduleOrPolicyChanged("THEME_UPDATED")
    }

    suspend fun setThemePalette(paletteKey: String, customSeedArgb: Long? = null) {
        preferences.edit { prefs ->
            prefs[PrefKeys.THEME_PALETTE_KEY] = paletteKey
            prefs[PrefKeys.DYNAMIC_COLOR] = false
            if (paletteKey == "LIQUID_GLASS") {
                prefs[PrefKeys.UI_DESIGN_STYLE] = "LIQUID_GLASS"
                val curCardAlpha = prefs[PrefKeys.WALLPAPER_CARD_ALPHA]
                if (curCardAlpha == null || curCardAlpha > 0.85f) {
                    prefs[PrefKeys.WALLPAPER_CARD_ALPHA] = ThemeVisualDefaults.DEFAULT_WALLPAPER_CARD_ALPHA
                }
                if (prefs[PrefKeys.WALLPAPER_BLUR_DP] == null) {
                    prefs[PrefKeys.WALLPAPER_BLUR_DP] = ThemeVisualDefaults.DEFAULT_WALLPAPER_BLUR_DP
                }
            }
            if (customSeedArgb != null) {
                prefs[PrefKeys.CUSTOM_THEME_SEED_ARGB] = customSeedArgb
            }
        }
        notifyScheduleOrPolicyChanged("THEME_UPDATED")
    }

    suspend fun saveCustomWallpaperTheme(
        localPath: String,
        primarySeedArgb: Long,
        extractedSwatches: List<Long>,
        enableLiquidGlass: Boolean = false
    ) {
        preferences.edit { prefs ->
            prefs[PrefKeys.CUSTOM_WALLPAPER_PATH] = localPath
            prefs[PrefKeys.THEME_PALETTE_KEY] = "CUSTOM"
            prefs[PrefKeys.CUSTOM_THEME_SEED_ARGB] = primarySeedArgb
            prefs[PrefKeys.DYNAMIC_COLOR] = false
            prefs[PrefKeys.EXTRACTED_WALLPAPER_COLORS] = extractedSwatches.joinToString(",")
            if (enableLiquidGlass) {
                prefs[PrefKeys.UI_DESIGN_STYLE] = "LIQUID_GLASS"
            }
        }
        notifyScheduleOrPolicyChanged("THEME_UPDATED")
    }

    suspend fun saveExtractedDesktopSwatches(
        primarySeedArgb: Long,
        extractedSwatches: List<Long>
    ) {
        preferences.edit { prefs ->
            prefs[PrefKeys.THEME_PALETTE_KEY] = "CUSTOM"
            prefs[PrefKeys.CUSTOM_THEME_SEED_ARGB] = primarySeedArgb
            prefs[PrefKeys.DYNAMIC_COLOR] = false
            prefs[PrefKeys.EXTRACTED_WALLPAPER_COLORS] = extractedSwatches.joinToString(",")
        }
        notifyScheduleOrPolicyChanged("THEME_UPDATED")
    }

    suspend fun updateWallpaperVisualParams(
        alpha: Float? = null,
        blurDp: Float? = null,
        cardAlpha: Float? = null
    ) {
        preferences.edit { prefs ->
            alpha?.let { prefs[PrefKeys.WALLPAPER_ALPHA] = ThemeVisualDefaults.clampWallpaperAlpha(it) }
            blurDp?.let { prefs[PrefKeys.WALLPAPER_BLUR_DP] = ThemeVisualDefaults.clampWallpaperBlurDp(it) }
            cardAlpha?.let { prefs[PrefKeys.WALLPAPER_CARD_ALPHA] = ThemeVisualDefaults.clampWallpaperCardAlpha(it) }
        }
    }

    suspend fun resetWallpaperVisualParams() {
        preferences.edit { prefs ->
            prefs[PrefKeys.WALLPAPER_ALPHA] = ThemeVisualDefaults.DEFAULT_WALLPAPER_ALPHA
            prefs[PrefKeys.WALLPAPER_BLUR_DP] = ThemeVisualDefaults.DEFAULT_WALLPAPER_BLUR_DP
            prefs[PrefKeys.WALLPAPER_CARD_ALPHA] = ThemeVisualDefaults.DEFAULT_WALLPAPER_CARD_ALPHA
        }
        notifyScheduleOrPolicyChanged("THEME_UPDATED")
    }

    suspend fun clearCustomWallpaperTheme() {
        preferences.edit { prefs ->
            val oldPath = prefs[PrefKeys.CUSTOM_WALLPAPER_PATH]
            if (!oldPath.isNullOrBlank()) {
                runCatching { java.io.File(oldPath).delete() }
            }
            prefs.remove(PrefKeys.CUSTOM_WALLPAPER_PATH)
            prefs.remove(PrefKeys.EXTRACTED_WALLPAPER_COLORS)
            if (prefs[PrefKeys.THEME_PALETTE_KEY] == "CUSTOM") {
                prefs[PrefKeys.THEME_PALETTE_KEY] =
                    if (prefs[PrefKeys.UI_DESIGN_STYLE] == "LIQUID_GLASS") "LIQUID_GLASS" else "HAUT_BLUE"
            }
        }
        notifyScheduleOrPolicyChanged("THEME_UPDATED")
    }

    suspend fun updateHomeCardLayout(
        order: List<String>,
        hidden: Set<String>,
        fullWidth: Set<String>
    ) {
        val normalizedOrder = HomeCardModule.normalizeOrderIds(order)
        val normalizedHidden = HomeCardModule.normalizeHiddenIds(hidden)
        preferences.edit { prefs ->
            prefs[PrefKeys.HOME_CARD_ORDER] = normalizedOrder.joinToString(",")
            prefs[PrefKeys.HIDDEN_HOME_CARDS] = normalizedHidden.joinToString(",")
            prefs[PrefKeys.FULL_WIDTH_HOME_CARDS] =
                if (fullWidth.isEmpty()) HomeCardModule.EMPTY_SET_SENTINEL else fullWidth.joinToString(",")
        }
    }

    suspend fun resetHomeCardLayout() {
        preferences.edit { prefs ->
            prefs.remove(PrefKeys.HOME_CARD_ORDER)
            prefs.remove(PrefKeys.HIDDEN_HOME_CARDS)
            prefs.remove(PrefKeys.FULL_WIDTH_HOME_CARDS)
        }
    }

    suspend fun getActiveThemeAccentArgb(): Int {
        val prefs = preferences.data.first()
        val key = prefs[PrefKeys.THEME_PALETTE_KEY] ?: "HAUT_BLUE"
        val custom = prefs[PrefKeys.CUSTOM_THEME_SEED_ARGB] ?: 0xFF053968
        val seedLong = when (key) {
            "MINT_BREEZE" -> 0xFF10B981
            "SKY_SODA" -> 0xFF0EA5E9
            "PEACH_OOLONG" -> 0xFFF472B6
            "MATCHA_CREAM" -> 0xFF22C55E
            "LAVENDER_MIST" -> 0xFF8B5CF6
            "POMELO_SUN" -> 0xFFFB7185
            "LIQUID_GLASS" -> 0xFF0EA5E9
            "HAUT_BLUE" -> 0xFF053968
            "PINE_TEAL" -> 0xFF0D9488
            "JADE_GREEN" -> 0xFF16A34A
            "AMBER_GOLD" -> 0xFFD97706
            "CORAL_RED" -> 0xFFE11D48
            "IRIS_PURPLE" -> 0xFF7C3AED
            "SAKURA_PINK" -> 0xFFDB2777
            "SLATE_INK" -> 0xFF334155
            "CUSTOM" -> custom
            else -> 0xFF053968
        }
        return seedLong.toInt()
    }

    suspend fun setAutoCheckUpdateEnabled(enabled: Boolean) {
        preferences.edit { it[PrefKeys.AUTO_CHECK_UPDATE] = enabled }
    }

    suspend fun getNetworkProxyConfigSnapshot(): NetworkProxyConfig {
        val prefs = preferences.data.first()
        return extractNetworkProxyConfig(prefs)
    }

    suspend fun updateNetworkProxyConfig(config: NetworkProxyConfig) {
        preferences.edit { prefs ->
            prefs[PrefKeys.PROXY_MODE] = config.mode.key
            prefs[PrefKeys.PROXY_PRESET_MIRROR_KEY] = config.presetMirrorKey
            prefs[PrefKeys.PROXY_CUSTOM_PREFIX] = config.customReverseProxyPrefix.trim()
            prefs[PrefKeys.PROXY_HOST] = config.proxyHost.trim().ifBlank { "127.0.0.1" }
            prefs[PrefKeys.PROXY_PORT] = config.proxyPort.coerceIn(1, 65535)
            prefs[PrefKeys.PROXY_AUTO_FALLBACK] = config.autoFallbackMirror
            prefs[PrefKeys.CLOUD_API_BASE_URL] = NetworkProxyConfig.normalizeCloudApiBaseUrl(config.customCloudApiBaseUrl)
        }
    }

    suspend fun resetNetworkProxyConfig() {
        preferences.edit { prefs ->
            prefs.remove(PrefKeys.PROXY_MODE)
            prefs.remove(PrefKeys.PROXY_PRESET_MIRROR_KEY)
            prefs.remove(PrefKeys.PROXY_CUSTOM_PREFIX)
            prefs.remove(PrefKeys.PROXY_HOST)
            prefs.remove(PrefKeys.PROXY_PORT)
            prefs.remove(PrefKeys.PROXY_AUTO_FALLBACK)
            prefs.remove(PrefKeys.CLOUD_API_BASE_URL)
        }
    }

    suspend fun saveCustomChannelUrl(channelId: String, inviteUrl: String) {
        preferences.edit { prefs ->
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
        preferences.edit { prefs ->
            val current = decodeKeyValueMap(prefs[PrefKeys.CUSTOM_CLUB_GROUPS]).toMutableMap()
            if (groupNumber.isBlank()) {
                current.remove(clubId)
            } else {
                current[clubId] = groupNumber.trim()
            }
            prefs[PrefKeys.CUSTOM_CLUB_GROUPS] = encodeKeyValueMap(current)
        }
    }

    /**
     * 从云端控制平面 (`GET /api/v1/campus-content?school_id=haut`) 实时拉取最新的校园频道与学生社团名录，
     * 并持久化到本地 DataStore 供全端响应式渲染。
     */
    suspend fun refreshCampusCommunityContent(): Result<cn.edu.haut.campus.core.model.CampusCommunityCloudCatalog> =
        withContext(Dispatchers.IO) {
            runCatching {
                val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                    preferencesFlow.first().networkProxyConfig.customCloudApiBaseUrl
                )
                val (catalog, rawJson) = CampusCloudOidcHelper.fetchPublicCampusContent(
                    schoolId = schoolId,
                    cloudBaseUrl = baseUrl
                ) ?: throw IllegalStateException("无法连接云端获取最新校园频道与社团内容")
                val now = System.currentTimeMillis()
                preferences.edit { prefs ->
                    prefs.remove(PrefKeys.CLOUD_CAMPUS_CONTENT_JSON_LEGACY_V1)
                    prefs[PrefKeys.CLOUD_CAMPUS_CONTENT_JSON] = rawJson
                    prefs[PrefKeys.CLOUD_CAMPUS_CONTENT_SYNCED_MS] = now
                }
                catalog
            }
        }

    private fun decodeKeyValueMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split("\n").mapNotNull { line ->
            val idx = line.indexOf('\t')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()
    }

    private fun decodeCsvSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun encodeCsvSet(set: Set<String>): String =
        set.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

    /**
     * 刷新校园互助广场（失物招领、二手教材、随身防丢码）云端快照并与本地草稿/发布记录合并。
     */
    suspend fun refreshCampusMomentsSnapshot(): Result<cn.edu.haut.campus.core.model.CampusMomentsSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                val currentPrefs = preferencesFlow.first()
                val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                    currentPrefs.networkProxyConfig.customCloudApiBaseUrl
                )
                val token = resolveValidCloudAccessToken(forceRefresh = false)
                val (remoteSnap, _) = CampusCloudOidcHelper.fetchMomentsSnapshot(
                    cloudBaseUrl = baseUrl,
                    bearerToken = token.takeIf { it.isNotBlank() }
                ) ?: (currentPrefs.campusMomentsSnapshot to "")
                val localSnap = currentPrefs.campusMomentsSnapshot
                val hasLiveRemote = token.isNotBlank() && remoteSnap.updatedAt.isNotBlank()
                val mergedPosts = if (hasLiveRemote) {
                    remoteSnap.posts
                } else {
                    remoteSnap.posts + localSnap.posts.filter { lp -> remoteSnap.posts.none { it.id == lp.id } }
                }
                val mergedBooks = (remoteSnap.bookListings + localSnap.bookListings.filter { lb -> remoteSnap.bookListings.none { it.id == lb.id } })
                val mergedTags = (remoteSnap.antiLostTags + localSnap.antiLostTags.filter { lt -> remoteSnap.antiLostTags.none { it.tagCode.equals(lt.tagCode, ignoreCase = true) } })
                val serverOwnerIds = mergedPosts.filter { it.isOwner }.map { it.id }.toSet()
                val effectiveMyPostIds = if (token.isNotBlank() && serverOwnerIds.isNotEmpty()) {
                    serverOwnerIds
                } else {
                    localSnap.myPostIds + serverOwnerIds
                }
                val rawFinalSnap = remoteSnap.copy(
                    posts = mergedPosts,
                    bookListings = mergedBooks,
                    antiLostTags = mergedTags,
                    favoritePostIds = localSnap.favoritePostIds,
                    favoriteBookIds = localSnap.favoriteBookIds,
                    myPostIds = if (token.isNotBlank()) effectiveMyPostIds else emptySet(),
                    myBookIds = localSnap.myBookIds,
                    myTagCodes = localSnap.myTagCodes
                )
                val finalSnap = if (token.isNotBlank()) {
                    rawFinalSnap
                } else {
                    CampusCloudOidcHelper.sanitizeMomentsSnapshotForViewerReset(rawFinalSnap)
                }
                preferences.edit { prefs ->
                    prefs[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                        CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(finalSnap)
                    if (token.isNotBlank()) {
                        prefs[PrefKeys.MOMENTS_MY_POST_IDS] = encodeCsvSet(effectiveMyPostIds)
                    } else {
                        prefs.remove(PrefKeys.MOMENTS_MY_POST_IDS)
                    }
                }
                finalSnap
            }
        }

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
    ): Result<cn.edu.haut.campus.core.model.MomentsPostItem> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanTitle = title.trim().take(64)
            require(cleanTitle.isNotEmpty()) { "请填写物品名称或标题" }
            val cleanContact = contact.trim().take(64)
            require(cleanContact.isNotEmpty()) { "请填写联系方式" }
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("发布寻物或招领信息需先在「我的 → 云端账号」中登录账号，以便后续跨设备结案、删除与审批联系申请")
            }
            val profile = studentProfileFlow.first()
            val prefsState = preferencesFlow.first()
            val authorName = profile?.displayName?.takeIf { it.isNotBlank() }?.let { "${it.first()}同学" } ?: "河工大同学"
            val studentNo = profile?.schoolId ?: prefsState.savedStudentId.orEmpty()
            val maskedNo = if (studentNo.length >= 4) "${studentNo.take(2)}***${studentNo.takeLast(2)}" else ""
            val postId = "mp_${UUID.randomUUID().toString().replace("-", "").take(10)}"
            val normalizedVisibility = if (contactVisibility.equals("ON_REQUEST", ignoreCase = true)) "ON_REQUEST" else "PUBLIC"
            val localItem = cn.edu.haut.campus.core.model.MomentsPostItem(
                id = postId,
                type = type,
                title = cleanTitle,
                category = category.trim().ifBlank { "其他物品" },
                campus = campus.trim().ifBlank { "莲花街校区" },
                location = location.trim().ifBlank { "校内" }.take(64),
                description = description.trim().take(360),
                contact = cleanContact,
                rewardNote = rewardNote.trim().take(64),
                status = "OPEN",
                authorName = authorName,
                authorStudentNoMasked = maskedNo,
                createdAt = "刚刚",
                contactVisibility = normalizedVisibility,
                isOwner = true,
                canManage = true,
                isLegacyUnowned = false,
                viewerRole = "OWNER"
            )
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val cloudSaved = CampusCloudOidcHelper.publishMomentsPostToCloud(
                post = localItem,
                cloudBaseUrl = baseUrl,
                bearerToken = token,
                contactVisibility = normalizedVisibility
            ) ?: throw IllegalStateException("网络未连接或云端认证已失效，失物招领发布未能提交")
            val curSnap = prefsState.campusMomentsSnapshot
            val nextPosts = listOf(cloudSaved) + curSnap.posts.filterNot { it.id == cloudSaved.id }
            val nextMyIds = curSnap.myPostIds + cloudSaved.id
            val nextSnap = curSnap.copy(posts = nextPosts, myPostIds = nextMyIds)
            preferences.edit { prefs ->
                prefs[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
                prefs[PrefKeys.MOMENTS_MY_POST_IDS] = encodeCsvSet(nextMyIds)
            }
            cloudSaved
        }
    }

    suspend fun addMomentsComment(
        postId: String,
        content: String,
        contactHint: String = ""
    ): Result<cn.edu.haut.campus.core.model.MomentsPostItem> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanContent = content.trim().take(240)
            require(cleanContent.isNotEmpty()) { "请输入线索或留言内容" }
            val profile = studentProfileFlow.first()
            val authorName = profile?.displayName?.takeIf { it.isNotBlank() }?.let { "${it.first()}同学" } ?: "热心同学"
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            val cloudUpdated = CampusCloudOidcHelper.addMomentsCommentToCloud(
                postId = postId,
                authorName = authorName,
                content = cleanContent,
                contactHint = contactHint.trim().take(48),
                cloudBaseUrl = baseUrl,
                bearerToken = token.takeIf { it.isNotBlank() }
            ) ?: throw IllegalStateException("网络未连接或服务端拒绝（如已被发起人屏蔽），留言未能提交，请联网重试")
            val curSnap = prefsState.campusMomentsSnapshot
            val nextPosts = curSnap.posts.map { if (it.id == postId) cloudUpdated else it }
            val nextSnap = curSnap.copy(posts = nextPosts)
            preferences.edit { prefs ->
                prefs[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
            }
            cloudUpdated
        }
    }

    suspend fun submitMomentsContactRequest(
        postId: String,
        message: String,
        contactInfo: String
    ): Result<CampusCloudOidcHelper.MomentsContactRequestDto> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanMsg = message.trim().take(240)
            require(cleanMsg.isNotEmpty()) { "请填写认领说明或物品特征" }
            val cleanContact = contactInfo.trim().take(64)
            require(cleanContact.isNotEmpty()) { "请填写您的联系方式（仅发起人可见）" }
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("发送私密联系申请需先在「我的 → 云端账号」中登录账号；未登录时仅可查看公开联系方式或发表公开线索")
            }
            val profile = studentProfileFlow.first()
            val senderName = profile?.displayName?.takeIf { it.isNotBlank() }?.let { "${it.first()}同学" } ?: "河工大同学"
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val idempotencyKey = "mcr_${postId}_${cleanMsg.hashCode()}_${cleanContact.hashCode()}"
            CampusCloudOidcHelper.submitMomentsContactRequestToCloud(
                postId = postId,
                senderName = senderName,
                message = cleanMsg,
                contactInfo = cleanContact,
                idempotencyKey = idempotencyKey,
                bearerToken = token,
                cloudBaseUrl = baseUrl
            ) ?: throw IllegalStateException("联系申请提交失败：可能网络未连接、该帖已结案或发起人已屏蔽申请")
        }
    }

    suspend fun fetchMomentsContactRequestsResult(
        postId: String
    ): CampusCloudOidcHelper.MomentsContactRequestsListResult = withContext(Dispatchers.IO) {
        val token = resolveValidCloudAccessToken(forceRefresh = false)
        if (token.isBlank()) return@withContext CampusCloudOidcHelper.MomentsContactRequestsListResult()
        val prefsState = preferencesFlow.first()
        val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
            prefsState.networkProxyConfig.customCloudApiBaseUrl
        )
        CampusCloudOidcHelper.fetchMomentsContactRequestsResultFromCloud(
            postId = postId,
            bearerToken = token,
            cloudBaseUrl = baseUrl
        )
    }

    suspend fun fetchMomentsContactRequests(
        postId: String
    ): List<CampusCloudOidcHelper.MomentsContactRequestDto> =
        fetchMomentsContactRequestsResult(postId).requests

    suspend fun claimLegacyMomentsPostOwnership(
        postId: String,
        claimToken: String
    ): Result<cn.edu.haut.campus.core.model.MomentsPostItem> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanClaimToken = claimToken.trim()
            require(cleanClaimToken.length >= 32) { "仅支持使用发帖时签发的原始高熵所有权凭证（32位以上）认领；无凭证历史旧帖请申请管理员人工核验" }
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("认领历史旧帖管理权需先登录云端账号")
            }
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val claimedPost = CampusCloudOidcHelper.claimLegacyMomentsPostOnCloud(
                postId = postId,
                claimToken = cleanClaimToken,
                bearerToken = token,
                cloudBaseUrl = baseUrl
            ) ?: throw IllegalStateException("高熵所有权凭证核验未通过；为防冒领已关闭联系方式/脱敏学号自助认领，请提交管理员人工核验")
            val curSnap = prefsState.campusMomentsSnapshot
            val nextPosts = curSnap.posts.map { if (it.id == postId) claimedPost else it }
            val nextMyIds = curSnap.myPostIds + claimedPost.id
            val nextSnap = curSnap.copy(posts = nextPosts, myPostIds = nextMyIds)
            preferences.edit { prefs ->
                prefs[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
                prefs[PrefKeys.MOMENTS_MY_POST_IDS] = encodeCsvSet(nextMyIds)
            }
            claimedPost
        }
    }

    suspend fun handleMomentsContactRequestAction(
        postId: String,
        requestId: String,
        action: String,
        replyMessage: String? = null,
        reason: String? = null
    ): Result<CampusCloudOidcHelper.MomentsContactRequestDto> = withContext(Dispatchers.IO) {
        runCatching {
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            require(token.isNotBlank()) { "请先登录云端账号后再处理联系申请" }
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            CampusCloudOidcHelper.handleMomentsContactRequestActionOnCloud(
                postId = postId,
                requestId = requestId,
                action = action,
                replyMessage = replyMessage,
                reason = reason,
                bearerToken = token,
                cloudBaseUrl = baseUrl
            ) ?: throw IllegalStateException("处理联系申请失败，请检查网络连接或权限")
        }
    }

    suspend fun reportMomentsPost(
        postId: String,
        reason: String = "用户举报不良或虚假失物招领信息"
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            val ok = CampusCloudOidcHelper.mutateMomentsPostOnCloud(
                postId = postId,
                action = "REPORT",
                cloudBaseUrl = baseUrl,
                bearerToken = token.takeIf { it.isNotBlank() },
                reason = reason
            )
            if (!ok) {
                throw IllegalStateException("举报未能提交至云端，请检查网络连接后重试")
            }
            true
        }
    }

    suspend fun toggleMomentsPostLike(postId: String) {
        val prefsState = preferencesFlow.first()
        val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
            prefsState.networkProxyConfig.customCloudApiBaseUrl
        )
        val token = resolveValidCloudAccessToken(forceRefresh = false)
        val ok = withContext(Dispatchers.IO) {
            CampusCloudOidcHelper.mutateMomentsPostOnCloud(
                postId = postId,
                action = "LIKE",
                cloudBaseUrl = baseUrl,
                bearerToken = token.takeIf { it.isNotBlank() }
            )
        }
        if (ok) {
            val curSnap = prefsState.campusMomentsSnapshot
            val nextPosts = curSnap.posts.map {
                if (it.id == postId) it.copy(likesCount = it.likesCount + 1) else it
            }
            val nextSnap = curSnap.copy(posts = nextPosts)
            preferences.edit { prefs ->
                prefs[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
            }
        }
    }

    suspend fun toggleMomentsPostFavorite(postId: String) {
        val prefsState = preferencesFlow.first()
        val curFavs = prefsState.campusMomentsSnapshot.favoritePostIds
        val nextFavs = if (postId in curFavs) curFavs - postId else curFavs + postId
        preferences.edit { prefs ->
            prefs[PrefKeys.MOMENTS_FAVORITE_POST_IDS] = encodeCsvSet(nextFavs)
        }
    }

    suspend fun toggleMomentsPostResolved(postId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val prefsState = preferencesFlow.first()
            val curSnap = prefsState.campusMomentsSnapshot
            val target = curSnap.posts.firstOrNull { it.id == postId }
                ?: throw IllegalArgumentException("未找到该帖子")
            val nextStatus = if (target.isResolved) "OPEN" else "RESOLVED"
            val nextAction = if (nextStatus == "RESOLVED") "RESOLVE" else "REOPEN"
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("结案或重开帖子需先登录云端账号验证发起人身份")
            }
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val ok = CampusCloudOidcHelper.mutateMomentsPostOnCloud(
                postId = postId,
                action = nextAction,
                cloudBaseUrl = baseUrl,
                bearerToken = token
            )
            if (!ok) {
                throw IllegalStateException("仅帖子发起人本人可在联网状态下结案或重开")
            }
            val nextPosts = curSnap.posts.map {
                if (it.id == postId) it.copy(status = nextStatus) else it
            }
            val nextSnap = curSnap.copy(posts = nextPosts)
            preferences.edit { prefs ->
                prefs[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
            }
            true
        }
    }

    suspend fun deleteMomentsPost(postId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val prefsState = preferencesFlow.first()
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("删除帖子需先登录云端账号验证发起人身份")
            }
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val ok = CampusCloudOidcHelper.mutateMomentsPostOnCloud(
                postId = postId,
                action = "DELETE",
                cloudBaseUrl = baseUrl,
                bearerToken = token
            )
            if (!ok) {
                throw IllegalStateException("删除失败：仅帖子发起人本人可在联网状态下删除")
            }
            val curSnap = prefsState.campusMomentsSnapshot
            val nextPosts = curSnap.posts.filterNot { it.id == postId }
            val nextMyIds = curSnap.myPostIds - postId
            val nextSnap = curSnap.copy(posts = nextPosts, myPostIds = nextMyIds)
            preferences.edit { prefs ->
                prefs[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
                prefs[PrefKeys.MOMENTS_MY_POST_IDS] = encodeCsvSet(nextMyIds)
            }
            true
        }
    }

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
    ): Result<cn.edu.haut.campus.core.model.MomentsTextbookListing> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanTitle = title.trim().take(80)
            require(cleanTitle.isNotEmpty()) { "请填写教材名称" }
            val cleanContact = contact.trim().take(64)
            require(cleanContact.isNotEmpty()) { "请填写卖家联系方式" }
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("发布闲置教材需先在「我的 → 云端账号」登录账号，以便后续下架或删除管理")
            }
            val profile = studentProfileFlow.first()
            val sellerName = profile?.displayName?.takeIf { it.isNotBlank() }?.let { "${it.first()}同学" } ?: "河工大同学"
            val bookId = "mb_${UUID.randomUUID().toString().replace("-", "").take(10)}"
            val localBook = cn.edu.haut.campus.core.model.MomentsTextbookListing(
                id = bookId,
                title = cleanTitle,
                isbn = isbn.trim().take(24),
                authorPublisher = authorPublisher.trim().ifBlank { "高等教育出版社" }.take(64),
                courseName = courseName.trim().take(40),
                majorTrack = majorTrack,
                campus = campus.trim().ifBlank { "莲花街校区" },
                conditionLabel = conditionLabel.trim().ifBlank { "九成新" },
                priceYuan = priceYuan.coerceIn(1, 500),
                originalPriceYuan = originalPriceYuan.coerceIn(priceYuan, 999),
                deliveryMode = deliveryMode.trim().ifBlank { "莲花街自提" },
                contact = cleanContact,
                sellerName = sellerName,
                status = "AVAILABLE",
                createdAt = "刚刚",
                isOwner = true,
                canManage = true
            )
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val cloudSaved = CampusCloudOidcHelper.publishMomentsBookToCloud(localBook, baseUrl, token)
                ?: throw IllegalStateException("网络未连接或云端认证已失效，闲置教材发布未能提交")
            val curSnap = prefsState.campusMomentsSnapshot
            val nextBooks = listOf(cloudSaved) + curSnap.bookListings.filterNot { it.id == cloudSaved.id }
            val nextMyBookIds = curSnap.myBookIds + cloudSaved.id
            val nextSnap = curSnap.copy(bookListings = nextBooks, myBookIds = nextMyBookIds)
            preferences.edit { prefs ->
                prefs[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
                prefs[PrefKeys.MOMENTS_MY_BOOK_IDS] = encodeCsvSet(nextMyBookIds)
            }
            cloudSaved
        }
    }

    suspend fun toggleMomentsBookFavorite(bookId: String) {
        val prefsState = preferencesFlow.first()
        val curFavs = prefsState.campusMomentsSnapshot.favoriteBookIds
        val nextFavs = if (bookId in curFavs) curFavs - bookId else curFavs + bookId
        preferences.edit { prefs ->
            prefs[PrefKeys.MOMENTS_FAVORITE_BOOK_IDS] = encodeCsvSet(nextFavs)
        }
    }

    suspend fun toggleMomentsBookSold(bookId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) throw IllegalStateException("修改教材状态需先登录云端账号")
            val prefsState = preferencesFlow.first()
            val curSnap = prefsState.campusMomentsSnapshot
            val target = curSnap.bookListings.firstOrNull { it.id == bookId }
                ?: throw IllegalArgumentException("未找到该教材挂单")
            val nextStatus = if (target.isSold) "AVAILABLE" else "SOLD"
            val nextAction = if (nextStatus == "SOLD") "MARK_SOLD" else "MARK_AVAILABLE"
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val ok = CampusCloudOidcHelper.mutateMomentsBookOnCloud(bookId, nextAction, baseUrl, token)
            if (!ok) throw IllegalStateException("更新失败：请检查网络连接或确认您是该教材发布人")
            val nextBooks = curSnap.bookListings.map {
                if (it.id == bookId) it.copy(status = nextStatus) else it
            }
            val nextSnap = curSnap.copy(bookListings = nextBooks)
            preferences.edit { prefs ->
                prefs[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
            }
            true
        }
    }

    suspend fun deleteMomentsBookListing(bookId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) throw IllegalStateException("删除教材挂单需先登录云端账号")
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val ok = CampusCloudOidcHelper.mutateMomentsBookOnCloud(bookId, "DELETE", baseUrl, token)
            if (!ok) throw IllegalStateException("删除失败：请检查网络连接或确认您是该教材发布人")
            val curSnap = prefsState.campusMomentsSnapshot
            val nextBooks = curSnap.bookListings.filterNot { it.id == bookId }
            val nextMyBookIds = curSnap.myBookIds - bookId
            val nextSnap = curSnap.copy(bookListings = nextBooks, myBookIds = nextMyBookIds)
            preferences.edit { prefs ->
                prefs[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
                prefs[PrefKeys.MOMENTS_MY_BOOK_IDS] = encodeCsvSet(nextMyBookIds)
            }
            true
        }
    }

    suspend fun upsertAntiLostTag(
        tagCode: String = "",
        itemName: String,
        category: String,
        campus: String,
        ownerContact: String,
        returnNote: String,
        status: String = "NORMAL"
    ): Result<cn.edu.haut.campus.core.model.AntiLostTagItem> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = itemName.trim().take(48)
            require(cleanName.isNotEmpty()) { "请填写随身物品名称" }
            val cleanContact = ownerContact.trim().take(64)
            require(cleanContact.isNotEmpty()) { "请填写失主联系方式" }
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("生成或更新随身防丢码需先在「我的 → 云端账号」登录账号，以绑定归属并接收拾得通知")
            }
            val profile = studentProfileFlow.first()
            val ownerName = profile?.displayName?.takeIf { it.isNotBlank() }?.let { "${it.first()}同学" } ?: "河工大同学"
            val effectiveCode = tagCode.trim().uppercase().takeIf {
                it.matches(Regex("^HAUT-TAG-[A-Z0-9]{4,10}$"))
            } ?: "HAUT-TAG-${UUID.randomUUID().toString().replace("-", "").take(6).uppercase()}"
            val prefsState = preferencesFlow.first()
            val curSnap = prefsState.campusMomentsSnapshot
            val existing = curSnap.antiLostTags.firstOrNull { it.tagCode.equals(effectiveCode, ignoreCase = true) }
            val localTag = cn.edu.haut.campus.core.model.AntiLostTagItem(
                id = existing?.id ?: "tag_${effectiveCode.takeLast(6)}",
                tagCode = effectiveCode,
                itemName = cleanName,
                category = category.trim().ifBlank { "水杯雨伞" },
                campus = campus.trim().ifBlank { "莲花街校区" },
                ownerName = ownerName,
                ownerContact = cleanContact,
                returnNote = returnNote.trim().ifBlank { "拾到请联系失主或放至就近服务台，感谢相助" }.take(160),
                status = status.uppercase(),
                reports = existing?.reports.orEmpty(),
                createdAt = existing?.createdAt ?: "今天",
                isOwner = true,
                canManage = true
            )
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val cloudSaved = CampusCloudOidcHelper.upsertAntiLostTagOnCloud(localTag, baseUrl, token)
                ?: throw IllegalStateException("网络未连接或该防丢码编号已被占用，未能同步注册至云端")
            val nextTags = listOf(cloudSaved) + curSnap.antiLostTags.filterNot {
                it.tagCode.equals(cloudSaved.tagCode, ignoreCase = true)
            }
            val nextMyCodes = curSnap.myTagCodes + cloudSaved.tagCode
            val nextSnap = curSnap.copy(antiLostTags = nextTags, myTagCodes = nextMyCodes)
            preferences.edit { prefs ->
                prefs[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                    CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(nextSnap)
                prefs[PrefKeys.MOMENTS_MY_TAG_CODES] = encodeCsvSet(nextMyCodes)
            }
            cloudSaved
        }
    }

    suspend fun reportAntiLostTagFound(
        tagCode: String,
        finderName: String,
        foundLocation: String,
        finderContact: String,
        message: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanCode = tagCode.trim().uppercase()
            require(cleanCode.isNotEmpty()) { "请输入防丢码编号（如 HAUT-TAG-7E2A9B）" }
            val cleanLoc = foundLocation.trim().take(80)
            require(cleanLoc.isNotEmpty()) { "请填写拾得或放置地点" }
            val prefsState = preferencesFlow.first()
            val baseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsState.networkProxyConfig.customCloudApiBaseUrl
            )
            val submitted = CampusCloudOidcHelper.reportAntiLostTagFoundOnCloud(
                tagCode = cleanCode,
                finderName = finderName.trim().ifBlank { "热心同学" },
                foundLocation = cleanLoc,
                finderContact = finderContact.trim().take(64),
                message = message.trim().take(200),
                cloudBaseUrl = baseUrl
            )
            if (!submitted) {
                throw IllegalStateException("拾得线索未能提交：请检查网络连接或确认防丢码编号已在云端有效登记")
            }
            true
        }
    }

    private fun encodeKeyValueMap(map: Map<String, String>): String {
        return map.entries.joinToString("\n") { (k, v) ->
            "${k.replace("\t", " ").replace("\n", " ")}\t${v.replace("\n", "").trim()}"
        }
    }

    suspend fun setTimelineZoomScale(scale: Float) {
        preferences.edit { it[PrefKeys.TIMELINE_SCALE] = ThemeVisualDefaults.clampTimelineZoomScale(scale) }
    }

    suspend fun saveWidgetConfig(config: WidgetInstanceConfig) {
        dao.upsertWidgetConfig(config.toEntity())
    }

    suspend fun getWidgetConfigOrDefault(appWidgetId: Int, defaultType: cn.edu.haut.campus.core.model.WidgetType): WidgetInstanceConfig {
        dao.getWidgetConfig(appWidgetId)?.toDomain()?.let { return it }
        val prefs = preferences.data.first()
        return WidgetInstanceConfig(
            appWidgetId = appWidgetId,
            widgetType = defaultType,
            compact = prefs[PrefKeys.WIDGET_DEFAULT_COMPACT] ?: false,
            privacyMode = prefs[PrefKeys.WIDGET_DEFAULT_PRIVACY_MODE] ?: false
        )
    }

    suspend fun updateAllWidgetDefaults(
        compact: Boolean? = null,
        privacyMode: Boolean? = null
    ) {
        preferences.edit { prefs ->
            compact?.let { prefs[PrefKeys.WIDGET_DEFAULT_COMPACT] = it }
            privacyMode?.let { prefs[PrefKeys.WIDGET_DEFAULT_PRIVACY_MODE] = it }
        }
        when {
            compact != null && privacyMode != null -> dao.updateAllWidgetConfigsDefaults(compact, privacyMode)
            compact != null -> dao.updateAllWidgetConfigsCompact(compact)
            privacyMode != null -> dao.updateAllWidgetConfigsPrivacyMode(privacyMode)
        }
        notifyScheduleOrPolicyChanged("WIDGET_CONFIG_UPDATED")
    }

    suspend fun getReminderConfigSnapshot(): ReminderGlobalConfig {
        val prefs = preferences.data.first()
        return extractReminderConfig(prefs)
    }

    suspend fun getExamSchedulesSnapshot(): List<ExamScheduleItem> {
        return dao.getExamSchedulesSnapshot().map { it.toDomain() }
    }

    suspend fun getReminderEventOverridesSnapshot(): List<ReminderEventOverride> {
        return reminderDao.getEventPrefsSnapshot().map { it.toDomain() }
    }

    suspend fun updateReminderGlobalConfig(transform: (ReminderGlobalConfig) -> ReminderGlobalConfig) {
        preferences.edit { prefs ->
            val current = extractReminderConfig(prefs)
            val updated = transform(current)
            prefs[PrefKeys.REMINDERS_ENABLED] = updated.enabled
            prefs[PrefKeys.CLASS_LEAD_MINUTES] = updated.classLeadMinutes.coerceIn(5, 120)
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
        preferences.edit { prefs ->
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
        preferences.edit { prefs ->
            utilitiesEnabled?.let { prefs[PrefKeys.FF_UTILITIES_ENABLED] = it }
            libraryNativeEnabled?.let { prefs[PrefKeys.FF_LIBRARY_NATIVE_ENABLED] = it }
            librarySeatEnabled?.let { prefs[PrefKeys.FF_LIBRARY_SEAT_ENABLED] = it }
            focusEnabled?.let { prefs[PrefKeys.FF_FOCUS_ENABLED] = it }
            showTodayFocusQuickPill?.let { prefs[PrefKeys.FF_TODAY_FOCUS_PILL] = it }
            timetableInterchangeEnabled?.let { prefs[PrefKeys.FF_TIMETABLE_INTERCHANGE] = it }
        }
    }

    suspend fun setAllowUnverifiedUtilityTransport(allowed: Boolean) {
        preferences.edit { prefs ->
            prefs[PrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT] = allowed
        }
    }

    suspend fun setMealRecommendEnabled(enabled: Boolean) {
        preferences.edit { prefs ->
            prefs[PrefKeys.MEAL_RECOMMEND_ENABLED] = enabled
        }
        notifyScheduleOrPolicyChanged("MEAL_RECOMMEND_CONFIG_CHANGED")
    }

    suspend fun isMealRecommendEnabledSnapshot(): Boolean {
        val prefs = preferences.data.first()
        return supportsHautServices && (prefs[PrefKeys.MEAL_RECOMMEND_ENABLED] ?: true)
    }

    suspend fun getLastMealRecommendBlockKeySnapshot(): String? =
        preferences.data.first()[PrefKeys.LAST_MEAL_RECOMMEND_BLOCK_KEY]

    suspend fun markMealRecommendNotified(blockKey: String) {
        preferences.edit { prefs ->
            prefs[PrefKeys.LAST_MEAL_RECOMMEND_BLOCK_KEY] = blockKey
        }
    }

    suspend fun getCanteenDishReviewsSnapshot(): List<cn.edu.haut.campus.core.domain.CanteenDishReview> {
        val raw = preferences.data.first()[PrefKeys.CANTEEN_DISH_REVIEWS]
        return cn.edu.haut.campus.core.domain.CanteenDishReviewCodec.decodeList(raw)
    }

    suspend fun saveCanteenDishReview(
        canteenBuildingId: String,
        dishName: String,
        stallLocation: String = "",
        priceYuan: Int? = null,
        stars: Int,
        tags: List<String> = emptyList(),
        comment: String = ""
    ) {
        val cleanCanteen = canteenBuildingId.trim().ifBlank { "b_canteen1" }
        val cleanDish = dishName.trim().take(36)
        require(cleanDish.isNotEmpty()) { "请输入菜品或档口名称" }
        val safeStars = stars.coerceIn(1, 5)
        val nowMs = System.currentTimeMillis()
        preferences.edit { prefs ->
            val existing = cn.edu.haut.campus.core.domain.CanteenDishReviewCodec.decodeList(
                prefs[PrefKeys.CANTEEN_DISH_REVIEWS]
            )
            val filtered = existing.filterNot {
                it.canteenBuildingId == cleanCanteen && it.dishName.equals(cleanDish, ignoreCase = true)
            }
            val newReview = cn.edu.haut.campus.core.domain.CanteenDishReview(
                id = "$cleanCanteen:$cleanDish",
                canteenBuildingId = cleanCanteen,
                dishName = cleanDish,
                stallLocation = stallLocation.trim().take(24),
                priceYuan = priceYuan?.coerceIn(1, 200),
                stars = safeStars,
                tags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(4),
                comment = comment.trim().take(80),
                updatedAtEpochMs = nowMs
            )
            val updated = listOf(newReview) + filtered
            prefs[PrefKeys.CANTEEN_DISH_REVIEWS] =
                cn.edu.haut.campus.core.domain.CanteenDishReviewCodec.encodeList(updated.take(120))
        }
    }

    suspend fun deleteCanteenDishReview(canteenBuildingId: String, dishName: String) {
        preferences.edit { prefs ->
            val existing = cn.edu.haut.campus.core.domain.CanteenDishReviewCodec.decodeList(
                prefs[PrefKeys.CANTEEN_DISH_REVIEWS]
            )
            val updated = existing.filterNot {
                it.canteenBuildingId == canteenBuildingId && it.dishName.equals(dishName.trim(), ignoreCase = true)
            }
            prefs[PrefKeys.CANTEEN_DISH_REVIEWS] =
                cn.edu.haut.campus.core.domain.CanteenDishReviewCodec.encodeList(updated)
        }
    }

    suspend fun setSelectedFocusMinutes(minutes: Int) {
        preferences.edit { prefs ->
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
            preferences.edit { prefs ->
                prefs[PrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT] = true
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
        val effectiveAllowOnline = allowUnverified || !rawAccount.isNullOrBlank() ||
            (preferences.data.first()[PrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT] ?: false)
        val snapshots = wanxiaoUtilityClient.queryOwnBoundRooms(
            account = accountToUse,
            allowUnverifiedTransport = effectiveAllowOnline
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
            preferences.edit { prefs ->
                prefs[PrefKeys.LAST_UTILITY_SAMPLE_MS] = finishedAtMs
            }
        }
        runCatching { notifyDailyUtilitySummaryIfEligible(finishedAtMs) }
        snapshots
    }

    /**
     * 供后台周期 Worker 或前台恢复时调用的按需水电采样：
     * 仅在已开启水电功能、已开启在线传输许可、存在有效加密账号且满足采样间隔时触发。
     */
    suspend fun performPeriodicUtilitySampleIfEligible(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = preferences.data.first()
        val utilitiesEnabled = supportsHautServices && (prefs[PrefKeys.FF_UTILITIES_ENABLED] ?: true)
        val allowUnverified = prefs[PrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT] ?: false
        if (!utilitiesEnabled || !allowUnverified) {
            runCatching { notifyDailyUtilitySummaryIfEligible(nowEpochMs) }
            return false
        }

        val lastSampleMs = prefs[PrefKeys.LAST_UTILITY_SAMPLE_MS] ?: 0L
        if (!UtilitySamplingPolicy.shouldSample(lastSampleMs, nowEpochMs)) {
            runCatching { notifyDailyUtilitySummaryIfEligible(nowEpochMs) }
            return false
        }

        val activeAccount = (utilityAccountStore.readAccountResult() as? UtilityAccountReadResult.Active)
            ?.plainAccount ?: run {
                runCatching { notifyDailyUtilitySummaryIfEligible(nowEpochMs) }
                return false
            }

        val sampled = saveAndRefreshOnlineUtilities(
            rawAccount = activeAccount,
            allowUnverified = true
        ).isSuccess
        runCatching { notifyDailyUtilitySummaryIfEligible(nowEpochMs) }
        return sampled
    }

    suspend fun updateUtilityNotificationConfig(
        enabled: Boolean? = null,
        dailyHour: Int? = null,
        lowBalanceThreshold: Float? = null
    ) {
        preferences.edit { prefs ->
            enabled?.let { prefs[PrefKeys.UTILITY_DAILY_NOTIFY_ENABLED] = it }
            dailyHour?.let { prefs[PrefKeys.UTILITY_DAILY_NOTIFY_HOUR] = it.coerceIn(8, 23) }
            lowBalanceThreshold?.let { prefs[PrefKeys.UTILITY_LOW_BALANCE_THRESHOLD] = it.coerceIn(5f, 100f) }
        }
    }

    suspend fun notifyDailyUtilitySummaryIfEligible(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = preferences.data.first()
        val utilitiesEnabled = supportsHautServices && (prefs[PrefKeys.FF_UTILITIES_ENABLED] ?: true)
        if (!utilitiesEnabled) return false

        val notifyEnabled = prefs[PrefKeys.UTILITY_DAILY_NOTIFY_ENABLED] ?: true
        if (!notifyEnabled) return false

        val zdt = Instant.ofEpochMilli(nowEpochMs).atZone(HAUT_ZONE_ID)
        val todayDateText = zdt.toLocalDate().toString()
        val currentHour = zdt.hour
        val targetHour = (prefs[PrefKeys.UTILITY_DAILY_NOTIFY_HOUR] ?: 20).coerceIn(8, 23)
        val lowThreshold = (prefs[PrefKeys.UTILITY_LOW_BALANCE_THRESHOLD] ?: 15f).toDouble()
        val lastNotifiedDate = prefs[PrefKeys.LAST_UTILITY_DAILY_NOTIFY_DATE]

        val payload = DormElectricityNotificationSubsystem.buildNotificationPayload(
            onlineRooms = utilityOnlineRoomsFlow.first(),
            dailySamples = utilityDailySamplesFlow.first(),
            manualMeters = utilityMetersFlow.first(),
            manualReadings = utilityReadingsFlow.first(),
            todayDateText = todayDateText,
            lowThreshold = lowThreshold
        ) ?: return false

        val shouldSend = DormElectricityNotificationSubsystem.shouldTriggerAutoNotify(
            enabled = notifyEnabled,
            lastNotifiedDate = lastNotifiedDate,
            todayDateText = todayDateText,
            currentHourInShanghai = currentHour,
            targetHour = targetHour,
            isLowBalance = payload.isLowBalance
        )
        if (!shouldSend) return false

        val posted = DormElectricityNotificationSubsystem.postNotification(
            context = appContext,
            notificationId = DormElectricityNotificationSubsystem.NOTIFICATION_ID_DAILY,
            title = payload.title,
            body = payload.body,
            profileId = profileId,
            generation = activeSessionGeneration
        )
        if (posted) {
            preferences.edit { editPrefs ->
                editPrefs[PrefKeys.LAST_UTILITY_DAILY_NOTIFY_DATE] = todayDateText
            }
        }
        return posted
    }

    suspend fun sendTestUtilityNotification(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = preferences.data.first()
        val todayDateText = Instant.ofEpochMilli(nowEpochMs).atZone(HAUT_ZONE_ID).toLocalDate().toString()
        val lowThreshold = (prefs[PrefKeys.UTILITY_LOW_BALANCE_THRESHOLD] ?: 15f).toDouble()
        val payload = DormElectricityNotificationSubsystem.buildNotificationPayload(
            onlineRooms = utilityOnlineRoomsFlow.first(),
            dailySamples = utilityDailySamplesFlow.first(),
            manualMeters = utilityMetersFlow.first(),
            manualReadings = utilityReadingsFlow.first(),
            todayDateText = todayDateText,
            lowThreshold = lowThreshold
        ) ?: DormElectricityNotificationPayload(
            title = "今日宿舍用电 · 通知预览",
            body = "今日用电 3.20度 · 剩余电量 28.50度",
            isLowBalance = false,
            primaryRoomName = "宿舍"
        )
        return DormElectricityNotificationSubsystem.postNotification(
            context = appContext,
            notificationId = DormElectricityNotificationSubsystem.NOTIFICATION_ID_TEST,
            title = payload.title,
            body = payload.body,
            profileId = profileId,
            generation = activeSessionGeneration
        )
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
        preferences.edit { prefs ->
            prefs.remove(PrefKeys.LAST_UTILITY_SAMPLE_MS)
            prefs.remove(PrefKeys.LAST_UTILITY_DAILY_NOTIFY_DATE)
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
        runCatching { notifyDailyUtilitySummaryIfEligible(entity.recordedAtEpochMs) }
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
        academicCredentialMutex.withLock {
            val prefs = preferences.data.first()
            prefs[PrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID]?.let { accountId ->
                withContext(Dispatchers.IO) { academicCredentialVault.delete(academicCredentialScope(accountId)) }
            }
            preferences.edit {
                it.remove(PrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID)
                it.remove(PrefKeys.ACADEMIC_REAUTH_REQUIRED)
                it.remove(PrefKeys.LEGACY_SAVED_STUDENT_ID)
                it.remove(PrefKeys.LEGACY_SAVED_PASSWORD_BASE64)
                it[PrefKeys.AUTO_SYNC_ENABLED] = false
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
        database.withTransaction {
            dao.clearStudentProfile()
            dao.clearCourses()
            dao.clearRules()
            dao.clearOccurrenceOverrides()
            dao.clearGradeAttempts()
            dao.clearExamSchedules()
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
        cloudTokenMutex.withLock {
            cachedCloudAccessToken = ""
            cachedCloudRefreshToken = ""
            lastCloudTokenRefreshAtMs = 0L
            preferences.edit { prefs ->
                prefs.remove(PrefKeys.OFFICIAL_GPA)
                prefs.remove(PrefKeys.OFFICIAL_GPA_NOTE)
                prefs.remove(PrefKeys.LEGACY_SAVED_STUDENT_ID)
                prefs.remove(PrefKeys.LEGACY_SAVED_PASSWORD_BASE64)
                prefs.remove(PrefKeys.SAVED_SYNC_XNM)
                prefs.remove(PrefKeys.SAVED_SYNC_XQM)
                prefs.remove(PrefKeys.USER_MANUAL_TERM_SEASON)
                prefs.remove(PrefKeys.LAST_SEASONAL_ROLLOVER_TERM_ID)
                prefs.remove(PrefKeys.LAST_AUTO_SYNC_MS)
                prefs.remove(PrefKeys.LAST_REMINDER_RECONCILE_MS)
                prefs.remove(PrefKeys.LAST_REMINDER_RECONCILE_SUMMARY)
                prefs.remove(PrefKeys.REMINDER_DIAGNOSTIC_LOGS)
                prefs.remove(PrefKeys.LAST_UTILITY_SAMPLE_MS)
                prefs.remove(PrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT)
                prefs.remove(PrefKeys.CLOUD_ACCESS_TOKEN)
                prefs.remove(PrefKeys.CLOUD_REFRESH_TOKEN)
                prefs.remove(PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON)
                prefs.remove(PrefKeys.MOMENTS_MY_POST_IDS)
                prefs.remove(PrefKeys.MOMENTS_MY_BOOK_IDS)
                prefs.remove(PrefKeys.MOMENTS_MY_TAG_CODES)
                prefs.remove(PrefKeys.MOMENTS_FAVORITE_POST_IDS)
                prefs.remove(PrefKeys.MOMENTS_FAVORITE_BOOK_IDS)
                prefs[PrefKeys.SEEDED] = true
                prefs[PrefKeys.CLEAN_BASE_V2] = true
                prefs[PrefKeys.ONBOARDING_COMPLETED] = false
            }
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
        val buildings = (dao.getBuildingsSnapshot().map { it.toDomain() } +
            (if (supportsHautServices) ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS else emptyList()))
            .distinctBy { it.id }
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

    suspend fun setSelectedCampusId(campusId: String) {
        preferences.edit { prefs ->
            prefs[PrefKeys.SELECTED_CAMPUS_ID] = campusId.trim().ifBlank { "auto" }
        }
    }

    suspend fun saveCustomCampusZonesJson(json: String) {
        preferences.edit { prefs ->
            prefs[PrefKeys.CUSTOM_CAMPUS_ZONES_JSON] = json.trim().ifBlank { "[]" }
        }
    }

    // --- XPEACH SSO Cloud Account & Wallet Binding (Optional; Zero-SSO Local Mode Preserved) ---
    val cloudIdentityBindingFlow: Flow<CloudIdentityBindingEntity?> =
        cloudAndParcelDao.observeCloudIdentityBinding()

    @Volatile
    private var cachedCloudAccessToken: String = ""

    @Volatile
    private var cachedCloudRefreshToken: String = ""

    @Volatile
    private var lastCloudTokenRefreshAtMs: Long = 0L

    private val cloudTokenMutex = Mutex()
    private val cloudSyncMutex = Mutex()
    private val bookingPlanStateMutex = Mutex()
    private val activeInProcessFireAttempts = ConcurrentHashMap<String, String>()
    private val activeDispatchedFireAttempts = ConcurrentHashMap<String, Boolean>()

    @Volatile
    internal var cloudHttpTransportForTest: ((String, String, String?, String?, Map<String, String>) -> String)? = null

    private fun isJwtNotExpired(token: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val parts = token.split(".")
        if (parts.size != 3) return false
        return runCatching {
            val payloadJson = String(
                java.util.Base64.getUrlDecoder().decode(parts[1]),
                Charsets.UTF_8
            )
            val expSec = org.json.JSONObject(payloadJson).optLong("exp", 0L)
            expSec <= 0L || expSec * 1000L > nowMs + 60_000L
        }.getOrDefault(false)
    }

    private suspend fun resolveValidCloudAccessToken(
        forceRefresh: Boolean = false,
        failedAccessToken: String? = null
    ): String =
        cloudTokenMutex.withLock {
            val nowMs = System.currentTimeMillis()
            if (!forceRefresh && cachedCloudAccessToken.isNotBlank() && isJwtNotExpired(cachedCloudAccessToken, nowMs)) {
                return@withLock cachedCloudAccessToken
            }
            if (forceRefresh &&
                !failedAccessToken.isNullOrBlank() &&
                cachedCloudAccessToken.isNotBlank() &&
                cachedCloudAccessToken != failedAccessToken &&
                isJwtNotExpired(cachedCloudAccessToken, nowMs)
            ) {
                return@withLock cachedCloudAccessToken
            }
            if (forceRefresh &&
                cachedCloudAccessToken.isNotBlank() &&
                isJwtNotExpired(cachedCloudAccessToken, nowMs) &&
                nowMs - lastCloudTokenRefreshAtMs < 10_000L
            ) {
                return@withLock cachedCloudAccessToken
            }
            val prefs = preferences.data.first()
            val storedAt = prefs[PrefKeys.CLOUD_ACCESS_TOKEN].orEmpty().let { cloudTokenVault.unseal(it) }
            val storedRt = cachedCloudRefreshToken.ifBlank {
                prefs[PrefKeys.CLOUD_REFRESH_TOKEN].orEmpty().let { cloudTokenVault.unseal(it) }
            }
            if (!forceRefresh && storedAt.isNotBlank() && isJwtNotExpired(storedAt, nowMs)) {
                cachedCloudAccessToken = storedAt
                cachedCloudRefreshToken = storedRt
                return@withLock storedAt
            }
            if (storedRt.isNotBlank()) {
                val refreshed = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    val pair = CampusCloudOidcHelper.refreshCloudSession(storedRt)
                    if (pair != null) {
                        cachedCloudAccessToken = pair.first
                        cachedCloudRefreshToken = pair.second
                        lastCloudTokenRefreshAtMs = System.currentTimeMillis()
                        val sealedAt = runCatching { cloudTokenVault.seal(pair.first) }.getOrNull()
                        val sealedRt = runCatching { cloudTokenVault.seal(pair.second) }.getOrNull()
                        if (sealedAt != null && sealedRt != null) {
                            preferences.edit {
                                it[PrefKeys.CLOUD_ACCESS_TOKEN] = sealedAt
                                it[PrefKeys.CLOUD_REFRESH_TOKEN] = sealedRt
                            }
                        }
                    }
                    pair
                }
                if (refreshed != null) {
                    return@withLock refreshed.first
                }
            }
            val candidate = cachedCloudAccessToken.ifBlank { storedAt }
            return@withLock if (candidate.isNotBlank() && isJwtNotExpired(candidate, nowMs)) candidate else ""
        }

    private suspend fun isolateBookingPlansForStudentSwitch(previousStudentId: String, nowMs: Long) {
        val cleanPrevStu = previousStudentId.trim()
        if (cleanPrevStu.isEmpty()) return
        bookingPlanStateMutex.withLock {
            database.withTransaction {
                val all = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
                for (plan in all) {
                    if (plan.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
                        if (plan.ownerStudentId?.trim() == cleanPrevStu) {
                            LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
                        }
                        continue
                    }
                    val stampedOwner = plan.ownerStudentId?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    if (stampedOwner != cleanPrevStu) continue
                    LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
                    when {
                        plan.status in setOf(
                            "CONFIRMED",
                            "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                            "SCHOOL_CANCELLED",
                            "CANCEL_RESULT_UNKNOWN",
                            "RESULT_UNKNOWN",
                            "DELEGATION_STOPPED_RESULT_UNKNOWN",
                            "FAILED",
                            "CANCELLED",
                            "EXPIRED",
                            "REMINDER_SENT"
                        ) -> {
                            if (plan.ownerStudentId != stampedOwner) {
                                cloudAndParcelDao.upsertLibraryBookingPlan(plan.copy(ownerStudentId = stampedOwner))
                            }
                        }
                        plan.status == "EXECUTING" || plan.fireStatus.startsWith("DISPATCH_INTENT") -> {
                            cloudAndParcelDao.upsertLibraryBookingPlan(
                                plan.copy(
                                    ownerStudentId = stampedOwner,
                                    status = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                    fireStatus = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                    generation = plan.generation + 1,
                                    stateVersion = plan.stateVersion + 1,
                                    remainingDays = 0,
                                    lastDiagnosticMessage = "已在任务派发期间切换学号（$stampedOwner），原任务已停止并保留待核对证据",
                                    updatedAtEpochMillis = nowMs
                                )
                            )
                        }
                        else -> {
                            cloudAndParcelDao.upsertLibraryBookingPlan(
                                plan.copy(
                                    ownerStudentId = stampedOwner,
                                    status = "CANCELLED",
                                    fireStatus = "CANCELLED",
                                    generation = plan.generation + 1,
                                    stateVersion = plan.stateVersion + 1,
                                    remainingDays = 0,
                                    lastDiagnosticMessage = "已切换学校学号，原学号（$stampedOwner）未派发的本机排队计划已安全停止",
                                    updatedAtEpochMillis = nowMs
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun saveVerifiedXpeachCloudAccount(
        profile: cn.edu.haut.campus.core.campusservices.XpeachCloudAccountProfile
    ): CloudIdentityBindingEntity {
        val now = System.currentTimeMillis()
        val previousBinding = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
        val isSwitchingIdentity =
            previousBinding == null || previousBinding.cloudUserId != profile.cloudUserId
        val tokenBeforeSwitch = if (previousBinding != null && previousBinding.cloudUserId != profile.cloudUserId) {
            resolveValidCloudAccessToken(forceRefresh = false)
        } else ""
        val cloudBaseUrlForSwitch = if (tokenBeforeSwitch.isNotBlank()) {
            runCatching {
                NetworkProxyConfig.normalizeCloudApiBaseUrl(
                    preferencesFlow.first().networkProxyConfig.customCloudApiBaseUrl
                )
            }.getOrDefault(CampusCloudOidcHelper.DEFAULT_CLOUD_BASE_URL)
        } else CampusCloudOidcHelper.DEFAULT_CLOUD_BASE_URL

        val previousCloudLogoutConfirmed = if (tokenBeforeSwitch.isNotBlank() && cloudHttpTransportForTest == null) {
            withContext(Dispatchers.IO) {
                CampusCloudOidcHelper.logoutAllCloudSessions(
                    accessToken = tokenBeforeSwitch,
                    unlinkXpeach = false,
                    cloudBaseUrl = cloudBaseUrlForSwitch
                )
            }
        } else false

        cloudTokenMutex.withLock {
            if (profile.accessToken.isNotBlank()) {
                cachedCloudAccessToken = profile.accessToken
                lastCloudTokenRefreshAtMs = now
            }
            if (profile.refreshToken.isNotBlank()) {
                cachedCloudRefreshToken = profile.refreshToken
            }
            preferences.edit {
                if (isSwitchingIdentity) {
                    // 切换云端账号时立即脱敏清除上一账号曾获批的受限联系方式、防丢码与个人关联集合
                    it.remove(PrefKeys.MOMENTS_MY_POST_IDS)
                    it.remove(PrefKeys.MOMENTS_MY_BOOK_IDS)
                    it.remove(PrefKeys.MOMENTS_MY_TAG_CODES)
                    it.remove(PrefKeys.MOMENTS_FAVORITE_POST_IDS)
                    it.remove(PrefKeys.MOMENTS_FAVORITE_BOOK_IDS)
                    val rawMoments = it[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON]
                    if (!rawMoments.isNullOrBlank()) {
                        val parsed = CampusCloudOidcHelper.parseMomentsSnapshotJsonString(rawMoments)
                        if (parsed != null) {
                            val scrubbed = CampusCloudOidcHelper.sanitizeMomentsSnapshotForViewerReset(parsed)
                            it[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                                CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(scrubbed)
                        }
                    }
                }
                if (profile.accessToken.isNotBlank()) {
                    it[PrefKeys.CLOUD_ACCESS_TOKEN] = cloudTokenVault.seal(profile.accessToken)
                }
                if (profile.refreshToken.isNotBlank()) {
                    it[PrefKeys.CLOUD_REFRESH_TOKEN] = cloudTokenVault.seal(profile.refreshToken)
                }
            }
        }
        if (previousBinding != null && previousBinding.cloudUserId != profile.cloudUserId) {
            val prevCloudUserId = previousBinding.cloudUserId
            bookingPlanStateMutex.withLock {
                database.withTransaction {
                    val oldPlans = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
                    for (plan in oldPlans) {
                        if (plan.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName) continue
                        val planOwner = plan.ownerCloudUserId?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                        if (planOwner != prevCloudUserId) continue
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
                        // 严禁在换号时 deleteLibraryBookingPlan！必须按原账号隔离保留全部已派发/待核对/已成功记录与预约凭据
                        when {
                            plan.status in setOf(
                                "CONFIRMED",
                                "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                                "SCHOOL_CANCELLED",
                                "CANCEL_RESULT_UNKNOWN",
                                "RESULT_UNKNOWN",
                                "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                "FAILED",
                                "CANCELLED",
                                "EXPIRED"
                            ) -> {
                                if (plan.ownerCloudUserId != planOwner) {
                                    cloudAndParcelDao.upsertLibraryBookingPlan(plan.copy(ownerCloudUserId = planOwner))
                                }
                            }
                            plan.status == "EXECUTING" || plan.fireStatus.startsWith("DISPATCH_INTENT") -> {
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    plan.copy(
                                        ownerCloudUserId = planOwner,
                                        status = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                        fireStatus = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                        generation = plan.generation + 1,
                                        stateVersion = plan.stateVersion + 1,
                                        remainingDays = 0,
                                        lastDiagnosticMessage = "切换云端账号时该任务正在派发或待核对，已保留原账号（$planOwner）证据供追溯",
                                        updatedAtEpochMillis = now
                                    )
                                )
                            }
                            !plan.cloudJobId.isNullOrBlank() && !plan.cloudJobId.startsWith("cjob_") && !previousCloudLogoutConfirmed -> {
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    plan.copy(
                                        ownerCloudUserId = planOwner,
                                        status = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                        fireStatus = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                        generation = plan.generation + 1,
                                        stateVersion = plan.stateVersion + 1,
                                        remainingDays = 0,
                                        lastDiagnosticMessage = "离线切换云端账号：未能联网确认原账号排队任务是否已停止，已隔离保留待核对记录",
                                        updatedAtEpochMillis = now
                                    )
                                )
                            }
                            else -> {
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    plan.copy(
                                        ownerCloudUserId = planOwner,
                                        status = "CANCELLED",
                                        fireStatus = "CANCELLED",
                                        generation = plan.generation + 1,
                                        stateVersion = plan.stateVersion + 1,
                                        remainingDays = 0,
                                        lastDiagnosticMessage = "已切换云端账号，原账号未派发的排队计划已停止",
                                        updatedAtEpochMillis = now
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        val entity = CloudIdentityBindingEntity(
            profileId = profileId,
            cloudUserId = profile.cloudUserId,
            ssoIssuer = profile.ssoIssuer,
            ssoSubject = profile.ssoSubject,
            displayName = profile.displayName.trim().ifBlank { profile.ssoSubject },
            peachBalanceMicropeach = profile.peachBalanceMicropeach,
            holdSupported = profile.holdSupported,
            linkedAtEpochMillis = profile.linkedAtEpochMillis,
            updatedAtEpochMillis = now
        )
        cloudAndParcelDao.upsertCloudIdentityBinding(entity)
        repoScope.launch {
            runCatching { syncWithCampusCloud() }
        }
        return entity
    }

    suspend fun refreshXpeachWalletBalance(): Double? = withContext(Dispatchers.IO) {
        val syncRes = syncWithCampusCloud().getOrNull()
        if (syncRes != null) {
            return@withContext syncRes.peachBalance
        }
        val current = cloudAndParcelDao.getCloudIdentityBindingSnapshot() ?: return@withContext null
        var token = resolveValidCloudAccessToken(forceRefresh = false)
        if (token.isBlank()) return@withContext null
        var livePeach = CampusCloudOidcHelper.fetchLivePeachBalance(token)
        if (livePeach == null) {
            token = resolveValidCloudAccessToken(forceRefresh = true, failedAccessToken = token)
            if (token.isNotBlank()) {
                livePeach = CampusCloudOidcHelper.fetchLivePeachBalance(token)
            }
        }
        if (livePeach == null) return@withContext null
        val updated = current.copy(
            peachBalanceMicropeach = kotlin.math.round(livePeach * 10_000.0).toLong(),
            updatedAtEpochMillis = System.currentTimeMillis()
        )
        cloudAndParcelDao.upsertCloudIdentityBinding(updated)
        livePeach
    }

    private suspend fun buildDelegatedCredentialBundle(): Pair<String?, String?> {
        val cachedLibSession = readCachedLibrarySeatSession()
        val credentialPair: Pair<String, String>? = academicCredentialMutex.withLock {
            val prefs = preferences.data.first()
            val accountId = prefs[PrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] ?: return@withLock null
            val readResult = academicCredentialVault.read(academicCredentialScope(accountId))
            if (readResult !is AcademicCredentialReadResult.Available) return@withLock null
            readResult.credentials.use { creds ->
                creds.studentId to creds.passwordPlain
            }
        }
        val studentNo = cachedLibSession?.userInfo?.userNo?.takeIf { it.isNotBlank() }
            ?: credentialPair?.first?.takeIf { it.isNotBlank() }
            ?: preferencesFlow.first().savedStudentId?.takeIf { it.isNotBlank() }
        if (studentNo.isNullOrBlank() && cachedLibSession == null && credentialPair == null) {
            return null to null
        }
        val bundleJson = org.json.JSONObject()
            .put("student_no", studentNo.orEmpty())
            .put("password", credentialPair?.second.orEmpty())
            .put("raw_token", cachedLibSession?.rawToken.orEmpty())
            .put("cas_tgc", cachedLibSession?.casTgcCookie.orEmpty())
            .put("cas_jsessionid", cachedLibSession?.casJsessionId.orEmpty())
            .toString()
        return studentNo to bundleJson
    }

    /**
     * 与 https://campus.yellowpeachxgp.com 双向同步：
     * 1. 上推本机学号绑定、快递包裹、已取件状态与已取消的预约任务
     * 2. 拉取 XPeach 最新 🍑 余额、网页端创建的占座预约计划与快递取件码并落库 Room
     */
    suspend fun syncWithCampusCloud(): Result<CloudSyncSnapshotResult> = withContext(Dispatchers.IO) {
        cloudSyncMutex.withLock {
            runCatching {
                CampusCloudOidcHelper.configurePersistentAuthStorageDir(
                    java.io.File(appContext.filesDir, "cloud_auth_provenance")
                )
                CampusCloudOidcHelper.configurePersistentAuthStorageDir(
                    java.io.File(appContext.filesDir, "cloud_auth_provenance")
                )
                val currentBinding = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
                    ?: throw IllegalStateException("尚未连接 XPeach 通行证")
                val expectedCloudUserId = currentBinding.cloudUserId.trim()
                if (expectedCloudUserId.isEmpty()) {
                    throw IllegalStateException("当前 XPeach 通行证标识异常，请重新登录")
                }
                var token = resolveValidCloudAccessToken(forceRefresh = false)
                if (token.isBlank()) {
                    throw IllegalStateException("云端会话已过期，请重新登录 XPeach 通行证")
                }

                val prefsBeforeSync = preferencesFlow.first()
                val (bundleStudentNo, localDelegatedSecret) = buildDelegatedCredentialBundle()
                val localStudentNo = bundleStudentNo?.trim()?.takeIf { it.isNotEmpty() }
                    ?: prefsBeforeSync.savedStudentId?.trim()?.takeIf { it.isNotEmpty() }
                val expectedStudentNo = localStudentNo.orEmpty()

                val existingPlansSnapshot = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
                val activeCloudPlans = existingPlansSnapshot.filter {
                    it.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
                        doesCloudPlanBelongToActiveAccount(it, expectedCloudUserId, expectedStudentNo) &&
                        it.status !in setOf(
                            "CANCELLED",
                            "FAILED",
                            "EXPIRED",
                            "CONFIRMED",
                            "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                            "SCHOOL_CANCELLED"
                        )
                }
                val hasActiveCloudBookingPlan = existingPlansSnapshot.any {
                    it.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
                        doesCloudPlanBelongToActiveAccount(it, expectedCloudUserId, expectedStudentNo) &&
                        it.status !in setOf("CANCELLED", "FAILED", "EXPIRED", "SCHOOL_CANCELLED")
                }
                val effectiveDelegatedSecret = if (hasActiveCloudBookingPlan) localDelegatedSecret else null
                val cloudBaseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                    prefsBeforeSync.networkProxyConfig.customCloudApiBaseUrl
                )

                val localPlanUpserts = activeCloudPlans.map { plan ->
                    val seats = plan.preferredSeatLabel
                        .split(",", "，", "、", "/", "|", ";", "；")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .ifEmpty { listOf(plan.preferredSeatLabel.trim().ifBlank { "01桌 B座" }) }
                    cn.edu.haut.campus.core.campusservices.CloudUnsyncedPlanUpsertDto(
                        planId = plan.id,
                        targetLocalDate = plan.targetLocalDate,
                        targetLocalTime = plan.targetLocalTime,
                        areaName = plan.preferredAreaName.trim().ifBlank { "五层中区" },
                        candidateSeats = seats,
                        repeatDays = plan.repeatDays.coerceIn(1, 30),
                        remainingDays = plan.remainingDays.coerceIn(1, 30),
                        allowAlternativeSeat = plan.allowAlternativeSeat,
                        studentNo = plan.ownerStudentId?.trim()?.takeIf { it.isNotEmpty() } ?: localStudentNo
                    )
                }

                val localParcels = cloudAndParcelDao.getParcelItemsSnapshot().map { p ->
                    CloudSyncedParcelDto(
                        id = p.id,
                        trackingNumber = p.trackingNumber,
                        carrierCode = p.carrierCode,
                        carrierName = p.carrierName,
                        status = p.status,
                        pickupCode = p.pickupCode,
                        stationName = p.stationName,
                        itemTitle = p.itemTitle,
                        rawNotificationText = p.estimatedDeliveryText
                    )
                }
                val cancelledJobIds = existingPlansSnapshot
                    .filter {
                        it.ownerCloudUserId?.trim() == expectedCloudUserId &&
                            (expectedStudentNo.isEmpty() || it.ownerStudentId.isNullOrBlank() || it.ownerStudentId.trim() == expectedStudentNo) &&
                            it.status in setOf("CANCELLED", "DELEGATION_STOPPED_RESULT_UNKNOWN") &&
                            !it.cloudJobId.isNullOrBlank() &&
                            !it.cloudJobId.startsWith("cjob_")
                    }
                    .mapNotNull { it.cloudJobId?.trim()?.takeIf { id -> id.isNotEmpty() } }

                var snapshot = CampusCloudOidcHelper.syncCloudSnapshot(
                    accessToken = token,
                    studentNo = localStudentNo,
                    delegatedSecret = effectiveDelegatedSecret,
                    localParcels = localParcels,
                    cancelledJobOrPlanIds = cancelledJobIds,
                    localActiveCloudPlans = localPlanUpserts,
                    cloudBaseUrl = cloudBaseUrl,
                    expectedCloudUserId = expectedCloudUserId,
                    httpTransport = cloudHttpTransportForTest
                )
                if (snapshot == null) {
                    token = resolveValidCloudAccessToken(forceRefresh = true, failedAccessToken = token)
                    if (token.isNotBlank()) {
                        snapshot = CampusCloudOidcHelper.syncCloudSnapshot(
                            accessToken = token,
                            studentNo = localStudentNo,
                            delegatedSecret = effectiveDelegatedSecret,
                            localParcels = localParcels,
                            cancelledJobOrPlanIds = cancelledJobIds,
                            localActiveCloudPlans = localPlanUpserts,
                            cloudBaseUrl = cloudBaseUrl,
                            expectedCloudUserId = expectedCloudUserId,
                            httpTransport = cloudHttpTransportForTest
                        )
                    }
                }
                val verifiedSnapshot = snapshot ?: throw IllegalStateException("同步云端数据失败，请稍后重试")
                if (verifiedSnapshot.cloudUserId.trim() != expectedCloudUserId) {
                    throw IllegalStateException("云端同步返回账号与当前绑定账号不一致，已拒绝落库")
                }
                val activeBindingAfterSync = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
                if (activeBindingAfterSync == null || activeBindingAfterSync.cloudUserId.trim() != expectedCloudUserId) {
                    throw IllegalStateException("云端账号已切换或解绑，已忽略旧会话同步结果")
                }
                val liveStuAfterSync = (buildDelegatedCredentialBundle().first ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                if (expectedStudentNo.isNotEmpty() && liveStuAfterSync.isNotEmpty() && liveStuAfterSync != expectedStudentNo) {
                    throw IllegalStateException("同步期间学号已切换，已忽略旧学号会话同步结果")
                }
                val now = System.currentTimeMillis()

            // 1) 更新本地 XPeach 通行证与 🍑 余额
            cloudAndParcelDao.upsertCloudIdentityBinding(
                currentBinding.copy(
                    displayName = verifiedSnapshot.displayName.ifBlank { currentBinding.displayName },
                    peachBalanceMicropeach = kotlin.math.round(verifiedSnapshot.peachBalance * 10_000.0).toLong(),
                    updatedAtEpochMillis = now
                )
            )

            // 2) 合并云端快递包裹到本地 Room
            var parcelChanged = false
            for (rp in verifiedSnapshot.parcels) {
                val cleanTracking = rp.trackingNumber.cleanNullLiteral()
                if (cleanTracking.isBlank()) continue
                val existing = cloudAndParcelDao.getParcelByTrackingNumber(cleanTracking)
                val cleanRemotePickup = rp.pickupCode.cleanNullableLiteral()
                val cleanExistingPickup = existing?.pickupCode.cleanNullableLiteral()
                val mergedPickup = cleanRemotePickup ?: cleanExistingPickup
                val mergedStation = rp.stationName.cleanNullableLiteral()
                    ?: existing?.stationName.cleanNullableLiteral()
                    ?: "莲花街北苑菜鸟驿站"
                val mergedTitle = rp.itemTitle.cleanNullableLiteral()
                    ?: existing?.itemTitle.cleanNullableLiteral()
                val mergedCarrier = rp.carrierName.cleanNullLiteral(
                    existing?.carrierName.cleanNullLiteral("校园快递")
                )
                val mergedStatus = when {
                    existing?.status == ParcelLifecycleStatus.PICKED_UP.wireName || rp.status == ParcelLifecycleStatus.PICKED_UP.wireName ->
                        ParcelLifecycleStatus.PICKED_UP.wireName
                    !mergedPickup.isNullOrBlank() ->
                        ParcelLifecycleStatus.READY_FOR_PICKUP.wireName
                    rp.status == ParcelLifecycleStatus.READY_FOR_PICKUP.wireName && mergedPickup == null ->
                        ParcelLifecycleStatus.IN_TRANSIT.wireName
                    else -> rp.status.cleanNullLiteral(ParcelLifecycleStatus.IN_TRANSIT.wireName)
                }
                if (existing == null ||
                    existing.status != mergedStatus ||
                    existing.pickupCode != mergedPickup ||
                    existing.stationName != mergedStation ||
                    existing.itemTitle != mergedTitle
                ) {
                    parcelChanged = true
                    cloudAndParcelDao.upsertParcelItem(
                        ParcelItemEntity(
                            id = existing?.id ?: rp.id.cleanNullLiteral("prc_${UUID.randomUUID().toString().replace("-", "").take(12)}"),
                            trackingNumber = cleanTracking,
                            carrierCode = rp.carrierCode.cleanNullLiteral(existing?.carrierCode.cleanNullLiteral("CAINIAO")),
                            carrierName = mergedCarrier,
                            status = mergedStatus,
                            pickupCode = mergedPickup,
                            stationName = mergedStation,
                            itemTitle = mergedTitle,
                            estimatedDeliveryText = when (mergedStatus) {
                                ParcelLifecycleStatus.PICKED_UP.wireName -> "本人已取件签收"
                                ParcelLifecycleStatus.READY_FOR_PICKUP.wireName -> "已到达驿站/快递柜，请凭取件码取件"
                                else -> existing?.estimatedDeliveryText.cleanNullableLiteral() ?: "运输中，预计 1-2 日送达校园驿站"
                            },
                            sourcesJson = existing?.sourcesJson ?: """[{"provider_id":"parcel.cloud-sync","priority":90}]""",
                            fieldProvenanceJson = existing?.fieldProvenanceJson ?: """{"provider_id":"parcel.cloud-sync","updated_at":$now}""",
                            checkpointsJson = existing?.checkpointsJson ?: "[]",
                            createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                            updatedAtEpochMillis = now
                        )
                    )
                }
            }

            // 3) 合并云端图书馆预约任务到本地 Room（严格隔离 ownerCloudUserId + 学号，严禁混并空 owner 旧记录或跨云账号记录）
            bookingPlanStateMutex.withLock {
                val liveBindingLocked = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
                val liveStuLocked = (buildDelegatedCredentialBundle().first ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                if (liveBindingLocked == null || liveBindingLocked.cloudUserId.trim() != expectedCloudUserId) {
                    throw IllegalStateException("同步落库前云端账号已切换或解绑，已放弃写入旧会话同步结果")
                }
                if (expectedStudentNo.isNotEmpty() && liveStuLocked.isNotEmpty() && liveStuLocked != expectedStudentNo) {
                    throw IllegalStateException("同步落库前学号已切换，已放弃写入旧学号会话同步结果")
                }
                val syncedCloudUserId = expectedCloudUserId
                val activeStuNo = expectedStudentNo.ifEmpty { liveStuLocked }.takeIf { it.isNotEmpty() }
                val allExistingInLock = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
                val mutableExistingById = allExistingInLock.associateBy { it.id }.toMutableMap()
                for (rj in verifiedSnapshot.jobs) {
                    // 3.1 先按稳定云端 jobId（次选精确 planId）在同云账号全量旧行中查找（不以当前 B 学号过滤，防止漏掉旧 A 行后被主键 REPLACE 覆盖！）
                    val existingByStableJob = allExistingInLock.firstOrNull {
                        it.ownerCloudUserId?.trim() == syncedCloudUserId &&
                            rj.jobId.isNotBlank() &&
                            it.cloudJobId == rj.jobId
                    } ?: allExistingInLock.firstOrNull {
                        it.ownerCloudUserId?.trim() == syncedCloudUserId &&
                            rj.planId.isNotBlank() &&
                            it.id == rj.planId &&
                            (it.cloudJobId.isNullOrBlank() || it.cloudJobId == rj.jobId)
                    }

                    // 3.2 核定不可变的学校学号归属（existingImmutableStu 优先且不可被新学号篡改；新返回任务仅认服务端/绑定/本机已核验来源，未知归属保持 null 隔离！）
                    val existingImmutableStu = existingByStableJob?.ownerStudentId?.trim()?.takeIf { it.isNotEmpty() }
                    val localProvenanceStu = listOfNotNull(
                        existingByStableJob?.id,
                        rj.planId.takeIf { it.isNotBlank() }
                    ).firstNotNullOfOrNull { pid ->
                        CampusCloudOidcHelper.resolveScopedPlanProvenanceStudentNo(
                            cloudBaseUrl = cloudBaseUrl,
                            accessToken = token,
                            planId = pid,
                            expectedBindingId = rj.schoolBindingId
                        )
                    }
                    val verifiedRemoteStu = rj.studentNo?.trim()?.takeIf { it.isNotEmpty() && !it.contains("***") }
                        ?: localProvenanceStu
                    if (existingImmutableStu != null &&
                        verifiedRemoteStu != null &&
                        verifiedRemoteStu != existingImmutableStu
                    ) {
                        // 同一 jobId 的学号归属发生矛盾：拒绝覆盖原有学校身份归属
                        continue
                    }
                    val resolvedJobStudentId: String? = existingImmutableStu ?: verifiedRemoteStu

                    // 3.3 仅当无已绑定 jobId 旧行且当前任务具备明确已核验学号时，才允许与同一云账号+同一学号+未分配 cloudJobId 的同日本地计划合并
                    val matchingLocal = existingByStableJob ?: if (resolvedJobStudentId != null) {
                        allExistingInLock.firstOrNull {
                            it.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
                                it.ownerCloudUserId?.trim() == syncedCloudUserId &&
                                it.ownerStudentId?.trim() == resolvedJobStudentId &&
                                it.cloudJobId.isNullOrBlank() &&
                                it.targetLocalDate == rj.targetLocalDate
                        }
                    } else {
                        null
                    }
                    // 若本地任务当前正处于本进程或已领取的 EXECUTING/DISPATCH_INTENT、或本地已请求取消而云端快照尚停留在排队态，拒绝被旧排队状态回退覆盖！
                    if (matchingLocal != null) {
                        val localIsExecuting =
                            matchingLocal.status == "EXECUTING" || matchingLocal.fireStatus.startsWith("DISPATCH_INTENT")
                        if (localIsExecuting && rj.state in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")) {
                            continue
                        }
                        if (matchingLocal.status in setOf("CANCELLED", "CANCEL_RESULT_UNKNOWN", "SCHOOL_CANCELLED") &&
                            rj.state in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")
                        ) {
                            continue
                        }
                    }
                    val matchingOrder = verifiedSnapshot.orders.firstOrNull { it.jobId == rj.jobId }
                    val cleanAreaName = (rj.bookedRegionAlias ?: rj.areaName).cleanNullLiteral(
                        matchingLocal?.preferredAreaName.cleanNullLiteral("五层中区")
                    )
                    val candidateSeatsLabel = rj.candidateSeats
                        .map { it.cleanNullLiteral() }
                        .filter { it.isNotEmpty() }
                        .joinToString(", ")
                        .ifBlank { matchingLocal?.preferredSeatLabel.cleanNullLiteral("01桌 B座") }
                    val primarySeat = rj.bookedSeatAlias?.cleanNullableLiteral()
                        ?: rj.candidateSeats.firstOrNull { it.cleanNullLiteral().isNotEmpty() }
                        ?: matchingLocal?.preferredSeatLabel.cleanNullableLiteral()
                        ?: "001"
                    val parsedDate = runCatching { LocalDate.parse(rj.targetLocalDate) }.getOrNull() ?: LocalDate.now().plusDays(1)
                    val parsedTime = runCatching { DualEngineReservationPlanner.parseTargetLocalTime(rj.targetLocalTime) }.getOrNull() ?: LocalTime.of(7, 0)
                    val fireEpochMs = parsedDate.atTime(parsedTime).atZone(DualEngineReservationPlanner.HAUT_ZONE).toInstant().toEpochMilli()
                    val effRepeatDays = rj.repeatDays.coerceAtLeast(matchingLocal?.repeatDays ?: 1)
                    val hasCompleteSchoolConfirmation =
                        !rj.externalBookingId.cleanNullableLiteral().isNullOrBlank() &&
                            !rj.bookedSeatAlias.cleanNullableLiteral().isNullOrBlank() &&
                            !rj.bookedRegionAlias.cleanNullableLiteral().isNullOrBlank() &&
                            !rj.evidenceSha256.cleanNullableLiteral().isNullOrBlank()
                    val rawEffectiveStatus = when {
                        rj.displayStatus == "DELEGATION_STOPPED_SCHOOL_CONFIRMED" ||
                            rj.state == "DELEGATION_STOPPED_SCHOOL_CONFIRMED" -> "DELEGATION_STOPPED_SCHOOL_CONFIRMED"
                        rj.displayStatus == "DELEGATION_STOPPED_RESULT_UNKNOWN" -> "DELEGATION_STOPPED_RESULT_UNKNOWN"
                        rj.displayStatus == "SCHOOL_CANCELLED" || rj.state == "SCHOOL_CANCELLED" -> "SCHOOL_CANCELLED"
                        rj.displayStatus == "CANCEL_RESULT_UNKNOWN" || rj.state == "CANCEL_RESULT_UNKNOWN" -> "CANCEL_RESULT_UNKNOWN"
                        rj.displayStatus == "NEEDS_RECONFIRMATION" -> "NEEDS_RECONFIRMATION"
                        else -> rj.state
                    }
                    val effectiveStatus = if (
                        rawEffectiveStatus in setOf("CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED") &&
                        !hasCompleteSchoolConfirmation
                    ) {
                        if (rawEffectiveStatus == "DELEGATION_STOPPED_SCHOOL_CONFIRMED" ||
                            rj.userIntentState in setOf("REVOKED", "STOP_REQUESTED")
                        ) {
                            "DELEGATION_STOPPED_RESULT_UNKNOWN"
                        } else {
                            "RESULT_UNKNOWN"
                        }
                    } else {
                        rawEffectiveStatus
                    }
                    val effRemainingDays = if (effectiveStatus in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")) {
                        rj.remainingDays.coerceAtLeast(1)
                    } else {
                        0
                    }
                    val repeatDiagSuffix = if (effRepeatDays > 1 && effRemainingDays > 0) {
                        "（连续 $effRepeatDays 天 · 剩余 $effRemainingDays 天）"
                    } else {
                        ""
                    }
                    val diagText = when (effectiveStatus) {
                        "CONFIRMED" -> "云端代抢（测试版）已成功为您锁定 $cleanAreaName $primarySeat，请按时到馆刷脸签到$repeatDiagSuffix"
                        "DELEGATION_STOPPED_SCHOOL_CONFIRMED" ->
                            "委托已停止，但学校预约已成功（座位：$cleanAreaName $primarySeat，预约号：${rj.externalBookingId ?: "已生成"}）。系统未扣费且已停止后续计划，请核对或按需退座"
                        "DELEGATION_STOPPED_RESULT_UNKNOWN" ->
                            "委托已停止，但此前已发出的学校预约请求结果待核对（禁止盲目重试）"
                        "RESULT_UNKNOWN" ->
                            "结果待核对：云端已发出选座请求，待回查核实学校端状态（核实前不重试、不扣费）"
                        "SCHOOL_CANCELLED" -> "学校端已确认取消该笔座位预约并释放座位"
                        "CANCEL_RESULT_UNKNOWN" -> "退座结果待核对：取消请求已发出，正在等待学校端确认"
                        "NEEDS_RECONFIRMATION" -> "需要重新确认授权：请点击「重新确认授权」以恢复云端同步"
                        "CANCELLED" -> "已停止委托（未发起学校预约写入，未消耗内测配额 🍑）"
                        "FAILED" -> "目标座位已被抢满，本次未消耗内测配额 🍑$repeatDiagSuffix"
                        "EXPIRED" -> "已过开抢有效期窗口（本次未消耗内测配额 🍑）"
                        else -> "云端代抢（测试版）已就绪：将在 ${rj.targetLocalDate} ${rj.targetLocalTime} 自动尝试抢座$repeatDiagSuffix"
                    }
                    val isConfirmedOnSchool =
                        effectiveStatus == "CONFIRMED" || effectiveStatus == "DELEGATION_STOPPED_SCHOOL_CONFIRMED"
                    val candidatePlanId = "plan_${rj.jobId.ifBlank { rj.planId }.replace("-", "").take(14)}"
                    val resolvedPlanId = if (matchingLocal != null) {
                        matchingLocal.id
                    } else {
                        val occupant = mutableExistingById[candidatePlanId]
                        if (occupant == null) {
                            candidatePlanId
                        } else {
                            val sameCloud = occupant.ownerCloudUserId?.trim() == syncedCloudUserId
                            val sameVerifiedStudent = !resolvedJobStudentId.isNullOrBlank() &&
                                occupant.ownerStudentId?.trim() == resolvedJobStudentId &&
                                (occupant.cloudJobId.isNullOrBlank() || occupant.cloudJobId == rj.jobId)
                            if (sameCloud && sameVerifiedStudent) {
                                candidatePlanId
                            } else {
                                val stuTag = (resolvedJobStudentId ?: "unscoped")
                                    .replace(Regex("[^A-Za-z0-9]"), "")
                                    .takeLast(6)
                                    .ifEmpty { "unscoped" }
                                val idDigest = rj.jobId.ifBlank { rj.planId }.hashCode().toUInt().toString(16).take(6)
                                "${candidatePlanId}_${syncedCloudUserId.replace("-", "").takeLast(4)}_${stuTag}_$idDigest"
                            }
                        }
                    }
                    val mergedPlan = LibraryBookingPlanEntity(
                        id = resolvedPlanId,
                        schoolId = schoolId,
                        executionMode = BookingExecutionMode.CLOUD_HOSTED.wireName,
                        targetLocalDate = rj.targetLocalDate,
                        targetLocalTime = rj.targetLocalTime,
                        timezoneId = DualEngineReservationPlanner.HAUT_ZONE.id,
                        precheckAtEpochMillis = fireEpochMs - 1800_000L,
                        finalCheckAtEpochMillis = fireEpochMs - 300_000L,
                        fireAtEpochMillis = fireEpochMs,
                        precheckStatus = if (isConfirmedOnSchool) "PRECHECK_OK" else (matchingLocal?.precheckStatus ?: "ARMED"),
                        finalCheckStatus = if (isConfirmedOnSchool) "FINAL_CHECK_OK" else (matchingLocal?.finalCheckStatus ?: "ARMED"),
                        fireStatus = if (isConfirmedOnSchool) effectiveStatus else effectiveStatus,
                        preferredAreaName = cleanAreaName,
                        preferredSeatLabel = rj.bookedSeatAlias?.cleanNullableLiteral() ?: candidateSeatsLabel,
                        allowAlternativeSeat = matchingLocal?.allowAlternativeSeat ?: (rj.candidateSeats.size > 1),
                        cloudJobId = rj.jobId.cleanNullableLiteral(),
                        externalBookingId = when {
                            isConfirmedOnSchool -> rj.externalBookingId.cleanNullableLiteral()
                            effectiveStatus == "CANCEL_RESULT_UNKNOWN" ->
                                rj.externalBookingId.cleanNullableLiteral() ?: matchingLocal?.externalBookingId.cleanNullableLiteral()
                            else -> null
                        },
                        evidenceSha256 = if (isConfirmedOnSchool) rj.evidenceSha256.cleanNullableLiteral() else null,
                        priceMicropeach = 10_000L,
                        paymentState = when {
                            effectiveStatus in setOf("DELEGATION_STOPPED_SCHOOL_CONFIRMED", "RESULT_UNKNOWN", "DELEGATION_STOPPED_RESULT_UNKNOWN") ->
                                "HELD_PENDING_REVIEW"
                            matchingOrder != null -> matchingOrder.paymentState
                            effectiveStatus == "CONFIRMED" -> "PAID"
                            else -> "DEBIT_PENDING"
                        },
                        status = effectiveStatus,
                        stateVersion = (matchingLocal?.stateVersion ?: 0) + 1,
                        generation = matchingLocal?.generation ?: 1,
                        lastDiagnosticMessage = diagText,
                        createdAtEpochMillis = matchingLocal?.createdAtEpochMillis ?: now,
                        updatedAtEpochMillis = now,
                        repeatDays = effRepeatDays,
                        remainingDays = effRemainingDays,
                        ownerCloudUserId = syncedCloudUserId,
                        ownerStudentId = resolvedJobStudentId
                    )
                    cloudAndParcelDao.upsertLibraryBookingPlan(mergedPlan)
                    mutableExistingById[mergedPlan.id] = mergedPlan
                    val belongsToCurrentStudentForAlarm =
                        activeStuNo == null || (!resolvedJobStudentId.isNullOrBlank() && resolvedJobStudentId == activeStuNo)
                    if (belongsToCurrentStudentForAlarm &&
                        mergedPlan.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")
                    ) {
                        LibrarySeatAlarmSubsystem.schedulePlanAlarms(appContext, mergedPlan)
                    } else {
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, mergedPlan.id)
                    }
                }

                // 4) 对因新授权世代未确认而被安全阻断上推的当前账号本地计划，显式标记为 NEEDS_RECONFIRMATION 并取消闹钟（绝不改动其他账号保留记录！）
                if (verifiedSnapshot.needsReconfirmationPlanIds.isNotEmpty() ||
                    verifiedSnapshot.needsReconfirmationBindingStudentNos.isNotEmpty()
                ) {
                    val reconfirmSet = verifiedSnapshot.needsReconfirmationPlanIds.toSet()
                    val latestLocalPlans = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
                    for (lp in latestLocalPlans) {
                        if (lp.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
                            doesCloudPlanBelongToActiveAccount(lp, syncedCloudUserId, activeStuNo.orEmpty()) &&
                            (lp.id in reconfirmSet ||
                                (verifiedSnapshot.needsReconfirmationBindingStudentNos.isNotEmpty() &&
                                    lp.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")))
                        ) {
                            LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, lp.id)
                            cloudAndParcelDao.upsertLibraryBookingPlan(
                                lp.copy(
                                    status = "NEEDS_RECONFIRMATION",
                                    stateVersion = lp.stateVersion + 1,
                                    lastDiagnosticMessage = "需要重新确认授权：云端授权世代已变更，请点击「重新确认授权并恢复同步」后继续云端托管抢座",
                                    updatedAtEpochMillis = now
                                )
                            )
                        }
                    }
                }
            }

            val rawCampusContent = verifiedSnapshot.campusContentRawJson
            if (!rawCampusContent.isNullOrBlank()) {
                preferences.edit { prefs ->
                    prefs[PrefKeys.CLOUD_CAMPUS_CONTENT_JSON] = rawCampusContent
                    prefs[PrefKeys.CLOUD_CAMPUS_CONTENT_SYNCED_MS] = now
                }
            }

            if (parcelChanged) {
                notifyScheduleOrPolicyChanged("PARCEL_CLOUD_SYNCED")
            }
            verifiedSnapshot
            }
        }
    }

    /**
     * 显式重新确认当前学校绑定与本地待确认的云端预约计划，在新授权世代下恢复同步
     */
    suspend fun reconfirmAndRestoreCloudBookingPlans(): Result<CloudSyncSnapshotResult> = withContext(Dispatchers.IO) {
        runCatching {
            CampusCloudOidcHelper.configurePersistentAuthStorageDir(
                java.io.File(appContext.filesDir, "cloud_auth_provenance")
            )
            val bindingAtStart = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
                ?: throw IllegalStateException("尚未连接 XPeach 通行证")
            val expectedCloudUserId = bindingAtStart.cloudUserId.trim()
            if (expectedCloudUserId.isEmpty()) {
                throw IllegalStateException("当前 XPeach 通行证标识异常，请重新登录")
            }
            val token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("云端会话已过期，请重新登录 XPeach 通行证")
            }
            val (localStudentNo, localDelegatedSecret) = buildDelegatedCredentialBundle()
            val prefsSnap = preferencesFlow.first()
            val studentNo = (localStudentNo ?: prefsSnap.savedStudentId.orEmpty()).trim()
            if (studentNo.isBlank()) {
                throw IllegalStateException("请先绑定并登录学校图书馆或教务账号")
            }
            val cloudBaseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsSnap.networkProxyConfig.customCloudApiBaseUrl
            )
            val plansSnapshot = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
            val matchingPlans = plansSnapshot.filter {
                it.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
                    doesCloudPlanBelongToActiveAccount(it, expectedCloudUserId, studentNo) &&
                    it.status in setOf("NEEDS_RECONFIRMATION", "ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")
            }
            val expectedStateVersionsById = matchingPlans.associate { it.id to it.stateVersion }
            val plansToReconfirm = matchingPlans.map { plan ->
                val seats = plan.preferredSeatLabel
                    .split(",", "，", "、", "/", "|", ";", "；")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { listOf(plan.preferredSeatLabel.trim().ifBlank { "01桌 B座" }) }
                cn.edu.haut.campus.core.campusservices.CloudUnsyncedPlanUpsertDto(
                    planId = plan.id,
                    targetLocalDate = plan.targetLocalDate,
                    targetLocalTime = plan.targetLocalTime,
                    areaName = plan.preferredAreaName.trim().ifBlank { "五层中区" },
                    candidateSeats = seats,
                    repeatDays = plan.repeatDays.coerceIn(1, 30),
                    remainingDays = plan.remainingDays.coerceIn(1, 30),
                    allowAlternativeSeat = plan.allowAlternativeSeat,
                    studentNo = studentNo
                )
            }
            val reconfirmed = CampusCloudOidcHelper.reconfirmSchoolBindingAndPlans(
                accessToken = token,
                studentNo = studentNo,
                delegatedSecret = localDelegatedSecret.orEmpty(),
                plansToReconfirm = plansToReconfirm,
                cloudBaseUrl = cloudBaseUrl,
                httpTransport = cloudHttpTransportForTest
            )
            if (!reconfirmed) {
                throw IllegalStateException("重新确认学校授权失败，请检查网络连接后重试")
            }
            val now = System.currentTimeMillis()
            bookingPlanStateMutex.withLock {
                val liveBinding = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
                val liveStu = (buildDelegatedCredentialBundle().first ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                if (liveBinding == null || liveBinding.cloudUserId.trim() != expectedCloudUserId || liveStu != studentNo) {
                    throw IllegalStateException("重新确认授权期间账号或学号已切换，已停止恢复旧会话计划")
                }
                database.withTransaction {
                    for ((planId, expectedVer) in expectedStateVersionsById) {
                        val currentRow = cloudAndParcelDao.getLibraryBookingPlanById(planId) ?: continue
                        if (currentRow.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
                            doesCloudPlanBelongToActiveAccount(currentRow, expectedCloudUserId, studentNo) &&
                            currentRow.status == "NEEDS_RECONFIRMATION" &&
                            currentRow.stateVersion == expectedVer
                        ) {
                            cloudAndParcelDao.upsertLibraryBookingPlan(
                                currentRow.copy(
                                    status = "ARMED",
                                    stateVersion = currentRow.stateVersion + 1,
                                    ownerCloudUserId = expectedCloudUserId,
                                    ownerStudentId = currentRow.ownerStudentId?.takeIf { it.isNotBlank() } ?: studentNo,
                                    lastDiagnosticMessage = "已重新确认云端授权，正在同步恢复云端托管抢座",
                                    updatedAtEpochMillis = now
                                )
                            )
                        }
                    }
                }
            }
            syncWithCampusCloud().getOrThrow()
        }
    }

    suspend fun unlinkXpeachCloudAccountAndRevokeCloudPlans() {
        val now = System.currentTimeMillis()
        val bindingBeforeUnlink = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
        val prevCloudUserId = bindingBeforeUnlink?.cloudUserId
        val tokenBeforeUnlink = resolveValidCloudAccessToken(forceRefresh = false)
        val cloudBaseUrl = runCatching {
            NetworkProxyConfig.normalizeCloudApiBaseUrl(
                preferencesFlow.first().networkProxyConfig.customCloudApiBaseUrl
            )
        }.getOrDefault(CampusCloudOidcHelper.DEFAULT_CLOUD_BASE_URL)
        val cloudRevokeSucceeded = if (tokenBeforeUnlink.isNotBlank()) {
            withContext(Dispatchers.IO) {
                CampusCloudOidcHelper.logoutAllCloudSessions(
                    accessToken = tokenBeforeUnlink,
                    unlinkXpeach = true,
                    cloudBaseUrl = cloudBaseUrl
                )
            }
        } else {
            false
        }
        cloudTokenMutex.withLock {
            cachedCloudAccessToken = ""
            cachedCloudRefreshToken = ""
            lastCloudTokenRefreshAtMs = 0L
            preferences.edit {
                it.remove(PrefKeys.CLOUD_ACCESS_TOKEN)
                it.remove(PrefKeys.CLOUD_REFRESH_TOKEN)
                it.remove(PrefKeys.MOMENTS_MY_POST_IDS)
                it.remove(PrefKeys.MOMENTS_MY_BOOK_IDS)
                it.remove(PrefKeys.MOMENTS_MY_TAG_CODES)
                it.remove(PrefKeys.MOMENTS_FAVORITE_POST_IDS)
                it.remove(PrefKeys.MOMENTS_FAVORITE_BOOK_IDS)
                val rawMoments = it[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON]
                if (!rawMoments.isNullOrBlank()) {
                    val parsed = CampusCloudOidcHelper.parseMomentsSnapshotJsonString(rawMoments)
                    if (parsed != null) {
                        val scrubbed = CampusCloudOidcHelper.sanitizeMomentsSnapshotForViewerReset(parsed)
                        it[PrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                            CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(scrubbed)
                    }
                }
            }
        }
        cloudAndParcelDao.clearCloudIdentityBinding()
        bookingPlanStateMutex.withLock {
            database.withTransaction {
                val plans = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
                for (plan in plans) {
                    if (plan.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
                        val explicitCloudOwner = plan.ownerCloudUserId?.trim()?.takeIf { it.isNotEmpty() }
                        if (prevCloudUserId != null && explicitCloudOwner != prevCloudUserId) {
                            continue
                        }
                        val stampedCloudOwner = explicitCloudOwner
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
                        when {
                            // 严禁把已确认、在途或结果未知的状态覆盖为 CANCELLED（否则会丢失预约凭据或放行同日重复抢座！）
                            plan.status in setOf(
                                "CONFIRMED",
                                "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                                "SCHOOL_CANCELLED",
                                "CANCEL_RESULT_UNKNOWN",
                                "RESULT_UNKNOWN",
                                "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                "FAILED",
                                "CANCELLED",
                                "EXPIRED"
                            ) -> {
                                if (stampedCloudOwner != null && plan.ownerCloudUserId != stampedCloudOwner) {
                                    cloudAndParcelDao.upsertLibraryBookingPlan(plan.copy(ownerCloudUserId = stampedCloudOwner))
                                }
                            }
                            plan.status == "EXECUTING" || plan.fireStatus.startsWith("DISPATCH_INTENT") -> {
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    plan.copy(
                                        ownerCloudUserId = stampedCloudOwner,
                                        status = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                        fireStatus = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                        generation = plan.generation + 1,
                                        stateVersion = plan.stateVersion + 1,
                                        remainingDays = 0,
                                        lastDiagnosticMessage = "已在任务派发后退出云端账号，结果待核对（核实前禁止同日重复提交）",
                                        updatedAtEpochMillis = now
                                    )
                                )
                            }
                            !plan.cloudJobId.isNullOrBlank() && !plan.cloudJobId.startsWith("cjob_") && !cloudRevokeSucceeded -> {
                                // 离线解绑且云端已有真实排队任务：无法协调停止云端，标为待核对以阻止同日本机盲目重复预约
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    plan.copy(
                                        ownerCloudUserId = stampedCloudOwner,
                                        status = "RESULT_UNKNOWN",
                                        fireStatus = "RESULT_UNKNOWN",
                                        generation = plan.generation + 1,
                                        stateVersion = plan.stateVersion + 1,
                                        remainingDays = 0,
                                        lastDiagnosticMessage = "离线退出云端账号：无法联网确认云端排队任务是否已停止，请核实前勿重复提交同日抢座",
                                        updatedAtEpochMillis = now
                                    )
                                )
                            }
                            else -> {
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    plan.copy(
                                        ownerCloudUserId = stampedCloudOwner,
                                        status = "CANCELLED",
                                        fireStatus = "CANCELLED",
                                        generation = plan.generation + 1,
                                        stateVersion = plan.stateVersion + 1,
                                        remainingDays = 0,
                                        lastDiagnosticMessage = "已解绑 XPEACH 账号并撤回未执行的云端托管授权",
                                        updatedAtEpochMillis = now
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Dual-Engine Library Reservation Plans (ADR-005 Dynamic Target T: T-30m / T-5m / T) ---
    val allLibraryBookingPlansForAuditFlow: Flow<List<LibraryBookingPlanEntity>> =
        cloudAndParcelDao.observeLibraryBookingPlans()

    private fun doesCloudPlanBelongToActiveAccount(
        plan: LibraryBookingPlanEntity,
        activeCloudUserId: String?,
        activeStudentNo: String?
    ): Boolean {
        val cleanActiveCloud = activeCloudUserId?.trim().orEmpty()
        if (cleanActiveCloud.isEmpty()) return false
        val planCloud = plan.ownerCloudUserId?.trim().orEmpty()
        if (planCloud.isEmpty() || planCloud != cleanActiveCloud) return false
        val cleanActiveStu = activeStudentNo?.trim().orEmpty()
        val planStu = plan.ownerStudentId?.trim().orEmpty()
        if (cleanActiveStu.isNotEmpty()) {
            // 当前已明确登录某学号 B：仅明确归属学号 B 的云任务属于当前活跃身份；A 的任务与未知学号归属任务一律隔离！
            return planStu.isNotEmpty() && planStu == cleanActiveStu
        }
        return planStu.isEmpty()
    }

    private fun isUnattributedLegacyBookingPlan(plan: LibraryBookingPlanEntity): Boolean {
        return if (plan.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
            plan.ownerCloudUserId.isNullOrBlank() || plan.ownerStudentId.isNullOrBlank()
        } else {
            plan.ownerStudentId.isNullOrBlank()
        }
    }

    /** 无法证明唯一归属的升级遗留记录（待认领/本地审计专用，普通登录账号页面与同步不可见） */
    val unattributedLegacyBookingPlansFlow: Flow<List<LibraryBookingPlanEntity>> =
        cloudAndParcelDao.observeLibraryBookingPlans().map { plans ->
            plans.filter(::isUnattributedLegacyBookingPlan)
        }

    private fun isBookingPlanVisibleToIdentity(
        plan: LibraryBookingPlanEntity,
        activeCloudUserId: String?,
        activeStudentId: String?
    ): Boolean {
        val cleanActiveCloud = activeCloudUserId?.trim().orEmpty()
        val cleanActiveStudent = activeStudentId?.trim().orEmpty()
        if (plan.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
            val planOwnerCloud = plan.ownerCloudUserId?.trim().orEmpty()
            val planOwnerStu = plan.ownerStudentId?.trim().orEmpty()
            return when {
                cleanActiveCloud.isNotEmpty() -> {
                    // 已登录当前云端账号：若同时已登录学号 B，严禁将未知学号归属（planOwnerStu 为空）或学号 A 的记录展示为 B 的计划！
                    planOwnerCloud.isNotEmpty() &&
                        planOwnerCloud == cleanActiveCloud &&
                        (cleanActiveStudent.isEmpty() || (planOwnerStu.isNotEmpty() && planOwnerStu == cleanActiveStudent))
                }
                // 未登录云端账号时：若已登录某学号则仅展示明确匹配该学号的证据记录；若完全未登录则保留展示本机待核对/已确认证据
                else -> {
                    val studentMatchesWhenLoggedOutOfCloud = if (cleanActiveStudent.isNotEmpty()) {
                        planOwnerStu.isNotEmpty() && planOwnerStu == cleanActiveStudent
                    } else {
                        true
                    }
                    studentMatchesWhenLoggedOutOfCloud && (
                        plan.status in setOf(
                            "CONFIRMED",
                            "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                            "RESULT_UNKNOWN",
                            "DELEGATION_STOPPED_RESULT_UNKNOWN",
                            "CANCEL_RESULT_UNKNOWN",
                            "SCHOOL_CANCELLED"
                        ) || !plan.externalBookingId.isNullOrBlank() || !plan.evidenceSha256.isNullOrBlank()
                    )
                }
            }
        }
        val planOwnerStu = plan.ownerStudentId?.trim().orEmpty()
        return if (cleanActiveStudent.isNotEmpty()) {
            // 已登录本地学号 B：严禁将未知归属（空 ownerStudentId）旧记录展示为 B 的计划！
            planOwnerStu.isNotEmpty() && planOwnerStu == cleanActiveStudent
        } else {
            planOwnerStu.isEmpty() ||
                plan.status in setOf(
                    "CONFIRMED",
                    "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                    "RESULT_UNKNOWN",
                    "DELEGATION_STOPPED_RESULT_UNKNOWN",
                    "CANCEL_RESULT_UNKNOWN",
                    "SCHOOL_CANCELLED"
                ) || !plan.externalBookingId.isNullOrBlank() || !plan.evidenceSha256.isNullOrBlank()
        }
    }

    private fun isSamePersonBookingConflictCandidate(
        candidate: LibraryBookingPlanEntity,
        targetLocalDate: String,
        activeCloudUserId: String?,
        activeStudentNo: String?,
        excludePlanId: String? = null
    ): Boolean {
        if (excludePlanId != null && candidate.id == excludePlanId) return false
        if (candidate.targetLocalDate != targetLocalDate) return false
        if (candidate.status in setOf("CANCELLED", "FAILED", "EXPIRED", "SCHOOL_CANCELLED", "REMINDER_SENT")) {
            return false
        }
        val cleanActiveStu = activeStudentNo?.trim().orEmpty()
        val candStu = candidate.ownerStudentId?.trim().orEmpty()
        val cleanActiveCloud = activeCloudUserId?.trim().orEmpty()
        val candCloud = candidate.ownerCloudUserId?.trim().orEmpty()

        // 1. 两侧均有明确学号：同一学号（哪怕跨不同云账号）严格防重；不同学号严格隔离不误阻！
        if (cleanActiveStu.isNotEmpty() && candStu.isNotEmpty()) {
            return cleanActiveStu == candStu
        }
        // 2. 两侧均有明确云账号：同一云账号防重；不同云账号隔离不误阻！
        if (cleanActiveCloud.isNotEmpty() && candCloud.isNotEmpty()) {
            return cleanActiveCloud == candCloud
        }
        // 3. 当前与候选记录均未写入学号，且候选为本机非云端任务（同设备未绑学号时的在途/排队本机计划）：必须防重阻断！
        if (cleanActiveStu.isEmpty() &&
            candStu.isEmpty() &&
            candCloud.isEmpty() &&
            candidate.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName
        ) {
            return true
        }
        // 4. 完全退出云账号且未切换为其他学号时（例如仅解绑 XPeach 云账号后尝试同日改建本机抢座）：保留对本机可见已确认/待核对记录的同日防重！
        if (cleanActiveCloud.isEmpty() && cleanActiveStu.isEmpty()) {
            return isBookingPlanVisibleToIdentity(candidate, null, "")
        }
        return false
    }

    val libraryBookingPlansFlow: Flow<List<LibraryBookingPlanEntity>> =
        combine(
            cloudAndParcelDao.observeLibraryBookingPlans(),
            cloudAndParcelDao.observeCloudIdentityBinding(),
            preferencesFlow
        ) { plans, binding, prefs ->
            plans.filter { plan ->
                isBookingPlanVisibleToIdentity(
                    plan = plan,
                    activeCloudUserId = binding?.cloudUserId,
                    activeStudentId = prefs.savedStudentId
                )
            }
        }

    suspend fun getAllLibraryBookingPlansForAuditSnapshot(): List<LibraryBookingPlanEntity> =
        cloudAndParcelDao.getLibraryBookingPlansSnapshot()

    suspend fun createDualEngineBookingPlan(
        executionMode: BookingExecutionMode,
        targetDate: LocalDate,
        targetTimeText: String,
        preferredAreaName: String,
        preferredSeatLabel: String,
        allowAlternativeSeat: Boolean = true,
        repeatDays: Int = 1,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Result<LibraryBookingPlanEntity> = runCatching {
        CampusCloudOidcHelper.configurePersistentAuthStorageDir(
            java.io.File(appContext.filesDir, "cloud_auth_provenance")
        )
        val normalizedRepeatDays = repeatDays.coerceIn(1, 30)
        val targetDateStr = targetDate.toString()
        val prefsSnap = preferencesFlow.first()
        val activeBindingSnap = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
        val (studentNoBundlePre, delegatedBundlePre) = buildDelegatedCredentialBundle()
        val currentStudentNo = (studentNoBundlePre ?: prefsSnap.savedStudentId.orEmpty()).trim()

        // 0) 跨引擎切换与同日同人防重协调：统一按真实学校身份（学号）与明确 owner 归属判重（不同人隔离不误伤，同一学号跨账号仍严格防重！）
        val allPlansSnapshot = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
        val existingForDate = allPlansSnapshot.filter { plan ->
            isSamePersonBookingConflictCandidate(
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
                    doesCloudPlanBelongToActiveAccount(plan, activeBindingSnap?.cloudUserId, currentStudentNo) ||
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
                var cloudToken = resolveValidCloudAccessToken(forceRefresh = false)
                if (cloudToken.isBlank()) {
                    cloudToken = resolveValidCloudAccessToken(forceRefresh = true)
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
                ?: throw IllegalStateException("云端代抢（测试版）需先绑定 XPEACH 内测账号；您也可随时切换为「本机抢座 (测试版)」（无需登录云账号）")
            if (binding.peachBalanceMicropeach < executionMode.priceMicropeach) {
                throw IllegalStateException("内测防滥用积分不足（需 1 🍑 测试配额，积分仅用于内部测试防止恶意利用，无任何收费属性），请改用「本机抢座 (测试版)」")
            }
            var token = resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                token = resolveValidCloudAccessToken(forceRefresh = true)
            }
            if (token.isBlank()) {
                throw IllegalStateException("云端会话已过期或当前离线，无法创建云端代抢计划；原有的本机排队计划已为您保留")
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
                val liveStuNow = (buildDelegatedCredentialBundle().first ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
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
                    val liveStuLocked = (buildDelegatedCredentialBundle().first ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
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
                    httpTransport = cloudHttpTransportForTest
                )
            }
            if (submitOutcome is CampusCloudOidcHelper.CloudPlanSubmissionOutcome.RejectedPrePersist && submitOutcome.isAuthExpired) {
                token = resolveValidCloudAccessToken(forceRefresh = true, failedAccessToken = token)
                if (token.isNotBlank()) {
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
                    throw IllegalStateException("云端未能受理该代抢计划（${finalOutcome.reason}）；已为您恢复原有本机排队计划")
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
                        priceMicropeach = executionMode.priceMicropeach,
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
            priceMicropeach = executionMode.priceMicropeach,
            paymentState = if (executionMode.priceMicropeach == 0L) "NOT_REQUIRED" else "DEBIT_PENDING",
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
            cloudAndParcelDao.upsertLibraryBookingPlan(entity)
        }
        LibrarySeatAlarmSubsystem.schedulePlanAlarms(appContext, entity)
        if (executionMode == BookingExecutionMode.CLOUD_HOSTED) {
            repoScope.launch { runCatching { syncWithCampusCloud() } }
        } else if (executionMode == BookingExecutionMode.LOCAL_DEVICE) {
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
        entity
    }

    suspend fun fetchLibraryStudyRegions(
        bookingDate: String
    ): Result<List<LibraryStudyRegionItem>> {
        val currentToken = readCachedLibrarySeatSession()?.rawToken
            ?: (ensureLibrarySeatSession(forceRefresh = false) as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
            ?: return Result.failure(IllegalStateException("请先登录图书馆账号"))
        val firstAttempt = HautLibrarySeatAuthClient.fetchLibraryStudyRegions(currentToken, bookingDate)
        if (firstAttempt.isSuccess) return firstAttempt
        val err = firstAttempt.exceptionOrNull()
        if (err is LibrarySeatSessionExpiredException) {
            val refreshed = ensureLibrarySeatSession(forceRefresh = true)
            if (refreshed is LibrarySeatSessionState.Authenticated) {
                return HautLibrarySeatAuthClient.fetchLibraryStudyRegions(refreshed.snapshot.rawToken, bookingDate)
            }
        }
        return firstAttempt
    }

    suspend fun fetchLibraryRegionSeats(
        regionId: Long,
        bookingDate: String
    ): Result<LibraryRegionSeatsSnapshot> {
        val currentToken = readCachedLibrarySeatSession()?.rawToken
            ?: (ensureLibrarySeatSession(forceRefresh = false) as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
            ?: return Result.failure(IllegalStateException("请先登录图书馆账号"))
        val firstAttempt = HautLibrarySeatAuthClient.fetchLibraryRegionSeats(currentToken, regionId, bookingDate)
        if (firstAttempt.isSuccess) return firstAttempt
        val err = firstAttempt.exceptionOrNull()
        if (err is LibrarySeatSessionExpiredException) {
            val refreshed = ensureLibrarySeatSession(forceRefresh = true)
            if (refreshed is LibrarySeatSessionState.Authenticated) {
                return HautLibrarySeatAuthClient.fetchLibraryRegionSeats(refreshed.snapshot.rawToken, regionId, bookingDate)
            }
        }
        return firstAttempt
    }

    suspend fun advanceBookingPlanStage(
        planId: String,
        stage: TripleScheduleStage,
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
                            val cloudToken = if (validCloudJobId != null && cloudOwnerMatched) resolveValidCloudAccessToken() else ""
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
                                        jobId = validCloudJobId
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
                            val liveStuForFire = (buildDelegatedCredentialBundle().first ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
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
                                    isSamePersonBookingConflictCandidate(
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
            val token = resolveValidCloudAccessToken(forceRefresh = false)
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
                val cloudToken = resolveValidCloudAccessToken()
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
                val belongsToActiveAccount = isBookingPlanVisibleToIdentity(
                    plan = plan,
                    activeCloudUserId = activeBinding?.cloudUserId,
                    activeStudentId = activeStudentId
                )
                if (belongsToActiveAccount && plan.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")) {
                    LibrarySeatAlarmSubsystem.schedulePlanAlarms(appContext, plan)
                } else {
                    LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
                }
            }
        }
    }

    // --- Parcel Hub (Multi-Source Express Aggregation, OTP Filter & Provenance Merge) ---
    val parcelItemsFlow: Flow<List<ParcelItemEntity>> =
        cloudAndParcelDao.observeParcelItems()

    val parcelSourceConnectionsFlow: Flow<List<ParcelSourceConnectionEntity>> =
        cloudAndParcelDao.observeParcelSourceConnections()

    suspend fun upsertParcelItemFromInput(
        trackingNumberRaw: String,
        carrierName: String = "校园快递",
        providerId: String = "parcel.manual",
        pickupCodeInput: String? = null,
        stationNameInput: String? = null,
        itemTitleInput: String? = null,
        rawNotificationText: String? = null
    ): Result<ParcelItemEntity> = runCatching {
        val spec = ParcelHubEngine.enforceProviderAuthority(providerId)
        val normalizedTracking = ParcelHubEngine.normalizeTrackingNumber(trackingNumberRaw)
        val extracted = ParcelHubEngine.resolvePickupCodeFromText(rawNotificationText)
        val effectivePickup = pickupCodeInput.cleanNullableLiteral() ?: extracted?.pickupCode.cleanNullableLiteral()
        val effectiveStation = stationNameInput.cleanNullableLiteral() ?: extracted?.stationName.cleanNullableLiteral()
        val effectiveTitle = itemTitleInput.cleanNullableLiteral()
        val now = System.currentTimeMillis()

        val existing = cloudAndParcelDao.getParcelByTrackingNumber(normalizedTracking)
        val cleanExistingPickup = existing?.pickupCode.cleanNullableLiteral()
        val mergedPickup = effectivePickup ?: cleanExistingPickup
        val nextStatus = if (!mergedPickup.isNullOrBlank()) {
            ParcelLifecycleStatus.READY_FOR_PICKUP.wireName
        } else {
            existing?.status?.cleanNullLiteral(ParcelLifecycleStatus.IN_TRANSIT.wireName) ?: ParcelLifecycleStatus.IN_TRANSIT.wireName
        }

        val cleanCarrier = carrierName.cleanNullLiteral(existing?.carrierName.cleanNullLiteral("校园快递"))
        val entity = ParcelItemEntity(
            id = existing?.id ?: "prc_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            trackingNumber = normalizedTracking,
            carrierCode = cleanCarrier.uppercase().take(8),
            carrierName = cleanCarrier,
            status = nextStatus,
            pickupCode = mergedPickup,
            stationName = effectiveStation ?: existing?.stationName.cleanNullableLiteral() ?: "莲花街北苑菜鸟驿站",
            itemTitle = effectiveTitle ?: existing?.itemTitle.cleanNullableLiteral(),
            estimatedDeliveryText = if (nextStatus == ParcelLifecycleStatus.READY_FOR_PICKUP.wireName) {
                "已到达驿站/快递柜，请凭取件码取件"
            } else {
                "运输中，预计 1-2 日送达校园驿站"
            },
            sourcesJson = """[{"provider_id":"${spec.providerId}","priority":${spec.sourcePriority}}]""",
            fieldProvenanceJson = """{"provider_id":"${spec.providerId}","updated_at":$now}""",
            checkpointsJson = existing?.checkpointsJson ?: "[]",
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = now
        )
        cloudAndParcelDao.upsertParcelItem(entity)
        notifyScheduleOrPolicyChanged("PARCEL_UPSERTED")
        repoScope.launch { runCatching { syncWithCampusCloud() } }
        entity
    }

    suspend fun markParcelPickedUp(parcelId: String) {
        val list = cloudAndParcelDao.getParcelItemsSnapshot()
        val found = list.firstOrNull { it.id == parcelId } ?: return
        cloudAndParcelDao.upsertParcelItem(
            found.copy(
                status = ParcelLifecycleStatus.PICKED_UP.wireName,
                estimatedDeliveryText = "本人已取件签收",
                updatedAtEpochMillis = System.currentTimeMillis()
            )
        )
        notifyScheduleOrPolicyChanged("PARCEL_PICKED_UP")
        repoScope.launch { runCatching { syncWithCampusCloud() } }
    }

    suspend fun markAllStationParcelsPickedUp(stationName: String) {
        val now = System.currentTimeMillis()
        val list = cloudAndParcelDao.getParcelItemsSnapshot()
        var updatedCount = 0
        for (item in list) {
            if (item.status != ParcelLifecycleStatus.PICKED_UP.wireName &&
                (item.stationName ?: "校内菜鸟驿站") == stationName
            ) {
                cloudAndParcelDao.upsertParcelItem(
                    item.copy(
                        status = ParcelLifecycleStatus.PICKED_UP.wireName,
                        estimatedDeliveryText = "本人已取件签收",
                        updatedAtEpochMillis = now
                    )
                )
                updatedCount++
            }
        }
        if (updatedCount > 0) {
            notifyScheduleOrPolicyChanged("PARCEL_STATION_BATCH_PICKED_UP")
            repoScope.launch { runCatching { syncWithCampusCloud() } }
        }
    }

    suspend fun deleteParcelById(parcelId: String) {
        cloudAndParcelDao.deleteParcelItemById(parcelId)
        notifyScheduleOrPolicyChanged("PARCEL_DELETED")
    }

    // --- Xiaomi Smart Assistant Account, Phone Number SMS Binding & Express Sync ---
    fun getXiaomiAccounts(): List<XiaomiAccountCredential> =
        xiaomiAccountVault.readAccounts()

    suspend fun saveXiaomiAccountFromWebLogin(
        token: String,
        cUserId: String,
        accountId: String,
        detectedPhones: List<String>
    ): Result<XiaomiAccountCredential> = withContext(Dispatchers.IO) {
        runCatching {
            require(token.isNotBlank()) { "小米 serviceToken 不能为空" }
            val effectiveAccountId = accountId.trim().ifBlank { cUserId.trim() }
            require(effectiveAccountId.isNotBlank()) { "小米账号标识不能为空" }
            val oaid = XiaomiExpressApi.resolveOaid(appContext, effectiveAccountId)
            val vaid = XiaomiExpressApi.resolveVaid(appContext, effectiveAccountId)
            val credId = "xiaomi_$effectiveAccountId"
            val cleanPhones = detectedPhones
                .map { it.trim() }
                .filter { XiaomiPassportClient.isValidMainlandPhone(it) }
                .distinct()
            val list = xiaomiAccountVault.upsertAccount(
                XiaomiAccountCredential(
                    id = credId,
                    label = "小米账号",
                    token = token.trim(),
                    cUserId = cUserId.trim().ifBlank { effectiveAccountId },
                    accountId = effectiveAccountId,
                    oaid = oaid,
                    vaid = vaid,
                    phones = cleanPhones,
                    enabled = true,
                    updatedAtEpochMillis = System.currentTimeMillis()
                )
            )
            val saved = list.first { it.id == credId || it.accountId == effectiveAccountId }
            cloudAndParcelDao.upsertParcelSourceConnection(
                ParcelSourceConnectionEntity(
                    id = saved.id,
                    providerId = "parcel.xiaomi.personal-assistant",
                    accountAlias = "${saved.label} · ${saved.maskedPhonesSummary}",
                    authorityStatus = "PRODUCTION_ENABLED",
                    status = "ACTIVE",
                    updatedAtEpochMillis = System.currentTimeMillis()
                )
            )
            saved
        }
    }

    suspend fun sendXiaomiPhoneVerificationCode(
        accountIdOrId: String,
        phone: String
    ): Result<XiaomiApiSimpleResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cred = xiaomiAccountVault.readAccounts()
                .firstOrNull { it.id == accountIdOrId || it.accountId == accountIdOrId }
                ?: throw IllegalStateException("未找到对应的小米登录账号，请先完成小米账号登录")
            XiaomiExpressApi.sendVerificationCode(appContext, cred, phone)
        }
    }

    suspend fun verifyAndBindXiaomiPhone(
        accountIdOrId: String,
        phone: String,
        verificationCode: String
    ): Result<XiaomiAccountCredential> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanPhone = phone.trim()
            require(XiaomiPassportClient.isValidMainlandPhone(cleanPhone)) {
                "请输入有效的 11 位中国大陆手机号"
            }
            require(verificationCode.trim().isNotEmpty()) {
                "请输入短信验证码"
            }
            val cred = xiaomiAccountVault.readAccounts()
                .firstOrNull { it.id == accountIdOrId || it.accountId == accountIdOrId }
                ?: throw IllegalStateException("未找到对应的小米登录账号")
            val checkRes = XiaomiExpressApi.checkVerificationCode(
                appContext,
                cred,
                cleanPhone,
                verificationCode.trim()
            )
            if (!checkRes.ok) {
                throw IllegalStateException("验证码校验失败: ${checkRes.message}")
            }
            val nextPhones = (cred.phones + cleanPhone).distinct()
            val bindRes = XiaomiExpressApi.bindPhone(
                appContext,
                cred,
                cleanPhone,
                nextPhones,
                bind = true
            )
            if (!bindRes.ok) {
                throw IllegalStateException("绑定手机号失败: ${bindRes.message}")
            }
            val updated = xiaomiAccountVault.updateAccountPhones(cred.id, nextPhones)
                ?: throw IllegalStateException("更新本地手机号列表失败")
            cloudAndParcelDao.upsertParcelSourceConnection(
                ParcelSourceConnectionEntity(
                    id = updated.id,
                    providerId = "parcel.xiaomi.personal-assistant",
                    accountAlias = "${updated.label} · ${updated.maskedPhonesSummary}",
                    authorityStatus = "PRODUCTION_ENABLED",
                    status = "ACTIVE",
                    updatedAtEpochMillis = System.currentTimeMillis()
                )
            )
            updated
        }
    }

    suspend fun addXiaomiPhoneDirectly(
        accountIdOrId: String,
        phone: String
    ): Result<XiaomiAccountCredential> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanPhone = phone.trim()
            require(XiaomiPassportClient.isValidMainlandPhone(cleanPhone)) {
                "请输入有效的 11 位中国大陆手机号"
            }
            val cred = xiaomiAccountVault.readAccounts()
                .firstOrNull { it.id == accountIdOrId || it.accountId == accountIdOrId }
                ?: throw IllegalStateException("未找到对应的小米登录账号")
            val nextPhones = (cred.phones + cleanPhone).distinct()
            runCatching {
                XiaomiExpressApi.bindPhone(appContext, cred, cleanPhone, nextPhones, bind = true)
            }
            val updated = xiaomiAccountVault.updateAccountPhones(cred.id, nextPhones)
                ?: throw IllegalStateException("保存收件手机号失败")
            cloudAndParcelDao.upsertParcelSourceConnection(
                ParcelSourceConnectionEntity(
                    id = updated.id,
                    providerId = "parcel.xiaomi.personal-assistant",
                    accountAlias = "${updated.label} · ${updated.maskedPhonesSummary}",
                    authorityStatus = "PRODUCTION_ENABLED",
                    status = "ACTIVE",
                    updatedAtEpochMillis = System.currentTimeMillis()
                )
            )
            updated
        }
    }

    suspend fun unbindXiaomiPhone(
        accountIdOrId: String,
        phone: String
    ): Result<XiaomiAccountCredential> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanPhone = phone.trim()
            val cred = xiaomiAccountVault.readAccounts()
                .firstOrNull { it.id == accountIdOrId || it.accountId == accountIdOrId }
                ?: throw IllegalStateException("未找到对应的小米登录账号")
            val remaining = cred.phones.filterNot { it == cleanPhone }
            runCatching {
                XiaomiExpressApi.bindPhone(appContext, cred, cleanPhone, remaining, bind = false)
            }
            val updated = xiaomiAccountVault.updateAccountPhones(cred.id, remaining)
                ?: throw IllegalStateException("解绑手机号更新失败")
            cloudAndParcelDao.upsertParcelSourceConnection(
                ParcelSourceConnectionEntity(
                    id = updated.id,
                    providerId = "parcel.xiaomi.personal-assistant",
                    accountAlias = "${updated.label} · ${updated.maskedPhonesSummary}",
                    authorityStatus = "PRODUCTION_ENABLED",
                    status = "ACTIVE",
                    updatedAtEpochMillis = System.currentTimeMillis()
                )
            )
            updated
        }
    }

    suspend fun removeXiaomiAccount(accountIdOrId: String) = withContext(Dispatchers.IO) {
        val current = xiaomiAccountVault.readAccounts()
            .firstOrNull { it.id == accountIdOrId || it.accountId == accountIdOrId }
        xiaomiAccountVault.removeAccount(accountIdOrId)
        if (current != null) {
            cloudAndParcelDao.deleteParcelSourceConnection(current.id)
        } else {
            cloudAndParcelDao.deleteParcelSourceConnection(accountIdOrId)
        }
    }

    suspend fun syncXiaomiExpressPackages(): Result<XiaomiSyncSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val accounts = xiaomiAccountVault.readAccounts().filter { it.enabled }
            if (accounts.isEmpty()) {
                throw IllegalStateException("尚未登录小米账号，请先点击「手机号/扫码登录小米账号」")
            }
            val activeWithPhones = accounts.filter { it.phones.isNotEmpty() }
            if (activeWithPhones.isEmpty()) {
                throw IllegalStateException("已登录小米账号，但尚未绑定收件手机号。请点击「绑定/管理收件手机号」添加手机号后再同步")
            }
            var totalFetched = 0
            var readyCount = 0
            val now = System.currentTimeMillis()
            for (cred in activeWithPhones) {
                val remoteItems = XiaomiExpressApi.fetchExpressList(appContext, cred)
                for (dto in remoteItems) {
                    val normalizedTracking = runCatching {
                        ParcelHubEngine.normalizeTrackingNumber(dto.mailNo)
                    }.getOrElse { dto.mailNo.trim().uppercase() }
                    val existing = cloudAndParcelDao.getParcelByTrackingNumber(normalizedTracking)
                    val mergedPickup = dto.pickupCode.cleanNullableLiteral() ?: existing?.pickupCode.cleanNullableLiteral()
                    val mergedStation = dto.stationName.cleanNullableLiteral()
                        ?: existing?.stationName.cleanNullableLiteral()
                        ?: "校园快递服务点"
                    val effectiveStatus = when {
                        existing?.status == ParcelLifecycleStatus.PICKED_UP.wireName ->
                            ParcelLifecycleStatus.PICKED_UP.wireName
                        !mergedPickup.isNullOrBlank() && dto.lifecycleStatus != ParcelLifecycleStatus.PICKED_UP ->
                            ParcelLifecycleStatus.READY_FOR_PICKUP.wireName
                        else -> dto.lifecycleStatus.wireName
                    }
                    if (effectiveStatus == ParcelLifecycleStatus.READY_FOR_PICKUP.wireName) {
                        readyCount++
                    }
                    val checkpointsArr = org.json.JSONArray()
                    for (pt in dto.checkpoints) {
                        checkpointsArr.put(
                            org.json.JSONObject()
                                .put("desc", pt.desc.cleanNullLiteral())
                                .put("time", pt.time.cleanNullLiteral())
                        )
                    }
                    val provenanceObj = org.json.JSONObject()
                        .put("provider_id", "parcel.xiaomi.personal-assistant")
                        .put("xiaomi_account_id", cred.id)
                        .put("cp_code", dto.cpCode.cleanNullLiteral())
                        .put("provider", dto.provider.cleanNullLiteral())
                        .put("state_num", dto.stateNum)
                        .put("latest_time", dto.latestTime.cleanNullLiteral())
                        .put("phone", dto.phone.cleanNullableLiteral() ?: cred.phones.firstOrNull().orEmpty())
                        .put("query_channel", dto.queryChannel.cleanNullLiteral())
                        .put("updated_at", now)

                    val cleanCompany = dto.companyName.cleanNullLiteral(existing?.carrierName.cleanNullLiteral("校园快递"))
                    val cleanStateLabel = dto.stateName.cleanNullLiteral(dto.lifecycleStatus.label)
                    val entity = ParcelItemEntity(
                        id = existing?.id ?: "prc_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                        trackingNumber = normalizedTracking,
                        carrierCode = dto.cpCode.cleanNullLiteral("XIAOMI").uppercase().take(12),
                        carrierName = cleanCompany,
                        status = effectiveStatus,
                        pickupCode = mergedPickup,
                        stationName = mergedStation,
                        itemTitle = existing?.itemTitle.cleanNullableLiteral() ?: "$cleanCompany · $cleanStateLabel",
                        estimatedDeliveryText = dto.latestText.cleanNullLiteral().ifBlank {
                            if (dto.latestTime.cleanNullLiteral().isNotBlank()) "更新于 ${dto.latestTime.cleanNullLiteral()}" else "来自小米智能助理"
                        },
                        sourcesJson = """[{"provider_id":"parcel.xiaomi.personal-assistant","priority":95,"account":"${cred.id}"}]""",
                        fieldProvenanceJson = provenanceObj.toString(),
                        checkpointsJson = if (checkpointsArr.length() > 0) checkpointsArr.toString() else (existing?.checkpointsJson ?: "[]"),
                        createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                        updatedAtEpochMillis = now
                    )
                    cloudAndParcelDao.upsertParcelItem(entity)
                    totalFetched++
                }
            }
            val totalPhones = activeWithPhones.sumOf { it.phones.size }
            notifyScheduleOrPolicyChanged("XIAOMI_PARCEL_SYNCED")
            repoScope.launch { runCatching { syncWithCampusCloud() } }
            XiaomiSyncSummary(
                accountCount = activeWithPhones.size,
                phoneCount = totalPhones,
                fetchedParcelCount = totalFetched,
                readyPickupCount = readyCount,
                message = "小米智能助理同步成功：已查询 ${activeWithPhones.size} 个账号（共 $totalPhones 个手机号），同步 $totalFetched 件快递（待取 $readyCount 件）"
            )
        }
    }

    suspend fun fetchXiaomiParcelDetail(parcelId: String): Result<List<XiaomiTrackPoint>> = withContext(Dispatchers.IO) {
        runCatching {
            val parcel = cloudAndParcelDao.getParcelItemsSnapshot().firstOrNull { it.id == parcelId }
                ?: throw IllegalArgumentException("未找到该包裹记录")
            val prov = runCatching { org.json.JSONObject(parcel.fieldProvenanceJson) }.getOrNull()
            val accountId = prov?.optString("xiaomi_account_id").orEmpty()
            val accounts = xiaomiAccountVault.readAccounts()
            val cred = accounts.firstOrNull { it.id == accountId || it.accountId == accountId }
                ?: accounts.firstOrNull { it.enabled && it.phones.isNotEmpty() }
                ?: throw IllegalStateException("未找到可用的小米登录账号")
            val detail = XiaomiExpressApi.fetchExpressDetail(
                context = appContext,
                cred = cred,
                mailNo = parcel.trackingNumber,
                cpCode = prov?.optString("cp_code")?.takeIf { it.isNotBlank() } ?: parcel.carrierCode,
                companyName = parcel.carrierName,
                provider = prov?.optString("provider"),
                stateNum = prov?.optInt("state_num", 0) ?: 0,
                latestTime = prov?.optString("latest_time"),
                phone = prov?.optString("phone")?.takeIf { it.isNotBlank() } ?: cred.phones.firstOrNull(),
                queryChannel = prov?.optString("query_channel")
            )
            if (detail.points.isNotEmpty()) {
                val arr = org.json.JSONArray()
                for (pt in detail.points) {
                    arr.put(org.json.JSONObject().put("desc", pt.desc).put("time", pt.time))
                }
                val latestDesc = detail.points.firstOrNull()?.desc ?: parcel.estimatedDeliveryText
                val extracted = ParcelHubEngine.resolvePickupCodeFromText(
                    detail.points.joinToString(" ") { it.desc }
                )
                cloudAndParcelDao.upsertParcelItem(
                    parcel.copy(
                        pickupCode = extracted?.pickupCode ?: parcel.pickupCode,
                        stationName = extracted?.stationName ?: parcel.stationName,
                        status = when {
                            detail.isReceived -> ParcelLifecycleStatus.PICKED_UP.wireName
                            extracted != null -> ParcelLifecycleStatus.READY_FOR_PICKUP.wireName
                            else -> parcel.status
                        },
                        estimatedDeliveryText = latestDesc,
                        checkpointsJson = arr.toString(),
                        updatedAtEpochMillis = System.currentTimeMillis()
                    )
                )
            }
            detail.points
        }
    }

    // --- Unified Extension Plugins (dis-extension/v2 + dis-campus-plugin/v1) ---
    val installedExtensionPluginsFlow: Flow<List<InstalledExtensionPluginEntity>> =
        cloudAndParcelDao.observeInstalledExtensionPlugins()

    suspend fun ensureDefaultReferencePluginsInstalled() {
        runCatching {
            cloudAndParcelDao.deleteInstalledExtensionPlugin("org.example.hello-campus")
            cloudAndParcelDao.deleteInstalledExtensionPlugin("org.example.parcel-station")
        }
    }

    suspend fun upsertExtensionPlugin(entity: InstalledExtensionPluginEntity) {
        cloudAndParcelDao.upsertInstalledExtensionPlugin(entity)
    }

    suspend fun removeExtensionPlugin(pluginId: String) {
        cloudAndParcelDao.deleteInstalledExtensionPlugin(pluginId)
    }

    companion object {
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
