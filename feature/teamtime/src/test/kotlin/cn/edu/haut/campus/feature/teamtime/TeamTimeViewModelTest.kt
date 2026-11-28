package cn.edu.haut.campus.feature.teamtime

import cn.edu.haut.campus.core.community.CommunityResult
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.Staleness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.timeline.BusyBlock
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeStatus
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeUiAction
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TeamTimeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val zone: ZoneId = ZoneOffset.UTC
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct-teamtime-test"),
        profileId = ProfileId("prof-teamtime-test"),
        campusId = CampusId("lianhuajie"),
    )
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-28T12:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialLoadShowsGroupsMemberCountsAndPersonalBusyCountWithoutRawBlocks() = runTest {
        val fake = FakeTeamTimeRepository()
        val viewModel = TeamTimeViewModel(repositoryFactory = { fake }, clock = clock)

        advanceUntilIdle()
        val state = viewModel.uiState.value

        assertEquals(TeamTimeStatus.CONTENT, state.status)
        assertEquals("group-a", state.selectedGroupId)
        assertEquals(2, state.activeMemberCount)
        assertEquals(1, state.sharingMemberCount)
        assertEquals(2, state.localBusyBlockCount)
        assertTrue(state.queryWindowLabel.isNotBlank())
    }

    @Test
    fun explicitShareTtlEndsAtQueryWindowEndAndUsesThirtyMinuteBusyBlocks() = runTest {
        val fake = FakeTeamTimeRepository()
        val viewModel = TeamTimeViewModel(repositoryFactory = { fake }, clock = clock)
        advanceUntilIdle()

        viewModel.dispatch(TeamTimeUiAction.ShareBusyBlocksClicked)
        advanceUntilIdle()
        val state = viewModel.uiState.value

        assertEquals(7 * 24 * 3600 + 1800, fake.lastShareTtlSeconds)
        assertEquals(fake.lastShareWindowEnd?.epochSecond?.minus(clock.instant().epochSecond)?.toInt(), fake.lastShareTtlSeconds)
        assertEquals(2, fake.lastSharedBlocks.size)
        assertTrue(fake.lastSharedBlocks.all { (it.end.epochSecond - it.start.epochSecond) % 1800L == 0L })
        assertTrue(state.share.shared)
        assertTrue(state.notice?.contains("未上传课程标题") == true)
    }

    @Test
    fun expiredShareAppearsAsUnknownAndIsNotProjectedShared() = runTest {
        val fake = FakeTeamTimeRepository()
        val viewModel = TeamTimeViewModel(repositoryFactory = { fake }, clock = clock)
        advanceUntilIdle()
        assertTrue("初始成员共享级别未过期时是共享投影", viewModel.uiState.value.share.shared)

        viewModel.dispatch(TeamTimeUiAction.QueryAvailabilityClicked)
        advanceUntilIdle()
        val state = viewModel.uiState.value

        assertFalse("云端把过期/不足共享标成 UNKNOWN 后不得继续投影为已共享", state.share.shared)
        assertEquals("未共享或已过期", state.share.statusLabel)
        assertTrue(state.share.detailLabel.contains("UNKNOWN"))
    }

    @Test
    fun expiredShareSummaryNeverProjectsAsShared() = runTest {
        val fake = FakeTeamTimeRepository().apply {
            shareExpiresAt = clock.instant().minusSeconds(1)
        }
        val viewModel = TeamTimeViewModel(repositoryFactory = { fake }, clock = clock)
        advanceUntilIdle()

        viewModel.dispatch(TeamTimeUiAction.ShareBusyBlocksClicked)
        advanceUntilIdle()
        val state = viewModel.uiState.value

        assertFalse(state.share.shared)
        assertEquals("共享已过期", state.share.statusLabel)
        assertTrue(state.share.detailLabel.contains("UNKNOWN"))
    }

    @Test
    fun querySelectionAndCreatePollRequireExplicitUserActions() = runTest {
        val fake = FakeTeamTimeRepository()
        val viewModel = TeamTimeViewModel(repositoryFactory = { fake }, clock = clock)
        advanceUntilIdle()

        viewModel.dispatch(TeamTimeUiAction.QueryAvailabilityClicked)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.candidates.size)
        assertTrue(viewModel.uiState.value.candidates.any { it.unknownLabel?.contains("未知") == true })
        assertTrue(viewModel.uiState.value.candidates.none { it.selected })

        viewModel.dispatch(TeamTimeUiAction.CandidateToggled(fake.candidates[0].key))
        viewModel.dispatch(TeamTimeUiAction.CandidateToggled(fake.candidates[1].key))
        viewModel.dispatch(TeamTimeUiAction.PollTitleChanged("期末复习碰头"))
        viewModel.dispatch(TeamTimeUiAction.CreatePollClicked)
        advanceUntilIdle()

        val poll = viewModel.uiState.value.poll
        assertNotNull(poll)
        assertEquals("期末复习碰头", poll?.title)
        assertEquals(TeamTimePollStatus.OPEN, poll?.status)
        assertEquals(2, fake.createdPollCandidates)
    }

    private inner class FakeTeamTimeRepository : TeamTimeRepository {
        override val scope: SchoolAccountScope = this@TeamTimeViewModelTest.scope
        override val zone: ZoneId = this@TeamTimeViewModelTest.zone
        private val group = TeamTimeGroup(
            groupId = "group-a",
            schoolId = "haut",
            type = "study",
            name = "期末互助小组",
            status = "ACTIVE",
            memberCount = 2,
            myRole = "OWNER",
        )
        private val memberList = listOf(
            TeamTimeMember("member-a", "group-a", "cloud-a", "我", "OWNER", "BUSY_BLOCKS_30MIN", "ACTIVE"),
            TeamTimeMember("member-b", "group-a", "cloud-b", "同学", "MEMBER", "NONE", "ACTIVE"),
        )
        private val busy = listOf(
            BusyBlock(Instant.parse("2026-12-01T10:00:00Z"), Instant.parse("2026-12-01T10:30:00Z"), BusyLevel.BUSY),
            BusyBlock(Instant.parse("2026-12-02T12:00:00Z"), Instant.parse("2026-12-02T13:00:00Z"), BusyLevel.BUSY),
        )
        val candidates = listOf(
            TeamTimeCandidate(
                startAt = Instant.parse("2026-12-01T14:00:00Z"),
                endAt = Instant.parse("2026-12-01T15:00:00Z"),
                availableMemberCount = 2,
                busyMemberCount = 0,
                unknownMemberCount = 0,
                totalMemberCount = 2,
                memberResults = emptyList(),
            ),
            TeamTimeCandidate(
                startAt = Instant.parse("2026-12-02T14:00:00Z"),
                endAt = Instant.parse("2026-12-02T15:00:00Z"),
                availableMemberCount = 1,
                busyMemberCount = 0,
                unknownMemberCount = 1,
                totalMemberCount = 2,
                memberResults = listOf(
                    TeamTimeMemberAvailability(
                        memberId = "member-a",
                        status = TeamTimeAvailabilityStatus.UNKNOWN,
                        sharingLevel = "BUSY_BLOCKS_30MIN",
                    ),
                ),
            ),
        )
        var lastShareTtlSeconds: Int? = null
        var lastShareWindowEnd: Instant? = null
        var shareExpiresAt: Instant = Instant.parse("2026-12-08T12:30:00Z")
        var lastSharedBlocks: List<BusyBlock> = emptyList()
        var createdPollCandidates: Int = 0
        private var createdPoll: TeamTimePoll? = null

        override suspend fun currentCloudUserId(): String? = "cloud-a"
        override suspend fun listGroups(): CommunityResult<List<TeamTimeGroup>> = success(listOf(group))
        override suspend fun createGroup(name: String, type: String): CommunityResult<TeamTimeGroup> = success(group.copy(name = name, type = type))
        override suspend fun listMembers(groupId: String): CommunityResult<List<TeamTimeMember>> = success(memberList)
        override suspend fun myBusyBlocks(window: TimeWindow): List<BusyBlock> = busy

        override suspend fun shareMyBusyBlocks(groupId: String, window: TimeWindow, ttlSeconds: Int): CommunityResult<TeamTimeShareSummary> {
            lastShareTtlSeconds = ttlSeconds
            lastShareWindowEnd = window.end
            lastSharedBlocks = busy
            return success(
                TeamTimeShareSummary(
                    memberId = "member-a",
                    sharingLevel = "BUSY_BLOCKS_30MIN",
                    blockCount = busy.size,
                    ttlSeconds = ttlSeconds,
                    expiresAt = shareExpiresAt,
                    updatedAt = clock.instant(),
                ),
            )
        }

        override suspend fun withdrawMyBusyBlocks(groupId: String): CommunityResult<Unit> = success(Unit)

        override suspend fun queryAvailability(
            groupId: String,
            window: TimeWindow,
            durationMinutes: Int,
            minAttendees: Int,
        ): CommunityResult<List<TeamTimeCandidate>> = success(candidates)

        override suspend fun createPoll(
            groupId: String,
            title: String,
            candidates: List<TeamTimeCandidate>,
            votingDeadlineAt: Instant,
        ): CommunityResult<TeamTimePoll> {
            createdPollCandidates = candidates.size
            val poll = TeamTimePoll(
                pollId = "poll-created",
                groupId = groupId,
                schoolId = "haut",
                title = title,
                status = TeamTimePollStatus.OPEN,
                options = candidates.mapIndexed { index, candidate ->
                    TeamTimePollOption(
                        optionId = "option-" + index,
                        startAt = candidate.startAt,
                        endAt = candidate.endAt,
                        voteCount = 0,
                        voterMemberIds = emptyList(),
                    )
                },
                votingDeadlineAt = votingDeadlineAt,
                createdByMemberId = "member-a",
                createdAt = clock.instant(),
                closedAt = null,
                confirmedAt = null,
                confirmedOptionId = null,
                confirmedEventId = null,
                myVoteOptionId = null,
            )
            createdPoll = poll
            return success(poll)
        }

        override suspend fun vote(pollId: String, optionId: String): CommunityResult<TeamTimePoll> =
            success(checkNotNull(createdPoll).copy(myVoteOptionId = optionId))

        override suspend fun confirm(pollId: String, optionId: String): CommunityResult<TeamTimeConfirmation> =
            throw UnsupportedOperationException("本测试不覆盖 confirm")
    }

    private fun <T> success(value: T): CommunityResult<T> = CommunityResult.Success(
        value = value,
        provenance = DataProvenance(
            providerId = "test",
            schoolId = scope.schoolId,
            accountId = scope.accountId,
            sourceSystem = "fake",
            completeness = SnapshotCompleteness.COMPLETE,
            trust = VerificationLevel.SOURCE_CONFIRMED,
        ),
        staleness = Staleness.FRESH,
    )
}
