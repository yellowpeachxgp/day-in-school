package cn.edu.haut.campus.core.packages.store

import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.bundle.PackageBundle
import cn.edu.haut.campus.core.packages.bundle.PackageLimits
import cn.edu.haut.campus.core.packages.crypto.PublisherKeyRegistry
import cn.edu.haut.campus.core.packages.manifest.PackageManifest
import cn.edu.haut.campus.core.packages.manifest.PackageManifestCodec
import cn.edu.haut.campus.core.packages.verify.PackageVerifier
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.UUID

/**
 * 文件系统实现：staging → versions/<pkg>/<digest> → index.json。
 *
 * 关键约束：
 * 1. 暂存目录只接受通过路径策略校验的条目，写盘前再次做规范化前缀检查并拒绝软链接；
 * 2. 校验读的是磁盘上的真实字节，而不是内存里的包体，避免"校验内容和安装内容不一致"；
 * 3. 激活先落版本目录、再原子替换 index.json；index 没写成，则新版本存在但未激活；
 * 4. 回滚只接受 index 里记录过的摘要，且要求对应版本目录与清单仍可读。
 *
 * UNVERIFIED：索引格式与目录布局是本模块自定的，未在 Android 设备的真实存储上验证
 * （例如 scoped storage、断电恢复与低存储空间行为）。
 */
