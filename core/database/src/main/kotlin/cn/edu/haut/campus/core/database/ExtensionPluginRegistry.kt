package cn.edu.haut.campus.core.database

import android.content.Context
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.v2.ExtensionInstallation
import cn.edu.haut.campus.core.packages.v2.ExtensionPluginFileStore
import cn.edu.haut.campus.core.packages.v2.ExtensionPluginInstaller
import cn.edu.haut.campus.core.packages.v2.ExtensionPublisherTrustRoots
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 唯一扩展插件注册表。
 *
 * dis-extension/v2 的写入只走 core/school-packages 的签名安装器；Room 旧表不再接受
 * 手工 upsert。这里只把安装器维护的每 Profile 文件状态投影为现有 UI 实体，避免
 * Room v19 预留编号被本卡提前消耗。
 */
internal class ExtensionPluginRegistry(
    environment: CampusRepositoryEnvironment,
    appContext: Context
) {
    private val cloudAndParcelDao = environment.database.cloudAndParcelDao()
    private val schoolId = environment.accountProfile.schoolId
    private val installer = ExtensionPluginInstaller(
        store = ExtensionPluginFileStore(
            File(
                appContext.filesDir,
                "extension-plugin-packages/" + environment.accountProfile.databaseName.removeSuffix(".db")
            ).toPath()
        ),
        keys = ExtensionPublisherTrustRoots.officialExamplesRegistry()
    )

    val installedExtensionPluginsFlow: Flow<List<InstalledExtensionPluginEntity>> =
        installer.revision.map { snapshotProjections() }

    suspend fun ensureDefaultReferencePluginsInstalled() {
        // W6-HOTFIX 行为保持不变：参考插件不再由设置页手工伪造为数据库行。
        runCatching {
            cloudAndParcelDao.deleteInstalledExtensionPlugin("org.example.hello-campus")
            cloudAndParcelDao.deleteInstalledExtensionPlugin("org.example.parcel-station")
        }
    }

    suspend fun installSignedExtensionPackageZip(zipBytes: ByteArray) = withContext(Dispatchers.IO) {
        installer.installArchive(zipBytes, schoolId)
    }

    suspend fun installSignedExtensionPackage(manifestBytes: ByteArray, artifactMap: Map<String, ByteArray>) = withContext(Dispatchers.IO) {
        installer.install(manifestBytes, artifactMap, schoolId)
    }

    suspend fun setExtensionPluginEnabled(pluginId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        installer.setEnabled(schoolId, pluginId, enabled)
    }

    suspend fun confirmExtensionPluginPermissions(pluginId: String) = withContext(Dispatchers.IO) {
        installer.confirmPendingPermissions(schoolId, pluginId)
    }

    suspend fun revokeExtensionPluginPermission(pluginId: String, permission: String) = withContext(Dispatchers.IO) {
        installer.revokePermission(schoolId, pluginId, permission)
    }

    suspend fun rollbackExtensionPlugin(pluginId: String, targetVersion: String? = null) = withContext(Dispatchers.IO) {
        installer.rollback(schoolId, pluginId, targetVersion)
    }

    suspend fun removeExtensionPlugin(pluginId: String) = withContext(Dispatchers.IO) {
        val cleanup = installer.uninstall(schoolId, pluginId)
        runCatching { cloudAndParcelDao.deleteInstalledExtensionPlugin(pluginId) }
        cleanup
    }

    suspend fun wipeExtensionPluginData(reason: String) = withContext(Dispatchers.IO) {
        installer.wipeAll(reason)
    }

    private fun snapshotProjections(): List<InstalledExtensionPluginEntity> =
        installer.snapshot().map(::toLegacyProjection)

    private fun toLegacyProjection(installation: ExtensionInstallation): InstalledExtensionPluginEntity =
        InstalledExtensionPluginEntity(
            pluginId = installation.pluginId,
            name = installation.name,
            activeVersion = installation.activeVersion,
            previousVersion = installation.previousVersion,
            schemaFormat = installation.schemaFormat,
            status = installation.status,
            grantedPermissionsJson = permissionArrayJson(installation.grantedPermissions),
            uiSlotsJson = installation.uiSlotsJson,
            updatedAtEpochMillis = installation.updatedAtEpochMillis
        )

    private fun permissionArrayJson(permissions: List<String>): String =
        Json.canonical(JsonValue.Arr(permissions.map { JsonValue.Str(it) }))
}
