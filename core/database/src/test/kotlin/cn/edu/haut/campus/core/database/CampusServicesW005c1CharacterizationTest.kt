package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.model.HomeCardModule
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.core.model.ReminderEventOverride
import cn.edu.haut.campus.core.model.ThemeVisualDefaults
import cn.edu.haut.campus.core.model.WidgetInstanceConfig
import cn.edu.haut.campus.core.model.WidgetType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * W0-05-c1 搬迁前特征冻结：AppPreferences、ReminderSettings、Utility、
 * CampusContent 与 ExtensionPluginRegistry 的 DataStore/Room 结果和通知副作用。
 * 不访问真实网络、真实学校账号或系统通知服务。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CampusServicesW005c1CharacterizationTest {
    private lateinit var fixture: RepairTestFixture
    private val repo get() = fixture.repository
    private val dao get() = fixture.dao

    @Before
    fun setUp() {
        fixture = RepairTestFixture.create(
            RuntimeEnvironment.getApplication(),
            "prof_w005c1_characterization_" + System.nanoTime()
        )
    }

    @After
    fun tearDown() {
        repo.onScheduleOrPolicyChangedListener = null
        fixture.close()
    }

    @Test
    fun w005c1_theme_widget_and_app_preference_snapshot() = runBlocking {
        val notifications = mutableListOf<String>()
        repo.onScheduleOrPolicyChangedListener = { reason -> notifications += reason }

        repo.setUseDynamicColor(true)
        repo.setThemePalette("CUSTOM", customSeedArgb = 0xFF123456L)
        repo.updateWallpaperVisualParams(alpha = 2f, blurDp = -1f, cardAlpha = 2f)
        repo.resetWallpaperVisualParams()
        repo.updateHomeCardLayout(
            order = listOf("LIBRARY_SEAT", "EXAM_SUMMARY"),
            hidden = setOf("PARCEL_HUB"),
            fullWidth = emptySet()
        )
        repo.setTimelineZoomScale(99f)
        repo.setCampusServiceFeatureFlag(utilitiesEnabled = false, focusEnabled = false)
        repo.setSelectedFocusMinutes(999)
        repo.setSelectedCampusId("  ")
        repo.saveCustomCampusZonesJson("  ")

        val widget = WidgetInstanceConfig(
            appWidgetId = 42,
            widgetType = WidgetType.TODAY_4X2,
            compact = false,
            privacyMode = false
        )
        repo.saveWidgetConfig(widget)
        repo.updateAllWidgetDefaults(compact = true, privacyMode = true)

        val state = repo.preferencesFlow.first()
        // setThemePalette 会显式关闭动态色：冻结的是这一既有顺序副作用，而不是先入为主地期待 true。
        assertEquals(false, state.useDynamicColor)
        assertEquals("CUSTOM", state.themePaletteKey)
        assertEquals(0xFF123456L, state.customThemeSeedArgb)
        assertEquals(0xFF123456.toInt(), repo.getActiveThemeAccentArgb())
        assertEquals(ThemeVisualDefaults.DEFAULT_WALLPAPER_ALPHA, state.wallpaperAlpha, 0.0001f)
        assertEquals(ThemeVisualDefaults.DEFAULT_WALLPAPER_BLUR_DP, state.wallpaperBlurDp, 0.0001f)
        assertEquals(ThemeVisualDefaults.DEFAULT_WALLPAPER_CARD_ALPHA, state.wallpaperCardAlpha, 0.0001f)
        assertEquals(listOf("LIBRARY_SEAT", "EXAM_SUMMARY"), state.homeCardOrder.take(2))
        assertEquals(setOf("PARCEL_HUB"), state.hiddenHomeCards)
        assertTrue(state.fullWidthHomeCards.isEmpty())
        assertEquals(ThemeVisualDefaults.clampTimelineZoomScale(99f), state.timelineZoomScale, 0.0001f)
        assertEquals(false, state.utilitiesEnabled)
        assertEquals(false, state.focusEnabled)
        assertEquals(180, state.selectedFocusMinutes)
        assertEquals("auto", state.selectedCampusId)
        assertEquals("[]", state.customCampusZonesJson)

        val storedWidget = repo.getWidgetConfigOrDefault(42, WidgetType.NEXT_CLASS_2X2)
        assertEquals(WidgetType.TODAY_4X2, storedWidget.widgetType)
        assertEquals(true, storedWidget.compact)
        assertEquals(true, storedWidget.privacyMode)
        assertEquals(
            listOf("THEME_UPDATED", "THEME_UPDATED", "THEME_UPDATED", "WIDGET_CONFIG_UPDATED"),
            notifications
        )
    }

    @Test
    fun w005c1_network_proxy_snapshot_and_reset_semantics() = runBlocking {
        repo.updateNetworkProxyConfig(
            NetworkProxyConfig(
                proxyHost = " 192.0.2.10 ",
                proxyPort = 70000,
                autoFallbackMirror = false
            )
        )
        val updated = repo.getNetworkProxyConfigSnapshot()
        repo.resetNetworkProxyConfig()
        val reset = repo.getNetworkProxyConfigSnapshot()

        assertEquals("192.0.2.10", updated.proxyHost)
        assertEquals(65535, updated.proxyPort)
        assertEquals(false, updated.autoFallbackMirror)
        assertEquals("127.0.0.1", reset.proxyHost)
        assertEquals(7890, reset.proxyPort)
        assertEquals(true, reset.autoFallbackMirror)
    }

    @Test
    fun w005c1_reminder_settings_snapshot_override_log_and_notifications() = runBlocking {
        val notifications = mutableListOf<String>()
        repo.onScheduleOrPolicyChangedListener = { reason -> notifications += reason }

        repo.updateReminderGlobalConfig { current ->
            current.copy(
                enabled = false,
                classLeadMinutes = 1,
                classSnoozeDefaultMinutes = 99,
                classSnoozeMaxCount = 9,
                classSnoozeCutoffMinutes = 0,
                examDayBeforeEnabled = false,
                examHourBeforeEnabled = false,
                examSnoozeMaxCount = 9,
                preciseReminderPreferred = false,
                liveProgressRefreshMinutes = 7,
                lockscreenPrivate = false,
                quietMode = "CUSTOM"
            )
        }
        val config = repo.getReminderConfigSnapshot()
        assertEquals(false, config.enabled)
        assertEquals(5, config.classLeadMinutes)
        assertEquals(30, config.classSnoozeDefaultMinutes)
        assertEquals(5, config.classSnoozeMaxCount)
        assertEquals(1, config.classSnoozeCutoffMinutes)
        assertEquals(false, config.examDayBeforeEnabled)
        assertEquals(false, config.examHourBeforeEnabled)
        assertEquals(5, config.examSnoozeMaxCount)
        assertEquals(false, config.preciseReminderPreferred)
        assertEquals(5, config.liveProgressRefreshMinutes)
        assertEquals(false, config.lockscreenPrivate)
        assertEquals("CUSTOM", config.quietMode)

        val override = ReminderEventOverride(
            eventScopeKey = "scope_w005c1",
            enabledOverride = false,
            leadMinutesOverride = 7,
            maxSnoozesOverride = 1,
            updatedAtEpochMs = 123L
        )
        repo.upsertReminderEventOverride(override)
        assertEquals(listOf(override), repo.getReminderEventOverridesSnapshot())
        assertEquals(listOf(override), repo.reminderEventOverridesFlow.first())
        repo.removeReminderEventOverride("scope_w005c1")
        assertTrue(repo.getReminderEventOverridesSnapshot().isEmpty())

        repo.appendReminderDiagnosticLog("核对完成", reconcileSummary = "3项一致")
        val state = repo.preferencesFlow.first()
        assertEquals(1, state.reminderDiagnosticLogs.size)
        assertTrue(state.reminderDiagnosticLogs.single().contains("核对完成"))
        assertEquals("3项一致", state.lastReminderReconcileSummary)
        assertEquals(
            listOf(
                "REMINDER_GLOBAL_CONFIG_UPDATED",
                "REMINDER_EVENT_OVERRIDE_UPSERTED:scope_w005c1",
                "REMINDER_EVENT_OVERRIDE_REMOVED:scope_w005c1"
            ),
            notifications
        )
    }

    @Test
    fun w005c1_campus_content_custom_maps_roundtrip() = runBlocking {
        repo.saveCustomChannelUrl("channel_a", " https://invite.example/a ")
        repo.saveCustomClubGroupNumber("club_a", " 123456 ")
        var state = repo.preferencesFlow.first()
        assertEquals(mapOf("channel_a" to "https://invite.example/a"), state.customChannelUrls)
        assertEquals(mapOf("club_a" to "123456"), state.customClubGroupNumbers)

        repo.saveCustomChannelUrl("channel_a", "")
        repo.saveCustomClubGroupNumber("club_a", "")
        state = repo.preferencesFlow.first()
        assertTrue(state.customChannelUrls.isEmpty())
        assertTrue(state.customClubGroupNumbers.isEmpty())
    }

    @Test
    fun w005c1_manual_utility_meter_reading_config_and_cascade_delete() = runBlocking {
        repo.updateUtilityNotificationConfig(enabled = false, dailyHour = 99, lowBalanceThreshold = 999f)
        repo.saveUtilityMeter(
            name = " 宿舍电表 ",
            campusName = "",
            roomName = " 南苑6号楼208 ",
            unit = " 度 ",
            lowThreshold = 12.5
        )
        val meter = repo.utilityMetersFlow.first().single()
        assertEquals("宿舍电表", meter.name)
        assertEquals("莲花街校区", meter.campusName)
        assertEquals("南苑6号楼208", meter.roomName)
        assertEquals("度", meter.unit)
        assertEquals(12.5, meter.lowThreshold, 0.0001)

        repo.addUtilityReading(meterId = meter.id, value = 20.0, topUpValue = 5.0, note = " 首次 ")
        val reading = repo.utilityReadingsFlow.first().single()
        assertEquals(meter.id, reading.meterId)
        assertEquals(20.0, reading.value, 0.0001)
        assertEquals(5.0, reading.topUpValue, 0.0001)
        assertEquals("首次", reading.note)

        val prefs = repo.preferencesFlow.first()
        assertEquals(false, prefs.utilityDailyNotifyEnabled)
        assertEquals(23, prefs.utilityDailyNotifyHour)
        assertEquals(100f, prefs.utilityLowBalanceThreshold, 0.0001f)

        repo.deleteUtilityMeter(meter.id)
        assertTrue(repo.utilityMetersFlow.first().isEmpty())
        assertTrue(repo.utilityReadingsFlow.first().isEmpty())
    }

    @Test
    fun w005c1_extension_plugin_registry_exposes_only_signed_lifecycle_api() = runBlocking {
        val methodNames = repo.javaClass.methods.map { it.name }.toSet()
        assertTrue(methodNames.contains("installSignedExtensionPackageZip"))
        assertTrue(methodNames.contains("installSignedExtensionPackage"))
        assertTrue(methodNames.contains("setExtensionPluginEnabled"))
        assertTrue(methodNames.contains("revokeExtensionPluginPermission"))
        assertTrue(methodNames.contains("rollbackExtensionPlugin"))
        assertTrue(methodNames.contains("removeExtensionPlugin"))
        assertTrue(repo.installedExtensionPluginsFlow.first().isEmpty())
        repo.removeExtensionPlugin("org.example.missing")
        assertTrue(repo.installedExtensionPluginsFlow.first().isEmpty())
    }
}
