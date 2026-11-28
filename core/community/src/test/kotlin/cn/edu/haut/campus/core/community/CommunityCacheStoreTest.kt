package cn.edu.haut.campus.core.community

import cn.edu.haut.campus.core.model.json.JsonValue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CommunityCacheStoreTest {
    private fun key(operationId: String = "listGroups", marker: String = "haut"): CommunityCacheKey = CommunityCacheKey(
        operationId = operationId,
        resolvedPath = "/api/v1/groups",
        queryParams = mapOf("school_id" to listOf(marker)),
    )

    private fun envelope(requestId: String = "req-cache"): CommunityEnvelope = envelopeFromBody(successBody("[]", requestId))

    @Test
    fun fileCacheRoundTripsEnvelopeAndIsProfileIsolated() = runTest {
        val filesDir = Files.createTempDirectory("community-cache-test").toFile()
        try {
            val firstRoot = CommunityCacheDirectories.rootForProfile(filesDir, "prof_alpha")
            val secondRoot = CommunityCacheDirectories.rootForProfile(filesDir, "prof_beta")
            assertNotEquals(firstRoot.absolutePath, secondRoot.absolutePath)
            assertFalse(firstRoot.name.contains("prof_alpha"))
            assertFalse(firstRoot.name.contains("acct_alpha"))

            val store = FileCommunityCacheStore(firstRoot)
            val cacheKey = key()
            store.write(cacheKey, envelope(), cachedAtEpochMillis = 42L)

            val read = store.read(cacheKey)
            assertEquals(42L, read?.cachedAtEpochMillis)
            assertEquals("req-cache", read?.envelope?.requestId)
            assertEquals(1, store.countEntries())

            val otherStore = FileCommunityCacheStore(secondRoot)
            assertNull(otherStore.read(cacheKey))
            assertEquals(0, otherStore.countEntries())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun fileCacheWipeDeletesEveryResponseAndLeavesZeroEntries() = runTest {
        val filesDir = Files.createTempDirectory("community-cache-wipe-test").toFile()
        try {
            val root = CommunityCacheDirectories.rootForProfile(filesDir, "prof_wipe")
            val store = FileCommunityCacheStore(root)
            store.write(key(marker = "one"), envelope("req-one"), 1L)
            store.write(key(marker = "two"), envelope("req-two"), 2L)
            assertEquals(2, store.countEntries())

            store.wipe()

            assertEquals(0, store.countEntries())
            assertFalse(root.exists())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun corruptedCacheEntryFailsClosedAndIsDeleted() = runTest {
        val filesDir = Files.createTempDirectory("community-cache-corrupt-test").toFile()
        try {
            val root = CommunityCacheDirectories.rootForProfile(filesDir, "prof_corrupt")
            val store = FileCommunityCacheStore(root)
            val cacheKey = key()
            store.write(cacheKey, envelope(), 7L)
            val responseFile = root.toPath().resolve("responses").resolve(cacheKey.storageId + ".json")
            Files.writeString(responseFile, "not-json")

            assertNull(store.read(cacheKey))
            assertFalse(Files.exists(responseFile))
            assertEquals(0, store.countEntries())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun cacheKeyUsesCanonicalQueryAndBodyWithoutAuthorizationMaterial() = runTest {
        val body = jsonObjectOf(
            "window_end_at" to JsonValue.Str("2026-11-28T12:00:00+08:00"),
            "window_start_at" to JsonValue.Str("2026-11-28T08:00:00+08:00"),
        )
        val first = CommunityCacheKey(
            operationId = "queryGroupAvailability",
            resolvedPath = "/api/v1/groups/g-1/availability/query",
            queryParams = mapOf("b" to listOf("2"), "a" to listOf("1")),
            body = body,
        )
        val second = CommunityCacheKey(
            operationId = "queryGroupAvailability",
            resolvedPath = "/api/v1/groups/g-1/availability/query",
            queryParams = mapOf("a" to listOf("1"), "b" to listOf("2")),
            body = body,
        )
        assertEquals(first.storageId, second.storageId)
        val material = first.keyMaterial().toString()
        assertFalse(material.contains("Authorization"))
        assertFalse(material.contains("synthetic-access-token"))
    }

    @Test
    fun entryLargerThanConfiguredLimitIsRejectedWithoutPartialFile() = runTest {
        val filesDir = Files.createTempDirectory("community-cache-limit-test").toFile()
        try {
            val root = CommunityCacheDirectories.rootForProfile(filesDir, "prof_limit")
            val store = FileCommunityCacheStore(root, maxEntryBytes = 32)
            val cacheKey = key()
            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                kotlinx.coroutines.runBlocking { store.write(cacheKey, envelope(), 1L) }
            }
            assertEquals(0, store.countEntries())
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
