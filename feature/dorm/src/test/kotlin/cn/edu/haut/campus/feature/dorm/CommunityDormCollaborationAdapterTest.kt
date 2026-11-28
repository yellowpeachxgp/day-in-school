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

    private fun envelope(dataJson: String): String {
        val data = Json.parse(dataJson)
        return Json.canonical(
            obj(
                "ok" to JsonValue.Bool(true),
                "code" to str("OK"),
                "request_id" to str("req"),
                "data" to data,
                "meta" to obj(
                    "request_id" to str("req"),
                    "api_version" to str("v1"),
                    "next_cursor" to JsonValue.Null,
                ),
            ),
        )
    }

    private fun jsonMembers(): String = Json.canonical(
        JsonValue.Arr(
            listOf(member1, member2, member3).map { id ->
                obj(
                    "member_id" to str(id),
                    "group_id" to str(groupId),
                    "school_id" to str("haut"),
                    "cloud_user_id" to str("10000000-0000-4000-8000-000000000001"),
                    "display_name" to str("合成成员"),
                    "role" to str("MEMBER"),
                    "sharing_level" to str("NONE"),
                    "status" to str("ACTIVE"),
                    "joined_at" to str("2026-11-01T00:00:00Z"),
                    "left_at" to JsonValue.Null,
                )
            },
        ),
    )

    private fun jsonLedgerEntry(shares: List<Long>): String {
        val shareRows = listOf(member1, member2, member3).mapIndexed { index, id ->
            obj(
                "participant_member_id" to str(id),
                "amount_fen" to num(shares[index]),
            )
        }
        return Json.canonical(
            obj(
                "entry_id" to str("20000000-0000-4000-8000-000000000001"),
                "group_id" to str(groupId),
                "school_id" to str("haut"),
                "entry_kind" to str("EXPENSE"),
                "corrects_entry_id" to JsonValue.Null,
                "correction_reason" to JsonValue.Null,
                "title" to str("公共洗衣液"),
                "note" to JsonValue.Null,
                "amount_fen" to num(1000L),
                "paid_by_member_id" to str(member1),
                "occurred_at" to str("2026-11-20T12:00:00Z"),
                "split_rule" to obj("type" to str("EQUAL")),
                "shares" to JsonValue.Arr(shareRows),
                "created_by_member_id" to str(member1),
                "created_at" to str("2026-11-20T12:00:01Z"),
            ),
        )
    }

    private fun str(value: String): JsonValue.Str = JsonValue.Str(value)
    private fun num(value: Long): JsonValue.Num = JsonValue.Num(value.toString())
    private fun obj(vararg fields: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(linkedMapOf(*fields))
}
