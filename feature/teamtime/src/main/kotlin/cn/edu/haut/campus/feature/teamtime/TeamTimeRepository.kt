package cn.edu.haut.campus.feature.teamtime

import cn.edu.haut.campus.core.community.CommunityClient
import cn.edu.haut.campus.core.community.CommunityEnvelope
import cn.edu.haut.campus.core.community.CommunityError
import cn.edu.haut.campus.core.community.CommunityErrorKind
import cn.edu.haut.campus.core.community.CommunityResult
import cn.edu.haut.campus.core.community.confirmGroupPoll
import cn.edu.haut.campus.core.community.createGroup
import cn.edu.haut.campus.core.community.createGroupPoll
import cn.edu.haut.campus.core.community.deleteMyGroupAvailability
import cn.edu.haut.campus.core.community.listGroupMembers
import cn.edu.haut.campus.core.community.listGroups
import cn.edu.haut.campus.core.community.queryGroupAvailability
import cn.edu.haut.campus.core.community.replaceMyGroupAvailability
import cn.edu.haut.campus.core.community.upsertGroupPollVote
import cn.edu.haut.campus.core.community.AvailabilityBlockInput
import cn.edu.haut.campus.core.community.ConfirmGroupPollRequest
import cn.edu.haut.campus.core.community.CreateGroupPollRequest
import cn.edu.haut.campus.core.community.CreateGroupRequest
import cn.edu.haut.campus.core.community.GroupAvailabilityQueryRequest
import cn.edu.haut.campus.core.community.GroupPollOptionInput
import cn.edu.haut.campus.core.community.ReplaceGroupAvailabilityRequest
import cn.edu.haut.campus.core.community.UpsertGroupPollVoteRequest
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.timeline.BusyBlock
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.PersonalEventDraft
import cn.edu.haut.campus.core.timeline.PersonalEventStore
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineEventId
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineService
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** 组队找时间的应用边界。UI 不接触 CommunityClient、JsonValue、Room 或 Timeline 装配细节。 */
interface TeamTimeRepository {
    val scope: SchoolAccountScope
    val zone: ZoneId

    suspend fun currentCloudUserId(): String?
    suspend fun listGroups(): CommunityResult<List<TeamTimeGroup>>
    suspend fun createGroup(name: String, type: String): CommunityResult<TeamTimeGroup>
    suspend fun listMembers(groupId: String): CommunityResult<List<TeamTimeMember>>
    suspend fun myBusyBlocks(window: TimeWindow): List<BusyBlock>
    suspend fun shareMyBusyBlocks(groupId: String, window: TimeWindow, ttlSeconds: Int): CommunityResult<TeamTimeShareSummary>
    suspend fun withdrawMyBusyBlocks(groupId: String): CommunityResult<Unit>
    suspend fun queryAvailability(
        groupId: String,
        window: TimeWindow,
        durationMinutes: Int,
        minAttendees: Int,
    ): CommunityResult<List<TeamTimeCandidate>>

    suspend fun createPoll(
        groupId: String,
        title: String,
        candidates: List<TeamTimeCandidate>,
        votingDeadlineAt: Instant,
    ): CommunityResult<TeamTimePoll>

    suspend fun vote(pollId: String, optionId: String): CommunityResult<TeamTimePoll>
    suspend fun confirm(pollId: String, optionId: String): CommunityResult<TeamTimeConfirmation>
}

