package cn.edu.haut.campus.feature.dorm.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachEmptyState
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.model.Staleness
import cn.edu.haut.campus.feature.dorm.DormMember
import cn.edu.haut.campus.feature.dorm.DormSnapshot
import cn.edu.haut.campus.feature.dorm.DormUtilityReminder
import cn.edu.haut.campus.feature.dorm.state.DormScreenStatus
import cn.edu.haut.campus.feature.dorm.state.DormUiAction
import cn.edu.haut.campus.feature.dorm.state.DormUiState

@Composable
internal fun SectionTitle(title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun LoadingCard() {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator()
            Text("正在加载宿舍群组、账本、值日与报修…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun StatusCard(state: DormUiState, onAction: (DormUiAction) -> Unit) {
    val title = when (state.status) {
        DormScreenStatus.ERROR_RETRYABLE -> "暂时无法加载"
        DormScreenStatus.ERROR_NON_RETRYABLE -> "无法安全使用宿舍协作"
        DormScreenStatus.NEEDS_PERMISSION -> "需要授权"
        DormScreenStatus.NEEDS_LOGIN -> "需要登录校园云"
        DormScreenStatus.OFFLINE -> "当前离线"
        DormScreenStatus.UNSUPPORTED -> "当前环境不支持"
        else -> "状态提示"
    }
    XPeachEmptyState(
        title = title,
        subtitle = state.statusDetail ?: "请稍后重试",
        actionLabel = when {
            state.status == DormScreenStatus.NEEDS_LOGIN -> "返回"
            state.statusRetryable || state.status == DormScreenStatus.OFFLINE -> "重试"
            else -> null
        },
        onAction = {
            if (state.status == DormScreenStatus.NEEDS_LOGIN) onAction(DormUiAction.Back) else onAction(DormUiAction.Refresh)
        },
    )
    if (state.status == DormScreenStatus.NEEDS_LOGIN) {
        XPeachCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                "宿舍协作使用 XPeach 校园云群组账本；登录只用于授权访问云端协作数据，不会上传水电密码或执行支付。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun GroupOverviewCard(state: DormUiState, snapshot: DormSnapshot?, onAction: (DormUiAction) -> Unit) {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        val selected = snapshot?.groups?.firstOrNull { it.groupId == snapshot.selectedGroupId }
        val subtitle = if (selected == null) {
            "未选择群组"
        } else {
            "成员 " + selected.memberCount + " · 我的角色 " + selected.myRole +
                if (snapshot?.staleness == Staleness.STALE) " · 群组元数据为缓存" else ""
        }
        SectionTitle(selected?.name ?: "宿舍群组", subtitle)
        if ((snapshot?.groups?.size ?: 0) > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                snapshot?.groups?.forEach { group ->
                    XPeachFilterPill(
                        label = group.name,
                        selected = group.groupId == snapshot.selectedGroupId,
                        compact = true,
                        onClick = { onAction(DormUiAction.SelectGroup(group.groupId)) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun UtilityReminderCard(reminders: List<DormUtilityReminder>, onAction: (DormUiAction) -> Unit) {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                SectionTitle("水电余额联动", "只读取本机已授权水电数据；低余额提醒不代表已缴费或扣款")
            }
            TextButton(onClick = { onAction(DormUiAction.OpenElectricity) }) { Text("水电详情") }
        }
        if (reminders.isEmpty()) {
            Text("暂无水电读数。可先在宿舍水电页绑定房间或记录手工台账。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            reminders.forEachIndexed { index, reminder ->
                if (index > 0) XPeachInsetDivider(verticalPadding = 8.dp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(reminder.meterLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(reminder.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    XPeachStatusBadge(
                        text = (if (reminder.lowBalance) "低余额 " else "余额 ") + reminder.balanceText,
                        containerColor = if (reminder.lowBalance) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (reminder.lowBalance) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun MemberPillRow(members: List<DormMember>, selectedId: String?, onSelect: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        members.forEach { member ->
            XPeachFilterPill(
                label = member.displayLabel,
                selected = selectedId == member.memberId || (selectedId == null && members.firstOrNull()?.memberId == member.memberId),
                compact = true,
                onClick = { onSelect(member.memberId) },
            )
        }
    }
}
