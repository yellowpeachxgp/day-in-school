package cn.edu.haut.campus.core.community

import cn.edu.haut.campus.core.model.Staleness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CommunityClientDomainWireTest {
    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(1_764_300_000_000L), ZoneOffset.UTC)
    private val groupId = "11111111-1111-4111-8111-111111111111"
    private val memberOne = "22222222-2222-4222-8222-222222222222"
    private val memberTwo = "33333333-3333-4333-8333-333333333333"

    private fun client(
        transport: CommunityTransport,
        cacheStore: CommunityCacheStore? = null,
    ): CommunityClient = CommunityClient(
        baseUrl = "https://cloud.example.test",
        scope = communityTestScope(),
        accessTokenProvider = { "synthetic-access-token" },
        featureGate = CommunityFeatureGate.ENABLED,
        cacheStore = cacheStore,
        transport = transport,
        clock = fixedClock,
    )

    @Test
    fun availabilityQueryUsesContractBodyButIsNotRetainedAsOfflineCache() = runTest {
        val cache = InMemoryCommunityCacheStore()
        val transport = RecordingCommunityTransport(
            jsonResponse(successBody("{}")),
        )
        val subject = client(transport, cache)
        val result = subject.queryGroupAvailability(
            groupId = groupId,
            request = GroupAvailabilityQueryRequest(
                windowStartAt = "2026-11-28T08:00:00Z",
                windowEndAt = "2026-11-28T10:00:00Z",
                durationMinutes = 60,
                minAttendees = 2,
                memberIds = listOf(memberOne, memberTwo),
                granularityMinutes = 60,
            ),
            idempotencyKey = "idem-avail-1",
        )

        assertTrue(result is CommunityResult.Success)
        assertEquals(0, cache.countEntries())
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://cloud.example.test/api/v1/groups/" + groupId + "/availability/query", request.url)
        assertEquals("idem-avail-1", request.headers["Idempotency-Key"])
    }

    @Test
    fun availabilityLedgerAndPlaceReadsFailExplicitlyOfflineWithoutPrivacyUnsafeCache() = runTest {
        for (operationCall in listOf<suspend (CommunityClient) -> CommunityResult<CommunityEnvelope>>(
            { subject ->
                subject.queryGroupAvailability(
                    groupId,
                    GroupAvailabilityQueryRequest(
                        windowStartAt = "2026-11-28T08:00:00Z",
                        windowEndAt = "2026-11-28T10:00:00Z",
                        durationMinutes = 30,
                        minAttendees = 1,
                    ),
                    "idem-avail-2",
                )
            },
            { subject -> subject.getLedgerBalances(groupId) },
            { subject -> subject.queryPlaceStatus("haut", placeRefs = listOf("haut/lianhuajie/room/5-111")) },
        )) {
            val cache = InMemoryCommunityCacheStore()
            val transport = RecordingCommunityTransport(networkFailure())
            val result = operationCall(client(transport, cache))
            assertTrue(result is CommunityResult.Failure)
            assertEquals("NETWORK_UNAVAILABLE", (result as CommunityResult.Failure).error.code)
            assertEquals(0, cache.countEntries())
        }
    }

    @Test
    fun memberSnapshotRemainsWithinPrivacyApprovedOfflineCacheWhitelist() = runTest {
        val cache = InMemoryCommunityCacheStore()
        val transport = RecordingCommunityTransport(
            jsonResponse(successBody("[]", requestId = "req-members")),
            networkFailure(),
        )
        val subject = client(transport, cache)
        assertTrue(subject.listGroupMembers(groupId) is CommunityResult.Success)
        assertEquals(1, cache.countEntries())

        val offline = subject.listGroupMembers(groupId)
        assertTrue(offline is CommunityResult.Success)
        assertEquals(Staleness.STALE, (offline as CommunityResult.Success).staleness)
    }

    @Test
    fun ledgerEntrySerializesIntegerFenAndRatioSplitContract() = runTest {
        val transport = RecordingCommunityTransport(jsonResponse(successBody("{}", requestId = "req-ledger"), status = 201))
        val result = client(transport).createLedgerEntry(
            groupId = groupId,
            request = CreateLedgerEntryRequest(
                title = "宿舍电费",
                amountFen = 1234L,
                paidByMemberId = memberOne,
                occurredAt = "2026-11-28T10:00:00Z",
                splitRule = LedgerSplitRuleInput(
                    type = "RATIOS",
                    ratioWeights = listOf(
                        LedgerRatioWeightInput(memberOne, 1L),
                        LedgerRatioWeightInput(memberTwo, 3L),
                    ),
                ),
                note = "11 月公摊",
            ),
            idempotencyKey = "idem-ledger-1",
        )

        assertTrue(result is CommunityResult.Success)
        val body = cn.edu.haut.campus.core.model.json.Json.parse(requireNotNull(transport.requests.single().body)).asObjOrNull
        assertEquals("宿舍电费", body?.str("title"))
        assertEquals("1234", body?.obj("amount_fen")?.let { (it as cn.edu.haut.campus.core.model.json.JsonValue.Num).literal })
        assertEquals(memberOne, body?.str("paid_by_member_id"))
        val split = body?.obj("split_rule")?.asObjOrNull
        assertEquals("RATIOS", split?.str("type"))
    }

    @Test
    fun placeStatusQueryBuildsCommaSeparatedExplodeFalseQueryAndValidatesExactlyOneScope() = runTest {
        val transport = RecordingCommunityTransport(jsonResponse(successBody("[]", requestId = "req-place")))
        val result = client(transport).queryPlaceStatus(
            schoolId = "haut",
            placeRefs = listOf("haut/lianhuajie/room/5-111"),
            placeTypes = listOf("room", "facility"),
            metrics = listOf("OCCUPANCY", "ACCESS"),
            includeExpired = true,
            limit = 10,
        )

        assertTrue(result is CommunityResult.Success)
        val url = transport.requests.single().url
        assertTrue(url.startsWith("https://cloud.example.test/api/v1/places/status?"))
        assertTrue(url.contains("school_id=haut"))
        assertTrue(url.contains("place_refs=haut%2Flianhuajie%2Froom%2F5-111"))
        assertTrue(url.contains("place_types=room,facility"))
        assertTrue(url.contains("metrics=OCCUPANCY,ACCESS"))
        assertTrue(url.contains("include_expired=true"))
        assertTrue(url.contains("limit=10"))

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                client(RecordingCommunityTransport()).queryPlaceStatus(
                    schoolId = "haut",
                    placeRefs = listOf("haut/lianhuajie/room/5-111"),
                    bbox = "34.820,113.540,34.850,113.570",
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                client(RecordingCommunityTransport()).queryPlaceStatus(schoolId = "haut")
            }
        }
    }

    @Test
    fun placeReportPathPercentEncodesStablePlaceRefAsOnePathParameter() = runTest {
        val transport = RecordingCommunityTransport(jsonResponse(successBody("{}", requestId = "req-report"), status = 201))
        val result = client(transport).createPlaceStatusReport(
            placeRef = "haut/lianhuajie/room/5-111",
            request = CreatePlaceStatusReportRequest(
                metric = "OCCUPANCY",
                state = "CROWDED",
                observedAt = "2026-11-28T10:00:00Z",
                reportKind = "OBSERVATION",
                note = "座位接近满员",
            ),
            idempotencyKey = "idem-place-1",
        )

        assertTrue(result is CommunityResult.Success)
        assertEquals(
            "https://cloud.example.test/api/v1/places/haut%2Flianhuajie%2Froom%2F5-111/reports",
            transport.requests.single().url,
        )
    }

    @Test
    fun expiredCloudCredentialMapsToAuthenticationRequired() = runTest {
        val transport = RecordingCommunityTransport(jsonResponse(errorBody("CLOUD_AUTH_REQUIRED", retryable = false), status = 401))
        val result = client(transport).listGroups("haut")
        assertEquals(CommunityResult.AuthenticationRequired, result)
    }

    @Test
    fun invalidUuidAndInvalidEnumFailBeforeTransport() = runTest {
        val transport = RecordingCommunityTransport()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { client(transport).getLedgerBalances("not-a-uuid") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { client(transport).listGroups("haut", type = "STUDY") }
        }
        assertEquals(0, transport.requests.size)
    }
}
