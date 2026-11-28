package cn.edu.haut.campus.core.packages.crypto

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RFC 8032 vectors and Android API compatibility guards for the portable verifier. */
class Ed25519Test {

    @Test
    fun rfc8032EmptyMessageVectorVerifies() {
        assertTrue(
            "RFC 8032 test vector 1 must verify",
            Ed25519.verify(
                hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
                ByteArray(0),
                hex(
                    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                        "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
                )
            )
        )
    }

    @Test
    fun rfc8032OneOctetMessageVectorVerifies() {
        assertTrue(
            "RFC 8032 test vector 2 must verify",
            Ed25519.verify(
                hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"),
                hex("72"),
                hex(
                    "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                        "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
                )
            )
        )
    }

    @Test
    fun tamperedRfc8032SignatureFailsClosed() {
        val signature = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        )
        signature[0] = (signature[0].toInt() xor 1).toByte()
        assertFalse(
            "one-bit signature tampering must fail",
            Ed25519.verify(
                hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
                ByteArray(0),
                signature
            )
        )
    }

    @Test
    fun api31AndApi32DoNotRequirePlatformEdEcApis() {
        val repoRoot = File("../..").canonicalFile
        val source = File(
            repoRoot,
            "core/school-packages/src/main/kotlin/cn/edu/haut/campus/core/packages/crypto/Ed25519.kt"
        ).readText(Charsets.UTF_8)
        val imports = source.lines().filter { it.startsWith("import ") }
        assertTrue(imports.any { it == "import java.math.BigInteger" })
        assertTrue(imports.any { it == "import java.security.MessageDigest" })
        assertFalse(imports.any { it == "import java.security.Signature" })
        assertFalse(imports.any { it.startsWith("import java.security.interfaces.EdEC") })
        assertFalse(imports.any { it.startsWith("import java.security.spec.EdEC") })
        assertFalse(source.contains("KeyFactory.getInstance"))
        assertFalse(source.contains("Signature.getInstance"))
    }

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex fixture must have even length" }
        return ByteArray(text.length / 2) { index ->
            text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
