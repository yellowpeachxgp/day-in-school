package cn.edu.haut.campus.core.packages.crypto

import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.security.spec.EdECPoint
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec

/**
 * Ed25519（JDK EdDSA）验签的最小封装。
 *
 * 信任清单里保存的是 RFC 8032 的 32 字节原始公钥，而 JDK 只提供 EdECPublicKey，
 * 因此这里实现两个方向的编码转换：y 采用 32 字节小端，最高位是 x 的奇偶符号。
 * 本对象不提供签名能力——宿主只验证，不生成学校包签名。
 *
 * UNVERIFIED：实现按 RFC 8032 的公开编码规则自写，未与任何真实学校的发布密钥联调。
 */
object Ed25519 {

    const val PUBLIC_KEY_BYTES: Int = 32

    const val SIGNATURE_BYTES: Int = 64

    /** 把 32 字节原始公钥转换为 JDK 公钥对象；长度不符直接拒绝。 */
    fun toPublicKey(raw: ByteArray): PublicKey {
        if (raw.size != PUBLIC_KEY_BYTES) {
            throw PackageException(PackageError.SIGNATURE_INVALID, "Ed25519 公钥必须是 32 字节")
        }
        val littleEndian = raw.copyOf()
        val xOdd = (littleEndian[31].toInt() and 0x80) != 0
        littleEndian[31] = (littleEndian[31].toInt() and 0x7f).toByte()
        val y = BigInteger(1, littleEndian.reversedArray())
        return try {
            KeyFactory.getInstance("Ed25519").generatePublic(
                EdECPublicKeySpec(NamedParameterSpec.ED25519, EdECPoint(xOdd, y))
            )
        } catch (error: Exception) {
            throw PackageException(PackageError.SIGNATURE_INVALID, "无法构造 Ed25519 公钥：" + error.javaClass.simpleName)
        }
    }

    /** 把 JDK 公钥转换回 32 字节原始公钥，用于信任清单的存储格式。 */
    fun toRaw(publicKey: PublicKey): ByteArray {
        val ed = publicKey as? EdECPublicKey
            ?: throw PackageException(PackageError.SIGNATURE_INVALID, "公钥不是 Ed25519 类型")
        val bigEndian = ed.point.y.toByteArray()
        if (bigEndian.size > PUBLIC_KEY_BYTES) {
            // BigInteger 只有在最高位为 1 时才会多出一个前导 0x00 字节。
            if (bigEndian[0].toInt() != 0) {
                throw PackageException(PackageError.SIGNATURE_INVALID, "公钥超出 32 字节")
            }
        }
        val padded = ByteArray(PUBLIC_KEY_BYTES)
        val length = minOf(bigEndian.size, PUBLIC_KEY_BYTES)
        System.arraycopy(bigEndian, bigEndian.size - length, padded, PUBLIC_KEY_BYTES - length, length)
        val raw = padded.reversedArray()
        if (ed.point.isXOdd) raw[31] = (raw[31].toInt() or 0x80).toByte()
        return raw
    }

    /** 验签失败返回 false，任何底层异常都按"不通过"处理（fail closed）。 */
    fun verify(publicKey: PublicKey, payload: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != SIGNATURE_BYTES) return false
        return try {
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey)
            verifier.update(payload)
            verifier.verify(signature)
        } catch (_: Exception) {
            false
        }
    }
}
