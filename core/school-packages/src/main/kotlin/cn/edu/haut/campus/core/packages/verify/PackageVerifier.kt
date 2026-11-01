package cn.edu.haut.campus.core.packages.verify

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.bundle.PackageBundle
import cn.edu.haut.campus.core.packages.crypto.PackageSignature
import cn.edu.haut.campus.core.packages.crypto.PublisherKeyRegistry
import cn.edu.haut.campus.core.packages.manifest.PackageManifest
import cn.edu.haut.campus.core.packages.manifest.PackageManifestCodec

/**
 * 签名与内容校验结果。拒绝时携带结构化错误码和"只含元数据"的说明，
 * 便于 UI 给出可操作提示，同时避免把文件正文写进日志。
 */
sealed interface VerificationResult {

    data class Accepted(
        val manifest: PackageManifest,
        val bundleDigest: String,
        val manifestDigest: String
    ) : VerificationResult

    data class Rejected(
        val error: PackageError,
        val detail: String
    ) : VerificationResult
}

/**
 * 安装前校验：清单 → 发布者 → 签名 → 文件集合 → 逐个摘要。
 *
 * 顺序是刻意的：签名不通过就绝不进入摘要比较阶段，避免攻击者用"大量文件"
 * 引导宿主做无谓的哈希计算；文件集合不一致先于摘要不一致报告，
 * 让"少了一个文件"和"文件被改过"得到不同错误码。
 *
 * UNVERIFIED：验证策略来自本仓库自定的合成清单格式，未在真实学校包上跑过。
 */
object PackageVerifier {

    fun verify(
        manifestBytes: ByteArray,
        bundle: PackageBundle,
        signature: ByteArray,
        keys: PublisherKeyRegistry
    ): VerificationResult {
        val manifest = try {
            PackageManifestCodec.parse(manifestBytes)
        } catch (error: PackageException) {
            return VerificationResult.Rejected(error.error, error.message ?: error.error.name)
        } catch (error: IllegalArgumentException) {
            return VerificationResult.Rejected(PackageError.MANIFEST_INVALID, error.message ?: "清单不合法")
        }
        if (signature.isEmpty()) {
            return VerificationResult.Rejected(PackageError.SIGNATURE_MISSING, "包缺少签名：" + manifest.id)
        }
        val publicKey = keys.keyFor(manifest.publisher)
            ?: return VerificationResult.Rejected(
                PackageError.SIGNATURE_UNTRUSTED_PUBLISHER,
                "发布者不在信任清单：" + manifest.publisher
            )
        if (!PackageSignature.verify(publicKey, manifestBytes, signature)) {
            return VerificationResult.Rejected(
                PackageError.SIGNATURE_INVALID,
                "签名与清单摘要不匹配：" + manifest.id + "@" + manifest.version.value
            )
        }
        val declared = manifest.files.keys
        val actual = bundle.files.map { it.path }.toSet()
        val missing = declared - actual
        if (missing.isNotEmpty()) {
            return VerificationResult.Rejected(
                PackageError.FILE_MISSING,
                "包缺少清单声明的条目：" + missing.sorted().joinToString(", ")
            )
        }
        val unlisted = actual - declared
        if (unlisted.isNotEmpty()) {
            return VerificationResult.Rejected(
                PackageError.FILE_UNLISTED,
                "包包含清单未声明的条目：" + unlisted.sorted().joinToString(", ")
            )
        }
        val mismatched = manifest.files.keys.filter { path ->
            bundle.file(path)?.sha256 != manifest.files.getValue(path)
        }
        if (mismatched.isNotEmpty()) {
            return VerificationResult.Rejected(
                PackageError.FILE_HASH_MISMATCH,
                "文件摘要与清单不一致：" + mismatched.sorted().joinToString(", ")
            )
        }
        if (manifest.filesDigest != bundle.digest) {
            return VerificationResult.Rejected(
                PackageError.FILE_HASH_MISMATCH,
                "包摘要与清单 files 映射不一致：" + manifest.id
            )
        }
        val manifestDigest = try {
            Digests.sha256Hex(PackageManifestCodec.canonicalBytes(manifestBytes))
        } catch (error: IllegalArgumentException) {
            return VerificationResult.Rejected(PackageError.MANIFEST_INVALID, "清单无法规范化")
        }
        return VerificationResult.Accepted(
            manifest = manifest,
            bundleDigest = bundle.digest,
            manifestDigest = manifestDigest
        )
    }
}
