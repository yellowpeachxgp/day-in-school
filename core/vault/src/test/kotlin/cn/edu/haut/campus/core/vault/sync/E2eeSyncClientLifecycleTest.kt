package cn.edu.haut.campus.core.vault.sync

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class E2eeSyncClientLifecycleTest {
    @Test
    fun enableBindsCloudUserDisableWithdrawsBeforeRevokeAndReenableUsesFreshDeviceId() = runBlocking {
        val api = LifecycleApi()
        val keyStore = InMemoryE2eeSyncKeyStore()
        val stateStore = InMemoryE2eeSyncStateStore()
        val client = E2eeSyncClient(
            stateStore = stateStore,
            keyStore = keyStore,
            backupStore = EmptyBackupStore(),
            apiFactory = { api },
            cloudUserIdProvider = { "cloud-user-a" }
        )

        val enabled = client.enable(E2eeSyncEnableMode.CREATE_NEW_SYNC_SPACE, "测试 Android")
        assertNotNull(enabled.recoveryKeyForUserCopy)
        val stateAfterEnable = stateStore.load()
        assertTrue(stateAfterEnable.enabled)
        assertEquals("cloud-user-a", stateAfterEnable.cloudUserId)
        assertEquals(enabled.deviceId, stateAfterEnable.deviceId)

        val disableReport = client.disableAndWithdraw()
        assertTrue(disableReport.consentWithdrawn)
        assertTrue(disableReport.deviceRevoked)
        val withdrawIndex = api.events.indexOf("withdraw")
        val revokeIndex = api.events.indexOf("revoke")
        assertTrue(withdrawIndex >= 0)
        assertTrue(revokeIndex > withdrawIndex)
        val stateAfterDisable = stateStore.load()
        assertFalse(stateAfterDisable.enabled)
        assertNull(stateAfterDisable.deviceId)
        assertNull(stateAfterDisable.consentId)

        val reenabled = client.enable(
            E2eeSyncEnableMode.JOIN_EXISTING_SYNC_SPACE,
            "测试 Android",
            enabled.recoveryKeyForUserCopy
        )
        assertNotEquals(enabled.deviceId, reenabled.deviceId)
        assertEquals("cloud-user-a", stateStore.load().cloudUserId)
    }

    @Test
    fun interruptedFirstRegistrationRetriesWithSameDeviceKeyAndIdempotencyIdentity() = runBlocking {
        val api = LifecycleApi()
        api.failNextRegisterWithIOException = true
        val keyStore = InMemoryE2eeSyncKeyStore()
        val stateStore = InMemoryE2eeSyncStateStore()
        val client = E2eeSyncClient(
            stateStore = stateStore,
            keyStore = keyStore,
            backupStore = EmptyBackupStore(),
            apiFactory = { api },
            cloudUserIdProvider = { "cloud-user-a" }
        )

        try {
            client.enable(E2eeSyncEnableMode.CREATE_NEW_SYNC_SPACE, "测试 Android")
            org.junit.Assert.fail("首次注册网络失败必须向上暴露")
        } catch (expected: IOException) {
            // expected: 密钥与 installation UUID 已在首个网络写前持久化
        }
        val failedDeviceId = stateStore.load().deviceId
        assertNotNull(failedDeviceId)

        val retry = client.enable(E2eeSyncEnableMode.CREATE_NEW_SYNC_SPACE, "测试 Android")
        assertEquals(failedDeviceId, retry.deviceId)
        assertNotNull("中断重试成功后仍必须给用户一次抄写恢复密钥的机会", retry.recoveryKeyForUserCopy)
    }

    @Test
    fun cloudIdentityMismatchFreezesEnableInsteadOfMixingAccounts() = runBlocking {
        val api = LifecycleApi()
        val client = E2eeSyncClient(
            stateStore = InMemoryE2eeSyncStateStore(),
            keyStore = InMemoryE2eeSyncKeyStore(),
            backupStore = EmptyBackupStore(),
            apiFactory = { api },
            cloudUserIdProvider = { null }
        )
        try {
            client.enable(E2eeSyncEnableMode.CREATE_NEW_SYNC_SPACE, null)
            org.junit.Assert.fail("未登录云账号不得启用")
        } catch (expected: E2eeSyncConsentException) {
            // expected
        }
        assertTrue(api.events.isEmpty())
    }

    private class EmptyBackupStore : SyncBackupStore {
        override val schemaVersion: Int = 13
        override suspend fun captureRows(): List<SyncBackupRowSnapshot> = emptyList()
        override suspend fun readRow(table: String, rowKey: String): SyncBackupRowSnapshot? = null
        override suspend fun applyUpserts(rows: List<SyncBackupRowSnapshot>) = Unit
        override suspend fun applyDeletes(deletes: List<Pair<String, String>>) = Unit
    }

    private class LifecycleApi : E2eeSyncApi {
        val events = mutableListOf<String>()
        private val consentId = UUID.randomUUID().toString()
        var failNextRegisterWithIOException = false

        override suspend fun grantConsent(): SyncConsentRecord {
            events += "grant"
            return SyncConsentRecord(
                id = consentId,
                purpose = E2eeSyncProtocol.CONSENT_PURPOSE,
                documentVersion = E2eeSyncProtocol.CONSENT_DOCUMENT_VERSION,
                documentSha256 = E2eeSyncProtocol.CONSENT_DOCUMENT_SHA256
            )
        }

        override suspend fun withdrawConsent(consentId: String) {
            events += "withdraw"
        }

        override suspend fun listDevices(): List<SyncDevice> = emptyList()

        override suspend fun registerDevice(deviceId: String, consentId: String, deviceLabel: String?, idempotencyKey: String): SyncDevice {
            if (failNextRegisterWithIOException) {
                failNextRegisterWithIOException = false
                throw IOException("simulated registration network loss")
            }
            events += "register"
            return SyncDevice(deviceId, "ACTIVE", deviceLabel, "ANDROID", 1, "2026-01-01T00:00:00Z", null, null, null)
        }

        override suspend fun revokeDevice(deviceId: String, reason: String): SyncDevice {
            events += "revoke"
            return SyncDevice(deviceId, "REVOKED", null, "ANDROID", 1, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", reason, null)
        }

        override suspend fun putBlob(blobId: String, request: SyncBlobPutInput): SyncBlobMetadata = unsupported()
        override suspend fun downloadBlob(blobId: String): Pair<SyncBlobMetadata, String> = unsupported()
        override suspend fun deleteBlob(blobId: String, request: SyncBlobDeleteInput): SyncBlobMetadata = unsupported()
        override suspend fun listChanges(cursor: String?, limit: Int): SyncChangePage = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("lifecycle test does not use blob APIs")
    }
}
