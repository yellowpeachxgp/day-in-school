package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.community.CommunityCacheKey
import cn.edu.haut.campus.core.community.CommunityEnvelope
import cn.edu.haut.campus.core.community.FileCommunityCacheStore
import cn.edu.haut.campus.core.model.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** W4-05/C27: community cache must be registered in the profile erasure chain and finish at zero. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CommunityCacheWipeTest {
    private lateinit var fixture: RepairTestFixture

    @Before
    fun setUp() {
        fixture = RepairTestFixture.create(RuntimeEnvironment.getApplication(), "prof_community_wipe")
    }

    @After
    fun tearDown() = fixture.close()

    @Test
    fun wipeLeavesCurrentProfileCommunityCacheAtZeroAndDoesNotCrossProfiles() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val currentRoot = CommunityCacheWipeRegistration.rootForProfile(context.filesDir, fixture.profile.id)
        val otherRoot = CommunityCacheWipeRegistration.rootForProfile(context.filesDir, "prof_community_other")
        val currentStore = FileCommunityCacheStore(currentRoot)
        val otherStore = FileCommunityCacheStore(otherRoot)
        try {
            currentStore.write(cacheKey("current"), envelope("req-current"), cachedAtEpochMillis = 10L)
            otherStore.write(cacheKey("other"), envelope("req-other"), cachedAtEpochMillis = 11L)
            assertEquals(1, currentStore.countEntries())
            assertEquals(1, otherStore.countEntries())

            fixture.repository.wipeAllPersonalData()

            assertEquals(0, currentStore.countEntries())
            assertFalse(currentRoot.exists())
            assertEquals("other profile cache must remain isolated from this profile wipe", 1, otherStore.countEntries())
        } finally {
            currentStore.wipe()
            otherStore.wipe()
        }
    }

    private fun cacheKey(marker: String): CommunityCacheKey = CommunityCacheKey(
        operationId = "listGroups",
        resolvedPath = "/api/v1/groups",
        queryParams = mapOf("school_id" to listOf(marker)),
    )

    private fun envelope(requestId: String): CommunityEnvelope {
        val meta = JsonValue.Obj(
            linkedMapOf(
                "request_id" to JsonValue.Str(requestId),
                "api_version" to JsonValue.Str("v1"),
            ),
        )
        val data = JsonValue.Arr(emptyList())
        val raw = JsonValue.Obj(
            linkedMapOf(
                "ok" to JsonValue.Bool(true),
                "code" to JsonValue.Str("OK"),
                "request_id" to JsonValue.Str(requestId),
                "data" to data,
                "meta" to meta,
            ),
        )
        return CommunityEnvelope(
            ok = true,
            code = "OK",
            requestId = requestId,
            data = data,
            meta = meta,
            raw = raw,
        )
    }
}
