package cn.edu.haut.campus.core.ai.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import cn.edu.haut.campus.core.ai.AiEndpoint
import cn.edu.haut.campus.core.ai.AiKeyAccessException
import cn.edu.haut.campus.core.ai.AiKeyMaterialAccess
import cn.edu.haut.campus.core.ai.AiKeyStatus
import cn.edu.haut.campus.core.ai.AiKeyStore
import cn.edu.haut.campus.core.ai.AiSettings
import cn.edu.haut.campus.core.ai.AiSettingsStore
import cn.edu.haut.campus.core.ai.HttpsUrl
import cn.edu.haut.campus.core.model.SchoolAccountScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface AiAesKeyProvider {
    fun existing(scope: SchoolAccountScope): SecretKey?
    fun getOrCreate(scope: SchoolAccountScope): SecretKey
    fun delete(scope: SchoolAccountScope)
}

private class AndroidAiAesKeyProvider : AiAesKeyProvider {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(scope: SchoolAccountScope): String =
        "cn.edu.haut.campus.ai.byok.v1." + AiByokStorageFormat.scopeStorageKey(scope)

    override fun existing(scope: SchoolAccountScope): SecretKey? =
        runCatching { store().getKey(alias(scope), null) as? SecretKey }.getOrElse {
            runCatching { store().deleteEntry(alias(scope)) }
            null
        }

    override fun getOrCreate(scope: SchoolAccountScope): SecretKey {
        existing(scope)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias(scope),
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }

    override fun delete(scope: SchoolAccountScope) {
        val store = store()
        if (store.containsAlias(alias(scope))) {
            store.deleteEntry(alias(scope))
        }
        check(!store.containsAlias(alias(scope))) { "AI Keystore 密钥材料清除失败" }
    }
}

/**
 * D3/C10 的 Android Key 仓：Keystore 非导出 AES-256-GCM、AAD 绑定完整 SchoolAccountScope、
 * 密文与 enabled 标记都只写入 context.noBackupFilesDir/ai_byok_v1。公开接口绝不返回 Key。
 */
