package cn.edu.haut.campus.feature.teamtime

import cn.edu.haut.campus.core.community.CommunityClient
import cn.edu.haut.campus.core.community.CommunityFeatureGate
import cn.edu.haut.campus.core.community.CommunityHttpRequest
import cn.edu.haut.campus.core.community.CommunityHttpResponse
import cn.edu.haut.campus.core.community.CommunityResult
import cn.edu.haut.campus.core.community.CommunitySessionSnapshot
import cn.edu.haut.campus.core.community.CommunityTransport
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.timeline.BusyBlock
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.FreeSlot
import cn.edu.haut.campus.core.timeline.PersonalEventDraft
import cn.edu.haut.campus.core.timeline.PersonalEventStore
import cn.edu.haut.campus.core.timeline.SlotConstraints
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineEvent
import cn.edu.haut.campus.core.timeline.TimelineEventId
import cn.edu.haut.campus.core.timeline.TimelineFilter
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityTeamTimeRepositoryTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct-teamtime-repo"),
        profileId = ProfileId("prof-teamtime-repo"),
        campusId = CampusId("lianhuajie"),
    )
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-28T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun shareUploadsOnlyTimeBlocksAndTtlWithoutEventMetadata() = runTest {
        val transport = RecordingTransport(
            CommunityHttpResponse(200, successBody(shareDataJson())),
        )
        val personalStore = RecordingPersonalEventStore()
        val repository = repository(
            transport = transport,
            personalEventStore = personalStore,
            busyBlocks = listOf(
                BusyBlock(Instant.parse("2026-12-01T10:00:00Z"), Instant.parse("2026-12-01T10:30:00Z"), BusyLevel.BUSY),
            ),
        )

        val result = repository.shareMyBusyBlocks(
            groupId = GROUP_ID,
            window = TimeWindow(Instant.parse("2026-12-01T10:00:00Z"), Instant.parse("2026-12-08T10:00:00Z")),
            ttlSeconds = 604800,
        )

        assertTrue(result is CommunityResult.Success)
        val request = transport.requests.single()
        assertEquals("PUT", request.method)
        assertTrue(request.url.endsWith("/api/v1/groups/" + GROUP_ID + "/availability/me"))
        val body = Json.parse(requireNotNull(request.body)).asObjOrNull
        assertNotNull(body)
        assertEquals(setOf("blocks", "ttl_seconds"), body!!.fields.keys)
        val block = body.obj("blocks")?.asArrOrNull?.items?.single()?.asObjOrNull
        assertNotNull(block)
        assertEquals(setOf("start_at", "end_at"), block!!.fields.keys)
        assertFalse(block.fields.containsKey("title"))
        assertFalse(block.fields.containsKey("note"))
        assertFalse(block.fields.containsKey("location"))
        assertFalse(block.fields.containsKey("event_id"))
    }

    @Test
    fun confirmWritesStableGroupMeetingIntoPersonalTimelineAfterCloudSuccess() = runTest {
        val transport = RecordingTransport(
            CommunityHttpResponse(201, successBody(confirmDataJson())),
        )
        val personalStore = RecordingPersonalEventStore()
        val repository = repository(
            transport = transport,
            personalEventStore = personalStore,
            busyBlocks = emptyList(),
        )

        val result = repository.confirm(POLL_ID, OPTION_ONE_ID)

        assertTrue(result is CommunityResult.Success)
        val success = result as CommunityResult.Success
        assertNull(success.value.timelineWriteError)
        assertEquals("personal:group-meeting-" + EVENT_ID, success.value.timelineEventId?.value)
        val saved = personalStore.upserts.single()
        assertEquals(Actor.USER, saved.second)
        val draft = saved.first
        assertEquals(TimelineKind.GROUP_MEETING, draft.kind)
        assertEquals(TimelineEventId("personal:group-meeting-" + EVENT_ID), draft.id)
        assertEquals(Instant.parse("2026-12-01T10:00:00Z"), draft.start)
        assertEquals(Instant.parse("2026-12-01T11:00:00Z"), draft.end)
        assertEquals(EVENT_ID, draft.provenance.sourceRecordId)
        assertTrue(draft.links.any { it.id == GROUP_ID })
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertTrue(request.url.endsWith("/api/v1/polls/" + POLL_ID + "/confirm"))
        val requestBody = Json.parse(requireNotNull(request.body)).asObjOrNull
        assertEquals(OPTION_ONE_ID, requestBody?.str("option_id"))
    }

    private fun repository(
        transport: RecordingTransport,
        personalEventStore: RecordingPersonalEventStore,
        busyBlocks: List<BusyBlock>,
    ): CommunityTeamTimeRepository {
        val client = CommunityClient(
            baseUrl = "https://cloud.example.test",
            scope = scope,
            sessionSnapshotProvider = { CommunitySessionSnapshot("synthetic-access-token", null) },
            featureGate = CommunityFeatureGate.ENABLED,
            transport = transport,
            clock = clock,
        )
        return CommunityTeamTimeRepository(
            client = client,
            cloudUserIdProvider = { "cloud-user" },
            timelineService = StaticTimelineService(busyBlocks),
            personalEventStore = personalEventStore,
            scope = scope,
            zone = zone,
            clock = clock,
            idempotencyKeyFactory = { "tt-test-fixed" },
        )
    }

    private class RecordingTransport(vararg responses: CommunityHttpResponse) : CommunityTransport {
        private val pending = ArrayDeque(responses.toList())
        val requests = mutableListOf<CommunityHttpRequest>()

        override suspend fun execute(request: CommunityHttpRequest): CommunityHttpResponse {
            requests += request
            return pending.removeFirst()
        }
    }

    private class StaticTimelineService(
        private val blocks: List<BusyBlock>,
    ) : TimelineService {
        override fun observe(scope: SchoolAccountScope, window: TimeWindow, filter: TimelineFilter): Flow<List<TimelineEvent>> =
            flowOf(emptyList())

        override suspend fun busyBlocks(scope: SchoolAccountScope, window: TimeWindow, granularity: Duration): List<BusyBlock> = blocks

        override suspend fun freeSlots(
            scope: SchoolAccountScope,
            window: TimeWindow,
            minLength: Duration,
            constraints: SlotConstraints,
        ): List<FreeSlot> = emptyList()
    }

    private class RecordingPersonalEventStore : PersonalEventStore {
        val upserts = mutableListOf<Pair<PersonalEventDraft, Actor>>()

        override suspend fun upsert(event: PersonalEventDraft, actor: Actor): TimelineEventId {
            upserts += event to actor
            return requireNotNull(event.id)
        }

        override suspend fun delete(id: TimelineEventId, actor: Actor) = Unit
    }

    private fun successBody(dataJson: String): String =
        """
        {"ok":true,"code":"OK","request_id":"req-test","data":%DATA%,"meta":{"request_id":"req-test","api_version":"v1"}}
        """.trimIndent().replace("%DATA%", dataJson)

    private fun shareDataJson(): String =
        """
        {
          "group_id":"$GROUP_ID",
          "school_id":"haut",
          "member_id":"$MEMBER_ID",
          "sharing_level":"BUSY_BLOCKS_30MIN",
          "blocks":[{"start_at":"2026-12-01T10:00:00Z","end_at":"2026-12-01T10:30:00Z"}],
          "block_count":1,
          "ttl_seconds":604800,
          "expires_at":"2026-12-08T10:00:00Z",
          "updated_at":"2026-11-28T12:00:00Z"
        }
        """.trimIndent()

    private fun confirmDataJson(): String =
        """
        {
          "poll": {
            "poll_id":"$POLL_ID",
            "group_id":"$GROUP_ID",
            "school_id":"haut",
            "title":"期末复习碰头",
            "status":"CONFIRMED",
            "options":[
              {"option_id":"$OPTION_ONE_ID","start_at":"2026-12-01T10:00:00Z","end_at":"2026-12-01T11:00:00Z","vote_count":2,"voter_member_ids":["$MEMBER_ID"]},
              {"option_id":"$OPTION_TWO_ID","start_at":"2026-12-02T10:00:00Z","end_at":"2026-12-02T11:00:00Z","vote_count":1,"voter_member_ids":[]}
            ],
            "voting_deadline_at":"2026-12-01T09:30:00Z",
            "created_by_member_id":"$MEMBER_ID",
            "created_at":"2026-11-28T12:00:00Z",
            "closed_at":"2026-12-01T09:30:00Z",
            "confirmed_at":"2026-11-28T13:00:00Z",
            "confirmed_option_id":"$OPTION_ONE_ID",
            "confirmed_event_id":"$EVENT_ID",
            "my_vote_option_id":"$OPTION_ONE_ID"
          },
          "confirmed_event": {
            "event_id":"$EVENT_ID",
            "poll_id":"$POLL_ID",
            "option_id":"$OPTION_ONE_ID",
            "group_id":"$GROUP_ID",
            "school_id":"haut",
            "start_at":"2026-12-01T10:00:00Z",
            "end_at":"2026-12-01T11:00:00Z",
            "status":"CONFIRMED",
            "confirmed_by_member_id":"$MEMBER_ID",
            "confirmed_at":"2026-11-28T13:00:00Z"
          }
        }
        """.trimIndent()

    private companion object {
        const val GROUP_ID = "11111111-1111-4111-8111-111111111111"
        const val MEMBER_ID = "22222222-2222-4222-8222-222222222222"
        const val POLL_ID = "33333333-3333-4333-8333-333333333333"
        const val OPTION_ONE_ID = "44444444-4444-4444-8444-444444444444"
        const val OPTION_TWO_ID = "55555555-5555-4555-8555-555555555555"
        const val EVENT_ID = "66666666-6666-4666-8666-666666666666"
    }
}
