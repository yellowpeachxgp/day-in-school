package cn.edu.haut.campus.feature.teamtime.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import cn.edu.haut.campus.core.designsystem.HautCampusTheme
import cn.edu.haut.campus.feature.teamtime.TeamTimePollStatus
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeCandidateUi
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeGroupUi
import cn.edu.haut.campus.feature.teamtime.state.TeamTimePollOptionUi
import cn.edu.haut.campus.feature.teamtime.state.TeamTimePollUi
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeShareUi
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeStatus
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeUiState
import cn.edu.haut.campus.feature.teamtime.ui.TeamTimeScreen

/** W4-07 组队找时间骨架 UI 状态夹具；覆盖阶段 S 完整 11 态矩阵。 */
object TeamTimePreviewFixtures {
    private val sampleGroups = listOf(
        TeamTimeGroupUi(
            id = "group-study",
            name = "期末互助小组",
            typeLabel = "学习",
            memberCount = 4,
            roleLabel = "群主",
        ),
        TeamTimeGroupUi(
            id = "group-project",
            name = "课程设计项目组",
            typeLabel = "项目",
            memberCount = 3,
            roleLabel = "成员",
        ),
    )
    private val sampleCandidates = listOf(
        TeamTimeCandidateUi(
            key = "2026-12-01T10:00:00Z/2026-12-01T11:00:00Z",
            timeLabel = "12-01 18:00–19:00",
            quorumLabel = "可参加 4/4 · 忙碌 0",
            unknownLabel = null,
            personalLabel = "本机课表/日程无冲突",
            personalConflicts = false,
            selected = true,
        ),
        TeamTimeCandidateUi(
            key = "2026-12-02T11:30:00Z/2026-12-02T12:30:00Z",
            timeLabel = "12-02 19:30–20:30",
            quorumLabel = "可参加 3/4 · 忙碌 0",
            unknownLabel = "未知 1 人（不计入可参加）",
            personalLabel = "与本机课表/日程冲突",
            personalConflicts = true,
            selected = true,
        ),
    )
    private val samplePoll = TeamTimePollUi(
        pollId = "poll-preview",
        title = "期末复习碰头",
        status = TeamTimePollStatus.OPEN,
        statusLabel = "投票中",
        deadlineLabel = "截止 12-01 17:55",
        options = listOf(
            TeamTimePollOptionUi(
                optionId = "option-a",
                timeLabel = "12-01 18:00–19:00",
                voteLabel = "2 票",
                mine = true,
                confirmed = false,
            ),
            TeamTimePollOptionUi(
                optionId = "option-b",
                timeLabel = "12-02 19:30–20:30",
                voteLabel = "1 票",
                mine = false,
                confirmed = false,
            ),
        ),
        confirmedEventId = null,
    )

    private val content = TeamTimeUiState(
        status = TeamTimeStatus.CONTENT,
        groups = sampleGroups,
        selectedGroupId = "group-study",
        groupNameInput = "",
        groupTypeInput = "study",
        pollTitleInput = "期末复习碰头",
        durationMinutes = 60,
        minAttendees = 3,
        activeMemberCount = 4,
        sharingMemberCount = 3,
        share = TeamTimeShareUi(
            shared = true,
            statusLabel = "已共享 30 分钟粒度忙闲",
            detailLabel = "群内 3/4 名成员已共享；查询只返回忙/闲/未知状态",
            blockCount = 9,
            expiresLabel = "12-07 20:00",
        ),
        localBusyBlockCount = 9,
        queryWindowLabel = "12-01 10:00 至 12-08 10:00",
        candidates = sampleCandidates,
        poll = samplePoll,
    )

    val states: Map<TeamTimeStatus, TeamTimeUiState> = linkedMapOf(
        TeamTimeStatus.LOADING to content.copy(status = TeamTimeStatus.LOADING, groups = emptyList(), candidates = emptyList(), poll = null),
        TeamTimeStatus.EMPTY to content.copy(status = TeamTimeStatus.EMPTY, groups = emptyList(), selectedGroupId = null, candidates = emptyList(), poll = null),
        TeamTimeStatus.CONTENT to content,
        TeamTimeStatus.PARTIAL to content.copy(status = TeamTimeStatus.PARTIAL, errorMessage = "成员状态暂时不可用（预览）"),
        TeamTimeStatus.STALE to content.copy(status = TeamTimeStatus.STALE, notice = "群组列表来自本地快照（预览）"),
        TeamTimeStatus.ERROR_RETRYABLE to content.copy(status = TeamTimeStatus.ERROR_RETRYABLE, errorMessage = "校园云暂时不可用（预览）"),
        TeamTimeStatus.ERROR_TERMINAL to content.copy(status = TeamTimeStatus.ERROR_TERMINAL, errorMessage = "云端响应不符合契约（预览）"),
        TeamTimeStatus.NEEDS_PERMISSION to content.copy(status = TeamTimeStatus.NEEDS_PERMISSION, errorMessage = "你已不是该群 active 成员（预览）"),
        TeamTimeStatus.NEEDS_LOGIN to content.copy(status = TeamTimeStatus.NEEDS_LOGIN, errorMessage = "需要登录校园云账号（预览）"),
        TeamTimeStatus.OFFLINE to content.copy(status = TeamTimeStatus.OFFLINE, errorMessage = "网络不可用（预览）"),
        TeamTimeStatus.UNSUPPORTED to content.copy(status = TeamTimeStatus.UNSUPPORTED, errorMessage = "群组云能力未开启（预览）"),
    )

    fun stateFor(status: TeamTimeStatus): TeamTimeUiState = states.getValue(status)
}

@Composable
internal fun TeamTimePreviewHarness(state: TeamTimeUiState) {
    HautCampusTheme(
        darkTheme = false,
        themeMode = "LIGHT",
        uiDesignStyleKey = "FLUTTER_M3",
        dynamicColor = false,
        themePaletteKey = "HAUT_BLUE",
    ) {
        TeamTimeScreen(state = state, onAction = {})
    }
}

@Preview(name = "组队找时间 Content", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun TeamTimeContentPreview() {
    TeamTimePreviewHarness(TeamTimePreviewFixtures.stateFor(TeamTimeStatus.CONTENT))
}

@Preview(name = "组队找时间 Empty", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun TeamTimeEmptyPreview() {
    TeamTimePreviewHarness(TeamTimePreviewFixtures.stateFor(TeamTimeStatus.EMPTY))
}

@Preview(name = "组队找时间 NeedsLogin", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun TeamTimeNeedsLoginPreview() {
    TeamTimePreviewHarness(TeamTimePreviewFixtures.stateFor(TeamTimeStatus.NEEDS_LOGIN))
}
