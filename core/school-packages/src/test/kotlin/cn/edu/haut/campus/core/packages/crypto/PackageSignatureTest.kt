package cn.edu.haut.campus.core.packages.crypto

import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.SyntheticPackages
import cn.edu.haut.campus.core.packages.assertPackageError
import cn.edu.haut.campus.core.packages.manifest.PackageManifestCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ed25519 清单签名用例。
 *
 * UNVERIFIED：密钥是测试运行时临时生成的，签名与清单都是合成数据；
 * 这不证明任何真实发行密钥或真实学校包可用。
 */
class PackageSignatureTest {

    private val keyPair = SyntheticPackages.keyPair()
    private val publicKey = SyntheticPackages.rawPublic(keyPair)
    private val signed = SyntheticPackages.signed(keyPair)

    @Test
    fun 合法签名通过校验() {
        assertTrue(
            "UNVERIFIED：合规合成清单必须验签通过",
            PackageSignature.verify(publicKey, signed.manifestBytes, signed.signature)
        )
    }

    @Test
    fun 篡改清单能力声明后签名失败() {
        val tampered = signed.manifestText
            .replace("TIMETABLE_READ", "EXAMS_READ")
            .toByteArray(Charsets.UTF_8)
        assertFalse(
            "UNVERIFIED：改动能力声明后签名必须失效",
            PackageSignature.verify(publicKey, tampered, signed.signature)
        )
    }

    @Test
    fun 篡改文件摘要后签名失败() {
        val originalDigest = signed.bundle.files.single().sha256
        val forged = "f".repeat(64)
        assertTrue("UNVERIFIED：合成摘要必须是 64 位", originalDigest.length == 64)
        val tampered = signed.manifestText.replace(originalDigest, forged).toByteArray(Charsets.UTF_8)
        assertFalse(
            "UNVERIFIED：改动文件摘要后签名必须失效",
            PackageSignature.verify(publicKey, tampered, signed.signature)
        )
    }

    @Test
    fun 用另一把私钥伪造的签名不通过() {
        val attacker = SyntheticPackages.keyPair()
        val forgedSignature = SyntheticPackages.sign(attacker, signed.manifestBytes)
        assertFalse(
            "UNVERIFIED：非发布者私钥生成的签名必须失败",
            PackageSignature.verify(publicKey, signed.manifestBytes, forgedSignature)
        )
    }

    @Test
    fun 签名长度非法直接失败() {
        assertFalse(
            "UNVERIFIED：截断的签名必须失败",
            PackageSignature.verify(publicKey, signed.manifestBytes, signed.signature.copyOf(10))
        )
        assertFalse(
            "UNVERIFIED：空签名必须失败",
            PackageSignature.verify(publicKey, signed.manifestBytes, ByteArray(0))
        )
    }

    @Test
    fun 公钥长度非法直接失败() {
        assertFalse(
            "UNVERIFIED：非 32 字节公钥必须失败",
            PackageSignature.verify(ByteArray(16), signed.manifestBytes, signed.signature)
        )
    }

    @Test
    fun 公钥原始编码与JDK对象可以往返() {
        val raw = SyntheticPackages.rawPublic(keyPair)
        assertEquals("UNVERIFIED：原始公钥必须是 32 字节", 32, raw.size)
        assertEquals(
            "UNVERIFIED：公钥编码往返必须一致",
            raw.toList(),
            Ed25519.toRaw(Ed25519.toPublicKey(raw)).toList()
        )
        assertPackageError(PackageError.SIGNATURE_INVALID) { Ed25519.toPublicKey(ByteArray(31)) }
    }

    @Test
    fun 等价但重新排版的清单仍然验签通过() {
        val reflowed = signed.manifestText
            .replace("\n", " ")
            .replace("  ", "")
            .toByteArray(Charsets.UTF_8)
        assertTrue(
            "UNVERIFIED：签名覆盖规范化载荷，排版差异不应影响验签",
            PackageSignature.verify(publicKey, reflowed, signed.signature)
        )
    }

    @Test
    fun 签名载荷包含清单摘要与文件摘要两部分() {
        val payload = String(PackageSignaturePayload.of(signed.manifestBytes), Charsets.UTF_8)
        val parts = payload.split('\n')
        assertEquals("UNVERIFIED：载荷必须是三行结构", 3, parts.size)
        assertEquals("UNVERIFIED：载荷必须以格式标识开头", PackageManifestCodec.FORMAT, parts[0])
        assertEquals("UNVERIFIED：规范化摘要必须是 64 位十六进制", 64, parts[1].length)
        assertEquals("UNVERIFIED：文件摘要必须是 64 位十六进制", 64, parts[2].length)
        assertEquals("UNVERIFIED：文件摘要必须与包内容一致", signed.bundle.digest, parts[2])
    }
}
