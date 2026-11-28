package cn.edu.haut.campus.core.community

import cn.edu.haut.campus.core.model.Staleness
import cn.edu.haut.campus.core.model.json.Json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CommunityClientTest {
    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(1_764_300_000_000L), ZoneOffset.UTC)

    private fun client(
        transport: CommunityTransport,
        cacheStore: CommunityCacheStore? = null,
        featureGate: CommunityFeatureGate = CommunityFeatureGate.ENABLED,
        token: String? = "synthetic-access-token",
    ): CommunityClient = CommunityClient(
        baseUrl = "https://cloud.example.test",
        scope = communityTestScope(),
        accessTokenProvider = { token },
        featureGate = featureGate,
        cacheStore = cacheStore,
        transport = transport,
        clock = fixedClock,
    )

    @Test
    fun createGroupBuildsContractRequestWithBearerAndIdempotencyHeaders() = runTest {
        val transport = RecordingCommunityTransport(jsonResponse(successBody("{}")))
        val result = client(transport).createGroup(
            CreateGroupRequest(
                schoolId = "haut",
                type = "study",
                name = "编译原理互助组",
                description = "只讨论课程学习",
            ),
            idempotencyKey = "idem-create-1",
        )

        assertTrue(result is CommunityResult.Success)
        assertEquals(1, transport.requests.size)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://cloud.example.test/api/v1/groups", request.url)
        assertEquals("Bearer synthetic-access-token", request.headers["Authorization"])
        assertEquals("idem-create-1", request.headers["Idempotency-Key"])
        val body = requireNotNull(Json.parse(requireNotNull(request.body)).asObjOrNull)
        assertEquals("haut", body.str("school_id"))
        assertEquals("study", body.str("type"))
        assertEquals("编译原理互助组", body.str("name"))
        assertEquals("只讨论课程学习", body.str("description"))
    }

    @Test
    fun groupAndMemberValidatorsUseExactContractEnumValues() {
        assertTrue(CreateGroupRequest("haut", "study", "学习小组").toJson().fields.isNotEmpty())
        assertTrue(CreateGroupRequest("haut", "dorm", "宿舍").toJson().fields.isNotEmpty())
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CreateGroupRequest("haut", "STUDY", "错误大小写")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UpdateGroupMemberRequest(role = "GROUP_OWNER")
        }
        assertEquals("OWNER", UpdateGroupMemberRequest(role = "OWNER").toJson().str("role"))
        assertEquals("BUSY_BLOCKS_30MIN", UpdateGroupMemberRequest(sharingLevel = "BUSY_BLOCKS_30MIN").toJson().str("sharing_level"))
    }

    @Test
    fun updateAndRemoveMemberUseEncodedPathAndStableIdempotencyHeader() = runTest {
        val transport = RecordingCommunityTransport(
            jsonResponse(successBody("{}")),
            jsonResponse(successBody("{}")),
        )
        val subject = client(transport)
        val groupId = "11111111-1111-4111-8111-111111111111"
        val memberId = "22222222-2222-4222-8222-222222222222"

        val update = subject.updateGroupMember(
            groupId = groupId,
            memberId = memberId,
            request = UpdateGroupMemberRequest(role = "ADMIN", sharingLevel = "NONE"),
            idempotencyKey = "idem-member-1",
        )
        val remove = subject.removeGroupMember(
            groupId = groupId,
            memberId = memberId,
            idempotencyKey = "idem-remove-1",
        )

        assertTrue(update is CommunityResult.Success)
        assertTrue(remove is CommunityResult.Success)
        val expectedPath = "/api/v1/groups/" + groupId + "/members/" + memberId
        assertTrue(transport.requests[0].url.endsWith(expectedPath))
        assertEquals("PATCH", transport.requests[0].method)
        assertEquals("idem-member-1", transport.requests[0].headers["Idempotency-Key"])
        assertTrue(transport.requests[1].url.endsWith(expectedPath))
        assertEquals("DELETE", transport.requests[1].method)
        assertEquals("idem-remove-1", transport.requests[1].headers["Idempotency-Key"])
    }

    @Test
    fun offlineListGroupsFallsBackToExplicitlyStaleCache() = runTest {
        val cache = InMemoryCommunityCacheStore()
        val listBody = successBody("[1]", requestId = "req-list-1")
        val transport = RecordingCommunityTransport(jsonResponse(listBody), networkFailure())
        val subject = client(transport, cacheStore = cache)

        val fresh = subject.listGroups("haut")
        assertTrue(fresh is CommunityResult.Success)
        assertEquals(Staleness.FRESH, (fresh as CommunityResult.Success).staleness)
        assertEquals(1, cache.countEntries())

        val offline = subject.listGroups("haut")
        assertTrue(offline is CommunityResult.Success)
        val stale = offline as CommunityResult.Success
        assertEquals(Staleness.STALE, stale.staleness)
        assertEquals(fixedClock.millis(), stale.cachedAtEpochMillis)
        assertEquals(CommunityCacheFallbackReason.NETWORK_UNAVAILABLE, stale.cacheFallbackReason)
        assertEquals("cloud-in-school", stale.provenance.providerId)
        assertEquals("acct_community_test", stale.provenance.accountId.value)
        assertEquals("haut", stale.provenance.schoolId.value)
        assertNotNull(stale.value.data?.asArrOrNull)
    }

    @Test
    fun serverFailureUsesCacheOnlyForReadOperations() = runTest {
        val cache = InMemoryCommunityCacheStore()
        val transport = RecordingCommunityTransport(
            jsonResponse(successBody("[]", requestId = "req-list-2")),
            jsonResponse(errorBody("STORAGE_UNAVAILABLE", retryable = true), status = 503),
            jsonResponse(errorBody("STORAGE_UNAVAILABLE", retryable = true), status = 503),
        )
        val subject = client(transport, cacheStore = cache)

        assertTrue(subject.listGroups("haut") is CommunityResult.Success)
        val cachedRead = subject.listGroups("haut")
        assertTrue(cachedRead is CommunityResult.Success)
        assertEquals(Staleness.STALE, (cachedRead as CommunityResult.Success).staleness)
        assertEquals(CommunityCacheFallbackReason.SERVER_UNAVAILABLE, cachedRead.cacheFallbackReason)

        val write = subject.createGroup(
            CreateGroupRequest("haut", "project", "项目组"),
            idempotencyKey = "idem-create-2",
        )
        assertTrue(write is CommunityResult.Failure)
        assertEquals("STORAGE_UNAVAILABLE", (write as CommunityResult.Failure).error.code)
    }

    @Test
    fun successfulWriteInvalidatesReadSnapshotsConservatively() = runTest {
        val cache = InMemoryCommunityCacheStore()
        val transport = RecordingCommunityTransport(
            jsonResponse(successBody("[]", requestId = "req-list-3")),
            jsonResponse(successBody("{}", requestId = "req-create-3"), status = 201),
        )
        val subject = client(transport, cacheStore = cache)

        assertTrue(subject.listGroups("haut") is CommunityResult.Success)
        assertEquals(1, cache.countEntries())
        assertTrue(
            subject.createGroup(
                CreateGroupRequest("haut", "club", "社团组"),
                idempotencyKey = "idem-create-3",
            ) is CommunityResult.Success,
        )
        assertEquals(0, cache.countEntries())
    }

    @Test
    fun disabledFeatureAndMissingTokenDoNotTouchTransportOrCache() = runTest {
        val transport = RecordingCommunityTransport()
        val cache = InMemoryCommunityCacheStore()
        val disabled = client(
            transport,
            cacheStore = cache,
            featureGate = CommunityFeatureGate.disabled("群组云在测试配置中关闭"),
        ).listGroups("haut")
        assertTrue(disabled is CommunityResult.Disabled)
        assertEquals(CommunityFeature.GROUPS, (disabled as CommunityResult.Disabled).feature)

        val unauthenticated = client(transport, cacheStore = cache, token = null).listGroups("haut")
        assertEquals(CommunityResult.AuthenticationRequired, unauthenticated)
        assertEquals(0, transport.requests.size)
        assertEquals(0, cache.countEntries())
    }

    @Test
    fun malformedSuccessEnvelopeIsContractFailureAndNeverCached() = runTest {
        val cache = InMemoryCommunityCacheStore()
        val transport = RecordingCommunityTransport(jsonResponse("{}"))
        val result = client(transport, cacheStore = cache).listGroups("haut")

        assertTrue(result is CommunityResult.Failure)
        assertEquals("CONTRACT_RESPONSE_INVALID", (result as CommunityResult.Failure).error.code)
        assertEquals(0, cache.countEntries())
    }

    @Test
    fun httpErrorEnvelopeParsesContractCodeAndRetryableFlag() = runTest {
        val transport = RecordingCommunityTransport(
            jsonResponse(errorBody("GROUP_PERMISSION_DENIED", retryable = false), status = 403),
        )
        val result = client(transport).listGroups("haut")

        assertTrue(result is CommunityResult.Failure)
        val error = (result as CommunityResult.Failure).error
        assertEquals("GROUP_PERMISSION_DENIED", error.code)
        assertEquals(403, error.statusCode)
        assertFalse(error.retryable)
    }

    @Test
    fun idempotencyKeyMustMatchContractLengthBounds() = runTest {
        val transport = RecordingCommunityTransport()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                client(transport).createGroup(CreateGroupRequest("haut", "study", "学习"), idempotencyKey = "short")
            }
        }
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun nonHttpsBaseUrlIsRejectedExceptLoopbackTestTransport() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CommunityClient(
                baseUrl = "http://cloud.example.test",
                scope = communityTestScope(),
                accessTokenProvider = { "token" },
                featureGate = CommunityFeatureGate.ENABLED,
                transport = RecordingCommunityTransport(),
            )
        }
        CommunityClient(
            baseUrl = "http://127.0.0.1:8080",
            scope = communityTestScope(),
            accessTokenProvider = { "token" },
            featureGate = CommunityFeatureGate.ENABLED,
            transport = RecordingCommunityTransport(),
        )
    }
}
