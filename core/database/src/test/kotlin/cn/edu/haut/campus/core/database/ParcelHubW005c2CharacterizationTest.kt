package cn.edu.haut.campus.core.database

import android.security.keystore.KeyGenParameterSpec
import cn.edu.haut.campus.core.campusservices.ParcelLifecycleStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * W0-05-c2 搬迁前特征冻结：Parcel Hub 手动包裹、取件状态流转、
 * 数据源权限 Fail-Closed 与小米账号本地连接元数据。
 * 不访问真实网络、真实小米账号或真实学校账号。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ParcelHubW005c2CharacterizationTest {
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
    private val parcelDao get() = fixture.database.cloudAndParcelDao()

    @Before
    fun setUp() {
        keys.clear()
        Security.addProvider(provider)
        fixture = RepairTestFixture.create(
            RuntimeEnvironment.getApplication(),
            "prof_w005c2_parcel_" + System.nanoTime()
        )
    }

    @After
    fun tearDown() {
        repo.onScheduleOrPolicyChangedListener = null
        fixture.close()
        Security.removeProvider(provider.name)
        keys.clear()
    }

    @Test
    fun w005c2_manual_parcel_upsert_merge_pickup_batch_and_delete_snapshot() = runBlocking {
        val notifications = mutableListOf<String>()
        repo.onScheduleOrPolicyChangedListener = { reason -> notifications += reason }

        val first = repo.upsertParcelItemFromInput(
            trackingNumberRaw = " sf-123 456 ",
            carrierName = " 顺丰速运 ",
            pickupCodeInput = null,
            stationNameInput = null,
            itemTitleInput = " 教材包裹 ",
            rawNotificationText = "【莲花街北苑菜鸟驿站】您的包裹已到，取件码 A-1-2345，请尽快取件"
        ).getOrThrow()

        assertEquals("SF123456", first.trackingNumber)
        assertEquals("顺丰速运", first.carrierName)
        assertEquals(ParcelLifecycleStatus.READY_FOR_PICKUP.wireName, first.status)
        assertEquals("A-1-2345", first.pickupCode)
        assertEquals("莲花街北苑菜鸟驿站", first.stationName)
        assertEquals("教材包裹", first.itemTitle)
        assertEquals("已到达驿站/快递柜，请凭取件码取件", first.estimatedDeliveryText)
        assertTrue(first.sourcesJson.contains("parcel.manual"))
        assertTrue(first.fieldProvenanceJson.contains("parcel.manual"))
        assertEquals("[]", first.checkpointsJson)
        assertEquals(first, parcelDao.getParcelByTrackingNumber("SF123456"))
        assertEquals(listOf(first), repo.parcelItemsFlow.first())

        val merged = repo.upsertParcelItemFromInput(
            trackingNumberRaw = "SF123456",
            carrierName = " null ",
            pickupCodeInput = " undefined ",
            stationNameInput = "  ",
            itemTitleInput = null,
            rawNotificationText = null
        ).getOrThrow()
        assertEquals(first.id, merged.id)
        assertEquals(first.createdAtEpochMillis, merged.createdAtEpochMillis)
        assertEquals("A-1-2345", merged.pickupCode)
        assertEquals("莲花街北苑菜鸟驿站", merged.stationName)
        assertEquals("教材包裹", merged.itemTitle)
        assertEquals(ParcelLifecycleStatus.READY_FOR_PICKUP.wireName, merged.status)

        repo.markParcelPickedUp(first.id)
        val picked = parcelDao.getParcelByTrackingNumber("SF123456")!!
        assertEquals(ParcelLifecycleStatus.PICKED_UP.wireName, picked.status)
        assertEquals("本人已取件签收", picked.estimatedDeliveryText)

        val targetA = repo.upsertParcelItemFromInput(
            trackingNumberRaw = "YT00000001",
            stationNameInput = "莲花街北苑菜鸟驿站"
        ).getOrThrow()
        val targetB = repo.upsertParcelItemFromInput(
            trackingNumberRaw = "YT00000002",
            stationNameInput = "莲花街北苑菜鸟驿站"
        ).getOrThrow()
        val otherStation = repo.upsertParcelItemFromInput(
            trackingNumberRaw = "YT00000003",
            stationNameInput = "嵩山路菜鸟驿站"
        ).getOrThrow()

        repo.markAllStationParcelsPickedUp("莲花街北苑菜鸟驿站")
        val afterBatch = parcelDao.getParcelItemsSnapshot().associateBy { it.id }
        assertEquals(ParcelLifecycleStatus.PICKED_UP.wireName, afterBatch.getValue(targetA.id).status)
        assertEquals(ParcelLifecycleStatus.PICKED_UP.wireName, afterBatch.getValue(targetB.id).status)
        assertNotEquals(ParcelLifecycleStatus.PICKED_UP.wireName, afterBatch.getValue(otherStation.id).status)

        repo.deleteParcelById(otherStation.id)
        assertNull(parcelDao.getParcelItemsSnapshot().firstOrNull { it.id == otherStation.id })
        assertTrue(parcelDao.observeParcelSourceConnections().first().isEmpty())
        assertEquals(
            listOf(
                "PARCEL_UPSERTED",
                "PARCEL_UPSERTED",
                "PARCEL_PICKED_UP",
                "PARCEL_UPSERTED",
                "PARCEL_UPSERTED",
                "PARCEL_UPSERTED",
                "PARCEL_STATION_BATCH_PICKED_UP",
                "PARCEL_DELETED"
            ),
            notifications
        )
    }

    @Test
    fun w005c2_manual_parcel_rejects_invalid_tracking_and_unauthorized_provider() = runBlocking {
        val invalidTracking = repo.upsertParcelItemFromInput(" abc ")
        assertTrue(invalidTracking.isFailure)
        assertTrue(invalidTracking.exceptionOrNull()!!.message!!.contains("运单号格式不合法"))

        val unauthorized = repo.upsertParcelItemFromInput(
            trackingNumberRaw = "SF123456",
            providerId = "parcel.taobao.cainiao"
        )
        assertTrue(unauthorized.isFailure)
        assertTrue(unauthorized.exceptionOrNull() is SecurityException)
        assertTrue(parcelDao.getParcelItemsSnapshot().isEmpty())
    }

    @Test
    fun w005c2_xiaomi_web_login_persists_account_and_source_connection_locally() = runBlocking {
        assertTrue(repo.getXiaomiAccounts().isEmpty())
        val blankToken = repo.saveXiaomiAccountFromWebLogin(
            token = " ",
            cUserId = "123456789",
            accountId = "123456789",
            detectedPhones = emptyList()
        )
        assertTrue(blankToken.isFailure)

        val savedResult = repo.saveXiaomiAccountFromWebLogin(
            token = " synthetic-service-token ",
            cUserId = " 123456789 ",
            accountId = " 123456789 ",
            detectedPhones = listOf("13800000000", "not-a-phone", "13800000000")
        )
        val saved = savedResult.getOrThrow()
        assertEquals("xiaomi_123456789", saved.id)
        assertEquals("123456789", saved.cUserId)
        assertEquals("123456789", saved.accountId)
        assertEquals(listOf("13800000000"), saved.phones)
        assertTrue(saved.enabled)
        assertEquals(saved.id, repo.getXiaomiAccounts().single().id)
        assertEquals(listOf("13800000000"), repo.getXiaomiAccounts().single().phones)

        val connection = parcelDao.observeParcelSourceConnections().first().single()
        assertEquals(saved.id, connection.id)
        assertEquals("parcel.xiaomi.personal-assistant", connection.providerId)
        assertEquals("PRODUCTION_ENABLED", connection.authorityStatus)
        assertEquals("ACTIVE", connection.status)
        assertEquals("${saved.label} · ${saved.maskedPhonesSummary}", connection.accountAlias)

        repo.removeXiaomiAccount(saved.accountId)
        assertTrue(repo.getXiaomiAccounts().isEmpty())
        assertTrue(parcelDao.observeParcelSourceConnections().first().isEmpty())
    }

    @Test
    fun w005c2_xiaomi_network_entrypoints_fail_closed_without_local_prerequisites() = runBlocking {
        val codeWithoutAccount = repo.sendXiaomiPhoneVerificationCode("missing", "13800000000")
        assertTrue(codeWithoutAccount.isFailure)
        assertTrue(codeWithoutAccount.exceptionOrNull()!!.message!!.contains("未找到对应的小米登录账号"))

        val syncWithoutAccount = repo.syncXiaomiExpressPackages()
        assertTrue(syncWithoutAccount.isFailure)
        assertTrue(syncWithoutAccount.exceptionOrNull()!!.message!!.contains("尚未登录小米账号"))

        val detailWithoutParcel = repo.fetchXiaomiParcelDetail("missing-parcel")
        assertTrue(detailWithoutParcel.isFailure)
        assertTrue(detailWithoutParcel.exceptionOrNull()!!.message!!.contains("未找到该包裹记录"))
        assertFalse(parcelDao.getParcelItemsSnapshot().isNotEmpty())
    }
}
