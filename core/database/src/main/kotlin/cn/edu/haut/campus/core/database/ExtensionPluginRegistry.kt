package cn.edu.haut.campus.core.database

import kotlinx.coroutines.flow.Flow

/** 统一扩展插件注册表：仅负责本地安装状态读写，示例插件清理行为保持不变。 */
internal class ExtensionPluginRegistry(
    environment: CampusRepositoryEnvironment
) {
    private val cloudAndParcelDao = environment.database.cloudAndParcelDao()

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

}
