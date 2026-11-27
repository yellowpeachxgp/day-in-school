package cn.edu.haut.campus.core.packages.v2

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.crypto.Ed25519
import cn.edu.haut.campus.core.packages.crypto.PublisherKeyRegistry
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Verified signed v2 package payload; artifacts are defensive copies and are never executed. */
data class VerifiedExtensionPackage(
    val manifest: ExtensionManifestV2,
    val manifestBytes: ByteArray,
    val artifactMap: Map<String, ByteArray>,
    val manifestDigest: String,
    val publisherId: String
) {
    fun copyOfArtifacts(): Map<String, ByteArray> = artifactMap.mapValues { it.value.copyOf() }
}

/** Decode host-trust-root public keys. Cloud tooling stores Ed25519 as X.509/SPKI DER base64. */
object ExtensionPublicKeyCodec {
    private val SPKI_ED25519_PREFIX = byteArrayOf(
        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00
    )

    fun spkiBase64ToRaw(base64: String): ByteArray {
        val der = try {
            Base64.getDecoder().decode(base64.trim())
        } catch (error: IllegalArgumentException) {
            throw PackageException(PackageError.SIGNATURE_INVALID, "发布者公钥不是合法 Base64")
        }
        if (der.size != SPKI_ED25519_PREFIX.size + Ed25519.PUBLIC_KEY_BYTES ||
            !der.copyOfRange(0, SPKI_ED25519_PREFIX.size).contentEquals(SPKI_ED25519_PREFIX)
        ) {
            throw PackageException(PackageError.SIGNATURE_INVALID, "发布者公钥不是 Ed25519 SPKI DER")
        }
        return der.copyOfRange(SPKI_ED25519_PREFIX.size, der.size)
    }
}

/**
 * dis-extension/v2 signature + integrity verifier.
 *
 * The signed payload is the canonical manifest object with the signature member removed,
 * matching cloud plugin-sdk. Artifact bytes are verified declarative data only; this
 * verifier has no classloader, script engine, Dex loader, or execution path.
 */
object ExtensionPackageVerifier {

    fun verify(
        manifestBytes: ByteArray,
        artifactMap: Map<String, ByteArray>,
        keys: PublisherKeyRegistry,
        currentHostApi: Int = 2
    ): VerifiedExtensionPackage {
        val manifestText = String(manifestBytes, StandardCharsets.UTF_8)
        val manifest = try {
            ExtensionManifestV2Codec.parseOrAdapt(manifestText, currentHostApi)
        } catch (error: PackageException) {
            throw error
        } catch (error: Exception) {
            throw PackageException(PackageError.MANIFEST_INVALID, "插件清单解析失败：" + error.javaClass.simpleName)
        }
        val signature = manifest.signature
            ?: throw PackageException(PackageError.SIGNATURE_MISSING, "dis-extension/v2 清单缺少 Ed25519 签名")
        val publisherId = signature.keyId
        val publicKey = keys.keyFor(publisherId)
            ?: throw PackageException(
                PackageError.SIGNATURE_UNTRUSTED_PUBLISHER,
                "插件发布者不在信任清单：" + publisherId
            )

        val root = try {
            Json.parse(manifestText).asObjOrNull
                ?: throw PackageException(PackageError.MANIFEST_INVALID, "插件清单顶层必须是对象")
        } catch (error: PackageException) {
            throw error
        } catch (error: Exception) {
            throw PackageException(PackageError.MANIFEST_INVALID, "插件清单 JSON 无法规范化")
        }
        val unsigned = root.copy(fields = root.fields.filterKeys { it != "signature" })
        val canonicalBytes = Json.canonical(unsigned).toByteArray(StandardCharsets.UTF_8)
        val signatureBytes = try {
            Base64.getDecoder().decode(signature.valueBase64)
        } catch (error: IllegalArgumentException) {
            throw PackageException(PackageError.SIGNATURE_INVALID, "插件签名不是合法 Base64")
        }
        if (!Ed25519.verify(Ed25519.toPublicKey(publicKey), canonicalBytes, signatureBytes)) {
            throw PackageException(
                PackageError.SIGNATURE_INVALID,
                "插件签名与规范化摘要不匹配：" + manifest.pluginId + "@" + manifest.version.value
            )
        }

        val declared = manifest.entryArtifacts.associateBy { it.path }
        val actualKeys = artifactMap.keys
        val missing = declared.keys - actualKeys
        if (missing.isNotEmpty()) {
            throw PackageException(
                PackageError.FILE_MISSING,
                "插件包缺少清单声明的产物：" + missing.sorted().joinToString(", ")
            )
        }
        val unlisted = actualKeys - declared.keys
        if (unlisted.isNotEmpty()) {
            throw PackageException(
                PackageError.FILE_UNLISTED,
                "插件包包含清单未声明的产物：" + unlisted.sorted().joinToString(", ")
            )
        }
        val copied = LinkedHashMap<String, ByteArray>()
        for ((artifactPath, declaredArtifact) in declared.entries.sortedBy { it.key }) {
            ExtensionManifestV2Codec.assertSafeRelativePath(artifactPath)
            val bytes = artifactMap.getValue(artifactPath)
            if (bytes.size.toLong() != declaredArtifact.sizeBytes) {
                throw PackageException(
                    PackageError.FILE_HASH_MISMATCH,
                    "插件产物大小与清单不一致：" + artifactPath
                )
            }
            val digest = Digests.sha256Hex(bytes)
            if (!digest.equals(declaredArtifact.sha256, ignoreCase = true)) {
                throw PackageException(
                    PackageError.FILE_HASH_MISMATCH,
                    "插件产物摘要与清单不一致：" + artifactPath
                )
            }
            copied[artifactPath] = bytes.copyOf()
        }
        return VerifiedExtensionPackage(
            manifest = manifest,
            manifestBytes = manifestBytes.copyOf(),
            artifactMap = copied,
            manifestDigest = Digests.sha256Hex(canonicalBytes),
            publisherId = publisherId
        )
    }
}
