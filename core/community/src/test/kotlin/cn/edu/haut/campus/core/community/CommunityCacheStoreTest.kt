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
    private fun key(
        operationId: String = "listGroups",
        marker: String = "haut",
        identity: CommunityCacheIdentity = communityTestCacheIdentity(),
    ): CommunityCacheKey = CommunityCacheKey(
        cloudIdentity = identity,
        apiBaseUrl = "https://cloud.example.test",
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

            // 擦除前创建的旧实例即使随后完成写路径，也不得把已擦除数据重新种回；
            // 擦除后新建实例采用新代际，可以继续使用同一目录。
            store.write(key(marker = "after-wipe-old-instance"), envelope("req-old"), 3L)
            assertNull(store.read(key(marker = "after-wipe-old-instance")))
            assertEquals(0, store.countEntries())
            val replacement = FileCommunityCacheStore(root)
            replacement.write(key(marker = "after-wipe-new-instance"), envelope("req-new"), 4L)
            assertEquals(1, replacement.countEntries())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun invalidateAllClearsEntriesWithoutRetiringTheStore() = runTest {
        val filesDir = Files.createTempDirectory("community-cache-invalidate-test").toFile()
        try {
            val root = CommunityCacheDirectories.rootForProfile(filesDir, "prof_invalidate")
            val store = FileCommunityCacheStore(root)
            store.write(key(marker = "before"), envelope(), 1L)
            store.invalidateAll()
            assertEquals(0, store.countEntries())
            store.write(key(marker = "after"), envelope(), 2L)
            assertEquals(1, store.countEntries())
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
            cloudIdentity = communityTestCacheIdentity(),
            apiBaseUrl = "https://cloud.example.test",
            operationId = "queryGroupAvailability",
            resolvedPath = "/api/v1/groups/g-1/availability/query",
            queryParams = mapOf("b" to listOf("2"), "a" to listOf("1")),
            body = body,
        )
        val second = CommunityCacheKey(
            cloudIdentity = communityTestCacheIdentity(),
            apiBaseUrl = "https://cloud.example.test",
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
            val store = FileCommunityCacheStore(root.toPath(), maxEntryBytes = 32)
            val cacheKey = key()
            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                kotlinx.coroutines.runBlocking { store.write(cacheKey, envelope(), 1L) }
            }
            assertEquals(0, store.countEntries())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun cacheKeySeparatesCloudUserIdentityGenerationAndBaseUrl() = runTest {
        val base = key()
        val same = key()
        val otherUser = key(
            identity = communityTestCacheIdentity(
                cloudUserId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                identityGeneration = 1L,
            ),
        )
        val nextGeneration = key(identity = communityTestCacheIdentity(identityGeneration = 2L))
        val otherBase = CommunityCacheKey(
            cloudIdentity = communityTestCacheIdentity(),
            apiBaseUrl = "https://mirror.example.test",
            operationId = "listGroups",
            resolvedPath = "/api/v1/groups",
            queryParams = mapOf("school_id" to listOf("haut")),
        )

        assertEquals(base.storageId, same.storageId)
        assertNotEquals(base.storageId, otherUser.storageId)
        assertNotEquals(base.storageId, nextGeneration.storageId)
        assertNotEquals(base.storageId, otherBase.storageId)
    }

    @Test
    fun cacheFileDoesNotPersistRawCloudUserId() = runTest {
        val filesDir = Files.createTempDirectory("community-cache-identity-test").toFile()
        try {
            val root = CommunityCacheDirectories.rootForProfile(filesDir, "prof_identity_key")
            val cloudUserId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val store = FileCommunityCacheStore(root)
            val cacheKey = key(identity = communityTestCacheIdentity(cloudUserId = cloudUserId))
            store.write(cacheKey, envelope(), 9L)
            val responseFile = root.toPath().resolve("responses").resolve(cacheKey.storageId + ".json")

            val persisted = Files.readString(responseFile, Charsets.UTF_8)
            assertFalse(persisted.contains(cloudUserId))
            assertEquals("req-cache", store.read(cacheKey)?.envelope?.requestId)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun wipeDeletionFailurePropagatesAndBlocksFurtherReadsUntilSuccessfulRetry() = runTest {
        val filesDir = Files.createTempDirectory("community-cache-wipe-failure-test").toFile()
        try {
            val root = CommunityCacheDirectories.rootForProfile(filesDir, "prof_wipe_failure")
            val cacheKey = key()
            var failDeletion = true
            val store = FileCommunityCacheStore(
                rootDir = root.toPath(),
                maxEntries = 128,
                maxBytes = 2L * 1024L * 1024L,
                maxEntryBytes = 512 * 1024,
                deleteTreeImpl = { path ->
                    if (failDeletion) throw java.io.IOException("synthetic delete failure")
                    if (!path.toFile().deleteRecursively() && Files.exists(path)) {
                        throw java.io.IOException("synthetic retry delete failure")
                    }
                },
            )
            store.write(cacheKey, envelope(), 11L)
            val responseFile = root.toPath().resolve("responses").resolve(cacheKey.storageId + ".json")
            assertTrue(Files.exists(responseFile))

            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                kotlinx.coroutines.runBlocking { store.wipe() }
            }

            assertTrue("失败注入不删除文件，用于证明异常不得伪装成已擦除", Files.exists(responseFile))
            assertNull("擦除失败后即使是残留文件也不得继续被读作缓存", store.read(cacheKey))

            failDeletion = false
            store.wipe()
            assertFalse(root.exists())
            val replacement = FileCommunityCacheStore(root)
            replacement.write(cacheKey, envelope("req-after-retry"), 12L)
            assertEquals("req-after-retry", replacement.read(cacheKey)?.envelope?.requestId)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun invalidateDeletionFailurePersistsPoisonTombstoneUntilSuccessfulRetry() = runTest {
        val filesDir = Files.createTempDirectory("community-cache-invalidate-failure-test").toFile()
        try {
            val root = CommunityCacheDirectories.rootForProfile(filesDir, "prof_invalidate_failure")
            val cacheKey = key()
            var failDeletion = true
            val store = FileCommunityCacheStore(
                rootDir = root.toPath(),
                maxEntries = 128,
                maxBytes = 2L * 1024L * 1024L,
                maxEntryBytes = 512 * 1024,
                deleteTreeImpl = { path ->
                    if (!path.toFile().deleteRecursively() && Files.exists(path)) {
                        throw java.io.IOException("synthetic tree delete failure")
                    }
                },
                deleteResponseFileImpl = { file ->
                    if (failDeletion) throw java.io.IOException("synthetic response delete failure")
                    Files.deleteIfExists(file)
                },
            )
            store.write(cacheKey, envelope(), 11L)
            val responseFile = root.toPath().resolve("responses").resolve(cacheKey.storageId + ".json")
            val tombstone = root.toPath().resolve(".invalidation-poisoned")
            assertTrue(Files.exists(responseFile))

            val failure = runCatching { store.invalidateAll() }.exceptionOrNull()

            assertTrue(failure is java.io.IOException)
            assertTrue("失败注入不删除文件，用于证明异常不得伪装成已清理", Files.exists(responseFile))
            assertTrue("清理失败必须留下持久 tombstone", Files.exists(tombstone))
            assertNull("清理失败后残留文件不得继续被读作缓存", store.read(cacheKey))
            val blockedKey = key(marker = "blocked-write")
            store.write(blockedKey, envelope("req-blocked"), 12L)
            assertNull("清理失败后在重试成功前禁止写入新快照", store.read(blockedKey))
            assertEquals(1, store.countEntries())

            val replacement = FileCommunityCacheStore(root)
            assertNull("tombstone 必须跨实例/进程重启语义阻断残留读取", replacement.read(cacheKey))

            failDeletion = false
            store.invalidateAll()
            assertFalse(Files.exists(tombstone))
            assertEquals(0, store.countEntries())
            store.write(cacheKey, envelope("req-after-retry"), 13L)
            assertEquals("req-after-retry", store.read(cacheKey)?.envelope?.requestId)
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
