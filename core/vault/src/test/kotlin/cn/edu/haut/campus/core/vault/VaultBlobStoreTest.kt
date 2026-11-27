package cn.edu.haut.campus.core.vault

import androidx.room.Room
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VaultBlobStoreTest {
    private lateinit var database: HautDatabase
    private lateinit var blobs: VaultBlobStore
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct_blob"),
        profileId = ProfileId("prof_blob"),
        campusId = CampusId("lianhuajie")
    )

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            HautDatabase::class.java
        ).allowMainThreadQueries().build()
        blobs = VaultBlobStore(database, clock = { Instant.parse("2026-01-02T03:04:05Z") })
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun putGetDeduplicatesAndDefensivelyCopiesContent() = runBlocking {
        val input = byteArrayOf(1, 2, 3, 4)
        val ref = blobs.put(input, "application/octet-stream", scope)
        input[0] = 99

        assertTrue(ref.blobId.startsWith("blob-"))
        assertEquals(64, ref.sha256.length)
        assertEquals(4L, ref.byteCount)
        assertEquals(1, blobs.count())
        assertEquals(4L, blobs.totalBytes())

        val duplicate = blobs.put(byteArrayOf(1, 2, 3, 4), "application/octet-stream", scope)
        assertEquals(ref.blobId, duplicate.blobId)
        assertEquals(1, blobs.count())

        val stored = blobs.get(ref.blobId, scope)
        val firstCopy = stored?.content()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), firstCopy)
        firstCopy?.set(0, 88)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), stored?.content())
    }

    @Test
    fun blobIdsAndReadsAreScopeIsolated() = runBlocking {
        val otherScope = scope.copy(accountId = AccountId("acct_other"), profileId = ProfileId("prof_other"))
        val first = blobs.put(byteArrayOf(1), "text/plain", scope)
        val second = blobs.put(byteArrayOf(1), "text/plain", otherScope)

        assertNotEquals(first.blobId, second.blobId)
        assertNull(blobs.get(first.blobId, otherScope))
        assertEquals(1, blobs.get(second.blobId, otherScope)?.content()?.size)
    }

    @Test
    fun deleteIsScopedAndIdempotent() = runBlocking {
        val otherScope = scope.copy(accountId = AccountId("acct_other"), profileId = ProfileId("prof_other"))
        val ref = blobs.put(byteArrayOf(1, 2), "text/plain", scope)

        assertFalse(blobs.delete(ref.blobId, otherScope))
        assertTrue(blobs.get(ref.blobId, scope) != null)
        assertTrue(blobs.delete(ref.blobId, scope))
        assertFalse(blobs.delete(ref.blobId, scope))
        assertNull(blobs.get(ref.blobId, scope))
        assertEquals(0, blobs.count())
    }
}
