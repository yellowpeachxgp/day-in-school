package cn.edu.haut.campus.core.packages.verify

import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.SyntheticPackages
import cn.edu.haut.campus.core.packages.bundle.PackageBundle
import cn.edu.haut.campus.core.packages.crypto.PackageSignaturePayload
import cn.edu.haut.campus.core.packages.crypto.PublisherKeyRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装前校验用例：签名、文件集合、逐个摘要。
 *
 * UNVERIFIED：夹具为合成数据，未对任何真实学校包运行过。
 */
class PackageVerifierTest {

    private val keyPair = SyntheticPackages.keyPair()
    private val registry = SyntheticPackages.registry(keyPair)
    private val signed = SyntheticPackages.signed(keyPair)

    private fun rejected(result: VerificationResult): VerificationResult.Rejected {
        val failure = result as? VerificationResult.Rejected
        assertTrue("UNVERIFIED：期望被拒绝，实际为 " + result, failure != null)
        return failure!!
    }

    @Test
    fun 合规包通过校验并给出双摘要() {
        val accepted = PackageVerifier.verify(
            signed.manifestBytes,
            signed.bundle,
            signed.signature,
            registry
        ) as VerificationResult.Accepted
        assertEquals("UNVERIFIED：内容摘要必须与包体一致", signed.bundle.digest, accepted.bundleDigest)
        assertEquals("UNVERIFIED：清单摘要必须是 64 位", 64, accepted.manifestDigest.length)
        assertEquals("UNVERIFIED：包 ID 必须与清单一致", SyntheticPackages.PACKAGE_ID, accepted.manifest.id)
    }

    @Test
    fun 文件内容被篡改时拒绝并给出摘要不一致() {
        val tampered = PackageBundle.ofContents(
            mapOf("profile/school.json" to "{\"displayName\":\"被改过的学校\"}".toByteArray())
        )
        val failure = rejected(PackageVerifier.verify(signed.manifestBytes, tampered, signed.signature, registry))
        assertEquals("UNVERIFIED：篡改内容必须映射为 FILE_HASH_MISMATCH", PackageError.FILE_HASH_MISMATCH, failure.error)
    }

    @Test
    fun 缺少清单声明的文件时拒绝() {
        val empty = PackageBundle.ofContents(mapOf("profile/other.json" to ByteArray(4)))
        val failure = rejected(PackageVerifier.verify(signed.manifestBytes, empty, signed.signature, registry))
        assertEquals("UNVERIFIED：缺少文件必须映射为 FILE_MISSING", PackageError.FILE_MISSING, failure.error)
    }

    @Test
    fun 包内出现清单未声明的文件时拒绝() {
        val extra = PackageBundle.ofContents(
            mapOf(
                "profile/school.json" to "{\"displayName\":\"合成学校\"}".toByteArray(),
                "assets/payload.bin" to ByteArray(8)
            )
        )
        val failure = rejected(PackageVerifier.verify(signed.manifestBytes, extra, signed.signature, registry))
        assertEquals("UNVERIFIED：多余文件必须映射为 FILE_UNLISTED", PackageError.FILE_UNLISTED, failure.error)
    }

    @Test
    fun 发布者不在信任清单时拒绝() {
        val otherRegistry = PublisherKeyRegistry(mapOf("another-publisher" to SyntheticPackages.rawPublic(SyntheticPackages.keyPair())))
        val failure = rejected(PackageVerifier.verify(signed.manifestBytes, signed.bundle, signed.signature, otherRegistry))
        assertEquals(
            "UNVERIFIED：未知发布者必须映射为 SIGNATURE_UNTRUSTED_PUBLISHER",
            PackageError.SIGNATURE_UNTRUSTED_PUBLISHER,
            failure.error
        )
    }

    @Test
    fun 缺少签名时拒绝() {
        val failure = rejected(PackageVerifier.verify(signed.manifestBytes, signed.bundle, ByteArray(0), registry))
        assertEquals("UNVERIFIED：缺签名必须映射为 SIGNATURE_MISSING", PackageError.SIGNATURE_MISSING, failure.error)
    }

    @Test
    fun 清单被篡改时拒绝() {
        val tampered = signed.manifestText.replace("TIMETABLE_READ", "EXAMS_READ").toByteArray()
        val failure = rejected(PackageVerifier.verify(tampered, signed.bundle, signed.signature, registry))
        assertEquals("UNVERIFIED：篡改清单必须映射为 SIGNATURE_INVALID", PackageError.SIGNATURE_INVALID, failure.error)
    }

    @Test
    fun 清单格式错误时按清单错误拒绝而不是崩溃() {
        val failure = rejected(
            PackageVerifier.verify("{".toByteArray(), signed.bundle, signed.signature, registry)
        )
        assertEquals("UNVERIFIED：坏清单必须映射为 MANIFEST_INVALID", PackageError.MANIFEST_INVALID, failure.error)
    }

    @Test
    fun 签名载荷可以用包体摘要独立复算() {
        val payload = String(PackageSignaturePayload.of(signed.manifestBytes), Charsets.UTF_8)
        assertTrue("UNVERIFIED：载荷必须包含包体摘要", payload.endsWith(signed.bundle.digest))
    }
}
