package cn.edu.haut.campus.core.packages.v2

import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.crypto.PublisherKeyRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Result of installing or updating a declarative extension package. */
data class ExtensionInstallResult(
    val installation: ExtensionInstallation,
    val manifestDigest: String,
    val cleanup: ExtensionCleanupResult? = null
)

/**
 * Single Android v2 install path: signed manifest parse → trust-root signature check →
 * artifact digest validation → staged atomic file activation → scoped permission state.
 * There is deliberately no API that accepts a plugin name/id/version without the signed package.
 */
class ExtensionPluginInstaller(
    private val store: ExtensionPluginFileStore,
    private val keys: PublisherKeyRegistry,
    private val currentHostApi: Int = 2,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val revisionState = MutableStateFlow(0L)
    val revision: StateFlow<Long> = revisionState

    @Synchronized
    fun install(
        manifestBytes: ByteArray,
        artifactMap: Map<String, ByteArray>,
        schoolId: String
    ): ExtensionInstallResult {
        val verified = ExtensionPackageVerifier.verify(manifestBytes, artifactMap, keys, currentHostApi)
        val manifest = verified.manifest
        val now = nowEpochMillis()
        val existing = store.loadInstallation(schoolId, manifest.pluginId)
        val targetPermissions = manifest.permissions.toList()
        val uiSlotsJson = extractUiSlotsJson(verified.manifestBytes)

        val next = if (existing == null) {
            ExtensionInstallation(
                pluginId = manifest.pluginId,
                schoolId = schoolId,
                name = manifest.name,
                schemaFormat = manifest.schemaVersion,
                activeVersion = manifest.version.value,
                previousVersion = null,
                pendingVersion = null,
                pendingPermissions = emptyList(),
                status = "ENABLED",
                grantedPermissions = targetPermissions,
                uiSlotsJson = uiSlotsJson,
                publisherId = verified.publisherId,
                installedAtEpochMillis = now,
                updatedAtEpochMillis = now
            )
        } else {
            val currentGranted = existing.grantedPermissions.distinct()
            val added = targetPermissions.filter { it !in currentGranted }
            if (added.isNotEmpty()) {
                existing.copy(
                    name = manifest.name,
                    schemaFormat = manifest.schemaVersion,
                    activeVersion = existing.activeVersion,
                    previousVersion = existing.previousVersion,
                    pendingVersion = manifest.version.value,
                    pendingPermissions = added,
                    status = if (existing.status == "DISABLED") "DISABLED" else "PENDING_PERMISSION_REVIEW",
                    grantedPermissions = currentGranted,
                    uiSlotsJson = uiSlotsJson,
                    publisherId = verified.publisherId,
                    updatedAtEpochMillis = now
                )
            } else {
                existing.copy(
                    name = manifest.name,
                    schemaFormat = manifest.schemaVersion,
                    activeVersion = manifest.version.value,
                    previousVersion = if (existing.activeVersion != manifest.version.value) existing.activeVersion else existing.previousVersion,
                    pendingVersion = null,
                    pendingPermissions = emptyList(),
                    status = if (existing.status == "DISABLED") "DISABLED" else "ENABLED",
                    grantedPermissions = targetPermissions.filter { it in currentGranted },
                    uiSlotsJson = uiSlotsJson,
                    publisherId = verified.publisherId,
                    updatedAtEpochMillis = now
                )
            }
        }

        val versionAlreadyPresent = store.versionExists(manifest.pluginId, manifest.version.value)
        store.saveVersion(verified)
        try {
            store.saveInstallation(next)
        } catch (error: Exception) {
            if (!versionAlreadyPresent) {
                store.deleteVersion(manifest.pluginId, manifest.version.value)
            }
            throw error
        }
        bump()
        return ExtensionInstallResult(next, verified.manifestDigest)
    }

    fun installArchive(zipBytes: ByteArray, schoolId: String): ExtensionInstallResult {
        val archive = ExtensionZipPackageReader.read(zipBytes)
        return install(archive.manifestBytes, archive.artifactMap, schoolId)
    }

    fun snapshot(): List<ExtensionInstallation> = store.listInstallations()

    fun installation(schoolId: String, pluginId: String): ExtensionInstallation? =
        store.loadInstallation(schoolId, pluginId)

    @Synchronized
    fun confirmPendingPermissions(schoolId: String, pluginId: String): ExtensionInstallation {
        val existing = requireInstallation(schoolId, pluginId)
        val pending = existing.pendingVersion
            ?: throw PackageException(PackageError.MANIFEST_INVALID, "插件没有待确认权限版本")
        if (existing.status != "PENDING_PERMISSION_REVIEW") {
            throw PackageException(PackageError.MANIFEST_INVALID, "插件不处于权限确认状态")
        }
        val targetManifest = loadStoredManifest(pluginId, pending)
        val next = existing.copy(
            previousVersion = if (existing.activeVersion != pending) existing.activeVersion else existing.previousVersion,
            activeVersion = pending,
            pendingVersion = null,
            pendingPermissions = emptyList(),
            status = "ENABLED",
            grantedPermissions = targetManifest.permissions.toList(),
            updatedAtEpochMillis = nowEpochMillis()
        )
        store.saveInstallation(next)
        bump()
        return next
    }

    @Synchronized
    fun setEnabled(schoolId: String, pluginId: String, enabled: Boolean): ExtensionInstallation {
        val existing = requireInstallation(schoolId, pluginId)
        val next = if (enabled) {
            if (existing.status == "PENDING_PERMISSION_REVIEW") {
                return confirmPendingPermissions(schoolId, pluginId)
            }
            existing.copy(
                status = if (existing.pendingVersion != null) "PENDING_PERMISSION_REVIEW" else "ENABLED",
                disabledReason = null,
                updatedAtEpochMillis = nowEpochMillis()
            )
        } else {
            existing.copy(status = "DISABLED", updatedAtEpochMillis = nowEpochMillis())
        }
        store.saveInstallation(next)
        bump()
        return next
    }

    @Synchronized
    fun revokePermission(schoolId: String, pluginId: String, permission: String): ExtensionInstallation {
        val existing = requireInstallation(schoolId, pluginId)
        val activeManifest = loadStoredManifest(pluginId, existing.activeVersion)
        if (permission !in activeManifest.permissions) {
            throw PackageException(PackageError.MANIFEST_INVALID, "权限不由当前插件版本声明：" + permission)
        }
        val next = existing.copy(
            grantedPermissions = existing.grantedPermissions.filter { it != permission }.distinct(),
            updatedAtEpochMillis = nowEpochMillis()
        )
        store.saveInstallation(next)
        bump()
        return next
    }

    @Synchronized
    fun rollback(schoolId: String, pluginId: String, targetVersion: String? = null): ExtensionInstallation {
        val existing = requireInstallation(schoolId, pluginId)
        val rollbackVersion = targetVersion ?: existing.previousVersion
            ?: throw PackageException(PackageError.ROLLBACK_UNAVAILABLE, "插件没有可回滚版本")
        val targetManifest = loadStoredManifest(pluginId, rollbackVersion)
        val next = existing.copy(
            previousVersion = existing.activeVersion,
            activeVersion = rollbackVersion,
            pendingVersion = null,
            pendingPermissions = emptyList(),
            status = if (existing.status == "DISABLED") "DISABLED" else "ENABLED",
            grantedPermissions = targetManifest.permissions.filter { it in existing.grantedPermissions },
            updatedAtEpochMillis = nowEpochMillis()
        )
        store.saveInstallation(next)
        bump()
        return next
    }

    @Synchronized
    fun uninstall(schoolId: String, pluginId: String, reason: String = "USER_UNINSTALL"): ExtensionCleanupResult {
        val result = store.cleanupPlugin(schoolId, pluginId, reason)
        bump()
        return result
    }

    @Synchronized
    fun wipeAll(reason: String): ExtensionCleanupResult {
        val result = store.wipeAll(reason)
        bump()
        return result
    }

    fun scopedStorageCounts(schoolId: String, pluginId: String): Pair<Int, Int> =
        store.countScopedFiles("storage", schoolId, pluginId) to store.countScopedFiles("cache", schoolId, pluginId)

    fun putScopedStorageForTest(schoolId: String, pluginId: String, key: String, bytes: ByteArray, cache: Boolean = false) {
        store.putScopedBytes(if (cache) "cache" else "storage", schoolId, pluginId, key, bytes)
    }

    fun auditLines(): List<String> = store.auditLines()

    private fun requireInstallation(schoolId: String, pluginId: String): ExtensionInstallation =
        store.loadInstallation(schoolId, pluginId)
            ?: throw PackageException(PackageError.MANIFEST_INVALID, "插件未安装：" + pluginId)

    /**
     * Re-verify and expose a stored signed package for declarative UI/data consumers.
     * Persisted bytes remain evidence rather than trusted input: callers never receive
     * a manifest or artifact map unless the publisher signature and every digest pass.
     */
    fun verifiedStoredPackage(
        schoolId: String,
        pluginId: String,
        version: String? = null
    ): VerifiedExtensionPackage {
        val installation = requireInstallation(schoolId, pluginId)
        val targetVersion = version ?: installation.activeVersion
        return loadVerifiedStoredPackage(pluginId, targetVersion)
    }

    private fun loadStoredManifest(pluginId: String, version: String): ExtensionManifestV2 =
        loadVerifiedStoredPackage(pluginId, version).manifest

    private fun loadVerifiedStoredPackage(pluginId: String, version: String): VerifiedExtensionPackage {
        val bytes = store.readVersionManifestBytes(pluginId, version)
            ?: throw PackageException(PackageError.ROLLBACK_UNAVAILABLE, "目标插件版本不存在或已损坏：" + version)
        return ExtensionPackageVerifier.verify(
            bytes,
            store.readVersionArtifactMap(pluginId, version),
            keys,
            currentHostApi
        )
    }

    private fun extractUiSlotsJson(manifestBytes: ByteArray): String {
        val root = Json.parse(String(manifestBytes, Charsets.UTF_8)).asObjOrNull
        val slots = root?.fields?.get("ui_slots")
        return slots?.let(Json::canonical) ?: "[]"
    }

    private fun bump() {
        revisionState.value = revisionState.value + 1
    }
}
