package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.core.edit
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.domain.ReminderRevision
import cn.edu.haut.campus.core.model.AcademicTermResolver
import cn.edu.haut.campus.core.model.HomeCardModule
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.core.model.PresetMirrorNode
import cn.edu.haut.campus.core.model.ProxyMode
import cn.edu.haut.campus.core.model.ThemeVisualDefaults
import cn.edu.haut.campus.core.model.WidgetInstanceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * 应用偏好、主题/壁纸、首页卡片、网络代理、提醒外的功能开关、餐饮评价、
 * 小组件默认配置与校区/自定义区划存储。
 * 教务凭据与云身份屏障仍经运行时端口复用后续批次所有者，行为顺序不变。
 */
internal class AppPreferencesStore(
    private val environment: CampusRepositoryEnvironment,
    private val runtimePort: CampusServicesRuntimePort
) {
    private val database = environment.database
    private val dao = environment.dao
    private val preferences = environment.preferences
    private val supportsHautServices: Boolean get() = environment.supportsHautServices

    val widgetConfigsFlow: Flow<List<WidgetInstanceConfig>> =
        dao.observeWidgetConfigs().map { list -> list.map { it.toDomain() } }

    private suspend fun migrateLegacyAcademicCredentialsIfNeeded() {
        runtimePort.migrateLegacyAcademicCredentialsIfNeeded()
    }

    private suspend fun notifyScheduleOrPolicyChanged(reason: String) {
        environment.notifyScheduleOrPolicyChanged(reason)
    }

    val preferencesFlow: Flow<AppPreferencesState> = preferences.data
        .onStart { migrateLegacyAcademicCredentialsIfNeeded() }
        .map { prefs ->
        val credentialResult = prefs[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID]?.let { accountId ->
            runtimePort.readAcademicCredential(accountId)
        } ?: AcademicCredentialReadResult.Empty
        val savedStudentId = (credentialResult as? AcademicCredentialReadResult.Available)
            ?.credentials?.use { it.studentId }
        val requiresReauthentication = prefs[CampusRepositoryPrefKeys.ACADEMIC_REAUTH_REQUIRED] == true ||
            credentialResult == AcademicCredentialReadResult.ReauthenticationRequired ||
            (prefs[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] != null && savedStudentId == null)
        val reminderConfig = extractReminderConfig(prefs)
        val rawOrder = prefs[CampusRepositoryPrefKeys.HOME_CARD_ORDER]
            ?.split(",")
            ?.map { it.trim() }
            .orEmpty()
        val normalizedOrder = HomeCardModule.normalizeOrderIds(rawOrder)
        val hiddenSet = HomeCardModule.normalizeHiddenIds(
            prefs[CampusRepositoryPrefKeys.HIDDEN_HOME_CARDS]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet()
        )
        val fullWidthSet = HomeCardModule.normalizeFullWidthIds(
            prefs[CampusRepositoryPrefKeys.FULL_WIDTH_HOME_CARDS]
                ?.let { raw ->
                    if (raw == HomeCardModule.EMPTY_SET_SENTINEL) setOf(HomeCardModule.EMPTY_SET_SENTINEL)
                    else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                }
        )

        AppPreferencesState(
            gpaPrivacyShieldEnabled = prefs[CampusRepositoryPrefKeys.GPA_PRIVACY] ?: false,
            useDynamicColor = prefs[CampusRepositoryPrefKeys.DYNAMIC_COLOR] ?: false,
            themeMode = prefs[CampusRepositoryPrefKeys.THEME_MODE] ?: "SYSTEM",
            uiDesignStyle = prefs[CampusRepositoryPrefKeys.UI_DESIGN_STYLE]
                ?: if (prefs[CampusRepositoryPrefKeys.THEME_PALETTE_KEY] == "LIQUID_GLASS") "LIQUID_GLASS" else "FRESH_BREEZE",
            themePaletteKey = prefs[CampusRepositoryPrefKeys.THEME_PALETTE_KEY] ?: "HAUT_BLUE",
            customThemeSeedArgb = run {
                val paletteKey = prefs[CampusRepositoryPrefKeys.THEME_PALETTE_KEY] ?: "HAUT_BLUE"
                val storedSeed = prefs[CampusRepositoryPrefKeys.CUSTOM_THEME_SEED_ARGB]
                when {
                    paletteKey == "HAUT_BLUE" && (storedSeed == null || storedSeed == 0xFF0066CCL) -> 0xFF053968L
                    storedSeed != null -> storedSeed
                    else -> 0xFF053968L
                }
            },
            customWallpaperPath = prefs[CampusRepositoryPrefKeys.CUSTOM_WALLPAPER_PATH]?.takeIf { it.isNotBlank() },
            wallpaperAlpha = ThemeVisualDefaults.clampWallpaperAlpha(
                prefs[CampusRepositoryPrefKeys.WALLPAPER_ALPHA] ?: ThemeVisualDefaults.DEFAULT_WALLPAPER_ALPHA
            ),
            wallpaperBlurDp = ThemeVisualDefaults.clampWallpaperBlurDp(
                prefs[CampusRepositoryPrefKeys.WALLPAPER_BLUR_DP] ?: ThemeVisualDefaults.DEFAULT_WALLPAPER_BLUR_DP
            ),
            wallpaperCardAlpha = ThemeVisualDefaults.clampWallpaperCardAlpha(
                prefs[CampusRepositoryPrefKeys.WALLPAPER_CARD_ALPHA] ?: ThemeVisualDefaults.DEFAULT_WALLPAPER_CARD_ALPHA
            ),
            extractedWallpaperColors = prefs[CampusRepositoryPrefKeys.EXTRACTED_WALLPAPER_COLORS]
                ?.split(",")
                ?.mapNotNull { it.trim().toLongOrNull() }
                .orEmpty(),
            homeCardOrder = normalizedOrder,
            hiddenHomeCards = hiddenSet,
            fullWidthHomeCards = fullWidthSet,
            timelineZoomScale = ThemeVisualDefaults.clampTimelineZoomScale(
                prefs[CampusRepositoryPrefKeys.TIMELINE_SCALE] ?: ThemeVisualDefaults.DEFAULT_TIMELINE_ZOOM_SCALE
            ),
            widgetDefaultCompact = prefs[CampusRepositoryPrefKeys.WIDGET_DEFAULT_COMPACT] ?: false,
            widgetDefaultPrivacyMode = prefs[CampusRepositoryPrefKeys.WIDGET_DEFAULT_PRIVACY_MODE] ?: false,
            officialGpaValuePlain = prefs[CampusRepositoryPrefKeys.OFFICIAL_GPA],
            officialGpaNote = prefs[CampusRepositoryPrefKeys.OFFICIAL_GPA_NOTE],
            hasSeededInitialMockData = prefs[CampusRepositoryPrefKeys.SEEDED] ?: false,
            hasCompletedOnboarding = prefs[CampusRepositoryPrefKeys.ONBOARDING_COMPLETED] ?: false,
            autoSyncEnabled = prefs[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED] ?: true,
            autoCheckUpdateEnabled = prefs[CampusRepositoryPrefKeys.AUTO_CHECK_UPDATE] ?: true,
            savedStudentId = savedStudentId,
            hasSavedCredentials = savedStudentId != null && !requiresReauthentication,
            academicReauthenticationRequired = requiresReauthentication,
            syncXnm = prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM] ?: AcademicTermResolver.defaultAcademicYearCode(),
            syncXqm = prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM] ?: AcademicTermResolver.defaultSemesterCode(),
            lastAutoSyncEpochMillis = prefs[CampusRepositoryPrefKeys.LAST_AUTO_SYNC_MS],
            customChannelUrls = decodeKeyValueMap(prefs[CampusRepositoryPrefKeys.CUSTOM_CHANNEL_URLS]),
            customClubGroupNumbers = decodeKeyValueMap(prefs[CampusRepositoryPrefKeys.CUSTOM_CLUB_GROUPS]),
            campusCommunityCatalog = CampusCloudOidcHelper.parseCampusContentJsonString(
                prefs[CampusRepositoryPrefKeys.CLOUD_CAMPUS_CONTENT_JSON]
            ) ?: cn.edu.haut.campus.core.model.CampusCommunityCloudCatalog(),
            lastCampusContentSyncEpochMillis = prefs[CampusRepositoryPrefKeys.CLOUD_CAMPUS_CONTENT_SYNCED_MS],
            reminderConfig = reminderConfig,
            reminderPolicyRevision = prefs[CampusRepositoryPrefKeys.REMINDER_POLICY_REVISION]
                ?: ReminderRevision.computePolicyRevision(
                    enabled = reminderConfig.enabled,
                    classLeadMinutes = reminderConfig.classLeadMinutes,
                    maxSnoozes = reminderConfig.classSnoozeMaxCount,
                    cutoffMinutesBeforeStart = reminderConfig.classSnoozeCutoffMinutes,
                    examDayEnabled = reminderConfig.examDayBeforeEnabled,
                    examHourEnabled = reminderConfig.examHourBeforeEnabled
                ),
            lastReminderReconcileEpochMillis = prefs[CampusRepositoryPrefKeys.LAST_REMINDER_RECONCILE_MS],
            lastReminderReconcileSummary = prefs[CampusRepositoryPrefKeys.LAST_REMINDER_RECONCILE_SUMMARY],
            reminderDiagnosticLogs = prefs[CampusRepositoryPrefKeys.REMINDER_DIAGNOSTIC_LOGS]
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                .orEmpty(),
            utilitiesEnabled = supportsHautServices && (prefs[CampusRepositoryPrefKeys.FF_UTILITIES_ENABLED] ?: true),
            libraryNativeEnabled = supportsHautServices && (prefs[CampusRepositoryPrefKeys.FF_LIBRARY_NATIVE_ENABLED] ?: true),
            librarySeatEnabled = supportsHautServices && (prefs[CampusRepositoryPrefKeys.FF_LIBRARY_SEAT_ENABLED] ?: true),
            focusEnabled = prefs[CampusRepositoryPrefKeys.FF_FOCUS_ENABLED] ?: true,
            showTodayFocusQuickPill = prefs[CampusRepositoryPrefKeys.FF_TODAY_FOCUS_PILL] ?: true,
            timetableInterchangeEnabled = prefs[CampusRepositoryPrefKeys.FF_TIMETABLE_INTERCHANGE] ?: true,
            allowUnverifiedUtilityTransport = prefs[CampusRepositoryPrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT] ?: false,
            lastUtilitySampleEpochMillis = prefs[CampusRepositoryPrefKeys.LAST_UTILITY_SAMPLE_MS],
            utilityDailyNotifyEnabled = prefs[CampusRepositoryPrefKeys.UTILITY_DAILY_NOTIFY_ENABLED] ?: true,
            utilityDailyNotifyHour = (prefs[CampusRepositoryPrefKeys.UTILITY_DAILY_NOTIFY_HOUR] ?: 20).coerceIn(8, 23),
            utilityLowBalanceThreshold = (prefs[CampusRepositoryPrefKeys.UTILITY_LOW_BALANCE_THRESHOLD] ?: 15f).coerceIn(5f, 100f),
            lastUtilityDailyNotifyDate = prefs[CampusRepositoryPrefKeys.LAST_UTILITY_DAILY_NOTIFY_DATE],
            mealRecommendEnabled = supportsHautServices && (prefs[CampusRepositoryPrefKeys.MEAL_RECOMMEND_ENABLED] ?: true),
            lastMealRecommendBlockKey = prefs[CampusRepositoryPrefKeys.LAST_MEAL_RECOMMEND_BLOCK_KEY],
            canteenDishReviews = cn.edu.haut.campus.core.domain.CanteenDishReviewCodec.decodeList(
                prefs[CampusRepositoryPrefKeys.CANTEEN_DISH_REVIEWS]
            ),
            campusMomentsSnapshot = run {
                val rawSnap = CampusCloudOidcHelper.parseMomentsSnapshotJsonString(
                    prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON]
                ) ?: cn.edu.haut.campus.core.model.CampusMomentsSnapshot()
                val hasCloudSessionToken = !prefs[CampusRepositoryPrefKeys.CLOUD_ACCESS_TOKEN].isNullOrBlank()
                val baseSnap = if (hasCloudSessionToken) {
                    rawSnap
                } else {
                    CampusCloudOidcHelper.sanitizeMomentsSnapshotForViewerReset(rawSnap)
                }
                baseSnap.copy(
                    favoritePostIds = if (hasCloudSessionToken) decodeCsvSet(prefs[CampusRepositoryPrefKeys.MOMENTS_FAVORITE_POST_IDS]) else emptySet(),
                    favoriteBookIds = if (hasCloudSessionToken) decodeCsvSet(prefs[CampusRepositoryPrefKeys.MOMENTS_FAVORITE_BOOK_IDS]) else emptySet(),
                    myPostIds = if (hasCloudSessionToken) decodeCsvSet(prefs[CampusRepositoryPrefKeys.MOMENTS_MY_POST_IDS]) else emptySet(),
                    myBookIds = if (hasCloudSessionToken) decodeCsvSet(prefs[CampusRepositoryPrefKeys.MOMENTS_MY_BOOK_IDS]) else emptySet(),
                    myTagCodes = if (hasCloudSessionToken) decodeCsvSet(prefs[CampusRepositoryPrefKeys.MOMENTS_MY_TAG_CODES]) else emptySet()
                )
            },
            selectedFocusMinutes = (prefs[CampusRepositoryPrefKeys.SELECTED_FOCUS_MINUTES] ?: 25).coerceIn(1, 180),
            networkProxyConfig = extractNetworkProxyConfig(prefs),
            selectedCampusId = prefs[CampusRepositoryPrefKeys.SELECTED_CAMPUS_ID] ?: "auto",
            customCampusZonesJson = prefs[CampusRepositoryPrefKeys.CUSTOM_CAMPUS_ZONES_JSON] ?: "[]"
        )
    }.flowOn(Dispatchers.IO)


    private fun extractNetworkProxyConfig(prefs: androidx.datastore.preferences.core.Preferences): NetworkProxyConfig {
        return NetworkProxyConfig(
            mode = ProxyMode.fromKey(prefs[CampusRepositoryPrefKeys.PROXY_MODE]),
            presetMirrorKey = prefs[CampusRepositoryPrefKeys.PROXY_PRESET_MIRROR_KEY] ?: PresetMirrorNode.GHFAST_TOP.key,
            customReverseProxyPrefix = prefs[CampusRepositoryPrefKeys.PROXY_CUSTOM_PREFIX] ?: "",
            proxyHost = prefs[CampusRepositoryPrefKeys.PROXY_HOST]?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
            proxyPort = (prefs[CampusRepositoryPrefKeys.PROXY_PORT] ?: 7890).coerceIn(1, 65535),
            autoFallbackMirror = prefs[CampusRepositoryPrefKeys.PROXY_AUTO_FALLBACK] ?: true,
            customCloudApiBaseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(prefs[CampusRepositoryPrefKeys.CLOUD_API_BASE_URL])
        )
    }


    suspend fun completeOnboarding() {
        preferences.edit {
            it[CampusRepositoryPrefKeys.ONBOARDING_COMPLETED] = true
            it[CampusRepositoryPrefKeys.CLEAN_BASE_V2] = true
        }
    }

    suspend fun resetOnboardingForGuide() {
        preferences.edit {
            it[CampusRepositoryPrefKeys.ONBOARDING_COMPLETED] = false
        }
    }


    suspend fun setUseDynamicColor(enabled: Boolean) {
        preferences.edit { it[CampusRepositoryPrefKeys.DYNAMIC_COLOR] = enabled }
        notifyScheduleOrPolicyChanged("THEME_UPDATED")
    }

    suspend fun setThemeMode(mode: String) {
        preferences.edit { it[CampusRepositoryPrefKeys.THEME_MODE] = mode }
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
            prefs[CampusRepositoryPrefKeys.UI_DESIGN_STYLE] = normalized
            val currentPalette = prefs[CampusRepositoryPrefKeys.THEME_PALETTE_KEY]
            when (normalized) {
                "FRESH_BREEZE" -> {
                    if (currentPalette == null || currentPalette == "LIQUID_GLASS" || currentPalette == "HAUT_BLUE") {
                        prefs[CampusRepositoryPrefKeys.THEME_PALETTE_KEY] = "MINT_BREEZE"
                    }
                }
                "CREAM_PASTEL" -> {
                    if (currentPalette == null || currentPalette == "LIQUID_GLASS" || currentPalette == "HAUT_BLUE" || currentPalette == "MINT_BREEZE") {
                        prefs[CampusRepositoryPrefKeys.THEME_PALETTE_KEY] = "PEACH_OOLONG"
                    }
                }
                "FLUTTER_M3" -> {
                    if (currentPalette == "LIQUID_GLASS") {
                        prefs[CampusRepositoryPrefKeys.THEME_PALETTE_KEY] = "HAUT_BLUE"
                    }
                }
                "LIQUID_GLASS" -> {
                    if (prefs[CampusRepositoryPrefKeys.WALLPAPER_CARD_ALPHA] == null) {
                        prefs[CampusRepositoryPrefKeys.WALLPAPER_CARD_ALPHA] = ThemeVisualDefaults.DEFAULT_WALLPAPER_CARD_ALPHA
                    }
                    if (prefs[CampusRepositoryPrefKeys.WALLPAPER_BLUR_DP] == null) {
                        prefs[CampusRepositoryPrefKeys.WALLPAPER_BLUR_DP] = ThemeVisualDefaults.DEFAULT_WALLPAPER_BLUR_DP
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
            prefs[CampusRepositoryPrefKeys.UI_DESIGN_STYLE] = normalizedStyle
            prefs[CampusRepositoryPrefKeys.THEME_PALETTE_KEY] = paletteKey
            prefs[CampusRepositoryPrefKeys.DYNAMIC_COLOR] = false
            if (normalizedStyle == "LIQUID_GLASS") {
                if (prefs[CampusRepositoryPrefKeys.WALLPAPER_CARD_ALPHA] == null) {
                    prefs[CampusRepositoryPrefKeys.WALLPAPER_CARD_ALPHA] = ThemeVisualDefaults.DEFAULT_WALLPAPER_CARD_ALPHA
                }
                if (prefs[CampusRepositoryPrefKeys.WALLPAPER_BLUR_DP] == null) {
                    prefs[CampusRepositoryPrefKeys.WALLPAPER_BLUR_DP] = ThemeVisualDefaults.DEFAULT_WALLPAPER_BLUR_DP
                }
            }
            if (clearWallpaper) {
                prefs.remove(CampusRepositoryPrefKeys.CUSTOM_WALLPAPER_PATH)
            }
        }
        notifyScheduleOrPolicyChanged("THEME_UPDATED")
    }

    suspend fun setThemePalette(paletteKey: String, customSeedArgb: Long? = null) {
        preferences.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.THEME_PALETTE_KEY] = paletteKey
            prefs[CampusRepositoryPrefKeys.DYNAMIC_COLOR] = false
            if (paletteKey == "LIQUID_GLASS") {
                prefs[CampusRepositoryPrefKeys.UI_DESIGN_STYLE] = "LIQUID_GLASS"
                val curCardAlpha = prefs[CampusRepositoryPrefKeys.WALLPAPER_CARD_ALPHA]
                if (curCardAlpha == null || curCardAlpha > 0.85f) {
                    prefs[CampusRepositoryPrefKeys.WALLPAPER_CARD_ALPHA] = ThemeVisualDefaults.DEFAULT_WALLPAPER_CARD_ALPHA
                }
                if (prefs[CampusRepositoryPrefKeys.WALLPAPER_BLUR_DP] == null) {
                    prefs[CampusRepositoryPrefKeys.WALLPAPER_BLUR_DP] = ThemeVisualDefaults.DEFAULT_WALLPAPER_BLUR_DP
                }
            }
            if (customSeedArgb != null) {
                prefs[CampusRepositoryPrefKeys.CUSTOM_THEME_SEED_ARGB] = customSeedArgb
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
            prefs[CampusRepositoryPrefKeys.CUSTOM_WALLPAPER_PATH] = localPath
            prefs[CampusRepositoryPrefKeys.THEME_PALETTE_KEY] = "CUSTOM"
            prefs[CampusRepositoryPrefKeys.CUSTOM_THEME_SEED_ARGB] = primarySeedArgb
            prefs[CampusRepositoryPrefKeys.DYNAMIC_COLOR] = false
            prefs[CampusRepositoryPrefKeys.EXTRACTED_WALLPAPER_COLORS] = extractedSwatches.joinToString(",")
            if (enableLiquidGlass) {
                prefs[CampusRepositoryPrefKeys.UI_DESIGN_STYLE] = "LIQUID_GLASS"
            }
        }
        notifyScheduleOrPolicyChanged("THEME_UPDATED")
    }

    suspend fun saveExtractedDesktopSwatches(
        primarySeedArgb: Long,
        extractedSwatches: List<Long>
    ) {
        preferences.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.THEME_PALETTE_KEY] = "CUSTOM"
            prefs[CampusRepositoryPrefKeys.CUSTOM_THEME_SEED_ARGB] = primarySeedArgb
            prefs[CampusRepositoryPrefKeys.DYNAMIC_COLOR] = false
            prefs[CampusRepositoryPrefKeys.EXTRACTED_WALLPAPER_COLORS] = extractedSwatches.joinToString(",")
        }
        notifyScheduleOrPolicyChanged("THEME_UPDATED")
    }

    suspend fun updateWallpaperVisualParams(
        alpha: Float? = null,
        blurDp: Float? = null,
        cardAlpha: Float? = null
    ) {
        preferences.edit { prefs ->
            alpha?.let { prefs[CampusRepositoryPrefKeys.WALLPAPER_ALPHA] = ThemeVisualDefaults.clampWallpaperAlpha(it) }
            blurDp?.let { prefs[CampusRepositoryPrefKeys.WALLPAPER_BLUR_DP] = ThemeVisualDefaults.clampWallpaperBlurDp(it) }
            cardAlpha?.let { prefs[CampusRepositoryPrefKeys.WALLPAPER_CARD_ALPHA] = ThemeVisualDefaults.clampWallpaperCardAlpha(it) }
        }
    }

    suspend fun resetWallpaperVisualParams() {
        preferences.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.WALLPAPER_ALPHA] = ThemeVisualDefaults.DEFAULT_WALLPAPER_ALPHA
            prefs[CampusRepositoryPrefKeys.WALLPAPER_BLUR_DP] = ThemeVisualDefaults.DEFAULT_WALLPAPER_BLUR_DP
            prefs[CampusRepositoryPrefKeys.WALLPAPER_CARD_ALPHA] = ThemeVisualDefaults.DEFAULT_WALLPAPER_CARD_ALPHA
        }
        notifyScheduleOrPolicyChanged("THEME_UPDATED")
    }

    suspend fun clearCustomWallpaperTheme() {
        preferences.edit { prefs ->
            val oldPath = prefs[CampusRepositoryPrefKeys.CUSTOM_WALLPAPER_PATH]
            if (!oldPath.isNullOrBlank()) {
                runCatching { java.io.File(oldPath).delete() }
            }
            prefs.remove(CampusRepositoryPrefKeys.CUSTOM_WALLPAPER_PATH)
            prefs.remove(CampusRepositoryPrefKeys.EXTRACTED_WALLPAPER_COLORS)
            if (prefs[CampusRepositoryPrefKeys.THEME_PALETTE_KEY] == "CUSTOM") {
                prefs[CampusRepositoryPrefKeys.THEME_PALETTE_KEY] =
                    if (prefs[CampusRepositoryPrefKeys.UI_DESIGN_STYLE] == "LIQUID_GLASS") "LIQUID_GLASS" else "HAUT_BLUE"
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
            prefs[CampusRepositoryPrefKeys.HOME_CARD_ORDER] = normalizedOrder.joinToString(",")
            prefs[CampusRepositoryPrefKeys.HIDDEN_HOME_CARDS] = normalizedHidden.joinToString(",")
            prefs[CampusRepositoryPrefKeys.FULL_WIDTH_HOME_CARDS] =
                if (fullWidth.isEmpty()) HomeCardModule.EMPTY_SET_SENTINEL else fullWidth.joinToString(",")
        }
    }

    suspend fun resetHomeCardLayout() {
        preferences.edit { prefs ->
            prefs.remove(CampusRepositoryPrefKeys.HOME_CARD_ORDER)
            prefs.remove(CampusRepositoryPrefKeys.HIDDEN_HOME_CARDS)
            prefs.remove(CampusRepositoryPrefKeys.FULL_WIDTH_HOME_CARDS)
        }
    }

    suspend fun getActiveThemeAccentArgb(): Int {
        val prefs = preferences.data.first()
        val key = prefs[CampusRepositoryPrefKeys.THEME_PALETTE_KEY] ?: "HAUT_BLUE"
        val custom = prefs[CampusRepositoryPrefKeys.CUSTOM_THEME_SEED_ARGB] ?: 0xFF053968
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
        preferences.edit { it[CampusRepositoryPrefKeys.AUTO_CHECK_UPDATE] = enabled }
    }

    suspend fun getNetworkProxyConfigSnapshot(): NetworkProxyConfig {
        val prefs = preferences.data.first()
        return extractNetworkProxyConfig(prefs)
    }

    suspend fun updateNetworkProxyConfig(config: NetworkProxyConfig) {
        // CLOUD_API_BASE_URL 属于身份会话快照成分：其变更必须过提交屏障（全局锁序 booking→identity），
        // 保证同步在锁内复核身份/世代/base-url 后，不存在"复核通过→写入前"间被换址的窗口。
        // 真实换址（同一规范化口径下 old != new）还必须在同一锁内推进身份世代：
        // 仅有 base-url 相等性复核挡不住 A→B→A——回到原址后迟到的在途响应会误判快照仍有效。
        // 世代推进即令在途会话全部失效（非解绑，不留 tombstone）；同值保存、仅改代理选项绝不推进。
        runtimePort.withCloudIdentityBarrier {
                var baseUrlChanged = false
                preferences.edit { prefs ->
                    // 与身份快照同一规范化口径（normalizeCloudApiBaseUrl 幂等，等价于快照读到的 apiBaseUrl）
                    val oldBase = NetworkProxyConfig.normalizeCloudApiBaseUrl(prefs[CampusRepositoryPrefKeys.CLOUD_API_BASE_URL])
                    val newBase = NetworkProxyConfig.normalizeCloudApiBaseUrl(config.customCloudApiBaseUrl)
                    baseUrlChanged = oldBase != newBase
                    prefs[CampusRepositoryPrefKeys.PROXY_MODE] = config.mode.key
                    prefs[CampusRepositoryPrefKeys.PROXY_PRESET_MIRROR_KEY] = config.presetMirrorKey
                    prefs[CampusRepositoryPrefKeys.PROXY_CUSTOM_PREFIX] = config.customReverseProxyPrefix.trim()
                    prefs[CampusRepositoryPrefKeys.PROXY_HOST] = config.proxyHost.trim().ifBlank { "127.0.0.1" }
                    prefs[CampusRepositoryPrefKeys.PROXY_PORT] = config.proxyPort.coerceIn(1, 65535)
                    prefs[CampusRepositoryPrefKeys.PROXY_AUTO_FALLBACK] = config.autoFallbackMirror
                    prefs[CampusRepositoryPrefKeys.CLOUD_API_BASE_URL] = newBase
                }
                if (baseUrlChanged) {
                    // 同一 owner 保留：这是"换址失效"而非解绑，绝不写 tombstone
                    runtimePort.bumpCloudIdentityGenerationForBaseUrlChange(
                        runtimePort.currentCloudUserIdInBarrier()
                    )
                }
        }
    }

    suspend fun resetNetworkProxyConfig() {
        // 同上：reset 会清除 CLOUD_API_BASE_URL（生效值回落默认网关），同样属于身份会话变更，
        // 须过提交屏障；仅当规范化后的生效 base-url 真实变化时才推进世代
        runtimePort.withCloudIdentityBarrier {
                var baseUrlChanged = false
                preferences.edit { prefs ->
                    val oldBase = NetworkProxyConfig.normalizeCloudApiBaseUrl(prefs[CampusRepositoryPrefKeys.CLOUD_API_BASE_URL])
                    baseUrlChanged = oldBase != NetworkProxyConfig.normalizeCloudApiBaseUrl(null)
                    prefs.remove(CampusRepositoryPrefKeys.PROXY_MODE)
                    prefs.remove(CampusRepositoryPrefKeys.PROXY_PRESET_MIRROR_KEY)
                    prefs.remove(CampusRepositoryPrefKeys.PROXY_CUSTOM_PREFIX)
                    prefs.remove(CampusRepositoryPrefKeys.PROXY_HOST)
                    prefs.remove(CampusRepositoryPrefKeys.PROXY_PORT)
                    prefs.remove(CampusRepositoryPrefKeys.PROXY_AUTO_FALLBACK)
                    prefs.remove(CampusRepositoryPrefKeys.CLOUD_API_BASE_URL)
                }
                if (baseUrlChanged) {
                    runtimePort.bumpCloudIdentityGenerationForBaseUrlChange(
                        runtimePort.currentCloudUserIdInBarrier()
                    )
                }
        }
    }


    suspend fun setTimelineZoomScale(scale: Float) {
        preferences.edit { it[CampusRepositoryPrefKeys.TIMELINE_SCALE] = ThemeVisualDefaults.clampTimelineZoomScale(scale) }
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
            compact = prefs[CampusRepositoryPrefKeys.WIDGET_DEFAULT_COMPACT] ?: false,
            privacyMode = prefs[CampusRepositoryPrefKeys.WIDGET_DEFAULT_PRIVACY_MODE] ?: false
        )
    }

    suspend fun updateAllWidgetDefaults(
        compact: Boolean? = null,
        privacyMode: Boolean? = null
    ) {
        preferences.edit { prefs ->
            compact?.let { prefs[CampusRepositoryPrefKeys.WIDGET_DEFAULT_COMPACT] = it }
            privacyMode?.let { prefs[CampusRepositoryPrefKeys.WIDGET_DEFAULT_PRIVACY_MODE] = it }
        }
        when {
            compact != null && privacyMode != null -> dao.updateAllWidgetConfigsDefaults(compact, privacyMode)
            compact != null -> dao.updateAllWidgetConfigsCompact(compact)
            privacyMode != null -> dao.updateAllWidgetConfigsPrivacyMode(privacyMode)
        }
        notifyScheduleOrPolicyChanged("WIDGET_CONFIG_UPDATED")
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
            utilitiesEnabled?.let { prefs[CampusRepositoryPrefKeys.FF_UTILITIES_ENABLED] = it }
            libraryNativeEnabled?.let { prefs[CampusRepositoryPrefKeys.FF_LIBRARY_NATIVE_ENABLED] = it }
            librarySeatEnabled?.let { prefs[CampusRepositoryPrefKeys.FF_LIBRARY_SEAT_ENABLED] = it }
            focusEnabled?.let { prefs[CampusRepositoryPrefKeys.FF_FOCUS_ENABLED] = it }
            showTodayFocusQuickPill?.let { prefs[CampusRepositoryPrefKeys.FF_TODAY_FOCUS_PILL] = it }
            timetableInterchangeEnabled?.let { prefs[CampusRepositoryPrefKeys.FF_TIMETABLE_INTERCHANGE] = it }
        }
    }


    suspend fun setMealRecommendEnabled(enabled: Boolean) {
        preferences.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.MEAL_RECOMMEND_ENABLED] = enabled
        }
        notifyScheduleOrPolicyChanged("MEAL_RECOMMEND_CONFIG_CHANGED")
    }

    suspend fun isMealRecommendEnabledSnapshot(): Boolean {
        val prefs = preferences.data.first()
        return supportsHautServices && (prefs[CampusRepositoryPrefKeys.MEAL_RECOMMEND_ENABLED] ?: true)
    }

    suspend fun getLastMealRecommendBlockKeySnapshot(): String? =
        preferences.data.first()[CampusRepositoryPrefKeys.LAST_MEAL_RECOMMEND_BLOCK_KEY]

    suspend fun markMealRecommendNotified(blockKey: String) {
        preferences.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.LAST_MEAL_RECOMMEND_BLOCK_KEY] = blockKey
        }
    }

    suspend fun getCanteenDishReviewsSnapshot(): List<cn.edu.haut.campus.core.domain.CanteenDishReview> {
        val raw = preferences.data.first()[CampusRepositoryPrefKeys.CANTEEN_DISH_REVIEWS]
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
                prefs[CampusRepositoryPrefKeys.CANTEEN_DISH_REVIEWS]
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
            prefs[CampusRepositoryPrefKeys.CANTEEN_DISH_REVIEWS] =
                cn.edu.haut.campus.core.domain.CanteenDishReviewCodec.encodeList(updated.take(120))
        }
    }

    suspend fun deleteCanteenDishReview(canteenBuildingId: String, dishName: String) {
        preferences.edit { prefs ->
            val existing = cn.edu.haut.campus.core.domain.CanteenDishReviewCodec.decodeList(
                prefs[CampusRepositoryPrefKeys.CANTEEN_DISH_REVIEWS]
            )
            val updated = existing.filterNot {
                it.canteenBuildingId == canteenBuildingId && it.dishName.equals(dishName.trim(), ignoreCase = true)
            }
            prefs[CampusRepositoryPrefKeys.CANTEEN_DISH_REVIEWS] =
                cn.edu.haut.campus.core.domain.CanteenDishReviewCodec.encodeList(updated)
        }
    }

    suspend fun setSelectedFocusMinutes(minutes: Int) {
        preferences.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.SELECTED_FOCUS_MINUTES] = minutes.coerceIn(1, 180)
        }
    }


    suspend fun setSelectedCampusId(campusId: String) {
        preferences.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.SELECTED_CAMPUS_ID] = campusId.trim().ifBlank { "auto" }
        }
    }

    suspend fun saveCustomCampusZonesJson(json: String) {
        preferences.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.CUSTOM_CAMPUS_ZONES_JSON] = json.trim().ifBlank { "[]" }
        }
    }

}