class AndroidAiKeyStore internal constructor(
    context: Context,
    internal val rootDir: File = File(context.applicationContext.noBackupFilesDir, "ai_byok_v1"),
    private val keys: AiAesKeyProvider = AndroidAiAesKeyProvider()
) : AiKeyStore, AiSettingsStore, AiKeyMaterialAccess {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val statusFlows = ConcurrentHashMap<String, MutableStateFlow<AiKeyStatus?>>()
    private val settingsFlows = ConcurrentHashMap<String, MutableStateFlow<AiSettings?>>()

    override suspend fun put(scope: SchoolAccountScope, endpoint: AiEndpoint, key: CharArray) {
        require(key.isNotEmpty() && key.size <= 4096) { "AI Key 为空或过长" }
        require(key.none { it.isWhitespace() || it.isISOControl() }) { "AI Key 不能包含空白或控制字符" }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val keyCopy = key.copyOf()
                var keyBytes: ByteArray? = null
                var payload: ByteArray? = null
                try {
                    keyBytes = charsToUtf8(keyCopy)
                    require(keyBytes.size <= AiByokStorageFormat.MAX_KEY_BYTES) { "AI Key UTF-8 长度超限" }
                    val normalizedEndpoint = endpoint.copy(baseUrl = HttpsUrl(endpoint.baseUrl.withoutTrailingSlash))
                    payload = AiByokStorageFormat.encodePayload(normalizedEndpoint, keyBytes)
                    writeAtomic(envelopeFile(scope), encrypt(scope, payload))
                    val verified = decryptEnvelopeLocked(scope) as? EnvelopeRead.Valid
                    check(verified != null && verified.payload.endpoint == normalizedEndpoint) { "AI Key 安全保存校验失败" }
                    try {
                        val roundTrip = verified.payload.keyBytes
                        check(roundTrip.contentEquals(keyBytes)) { "AI Key 安全保存校验失败" }
                    } finally {
                        verified.payload.wipe()
                    }
                } finally {
                    keyCopy.fill(0.toChar())
                    keyBytes?.fill(0)
                    payload?.fill(0)
                }
            }
            publish(scope)
        }
    }

    override suspend fun clear(scope: SchoolAccountScope) {
        withContext(Dispatchers.IO) {
            var failure: Throwable? = null
            mutex.withLock {
                runCatching { deleteAtomic(envelopeFile(scope)) }.onFailure { if (failure == null) failure = it }
                runCatching { deleteAtomic(enabledFile(scope)) }.onFailure { if (failure == null) failure = it }
                runCatching { keys.delete(scope) }.onFailure { if (failure == null) failure = it }
            }
            publish(scope)
            failure?.let { throw IllegalStateException("AI Key 安全擦除未完成", it) }
        }
    }

    /** 全量擦除入口；语义与 clear 相同，但命名让注册点明确表达连 enabled/config 也不保留。 */
    suspend fun wipeAll(scope: SchoolAccountScope) = clear(scope)

    override fun status(scope: SchoolAccountScope): Flow<AiKeyStatus> = flow {
        val storageKey = AiByokStorageFormat.scopeStorageKey(scope)
        emit(readStatus(scope))
        statusFlow(storageKey).filterNotNull().collect { emit(it) }
    }.distinctUntilChanged()

    override fun observeSettings(scope: SchoolAccountScope): Flow<AiSettings> = flow {
        val storageKey = AiByokStorageFormat.scopeStorageKey(scope)
        emit(readSettings(scope))
        settingsFlow(storageKey).filterNotNull().collect { emit(it) }
    }.distinctUntilChanged()

    override suspend fun settings(scope: SchoolAccountScope): AiSettings = readSettings(scope)

    override suspend fun setEnabled(scope: SchoolAccountScope, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (enabled) {
                    check(decryptEnvelopeLocked(scope) is EnvelopeRead.Valid) { "请先保存有效的 AI endpoint 与 Key" }
                }
                writeAtomic(enabledFile(scope), byteArrayOf(if (enabled) 1 else 0))
            }
            publish(scope)
        }
    }

    override suspend fun <T> withKeyMaterial(
        scope: SchoolAccountScope,
        block: suspend (endpoint: AiEndpoint, key: CharArray) -> T
    ): T {
        val material = withContext(Dispatchers.IO) {
            mutex.withLock {
                when (val read = decryptEnvelopeLocked(scope)) {
                    is EnvelopeRead.Valid -> {
                        val chars = utf8ToChars(read.payload.keyBytes)
                        read.payload.wipe()
                        KeyMaterial(read.payload.endpoint, chars)
                    }
                    EnvelopeRead.Missing -> throw AiKeyAccessException(AiKeyStatus.NONE)
                    EnvelopeRead.Invalid -> throw AiKeyAccessException(AiKeyStatus.INVALID)
                }
            }
        }
        try {
            return block(material.endpoint, material.keyChars)
        } finally {
            material.wipe()
        }
    }

    private suspend fun readStatus(scope: SchoolAccountScope): AiKeyStatus = withContext(Dispatchers.IO) {
        mutex.withLock {
            when (decryptEnvelopeLocked(scope)) {
                is EnvelopeRead.Valid -> AiKeyStatus.CONFIGURED
                EnvelopeRead.Missing -> AiKeyStatus.NONE
                EnvelopeRead.Invalid -> AiKeyStatus.INVALID
            }
        }
    }

    private suspend fun readSettings(scope: SchoolAccountScope): AiSettings = withContext(Dispatchers.IO) {
        mutex.withLock {
            when (val read = decryptEnvelopeLocked(scope)) {
                is EnvelopeRead.Valid -> {
                    val endpoint = read.payload.endpoint
                    read.payload.wipe()
                    AiSettings(endpoint, AiKeyStatus.CONFIGURED, readEnabledLocked(scope))
                }
                EnvelopeRead.Missing -> AiSettings(null, AiKeyStatus.NONE, false)
                EnvelopeRead.Invalid -> AiSettings(null, AiKeyStatus.INVALID, false)
            }
        }
    }

    private suspend fun publish(scope: SchoolAccountScope) {
        val storageKey = AiByokStorageFormat.scopeStorageKey(scope)
        statusFlow(storageKey).value = readStatus(scope)
        settingsFlow(storageKey).value = readSettings(scope)
    }

    private fun statusFlow(storageKey: String): MutableStateFlow<AiKeyStatus?> =
        statusFlows.getOrPut(storageKey) { MutableStateFlow(null) }

    private fun settingsFlow(storageKey: String): MutableStateFlow<AiSettings?> =
        settingsFlows.getOrPut(storageKey) { MutableStateFlow(null) }

    private fun envelopeFile(scope: SchoolAccountScope): File =
        File(rootDir, AiByokStorageFormat.scopeStorageKey(scope) + ".vault")

    private fun enabledFile(scope: SchoolAccountScope): File =
        File(rootDir, AiByokStorageFormat.scopeStorageKey(scope) + ".enabled")

    private fun readEnabledLocked(scope: SchoolAccountScope): Boolean {
        val file = enabledFile(scope)
        if (!file.isFile) return false
        return runCatching { file.readBytes().contentEquals(byteArrayOf(1)) }.getOrDefault(false)
    }

    private fun encrypt(scope: SchoolAccountScope, payload: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.getOrCreate(scope))
        cipher.updateAAD(AiByokStorageFormat.associatedData(scope))
        val iv = cipher.iv
        check(iv != null && iv.size == AiByokStorageFormat.IV_BYTES) { "AI Key 加密初始化失败" }
        val encrypted = cipher.doFinal(payload)
        return ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(AiByokStorageFormat.ENVELOPE_VERSION)
                data.write(iv)
                data.write(encrypted)
            }
            output.toByteArray()
        }
    }

    private fun decryptEnvelopeLocked(scope: SchoolAccountScope): EnvelopeRead {
        val file = envelopeFile(scope)
        if (!file.isFile && !File(file.path + ".bak").isFile) return EnvelopeRead.Missing
        return try {
            val envelope = readAtomic(file)
            require(envelope.size in 4 + AiByokStorageFormat.IV_BYTES + 16 + 8..AiByokStorageFormat.MAX_ENVELOPE_BYTES) {
                "AI BYOK 密文长度非法"
            }
            val input = DataInputStream(ByteArrayInputStream(envelope))
            require(input.readInt() == AiByokStorageFormat.ENVELOPE_VERSION) { "AI BYOK 密文版本非法" }
            val iv = ByteArray(AiByokStorageFormat.IV_BYTES).also(input::readFully)
            val key = keys.existing(scope) ?: return EnvelopeRead.Invalid
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(AiByokStorageFormat.associatedData(scope))
            val payloadBytes = cipher.doFinal(input.readBytes())
            try {
                EnvelopeRead.Valid(AiByokStorageFormat.decodePayload(payloadBytes))
            } finally {
                payloadBytes.fill(0)
            }
        } catch (_: Exception) {
            EnvelopeRead.Invalid
        }
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        check(rootDir.isDirectory || rootDir.mkdirs()) { "无法创建 AI 安全存储目录" }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun readAtomic(file: File): ByteArray {
        val atomic = AtomicFile(file)
        return atomic.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= AiByokStorageFormat.MAX_ENVELOPE_BYTES) { "AI BYOK 密文长度非法" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun deleteAtomic(file: File) {
        AtomicFile(file).delete()
        check(!file.exists() && !File(file.path + ".bak").exists()) { "AI 安全存储清除失败" }
    }

    private class KeyMaterial(val endpoint: AiEndpoint, val keyChars: CharArray) {
        fun wipe() {
            keyChars.fill(0.toChar())
        }
    }

    private sealed interface EnvelopeRead {
        data class Valid(val payload: DecodedAiPayload) : EnvelopeRead
        data object Missing : EnvelopeRead
        data object Invalid : EnvelopeRead
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        private fun charsToUtf8(chars: CharArray): ByteArray {
            val encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
            val out = ByteArray(encoded.remaining())
            encoded.get(out)
            if (encoded.hasArray()) encoded.array().fill(0)
            return out
        }

        private fun utf8ToChars(bytes: ByteArray): CharArray {
            val decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
            val out = CharArray(decoded.remaining())
            decoded.get(out)
            if (decoded.hasArray()) decoded.array().fill(0.toChar())
            return out
        }
    }
}
