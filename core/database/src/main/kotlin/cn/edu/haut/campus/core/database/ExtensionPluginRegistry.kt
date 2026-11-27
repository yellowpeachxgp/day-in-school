package cn.edu.haut.campus.core.database

import android.content.Context
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.v2.ExtensionInstallation
import cn.edu.haut.campus.core.packages.v2.ExtensionPluginFileStore
import cn.edu.haut.campus.core.packages.v2.ExtensionPluginInstaller
import cn.edu.haut.campus.core.packages.v2.ExtensionPublisherTrustRoots
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 独立扩展插件注册点。
 *
 * dis-extension/v2 的写入只走 core/school-packages 的签名安装器；Room 旧表不再接受
 * 手工 upsert。这里只把安装器维护的每 Profile 文件状态投影为现有 UI 实体，避免
 * Room v19 预留编号被本卡提前消耗。该类型由 CampusServicesRepository 装配，不修改
 * CampusRepository 兼容外观；数据擦除则通过应用层的 before-wipe 独立注册点接入。
 */
internal class ExtensionPluginRegistry(
    environment: CampusRepositoryEnvironment,
    appContext: Context
) {
    private val cloudAndParcelDao = environment.database.cloudAndParcelDao()
    private val schoolId = environment.accountProfile.schoolId
    private val installer = ExtensionPluginInstaller(
        store = ExtensionPluginFileStore(
            ExtensionPluginWipeRegistration.rootForProfile(
                appContext.filesDir,
                environment.accountProfile.id
            ).toPath()
        ),
        keys = ExtensionPublisherTrustRoots.officialExamplesRegistry()
    )

    val installedExtensionPluginsFlow: Flow<List<InstalledExtensionPluginEntity>> =
        combine(
            installer.revision.map { snapshotProjections() },
            cloudAndParcelDao.observeInstalledExtensionPlugins()
        ) { signed, legacyRows ->
            val signedIds = signed.mapTo(linkedSetOf<String>()) { it.pluginId }
            signed + legacyRows
                .filterNot { it.pluginId in signedIds }
                .map(::toLegacyRemovalOnlyProjection)
        }.flowOn(Dispatchers.IO)

    suspend fun ensureDefaultReferencePluginsInstalled() {
        // W6-HOTFIX 行为保持不变：参考插件不再由设置页手工伪造为数据库行。
        runCatching {
            cloudAndParcelDao.deleteInstalledExtensionPlugin("org.example.hello-campus")
            cloudAndParcelDao.deleteInstalledExtensionPlugin("org.example.parcel-station")
        }
    }

    /**
     * 兼容 CampusRepository 既有外观的唯一写入口。
     *
     * 该方法不是安装入口：未签名实体、字段伪造和新插件创建一律拒绝。对已经验签
     * 安装的插件，只采纳 status 作为启用/停用意图；待审权限绝不通过这个兼容
     * 外观静默确认，必须调用安装器显式 confirmPendingPermissions。
     */
    suspend fun upsertExtensionPlugin(entity: InstalledExtensionPluginEntity) = withContext(Dispatchers.IO) {
        val installation = installer.installation(schoolId, entity.pluginId)
            ?: throw PackageException(
                PackageError.SIGNATURE_MISSING,
                "拒绝写入未经签名安装器创建的扩展插件：" + entity.pluginId
            )
        when (entity.status) {
            "ENABLED" -> {
                if (installation.pendingVersion != null || installation.status == "PENDING_PERMISSION_REVIEW") {
                    throw PackageException(
                        PackageError.PERMISSION_REAUTHORIZATION_REQUIRED,
                        "扩展插件存在待确认权限，不能通过兼容 upsert 静默启用：" + entity.pluginId
                    )
                }
                installer.setEnabled(schoolId, entity.pluginId, true)
            }
            "DISABLED" -> installer.setEnabled(schoolId, entity.pluginId, false)
            else -> throw PackageException(
                PackageError.MANIFEST_INVALID,
                "扩展插件兼容写入口只接受 ENABLED/DISABLED 生命周期状态"
            )
        }
        Unit
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
        installer.uninstall(schoolId, pluginId)
        runCatching { cloudAndParcelDao.deleteInstalledExtensionPlugin(pluginId) }
        Unit
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

    /** Legacy Room rows are unverified residual data: visible only so the user can remove them. */
    private fun toLegacyRemovalOnlyProjection(entity: InstalledExtensionPluginEntity): InstalledExtensionPluginEntity =
        entity.copy(
            status = "LEGACY_UNVERIFIED",
            grantedPermissionsJson = "[]",
            uiSlotsJson = "[]"
        )

    private fun permissionArrayJson(permissions: List<String>): String =
        Json.canonical(JsonValue.Arr(permissions.map { JsonValue.Str(it) }))
}