/** 云端 contract client + Timeline/PersonalEventStore 的生产适配器。 */
class CommunityTeamTimeRepository(
    private val client: CommunityClient,
    private val cloudUserIdProvider: suspend () -> String?,
    private val timelineService: TimelineService,
    private val personalEventStore: PersonalEventStore?,
    override val scope: SchoolAccountScope,
    override val zone: ZoneId,
    private val clock: Clock = Clock.systemUTC(),
    private val idempotencyKeyFactory: () -> String = { "tt-" + UUID.randomUUID().toString() },
) : TeamTimeRepository {
    override suspend fun currentCloudUserId(): String? = cloudUserIdProvider()

    override suspend fun listGroups(): CommunityResult<List<TeamTimeGroup>> =
        client.listGroups(schoolId = scope.schoolId.value)
            .mapEnvelopeData(TeamTimeJson::parseGroupList)

    override suspend fun createGroup(name: String, type: String): CommunityResult<TeamTimeGroup> =
        client.createGroup(
            request = CreateGroupRequest(schoolId = scope.schoolId.value, type = type, name = name),
            idempotencyKey = idempotencyKeyFactory(),
        ).mapEnvelopeData(TeamTimeJson::parseGroup)

    override suspend fun listMembers(groupId: String): CommunityResult<List<TeamTimeMember>> =
        client.listGroupMembers(groupId).mapEnvelopeData(TeamTimeJson::parseMemberList)

    override suspend fun myBusyBlocks(window: TimeWindow): List<BusyBlock> =
        timelineService.busyBlocks(scope, window, Duration.ofMinutes(30))

    override suspend fun shareMyBusyBlocks(
        groupId: String,
        window: TimeWindow,
        ttlSeconds: Int,
    ): CommunityResult<TeamTimeShareSummary> {
        val inputs = myBusyBlocks(window).toAvailabilityBlockInputs()
        return client.replaceMyGroupAvailability(
            groupId = groupId,
            request = ReplaceGroupAvailabilityRequest(blocks = inputs, ttlSeconds = ttlSeconds),
            idempotencyKey = idempotencyKeyFactory(),
        ).mapEnvelopeData(TeamTimeJson::parseShare)
    }

    override suspend fun withdrawMyBusyBlocks(groupId: String): CommunityResult<Unit> =
        client.deleteMyGroupAvailability(groupId, idempotencyKeyFactory()).mapEnvelopeData { }

    override suspend fun queryAvailability(
        groupId: String,
        window: TimeWindow,
        durationMinutes: Int,
        minAttendees: Int,
    ): CommunityResult<List<TeamTimeCandidate>> =
        client.queryGroupAvailability(
            groupId = groupId,
            request = GroupAvailabilityQueryRequest(
                windowStartAt = formatTeamTimeInstant(window.start),
                windowEndAt = formatTeamTimeInstant(window.end),
                durationMinutes = durationMinutes,
                minAttendees = minAttendees,
            ),
            idempotencyKey = idempotencyKeyFactory(),
        ).mapEnvelopeData(TeamTimeJson::parseAvailabilityQuery)

    override suspend fun createPoll(
        groupId: String,
        title: String,
        candidates: List<TeamTimeCandidate>,
        votingDeadlineAt: Instant,
    ): CommunityResult<TeamTimePoll> =
        client.createGroupPoll(
            groupId = groupId,
            request = CreateGroupPollRequest(
                title = title,
                options = candidates.map { GroupPollOptionInput(formatTeamTimeInstant(it.startAt), formatTeamTimeInstant(it.endAt)) },
                votingDeadlineAt = formatTeamTimeInstant(votingDeadlineAt),
            ),
            idempotencyKey = idempotencyKeyFactory(),
        ).mapEnvelopeData(TeamTimeJson::parsePoll)

    override suspend fun vote(pollId: String, optionId: String): CommunityResult<TeamTimePoll> =
        client.upsertGroupPollVote(
            pollId = pollId,
            request = UpsertGroupPollVoteRequest(optionId),
            idempotencyKey = idempotencyKeyFactory(),
        ).mapEnvelopeData(TeamTimeJson::parseVoteResult)

    override suspend fun confirm(pollId: String, optionId: String): CommunityResult<TeamTimeConfirmation> {
        val cloudResult = client.confirmGroupPoll(
            pollId = pollId,
            request = ConfirmGroupPollRequest(optionId),
            idempotencyKey = idempotencyKeyFactory(),
        ).mapEnvelopeData(TeamTimeJson::parseConfirmResult)
        return when (cloudResult) {
            is CommunityResult.Success -> {
                val (poll, event) = cloudResult.value
                val write = writeConfirmedEventToTimeline(poll, event)
                CommunityResult.Success(
                    value = TeamTimeConfirmation(
                        poll = poll,
                        confirmedEvent = event,
                        timelineEventId = write.getOrNull(),
                        timelineWriteError = write.exceptionOrNull()?.message ?: if (personalEventStore == null) {
                            "本机 Timeline 写入端口不可用"
                        } else {
                            null
                        },
                    ),
                    provenance = cloudResult.provenance,
                    staleness = cloudResult.staleness,
                    cachedAtEpochMillis = cloudResult.cachedAtEpochMillis,
                    cacheFallbackReason = cloudResult.cacheFallbackReason,
                )
            }
            is CommunityResult.Failure -> cloudResult
            is CommunityResult.Disabled -> cloudResult
            CommunityResult.AuthenticationRequired -> cloudResult
        }
    }

    private suspend fun writeConfirmedEventToTimeline(
        poll: TeamTimePoll,
        event: ConfirmedGroupEvent,
    ): Result<TimelineEventId> = runCatching {
        val store = personalEventStore ?: error("本机 Timeline 写入端口不可用")
        val stableId = TimelineEventId(PersonalEventDraft.PERSONAL_TIMELINE_SOURCE + ":group-meeting-" + event.eventId)
        val groupRef = EntityRef(EntityType.GROUP, event.groupId, scope)
        val eventRef = EntityRef(EntityType.GROUP_EVENT, event.eventId, scope)
        val provenance = DataProvenance(
            providerId = "cloud-in-school",
            schoolId = scope.schoolId,
            accountId = scope.accountId,
            sourceSystem = "campus-cloud-community/v1",
            sourceRecordId = event.eventId,
            fetchedAt = clock.instant(),
            completeness = SnapshotCompleteness.COMPLETE,
            trust = VerificationLevel.SOURCE_CONFIRMED,
            schemaVersion = 1,
            contentRevision = poll.pollId,
        )
        store.upsert(
            PersonalEventDraft(
                id = stableId,
                scope = scope,
                kind = TimelineKind.GROUP_MEETING,
                title = poll.title.take(120),
                note = "由组队找时间投票确认；云端事件 " + event.eventId,
                start = event.startAt,
                end = event.endAt,
                timeSemantics = TimelineTimeSemantics.TIMED,
                zone = zone,
                status = EventStatus.SCHEDULED,
                provenance = provenance,
                sourceEntity = eventRef,
                links = listOf(groupRef),
            ),
            actor = Actor.USER,
        )
    }
}

internal fun List<BusyBlock>.toAvailabilityBlockInputs(): List<AvailabilityBlockInput> =
    map { AvailabilityBlockInput(formatTeamTimeInstant(it.start), formatTeamTimeInstant(it.end)) }

private inline fun <T> CommunityResult<CommunityEnvelope>.mapEnvelopeData(
    crossinline parser: (cn.edu.haut.campus.core.model.json.JsonValue?) -> T,
): CommunityResult<T> = when (this) {
    is CommunityResult.Success -> try {
        CommunityResult.Success(
            value = parser(value.data),
            provenance = provenance,
            staleness = staleness,
            cachedAtEpochMillis = cachedAtEpochMillis,
            cacheFallbackReason = cacheFallbackReason,
        )
    } catch (error: Exception) {
        CommunityResult.Failure(
            CommunityError(
                kind = CommunityErrorKind.CONTRACT,
                code = "CONTRACT_RESPONSE_INVALID",
                message = "校园云响应字段不符合组队找时间契约：" + (error.message ?: error::class.simpleName),
                retryable = false,
            ),
        )
    }
    is CommunityResult.Failure -> this
    is CommunityResult.Disabled -> this
    CommunityResult.AuthenticationRequired -> this
}
