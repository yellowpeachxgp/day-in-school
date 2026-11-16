package cn.edu.haut.campus.core.database

import android.security.keystore.KeyGenParameterSpec
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.edu.haut.campus.core.campusservices.CloudTokenSecureVault
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.security.*
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.*
import javax.crypto.*

/** 临时 Room/DataStore 与真实 AES-GCM；密钥端口替换为内存 JCE，不冒充硬件 Keystore。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CloudSessionUpgradeTest {
    companion object { val keys = mutableMapOf<String, Key>() }
    class TestKeyStore : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?) = keys[alias]
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String) = Date(0)
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) { keys[alias] = key }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) { error("不支持字节密钥") }
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) { error("不支持证书写入") }
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)
        override fun engineContainsAlias(alias: String) = keys.containsKey(alias)
        override fun engineSize() = keys.size
        override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }
    class TestKeyGenerator : KeyGeneratorSpi() {
        private var alias = ""
        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(size: Int, random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) { alias = (params as KeyGenParameterSpec).keystoreAlias }
        override fun engineGenerateKey(): SecretKey = KeyGenerator.getInstance("AES", "SunJCE").apply { init(256) }.generateKey().also { keys[alias] = it }
    }
    private val provider = object : Provider("AndroidKeyStore", 1.0, "隔离测试密钥端口") {
        init { put("KeyStore.AndroidKeyStore", TestKeyStore::class.java.name); put("KeyGenerator.AES", TestKeyGenerator::class.java.name) }
    }
    private val fixtures = mutableListOf<RepairTestFixture>()
    private val repos = mutableListOf<CampusRepository>()
    private val at = stringPreferencesKey("cloud_access_token_v1")
    private val rt = stringPreferencesKey("cloud_refresh_token_v1")
    private val owner = "upgrade-user"
    private fun jwt(user: String, expiry: Long): String =
        "eyJhbGciOiJIUzI1NiJ9." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$user","exp":$expiry}""".toByteArray()) + ".test-signature"
    @Before fun setup() { keys.clear(); Security.addProvider(provider) }
    @After fun close() = runBlocking {
        repos.forEach { repo ->
            val f = CampusRepository::class.java.getDeclaredField("repoScope").apply { isAccessible = true }
            ((f.get(repo) as CoroutineScope).coroutineContext[Job])?.cancelAndJoin()
        }
        fixtures.forEach { it.close() }
        Security.removeProvider(provider.name); keys.clear()
    }
    private suspend fun fixture(legacy: Boolean, tokenOwner: String = owner): RepairTestFixture {
        val f = RepairTestFixture.create(RuntimeEnvironment.getApplication(), "auth_${UUID.randomUUID()}", legacy)
        fixtures += f; repos += f.repository
        f.database.cloudAndParcelDao().upsertCloudIdentityBinding(CloudIdentityBindingEntity(
            profileId = f.profile.id, cloudUserId = owner, ssoIssuer = "https://test.invalid", ssoSubject = "subject-upgrade",
            displayName = "迁移测试", peachBalanceMicropeach = 20000, linkedAtEpochMillis = 1, updatedAtEpochMillis = 1))
        f.dataStore.edit { it[at] = jwt(tokenOwner, 1); it[rt] = "legacy-refresh-secret" }
        return f
    }
    private fun syncBody() = """{"ok":true,"data":{"auth_version":1,"user":{"cloud_user_id":"$owner","display_name":"迁移测试"},"wallet":{"peach_balance":2.0},"school_bindings":[],"reservation_jobs":[],"orders":[],"parcels":[]}}"""
    @Test fun `旧版真实明文令牌经过实际同步入口续期加密并在仓库重建后可用`() = runBlocking {
        val f = fixture(true)
        val live = jwt(owner, 4102444800)
        var refreshCalls = 0
        f.repository.cloudHttpTransportForTest = { method, url, body, token, _ ->
            when {
                url.endsWith("/auth/refresh") -> {
                    refreshCalls++; assertEquals("legacy-refresh-secret", JSONObject(body!!).getString("refresh_token"))
                    """{"ok":true,"data":{"cloud_user_id":"$owner","access_token":"$live","refresh_token":"rotated-refresh-secret"}}"""
                }
                method == "POST" && url.endsWith("/sync/snapshot") -> { assertEquals(live, token); syncBody() }
                else -> error("不允许未预期网络请求")
            }
        }
        f.repository.syncWithCampusCloud().getOrThrow()
        assertEquals(1, refreshCalls)
        val prefs = f.dataStore.data.first()
        assertTrue(prefs[at]!!.startsWith(CloudTokenSecureVault.SEALED_PREFIX))
        assertTrue(prefs[rt]!!.startsWith(CloudTokenSecureVault.SEALED_PREFIX))
        val vault = CloudTokenSecureVault(RuntimeEnvironment.getApplication(), scopeId = f.profile.id)
        assertEquals(live, vault.unseal(prefs[at]!!))
        assertEquals("rotated-refresh-secret", vault.unseal(prefs[rt]!!))
        val recreated = CampusRepository(RuntimeEnvironment.getApplication(), f.database, f.dataStore, f.profile).also { it.activateSession(1); repos += it }
        recreated.cloudHttpTransportForTest = { method, url, _, token, _ ->
            assertEquals("POST", method); assertTrue(url.endsWith("/sync/snapshot")); assertEquals(live, token); syncBody()
        }
        recreated.syncWithCampusCloud().getOrThrow()
        Unit
    }
    @Test fun `非旧档案不采纳未密封令牌且零网络请求`() = runBlocking {
        val f = fixture(false); var calls = 0
        f.repository.cloudHttpTransportForTest = { _, _, _, _, _ -> calls++; error("禁止发送") }
        assertTrue(f.repository.syncWithCampusCloud().isFailure); assertEquals(0, calls)
    }
    @Test fun `旧令牌属其他云账号时不迁移不串号且零网络请求`() = runBlocking {
        val f = fixture(true, "another-user"); var calls = 0
        f.repository.cloudHttpTransportForTest = { _, _, _, _, _ -> calls++; error("禁止发送") }
        assertTrue(f.repository.syncWithCampusCloud().isFailure); assertEquals(0, calls)
    }
}
