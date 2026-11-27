package cn.edu.haut.campus.core.database

import android.content.Context
import cn.edu.haut.campus.core.campusservices.UtilityAccountSecureStore
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
}
