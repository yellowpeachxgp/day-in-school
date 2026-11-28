package cn.edu.haut.campus.core.vault.sync

import cn.edu.haut.campus.core.model.json.Digests
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class E2eeSyncCiphertext(
    val encryption: SyncEncryptionEnvelope,
    val ciphertextBytes: ByteArray,
    val ciphertextBase64: String,
    val ciphertextSha256: String,
    val bucketSize: Int
) {
    init {
        require(bucketSize in E2eeSyncProtocol.BUCKET_SIZES) { "bucket size 非法" }
        require(ciphertextBytes.size == bucketSize) { "密文长度必须等于 POW4 bucket" }
    }

    override fun equals(other: Any?): Boolean = other is E2eeSyncCiphertext &&
        encryption == other.encryption &&
        ciphertextBytes.contentEquals(other.ciphertextBytes) &&
        ciphertextBase64 == other.ciphertextBase64 &&
        ciphertextSha256 == other.ciphertextSha256 &&
        bucketSize == other.bucketSize

    override fun hashCode(): Int = ciphertextSha256.hashCode()
}

/**
 * CT-07 块加密器。plaintext 只在设备内存中出现；输出恰为固定 POW4 bucket 的 AES-GCM 密文。
 * nonce 使用 keyed HMAC 从 blob/counter/plaintext 域分离派生：同一 pending write 重试得到逐字节相同请求，
 * 不同 counter 或不同 plaintext 不会故意复用 nonce。
 */
object E2eeSyncBlockCipher {
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val HMAC = "HmacSHA256"
    private const val GCM_TAG_BITS = 128

    fun keyId(masterKey: ByteArray): String {
        require(masterKey.size == E2eeSyncProtocol.MASTER_KEY_BYTES) { "sync master key 长度非法" }
        return hmac(masterKey, "dis-sync-e2ee/v1/key-id").joinToString("", limit = 32) { "%02x".format(it) }
    }

    fun encrypt(
        plaintext: ByteArray,
        masterKey: ByteArray,
        keyId: String,
        blobId: String,
        writerCounter: Int
    ): E2eeSyncCiphertext {
        validateKey(masterKey, keyId)
        E2eeSyncProtocol.requireValidUuid(blobId, "blob_id")
        require(writerCounter in 1..E2eeSyncProtocol.MAX_COUNTER) { "writer_counter 非法" }
        val bucket = E2eeSyncProtocol.bucketForPlaintext(plaintext.size)
        val envelopeSize = bucket - E2eeSyncProtocol.GCM_TAG_BYTES
        require(plaintext.size + 4 <= envelopeSize) { "plaintext 超出所选 bucket" }
        val envelope = ByteBuffer.allocate(envelopeSize).order(ByteOrder.BIG_ENDIAN)
        envelope.putInt(plaintext.size)
        envelope.put(plaintext)
        while (envelope.position() < envelopeSize) envelope.put(0)
        val nonce = deterministicNonce(masterKey, blobId, writerCounter, plaintext)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(E2eeSyncProtocol.associatedData(blobId, keyId))
        val ciphertext = cipher.doFinal(envelope.array())
        if (ciphertext.size != bucket) {
            throw E2eeSyncCryptoException("AES-GCM 输出长度不等于固定 bucket")
        }
        val encoded = Base64.getEncoder().encodeToString(ciphertext)
        return E2eeSyncCiphertext(
            encryption = SyncEncryptionEnvelope(
                keyId = keyId,
                nonceBase64 = Base64.getEncoder().withoutPadding().encodeToString(nonce)
            ),
            ciphertextBytes = ciphertext,
            ciphertextBase64 = encoded,
            ciphertextSha256 = Digests.sha256Hex(ciphertext),
            bucketSize = bucket
        )
    }

