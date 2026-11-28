package cn.edu.haut.campus.core.packages.datapack

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.packages.PackageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class DisDatapackSignatureTest {

    @Test
    fun detachedSignatureUsesExactPayloadShapeAndVerifiesAgainstPinnedTrustRoot() {
        val decoded = DisDatapackLoader.decode(fixtureBytes())
        val signed = sign(decoded.canonicalBytes)
        assertEquals("Ed25519", signed.signature.algorithm)
        assertEquals(DIS_DATAPACK_SIGNATURE_SCHEME, signed.signature.scheme)
        assertEquals(
            "dis-datapack/v1\n8a10ddfd1cc74d5b3f50b8208becef0ad8014b7702878bc16e7ee7446224bf47\n",
            String(DisDatapackSignature.payloadForCanonicalBytes(decoded.canonicalBytes), Charsets.UTF_8)
        )
        assertEquals(
            "8a7b6bb837654d944d64bd0b67134a70abbc331ba56064f889d124c5e09d4aad",
            signed.verification.payloadSha256
        )
        assertTrue(signed.signature.valueBase64.matches(Regex("^[A-Za-z0-9+/]{86}==$")))
        DisDatapackLoader.verify(decoded.canonicalBytes, signed.signature, signed.spkiTrustRoot)
    }

    @Test
    fun semanticTamperingWrongPayloadHashWrongSignerAndMalformedSignatureFailClosed() {
        val decoded = DisDatapackLoader.decode(fixtureBytes())
        val signed = sign(decoded.canonicalBytes)

        val tamperedBytes = String(fixtureBytes(), Charsets.UTF_8)
            .replace("\"title\":\"学生证办理\"", "\"title\":\"被篡改标题\"")
            .toByteArray(Charsets.UTF_8)
        val tamperedDecoded = DisDatapackLoader.decode(tamperedBytes)
        assertSignatureError {
            DisDatapackLoader.verify(tamperedBytes, signed.signature, signed.trustRoot)
        }

        val mismatchedPayloadSignature = signed.signature.copy(
            signedPayloadSha256 = DisDatapackSignature.payloadSha256ForCanonicalBytes(tamperedDecoded.canonicalBytes)
        )
        assertSignatureError {
            DisDatapackLoader.verify(tamperedBytes, mismatchedPayloadSignature, signed.trustRoot)
        }

        val attacker = sign(decoded.canonicalBytes)
        assertSignatureError(RUNTIME_DATAPACK_UNTRUSTED_SIGNER) {
            DisDatapackLoader.verify(decoded.canonicalBytes, signed.signature, attacker.trustRoot)
        }

        assertSignatureError {
            DisDatapackLoader.verify(decoded.canonicalBytes, signed.signature.copy(valueBase64 = "AAAA"), signed.trustRoot)
        }
    }

    private data class SyntheticSignature(
        val signature: DetachedDataPackSignature,
        val verification: DataPackSignatureVerification,
        val trustRoot: DataPackTrustRoot,
        val spkiTrustRoot: DataPackTrustRoot
    )

    private fun sign(canonicalBytes: ByteArray): SyntheticSignature {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val spki = keyPair.public.encoded
        val rawPublic = spki.copyOfRange(spki.size - 32, spki.size)
        val fingerprint = Digests.sha256Hex(rawPublic)
        val payload = DisDatapackSignature.payloadForCanonicalBytes(canonicalBytes)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(payload)
        val signature = DetachedDataPackSignature(
            algorithm = "Ed25519",
            scheme = DIS_DATAPACK_SIGNATURE_SCHEME,
            publicKeyFingerprint = fingerprint,
            valueBase64 = Base64.getEncoder().encodeToString(signer.sign()),
            signedPayloadSha256 = Digests.sha256Hex(payload)
        )
        val trustRoot = DataPackTrustRoot(listOf(Base64.getEncoder().encodeToString(rawPublic)))
        val verified = DisDatapackLoader.verify(canonicalBytes, signature, trustRoot)
        val spkiTrustRoot = DataPackTrustRoot(listOf(Base64.getEncoder().encodeToString(spki)))
        return SyntheticSignature(signature, verified.signatureVerification, trustRoot, spkiTrustRoot)
    }

    private fun fixtureBytes(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/datapack-fixtures/valid-procedure.json")).use { it.readBytes() }

    private fun assertSignatureError(runtimeCode: String? = null, block: () -> Unit) {
        try {
            block()
        } catch (error: PackageException) {
            if (runtimeCode != null) assertEquals(runtimeCode, error.runtimeValidationCode)
            return
        }
        throw AssertionError("expected signature PackageException")
    }
}
