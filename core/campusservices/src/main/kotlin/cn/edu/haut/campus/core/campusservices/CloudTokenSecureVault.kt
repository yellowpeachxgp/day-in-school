package cn.edu.haut.campus.core.campusservices

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 Android Keystore 非导出 AES/GCM/NoPadding 密钥的云端令牌密封存储。
 *
 * access_token / refresh_token 在写入 PreferenceDataStore 前经硬件密钥加密，
 * 落盘数据即使被 root/物理取证读取也无法还原明文。
 */
class CloudTokenSecureVault(
    context: Context,
    prefsName: String = PREFS_FILE_NAME,
    keyAlias: String = DEFAULT_KEY_ALIAS,
    scopeId: String? = null
) {
    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128

        private const val PREFS_FILE_NAME = "haut_cloud_token_vault"
        private const val DEFAULT_KEY_ALIAS = "cn.edu.haut.campus.cloud.tokens.v1"
        const val SEALED_PREFIX = "ctseal1:"
    }

    private val scopeSuffix = scopeId?.let {
        "." + java.security.MessageDigest.getInstance("SHA-256")
            .digest(it.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b) }
    }.orEmpty()
    private val keyAlias = keyAlias + scopeSuffix
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName + scopeSuffix, Context.MODE_PRIVATE)

    private fun getOrCreateSecretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existingKey = runCatching { store.getKey(keyAlias, null) as? SecretKey }.getOrElse {
            runCatching { store.deleteEntry(keyAlias) }
            null
        }
        if (existingKey != null) return existingKey
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun healCorruptedKeyAndStorage() {
        runCatching {
            val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
        }
        prefs.edit().clear().commit()
    }

    /**
     * 加密明文令牌，返回带 [SEALED_PREFIX] 前缀的 Base64 密文；
     * 严禁在加密失败时回退返回明文（Fail-Closed），若传入自定义 [encryptor] 便于单测验证失败闭合行为。
     */
    fun seal(
        plain: String,
        encryptor: ((ByteArray) -> Pair<ByteArray, ByteArray>)? = null
    ): String {
        require(plain.isNotBlank()) { "待加密令牌不能为空" }
        if (plain.startsWith(SEALED_PREFIX)) return plain
        return try {
            val plainBytes = plain.toByteArray(StandardCharsets.UTF_8)
            val (iv, ct) = if (encryptor != null) {
                encryptor(plainBytes)
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
                val rawIv = cipher.iv
                check(rawIv != null && rawIv.size == GCM_IV_LENGTH_BYTES) { "GCM IV 长度异常" }
                rawIv to cipher.doFinal(plainBytes)
            }
            check(iv.size == GCM_IV_LENGTH_BYTES) { "GCM IV 长度异常" }
            SEALED_PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw GeneralSecurityException("云端令牌加密失败，已拒绝回退明文保存", e)
        }
    }

    /**
     * 尝试加密新令牌；若加密失败则失败闭合，保留 [existingCiphertext] 原有密文（且绝不返回未加密明文）。
     */
    fun sealOrPreserveExisting(
        plain: String,
        existingCiphertext: String?,
        encryptor: ((ByteArray) -> Pair<ByteArray, ByteArray>)? = null
    ): String? {
        return runCatching { seal(plain, encryptor) }.getOrElse {
            existingCiphertext?.takeIf { it.startsWith(SEALED_PREFIX) }
        }
    }

    /** 解密密文；拒绝返回无 [SEALED_PREFIX] 前缀的明文数据，解密失败时保留密钥并返回空串。 */
    fun unseal(stored: String): String {
        if (stored.isBlank() || !stored.startsWith(SEALED_PREFIX)) return ""
        return runCatching {
            val rawBytes = Base64.decode(stored.removePrefix(SEALED_PREFIX), Base64.NO_WRAP)
            require(rawBytes.size > GCM_IV_LENGTH_BYTES + 16) { "密文长度非法" }
            val iv = rawBytes.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val ct = rawBytes.copyOfRange(GCM_IV_LENGTH_BYTES, rawBytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.doFinal(ct).toString(StandardCharsets.UTF_8)
        }.getOrDefault("")
    }

    fun wipe() {
        healCorruptedKeyAndStorage()
    }
}
