package cn.edu.haut.campus.core.database

import android.content.Context
import cn.edu.haut.campus.core.campusservices.OnlineUtilityRoomSnapshot
import cn.edu.haut.campus.core.campusservices.UtilityAccountReadResult
import cn.edu.haut.campus.core.campusservices.UtilityAccountSecureStore
import cn.edu.haut.campus.core.campusservices.UtilityDailySample
import cn.edu.haut.campus.core.model.FocusSessionDomain
import cn.edu.haut.campus.core.model.UtilityMeterDomain
import cn.edu.haut.campus.core.model.UtilityOnlineRoomDomain
import cn.edu.haut.campus.core.model.UtilityReadingDomain
import kotlinx.coroutines.flow.Flow

/**
 * W0-05 过渡期校园服务薄协调 Repository。
 * 本批逐步委托 AppPreferences/ReminderSettings/Utility/CampusContent/ExtensionPluginRegistry；
 * ParcelHub、CloudIdentity、LibrarySeat、Sync 与 Wiper 仍保留在后续批次。
 */
class CampusServicesRepository internal constructor(
    environment: CampusRepositoryEnvironment,
    appContext: Context,
    utilityAccountStore: UtilityAccountSecureStore,
    private val runtimePort: CampusServicesRuntimePort
) {
    private val campusContentService = CampusContentService(environment, runtimePort)
    private val extensionPluginRegistry = ExtensionPluginRegistry(environment)
    private val reminderSettingsStore = ReminderSettingsStore(environment)
    private val appPreferencesStore = AppPreferencesStore(environment, runtimePort)
    private val utilityService = UtilityService(environment, appContext, utilityAccountStore, runtimePort)

    suspend fun saveCustomChannelUrl(channelId: String, inviteUrl: String) =
        campusContentService.saveCustomChannelUrl(channelId, inviteUrl)

    suspend fun saveCustomClubGroupNumber(clubId: String, groupNumber: String) =
        campusContentService.saveCustomClubGroupNumber(clubId, groupNumber)

    suspend fun refreshCampusCommunityContent(): Result<cn.edu.haut.campus.core.model.CampusCommunityCloudCatalog> =
        campusContentService.refreshCampusCommunityContent()

    val installedExtensionPluginsFlow: Flow<List<InstalledExtensionPluginEntity>> =
        extensionPluginRegistry.installedExtensionPluginsFlow

    suspend fun ensureDefaultReferencePluginsInstalled() =
        extensionPluginRegistry.ensureDefaultReferencePluginsInstalled()

    suspend fun upsertExtensionPlugin(entity: InstalledExtensionPluginEntity) =
        extensionPluginRegistry.upsertExtensionPlugin(entity)

    suspend fun removeExtensionPlugin(pluginId: String) =
        extensionPluginRegistry.removeExtensionPlugin(pluginId)

    val activeReminderTasksFlow: Flow<List<cn.edu.haut.campus.core.model.ReminderTaskDomain>> =
        reminderSettingsStore.activeReminderTasksFlow

    val reminderEventOverridesFlow: Flow<List<cn.edu.haut.campus.core.model.ReminderEventOverride>> =
        reminderSettingsStore.reminderEventOverridesFlow

    fun observeReminderTasksInWindow(windowStartMs: Long, windowEndMs: Long): Flow<List<cn.edu.haut.campus.core.model.ReminderTaskDomain>> =
        reminderSettingsStore.observeReminderTasksInWindow(windowStartMs, windowEndMs)

    fun reminderDao(): ReminderDao = reminderSettingsStore.reminderDao()

    suspend fun getReminderConfigSnapshot(): cn.edu.haut.campus.core.model.ReminderGlobalConfig =
        reminderSettingsStore.getReminderConfigSnapshot()

    suspend fun getReminderEventOverridesSnapshot(): List<cn.edu.haut.campus.core.model.ReminderEventOverride> =
        reminderSettingsStore.getReminderEventOverridesSnapshot()

    suspend fun updateReminderGlobalConfig(transform: (cn.edu.haut.campus.core.model.ReminderGlobalConfig) -> cn.edu.haut.campus.core.model.ReminderGlobalConfig) =
        reminderSettingsStore.updateReminderGlobalConfig(transform)

    suspend fun upsertReminderEventOverride(override: cn.edu.haut.campus.core.model.ReminderEventOverride) =
        reminderSettingsStore.upsertReminderEventOverride(override)

    suspend fun removeReminderEventOverride(scopeKey: String) =
        reminderSettingsStore.removeReminderEventOverride(scopeKey)

    suspend fun appendReminderDiagnosticLog(message: String, reconcileSummary: String? = null) =
        reminderSettingsStore.appendReminderDiagnosticLog(message, reconcileSummary)

    val preferencesFlow: Flow<AppPreferencesState> = appPreferencesStore.preferencesFlow

    val widgetConfigsFlow: Flow<List<cn.edu.haut.campus.core.model.WidgetInstanceConfig>> =
        appPreferencesStore.widgetConfigsFlow

    suspend fun completeOnboarding() = appPreferencesStore.completeOnboarding()

    suspend fun resetOnboardingForGuide() = appPreferencesStore.resetOnboardingForGuide()

    suspend fun setUseDynamicColor(enabled: Boolean) =
        appPreferencesStore.setUseDynamicColor(enabled)

    suspend fun setThemeMode(mode: String) = appPreferencesStore.setThemeMode(mode)

    suspend fun setUiDesignStyle(styleKey: String) =
        appPreferencesStore.setUiDesignStyle(styleKey)

    suspend fun applyCuratedThemePreset(
        designStyleKey: String,
        paletteKey: String,
        clearWallpaper: Boolean = false
    ) = appPreferencesStore.applyCuratedThemePreset(
        designStyleKey = designStyleKey,
        paletteKey = paletteKey,
        clearWallpaper = clearWallpaper
    )

    suspend fun setThemePalette(paletteKey: String, customSeedArgb: Long? = null) =
        appPreferencesStore.setThemePalette(paletteKey, customSeedArgb)

    suspend fun saveCustomWallpaperTheme(
        localPath: String,
        primarySeedArgb: Long,
        extractedSwatches: List<Long>,
        enableLiquidGlass: Boolean = false
    ) = appPreferencesStore.saveCustomWallpaperTheme(
        localPath = localPath,
        primarySeedArgb = primarySeedArgb,
        extractedSwatches = extractedSwatches,
        enableLiquidGlass = enableLiquidGlass
    )

    suspend fun saveExtractedDesktopSwatches(
        primarySeedArgb: Long,
        extractedSwatches: List<Long>
    ) = appPreferencesStore.saveExtractedDesktopSwatches(primarySeedArgb, extractedSwatches)

    suspend fun updateWallpaperVisualParams(
        alpha: Float? = null,
        blurDp: Float? = null,
        cardAlpha: Float? = null
    ) = appPreferencesStore.updateWallpaperVisualParams(alpha, blurDp, cardAlpha)

    suspend fun resetWallpaperVisualParams() = appPreferencesStore.resetWallpaperVisualParams()

    suspend fun clearCustomWallpaperTheme() = appPreferencesStore.clearCustomWallpaperTheme()

    suspend fun updateHomeCardLayout(
        order: List<String>,
        hidden: Set<String>,
        fullWidth: Set<String>
    ) = appPreferencesStore.updateHomeCardLayout(order, hidden, fullWidth)

    suspend fun resetHomeCardLayout() = appPreferencesStore.resetHomeCardLayout()

    suspend fun getActiveThemeAccentArgb(): Int = appPreferencesStore.getActiveThemeAccentArgb()

    suspend fun setAutoCheckUpdateEnabled(enabled: Boolean) =
        appPreferencesStore.setAutoCheckUpdateEnabled(enabled)

    suspend fun getNetworkProxyConfigSnapshot(): cn.edu.haut.campus.core.model.NetworkProxyConfig =
        appPreferencesStore.getNetworkProxyConfigSnapshot()

    suspend fun updateNetworkProxyConfig(config: cn.edu.haut.campus.core.model.NetworkProxyConfig) =
        appPreferencesStore.updateNetworkProxyConfig(config)

    suspend fun resetNetworkProxyConfig() = appPreferencesStore.resetNetworkProxyConfig()

    suspend fun setTimelineZoomScale(scale: Float) =
        appPreferencesStore.setTimelineZoomScale(scale)

    suspend fun saveWidgetConfig(config: cn.edu.haut.campus.core.model.WidgetInstanceConfig) =
        appPreferencesStore.saveWidgetConfig(config)

    suspend fun getWidgetConfigOrDefault(
        appWidgetId: Int,
        defaultType: cn.edu.haut.campus.core.model.WidgetType
    ): cn.edu.haut.campus.core.model.WidgetInstanceConfig =
        appPreferencesStore.getWidgetConfigOrDefault(appWidgetId, defaultType)

    suspend fun updateAllWidgetDefaults(
        compact: Boolean? = null,
        privacyMode: Boolean? = null
    ) = appPreferencesStore.updateAllWidgetDefaults(compact, privacyMode)

    suspend fun setCampusServiceFeatureFlag(
        utilitiesEnabled: Boolean? = null,
        libraryNativeEnabled: Boolean? = null,
        librarySeatEnabled: Boolean? = null,
        focusEnabled: Boolean? = null,
        showTodayFocusQuickPill: Boolean? = null,
        timetableInterchangeEnabled: Boolean? = null
    ) = appPreferencesStore.setCampusServiceFeatureFlag(
        utilitiesEnabled = utilitiesEnabled,
        libraryNativeEnabled = libraryNativeEnabled,
        librarySeatEnabled = librarySeatEnabled,
        focusEnabled = focusEnabled,
        showTodayFocusQuickPill = showTodayFocusQuickPill,
        timetableInterchangeEnabled = timetableInterchangeEnabled
    )

    suspend fun setMealRecommendEnabled(enabled: Boolean) =
        appPreferencesStore.setMealRecommendEnabled(enabled)

    suspend fun isMealRecommendEnabledSnapshot(): Boolean =
        appPreferencesStore.isMealRecommendEnabledSnapshot()

    suspend fun getLastMealRecommendBlockKeySnapshot(): String? =
        appPreferencesStore.getLastMealRecommendBlockKeySnapshot()

    suspend fun markMealRecommendNotified(blockKey: String) =
        appPreferencesStore.markMealRecommendNotified(blockKey)

    suspend fun getCanteenDishReviewsSnapshot(): List<cn.edu.haut.campus.core.domain.CanteenDishReview> =
        appPreferencesStore.getCanteenDishReviewsSnapshot()

    suspend fun saveCanteenDishReview(
        canteenBuildingId: String,
        dishName: String,
        stallLocation: String = "",
        priceYuan: Int? = null,
        stars: Int,
        tags: List<String> = emptyList(),
        comment: String = ""
    ) = appPreferencesStore.saveCanteenDishReview(
        canteenBuildingId = canteenBuildingId,
        dishName = dishName,
        stallLocation = stallLocation,
        priceYuan = priceYuan,
        stars = stars,
        tags = tags,
        comment = comment
    )

    suspend fun deleteCanteenDishReview(canteenBuildingId: String, dishName: String) =
        appPreferencesStore.deleteCanteenDishReview(canteenBuildingId, dishName)

    suspend fun setSelectedFocusMinutes(minutes: Int) =
        appPreferencesStore.setSelectedFocusMinutes(minutes)

    suspend fun setSelectedCampusId(campusId: String) =
        appPreferencesStore.setSelectedCampusId(campusId)

    suspend fun saveCustomCampusZonesJson(json: String) =
        appPreferencesStore.saveCustomCampusZonesJson(json)

    val utilityDailySamplesFlow: Flow<List<UtilityDailySample>> = utilityService.utilityDailySamplesFlow

    val utilityOnlineRoomsFlow: Flow<List<UtilityOnlineRoomDomain>> = utilityService.utilityOnlineRoomsFlow

    val utilityMetersFlow: Flow<List<UtilityMeterDomain>> = utilityService.utilityMetersFlow

    val utilityReadingsFlow: Flow<List<UtilityReadingDomain>> = utilityService.utilityReadingsFlow

    val activeFocusSessionFlow: Flow<FocusSessionDomain?> = utilityService.activeFocusSessionFlow

    val completedFocusSessionsFlow: Flow<List<FocusSessionDomain>> = utilityService.completedFocusSessionsFlow

    fun utilityAndFocusDao(): UtilityAndFocusDao = utilityService.utilityAndFocusDao()

    fun utilityAccountSecureStore(): UtilityAccountSecureStore = utilityService.utilityAccountSecureStore()

    suspend fun setAllowUnverifiedUtilityTransport(allowed: Boolean) =
        utilityService.setAllowUnverifiedUtilityTransport(allowed)

    fun readUtilityAccountResult(): UtilityAccountReadResult = utilityService.readUtilityAccountResult()

    suspend fun saveAndRefreshOnlineUtilities(
        rawAccount: String? = null,
        allowUnverified: Boolean = false
    ): Result<List<OnlineUtilityRoomSnapshot>> = utilityService.saveAndRefreshOnlineUtilities(
        rawAccount = rawAccount,
        allowUnverified = allowUnverified
    )

    suspend fun performPeriodicUtilitySampleIfEligible(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean = utilityService.performPeriodicUtilitySampleIfEligible(nowEpochMs)

    suspend fun updateUtilityNotificationConfig(
        enabled: Boolean? = null,
        dailyHour: Int? = null,
        lowBalanceThreshold: Float? = null
    ) = utilityService.updateUtilityNotificationConfig(enabled, dailyHour, lowBalanceThreshold)

    suspend fun notifyDailyUtilitySummaryIfEligible(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean = utilityService.notifyDailyUtilitySummaryIfEligible(nowEpochMs)

    suspend fun sendTestUtilityNotification(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean = utilityService.sendTestUtilityNotification(nowEpochMs)

    suspend fun updateOnlineRoomCampus(roomId: String, campusCode: String?) =
        utilityService.updateOnlineRoomCampus(roomId, campusCode)

    suspend fun clearUtilityAccountAndOnlineCache() =
        utilityService.clearUtilityAccountAndOnlineCache()

    suspend fun saveUtilityMeter(
        name: String,
        campusName: String,
        roomName: String,
        unit: String,
        lowThreshold: Double
    ) = utilityService.saveUtilityMeter(name, campusName, roomName, unit, lowThreshold)

    suspend fun deleteUtilityMeter(meterId: String) = utilityService.deleteUtilityMeter(meterId)

    suspend fun addUtilityReading(
        meterId: String,
        value: Double,
        topUpValue: Double = 0.0,
        note: String = ""
    ) = utilityService.addUtilityReading(meterId, value, topUpValue, note)

    suspend fun deleteUtilityReading(readingId: String) = utilityService.deleteUtilityReading(readingId)
}
