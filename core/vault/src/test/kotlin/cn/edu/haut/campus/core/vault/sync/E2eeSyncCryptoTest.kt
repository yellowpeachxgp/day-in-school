package cn.edu.haut.campus.core.vault.sync

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class E2eeSyncCryptoTest {
    private val random = SecureRandom()

    @Test
    fun encryptDecryptRoundTripUsesExactPow4Buckets() {
        val master = randomBytes(32)
        val keyId = E2eeSyncBlockCipher.keyId(master)
        assertEquals(64, keyId.length)
        val cases = listOf(
            0 to 1_024,
            1_004 to 1_024,
            1_005 to 4_096,
            4_088 to 4_096,
            4_089 to 16_384
        )
        cases.forEachIndexed { index, (plaintextSize, expectedBucket) ->
            val plaintext = ByteArray(plaintextSize) { (it + index).toByte() }
            val blobId = UUID.randomUUID().toString()
            val encrypted = E2eeSyncBlockCipher.encrypt(plaintext, master, keyId, blobId, writerCounter = 1)
            assertEquals(expectedBucket, encrypted.bucketSize)
            assertEquals(expectedBucket, Base64.getDecoder().decode(encrypted.ciphertextBase64).size)
            assertEquals(16, encrypted.encryption.nonceBase64.length)
            val metadata = metadata(blobId, encrypted)
            val decrypted = E2eeSyncBlockCipher.decrypt(metadata, encrypted.ciphertextBase64, master)
            assertArrayEquals(plaintext, decrypted)
        }
    }

    @Test
    fun ciphertextDoesNotContainPlaintextAndTamperFailsClosed() {
        val master = randomBytes(32)
        val keyId = E2eeSyncBlockCipher.keyId(master)
        val plaintext = "跨设备同步明文-微积分-不应出现".toByteArray(Charsets.UTF_8)
        val blobId = UUID.randomUUID().toString()
        val encrypted = E2eeSyncBlockCipher.encrypt(plaintext, master, keyId, blobId, 1)
        assertFalse(encrypted.ciphertextBase64.contains("微积分"))
        assertFalse(encrypted.ciphertextBase64.contains(Base64.getEncoder().encodeToString(plaintext)))
        val tampered = Base64.getDecoder().decode(encrypted.ciphertextBase64)
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()
        val tamperedBase64 = Base64.getEncoder().encodeToString(tampered)
        try {
            E2eeSyncBlockCipher.decrypt(metadata(blobId, encrypted), tamperedBase64, master)
            fail("篡改密文必须失败")
        } catch (expected: E2eeSyncCryptoException) {
            assertTrue(expected.message.orEmpty().contains("不匹配") || expected.message.orEmpty().contains("校验失败"))
        }
    }

    @Test
    fun wrongKeyOrAssociatedBlobFailsClosed() {
        val master = randomBytes(32)
        val other = randomBytes(32)
        val keyId = E2eeSyncBlockCipher.keyId(master)
        val blobId = UUID.randomUUID().toString()
        val encrypted = E2eeSyncBlockCipher.encrypt("abc".toByteArray(), master, keyId, blobId, 1)
        try {
            E2eeSyncBlockCipher.decrypt(metadata(blobId, encrypted), encrypted.ciphertextBase64, other)
            fail("错误 master key 必须失败")
        } catch (expected: E2eeSyncCryptoException) {
            // expected
        }
        val otherBlob = UUID.randomUUID().toString()
        try {
            E2eeSyncBlockCipher.decrypt(metadata(otherBlob, encrypted), encrypted.ciphertextBase64, master)
            fail("AAD blob 替换必须失败")
        } catch (expected: E2eeSyncCryptoException) {
            // expected
        }
    }

    @Test
    fun samePendingWriteIsDeterministicAndDifferentCounterUsesDifferentNonce() {
        val master = randomBytes(32)
        val keyId = E2eeSyncBlockCipher.keyId(master)
        val plaintext = "canonical-pending-write".toByteArray()
        val blobId = UUID.randomUUID().toString()
        val first = E2eeSyncBlockCipher.encrypt(plaintext, master, keyId, blobId, 7)
        val replay = E2eeSyncBlockCipher.encrypt(plaintext, master, keyId, blobId, 7)
        assertEquals(first.ciphertextBase64, replay.ciphertextBase64)
        assertEquals(first.encryption.nonceBase64, replay.encryption.nonceBase64)
        val nextCounter = E2eeSyncBlockCipher.encrypt(plaintext, master, keyId, blobId, 8)
        assertFalse(first.encryption.nonceBase64 == nextCounter.encryption.nonceBase64)
    }

    @Test
    fun recoveryKeyRoundTripAndWrongLengthFails() {
        val master = randomBytes(32)
        val space = randomBytes(16)
        val encoded = E2eeSyncRecoveryKeys.encode(master, space)
        val (decodedMaster, decodedSpace) = E2eeSyncRecoveryKeys.decode(encoded)
        assertArrayEquals(master, decodedMaster)
        assertArrayEquals(space, decodedSpace)
        try {
            E2eeSyncRecoveryKeys.decode(encoded.dropLast(2) + "AA")
            fail("错误长度恢复密钥必须失败")
        } catch (expected: E2eeSyncCryptoException) {
            // expected
        }
    }

    private fun metadata(blobId: String, encrypted: E2eeSyncCiphertext): SyncBlobMetadata = SyncBlobMetadata(
        blobId = blobId,
        changeKind = "UPSERT",
        versionVector = listOf(SyncVersionVectorEntry(UUID.randomUUID().toString(), 1)),
        authorDeviceId = UUID.randomUUID().toString(),
        changeSequence = 1L,
        encryption = encrypted.encryption,
        ciphertextSha256 = encrypted.ciphertextSha256,
        ciphertextSizeBytes = encrypted.bucketSize,
        committedAt = "2026-01-01T00:00:00Z"
    )

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}
