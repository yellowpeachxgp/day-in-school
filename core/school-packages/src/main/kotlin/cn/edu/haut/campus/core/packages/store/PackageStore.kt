package cn.edu.haut.campus.core.packages.store

import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.bundle.PackageBundle
import cn.edu.haut.campus.core.packages.crypto.PublisherKeyRegistry
import cn.edu.haut.campus.core.packages.manifest.PackageManifest
import cn.edu.haut.campus.core.packages.manifest.PackageVersion
import cn.edu.haut.campus.core.packages.verify.PackageVerifier
import cn.edu.haut.campus.core.packages.verify.VerificationResult
import java.time.Instant

/**
 * 当前生效的包版本。previousDigest 指向激活顺序里更早的已验证摘要，
 * 为 null 表示这是该包第一次激活，没有可回滚目标。
 */
data class ActivePackage(
    val manifest: PackageManifest,
    val digest: String,
    val activatedAt: Instant,
    val previousDigest: String?
) {
    val packageId: String get() = manifest.id
    val version: PackageVersion get() = manifest.version
}

/** 已写入暂存区、尚未校验的包。暂存内容永远不会被激活，只有通过校验才能前进。 */
class StagedPackage internal constructor(
    val stagingId: String,
    val packageId: String,
    val bundle: PackageBundle
) {
    override fun toString(): String = "StagedPackage(" + stagingId + ", " + packageId + ")"
}

/** staged → verified 的状态迁移结果。 */
sealed interface StagedVerification {

    /** 已通过签名与内容校验，可以激活；digest 是真实内容的摘要。 */
    data class Verified(
        val stage: StagedPackage,
        val manifest: PackageManifest,
        val digest: String,
        val manifestDigest: String
    ) : StagedVerification

    data class Rejected(
        val error: PackageError,
        val detail: String
    ) : StagedVerification

    companion object {
        fun from(stage: StagedPackage, result: VerificationResult): StagedVerification = when (result) {
            is VerificationResult.Rejected -> Rejected(result.error, result.detail)
            is VerificationResult.Accepted -> {
                if (result.manifest.id != stage.packageId) {
                    Rejected(
                        PackageError.PACKAGE_ID_MISMATCH,
                        "清单包 ID 与暂存登记不一致：" + result.manifest.id
                    )
                } else {
                    Verified(stage, result.manifest, result.bundleDigest, result.manifestDigest)
                }
            }
        }
    }
}

/**
 * 不可变包存储：暂存 → 校验 → 激活 → 回滚。
 *
 * 不变式：
 * 1. 未通过 [verify] 的内容不能被 [activate]；
 * 2. 激活失败不改变当前活动版本；
 * 3. [rollback] 只能回到此前已激活且仍然可读的摘要，绝不回到"未校验"内容。
 */
interface PackageStore {

    fun stage(packageId: String, bundle: PackageBundle): StagedPackage

    fun verify(
        staged: StagedPackage,
        manifestBytes: ByteArray,
        signature: ByteArray,
        keys: PublisherKeyRegistry
    ): StagedVerification

    fun activate(verified: StagedVerification.Verified): ActivePackage

    fun discard(staged: StagedPackage)

    fun active(packageId: String): ActivePackage?

    fun rollback(packageId: String): ActivePackage

    /** 按激活顺序去重的已验证摘要；回滚目标一定出现在其中。 */
    fun verifiedDigests(packageId: String): List<String>
}

/**
 * 纯内存实现：用于测试、单元验证与"关闭磁盘写入"的降级模式。
 * 生命周期与进程一致，重启后不保留任何包。
 */
class InMemoryPackageStore : PackageStore {

    private data class Activation(
        val manifest: PackageManifest,
        val digest: String,
        val manifestDigest: String,
        val activatedAt: Instant
    )

    private val stagedById = LinkedHashMap<String, StagedPackage>()
    private val verifiedByStaging = LinkedHashMap<String, StagedVerification.Verified>()
    private val journal = LinkedHashMap<String, MutableList<Activation>>()
    private var sequence = 0

    @Synchronized
    override fun stage(packageId: String, bundle: PackageBundle): StagedPackage {
        require(packageId.isNotBlank()) { "包 ID 不能为空" }
        val staged = StagedPackage("staged-" + (++sequence), packageId, bundle)
        stagedById[staged.stagingId] = staged
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
        val result = PackageVerifier.verify(manifestBytes, staged.bundle, signature, keys)
        val verification = StagedVerification.from(staged, result)
        if (verification is StagedVerification.Verified) {
            verifiedByStaging[staged.stagingId] = verification
        }
        return verification
    }

    @Synchronized
    override fun activate(verified: StagedVerification.Verified): ActivePackage {
        val record = verifiedByStaging[verified.stage.stagingId]
            ?: throw PackageException(PackageError.NOT_VERIFIED, "该包未通过校验，不能激活")
        if (record.digest != verified.digest || record.manifest.id != verified.manifest.id) {
            throw PackageException(PackageError.NOT_VERIFIED, "校验记录与激活请求不一致")
        }
        val packageId = record.manifest.id
        val history = journal.getOrPut(packageId) { mutableListOf() }
        val last = history.lastOrNull()
        if (last != null && last.digest == record.digest) {
            history[history.size - 1] = last.copy(activatedAt = Instant.now())
        } else {
            history += Activation(record.manifest, record.digest, record.manifestDigest, Instant.now())
        }
        stagedById.remove(verified.stage.stagingId)
        verifiedByStaging.remove(verified.stage.stagingId)
        return activeOf(history)
    }

    @Synchronized
    override fun discard(staged: StagedPackage) {
        stagedById.remove(staged.stagingId)
        verifiedByStaging.remove(staged.stagingId)
    }

    @Synchronized
    override fun active(packageId: String): ActivePackage? {
        val history = journal[packageId] ?: return null
        if (history.isEmpty()) return null
        return activeOf(history)
    }

    @Synchronized
    override fun rollback(packageId: String): ActivePackage {
        val history = journal[packageId]
            ?: throw PackageException(PackageError.ROLLBACK_UNAVAILABLE, "没有该包的任何激活记录")
        if (history.size < 2) {
            throw PackageException(PackageError.ROLLBACK_UNAVAILABLE, "没有更早的已验证版本可回滚")
        }
        history.removeAt(history.size - 1)
        return activeOf(history)
    }

    @Synchronized
    override fun verifiedDigests(packageId: String): List<String> =
        journal[packageId].orEmpty().map { it.digest }.distinct()

    private fun activeOf(history: MutableList<Activation>): ActivePackage {
        val current = history.last()
        return ActivePackage(
            manifest = current.manifest,
            digest = current.digest,
            activatedAt = current.activatedAt,
            previousDigest = if (history.size >= 2) history[history.size - 2].digest else null
        )
    }
}
