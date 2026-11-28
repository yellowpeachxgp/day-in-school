package cn.edu.haut.campus.core.ai.android

import cn.edu.haut.campus.core.ai.AiEndpoint
import cn.edu.haut.campus.core.ai.AiKeyAccessException
import cn.edu.haut.campus.core.ai.AiKeyStatus
import cn.edu.haut.campus.core.ai.HttpsUrl
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidAiKeyStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct-ai-test"),
        profileId = ProfileId("prof-ai-test"),
        campusId = CampusId("north")
    )
    private val otherScope = scope.copy(accountId = AccountId("acct-other"), profileId = ProfileId("prof-other"))
    private val endpoint = AiEndpoint(HttpsUrl("https://provider.example/v1"), "chat-vision-test", supportsVision = true)

    @Test
    fun storeUsesNoBackupPartitionEncryptsKeyAndNeverExposesPlaintextOnDisk() = runTest {
        val store = AndroidAiKeyStore(context, rootDir = testRoot("plain"), keys = fakeKeys)
        val canary = keyCanary()
        store.put(scope, endpoint, canary.toCharArray())

        assertTrue(store.rootDir.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertEquals(AiKeyStatus.CONFIGURED, store.status(scope).first())
        assertEquals(AiKeyStatus.NONE, store.status(otherScope).first())
        val allBytes = store.rootDir.walkTopDown().filter { it.isFile }.flatMap { it.readBytes().toList() }.toList().toByteArray()
        assertFalse(allBytes.containsText(canary))
        assertFalse(allBytes.containsText(endpoint.baseUrl.withoutTrailingSlash))

        store.setEnabled(scope, true)
        val configuration = store.configuration(scope)
        assertEquals(endpoint, configuration.endpoint)
        assertEquals(AiKeyStatus.CONFIGURED, configuration.keyStatus)
        assertTrue(configuration.enabled)

        store.wipeAll(scope)
    }

    @Test
    fun keyMaterialExistsOnlyInsideCallbackAndArrayIsWipedAfterwards() = runTest {
        val store = AndroidAiKeyStore(context, rootDir = testRoot("wipe"), keys = fakeKeys)
        val canary = keyCanary()
        store.put(scope, endpoint, canary.toCharArray())
        var exposed: CharArray? = null

        val endpointInside = store.withKeyMaterial(scope) { loadedEndpoint, key ->
            exposed = key
            assertEquals(canary, String(key))
            loadedEndpoint
        }

        assertEquals(endpoint, endpointInside)
        assertNotEquals(null, exposed)
        assertArrayEquals(CharArray(canary.length), exposed!!)
        store.wipeAll(scope)
    }

    @Test
    fun corruptEnvelopeBecomesInvalidAndDoesNotThrowOrLeakMaterial() = runTest {
        val store = AndroidAiKeyStore(context, rootDir = testRoot("corrupt"), keys = fakeKeys)
        store.put(scope, endpoint, keyCanary().toCharArray())
        val vault = store.rootDir.listFiles()?.single { it.name.endsWith(".vault") }
        assertNotEquals(null, vault)
        vault?.writeBytes(byteArrayOf(1, 2, 3, 4))

        assertEquals(AiKeyStatus.INVALID, store.status(scope).first())
        assertEquals(AiKeyStatus.INVALID, store.configuration(scope).keyStatus)
        assertThrows(AiKeyAccessException::class.java) {
            kotlinx.coroutines.runBlocking {
                store.withKeyMaterial(scope) { _, _ -> Unit }
            }
        }
        store.wipeAll(scope)
    }

    @Test
    fun wipeDeletesCiphertextEnabledFlagAndProfileKeyMaterial() = runTest {
        val store = AndroidAiKeyStore(context, rootDir = testRoot("clear"), keys = fakeKeys)
        store.put(scope, endpoint, keyCanary().toCharArray())
        store.setEnabled(scope, true)
        assertTrue(store.rootDir.listFiles()?.isNotEmpty() == true)

        store.wipeAll(scope)

        assertEquals(AiKeyStatus.NONE, store.status(scope).first())
        assertFalse(store.configuration(scope).enabled)
        assertTrue(store.rootDir.listFiles()?.isEmpty() != false)
    }

    private val fakeKeys = FakeAiAesKeyProvider()

    private class FakeAiAesKeyProvider : AiAesKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()

        override fun existing(scope: SchoolAccountScope): SecretKey? = keys[storageKey(scope)]

        override fun getOrCreate(scope: SchoolAccountScope): SecretKey = keys.getOrPut(storageKey(scope)) {
            // 测试专用软件 AES key 只留在进程内；生产实现仍由 AndroidKeyStore 生成非导出 key。
            SecretKeySpec(ByteArray(32) { index -> (index * 17 + 11).toByte() }, "AES")
        }

        override fun delete(scope: SchoolAccountScope) {
            keys.remove(storageKey(scope))
        }

        private fun storageKey(scope: SchoolAccountScope): String = AiByokStorageFormat.scopeStorageKey(scope)
    }

    private fun testRoot(name: String): File {
        val root = java.io.File(context.noBackupFilesDir, "ai_byok_test_" + name)
        root.deleteRecursively()
        return root
    }

    private fun keyCanary(): String = listOf("android", "test", "key", "canary").joinToString("-")

    private fun ByteArray.containsText(text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_8)
        if (needle.isEmpty() || size < needle.size) return false
        for (index in 0..size - needle.size) {
            var same = true
            for (offset in needle.indices) {
                if (this[index + offset] != needle[offset]) {
                    same = false
                    break
                }
            }
            if (same) return true
        }
        return false
    }
}
