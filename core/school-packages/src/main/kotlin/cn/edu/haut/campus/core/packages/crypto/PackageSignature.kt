package cn.edu.haut.campus.core.packages.crypto

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.bundle.PackageFileDigest
import cn.edu.haut.campus.core.packages.manifest.PackageManifestCodec
import java.nio.charset.StandardCharsets

/**
 * 待签名载荷：规范化清单摘要 + 文件摘要。
 *
 * 结构固定为 [FORMAT, sha256(canonical(manifest)), filesDigest] 三行，
 * 已经用长度前缀拼接的内层摘要避免分隔符歧义。签名不覆盖"签名本身"，
 * 也不覆盖包外任何信息，因此同一份清单在任何宿主上的载荷都相同。
 */
object PackageSignaturePayload {

    fun of(manifestBytes: ByteArray): ByteArray {
        val document = PackageManifestCodec.document(String(manifestBytes, StandardCharsets.UTF_8))
        val canonical = Json.canonical(document)
        val filesObject = document.fields["files"]?.asObjOrNull
            ?: throw PackageException(PackageError.MANIFEST_INVALID, "清单缺少 files 对象")
        val files = filesObject.fields.mapValues { (path, value) ->
            value.stringOrNull()
                ?: throw PackageException(PackageError.MANIFEST_INVALID, "files 条目必须是字符串：" + path)
        }
        val payload = StringBuilder()
            .append(PackageManifestCodec.FORMAT).append('\n')
            .append(Digests.sha256Hex(canonical)).append('\n')
            .append(PackageFileDigest.of(files))
            .toString()
        return payload.toByteArray(StandardCharsets.UTF_8)
    }
}

/**
 * 清单签名验证入口。
 *
 * 验签对象是 [PackageSignaturePayload] 计算出的载荷，而不是原始字节：
 * 这样缩进、键顺序、数字写法不同的等价清单得到同一结果，但任何语义改动都会失败。
 */
object PackageSignature {

    fun verify(publicKeyRaw: ByteArray, manifestBytes: ByteArray, signature: ByteArray): Boolean {
        val payload = try {
            PackageSignaturePayload.of(manifestBytes)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return verifyPayload(publicKeyRaw, payload, signature)
    }

    fun verifyPayload(publicKeyRaw: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != Ed25519.SIGNATURE_BYTES) return false
        val publicKey = try {
            Ed25519.toPublicKey(publicKeyRaw)
        } catch (_: Exception) {
            return false
        }
        return Ed25519.verify(publicKey, payload, signature)
    }
}