class FilePackageStore(
    root: Path,
    private val limits: PackageLimits = PackageLimits.DEFAULT
) : PackageStore {

    private data class ActivationRecord(
        val version: String,
        val digest: String,
        val manifestDigest: String,
        val activatedAt: Instant
    )

    private data class VerifiedRecord(
        val verified: StagedVerification.Verified,
        val manifestBytes: ByteArray
    )

    private val root: Path = root.toAbsolutePath().normalize()
    private val stagingDir: Path = this.root.resolve("staging")
    private val versionsDir: Path = this.root.resolve("versions")
    private val manifestsDir: Path = this.root.resolve("manifests")
    private val indexPath: Path = this.root.resolve("index.json")

    private val stagedById = LinkedHashMap<String, StagedPackage>()
    private val verifiedByStaging = LinkedHashMap<String, VerifiedRecord>()

    init {
        Files.createDirectories(stagingDir)
        Files.createDirectories(versionsDir)
        Files.createDirectories(manifestsDir)
    }

    @Synchronized
    override fun stage(packageId: String, bundle: PackageBundle): StagedPackage {
        require(packageId.isNotBlank()) { "包 ID 不能为空" }
        val stagingId = "staged-" + UUID.randomUUID().toString().replace("-", "")
        val directory = stagingDir.resolve(stagingId)
        try {
            Files.createDirectories(directory)
            bundle.files.forEach { file ->
                val target = directory.resolve(file.path).normalize()
                if (!target.startsWith(directory)) {
                    throw PackageException(PackageError.UNSAFE_PATH, "条目路径越出暂存目录：" + file.path)
                }
                requireNoSymlink(directory, file.path)
                Files.createDirectories(target.parent)
                Files.write(
                    target,
                    file.copyOfContent(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                )
            }
        } catch (error: Exception) {
            deleteRecursively(directory)
            if (error is PackageException) throw error
            if (error is IllegalArgumentException) throw error
            throw PackageException(PackageError.STAGING_FAILED, "暂存写入失败：" + error.javaClass.simpleName)
        }
        val staged = StagedPackage(stagingId, packageId, bundle)
        stagedById[stagingId] = staged
        return staged
    }

    @Synchronized
    override fun verify(
        staged: StagedPackage,
        manifestBytes: ByteArray,
        signature: ByteArray,
        keys: PublisherKeyRegistry
    ): StagedVerification {
        if (stagedById[staged.stagingId] == null) {
            return StagedVerification.Rejected(PackageError.NOT_STAGED, "暂存记录不存在或已被丢弃")
        }
        val directory = stagingDir.resolve(staged.stagingId)
        if (!Files.isDirectory(directory)) {
            return StagedVerification.Rejected(PackageError.NOT_STAGED, "暂存目录已不存在")
        }
        val diskBundle = try {
            readBundle(directory)
        } catch (error: PackageException) {
            return StagedVerification.Rejected(error.error, error.message ?: error.error.name)
        }
        val verification = PackageVerifier.verify(manifestBytes, diskBundle, signature, keys)
        val result = StagedVerification.from(staged, verification)
        if (result is StagedVerification.Verified) {
            verifiedByStaging[staged.stagingId] = VerifiedRecord(result, manifestBytes.copyOf())
        }
        return result
    }

    @Synchronized
    override fun activate(verified: StagedVerification.Verified): ActivePackage {
        val record = verifiedByStaging[verified.stage.stagingId]
            ?: throw PackageException(PackageError.NOT_VERIFIED, "该包未通过校验，不能激活")
        if (record.verified.digest != verified.digest || record.verified.manifest.id != verified.manifest.id) {
            throw PackageException(PackageError.NOT_VERIFIED, "校验记录与激活请求不一致")
        }
        val packageId = record.verified.manifest.id
        val digest = record.verified.digest
        val staging = stagingDir.resolve(verified.stage.stagingId)
        val versionDir = versionsDir.resolve(packageId).resolve(digest)
        try {
            Files.createDirectories(versionDir.parent)
            if (!Files.exists(versionDir)) {
                moveDirectory(staging, versionDir)
            } else {
                deleteRecursively(staging)
            }
            val manifestFile = manifestsDir.resolve(packageId).resolve(digest + ".json")
            Files.createDirectories(manifestFile.parent)
            Files.write(manifestFile, record.manifestBytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            val index = readIndex()
            val history = index.getOrPut(packageId) { mutableListOf() }
            val last = history.lastOrNull()
            if (last == null || last.digest != digest) {
                history += ActivationRecord(
                    version = record.verified.manifest.version.value,
                    digest = digest,
                    manifestDigest = record.verified.manifestDigest,
                    activatedAt = Instant.now()
                )
            }
            writeIndex(index)
            stagedById.remove(verified.stage.stagingId)
            verifiedByStaging.remove(verified.stage.stagingId)
            return activeOf(packageId, history)
                ?: throw PackageException(PackageError.ACTIVATION_FAILED, "激活后无法读回清单")
        } catch (error: PackageException) {
            throw error
        } catch (error: Exception) {
            throw PackageException(
                PackageError.ACTIVATION_FAILED,
                "激活失败，活动版本未改变：" + error.javaClass.simpleName
            )
        }
    }

    @Synchronized
    override fun discard(staged: StagedPackage) {
        stagedById.remove(staged.stagingId)
        verifiedByStaging.remove(staged.stagingId)
        deleteRecursively(stagingDir.resolve(staged.stagingId))
    }

    @Synchronized
    override fun active(packageId: String): ActivePackage? {
        val history = readIndex()[packageId] ?: return null
        return activeOf(packageId, history)
    }

    @Synchronized
    override fun rollback(packageId: String): ActivePackage {
        val index = readIndex()
        val history = index[packageId]
            ?: throw PackageException(PackageError.ROLLBACK_UNAVAILABLE, "没有该包的任何激活记录")
        if (history.size < 2) {
            throw PackageException(PackageError.ROLLBACK_UNAVAILABLE, "没有更早的已验证版本可回滚")
        }
        val remaining = history.dropLast(1)
        val previous = remaining.last()
        if (!Files.isDirectory(versionsDir.resolve(packageId).resolve(previous.digest))) {
            throw PackageException(PackageError.ROLLBACK_UNAVAILABLE, "上一个版本的目录不可用")
        }
        index[packageId] = remaining.toMutableList()
        writeIndex(index)
        return activeOf(packageId, remaining)
            ?: throw PackageException(PackageError.ROLLBACK_UNAVAILABLE, "上一个版本的清单不可用")
    }

    @Synchronized
    override fun verifiedDigests(packageId: String): List<String> =
        readIndex()[packageId].orEmpty().map { it.digest }.distinct()

    private fun activeOf(packageId: String, history: List<ActivationRecord>): ActivePackage? {
        if (history.isEmpty()) return null
        val current = history.last()
        val manifest = loadManifest(packageId, current.digest) ?: return null
        return ActivePackage(
            manifest = manifest,
            digest = current.digest,
            activatedAt = current.activatedAt,
            previousDigest = if (history.size >= 2) history[history.size - 2].digest else null
        )
    }

    private fun loadManifest(packageId: String, digest: String): PackageManifest? {
        val file = manifestsDir.resolve(packageId).resolve(digest + ".json")
        if (!Files.isRegularFile(file)) return null
        return try {
            PackageManifestCodec.parse(Files.readAllBytes(file))
        } catch (_: Exception) {
            null
        }
    }

    /** 读取暂存目录里的真实字节；拒绝软链接、非普通文件与设备文件。 */
    private fun readBundle(directory: Path): PackageBundle {
        val contents = LinkedHashMap<String, ByteArray>()
        var total = 0L
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = directory.relativize(file).joinToString("/") { it.toString() }
                if (Files.isSymbolicLink(file) || !attrs.isRegularFile) {
                    throw PackageException(PackageError.UNSAFE_PATH, "暂存条目不是普通文件：" + relative)
                }
                if (attrs.size() > limits.maxFileBytes) {
                    throw PackageException(PackageError.SIZE_LIMIT_EXCEEDED, "暂存条目超出上限：" + relative)
                }
                total += attrs.size()
                if (total > limits.maxTotalBytes) {
                    throw PackageException(PackageError.SIZE_LIMIT_EXCEEDED, "暂存内容超出上限")
                }
                contents[relative] = Files.readAllBytes(file)
                return FileVisitResult.CONTINUE
            }
        })
        if (contents.isEmpty()) {
            throw PackageException(PackageError.EMPTY_PACKAGE, "暂存目录为空")
        }
        return PackageBundle.ofContents(contents, limits)
    }

    private fun requireNoSymlink(directory: Path, relativePath: String) {
        var cursor: Path? = directory
        relativePath.split('/').dropLast(1).forEach { segment ->
            cursor = cursor?.resolve(segment)
            val current = cursor ?: return
            if (Files.isSymbolicLink(current)) {
                throw PackageException(PackageError.UNSAFE_PATH, "暂存路径包含软链接：" + relativePath)
            }
        }
    }

    private fun moveDirectory(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun readIndex(): LinkedHashMap<String, MutableList<ActivationRecord>> {
        val index = LinkedHashMap<String, MutableList<ActivationRecord>>()
        if (!Files.isRegularFile(indexPath)) return index
        val document = try {
            Json.parse(String(Files.readAllBytes(indexPath), StandardCharsets.UTF_8))
        } catch (_: Exception) {
            return index
        }
        document.arr("packages").orEmpty().forEach { item ->
            val packageId = item.str("packageId") ?: return@forEach
            val records = mutableListOf<ActivationRecord>()
            item.arr("activations").orEmpty().forEach { activation ->
                records += ActivationRecord(
                    version = activation.str("version").orEmpty(),
                    digest = activation.str("digest").orEmpty(),
                    manifestDigest = activation.str("manifestDigest").orEmpty(),
                    activatedAt = Instant.ofEpochSecond(activation.obj("activatedAt")?.intOrNull()?.toLong() ?: 0L)
                )
            }
            index[packageId] = records
        }
        return index
    }

    private fun writeIndex(index: Map<String, List<ActivationRecord>>) {
        val packages = index.entries.map { (packageId, records) ->
            JsonValue.Obj(
                linkedMapOf(
                    "packageId" to JsonValue.Str(packageId),
                    "activations" to JsonValue.Arr(
                        records.map { record ->
                            JsonValue.Obj(
                                linkedMapOf(
                                    "version" to JsonValue.Str(record.version),
                                    "digest" to JsonValue.Str(record.digest),
                                    "manifestDigest" to JsonValue.Str(record.manifestDigest),
                                    "activatedAt" to JsonValue.Num(record.activatedAt.epochSecond.toString())
                                )
                            )
                        }
                    )
                )
            )
        }
        val document = JsonValue.Obj(
            linkedMapOf(
                "format" to JsonValue.Str(INDEX_FORMAT),
                "packages" to JsonValue.Arr(packages)
            )
        )
        Files.createDirectories(indexPath.parent)
        val temporary = indexPath.resolveSibling("index.json.tmp")
        Files.writeString(temporary, Json.canonical(document), StandardCharsets.UTF_8)
        try {
            Files.move(
                temporary,
                indexPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, indexPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val INDEX_FORMAT = "dis-campus-package-index/1"
    }
}
