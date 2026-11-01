package cn.edu.haut.campus.core.database

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal fun createAcademicCredentialVault(context: Context): AcademicCredentialVault = AcademicCredentialVault(
    keys = AndroidAcademicCredentialKeys(),
    storage = NoBackupAcademicCredentialStorage(File(context.noBackupFilesDir, "academic_credentials_v1"))
)

private class AndroidAcademicCredentialKeys : AcademicCredentialKeyProvider {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(scope: AcademicCredentialScope) = "cn.edu.haut.campus.academic.v1.${scope.storageKey}"

    override fun existing(scope: AcademicCredentialScope): SecretKey? =
        store().getKey(alias(scope), null) as? SecretKey

    override fun getOrCreate(scope: AcademicCredentialScope): SecretKey {
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

    override fun delete(scope: AcademicCredentialScope) {
        store().deleteEntry(alias(scope))
    }
}

private class NoBackupAcademicCredentialStorage(private val directory: File) : AcademicCredentialStorage {
    private fun file(scope: AcademicCredentialScope) = File(directory, "${scope.storageKey}.vault")

    override fun read(scope: AcademicCredentialScope): ByteArray? {
        val atomicFile = AtomicFile(file(scope))
        if (!atomicFile.baseFile.exists() && !File(atomicFile.baseFile.path + ".bak").exists()) return null
        return atomicFile.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= AcademicCredentialVault.MAX_ENVELOPE_BYTES) {
                    "教务安全凭据文件无效"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    override fun write(scope: AcademicCredentialScope, envelope: ByteArray) {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建教务安全存储目录" }
        val atomicFile = AtomicFile(file(scope))
        val output = atomicFile.startWrite()
        try {
            output.write(envelope)
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    override fun delete(scope: AcademicCredentialScope) {
        val file = file(scope)
        AtomicFile(file).delete()
        check(!file.exists()) { "教务安全凭据清除失败" }
    }
}
