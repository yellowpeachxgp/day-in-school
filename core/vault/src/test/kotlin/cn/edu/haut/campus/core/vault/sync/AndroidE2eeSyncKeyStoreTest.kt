package cn.edu.haut.campus.core.vault.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AndroidE2eeSyncKeyStoreTest {
    @Test
    fun keystoreSealedStoreRoundTripsAndWipes() = runBlocking {
        val wrappingKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val keys = AndroidE2eeSyncKeyStore(
            RuntimeEnvironment.getApplication(),
            "prof-android-keystore-test",
            wrappingKeyOverride = { wrappingKey }
        )
        keys.wipe()
        assertFalse(keys.exists())
        val recovery = keys.createNew()
        val (expectedMaster, expectedSpace) = E2eeSyncRecoveryKeys.decode(recovery)
        assertTrue(keys.exists())

        val material = keys.load()
        assertArrayEquals(expectedMaster, material.masterKey)
        assertEquals(E2eeSyncRecoveryKeys.syncSpaceId(expectedSpace), material.syncSpaceId)
        assertEquals(E2eeSyncBlockCipher.keyId(expectedMaster), material.keyId)
        material.zero()

        keys.wipe()
        assertFalse(keys.exists())
    }
}
