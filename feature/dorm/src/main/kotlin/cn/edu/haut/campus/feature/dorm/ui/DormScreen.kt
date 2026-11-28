package cn.edu.haut.campus.feature.dorm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachEmptyState
import cn.edu.haut.campus.core.designsystem.XPeachFeedbackBanner
import cn.edu.haut.campus.core.designsystem.XPeachFeedbackTone
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.feature.dorm.state.DormScreenStatus
import cn.edu.haut.campus.feature.dorm.state.DormTab
import cn.edu.haut.campus.feature.dorm.state.DormUiAction
import cn.edu.haut.campus.feature.dorm.state.DormUiState

/** W4-08 宿舍协作骨架 UI。M3 默认组件为主，完整覆盖 11 态矩阵，不做视觉打磨。 */
@Composable
fun DormScreen(
    state: DormUiState,
    onAction: (DormUiAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        XPeachTopBar(
            title = "宿舍协作",
            subtitle = "只记账不收付款 · 值日与报修协调",
            badgeText = when (state.status) {
                DormScreenStatus.STALE -> "缓存"
                DormScreenStatus.PARTIAL -> "部分"
                else -> null
            },
            navigationIcon = {
                XPeachIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = { onAction(DormUiAction.Back) },
                )
            },
            actions = {
                XPeachFilterPill(
                    label = if (state.loading) "加载中" else "刷新",
                    selected = false,
                    compact = true,
                    onClick = { if (!state.loading && !state.actionInFlight) onAction(DormUiAction.Refresh) },
                )
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            XPeachFeedbackBanner(
                message = state.notice,
                tone = XPeachFeedbackTone.INFO,
                onDismiss = { onAction(DormUiAction.ConsumeNotice) },
            )
            if (state.actionInFlight) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    Text("正在提交云端协作变更…", style = MaterialTheme.typography.labelMedium)
                }
            }

            when (state.status) {
                DormScreenStatus.LOADING -> LoadingCard()
                DormScreenStatus.EMPTY -> EmptyDormContent(state, onAction)
                DormScreenStatus.CONTENT,
                DormScreenStatus.PARTIAL,
                DormScreenStatus.STALE -> DormContent(state, onAction)
                DormScreenStatus.ERROR_RETRYABLE,
                DormScreenStatus.ERROR_NON_RETRYABLE,
                DormScreenStatus.NEEDS_PERMISSION,
                DormScreenStatus.NEEDS_LOGIN,
                DormScreenStatus.OFFLINE,
                DormScreenStatus.UNSUPPORTED -> StatusCard(state, onAction)
            }
            Spacer(modifier = Modifier.height(72.dp))
        }
    }
}

@Composable
private fun EmptyDormContent(state: DormUiState, onAction: (DormUiAction) -> Unit) {
    XPeachEmptyState(
        title = "还没有宿舍群组",
        subtitle = state.statusDetail ?: "创建宿舍群组并邀请室友后，即可开始只记账、值日轮班和报修协调。",
    )
    GroupCreateCard(state, onAction)
}

@Composable
private fun DormContent(state: DormUiState, onAction: (DormUiAction) -> Unit) {
    val snapshot = state.snapshot
    if (state.status != DormScreenStatus.CONTENT) {
        XPeachFeedbackBanner(
            message = state.statusDetail,
            tone = if (state.status == DormScreenStatus.STALE) XPeachFeedbackTone.WARNING else XPeachFeedbackTone.INFO,
        )
    }
    GroupOverviewCard(state, snapshot, onAction)
    UtilityReminderCard(state.utilityReminders, onAction)
    XPeachSegmentedTabs(
        items = DormTab.entries.map { it.label },
        selectedIndex = DormTab.entries.indexOf(state.selectedTab).coerceAtLeast(0),
        onSelect = { index -> onAction(DormUiAction.SelectTab(DormTab.entries[index])) },
    )
    when (state.selectedTab) {
        DormTab.LEDGER -> LedgerTab(state, snapshot, onAction)
        DormTab.DUTY -> DutyTab(state, snapshot, onAction)
        DormTab.REPAIRS -> RepairTab(state, snapshot, onAction)
    }
}

@Composable
private fun GroupCreateCard(state: DormUiState, onAction: (DormUiAction) -> Unit) {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        SectionTitle("创建宿舍群组", "仅创建云端协作群组；成员邀请与角色管理沿用群组能力")
        OutlinedTextField(
            value = state.newGroupName,
            onValueChange = { onAction(DormUiAction.UpdateNewGroupName(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("群组名称，例如 1 号楼 101") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onAction(DormUiAction.CreateDormGroup) },
            enabled = !state.actionInFlight && state.newGroupName.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
        ) { Text("创建宿舍群组") }
    }
}
