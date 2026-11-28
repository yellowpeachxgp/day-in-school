package cn.edu.haut.campus.core.vault.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class E2eeSyncKeyMaterial(
    val masterKey: ByteArray,
    val syncSpaceId: String,
    val keyId: String
) {
    init {
        require(masterKey.size == E2eeSyncProtocol.MASTER_KEY_BYTES) { "sync master key 长度非法" }
        require(syncSpaceId.isNotBlank()) { "syncSpaceId 不能为空" }
    }

    fun zero() {
        Arrays.fill(masterKey, 0)
    }
}

/** 同步密钥仓库。恢复密钥只由用户手动带外转移；实现绝不把 master key 明文写入磁盘或云端。 */
interface E2eeSyncKeyStore {
    suspend fun exists(): Boolean
    /** 创建新密钥并返回一次性恢复密钥文本；返回值本身是秘密，调用方不得记录日志。 */
    suspend fun createNew(): String
    suspend fun importRecoveryKey(recoveryKey: String)
    suspend fun load(): E2eeSyncKeyMaterial
    suspend fun wipe()
}

object E2eeSyncRecoveryKeys {
    fun encode(masterKey: ByteArray, syncSpace: ByteArray): String {
        require(masterKey.size == E2eeSyncProtocol.MASTER_KEY_BYTES) { "sync master key 长度非法" }
        require(syncSpace.size == E2eeSyncProtocol.SYNC_SPACE_BYTES) { "sync space id 长度非法" }
        return E2eeSyncProtocol.RECOVERY_KEY_PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(masterKey + syncSpace)
    }

    fun decode(recoveryKey: String): Pair<ByteArray, ByteArray> {
        val clean = recoveryKey.trim()
        if (!clean.startsWith(E2eeSyncProtocol.RECOVERY_KEY_PREFIX)) {
            throw E2eeSyncCryptoException("同步恢复密钥格式不正确")
        }
        val raw = try {
            Base64.getUrlDecoder().decode(clean.removePrefix(E2eeSyncProtocol.RECOVERY_KEY_PREFIX))
        } catch (error: IllegalArgumentException) {
            throw E2eeSyncCryptoException("同步恢复密钥不是合法 base64url", error)
        }
        if (raw.size != E2eeSyncProtocol.MASTER_KEY_BYTES + E2eeSyncProtocol.SYNC_SPACE_BYTES) {
            throw E2eeSyncCryptoException("同步恢复密钥长度不正确")
        }
        return raw.copyOfRange(0, E2eeSyncProtocol.MASTER_KEY_BYTES) to
            raw.copyOfRange(E2eeSyncProtocol.MASTER_KEY_BYTES, raw.size)
    }

