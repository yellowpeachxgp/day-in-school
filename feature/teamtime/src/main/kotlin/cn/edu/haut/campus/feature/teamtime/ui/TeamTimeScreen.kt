package cn.edu.haut.campus.feature.teamtime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.feature.teamtime.TeamTimePollStatus
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeCandidateUi
import cn.edu.haut.campus.feature.teamtime.state.TeamTimePollUi
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeStatus
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeUiAction
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeUiState

/** 阶段 S 骨架 UI：M3 默认组件、完整状态入口，不做视觉打磨。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamTimeScreen(
    state: TeamTimeUiState,
    onAction: (TeamTimeUiAction) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("组队找时间") },
                navigationIcon = {
                    TextButton(onClick = { onAction(TeamTimeUiAction.BackClicked) }) { Text("返回") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.notice?.let { NoticeCard(text = it, error = false) }
            state.errorMessage?.let { NoticeCard(text = it, error = true) }
            when (state.status) {
                TeamTimeStatus.LOADING -> LoadingCard()
                TeamTimeStatus.EMPTY,
                TeamTimeStatus.CONTENT,
                TeamTimeStatus.PARTIAL,
                TeamTimeStatus.STALE -> TeamTimeContent(state, onAction)
                else -> TeamTimeStateCard(state, onAction)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TeamTimeContent(
    state: TeamTimeUiState,
    onAction: (TeamTimeUiAction) -> Unit,
) {
    if (state.status == TeamTimeStatus.PARTIAL || state.status == TeamTimeStatus.STALE) {
        Text(
            text = if (state.status == TeamTimeStatus.PARTIAL) "部分数据不可用；页面不会用猜测补齐。" else "群组列表可能已过期；成员忙闲不离线缓存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    GroupSection(state, onAction)
    if (state.selectedGroupId != null) {
        ShareSection(state, onAction)
        QuerySection(state, onAction)
        CandidatesSection(state, onAction)
        PollSection(state, onAction)
    } else {
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("还没有可用群组", style = MaterialTheme.typography.titleMedium)
                Text(
                    "先创建一个学习、项目或社团群组。群组成员邀请与管理沿用群组云能力；本页只处理共同空闲、投票和确认。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GroupSection(
    state: TeamTimeUiState,
    onAction: (TeamTimeUiAction) -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("群组", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            state.groups.forEach { group ->
                val selected = group.id == state.selectedGroupId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !state.working) { onAction(TeamTimeUiAction.GroupSelected(group.id)) }
                        .padding(vertical = 6.dp),
                ) {
                    Text((if (selected) "● " else "○ ") + group.name, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    Text(
                        group.typeLabel + " · " + group.memberCount + " 人 · 我的角色：" + group.roleLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            Text("创建群组", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = state.groupNameInput,
                onValueChange = { onAction(TeamTimeUiAction.GroupNameChanged(it)) },
                label = { Text("群组名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.working,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupTypeChip("study", "学习", state, onAction)
                GroupTypeChip("project", "项目", state, onAction)
                GroupTypeChip("club", "社团", state, onAction)
            }
            Button(
                onClick = { onAction(TeamTimeUiAction.CreateGroupClicked) },
                enabled = !state.working && state.groupNameInput.isNotBlank(),
            ) { Text("创建并选择") }
        }
    }
}

@Composable
private fun GroupTypeChip(
    type: String,
    label: String,
    state: TeamTimeUiState,
    onAction: (TeamTimeUiAction) -> Unit,
) {
    FilterChip(
        selected = state.groupTypeInput == type,
        onClick = { onAction(TeamTimeUiAction.GroupTypeSelected(type)) },
        label = { Text(label) },
        enabled = !state.working,
    )
}

@Composable
private fun ShareSection(
    state: TeamTimeUiState,
    onAction: (TeamTimeUiAction) -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("我的忙闲共享", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "硬约束：只上传 30 分钟粒度的忙碌时间块；不上传课程标题、地点、原因或备注。其他成员原始块也不会返回给你，交集只展示忙/闲/未知与人数。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(state.share.statusLabel, fontWeight = FontWeight.Medium)
            Text(state.share.detailLabel, style = MaterialTheme.typography.bodySmall)
            state.share.expiresLabel?.let {
                Text("共享到期：" + it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("未来 7 天本机量化忙块：" + state.localBusyBlockCount + " 个；成员共享：" + state.sharingMemberCount + "/" + state.activeMemberCount,
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAction(TeamTimeUiAction.ShareBusyBlocksClicked) },
                    enabled = !state.working,
                ) { Text(if (state.share.shared) "刷新共享" else "显式共享") }
                OutlinedButton(
                    onClick = { onAction(TeamTimeUiAction.WithdrawSharingClicked) },
                    enabled = !state.working && state.share.shared,
                ) { Text("撤回共享") }
            }
        }
    }
}

@Composable
private fun QuerySection(
    state: TeamTimeUiState,
    onAction: (TeamTimeUiAction) -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("共同空闲查询", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("查询窗口：" + state.queryWindowLabel, style = MaterialTheme.typography.bodySmall)
            Text("时长", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 90, 120).forEach { minutes ->
                    FilterChip(
                        selected = state.durationMinutes == minutes,
                        onClick = { onAction(TeamTimeUiAction.DurationSelected(minutes)) },
                        label = { Text(minutes.toString() + " 分钟") },
                        enabled = !state.working,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("至少可参加人数：" + state.minAttendees)
                OutlinedButton(
                    onClick = { onAction(TeamTimeUiAction.MinAttendeesChanged(state.minAttendees - 1)) },
                    enabled = !state.working && state.minAttendees > 1,
                ) { Text("−") }
                OutlinedButton(
                    onClick = { onAction(TeamTimeUiAction.MinAttendeesChanged(state.minAttendees + 1)) },
                    enabled = !state.working,
                ) { Text("+") }
            }
            Button(
                onClick = { onAction(TeamTimeUiAction.QueryAvailabilityClicked) },
                enabled = !state.working,
            ) { Text("查询共同空闲") }
        }
    }
}

@Composable
private fun CandidatesSection(
    state: TeamTimeUiState,
    onAction: (TeamTimeUiAction) -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("候选时段", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (state.candidates.isEmpty()) {
                Text(
                    "尚未查询或没有满足条件的时段。未知成员不会被当作空闲；候选只保留聚合计数。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.candidates.forEach { candidate -> CandidateCard(candidate, state.working, onAction) }
            if (state.candidates.isNotEmpty()) {
                HorizontalDivider()
                OutlinedTextField(
                    value = state.pollTitleInput,
                    onValueChange = { onAction(TeamTimeUiAction.PollTitleChanged(it)) },
                    label = { Text("投票标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.working,
                )
                Text("已选择 " + state.selectedCandidateCount + " 个候选；创建投票需要 2–10 个。", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { onAction(TeamTimeUiAction.CreatePollClicked) },
                    enabled = state.canCreatePoll,
                ) { Text("创建投票") }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: TeamTimeCandidateUi,
    working: Boolean,
    onAction: (TeamTimeUiAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !working) { onAction(TeamTimeUiAction.CandidateToggled(candidate.key)) }
            .padding(vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text((if (candidate.selected) "☑ " else "☐ ") + candidate.timeLabel, fontWeight = FontWeight.Medium)
        Text(candidate.quorumLabel, style = MaterialTheme.typography.bodySmall)
        candidate.unknownLabel?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            candidate.personalLabel,
            style = MaterialTheme.typography.bodySmall,
            color = if (candidate.personalConflicts) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PollSection(
    state: TeamTimeUiState,
    onAction: (TeamTimeUiAction) -> Unit,
) {
    val poll = state.poll ?: return
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("投票", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(poll.title, fontWeight = FontWeight.Medium)
            Text(poll.statusLabel + " · " + poll.deadlineLabel, style = MaterialTheme.typography.bodySmall)
            poll.options.forEach { option ->
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(option.timeLabel + (if (option.confirmed) " · 已确认" else ""), fontWeight = FontWeight.Medium)
                    Text(option.voteLabel + if (option.mine) " · 我的选择" else "", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (poll.status == TeamTimePollStatus.OPEN) {
                            OutlinedButton(
                                onClick = { onAction(TeamTimeUiAction.VoteClicked(option.optionId)) },
                                enabled = !state.working,
                            ) { Text(if (option.mine) "已投此项" else "投此项") }
                        }
                        if (poll.status != TeamTimePollStatus.CONFIRMED) {
                            Button(
                                onClick = { onAction(TeamTimeUiAction.ConfirmClicked(option.optionId)) },
                                enabled = !state.working,
                            ) { Text("确认此时段") }
                        }
                    }
                }
            }
            poll.confirmedEventId?.let {
                Text("云端事件：" + it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "确认后只写入你自己的时间流；成员之后调课产生的冲突只提示核对，不会自动改期。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TeamTimeStateCard(
    state: TeamTimeUiState,
    onAction: (TeamTimeUiAction) -> Unit,
) {
    val (title, body) = when (state.status) {
        TeamTimeStatus.ERROR_RETRYABLE -> "暂时无法使用" to (state.errorMessage ?: "校园云或本机时间流暂时不可用，可以重试。")
        TeamTimeStatus.ERROR_TERMINAL -> "无法使用组队找时间" to (state.errorMessage ?: "响应不符合契约或本地状态不可恢复。")
        TeamTimeStatus.NEEDS_PERMISSION -> "需要群组成员权限" to (state.errorMessage ?: "云端认为你不是该群 active 成员，或没有投票/忙闲权限。")
        TeamTimeStatus.NEEDS_LOGIN -> "需要登录校园云" to (state.errorMessage ?: "组队找时间需要 XPEACH 校园云会话；本地课表不会因登录而上传标题。")
        TeamTimeStatus.OFFLINE -> "当前离线" to (state.errorMessage ?: "忙闲、成员和投票不离线缓存；请恢复网络后重试。")
        TeamTimeStatus.UNSUPPORTED -> "暂不支持" to (state.errorMessage ?: "当前群组云能力未开启。")
        else -> "状态未知" to "无法确定当前状态。"
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.status == TeamTimeStatus.NEEDS_LOGIN) {
                    Button(onClick = { onAction(TeamTimeUiAction.LoginRequested) }) { Text("去登录") }
                }
                if (state.status == TeamTimeStatus.NEEDS_PERMISSION) {
                    OutlinedButton(onClick = { onAction(TeamTimeUiAction.PermissionRequested) }) { Text("了解权限") }
                }
                if (state.status == TeamTimeStatus.ERROR_RETRYABLE || state.status == TeamTimeStatus.OFFLINE) {
                    OutlinedButton(onClick = { onAction(TeamTimeUiAction.RetryClicked) }) { Text("重试") }
                }
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.width(14.dp))
            Text("正在加载群组与时间流…")
        }
    }
}

@Composable
private fun NoticeCard(text: String, error: Boolean) {
    Card {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
