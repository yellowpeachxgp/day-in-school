package cn.edu.haut.campus.feature.dorm

import cn.edu.haut.campus.core.community.CommunityClient
import cn.edu.haut.campus.core.community.CommunityFeatureGate
import cn.edu.haut.campus.core.community.CommunityHttpRequest
import cn.edu.haut.campus.core.community.CommunityHttpResponse
import cn.edu.haut.campus.core.community.CommunitySessionSnapshot
import cn.edu.haut.campus.core.community.CommunityTransport
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.feature.dorm.data.CommunityDormCollaborationAdapter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CommunityDormCollaborationAdapterTest {
    private val groupId = "00000000-0000-4000-8000-000000000101"
    private val member1 = "00000000-0000-4000-8000-000000000001"
    private val member2 = "00000000-0000-4000-8000-000000000002"
    private val member3 = "00000000-0000-4000-8000-000000000003"

    @Test
    fun createLedgerEntryUsesCoreLedgerLargestRemainderAndValidatesCloudShares() = runTest {
        val transport = QueueTransport(
            CommunityHttpResponse(200, envelope(jsonMembers())),
            CommunityHttpResponse(200, envelope(jsonLedgerEntry(shares = listOf(334L, 333L, 333L)))),
        )
        val adapter = CommunityDormCollaborationAdapter(client(transport))
        val input = DormLedgerEntryInput(
            title = "公共洗衣液",
            amountFen = 1000L,
            paidByMemberId = member1,
            occurredAt = "2026-11-20T12:00:00Z",
            splitRule = DormSplitRule(DormSplitRuleType.EQUAL),
        )

        val entry = adapter.createLedgerEntry(groupId, input, "test-idempotency-key")

        assertEquals(1000L, entry.amountFen)
        assertEquals(listOf(member1, member2, member3), entry.shares.map { it.participantMemberId })
        assertEquals(listOf(334L, 333L, 333L), entry.shares.map { it.amountFen })
        assertEquals(2, transport.requests.size)
        val create = transport.requests[1]
        assertEquals("POST", create.method)
        assertTrue(create.url.contains("/api/v1/groups/" + groupId + "/ledger/entries"))
        assertEquals("test-idempotency-key", create.headers["Idempotency-Key"])
        val body = Json.parse(create.body.orEmpty()).asObjOrNull ?: error("request body must be object")
        assertEquals("1000", (body.fields.getValue("amount_fen") as JsonValue.Num).literal)
        assertEquals("EQUAL", body.obj("split_rule")?.str("type"))
    }

    @Test
    fun createLedgerEntryFailsClosedWhenCloudShareSumDoesNotConserve() = runTest {
        val transport = QueueTransport(
            CommunityHttpResponse(200, envelope(jsonMembers())),
            CommunityHttpResponse(200, envelope(jsonLedgerEntry(shares = listOf(334L, 333L, 332L)))),
        )
        val adapter = CommunityDormCollaborationAdapter(client(transport))
        val input = DormLedgerEntryInput(
            title = "公共洗衣液",
            amountFen = 1000L,
            paidByMemberId = member1,
            occurredAt = "2026-11-20T12:00:00Z",
            splitRule = DormSplitRule(DormSplitRuleType.EQUAL),
        )

        try {
            adapter.createLedgerEntry(groupId, input, "test-idempotency-key-2")
            fail("cloud share sum violation must fail closed")
        } catch (expected: DormCollaborationException) {
            assertTrue(expected.message.orEmpty().contains("守恒") || expected.message.orEmpty().contains("不一致"))
        }
    }

    private fun client(transport: CommunityTransport): CommunityClient = CommunityClient(
        baseUrl = "https://campus.example.test",
        scope = SchoolAccountScope(
            schoolId = SchoolId("haut"),
            accountId = AccountId("synthetic-account"),
            profileId = ProfileId("synthetic-profile"),
        ),
        sessionSnapshotProvider = { CommunitySessionSnapshot("synthetic-token", null) },
        featureGate = CommunityFeatureGate.ENABLED,
        cacheStore = null,
        transport = transport,
    )

    private class QueueTransport(vararg responses: CommunityHttpResponse) : CommunityTransport {
        val requests = mutableListOf<CommunityHttpRequest>()
        private val queue = ArrayDeque(responses.toList())
        override suspend fun execute(request: CommunityHttpRequest): CommunityHttpResponse {
            requests += request
            return queue.removeFirstOrNull() ?: error("unexpected request: " + request.method + " " + request.url)
        }
    }

    private fun envelope(dataJson: String): String =
        """{"ok":true,"code":"OK","request_id":"req","data":""" + dataJson + ""","meta":{"request_id":"req","api_version":"v1","next_cursor":null}}"""

    private fun jsonMembers(): String = "[" + listOf(member1, member2, member3).joinToString(",") { id ->
        """{"member_id":""" + id + ""","group_id":""" + groupId + ""","school_id":"haut","cloud_user_id":"10000000-0000-4000-8000-000000000001","display_name":"合成成员","role":"MEMBER","sharing_level":"NONE","status":"ACTIVE","joined_at":"2026-11-01T00:00:00Z","left_at":null}"""
    } + "]"

    private fun jsonLedgerEntry(shares: List<Long>): String {
        val shareJson = listOf(member1, member2, member3).mapIndexed { index, id ->
            """{"participant_member_id":""" + id + ""","amount_fen":""" + shares[index] + """}"""
        }.joinToString(",")
        return """{"entry_id":"20000000-0000-4000-8000-000000000001","group_id":""" + groupId + ""","school_id":"haut","entry_kind":"EXPENSE","corrects_entry_id":null,"correction_reason":null,"title":"公共洗衣液","note":null,"amount_fen":1000,"paid_by_member_id":""" + member1 + ""","occurred_at":"2026-11-20T12:00:00Z","split_rule":{"type":"EQUAL"},"shares":[""" + shareJson + """],"created_by_member_id":""" + member1 + ""","created_at":"2026-11-20T12:00:01Z"}"""
    }
}
