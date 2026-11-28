package cn.edu.haut.campus.core.vault

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
        random: SecureRandom = SecureRandom(),
        allowLegacyIterations: Boolean = false
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "备份口令不能为空" }
        val minimumIterations = if (allowLegacyIterations) {
            BackupFormatConstants.LEGACY_MIN_PBKDF2_ITERATIONS
        } else {
            BackupFormatConstants.MIN_PBKDF2_ITERATIONS
        }
        require(iterations in minimumIterations..BackupFormatConstants.MAX_PBKDF2_ITERATIONS) {
            "PBKDF2 迭代次数低于 dis-backup/v1 新导出基线或超出上限"
        }
        require(plaintextZip.size <= BackupFormatConstants.MAX_ARCHIVE_BYTES) { "备份明文超过大小上限" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val keys = deriveKeys(passphrase, salt, iterations)
        var keyCheckBytes: ByteArray? = null
        try {
            val computedKeyCheck = keyCheck(keys.check)
            keyCheckBytes = computedKeyCheck
            val header = canonicalHeader(
                iterations = iterations,
                salt = salt,
                nonce = nonce,
                keyCheck = computedKeyCheck
            )
            val headerBytes = header.toByteArray(Charsets.UTF_8)
            require(headerBytes.size <= BackupFormatConstants.MAX_HEADER_BYTES) { "备份加密头过大" }
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.encryption, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(headerBytes)
            val ciphertext = cipher.doFinal(plaintextZip)
            val out = ByteBuffer.allocate(MAGIC.size + 4 + headerBytes.size + ciphertext.size)
                .order(ByteOrder.BIG_ENDIAN)
            out.put(MAGIC)
            out.putInt(headerBytes.size)
            out.put(headerBytes)
            out.put(ciphertext)
            return out.array()
        } finally {
            keyCheckBytes?.let { Arrays.fill(it, 0) }
            keys.zero()
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
        val headerText = String(headerBytes, Charsets.UTF_8)
        val header = try {
            Json.parse(headerText, BackupFormatConstants.MAX_HEADER_BYTES).asObjOrNull
                ?: throw IllegalArgumentException("header 不是 object")
        } catch (error: Throwable) {
            throw BackupCorruptedException("外层头 JSON 无法解析", error)
        }
        if (Json.canonical(header) != headerText) throw BackupCorruptedException("外层头不是 CanonicalJson 规范形")
        val allowedHeaderKeys = setOf(
            "format", "formatVersion", "kdf", "iterations", "cipher", "salt", "nonce",
            "keyCheck", "payloadFormat"
        )
        val unknownHeaderKeys = header.fields.keys - allowedHeaderKeys
        if (unknownHeaderKeys.isNotEmpty()) {
            throw BackupCorruptedException("外层头包含 v1 未定义字段: " + unknownHeaderKeys.sorted().joinToString(","))
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
        if (header.requiredStringOrNull("kdf") != KDF) throw BackupCorruptedException("KDF 不是 PBKDF2WithHmacSHA256")
        if (header.requiredStringOrNull("cipher") != CIPHER) throw BackupCorruptedException("cipher 不是 AES/GCM/NoPadding")
        if (header.requiredStringOrNull("payloadFormat") != "application/zip") {
            throw BackupCorruptedException("payloadFormat 不是 application/zip")
        }
        val iterations = header.requiredIntOrCorrupt("iterations")
        if (iterations !in BackupFormatConstants.LEGACY_MIN_PBKDF2_ITERATIONS..BackupFormatConstants.MAX_PBKDF2_ITERATIONS) {
            throw BackupCorruptedException("PBKDF2 迭代次数超出允许范围")
        }
        val salt = decodeBase64Field(header, "salt")
        val nonce = decodeBase64Field(header, "nonce")
        val keyCheckExpected = decodeBase64Field(header, "keyCheck")
        if (salt.size != SALT_BYTES || nonce.size != NONCE_BYTES || keyCheckExpected.size != 32) {
            throw BackupCorruptedException("salt/nonce/keyCheck 长度非法")
        }

        val keys = deriveKeys(passphrase, salt, iterations)
        try {
            val actualKeyCheck = keyCheck(keys.check)
            if (!MessageDigest.isEqual(actualKeyCheck, keyCheckExpected)) {
                Arrays.fill(actualKeyCheck, 0)
                throw BackupWrongPassphraseException()
            }
            Arrays.fill(actualKeyCheck, 0)
            val ciphertext = container.copyOfRange(headerEnd, container.size)
            if (ciphertext.isEmpty()) throw BackupCorruptedException("密文为空")
            val plaintext = try {
                val cipher = Cipher.getInstance(CIPHER)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.encryption, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                cipher.updateAAD(headerBytes)
                cipher.doFinal(ciphertext)
            } catch (error: Throwable) {
                throw BackupCorruptedException("AES-GCM 完整性校验失败", error)
            }
            return plaintext
        } finally {
            keys.zero()
        }
    }

    private fun canonicalHeader(
        iterations: Int,
        salt: ByteArray,
        nonce: ByteArray,
        keyCheck: ByteArray
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
                    "payloadFormat" to JsonValue.Str("application/zip")
                )
            )
        )
    }

    private class DerivedKeys(val encryption: ByteArray, val check: ByteArray) {
        fun zero() {
            Arrays.fill(encryption, 0)
            Arrays.fill(check, 0)
        }
    }

    private fun deriveKeys(passphrase: CharArray, salt: ByteArray, iterations: Int): DerivedKeys {
        val passwordCopy = passphrase.copyOf()
        val spec = PBEKeySpec(passwordCopy, salt, iterations, KEY_BITS)
        val master = try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            passwordCopy.fill('\u0000')
        }
        return try {
            DerivedKeys(
                encryption = hmac(master, "dis-backup/v1/aes-key"),
                check = hmac(master, "dis-backup/v1/key-check-key")
            )
        } finally {
            Arrays.fill(master, 0)
        }
    }

    private fun hmac(keyBytes: ByteArray, purpose: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        return mac.doFinal(purpose.toByteArray(Charsets.UTF_8))
    }

    private fun keyCheck(keyBytes: ByteArray): ByteArray = hmac(keyBytes, KEY_CHECK_PURPOSE)

    private fun decodeBase64Field(header: JsonValue.Obj, key: String): ByteArray = try {
        Base64.getDecoder().decode(header.requiredString(key))
    } catch (error: Throwable) {
        throw BackupCorruptedException("外层字段不是合法 base64: " + key, error)
    }

    private fun JsonValue.Obj.requiredStringOrNull(key: String): String? = try {
        requiredString(key)
    } catch (error: Throwable) {
        null
    }

    private fun JsonValue.Obj.requiredIntOrCorrupt(key: String): Int = try {
        val literal = (requiredField(key) as? JsonValue.Num)?.literal
            ?: throw IllegalArgumentException("字段不是 number")
        if (!literal.matches(Regex("-?(0|[1-9][0-9]*)"))) {
            throw IllegalArgumentException("字段不是严格整数字面量")
        }
        literal.toIntOrNull() ?: throw IllegalArgumentException("整数超出 Int 范围")
    } catch (error: Throwable) {
        throw BackupCorruptedException("外层缺少严格整数字段: " + key, error)
    }

    private fun JsonValue.Obj.requiredField(key: String): JsonValue =
        fields[key] ?: throw IllegalArgumentException("缺少字段 " + key)
}