    fun syncSpaceId(syncSpace: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(syncSpace)

    fun encodeMaterial(masterKey: ByteArray, syncSpaceId: String): String = try {
        encode(masterKey, Base64.getUrlDecoder().decode(syncSpaceId))
    } catch (error: IllegalArgumentException) {
        throw E2eeSyncCryptoException("sync space id 不是合法 base64url", error)
    }
}

/** Android Keystore 包装密钥 + 应用私有目录密封文件。包装密钥不可导出；sealed 文件不含明文 key。 */
internal class AndroidE2eeSyncKeyStore(
    context: Context,
    profileId: String,
    private val random: SecureRandom = SecureRandom(),
    private val wrappingKeyOverride: (() -> SecretKey)? = null
) : E2eeSyncKeyStore {
    private val mutex = Mutex()
    private val rootDir: File
    private val keyFile: File
    private val wrapAlias: String

    init {
        require(profileId.isNotBlank()) { "profileId 不能为空" }
        val digest = cn.edu.haut.campus.core.model.json.Digests.sha256Hex(profileId)
        rootDir = File(context.applicationContext.filesDir, "e2ee-sync/" + digest)
        keyFile = File(rootDir, "device-key.json")
        wrapAlias = "cn.edu.haut.campus.e2ee.sync.wrap." + digest.take(48)
    }

    override suspend fun exists(): Boolean = mutex.withLock { keyFile.isFile }

    override suspend fun createNew(): String = mutex.withLock {
        val master = ByteArray(E2eeSyncProtocol.MASTER_KEY_BYTES).also(random::nextBytes)
        val space = ByteArray(E2eeSyncProtocol.SYNC_SPACE_BYTES).also(random::nextBytes)
        try {
            storeUnlocked(master, space)
            E2eeSyncRecoveryKeys.encode(master, space)
        } finally {
            Arrays.fill(master, 0)
            Arrays.fill(space, 0)
        }
    }

    override suspend fun importRecoveryKey(recoveryKey: String): Unit = mutex.withLock {
        val (master, space) = E2eeSyncRecoveryKeys.decode(recoveryKey)
        try {
            storeUnlocked(master, space)
        } finally {
            Arrays.fill(master, 0)
            Arrays.fill(space, 0)
        }
    }

    override suspend fun load(): E2eeSyncKeyMaterial = mutex.withLock {
        val sealedText = runCatching { keyFile.readText(Charsets.UTF_8) }.getOrElse {
            throw E2eeSyncCryptoException("本机尚未配置 E2EE 同步密钥")
        }
        val root = runCatching { JSONObject(sealedText) }.getOrElse {
            throw E2eeSyncCryptoException("本机同步密钥密封文件损坏")
        }
        if (root.optString("format") != SEALED_FORMAT) throw E2eeSyncCryptoException("本机同步密钥密封格式不受支持")
        val sealed = root.optString("sealed")
        val plaintext = unseal(sealed)
        try {
            if (plaintext.size != E2eeSyncProtocol.MASTER_KEY_BYTES + E2eeSyncProtocol.SYNC_SPACE_BYTES) {
                throw E2eeSyncCryptoException("本机同步密钥长度损坏")
            }
            val master = plaintext.copyOfRange(0, E2eeSyncProtocol.MASTER_KEY_BYTES)
            val space = plaintext.copyOfRange(E2eeSyncProtocol.MASTER_KEY_BYTES, plaintext.size)
            return@withLock E2eeSyncKeyMaterial(
                masterKey = master,
                syncSpaceId = E2eeSyncRecoveryKeys.syncSpaceId(space),
                keyId = E2eeSyncBlockCipher.keyId(master)
            ).also {
                Arrays.fill(space, 0)
            }
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }

    override suspend fun wipe(): Unit = mutex.withLock {
        runCatching { keyFile.delete() }
        runCatching {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (store.containsAlias(wrapAlias)) store.deleteEntry(wrapAlias)
        }
    }

    private fun storeUnlocked(master: ByteArray, space: ByteArray) {
        val payload = master + space
        val sealed = try {
            seal(payload)
        } finally {
            Arrays.fill(payload, 0)
        }
        if (!rootDir.exists() && !rootDir.mkdirs()) throw E2eeSyncCryptoException("无法创建同步密钥目录")
        val tmp = File(rootDir, "device-key.json.tmp")
        tmp.writeText(JSONObject().put("format", SEALED_FORMAT).put("sealed", sealed).toString(), Charsets.UTF_8)
        tmp.setReadable(false, false)
        tmp.setReadable(true, true)
        if (!tmp.renameTo(keyFile)) {
            tmp.delete()
            throw E2eeSyncCryptoException("无法原子保存同步密钥密封文件")
        }
    }

    private fun wrappingKey(): SecretKey {
        wrappingKeyOverride?.let { return it() }
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = runCatching { store.getKey(wrapAlias, null) as? SecretKey }.getOrElse {
            runCatching { store.deleteEntry(wrapAlias) }
            null
        }
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(wrapAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun seal(plaintext: ByteArray): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            val iv = cipher.iv
            if (iv.size != GCM_IV_BYTES) throw GeneralSecurityException("GCM IV 长度异常")
            Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext))
        } catch (error: Throwable) {
            throw E2eeSyncCryptoException("同步密钥本机密封失败，已拒绝明文落盘", error)
        }
    }

    private fun unseal(sealed: String): ByteArray {
        return try {
            val raw = Base64.getDecoder().decode(sealed)
            if (raw.size <= GCM_IV_BYTES + 16) throw GeneralSecurityException("密封长度非法")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, GCM_IV_BYTES)))
            cipher.doFinal(raw.copyOfRange(GCM_IV_BYTES, raw.size))
        } catch (error: Throwable) {
            throw E2eeSyncCryptoException("同步密钥本机解封失败；请使用恢复密钥重新加入", error)
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val SEALED_FORMAT = "dis-sync-device-key/v1"
    }
}

/** 测试与 Preview 使用的内存密钥仓库；进程退出即丢失，不能用于生产装配。 */
internal class InMemoryE2eeSyncKeyStore : E2eeSyncKeyStore {
    private val mutex = Mutex()
    private var master: ByteArray? = null
    private var space: ByteArray? = null

    override suspend fun exists(): Boolean = mutex.withLock { master != null && space != null }

    override suspend fun createNew(): String = mutex.withLock {
        val random = SecureRandom()
        val nextMaster = ByteArray(E2eeSyncProtocol.MASTER_KEY_BYTES).also(random::nextBytes)
        val nextSpace = ByteArray(E2eeSyncProtocol.SYNC_SPACE_BYTES).also(random::nextBytes)
        master = nextMaster.copyOf()
        space = nextSpace.copyOf()
        E2eeSyncRecoveryKeys.encode(nextMaster, nextSpace)
    }

    override suspend fun importRecoveryKey(recoveryKey: String): Unit = mutex.withLock {
        val (nextMaster, nextSpace) = E2eeSyncRecoveryKeys.decode(recoveryKey)
        master = nextMaster.copyOf()
        space = nextSpace.copyOf()
    }

    override suspend fun load(): E2eeSyncKeyMaterial = mutex.withLock {
        val m = master?.copyOf() ?: throw E2eeSyncCryptoException("内存密钥仓库为空")
        val s = space?.copyOf() ?: throw E2eeSyncCryptoException("内存 sync space 为空")
        val material = E2eeSyncKeyMaterial(
            masterKey = m,
            syncSpaceId = E2eeSyncRecoveryKeys.syncSpaceId(s),
            keyId = E2eeSyncBlockCipher.keyId(m)
        )
        Arrays.fill(s, 0)
        material
    }

    override suspend fun wipe(): Unit = mutex.withLock {
        master?.let { Arrays.fill(it, 0) }
        space?.let { Arrays.fill(it, 0) }
        master = null
        space = null
    }
}
