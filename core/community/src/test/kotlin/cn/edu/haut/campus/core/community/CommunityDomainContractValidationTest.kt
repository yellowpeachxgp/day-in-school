package cn.edu.haut.campus.core.community

import cn.edu.haut.campus.core.model.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommunityDomainContractValidationTest {
    private val memberOne = "11111111-1111-4111-8111-111111111111"
    private val memberTwo = "22222222-2222-4222-8222-222222222222"

    @Test
    fun optionalMaxLengthOnlyTextMayBeBlankWhenContractHasNoMinLength() {
        CreateGroupRequest("haut", "study", "空白说明", description = "")
        CreateGroupInviteRequest("2026-11-29T08:00:00Z", maxUses = 1, label = "")
        CreatePlaceReportFlagRequest(reason = "INCORRECT", detail = "")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UpdateRepairTicketRequest(description = "")
        }
    }

    @Test
    fun availabilityBlocksUseAbsoluteUtcThirtyMinuteAlignmentAndRejectOverlap() {
        AvailabilityBlockInput(
            startAt = "2026-11-28T08:15:00+05:45",
            endAt = "2026-11-28T08:45:00+05:45",
        )
        assertThrows(IllegalArgumentException::class.java) {
            AvailabilityBlockInput(
                startAt = "2026-11-28T08:30:00+05:45",
                endAt = "2026-11-28T09:00:00+05:45",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReplaceGroupAvailabilityRequest(
                blocks = listOf(
                    AvailabilityBlockInput("2026-11-28T08:00:00Z", "2026-11-28T09:00:00Z"),
                    AvailabilityBlockInput("2026-11-28T08:30:00Z", "2026-11-28T10:00:00Z"),
                ),
                ttlSeconds = 1800,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReplaceGroupAvailabilityRequest(
                blocks = (1..201).map {
                    val hour = 8 + it
                    AvailabilityBlockInput(
                        "2026-11-29T" + (hour % 24).toString().padStart(2, '0') + ":00:00Z",
                        "2026-11-29T" + (hour % 24).toString().padStart(2, '0') + ":30:00Z",
                    )
                },
                ttlSeconds = 1800,
            )
        }
    }

    @Test
    fun availabilityQueryAlignsWindowToSelectedGranularityAndRequiresMemberUuids() {
        GroupAvailabilityQueryRequest(
            windowStartAt = "2026-11-28T08:00:00Z",
            windowEndAt = "2026-11-28T10:00:00Z",
            durationMinutes = 60,
            minAttendees = 2,
            memberIds = listOf(memberOne, memberTwo),
            granularityMinutes = 60,
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroupAvailabilityQueryRequest(
                windowStartAt = "2026-11-28T08:30:00Z",
                windowEndAt = "2026-11-28T10:00:00Z",
                durationMinutes = 60,
                minAttendees = 2,
                granularityMinutes = 60,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupAvailabilityQueryRequest(
                windowStartAt = "2026-11-28T08:00:00Z",
                windowEndAt = "2026-11-28T10:00:00Z",
                durationMinutes = 30,
                minAttendees = 1,
                memberIds = listOf("not-a-uuid"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupAvailabilityQueryRequest(
                windowStartAt = "2026-11-28T08:00:00Z",
                windowEndAt = "2026-11-28T09:00:00Z",
                durationMinutes = 90,
                minAttendees = 1,
            )
        }
    }

    @Test
    fun pollOptionsRequireTwoToTenDistinctWindowsAndDeadlineBeforeEarliestStart() {
        val first = GroupPollOptionInput("2026-11-28T12:00:00Z", "2026-11-28T13:00:00Z")
        val second = GroupPollOptionInput("2026-11-28T14:00:00Z", "2026-11-28T15:00:00Z")
        CreateGroupPollRequest(
            title = "组会时间",
            options = listOf(first, second),
            votingDeadlineAt = "2026-11-28T11:30:00Z",
        )
        assertThrows(IllegalArgumentException::class.java) {
            CreateGroupPollRequest("只有一个", listOf(first), "2026-11-28T11:30:00Z")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CreateGroupPollRequest("重复", listOf(first, first), "2026-11-28T11:30:00Z")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CreateGroupPollRequest("截止过晚", listOf(first, second), "2026-11-28T12:30:00Z")
        }
    }

    @Test
    fun ledgerSplitRequiresUniquePositiveWeightsAndUuidParticipants() {
        val valid = LedgerSplitRuleInput(
            type = "RATIOS",
            ratioWeights = listOf(
                LedgerRatioWeightInput(memberOne, 1L),
                LedgerRatioWeightInput(memberTwo, 2L),
            ),
        )
        assertEquals("RATIOS", valid.toJson().str("type"))
        assertThrows(IllegalArgumentException::class.java) {
            LedgerSplitRuleInput(
                type = "RATIOS",
                ratioWeights = listOf(
                    LedgerRatioWeightInput(memberOne, 1L),
                    LedgerRatioWeightInput(memberOne, 2L),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LedgerSplitRuleInput(
                type = "RATIOS",
                ratioWeights = listOf(LedgerRatioWeightInput(memberOne, 0L)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LedgerSplitRuleInput(type = "SELECTED", participantMemberIds = listOf("not-a-uuid"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CreateLedgerEntryRequest(
                title = "电费",
                amountFen = 0L,
                paidByMemberId = memberOne,
                occurredAt = "2026-11-28T10:00:00Z",
                splitRule = valid,
            )
        }
    }

    @Test
    fun dutyAndRepairRequestsValidateDatesAndUuidReferences() {
        assertThrows(IllegalArgumentException::class.java) {
            CreateDutyRotaRequest(
                title = "宿舍值日",
                rotationMemberIds = listOf(memberOne, memberTwo),
                frequency = "DAILY",
                startDate = "2026-11-30",
                endDate = "2026-11-29",
                timeZone = "Asia/Shanghai",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RequestDutySwapRequest(
                dutyTaskId = "not-a-uuid",
                type = "COVER",
                targetMemberId = memberTwo,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransitionRepairStatusRequest(
                toStatus = "IN_PROGRESS",
                assignedMemberId = "not-a-uuid",
            )
        }
    }

    @Test
    fun placeStatusRequestsValidateMetricStateCorrectionAndPlaceRef() {
        CreatePlaceStatusReportRequest(
            metric = "OCCUPANCY",
            state = "CROWDED",
            observedAt = "2026-11-28T10:00:00Z",
            reportKind = "OBSERVATION",
        )
        assertThrows(IllegalArgumentException::class.java) {
            CreatePlaceStatusReportRequest(
                metric = "OCCUPANCY",
                state = "OPEN",
                observedAt = "2026-11-28T10:00:00Z",
                reportKind = "OBSERVATION",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CreatePlaceStatusReportRequest(
                metric = "ACCESS",
                state = "OPEN",
                observedAt = "2026-11-28T10:00:00Z",
                reportKind = "CORRECTION",
                correctsReportId = "not-a-uuid",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaceStatusSubscriptionTargetInput(type = "PLACE", placeRef = "not-a-place-ref")
        }
        assertEquals(
            JsonValue.Str("PLACE"),
            PlaceStatusSubscriptionTargetInput(type = "PLACE", placeRef = "haut/lianhuajie/room/5-111").toJson().obj("type"),
        )
    }
}
