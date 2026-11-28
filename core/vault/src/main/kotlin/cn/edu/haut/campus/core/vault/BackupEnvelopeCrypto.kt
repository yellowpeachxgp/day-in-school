package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** dis-backup 外层加密容器：明文先组成 ZIP，再用口令派生密钥对整个 ZIP 做 AES-GCM。 */
internal object BackupEnvelopeCrypto {
    private val MAGIC = byteArrayOf('D'.code.toByte(), 'I'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), 'U'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val KEY_CHECK_PURPOSE = "dis-backup/v1/key-check"

    fun encrypt(
        plaintextZip: ByteArray,
        passphrase: CharArray,
        iterations: Int,
        random: SecureRandom = SecureRandom()
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "备份口令不能为空" }
        require(iterations >= 1_000) { "PBKDF2 迭代次数过低" }
        require(plaintextZip.size <= BackupFormatConstants.MAX_ARCHIVE_BYTES) { "备份明文超过大小上限" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val keyBytes = deriveKey(passphrase, salt, iterations)
        try {
            val keyCheck = keyCheck(keyBytes)
            val header = canonicalHeader(
                iterations = iterations,
                salt = salt,
                nonce = nonce,
                keyCheck = keyCheck,
                plaintextSha256 = Digests.sha256Hex(plaintextZip)
            )
            val headerBytes = header.toByteArray(Charsets.UTF_8)
            require(headerBytes.size <= BackupFormatConstants.MAX_HEADER_BYTES) { "备份加密头过大" }
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(headerBytes)
            val ciphertext = cipher.doFinal(plaintextZip)
            val out = ByteBuffer.allocate(MAGIC.size + 4 + headerBytes.size + ciphertext.size)
                .order(ByteOrder.BIG_ENDIAN)
            out.put(MAGIC)
            out.putInt(headerBytes.size)
            out.put(headerBytes)
            out.put(ciphertext)
            Arrays.fill(keyCheck, 0)
            return out.array()
        } finally {
            Arrays.fill(keyBytes, 0)
        }
    }

