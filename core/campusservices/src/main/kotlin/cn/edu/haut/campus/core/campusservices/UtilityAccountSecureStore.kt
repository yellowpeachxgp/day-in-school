/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.core.campusservices

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 校园卡账号读取结果状态（显式区分“未配置”与“密钥失效已自愈重置”）。
 */
sealed interface UtilityAccountReadResult {
    data class Active(
        val plainAccount: String,
        val maskedAccount: String,
        val accountFingerprint: String
    ) : UtilityAccountReadResult

    data object Empty : UtilityAccountReadResult

    data object InvalidatedAndReset : UtilityAccountReadResult
}

data class AccountSwitchOutcome(
    val previousFingerprint: String?,
    val newFingerprint: String,
    val maskedAccount: String,
    val switchedFromDifferentAccount: Boolean
)

/**
 * 基于 Android Keystore 非导出 AES/GCM/NoPadding 密钥的智能水电账号安全存储仓。
 */
class UtilityAccountSecureStore(
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

        private const val PREFS_FILE_NAME = "haut_utility_account_vault"
        private const val KEY_ENCRYPTED_ACCOUNT = "encrypted_card_account_v1"
        private const val KEY_ACCOUNT_FINGERPRINT = "card_account_sha256_v1"
        private const val DEFAULT_KEY_ALIAS = "cn.edu.haut.campus.utility.account.v1"

        private val ACCOUNT_REGEX = Regex("^[A-Za-z0-9_-]{4,32}$")

        fun validateAccountFormat(rawAccount: String): String {
            val trimmed = rawAccount.trim()
            require(ACCOUNT_REGEX.matches(trimmed)) {
                "请填写本人校园卡账号（通常为学号，4–32 位字母或数字，保留前导 0）"
            }
            return trimmed
        }

        fun maskAccount(account: String): String {
            val clean = account.trim()
            if (clean.isEmpty()) return ""
            return when {
                clean.length >= 8 -> {
                    val prefix = clean.take(4)
                    val suffix = clean.takeLast(4)
                    val stars = "*".repeat((clean.length - 8).coerceIn(4, 8))
                    "$prefix$stars$suffix"
                }
                clean.length in 6..7 -> {
                    "${clean.take(2)}${"*".repeat(clean.length - 4)}${clean.takeLast(2)}"
                }
                clean.length in 4..5 -> {
                    "${clean.first()}${"*".repeat(clean.length - 2)}${clean.last()}"
                }
                else -> "****"
            }
        }

        fun accountFingerprint(account: String): String {
            val normalized = account.trim()
            require(normalized.isNotEmpty()) { "账号不能为空" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    private val lock = Any()
    private val scopeSuffix = scopeId?.let {
        require(it.isNotBlank()) { "校园服务账号作用域不能为空" }
        "." + MessageDigest.getInstance("SHA-256").digest(it.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
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

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun healCorruptedKeyAndStorage() {
        runCatching {
            val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (store.containsAlias(keyAlias)) {
                store.deleteEntry(keyAlias)
            }
        }
        prefs.edit()
            .remove(KEY_ENCRYPTED_ACCOUNT)
            .remove(KEY_ACCOUNT_FINGERPRINT)
            .commit()
    }

    fun readAccountResult(): UtilityAccountReadResult = synchronized(lock) {
        val encoded = prefs.getString(KEY_ENCRYPTED_ACCOUNT, null)
            ?: return UtilityAccountReadResult.Empty

        return try {
            val rawBytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(rawBytes.size > GCM_IV_LENGTH_BYTES + 16) { "密文长度非法" }

            val iv = rawBytes.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val cipherBytes = rawBytes.copyOfRange(GCM_IV_LENGTH_BYTES, rawBytes.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            )
            val plainAccount = cipher.doFinal(cipherBytes).toString(StandardCharsets.UTF_8)
            val validated = validateAccountFormat(plainAccount)
            val fingerprint = accountFingerprint(validated)

            UtilityAccountReadResult.Active(
                plainAccount = validated,
                maskedAccount = maskAccount(validated),
                accountFingerprint = fingerprint
            )
        } catch (_: KeyPermanentlyInvalidatedException) {
            healCorruptedKeyAndStorage()
            UtilityAccountReadResult.InvalidatedAndReset
        } catch (_: AEADBadTagException) {
            healCorruptedKeyAndStorage()
            UtilityAccountReadResult.InvalidatedAndReset
        } catch (_: GeneralSecurityException) {
            healCorruptedKeyAndStorage()
            UtilityAccountReadResult.InvalidatedAndReset
        } catch (_: Exception) {
            healCorruptedKeyAndStorage()
            UtilityAccountReadResult.InvalidatedAndReset
        }
    }

    fun readPlainOrNull(): String? =
        (readAccountResult() as? UtilityAccountReadResult.Active)?.plainAccount

    fun readMaskedOrNull(): String? =
        (readAccountResult() as? UtilityAccountReadResult.Active)?.maskedAccount

    fun saveOrSwitchAccount(
        rawAccount: String,
        onAccountSwitched: ((oldFingerprint: String) -> Unit)? = null
    ): AccountSwitchOutcome = synchronized(lock) {
        val cleanAccount = validateAccountFormat(rawAccount)
        val newFingerprint = accountFingerprint(cleanAccount)

        val previousFingerprint = (readAccountResult() as? UtilityAccountReadResult.Active)
            ?.accountFingerprint
            ?: prefs.getString(KEY_ACCOUNT_FINGERPRINT, null)

        val isSwitched = !previousFingerprint.isNullOrBlank() && previousFingerprint != newFingerprint
        if (isSwitched && previousFingerprint != null) {
            onAccountSwitched?.invoke(previousFingerprint)
        }

        val encodedPayload = encryptWithSelfHealing(cleanAccount.toByteArray(StandardCharsets.UTF_8))
        val committed = prefs.edit()
            .putString(KEY_ENCRYPTED_ACCOUNT, encodedPayload)
            .putString(KEY_ACCOUNT_FINGERPRINT, newFingerprint)
            .commit()
        check(committed) { "校园卡账号加密保存失败，请检查存储空间" }

        AccountSwitchOutcome(
            previousFingerprint = previousFingerprint,
            newFingerprint = newFingerprint,
            maskedAccount = maskAccount(cleanAccount),
            switchedFromDifferentAccount = isSwitched
        )
    }

    private fun encryptWithSelfHealing(plainBytes: ByteArray): String {
        return try {
            encryptBytes(plainBytes)
        } catch (_: Exception) {
            healCorruptedKeyAndStorage()
            encryptBytes(plainBytes)
        }
    }

    private fun encryptBytes(plainBytes: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        check(iv != null && iv.size == GCM_IV_LENGTH_BYTES) { "GCM IV 长度异常" }
        val cipherBytes = cipher.doFinal(plainBytes)
        return Base64.encodeToString(iv + cipherBytes, Base64.NO_WRAP)
    }

    fun wipeAll(onWipeAssociatedData: ((lastFingerprint: String?) -> Unit)? = null) = synchronized(lock) {
        val lastFingerprint = prefs.getString(KEY_ACCOUNT_FINGERPRINT, null)
            ?: (readAccountResult() as? UtilityAccountReadResult.Active)?.accountFingerprint

        runCatching {
            val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (store.containsAlias(keyAlias)) {
                store.deleteEntry(keyAlias)
            }
        }
        check(prefs.edit().clear().commit()) { "账号清除失败" }
        onWipeAssociatedData?.invoke(lastFingerprint)
    }
}