    fun decrypt(
        metadata: SyncBlobMetadata,
        ciphertextBase64: String,
        masterKey: ByteArray
    ): ByteArray {
        val encryption = metadata.encryption
            ?: throw E2eeSyncCryptoException("同步块缺少 encryption envelope")
        validateKey(masterKey, encryption.keyId)
        if (encryption.format != E2eeSyncProtocol.FORMAT ||
            encryption.cipherSuite != E2eeSyncProtocol.CIPHER_SUITE ||
            encryption.paddingScheme != E2eeSyncProtocol.PADDING_SCHEME
        ) {
            throw E2eeSyncCryptoException("同步块 encryption envelope 算法或填充方案不受支持")
        }
        val ciphertext = try {
            Base64.getDecoder().decode(ciphertextBase64)
        } catch (error: IllegalArgumentException) {
            throw E2eeSyncCryptoException("同步块 ciphertext_base64 非法", error)
        }
        if (ciphertext.size !in E2eeSyncProtocol.BUCKET_SIZES || ciphertext.size != metadata.ciphertextSizeBytes) {
            throw E2eeSyncCryptoException("同步块长度不是契约允许的固定 bucket")
        }
        val expectedDigest = metadata.ciphertextSha256
        if (expectedDigest != null && !MessageDigest.isEqual(
                Digests.sha256Hex(ciphertext).toByteArray(Charsets.US_ASCII),
                expectedDigest.lowercase().toByteArray(Charsets.US_ASCII)
            )
        ) {
            throw E2eeSyncCryptoException("同步块 ciphertext_sha256 不匹配")
        }
        val nonce = try {
            Base64.getDecoder().decode(encryption.nonceBase64)
        } catch (error: IllegalArgumentException) {
            throw E2eeSyncCryptoException("同步块 nonce_base64 非法", error)
        }
        if (nonce.size != E2eeSyncProtocol.NONCE_BYTES || encryption.nonceBase64.length != 16) {
            throw E2eeSyncCryptoException("同步块 nonce 长度非法")
        }
        val plaintextEnvelope = try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(E2eeSyncProtocol.associatedData(metadata.blobId, encryption.keyId))
            cipher.doFinal(ciphertext)
        } catch (error: Throwable) {
            throw E2eeSyncCryptoException("同步块 AES-GCM 校验失败，已拒绝部分明文", error)
        }
        if (plaintextEnvelope.size != ciphertext.size - E2eeSyncProtocol.GCM_TAG_BYTES) {
            throw E2eeSyncCryptoException("同步块填充 envelope 长度非法")
        }
        if (plaintextEnvelope.size < 4) throw E2eeSyncCryptoException("同步块填充 envelope 过短")
        val declaredLength = ByteBuffer.wrap(plaintextEnvelope, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        if (declaredLength < 0 || declaredLength > plaintextEnvelope.size - 4) {
            throw E2eeSyncCryptoException("同步块 plaintext 长度前缀非法")
        }
        for (index in 4 + declaredLength until plaintextEnvelope.size) {
            if (plaintextEnvelope[index].toInt() != 0) {
                throw E2eeSyncCryptoException("同步块零填充区域被污染")
            }
        }
        return plaintextEnvelope.copyOfRange(4, 4 + declaredLength)
    }

    fun zero(key: ByteArray?) {
        key?.let { Arrays.fill(it, 0) }
    }

    private fun validateKey(masterKey: ByteArray, keyId: String) {
        require(masterKey.size == E2eeSyncProtocol.MASTER_KEY_BYTES) { "sync master key 长度非法" }
        require(keyId.length in 8..128 && keyId.matches(Regex("[A-Za-z0-9._:-]+"))) { "key_id 非法" }
        if (keyId(masterKey) != keyId) throw E2eeSyncCryptoException("key_id 与本地同步密钥不匹配")
    }

    private fun deterministicNonce(masterKey: ByteArray, blobId: String, writerCounter: Int, plaintext: ByteArray): ByteArray {
        val plaintextDigest = Digests.sha256Hex(plaintext)
        val purpose = "dis-sync-e2ee/v1/nonce\n" + blobId.lowercase() + "\n" + writerCounter + "\n" + plaintextDigest
        return hmac(masterKey, purpose).copyOfRange(0, E2eeSyncProtocol.NONCE_BYTES)
    }

    private fun hmac(key: ByteArray, purpose: String): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(purpose.toByteArray(Charsets.UTF_8))
    }
}