    fun decrypt(container: ByteArray, passphrase: CharArray): ByteArray {
        if (passphrase.isEmpty()) throw BackupWrongPassphraseException()
        if (container.size < MAGIC.size + 4 || !container.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupCorruptedException("外层魔数不匹配")
        }
        if (container.size.toLong() > BackupFormatConstants.MAX_ARCHIVE_BYTES * 2) {
            throw BackupCorruptedException("加密容器超过大小上限")
        }
        val headerLength = try {
            ByteBuffer.wrap(container, MAGIC.size, 4).order(ByteOrder.BIG_ENDIAN).int
        } catch (error: Throwable) {
            throw BackupCorruptedException("外层头长度不可读", error)
        }
        if (headerLength <= 0 || headerLength > BackupFormatConstants.MAX_HEADER_BYTES) {
            throw BackupCorruptedException("外层头长度非法")
        }
        val headerStart = MAGIC.size + 4
        val headerEnd = headerStart + headerLength
        if (headerEnd > container.size) throw BackupCorruptedException("外层头超出容器末尾")
        val headerBytes = container.copyOfRange(headerStart, headerEnd)
        val header = try {
            Json.parse(String(headerBytes, Charsets.UTF_8), BackupFormatConstants.MAX_HEADER_BYTES).asObjOrNull
                ?: throw IllegalArgumentException("header 不是 object")
        } catch (error: Throwable) {
            throw BackupCorruptedException("外层头 JSON 无法解析", error)
        }
        try {
            if (header.requiredString("format") != BackupFormatConstants.ENVELOPE_FORMAT) {
                throw BackupCorruptedException("外层格式不是 dis-backup-envelope/v1")
            }
        } catch (error: BackupCorruptedException) {
            throw error
        } catch (error: Throwable) {
            throw BackupCorruptedException("外层格式字段缺失", error)
        }
        val envelopeVersion = header.requiredIntOrCorrupt("formatVersion")
        if (envelopeVersion > BackupFormatConstants.FORMAT_VERSION) {
            throw BackupVersionTooNewException("envelope", envelopeVersion, BackupFormatConstants.FORMAT_VERSION)
        }
        if (envelopeVersion != BackupFormatConstants.FORMAT_VERSION) {
            throw BackupCorruptedException("不支持的外层版本")
        }
        val iterations = header.requiredIntOrCorrupt("iterations")
        if (iterations < 1_000) throw BackupCorruptedException("PBKDF2 迭代次数非法")
        val salt = decodeBase64Field(header, "salt")
        val nonce = decodeBase64Field(header, "nonce")
        val keyCheckExpected = decodeBase64Field(header, "keyCheck")
        val plaintextSha256 = try {
            header.requiredString("plaintextSha256")
        } catch (error: Throwable) {
            throw BackupCorruptedException("外层缺少明文摘要", error)
        }
        if (!plaintextSha256.matches(Regex("[0-9a-f]{64}"))) throw BackupCorruptedException("明文摘要非法")
        if (salt.size != SALT_BYTES || nonce.size != NONCE_BYTES || keyCheckExpected.size != 32) {
            throw BackupCorruptedException("salt/nonce/keyCheck 长度非法")
        }

        val keyBytes = deriveKey(passphrase, salt, iterations)
        try {
            val actualKeyCheck = keyCheck(keyBytes)
            if (!MessageDigest.isEqual(actualKeyCheck, keyCheckExpected)) {
                Arrays.fill(actualKeyCheck, 0)
                throw BackupWrongPassphraseException()
            }
            Arrays.fill(actualKeyCheck, 0)
            val ciphertext = container.copyOfRange(headerEnd, container.size)
            if (ciphertext.isEmpty()) throw BackupCorruptedException("密文为空")
            val plaintext = try {
                val cipher = Cipher.getInstance(CIPHER)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                cipher.updateAAD(headerBytes)
                cipher.doFinal(ciphertext)
            } catch (error: Throwable) {
                throw BackupCorruptedException("AES-GCM 完整性校验失败", error)
            }
            if (!MessageDigest.isEqual(Digests.sha256Hex(plaintext).toByteArray(Charsets.US_ASCII), plaintextSha256.toByteArray(Charsets.US_ASCII))) {
                Arrays.fill(plaintext, 0)
                throw BackupCorruptedException("明文 SHA-256 不匹配")
            }
            return plaintext
        } finally {
            Arrays.fill(keyBytes, 0)
        }
    }

    private fun canonicalHeader(
        iterations: Int,
        salt: ByteArray,
        nonce: ByteArray,
        keyCheck: ByteArray,
        plaintextSha256: String
    ): String {
        val encoder = Base64.getEncoder()
        return Json.canonical(
            JsonValue.Obj(
                linkedMapOf(
                    "format" to JsonValue.Str(BackupFormatConstants.ENVELOPE_FORMAT),
                    "formatVersion" to JsonValue.Num(BackupFormatConstants.FORMAT_VERSION.toString()),
                    "kdf" to JsonValue.Str(KDF),
                    "iterations" to JsonValue.Num(iterations.toString()),
                    "cipher" to JsonValue.Str(CIPHER),
                    "salt" to JsonValue.Str(encoder.encodeToString(salt)),
                    "nonce" to JsonValue.Str(encoder.encodeToString(nonce)),
                    "keyCheck" to JsonValue.Str(encoder.encodeToString(keyCheck)),
                    "payloadFormat" to JsonValue.Str("application/zip"),
                    "plaintextSha256" to JsonValue.Str(plaintextSha256)
                )
            )
        )
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase.copyOf(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun keyCheck(keyBytes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        return mac.doFinal(KEY_CHECK_PURPOSE.toByteArray(Charsets.UTF_8))
    }

    private fun decodeBase64Field(header: JsonValue.Obj, key: String): ByteArray = try {
        Base64.getDecoder().decode(header.requiredString(key))
    } catch (error: Throwable) {
        throw BackupCorruptedException("外层字段不是合法 base64: " + key, error)
    }

    private fun JsonValue.Obj.requiredIntOrCorrupt(key: String): Int = try {
        requiredInt(key)
    } catch (error: Throwable) {
        throw BackupCorruptedException("外层缺少整数字段: " + key, error)
    }
}
