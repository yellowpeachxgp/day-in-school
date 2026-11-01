package cn.edu.haut.campus.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AcademicCredentialVaultTest {
    private val scope = AcademicCredentialScope("school-a", "opaque-account-a", "provider-a")

    private class MemoryKeys : AcademicCredentialKeyProvider {
        val values = mutableMapOf<AcademicCredentialScope, SecretKey>()
        var generated = 0
        override fun existing(scope: AcademicCredentialScope) = values[scope]
        override fun getOrCreate(scope: AcademicCredentialScope): SecretKey = values.getOrPut(scope) {
            generated++
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        override fun delete(scope: AcademicCredentialScope) { values.remove(scope) }
    }

    private class MemoryStorage : AcademicCredentialStorage {
        val values = mutableMapOf<AcademicCredentialScope, ByteArray>()
        var writes = 0
        var corruptWrites = false
        override fun read(scope: AcademicCredentialScope) = values[scope]?.copyOf()
        override fun write(scope: AcademicCredentialScope, envelope: ByteArray) {
            writes++
            values[scope] = envelope.copyOf().also { if (corruptWrites) it[it.lastIndex] = (it.last() + 1).toByte() }
        }
        override fun delete(scope: AcademicCredentialScope) { values.remove(scope) }
    }

    @Test
    fun `学号和密码只在认证密文中且每次保存使用随机 IV`() {
        val keys = MemoryKeys()
        val storage = MemoryStorage()
        val vault = AcademicCredentialVault(keys, storage)
        vault.save(scope, "  synthetic-student-001  ", "合成密码-for-test")
        val first = storage.values.getValue(scope).copyOf()
        assertFalse(first.toString(StandardCharsets.ISO_8859_1).contains("synthetic-student"))
        val result = vault.read(scope) as AcademicCredentialReadResult.Available
        result.credentials.use {
            assertEquals("synthetic-student-001", it.studentId)
            assertEquals("合成密码-for-test", it.passwordPlain)
            assertFalse(it.toString().contains("合成密码"))
        }
        vault.save(scope, "synthetic-student-001", "合成密码-for-test")
        assertFalse(first.contentEquals(storage.values.getValue(scope)))
        assertEquals(1, keys.generated)
    }

    @Test
    fun `学校账号提供者任一改变都不能解密复制的 envelope`() {
        val keys = MemoryKeys()
        val storage = MemoryStorage()
        val vault = AcademicCredentialVault(keys, storage)
        vault.save(scope, "synthetic-student", "synthetic-password")
        listOf(
            scope.copy(schoolId = "school-b"),
            scope.copy(accountId = "opaque-account-b"),
            scope.copy(providerId = "provider-b")
        ).forEach { wrongScope ->
            // 即使错误地重用同一密钥，AAD 仍必须拒绝跨作用域密文。
            keys.values[wrongScope] = keys.values.getValue(scope)
            storage.values[wrongScope] = storage.values.getValue(scope).copyOf()
            assertEquals(AcademicCredentialReadResult.ReauthenticationRequired, vault.read(wrongScope))
            assertNotEquals(scope.storageKey, wrongScope.storageKey)
        }
    }

    @Test
    fun `密文篡改截断未知版本与密钥丢失都只要求重登`() {
        val keys = MemoryKeys()
        val storage = MemoryStorage()
        val vault = AcademicCredentialVault(keys, storage)
        vault.save(scope, "synthetic-student", "synthetic-password")
        val original = storage.values.getValue(scope).copyOf()
        listOf(
            original.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() },
            original.copyOf(12),
            original.copyOf().also { it[3] = 99 }
        ).forEach { corrupt ->
            storage.values[scope] = corrupt
            assertEquals(AcademicCredentialReadResult.ReauthenticationRequired, vault.read(scope))
            assertTrue(storage.values.getValue(scope).contentEquals(corrupt))
        }
        storage.values[scope] = original
        keys.values.clear()
        assertEquals(AcademicCredentialReadResult.ReauthenticationRequired, vault.read(scope))
        assertEquals(1, keys.generated)
    }

    @Test
    fun `删除指定凭据不影响另一账号`() {
        val keys = MemoryKeys()
        val storage = MemoryStorage()
        val vault = AcademicCredentialVault(keys, storage)
        val second = scope.copy(accountId = "opaque-account-b")
        vault.save(scope, "student-a", "password-a")
        vault.save(second, "student-b", "password-b")
        vault.delete(scope)
        assertEquals(AcademicCredentialReadResult.Empty, vault.read(scope))
        assertFalse(keys.values.containsKey(scope))
        (vault.read(second) as AcademicCredentialReadResult.Available).credentials.use {
            assertEquals("student-b", it.studentId)
            assertEquals("password-b", it.passwordPlain)
        }
    }

    @Test
    fun `旧 Base64 凭据迁移可以在写入后进程中断时重入且不重复写入`() {
        val keys = MemoryKeys()
        val storage = MemoryStorage()
        val vault = AcademicCredentialVault(keys, storage)
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(109, 105, 103, 114, 97, 116, 101, 45, 112, 97, 115, 115, 119, 111, 114, 100))
        assertTrue(migrateLegacyAcademicCredentials(vault, scope, "synthetic-student", encoded))
        assertTrue(migrateLegacyAcademicCredentials(vault, scope, "synthetic-student", encoded))
        assertEquals(1, storage.writes)
        (vault.read(scope) as AcademicCredentialReadResult.Available).credentials.use {
            assertEquals("migrate-password", it.passwordPlain)
        }
        keys.values.clear()
        assertFalse(migrateLegacyAcademicCredentials(vault, scope, "synthetic-student", encoded))
        assertEquals(1, storage.writes)
    }

    @Test
    fun `损坏空值截断和非 UTF8 旧凭据不产生新凭据`() {
        val keys = MemoryKeys()
        val storage = MemoryStorage()
        val vault = AcademicCredentialVault(keys, storage)
        listOf(null, "", "%%%", "a", "/w==", "ICAg").forEach { encoded ->
            assertFalse(migrateLegacyAcademicCredentials(vault, scope, "synthetic-student", encoded))
        }
        assertEquals(0, storage.writes)
        assertEquals(0, keys.generated)
    }

    @Test
    fun `新密文写入后校验失败不会宣称迁移成功`() {
        val storage = MemoryStorage().apply { corruptWrites = true }
        val vault = AcademicCredentialVault(MemoryKeys(), storage)
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(109, 105, 103, 114, 97, 116, 101, 45, 112, 97, 115, 115, 119, 111, 114, 100))
        assertFalse(migrateLegacyAcademicCredentials(vault, scope, "synthetic-student", encoded))
        assertEquals(AcademicCredentialReadResult.ReauthenticationRequired, vault.read(scope))
    }

    @Test
    fun `释放后的凭据不可继续读取`() {
        val vault = AcademicCredentialVault(MemoryKeys(), MemoryStorage())
        vault.save(scope, "student", "password")
        val credentials = (vault.read(scope) as AcademicCredentialReadResult.Available).credentials
        credentials.close()
        assertTrue(runCatching { credentials.studentId }.isFailure)
        assertTrue(runCatching { credentials.passwordPlain }.isFailure)
    }
}
