package cn.edu.haut.campus.core.packages.v2

import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Durable account+school scoped v2 installation state. */
data class ExtensionInstallation(
    val pluginId: String,
    val schoolId: String,
    val name: String,
    val schemaFormat: String,
    val activeVersion: String,
    val previousVersion: String?,
    val pendingVersion: String?,
    val pendingPermissions: List<String>,
    val status: String,
    val grantedPermissions: List<String>,
    val uiSlotsJson: String,
    val publisherId: String,
    val installedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val disabledReason: String? = null
)

data class ExtensionCleanupResult(
    val installationFiles: Int,
    val storageFiles: Int,
    val cacheFiles: Int
)

/** File count and byte usage of one plugin-scoped storage/cache partition. */
data class ExtensionScopedStorageUsage(
    val files: Int,
    val bytes: Long
)

/**
 * Profile-local file store for declarative extension packages.
 *
 * This module deliberately does not use Room v19: W6-01a persists v2 package state in the
 * existing per-profile filesystem partition while the legacy installed_extension_plugins
 * table remains a read-only compatibility projection target. Artifact writes are staged;
 * an installation pointer is changed only by an atomic JSON rename, so a mid-write fault
 * leaves the previously active version intact.
 */
class ExtensionPluginFileStore(
    root: Path,
    private val writeFaultHook: ((relativePath: String, writeIndex: Int) -> Unit)? = null
) {
    private val root: Path = root.toAbsolutePath().normalize()
    private val stagingDir = this.root.resolve("staging")
    private val versionsDir = this.root.resolve("versions")
    private val installationsDir = this.root.resolve("installations")
    private val storageDir = this.root.resolve("storage")
    private val cacheDir = this.root.resolve("cache")
    private val auditPath = this.root.resolve("audit.jsonl")

    init {
        Files.createDirectories(stagingDir)
        Files.createDirectories(versionsDir)
        Files.createDirectories(installationsDir)
        Files.createDirectories(storageDir)
        Files.createDirectories(cacheDir)
        deleteRecursively(stagingDir)
        Files.createDirectories(stagingDir)
    }

    @Synchronized
    fun saveVersion(verified: VerifiedExtensionPackage) {
        val pluginId = verified.manifest.pluginId
        val version = verified.manifest.version.value
        val target = versionDir(pluginId, version)
        if (Files.isDirectory(target) && Files.isRegularFile(target.resolve("manifest.json"))) return
        if (Files.isDirectory(target)) deleteRecursively(target)
        val staging = stagingDir.resolve("staged-" + UUID.randomUUID().toString().replace("-", ""))
        var writeIndex = 0
        try {
            Files.createDirectories(staging)
            writeFileWithHook(staging.resolve("manifest.json"), verified.manifestBytes, "manifest.json", writeIndex++)
            for ((relativePath, bytes) in verified.artifactMap.toSortedMap()) {
                ExtensionManifestV2Codec.assertSafeRelativePath(relativePath)
                val artifactTarget = staging.resolve("artifacts").resolve(relativePath).normalize()
                val artifactRoot = staging.resolve("artifacts").normalize()
                if (!artifactTarget.startsWith(artifactRoot)) {
                    throw PackageException(PackageError.UNSAFE_PATH, "产物路径越出插件暂存目录：" + relativePath)
                }
                writeFileWithHook(artifactTarget, bytes, relativePath, writeIndex++)
            }
            Files.createDirectories(target.parent)
            moveDirectory(staging, target)
        } catch (error: Exception) {
            deleteRecursively(staging)
            if (error is PackageException) throw error
            throw PackageException(PackageError.STAGING_FAILED, "扩展插件暂存失败：" + error.javaClass.simpleName)
        }
    }

    fun versionExists(pluginId: String, version: String): Boolean =
        Files.isRegularFile(versionDir(pluginId, version).resolve("manifest.json"))

    @Synchronized
    fun deleteVersion(pluginId: String, version: String) {
        deleteRecursively(versionDir(pluginId, version))
    }

    fun readVersionManifestBytes(pluginId: String, version: String): ByteArray? {
        val file = versionDir(pluginId, version).resolve("manifest.json")
        if (!Files.isRegularFile(file)) return null
        return Files.readAllBytes(file)
    }

    /** Read stored artifact bytes for re-verification; keys are canonical relative POSIX paths. */
    fun readVersionArtifactMap(pluginId: String, version: String): Map<String, ByteArray> {
        val artifactsRoot = versionDir(pluginId, version).resolve("artifacts")
        if (!Files.isDirectory(artifactsRoot)) return emptyMap()
        val result = linkedMapOf<String, ByteArray>()
        Files.walk(artifactsRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                val relative = artifactsRoot.relativize(file).joinToString("/") { it.toString() }
                result[relative] = Files.readAllBytes(file)
            }
        }
        return result
    }

    @Synchronized
    fun saveInstallation(installation: ExtensionInstallation) {
        val file = installationFile(installation.schoolId, installation.pluginId)
        writeJsonAtomic(file, installationToJson(installation))
    }

    fun loadInstallation(schoolId: String, pluginId: String): ExtensionInstallation? {
        val file = installationFile(schoolId, pluginId)
        if (!Files.isRegularFile(file)) return null
        return readInstallation(file)
    }

    fun listInstallations(): List<ExtensionInstallation> {
        if (!Files.isDirectory(installationsDir)) return emptyList()
        val result = mutableListOf<ExtensionInstallation>()
        Files.list(installationsDir).use { schoolStreams ->
            for (schoolDir in schoolStreams) {
                if (!Files.isDirectory(schoolDir)) continue
                Files.list(schoolDir).use { files ->
                    for (file in files) {
                        if (file.fileName.toString().endsWith(".json")) {
                            runCatching { readInstallation(file) }.getOrNull()?.let(result::add)
                        }
                    }
                }
            }
        }
        return result.sortedWith(compareBy<ExtensionInstallation> { it.schoolId }.thenBy { it.pluginId })
    }

    @Synchronized
    fun deleteInstallation(schoolId: String, pluginId: String) {
        Files.deleteIfExists(installationFile(schoolId, pluginId))
    }

    fun putScopedBytes(kind: String, schoolId: String, pluginId: String, key: String, bytes: ByteArray) {
        require(kind == "storage" || kind == "cache") { "未知插件分区类型" }
        val base = if (kind == "storage") storageDir else cacheDir
        val target = base.resolve(encodeKey(schoolId)).resolve(encodeKey(pluginId)).resolve(encodeKey(key) + ".bin")
        Files.createDirectories(target.parent)
        writeBytesAtomic(target, bytes)
    }

    fun countScopedFiles(kind: String, schoolId: String, pluginId: String): Int =
        measureScopedFiles(kind, schoolId, pluginId).files

    fun measureScopedFiles(kind: String, schoolId: String, pluginId: String): ExtensionScopedStorageUsage {
        val base = if (kind == "storage") storageDir else cacheDir
        val dir = base.resolve(encodeKey(schoolId)).resolve(encodeKey(pluginId))
        return measureRegularFiles(dir)
    }

    @Synchronized
    fun cleanupPlugin(schoolId: String, pluginId: String, reason: String): ExtensionCleanupResult {
        val installationCount = if (Files.exists(installationFile(schoolId, pluginId))) 1 else 0
        val storageCount = countScopedFiles("storage", schoolId, pluginId)
        val cacheCount = countScopedFiles("cache", schoolId, pluginId)
        Files.deleteIfExists(installationFile(schoolId, pluginId))
        deleteRecursively(storageDir.resolve(encodeKey(schoolId)).resolve(encodeKey(pluginId)))
        deleteRecursively(cacheDir.resolve(encodeKey(schoolId)).resolve(encodeKey(pluginId)))
        appendAudit("UNINSTALL_PLUGIN", pluginId, schoolId, reason)
        return ExtensionCleanupResult(installationCount, storageCount, cacheCount)
    }

    @Synchronized
    fun wipeAll(reason: String): ExtensionCleanupResult {
        val installationCount = countRegularFiles(installationsDir)
        val storageCount = countRegularFiles(storageDir)
        val cacheCount = countRegularFiles(cacheDir)
        deleteRecursively(installationsDir)
        deleteRecursively(storageDir)
        deleteRecursively(cacheDir)
        deleteRecursively(stagingDir)
        Files.createDirectories(installationsDir)
        Files.createDirectories(storageDir)
        Files.createDirectories(cacheDir)
        Files.createDirectories(stagingDir)
        appendAudit("WIPE_EXTENSION_PLUGIN_DATA", "*", "*", reason)
        return ExtensionCleanupResult(installationCount, storageCount, cacheCount)
    }

    fun auditLines(): List<String> =
        if (Files.isRegularFile(auditPath)) Files.readAllLines(auditPath, StandardCharsets.UTF_8) else emptyList()

    private fun writeFileWithHook(target: Path, bytes: ByteArray, relativePath: String, writeIndex: Int) {
        Files.createDirectories(target.parent)
        writeFaultHook?.invoke(relativePath, writeIndex)
        writeBytesAtomic(target, bytes)
    }

    private fun versionDir(pluginId: String, version: String): Path =
        versionsDir.resolve(encodeKey(pluginId)).resolve(encodeKey(version))

    private fun installationFile(schoolId: String, pluginId: String): Path =
        installationsDir.resolve(encodeKey(schoolId)).resolve(encodeKey(pluginId) + ".json")

    private fun encodeKey(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun writeJsonAtomic(file: Path, value: JsonValue) {
        writeBytesAtomic(file, Json.canonical(value).toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeBytesAtomic(file: Path, bytes: ByteArray) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp-" + UUID.randomUUID().toString().replace("-", ""))
        Files.write(tmp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try {
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun moveDirectory(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun countRegularFiles(dir: Path): Int = measureRegularFiles(dir).files

    private fun measureRegularFiles(dir: Path): ExtensionScopedStorageUsage {
        if (!Files.isDirectory(dir)) return ExtensionScopedStorageUsage(0, 0L)
        var count = 0
        var bytes = 0L
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                count += 1
                bytes += runCatching { Files.size(file) }.getOrDefault(0L)
            }
        }
        return ExtensionScopedStorageUsage(count, bytes)
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun appendAudit(action: String, pluginId: String, schoolId: String, reason: String) {
        val row = JsonValue.Obj(mapOf(
            "action" to JsonValue.Str(action),
            "plugin_id" to JsonValue.Str(pluginId),
            "school_id" to JsonValue.Str(schoolId),
            "reason" to JsonValue.Str(reason),
            "created_at" to JsonValue.Str(Instant.now().toString())
        ))
        Files.createDirectories(auditPath.parent)
        Files.write(
            auditPath,
            (Json.canonical(row) + "\n").toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
    }

    private fun readInstallation(file: Path): ExtensionInstallation {
        val root = Json.parse(String(Files.readAllBytes(file), StandardCharsets.UTF_8)).asObjOrNull
            ?: throw PackageException(PackageError.MANIFEST_INVALID, "插件安装状态不是 JSON 对象")
        fun str(key: String): String = root.fields[key]?.stringOrNull()
            ?: throw PackageException(PackageError.MANIFEST_INVALID, "插件安装状态缺少字段：" + key)
        fun optionalStr(key: String): String? = root.fields[key]?.stringOrNull()
        fun long(key: String): Long = root.fields[key]?.let { (it as? JsonValue.Num)?.literal?.toLongOrNull() }
            ?: throw PackageException(PackageError.MANIFEST_INVALID, "插件安装状态缺少时间字段：" + key)
        fun strList(key: String): List<String> = root.fields[key]?.asArrOrNull?.items?.mapNotNull { it.stringOrNull() } ?: emptyList()
        return ExtensionInstallation(
            pluginId = str("plugin_id"),
            schoolId = str("school_id"),
            name = str("name"),
            schemaFormat = str("schema_format"),
            activeVersion = str("active_version"),
            previousVersion = optionalStr("previous_version"),
            pendingVersion = optionalStr("pending_version"),
            pendingPermissions = strList("pending_permissions"),
            status = str("status"),
            grantedPermissions = strList("granted_permissions"),
            uiSlotsJson = str("ui_slots_json"),
            publisherId = str("publisher_id"),
            installedAtEpochMillis = long("installed_at_epoch_millis"),
            updatedAtEpochMillis = long("updated_at_epoch_millis"),
            disabledReason = optionalStr("disabled_reason")
        )
    }

    private fun installationToJson(installation: ExtensionInstallation): JsonValue {
        fun strList(values: List<String>) = JsonValue.Arr(values.map { JsonValue.Str(it) })
        val fields = linkedMapOf<String, JsonValue>(
            "plugin_id" to JsonValue.Str(installation.pluginId),
            "school_id" to JsonValue.Str(installation.schoolId),
            "name" to JsonValue.Str(installation.name),
            "schema_format" to JsonValue.Str(installation.schemaFormat),
            "active_version" to JsonValue.Str(installation.activeVersion),
            "pending_permissions" to strList(installation.pendingPermissions),
            "status" to JsonValue.Str(installation.status),
            "granted_permissions" to strList(installation.grantedPermissions),
            "ui_slots_json" to JsonValue.Str(installation.uiSlotsJson),
            "publisher_id" to JsonValue.Str(installation.publisherId),
            "installed_at_epoch_millis" to JsonValue.Num(installation.installedAtEpochMillis.toString()),
            "updated_at_epoch_millis" to JsonValue.Num(installation.updatedAtEpochMillis.toString())
        )
        installation.previousVersion?.let { fields["previous_version"] = JsonValue.Str(it) }
        installation.pendingVersion?.let { fields["pending_version"] = JsonValue.Str(it) }
        installation.disabledReason?.let { fields["disabled_reason"] = JsonValue.Str(it) }
        return JsonValue.Obj(fields)
    }
}
