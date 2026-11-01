package cn.edu.haut.campus.core.database

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 凭据作用域只接受本地不透明账号 ID，不以学号或域名充当账号身份。 */
data class AcademicCredentialScope(
    val schoolId: String,
    val accountId: String,
    val providerId: String
) {
    init {
        require(listOf(schoolId, accountId, providerId).all { it.isNotBlank() && it.length <= 200 }) {
            "教务凭据作用域无效"
        }
    }

    internal fun associatedData(): ByteArray = encodeFields(
        "day-in-school-academic-credential-v1".toByteArray(StandardCharsets.UTF_8),
        schoolId.toByteArray(StandardCharsets.UTF_8),
        accountId.toByteArray(StandardCharsets.UTF_8),
        providerId.toByteArray(StandardCharsets.UTF_8)
    )

    internal val storageKey: String
        get() = MessageDigest.getInstance("SHA-256").digest(associatedData())
            .joinToString("") { "%02x".format(it) }
}

/** 可清除的短生命周期凭据，禁止数据类自动生成包含秘密的 toString。 */
class AcademicCredentials internal constructor(
    private val studentBytes: ByteArray,
    private val passwordBytes: ByteArray
) : AutoCloseable {
    private var closed = false

    val studentId: String
        get() {
            check(!closed) { "教务凭据已释放" }
            return decodeUtf8(studentBytes)
        }

    val passwordPlain: String
        get() {
            check(!closed) { "教务凭据已释放" }
            return decodeUtf8(passwordBytes)
        }

    override fun close() {
        studentBytes.fill(0)
        passwordBytes.fill(0)
        closed = true
    }

    override fun toString(): String = "AcademicCredentials(已隐藏)"
}

sealed interface AcademicCredentialReadResult {
    class Available(val credentials: AcademicCredentials) : AcademicCredentialReadResult {
        override fun toString(): String = "Available(已隐藏)"
    }
    data object Empty : AcademicCredentialReadResult
    data object ReauthenticationRequired : AcademicCredentialReadResult
}

internal interface AcademicCredentialKeyProvider {
    fun existing(scope: AcademicCredentialScope): SecretKey?
    fun getOrCreate(scope: AcademicCredentialScope): SecretKey
    fun delete(scope: AcademicCredentialScope)
}

internal interface AcademicCredentialStorage {
    fun read(scope: AcademicCredentialScope): ByteArray?
    fun write(scope: AcademicCredentialScope, envelope: ByteArray)
    fun delete(scope: AcademicCredentialScope)
}

/**
 * AES-256-GCM 凭据仓。版本、学校、账号和提供者均受认证保护；读取失败绝不重建密钥。
 * Android 实现把不可导出的密钥保存在 Keystore，密文写入不参与备份的原子文件。
 */
