package cn.edu.haut.campus.core.database

import android.security.keystore.KeyGenParameterSpec
import androidx.datastore.preferences.core.edit
import cn.edu.haut.campus.core.campusservices.LibraryRegionSeatsSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatActionType
import cn.edu.haut.campus.core.campusservices.LibrarySeatRefreshSource
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionState
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionVault
import cn.edu.haut.campus.core.campusservices.LibrarySeatUserInfo
import cn.edu.haut.campus.core.campusservices.LibraryStudyRegionItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * W0-05-c3 图书馆座位访问簇搬迁特征冻结。
 * 只覆盖无网络守卫、加密会话仓读写与失败消息快照；不访问真实学校账号或 wslib 网络。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CampusRepositoryW005c3LibrarySeatCharacterizationTest {
    companion object {
        /** 内存 JCE 密钥端口：只替换 Robolectric 缺失的 AndroidKeyStore Provider，不冒充硬件隔离。 */
        val keys = mutableMapOf<String, Key>()
    }

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
        override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
            alias = (params as KeyGenParameterSpec).keystoreAlias
        }
        override fun engineGenerateKey(): SecretKey =
            KeyGenerator.getInstance("AES", "SunJCE").apply { init(256) }.generateKey().also { keys[alias] = it }
    }

    private val provider = object : Provider("AndroidKeyStore", 1.0, "隔离测试密钥端口") {
        init {
            put("KeyStore.AndroidKeyStore", TestKeyStore::class.java.name)
            put("KeyGenerator.AES", TestKeyGenerator::class.java.name)
        }
    }

    private lateinit var fixture: RepairTestFixture
    private val repo get() = fixture.repository

    @Before
    fun setUp() {
        keys.clear()
        Security.addProvider(provider)
        fixture = RepairTestFixture.create(
            RuntimeEnvironment.getApplication(),
            "prof_w005c3_library_" + System.nanoTime()
        )
        newVault().clear()
    }

    @After
    fun tearDown() {
        newVault().clear()
        fixture.close()
        Security.removeProvider(provider.name)
        keys.clear()
    }

    private fun newVault() = LibrarySeatSessionVault(
        RuntimeEnvironment.getApplication(),
        scopeId = fixture.profile.id.takeUnless { fixture.profile.isLegacy }
    )

    private fun sessionSnapshot(token: String) = LibrarySeatSessionSnapshot(
        rawToken = token,
        casTgcCookie = "castgc-w005c3",
        casJsessionId = "jsession-w005c3",
        userInfo = LibrarySeatUserInfo(
            userNo = "20260001",
            userName = "测试同学",
            department = "测试学院",
            cardNo = "card-w005c3",
            breakNum = "0"
        ),
        updatedAtEpochMs = 1_760_000_000_000L,
        refreshSource = LibrarySeatRefreshSource.WEBVIEW_CAPTURE
    )

    @Test
    fun w005c3_library_session_vault_read_and_clear_snapshot() = runBlocking {
        val vault = newVault()
        val snapshot = sessionSnapshot("token-w005c3-valid-16")

        assertNull(repo.readCachedLibrarySeatSession())
        vault.saveSnapshot(snapshot)

        assertEquals(snapshot, repo.readCachedLibrarySeatSession())

        repo.clearLibrarySeatSession()
        assertNull(repo.readCachedLibrarySeatSession())
    }

    @Test
    fun w005c3_missing_credentials_guard_messages_snapshot() = runBlocking {
        val state = repo.ensureLibrarySeatSession(forceRefresh = false)

        assertTrue(state is LibrarySeatSessionState.CredentialsRequired)
        val requiredMessage = (state as LibrarySeatSessionState.CredentialsRequired).message
        assertEquals(
            "尚未保存统一身份认证凭据；绑定后将加密保存在本地数据库中，Cookie 失效时自动后台登录",
            requiredMessage
        )

        val overview = repo.fetchLibrarySeatOverview()
        assertTrue(overview.isFailure)
        assertEquals(requiredMessage, overview.exceptionOrNull()?.message)

        val action = repo.executeLibrarySeatAction(LibrarySeatActionType.SIGN_IN, bookingId = 123L)
        assertTrue(action.isFailure)
        assertEquals(requiredMessage, action.exceptionOrNull()?.message)
    }

    @Test
    fun w005c3_blank_credentials_fail_before_any_preference_write() = runBlocking {
        val before = fixture.dataStore.data.first()

        val state = repo.saveLibrarySeatCredentialsAndLogin("  ", "")

        assertTrue(state is LibrarySeatSessionState.AuthError)
        assertEquals("请输入学号和统一身份认证密码", (state as LibrarySeatSessionState.AuthError).message)
        val after = fixture.dataStore.data.first()
        assertEquals(before.asMap(), after.asMap())
    }

    @Test
    fun w005c3_planning_queries_fail_closed_without_library_session() = runBlocking {
        val date = "2026-11-28"
        val region = LibraryStudyRegionItem(42L, "测试自习室")

        assertNull(repo.captureLibrarySeatWebToken(""))

        val regions = repo.fetchLibraryStudyRegions(date)
        assertTrue(regions.isFailure)
        assertEquals("请先登录图书馆账号", regions.exceptionOrNull()?.message)

        val seats = repo.fetchLibraryRegionSeats(region.id, date)
        assertTrue(seats.isFailure)
        assertEquals("请先登录图书馆账号", seats.exceptionOrNull()?.message)

        val cachedRegions = repo.loadLibraryPlanningRegions(date, refresh = false)
        assertTrue(cachedRegions.isFailure)
        assertEquals("请先登录图书馆账号", cachedRegions.exceptionOrNull()?.message)

        val cachedSeats = repo.loadLibraryPlanningSeats(region, date, refresh = false)
        assertTrue(cachedSeats.isFailure)
        assertEquals("请先登录图书馆账号", cachedSeats.exceptionOrNull()?.message)
    }
}
