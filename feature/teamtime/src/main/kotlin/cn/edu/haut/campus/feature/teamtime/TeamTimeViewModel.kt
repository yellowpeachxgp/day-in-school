package cn.edu.haut.campus.feature.teamtime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.edu.haut.campus.core.community.CommunityError
import cn.edu.haut.campus.core.community.CommunityErrorKind
import cn.edu.haut.campus.core.community.CommunityResult
import cn.edu.haut.campus.core.model.Staleness
import cn.edu.haut.campus.core.timeline.BusyBlock
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeCandidateUi
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeGroupUi
import cn.edu.haut.campus.feature.teamtime.state.TeamTimePollOptionUi
import cn.edu.haut.campus.feature.teamtime.state.TeamTimePollUi
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeShareUi
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeStatus
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeUiAction
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeUiEffect
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeUiState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 组队找时间状态边界。只读取聚合忙闲状态；他人原始块既没有模型入口，也不会进入 UiState。 */
class TeamTimeViewModel(
    private val repositoryFactory: suspend () -> TeamTimeRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val repositoryReady = CompletableDeferred<TeamTimeRepository>()
    private val mutableState = MutableStateFlow(TeamTimeUiState(status = TeamTimeStatus.LOADING))
    val uiState = mutableState.asStateFlow()
    private val mutableEffects = MutableSharedFlow<TeamTimeUiEffect>(extraBufferCapacity = 8)
    val effects = mutableEffects.asSharedFlow()

    private var refreshJob: Job? = null
    private var actionJob: Job? = null
    private var groups: List<TeamTimeGroup> = emptyList()
    private var members: List<TeamTimeMember> = emptyList()
    private var candidateDomain: List<TeamTimeCandidate> = emptyList()
    private var pollDomain: TeamTimePoll? = null
    private var personalBusyBlocks: List<BusyBlock> = emptyList()
    private var shareSummary: TeamTimeShareSummary? = null
    private var cloudUserId: String? = null
    private var repositoryZone: ZoneId? = null
    private var queryWindow: TimeWindow = computeDefaultWindow()

    init {
        viewModelScope.launch {
            try {
                val repository = repositoryFactory()
                repositoryZone = repository.zone
                repositoryReady.complete(repository)
                refresh()
            } catch (error: CancellationException) {
                repositoryReady.completeExceptionally(error)
                throw error
            } catch (error: Throwable) {
                repositoryReady.completeExceptionally(error)
                mutableState.update {
                    it.copy(
                        status = TeamTimeStatus.ERROR_TERMINAL,
                        working = false,
                        errorMessage = "组队找时间装配失败：" + (error.message ?: error::class.simpleName),
                    )
                }
            }
        }
    }

    fun dispatch(action: TeamTimeUiAction) {
        when (action) {
            TeamTimeUiAction.BackClicked -> emit(TeamTimeUiEffect.NavigateBack)
            TeamTimeUiAction.RetryClicked -> refresh()
            TeamTimeUiAction.LoginRequested -> emit(TeamTimeUiEffect.NavigateToCloudLogin)
            TeamTimeUiAction.PermissionRequested -> mutableState.update {
                it.copy(notice = "群组忙闲与投票权限由云端按成员身份判定；请确认你仍是该群成员后重试")
            }
            is TeamTimeUiAction.GroupSelected -> selectGroup(action.groupId)
            is TeamTimeUiAction.GroupNameChanged -> mutableState.update {
                it.copy(groupNameInput = action.value.take(80), errorMessage = null)
            }
            is TeamTimeUiAction.GroupTypeSelected -> mutableState.update {
                it.copy(groupTypeInput = action.type, errorMessage = null)
            }
            TeamTimeUiAction.CreateGroupClicked -> createGroup()
            TeamTimeUiAction.ShareBusyBlocksClicked -> shareBusyBlocks()
            TeamTimeUiAction.WithdrawSharingClicked -> withdrawSharing()
            is TeamTimeUiAction.DurationSelected -> mutableState.update {
                it.copy(durationMinutes = action.minutes.coerceIn(30, 240), errorMessage = null)
            }
            is TeamTimeUiAction.MinAttendeesChanged -> mutableState.update {
                it.copy(minAttendees = action.value.coerceAtLeast(1), errorMessage = null)
            }
            TeamTimeUiAction.QueryAvailabilityClicked -> queryAvailability()
            is TeamTimeUiAction.CandidateToggled -> toggleCandidate(action.key)
            is TeamTimeUiAction.PollTitleChanged -> mutableState.update {
                it.copy(pollTitleInput = action.value.take(120), errorMessage = null)
            }
            TeamTimeUiAction.CreatePollClicked -> createPoll()
            is TeamTimeUiAction.VoteClicked -> vote(action.optionId)
            is TeamTimeUiAction.ConfirmClicked -> confirm(action.optionId)
        }
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(status = TeamTimeStatus.LOADING, working = true, errorMessage = null, notice = null) }
            try {
                val repository = repository()
                cloudUserId = repository.currentCloudUserId()
                when (val groupsResult = repository.listGroups()) {
                    is CommunityResult.Success -> {
                        groups = groupsResult.value.filter { it.status == "ACTIVE" }
                        val selected = mutableState.value.selectedGroupId
                            ?.takeIf { id -> groups.any { it.groupId == id } }
                            ?: groups.firstOrNull()?.groupId
                        members = emptyList()
                        candidateDomain = emptyList()
                        shareSummary = null
                        var degraded = false
                        var degradedMessage: String? = null
                        if (selected != null) {
                            when (val membersResult = repository.listMembers(selected)) {
                                is CommunityResult.Success -> members = membersResult.value
                                else -> {
                                    degraded = true
                                    degradedMessage = resultMessage(membersResult, "成员状态不可用")
                                }
                            }
                        }
                        queryWindow = computeDefaultWindow()
                        personalBusyBlocks = try {
                            repository.myBusyBlocks(queryWindow)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            degraded = true
                            degradedMessage = "个人课表对照不可用：" + (error.message ?: error::class.simpleName)
                            emptyList()
                        }
                        val baseStatus = when {
                            groups.isEmpty() -> TeamTimeStatus.EMPTY
                            degraded -> TeamTimeStatus.PARTIAL
                            groupsResult.staleness == Staleness.STALE -> TeamTimeStatus.STALE
                            else -> TeamTimeStatus.CONTENT
                        }
                        publishContent(
                            status = baseStatus,
                            selectedGroupId = selected,
                            errorMessage = degradedMessage,
                            notice = if (groupsResult.staleness == Staleness.STALE) "群组列表来自本地快照，成员与忙闲仍需在线核验" else null,
                            clearCandidates = true,
                        )
                    }
                    else -> applyCloudFailure(groupsResult, "群组列表加载失败")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        status = TeamTimeStatus.ERROR_RETRYABLE,
                        working = false,
                        errorMessage = "组队找时间加载失败：" + (error.message ?: error::class.simpleName),
                    )
                }
            }
        }
    }

    private fun selectGroup(groupId: String) {
        if (groupId == mutableState.value.selectedGroupId) return
        mutableState.update { it.copy(selectedGroupId = groupId, candidates = emptyList(), errorMessage = null) }
        candidateDomain = emptyList()
        pollDomain = null
        shareSummary = null
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            try {
                val repository = repository()
                when (val result = repository.listMembers(groupId)) {
                    is CommunityResult.Success -> {
                        members = result.value
                        publishContent(status = if (groups.isEmpty()) TeamTimeStatus.EMPTY else TeamTimeStatus.CONTENT, selectedGroupId = groupId, clearCandidates = true)
                    }
                    else -> {
                        members = emptyList()
                        publishContent(
                            status = TeamTimeStatus.PARTIAL,
                            selectedGroupId = groupId,
                            errorMessage = resultMessage(result, "成员状态加载失败"),
                            clearCandidates = true,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { it.copy(status = TeamTimeStatus.ERROR_RETRYABLE, errorMessage = error.message, working = false) }
            }
        }
    }

    private fun createGroup() {
        val name = mutableState.value.groupNameInput.trim()
        if (name.isEmpty()) {
            mutableState.update { it.copy(errorMessage = "请输入群组名称") }
            return
        }
        val type = mutableState.value.groupTypeInput
        runCloudAction {
            val repository = repository()
            when (val result = repository.createGroup(name, type)) {
                is CommunityResult.Success -> {
                    mutableState.update { it.copy(groupNameInput = "", selectedGroupId = result.value.groupId) }
                    refresh()
                }
                else -> applyCloudFailure(result, "创建群组失败")
            }
        }
    }

    private fun shareBusyBlocks() {
        val groupId = mutableState.value.selectedGroupId ?: return
        runCloudAction {
            val repository = repository()
            queryWindow = computeDefaultWindow()
            when (val result = repository.shareMyBusyBlocks(groupId, queryWindow, SHARE_TTL_SECONDS)) {
                is CommunityResult.Success -> {
                    shareSummary = result.value
                    projectOwnSharingLevel(shared = true)
                    reloadMembersBestEffort(repository, groupId)
                    publishContent(
                        status = TeamTimeStatus.CONTENT,
                        selectedGroupId = groupId,
                        notice = "已共享 " + result.value.blockCount + " 个 30 分钟粒度忙闲块；未上传课程标题、地点或原因",
                    )
                }
                else -> applyCloudFailure(result, "共享忙闲失败")
            }
        }
    }

    private fun withdrawSharing() {
        val groupId = mutableState.value.selectedGroupId ?: return
        runCloudAction {
            val repository = repository()
            when (val result = repository.withdrawMyBusyBlocks(groupId)) {
                is CommunityResult.Success -> {
                    shareSummary = null
                    projectOwnSharingLevel(shared = false)
                    reloadMembersBestEffort(repository, groupId)
                    publishContent(
                        status = TeamTimeStatus.CONTENT,
                        selectedGroupId = groupId,
                        notice = "已撤回本群忙闲共享；后续交集查询会把你视为未知而不是空闲",
                    )
                }
                else -> applyCloudFailure(result, "撤回共享失败")
            }
        }
    }

    private fun queryAvailability() {
        val groupId = mutableState.value.selectedGroupId ?: return
        runCloudAction {
            val repository = repository()
            queryWindow = computeDefaultWindow()
            personalBusyBlocks = try {
                repository.myBusyBlocks(queryWindow)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                personalBusyBlocks
            }
            when (val result = repository.queryAvailability(
                groupId = groupId,
                window = queryWindow,
                durationMinutes = mutableState.value.durationMinutes,
                minAttendees = mutableState.value.minAttendees,
            )) {
                is CommunityResult.Success -> {
                    candidateDomain = result.value
                    publishContent(
                        status = if (result.value.isEmpty()) TeamTimeStatus.CONTENT else TeamTimeStatus.CONTENT,
                        selectedGroupId = groupId,
                        notice = if (result.value.isEmpty()) {
                            "没有满足人数与时长要求的候选时段；未知成员不会被当作空闲"
                        } else {
                            "交集结果只含成员忙/闲/未知状态与聚合计数，不包含他人原始时间块"
                        },
                    )
                }
                else -> applyCloudFailure(result, "共同空闲查询失败")
            }
        }
    }

    private fun toggleCandidate(key: String) {
        mutableState.update { state ->
            state.copy(
                candidates = state.candidates.map { candidate ->
                    if (candidate.key == key) candidate.copy(selected = !candidate.selected) else candidate
                },
            )
        }
    }

    private fun createPoll() {
        val groupId = mutableState.value.selectedGroupId ?: return
        val selectedKeys = mutableState.value.candidates.filter { it.selected }.map { it.key }.toSet()
        val selectedCandidates = candidateDomain.filter { it.key in selectedKeys }
        val title = mutableState.value.pollTitleInput.trim()
        when {
            title.isEmpty() -> {
                mutableState.update { it.copy(errorMessage = "请输入投票标题") }
                return
            }
            selectedCandidates.size !in 2..10 -> {
                mutableState.update { it.copy(errorMessage = "请选择 2–10 个候选时段") }
                return
            }
        }
        runCloudAction {
            val repository = repository()
            val deadline = votingDeadlineFor(selectedCandidates)
            when (val result = repository.createPoll(groupId, title, selectedCandidates, deadline)) {
                is CommunityResult.Success -> {
                    pollDomain = result.value
                    publishContent(
                        status = TeamTimeStatus.CONTENT,
                        selectedGroupId = groupId,
                        notice = "投票已创建；确认前不会写入个人时间流",
                    )
                }
                else -> applyCloudFailure(result, "创建投票失败")
            }
        }
    }

    private fun vote(optionId: String) {
        val pollId = pollDomain?.pollId ?: return
        runCloudAction {
            val repository = repository()
            when (val result = repository.vote(pollId, optionId)) {
                is CommunityResult.Success -> {
                    pollDomain = result.value
                    publishContent(status = TeamTimeStatus.CONTENT, notice = "投票已更新")
                }
                else -> applyCloudFailure(result, "投票失败")
            }
        }
    }

    private fun confirm(optionId: String) {
        val pollId = pollDomain?.pollId ?: return
        runCloudAction {
            val repository = repository()
            when (val result = repository.confirm(pollId, optionId)) {
                is CommunityResult.Success -> {
                    pollDomain = result.value.poll
                    val timelineError = result.value.timelineWriteError
                    publishContent(
                        status = if (timelineError == null) TeamTimeStatus.CONTENT else TeamTimeStatus.PARTIAL,
                        notice = if (timelineError == null) {
                            "已确认并写入个人时间流；成员之后调课只会提示冲突，不会静默改期"
                        } else {
                            null
                        },
                        errorMessage = timelineError?.let { "云端已确认，但本机时间流写入失败：" + it },
                    )
                }
                else -> applyCloudFailure(result, "确认投票失败")
            }
        }
    }

    private fun runCloudAction(block: suspend () -> Unit) {
        if (mutableState.value.working) return
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            mutableState.update { it.copy(working = true, errorMessage = null, notice = null) }
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        status = TeamTimeStatus.ERROR_RETRYABLE,
                        working = false,
                        errorMessage = error.message ?: error::class.simpleName,
                    )
                }
            }
        }
    }

    private suspend fun reloadMembersBestEffort(repository: TeamTimeRepository, groupId: String) {
        when (val result = repository.listMembers(groupId)) {
            is CommunityResult.Success -> members = result.value
            else -> Unit
        }
    }

    private fun projectOwnSharingLevel(shared: Boolean) {
        val selfId = cloudUserId ?: return
        members = members.map { member ->
            if (member.cloudUserId == selfId) {
                member.copy(sharingLevel = if (shared) "BUSY_BLOCKS_30MIN" else "NONE")
            } else {
                member
            }
        }
    }

    private fun publishContent(
        status: TeamTimeStatus,
        selectedGroupId: String? = mutableState.value.selectedGroupId,
        errorMessage: String? = null,
        notice: String? = null,
        clearCandidates: Boolean = false,
    ) {
        val repositoryZone = zoneOrDefault()
        if (clearCandidates) candidateDomain = emptyList()
        val self = members.firstOrNull { it.cloudUserId == cloudUserId }
        val activeMembers = members.filter { it.isActive }
        val sharingCount = activeMembers.count { it.sharesBusyBlocks }
        val summary = shareSummary
        val shareUi = TeamTimeShareUi(
            shared = summary != null || self?.sharesBusyBlocks == true,
            statusLabel = if (summary != null || self?.sharesBusyBlocks == true) "已共享 30 分钟粒度忙闲" else "未共享忙闲",
            detailLabel = if (summary != null || self?.sharesBusyBlocks == true) {
                "群内 " + sharingCount + "/" + activeMembers.size + " 名成员已共享；查询只返回忙/闲/未知状态"
            } else {
                "未共享时你在交集中显示为未知；绝不会被当成空闲"
            },
            blockCount = summary?.blockCount ?: personalBusyBlocks.size,
            expiresLabel = summary?.expiresAt?.let { formatInstant(it, repositoryZone) },
        )
        mutableState.update { state ->
            val selected = selectedGroupId ?: state.selectedGroupId
            state.copy(
                status = status,
                groups = groups.map(::toGroupUi),
                selectedGroupId = selected,
                minAttendees = state.minAttendees.coerceIn(1, (activeMembers.size.takeIf { it > 0 } ?: 1)),
                activeMemberCount = activeMembers.size,
                sharingMemberCount = sharingCount,
                share = shareUi,
                localBusyBlockCount = personalBusyBlocks.size,
                queryWindowLabel = formatWindow(queryWindow, repositoryZone),
                candidates = candidateDomain.map { candidate ->
                    toCandidateUi(candidate, repositoryZone, state.candidates.firstOrNull { it.key == candidate.key }?.selected == true)
                },
                poll = pollDomain?.let { toPollUi(it, repositoryZone) },
                working = false,
                errorMessage = errorMessage,
                notice = notice,
            )
        }
    }

    private fun toGroupUi(group: TeamTimeGroup): TeamTimeGroupUi = TeamTimeGroupUi(
        id = group.groupId,
        name = group.name,
        typeLabel = when (group.type) {
            "study" -> "学习"
            "dorm" -> "宿舍"
            "club" -> "社团"
            "project" -> "项目"
            else -> group.type
        },
        memberCount = group.memberCount,
        roleLabel = when (group.myRole) {
            "OWNER" -> "群主"
            "ADMIN" -> "管理员"
            else -> "成员"
        },
    )

    private fun toCandidateUi(candidate: TeamTimeCandidate, zone: ZoneId, selected: Boolean): TeamTimeCandidateUi {
        val conflicts = personalBusyBlocks.any { it.start.isBefore(candidate.endAt) && it.end.isAfter(candidate.startAt) }
        return TeamTimeCandidateUi(
            key = candidate.key,
            timeLabel = formatRange(candidate.startAt, candidate.endAt, zone),
            quorumLabel = "可参加 " + candidate.availableMemberCount + "/" + candidate.totalMemberCount + " · 忙碌 " + candidate.busyMemberCount,
            unknownLabel = candidate.unknownMemberCount.takeIf { it > 0 }?.let { "未知 " + it + " 人（不计入可参加）" },
            personalLabel = if (conflicts) "与本机课表/日程冲突" else "本机课表/日程无冲突",
            personalConflicts = conflicts,
            selected = selected,
        )
    }

    private fun toPollUi(poll: TeamTimePoll, zone: ZoneId): TeamTimePollUi = TeamTimePollUi(
        pollId = poll.pollId,
        title = poll.title,
        status = poll.status,
        statusLabel = when (poll.status) {
            TeamTimePollStatus.OPEN -> "投票中"
            TeamTimePollStatus.CLOSED -> "已截止待确认"
            TeamTimePollStatus.CONFIRMED -> "已确认"
        },
        deadlineLabel = "截止 " + formatInstant(poll.votingDeadlineAt, zone),
        options = poll.options.map { option ->
            TeamTimePollOptionUi(
                optionId = option.optionId,
                timeLabel = formatRange(option.startAt, option.endAt, zone),
                voteLabel = option.voteCount.toString() + " 票",
                mine = poll.myVoteOptionId == option.optionId,
                confirmed = poll.confirmedOptionId == option.optionId,
            )
        },
        confirmedEventId = poll.confirmedEventId,
    )

    private fun applyCloudFailure(result: CommunityResult<*>, prefix: String) {
        val status = when (result) {
            CommunityResult.AuthenticationRequired -> TeamTimeStatus.NEEDS_LOGIN
            is CommunityResult.Disabled -> TeamTimeStatus.UNSUPPORTED
            is CommunityResult.Failure -> statusForError(result.error)
            is CommunityResult.Success -> TeamTimeStatus.ERROR_TERMINAL
        }
        mutableState.update {
            it.copy(
                status = status,
                working = false,
                errorMessage = resultMessage(result, prefix),
            )
        }
    }

    private fun statusForError(error: CommunityError): TeamTimeStatus = when {
        error.kind == CommunityErrorKind.NETWORK -> TeamTimeStatus.OFFLINE
        error.statusCode == 403 || error.code.contains("PERMISSION") -> TeamTimeStatus.NEEDS_PERMISSION
        error.retryable -> TeamTimeStatus.ERROR_RETRYABLE
        else -> TeamTimeStatus.ERROR_TERMINAL
    }

    private fun resultMessage(result: CommunityResult<*>, prefix: String): String = when (result) {
        CommunityResult.AuthenticationRequired -> "需要登录校园云账号后才能使用组队找时间"
        is CommunityResult.Disabled -> "当前群组云能力未开启：" + result.reason
        is CommunityResult.Failure -> prefix + "：" + result.error.message + "（" + result.error.code + "）"
        is CommunityResult.Success -> prefix
    }

    private suspend fun repository(): TeamTimeRepository = repositoryReady.await()

    private fun emit(effect: TeamTimeUiEffect) {
        check(mutableEffects.tryEmit(effect)) { "组队找时间事件缓冲已满" }
    }

    private fun computeDefaultWindow(): TimeWindow {
        val step = 1_800L
        val now = clock.instant().epochSecond
        val aligned = Math.floorDiv(now, step) * step
        val start = Instant.ofEpochSecond(aligned + step)
        return TimeWindow(start, start.plusSeconds(7L * 24L * 3600L))
    }

    private fun votingDeadlineFor(candidates: List<TeamTimeCandidate>): Instant {
        val earliest = candidates.minOf { it.startAt }
        val now = clock.instant()
        val preferred = earliest.minusSeconds(300)
        return when {
            preferred.isAfter(now.plusSeconds(30)) -> preferred
            earliest.isAfter(now.plusSeconds(60)) -> now.plusSeconds(30)
            else -> earliest
        }
    }

    private fun zoneOrDefault(): ZoneId = repositoryZone ?: ZoneId.of("Asia/Shanghai")

    private fun formatWindow(window: TimeWindow, zone: ZoneId): String =
        formatInstant(window.start, zone) + " 至 " + formatInstant(window.end, zone)

    private fun formatRange(start: Instant, end: Instant, zone: ZoneId): String =
        formatInstant(start, zone) + "–" + timeFormatter.withZone(zone).format(end)

    private fun formatInstant(instant: Instant, zone: ZoneId): String =
        dateTimeFormatter.withZone(zone).format(instant)

    companion object {
        private const val SHARE_TTL_SECONDS = 7 * 24 * 3600
        private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun factory(
            repositoryFactory: suspend () -> TeamTimeRepository,
            clock: Clock = Clock.systemUTC(),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(TeamTimeViewModel::class.java))
                return TeamTimeViewModel(repositoryFactory = repositoryFactory, clock = clock) as T
            }
        }
    }
}