class AcademicCredentialVault internal constructor(
    private val keys: AcademicCredentialKeyProvider,
    private val storage: AcademicCredentialStorage
) {
    @Synchronized
    fun save(scope: AcademicCredentialScope, studentId: String, passwordPlain: String) {
        require(studentId.isNotBlank() && passwordPlain.isNotBlank()) { "学号和密码不能为空" }
        val studentBytes = studentId.trim().toByteArray(StandardCharsets.UTF_8)
        val passwordBytes = passwordPlain.toByteArray(StandardCharsets.UTF_8)
        require(studentBytes.size <= MAX_FIELD_BYTES && passwordBytes.size <= MAX_FIELD_BYTES) {
            "教务凭据超出长度限制"
        }
        val plaintext = encodeFields(studentBytes, passwordBytes)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keys.getOrCreate(scope))
            cipher.updateAAD(scope.associatedData())
            val iv = cipher.iv
            check(iv.size == IV_BYTES) { "安全存储初始化失败" }
            val encrypted = cipher.doFinal(plaintext)
            val envelope = ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use {
                    it.writeInt(ENVELOPE_VERSION)
                    it.write(iv)
                    it.write(encrypted)
                }
                output.toByteArray()
            }
            storage.write(scope, envelope)
            // 只有完成持久化回读，调用方才可以删除旧凭据或切换当前引用。
            val verified = read(scope) as? AcademicCredentialReadResult.Available
                ?: error("教务凭据安全保存校验失败，请重新登录")
            verified.credentials.use {
                check(it.studentId == studentId.trim() && it.passwordPlain == passwordPlain) {
                    "教务凭据安全保存校验失败，请重新登录"
                }
            }
        } finally {
            studentBytes.fill(0)
            passwordBytes.fill(0)
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun read(scope: AcademicCredentialScope): AcademicCredentialReadResult {
        return try {
            val envelope = storage.read(scope) ?: return AcademicCredentialReadResult.Empty
            require(envelope.size in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES)
            val input = DataInputStream(ByteArrayInputStream(envelope))
            require(input.readInt() == ENVELOPE_VERSION)
            val iv = ByteArray(IV_BYTES).also(input::readFully)
            val key = keys.existing(scope) ?: return AcademicCredentialReadResult.ReauthenticationRequired
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(scope.associatedData())
            val plaintext = cipher.doFinal(input.readBytes())
            try {
                val payload = DataInputStream(ByteArrayInputStream(plaintext))
                val studentBytes = readField(payload)
                val passwordBytes = try { readField(payload) } catch (error: Exception) {
                    studentBytes.fill(0)
                    throw error
                }
                try {
                    require(payload.available() == 0)
                    require(decodeUtf8(studentBytes).isNotBlank() && decodeUtf8(passwordBytes).isNotBlank())
                    AcademicCredentialReadResult.Available(AcademicCredentials(studentBytes, passwordBytes))
                } catch (error: Exception) {
                    studentBytes.fill(0)
                    passwordBytes.fill(0)
                    throw error
                }
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            // 不包含底层异常文本，防止文件名、系统身份或秘密进入 UI 与日志。
            AcademicCredentialReadResult.ReauthenticationRequired
        }
    }

    @Synchronized
    fun delete(scope: AcademicCredentialScope) {
        // 先删除密文，即使 Keystore 暂时不可用也不留下可用的本地凭据。
        storage.delete(scope)
        keys.delete(scope)
    }

    companion object {
        private const val ENVELOPE_VERSION = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val MAX_FIELD_BYTES = 4096
        private const val MIN_ENVELOPE_BYTES = 4 + IV_BYTES + 16 + 8
        internal const val MAX_ENVELOPE_BYTES = 4 + IV_BYTES + 16 + 8 + MAX_FIELD_BYTES * 2

        private fun readField(input: DataInputStream): ByteArray {
            val size = input.readInt()
            require(size in 1..MAX_FIELD_BYTES && size <= input.available())
            return ByteArray(size).also(input::readFully)
        }
    }
}

/** 仅迁移入口认识旧 Base64；新存储路径不接受此格式。 */
internal fun decodeLegacyAcademicPassword(encoded: String): ByteArray {
    require(encoded.length in 1..8192) { "旧教务凭据无效，请重新登录" }
    val bytes = Base64.getDecoder().decode(encoded)
    try {
        require(bytes.size in 1..4096 && decodeUtf8(bytes).isNotBlank())
        return bytes
    } catch (error: Exception) {
        bytes.fill(0)
        throw error
    }
}

/** 可重入迁移：上次已写入并校验的密文优先，密钥失效时绝不回退旧密码。 */
internal fun migrateLegacyAcademicCredentials(
    vault: AcademicCredentialVault,
    scope: AcademicCredentialScope,
    studentId: String?,
    encodedPassword: String?
): Boolean {
    when (val existing = vault.read(scope)) {
        is AcademicCredentialReadResult.Available -> return existing.credentials.use {
            !studentId.isNullOrBlank() && it.studentId == studentId.trim()
        }
        AcademicCredentialReadResult.ReauthenticationRequired -> return false
        AcademicCredentialReadResult.Empty -> Unit
    }
    if (studentId.isNullOrBlank() || encodedPassword.isNullOrBlank()) return false
    return try {
        val passwordBytes = decodeLegacyAcademicPassword(encodedPassword)
        try {
            vault.save(scope, studentId.trim(), decodeUtf8(passwordBytes))
            true
        } finally {
            passwordBytes.fill(0)
        }
    } catch (_: Exception) {
        false
    }
}

private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

private fun encodeFields(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
    DataOutputStream(output).use { data ->
        values.forEach { value ->
            data.writeInt(value.size)
            data.write(value)
        }
    }
    output.toByteArray()
}
