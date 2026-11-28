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
import java.nio.file.Files

class CommunityClientTest {
    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(1_764_300_000_000L), ZoneOffset.UTC)

    private fun client(
        transport: CommunityTransport,
        cacheStore: CommunityCacheStore? = null,
        featureGate: CommunityFeatureGate = CommunityFeatureGate.ENABLED,
        token: String? = "synthetic-access-token",
        cacheIdentityProvider: suspend () -> CommunityCacheIdentity? = { communityTestCacheIdentity() },
    ): CommunityClient = CommunityClient(
        baseUrl = "https://cloud.example.test",
        scope = communityTestScope(),
        accessTokenProvider = { token },
        featureGate = featureGate,
        cacheStore = cacheStore,
        cacheIdentityProvider = cacheIdentityProvider,
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
        // AUTO-030 类型防线：成员管理类型根本不存在 sharingLevel 属性/访问器，序列化也绝不出现 sharing_level。
        assertFalse(UpdateGroupMemberRequest::class.java.declaredFields.any { it.name.equals("sharingLevel", ignoreCase = true) })
        assertFalse(UpdateGroupMemberRequest::class.java.methods.any { it.name == "getSharingLevel" })
        assertFalse(UpdateGroupMemberRequest(role = "ADMIN").toJson().fields.containsKey("sharing_level"))
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
            request = UpdateGroupMemberRequest(role = "ADMIN"),
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
    fun sharingLevelSelfServiceUsesOnlyAvailabilityMeEndpointAndNeverMemberManagementBody() = runTest {
        val transport = RecordingCommunityTransport(
            jsonResponse(successBody("{}", requestId = "req-share-self")),
            jsonResponse(successBody("{}", requestId = "req-withdraw-self")),
        )
        val subject = client(transport)
        val groupId = "11111111-1111-4111-8111-111111111111"

        val share = subject.replaceMyGroupAvailability(
            groupId = groupId,
            request = ReplaceGroupAvailabilityRequest(
                blocks = listOf(AvailabilityBlockInput("2026-11-28T08:00:00Z", "2026-11-28T09:00:00Z")),
                ttlSeconds = 1800,
            ),
            idempotencyKey = "idem-share-self",
        )
        val withdraw = subject.deleteMyGroupAvailability(
            groupId = groupId,
            idempotencyKey = "idem-withdraw-self",
        )

        assertTrue(share is CommunityResult.Success)
        assertTrue(withdraw is CommunityResult.Success)
        assertEquals("PUT", transport.requests[0].method)
        assertTrue(transport.requests[0].url.endsWith("/api/v1/groups/" + groupId + "/availability/me"))
        val shareBody = requireNotNull(Json.parse(requireNotNull(transport.requests[0].body)).asObjOrNull)
        assertFalse(shareBody.fields.containsKey("member_id"))
        assertFalse(shareBody.fields.containsKey("sharing_level"))
        assertEquals("DELETE", transport.requests[1].method)
        assertTrue(transport.requests[1].url.endsWith("/api/v1/groups/" + groupId + "/availability/me"))
        assertNull(transport.requests[1].body)
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
    fun successfulUncachedReadDoesNotInvalidateGroupMetadataSnapshots() = runTest {
        val cache = InMemoryCommunityCacheStore()
        val transport = RecordingCommunityTransport(
            jsonResponse(successBody("[]", requestId = "req-list-before-read")),
            jsonResponse(successBody("{}", requestId = "req-ledger-read")),
            networkFailure(),
        )
        val subject = client(transport, cacheStore = cache)
        val groupId = "11111111-1111-4111-8111-111111111111"

        assertTrue(subject.listGroups("haut") is CommunityResult.Success)
        assertEquals(1, cache.countEntries())
        assertTrue(subject.getLedgerBalances(groupId) is CommunityResult.Success)
        assertEquals("纯 GET 成功不得误清群组快照", 1, cache.countEntries())

        val offline = subject.listGroups("haut")
        assertTrue(offline is CommunityResult.Success)
        assertEquals(Staleness.STALE, (offline as CommunityResult.Success).staleness)
    }

    @Test
    fun failedMutationStillInvalidatesSnapshotsBecauseServerCommitMayBeUnknown() = runTest {
        val cache = InMemoryCommunityCacheStore()
        val transport = RecordingCommunityTransport(
            jsonResponse(successBody("[]", requestId = "req-list-before-write")),
            networkFailure(),
        )
        val subject = client(transport, cacheStore = cache)
        assertTrue(subject.listGroups("haut") is CommunityResult.Success)
        assertEquals(1, cache.countEntries())

        val write = subject.createGroup(
            CreateGroupRequest("haut", "study", "可能未知提交"),
            idempotencyKey = "idem-create-unknown",
        )

        assertTrue(write is CommunityResult.Failure)
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
    fun authoritativeForbiddenOrNotFoundInvalidatesSnapshotsBeforeAnyLaterFallback() = runTest {
        for ((status, code) in listOf(403 to "GROUP_PERMISSION_DENIED", 404 to "GROUP_NOT_FOUND")) {
            val cache = InMemoryCommunityCacheStore()
            val transport = RecordingCommunityTransport(
                jsonResponse(successBody("[]", requestId = "req-list-authoritative-" + status)),
                jsonResponse(errorBody(code, retryable = false), status = status),
                networkFailure(),
            )
            val subject = client(transport, cacheStore = cache)

            assertTrue(subject.listGroups("haut") is CommunityResult.Success)
            assertEquals(1, cache.countEntries())
            val authoritative = subject.listGroups("haut")
            assertTrue(authoritative is CommunityResult.Failure)
            assertEquals(code, (authoritative as CommunityResult.Failure).error.code)
            assertEquals("HTTP " + status + " 权威失败必须清理群组快照", 0, cache.countEntries())

            val offline = subject.listGroups("haut")
            assertTrue(offline is CommunityResult.Failure)
            assertEquals("NETWORK_UNAVAILABLE", (offline as CommunityResult.Failure).error.code)
        }
    }

    @Test
    fun switchedCloudIdentityOrGenerationCannotReadPreviousGroupSnapshot() = runTest {
        val cloudUserA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val cloudUserB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        var identity = communityTestCacheIdentity(cloudUserId = cloudUserA, identityGeneration = 1L)
        val cache = InMemoryCommunityCacheStore()
        val transport = RecordingCommunityTransport(
            jsonResponse(successBody("[]", requestId = "req-list-identity-a")),
            networkFailure(),
            networkFailure(),
        )
        val subject = client(
            transport,
            cacheStore = cache,
            cacheIdentityProvider = { identity },
        )

        assertTrue(subject.listGroups("haut") is CommunityResult.Success)
        assertEquals(1, cache.countEntries())

        identity = communityTestCacheIdentity(cloudUserId = cloudUserB, identityGeneration = 2L)
        val afterSwitch = subject.listGroups("haut")
        assertTrue(afterSwitch is CommunityResult.Failure)
        assertEquals("NETWORK_UNAVAILABLE", (afterSwitch as CommunityResult.Failure).error.code)

        identity = communityTestCacheIdentity(cloudUserId = cloudUserA, identityGeneration = 3L)
        val afterAbaRelogin = subject.listGroups("haut")
        assertTrue(afterAbaRelogin is CommunityResult.Failure)
        assertEquals("NETWORK_UNAVAILABLE", (afterAbaRelogin as CommunityResult.Failure).error.code)
    }

    @Test
    fun cacheStoreRequiresExplicitCloudIdentityProvider() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CommunityClient(
                baseUrl = "https://cloud.example.test",
                scope = communityTestScope(),
                accessTokenProvider = { "token" },
                featureGate = CommunityFeatureGate.ENABLED,
                cacheStore = InMemoryCommunityCacheStore(),
                transport = RecordingCommunityTransport(),
            )
        }
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
        assertEquals("RETRY_LATER", error.userAction)
        assertEquals("synthetic", error.detail?.str("reason"))
    }

    @Test
    fun factoryAssemblesProfileIsolatedFileBackedClientWithStaleOfflineFallback() = runTest {
        val filesDir = Files.createTempDirectory("community-client-factory-test").toFile()
        try {
            val transport = RecordingCommunityTransport(
                jsonResponse(successBody("[]", requestId = "req-factory")),
                networkFailure(),
            )
            val assembly = CommunityClientFactory.createFileBacked(
                filesDir = filesDir,
                baseUrl = "https://cloud.example.test",
                scope = communityTestScope(),
                accessTokenProvider = { "synthetic-access-token" },
                cacheIdentityProvider = { communityTestCacheIdentity() },
                featureGate = CommunityFeatureGate.ENABLED,
                transport = transport,
                clock = fixedClock,
            )

            assertTrue(assembly.client.listGroups("haut") is CommunityResult.Success)
            assertEquals(1, assembly.cacheStore.countEntries())
            val offline = assembly.client.listGroups("haut")
            assertTrue(offline is CommunityResult.Success)
            assertEquals(Staleness.STALE, (offline as CommunityResult.Success).staleness)
        } finally {
            filesDir.deleteRecursively()
        }
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
