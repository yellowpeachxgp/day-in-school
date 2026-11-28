package cn.edu.haut.campus.feature.teamtime

import cn.edu.haut.campus.core.model.json.Json
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class TeamTimeJsonTest {
    @Test
    fun featureModelsHaveNoRawBusyBlockField() {
        assertFalse(TeamTimeShareSummary::class.java.declaredFields.any { it.name.contains("block", ignoreCase = true) && it.name != "blockCount" })
        assertFalse(TeamTimeCandidate::class.java.declaredFields.any { it.name.contains("block", ignoreCase = true) })
        assertFalse(TeamTimeMemberAvailability::class.java.declaredFields.any { it.name.contains("block", ignoreCase = true) })
    }

    @Test
    fun availabilityQueryParsesAggregateCandidatesAndMemberStatesWithoutRawBlocks() {
        val data = Json.parse(
            """
            {
              "group_id":"group-1",
              "school_id":"haut",
              "window_start_at":"2026-12-01T10:00:00Z",
              "window_end_at":"2026-12-08T10:00:00Z",
              "duration_minutes":60,
              "granularity_minutes":30,
              "min_attendees":2,
              "generated_at":"2026-11-28T12:00:00Z",
              "candidates":[
                {
                  "start_at":"2026-12-01T10:00:00Z",
                  "end_at":"2026-12-01T11:00:00Z",
                  "available_member_count":3,
                  "busy_member_count":0,
                  "unknown_member_count":1,
                  "total_member_count":4,
                  "member_results":[
                    {"member_id":"member-a","status":"AVAILABLE","sharing_level":"BUSY_BLOCKS_30MIN"},
                    {"member_id":"member-b","status":"UNKNOWN","sharing_level":"NONE"}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val candidates = TeamTimeJson.parseAvailabilityQuery(data)

        assertEquals(1, candidates.size)
        assertEquals(Instant.parse("2026-12-01T10:00:00Z"), candidates.single().startAt)
        assertEquals(3, candidates.single().availableMemberCount)
        assertEquals(TeamTimeAvailabilityStatus.AVAILABLE, candidates.single().memberResults[0].status)
        assertEquals(TeamTimeAvailabilityStatus.UNKNOWN, candidates.single().memberResults[1].status)
    }

    @Test
    fun shareSummaryValidatesButDoesNotRetainRawBlocks() {
        val data = Json.parse(
            """
            {
              "group_id":"group-1",
              "school_id":"haut",
              "member_id":"member-a",
              "sharing_level":"BUSY_BLOCKS_30MIN",
              "blocks":[{"start_at":"2026-12-01T10:00:00Z","end_at":"2026-12-01T10:30:00Z"}],
              "block_count":1,
              "ttl_seconds":1800,
              "expires_at":"2026-12-01T12:00:00Z",
              "updated_at":"2026-11-28T12:00:00Z"
            }
            """.trimIndent(),
        )

        val summary = TeamTimeJson.parseShare(data)

        assertEquals(1, summary.blockCount)
        assertEquals("BUSY_BLOCKS_30MIN", summary.sharingLevel)
        assertEquals(Instant.parse("2026-12-01T12:00:00Z"), summary.expiresAt)
    }

    @Test
    fun confirmResultParsesContractFieldNamesAndNullablePollFields() {
        val pollJson = """
            {
              "poll_id":"poll-1",
              "group_id":"group-1",
              "school_id":"haut",
              "title":"复习碰头",
              "status":"CONFIRMED",
              "options":[
                {"option_id":"option-1","start_at":"2026-12-01T10:00:00Z","end_at":"2026-12-01T11:00:00Z","vote_count":2,"voter_member_ids":["member-a"]},
                {"option_id":"option-2","start_at":"2026-12-02T10:00:00Z","end_at":"2026-12-02T11:00:00Z","vote_count":1,"voter_member_ids":["member-b"]}
              ],
              "voting_deadline_at":"2026-12-01T09:30:00Z",
              "created_by_member_id":"member-a",
              "created_at":"2026-11-28T12:00:00Z",
              "closed_at":"2026-12-01T09:30:00Z",
              "confirmed_at":"2026-11-28T13:00:00Z",
              "confirmed_option_id":"option-1",
              "confirmed_event_id":"event-1",
              "my_vote_option_id":"option-1"
            }
        """.trimIndent()
        val data = Json.parse(
            """
            {
              "poll": $pollJson,
              "confirmed_event": {
                "event_id":"event-1",
                "poll_id":"poll-1",
                "option_id":"option-1",
                "group_id":"group-1",
                "school_id":"haut",
                "start_at":"2026-12-01T10:00:00Z",
                "end_at":"2026-12-01T11:00:00Z",
                "status":"CONFIRMED",
                "confirmed_by_member_id":"member-a",
                "confirmed_at":"2026-11-28T13:00:00Z"
              }
            }
            """.trimIndent(),
        )

        val (poll, event) = TeamTimeJson.parseConfirmResult(data)

        assertEquals(TeamTimePollStatus.CONFIRMED, poll.status)
        assertEquals("event-1", poll.confirmedEventId)
        assertEquals("option-1", event.optionId)
        assertEquals(Instant.parse("2026-12-01T10:00:00Z"), event.startAt)
        assertEquals(Instant.parse("2026-12-01T11:00:00Z"), event.endAt)
        assertEquals(Instant.parse("2026-11-28T13:00:00Z"), event.confirmedAt)
    }

    @Test
    fun pollParserAcceptsExplicitNullNullableFields() {
        val data = Json.parse(
            """
            {
              "poll_id":"poll-1",
              "group_id":"group-1",
              "school_id":"haut",
              "title":"复习碰头",
              "status":"OPEN",
              "options":[
                {"option_id":"option-1","start_at":"2026-12-01T10:00:00Z","end_at":"2026-12-01T11:00:00Z","vote_count":0,"voter_member_ids":[]},
                {"option_id":"option-2","start_at":"2026-12-02T10:00:00Z","end_at":"2026-12-02T11:00:00Z","vote_count":0,"voter_member_ids":[]}
              ],
              "voting_deadline_at":"2026-12-01T09:30:00Z",
              "created_by_member_id":"member-a",
              "created_at":"2026-11-28T12:00:00Z",
              "closed_at":null,
              "confirmed_at":null,
              "confirmed_option_id":null,
              "confirmed_event_id":null,
              "my_vote_option_id":null
            }
            """.trimIndent(),
        )

        val poll = TeamTimeJson.parsePoll(data)

        assertEquals(TeamTimePollStatus.OPEN, poll.status)
        assertNull(poll.closedAt)
        assertNull(poll.confirmedAt)
        assertNull(poll.confirmedOptionId)
        assertNull(poll.confirmedEventId)
        assertNull(poll.myVoteOptionId)
    }
}
