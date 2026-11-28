package cn.edu.haut.campus.core.packages.datapack

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.crypto.Ed25519
import cn.edu.haut.campus.core.packages.v2.ExtensionPublicKeyCodec
import java.nio.charset.StandardCharsets
import java.util.Base64

/** CT-06 detached signature object for dis-datapack/v1; the artifact itself intentionally has no signature field. */
data class DetachedDataPackSignature(
    val algorithm: String,
    val scheme: String,
    val publicKeyFingerprint: String,
    val valueBase64: String,
    val signedPayloadSha256: String
)

data class DataPackSignatureVerification(
    val signature: DetachedDataPackSignature,
    val publicKeyFingerprint: String,
    val payloadSha256: String
)

/** Pinned raw Ed25519 public keys keyed by their lowercase SHA-256 fingerprint. Catalog data is never a trust root. */
class DataPackTrustRoot(keysBase64: Collection<String>) {
    private val keys: Map<String, ByteArray>

    init {
        require(keysBase64.isNotEmpty()) { "数据包信任根不能为空" }
        keys = keysBase64.associate { encoded ->
            val raw = try {
                Base64.getDecoder().decode(encoded)
            } catch (error: IllegalArgumentException) {
                throw datapackSignatureInvalid("数据包信任根公钥不是合法 Base64")
            }
            val rawEd25519 = when (raw.size) {
                Ed25519.PUBLIC_KEY_BYTES -> raw.copyOf()
                else -> ExtensionPublicKeyCodec.spkiBase64ToRaw(encoded)
            }
            val copy = rawEd25519.copyOf()
            Digests.sha256Hex(copy) to copy
        }
    }

    fun keyForFingerprint(fingerprint: String): ByteArray? = keys[fingerprint]?.copyOf()

    val fingerprints: Set<String> get() = keys.keys
}

object DisDatapackSignature {
    private val LOWER_SHA256 = Regex("^[0-9a-f]{64}$")
    private val SIGNATURE_BASE64 = Regex("^[A-Za-z0-9+/]{86}==$")
    private val SIGNATURE_KEYS = setOf("algorithm", "scheme", "public_key_fingerprint", "value", "signed_payload_sha256")

    /** Exact payload: UTF-8 bytes of format line, LF, lowercase canonical sha256, LF. */
    fun payloadForCanonicalBytes(canonicalBytes: ByteArray): ByteArray {
        val digest = Digests.sha256Hex(canonicalBytes)
        return (DIS_DATAPACK_FORMAT + "\n" + digest + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    fun payloadSha256ForCanonicalBytes(canonicalBytes: ByteArray): String =
        Digests.sha256Hex(payloadForCanonicalBytes(canonicalBytes))

    fun parseDetached(value: JsonValue?): DetachedDataPackSignature {
        val obj = value as? JsonValue.Obj
            ?: throw datapackSignatureInvalid("dis-datapack/v1 detached signature 必须是对象")
        for (key in obj.fields.keys) {
            if (key !in SIGNATURE_KEYS) throw datapackSignatureInvalid("dis-datapack/v1 detached signature 含未知字段")
        }
        for (key in SIGNATURE_KEYS) {
            if (!obj.fields.containsKey(key)) throw datapackSignatureInvalid("dis-datapack/v1 detached signature 缺少字段 " + key)
        }
        return DetachedDataPackSignature(
            algorithm = stringField(obj, "algorithm"),
            scheme = stringField(obj, "scheme"),
            publicKeyFingerprint = stringField(obj, "public_key_fingerprint"),
            valueBase64 = stringField(obj, "value"),
            signedPayloadSha256 = stringField(obj, "signed_payload_sha256")
        ).also { validateShape(it) }
    }

    fun validateShape(signature: DetachedDataPackSignature) {
        if (signature.algorithm != "Ed25519") {
            throw datapackSignatureInvalid("dis-datapack/v1 签名算法必须是 Ed25519")
        }
        if (signature.scheme != DIS_DATAPACK_SIGNATURE_SCHEME) {
            throw datapackSignatureInvalid("dis-datapack/v1 签名 scheme 不受支持")
        }
        if (!LOWER_SHA256.matches(signature.publicKeyFingerprint)) {
            throw datapackSignatureInvalid("dis-datapack/v1 公钥指纹必须是小写 SHA-256 hex")
        }
        if (!SIGNATURE_BASE64.matches(signature.valueBase64)) {
            throw datapackSignatureInvalid("dis-datapack/v1 签名值不是 64 字节 Ed25519 Base64")
        }
        if (!LOWER_SHA256.matches(signature.signedPayloadSha256)) {
            throw datapackSignatureInvalid("dis-datapack/v1 signed_payload_sha256 必须是小写 SHA-256 hex")
        }
    }

    fun verify(
        canonicalBytes: ByteArray,
        signature: DetachedDataPackSignature,
        trustRoot: DataPackTrustRoot
    ): DataPackSignatureVerification {
        validateShape(signature)
        val publicKey = trustRoot.keyForFingerprint(signature.publicKeyFingerprint)
            ?: throw PackageException(
                PackageError.SIGNATURE_UNTRUSTED_PUBLISHER,
                "dis-datapack/v1 签名者不在固定发布者信任根中",
                RUNTIME_DATAPACK_UNTRUSTED_SIGNER
            )
        val payload = payloadForCanonicalBytes(canonicalBytes)
        val payloadDigest = Digests.sha256Hex(payload)
        if (payloadDigest != signature.signedPayloadSha256) {
            throw datapackSignatureInvalid("dis-datapack/v1 signed_payload_sha256 与 artifact 推导载荷不一致")
        }
        val signatureBytes = try {
            Base64.getDecoder().decode(signature.valueBase64)
        } catch (error: IllegalArgumentException) {
            throw datapackSignatureInvalid("dis-datapack/v1 签名值不是合法 Base64")
        }
        if (!Ed25519.verify(publicKey, payload, signatureBytes)) {
            throw datapackSignatureInvalid("dis-datapack/v1 Ed25519 验签失败")
        }
        return DataPackSignatureVerification(
            signature = signature,
            publicKeyFingerprint = signature.publicKeyFingerprint,
            payloadSha256 = payloadDigest
        )
    }

    private fun stringField(obj: JsonValue.Obj, key: String): String =
        (obj.fields[key] as? JsonValue.Str)?.value
            ?: throw datapackSignatureInvalid("dis-datapack/v1 detached signature 字段必须是字符串：" + key)
}
