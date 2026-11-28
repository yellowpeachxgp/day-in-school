package cn.edu.haut.campus.feature.dorm.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.model.Staleness
import cn.edu.haut.campus.feature.dorm.DormDutySwap
import cn.edu.haut.campus.feature.dorm.DormDutyTask
import cn.edu.haut.campus.feature.dorm.DormDutyTaskStatus
import cn.edu.haut.campus.feature.dorm.DormMember
import cn.edu.haut.campus.feature.dorm.DormRepairTicket
import cn.edu.haut.campus.feature.dorm.DormSnapshot
import cn.edu.haut.campus.feature.dorm.state.DormUiAction
import cn.edu.haut.campus.feature.dorm.state.DormUiState

@Composable
internal fun DutyTab(state: DormUiState, snapshot: DormSnapshot?, onAction: (DormUiAction) -> Unit) {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        SectionTitle("创建值日轮班", "轮班由云端生成近期任务；换班/请假接受前不改派")
        OutlinedTextField(
            value = state.dutyRotaDraft.title,
            onValueChange = { onAction(DormUiAction.UpdateDutyRotaDraft(state.dutyRotaDraft.copy(title = it.take(80)))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("轮班标题") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            XPeachFilterPill(
                label = "每周",
                selected = state.dutyRotaDraft.weekly,
                compact = true,
                onClick = { onAction(DormUiAction.UpdateDutyRotaDraft(state.dutyRotaDraft.copy(weekly = true))) },
            )
            XPeachFilterPill(
                label = "每日",
                selected = !state.dutyRotaDraft.weekly,
                compact = true,
                onClick = { onAction(DormUiAction.UpdateDutyRotaDraft(state.dutyRotaDraft.copy(weekly = false))) },
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.dutyRotaDraft.leaveReason,
            onValueChange = { onAction(DormUiAction.UpdateDutyRotaDraft(state.dutyRotaDraft.copy(leaveReason = it.take(240)))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("请假/跳过审计原因") },
            minLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onAction(DormUiAction.CreateDutyRota) },
            enabled = !state.actionInFlight && (snapshot?.members?.count { it.isActive } ?: 0) >= 2,
            shape = RoundedCornerShape(12.dp),
        ) { Text("创建轮班") }
    }

    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        SectionTitle("值日任务", "完成需要再确认；SKIPPED 是请假/跳过审计，不算完成")
        val tasks = snapshot?.dutyTasks.orEmpty()
        if (tasks.isEmpty()) Text("暂无值日任务", style = MaterialTheme.typography.bodySmall)
        val currentMember = snapshot?.members?.firstOrNull { it.cloudUserId == state.currentCloudUserId && it.isActive }
        val swaps = snapshot?.dutySwaps.orEmpty()
        tasks.forEachIndexed { index, task ->
            if (index > 0) XPeachInsetDivider(verticalPadding = 8.dp)
            DutyTaskRow(
                task = task,
                members = snapshot?.members.orEmpty(),
                currentMember = currentMember,
                hasOpenSwap = swaps.any { it.dutyTaskId == task.taskId && it.status == "PENDING" },
                hasAcceptedSwap = swaps.any { it.dutyTaskId == task.taskId && it.status == "ACCEPTED" },
                leaveReason = state.dutyRotaDraft.leaveReason,
                actionInFlight = state.actionInFlight,
                onAction = onAction,
            )
        }
    }

    val pendingSwaps = snapshot?.dutySwaps.orEmpty().filter { it.status == "PENDING" }
    if (pendingSwaps.isNotEmpty()) {
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("待处理换班/代值", "接受后才会按服务端规则改派；拒绝或取消保留原任务")
            val currentMember = snapshot?.members?.firstOrNull { it.cloudUserId == state.currentCloudUserId && it.isActive }
            pendingSwaps.forEach { swap -> DutySwapRow(swap, snapshot?.members.orEmpty(), currentMember, state.actionInFlight, onAction) }
        }
    }
}

@Composable
private fun DutyTaskRow(
    task: DormDutyTask,
    members: List<DormMember>,
    currentMember: DormMember?,
    hasOpenSwap: Boolean,
    hasAcceptedSwap: Boolean,
    leaveReason: String,
    actionInFlight: Boolean,
    onAction: (DormUiAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(task.title + " · " + task.dueDate, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            XPeachStatusBadge(text = dutyStatusLabel(task.status))
        }
        Text("负责人：" + memberLabel(members, task.assignedMemberId), style = MaterialTheme.typography.labelSmall)
        task.completionNote?.let { Text("完成说明：" + it, style = MaterialTheme.typography.labelSmall) }
        task.confirmationNote?.let { Text("确认说明：" + it, style = MaterialTheme.typography.labelSmall) }
        task.skipReason?.let { Text("请假/跳过原因：" + it, style = MaterialTheme.typography.labelSmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (task.status == DormDutyTaskStatus.PENDING) {
                val isAssignee = currentMember?.memberId == task.assignedMemberId
                val canManage = (currentMember?.role ?: "") in setOf("OWNER", "ADMIN")
                val target = members.firstOrNull { it.isActive && it.memberId != task.assignedMemberId }
                TextButton(
                    enabled = !actionInFlight && isAssignee,
                    onClick = { onAction(DormUiAction.CompleteDutyTask(task.taskId)) },
                ) { Text(if (isAssignee) "完成" else "仅负责人完成") }
                if (target != null) {
                    val canRequest = isAssignee || canManage
                    TextButton(
                        enabled = !actionInFlight && canRequest && !hasOpenSwap && !hasAcceptedSwap,
                        onClick = { onAction(DormUiAction.RequestDutySwap(task.taskId, target.memberId)) },
                    ) { Text(if (hasOpenSwap || hasAcceptedSwap) "已有换班" else "请" + target.displayLabel + "代值") }
                }
                TextButton(
                    enabled = !actionInFlight && canManage && !hasAcceptedSwap,
                    onClick = { onAction(DormUiAction.SkipDutyTask(task.taskId, leaveReason)) },
                ) { Text("请假") }
            } else if (task.status == DormDutyTaskStatus.COMPLETED) {
                val canConfirm = currentMember != null && currentMember.memberId != task.assignedMemberId
                TextButton(
                    enabled = !actionInFlight && canConfirm,
                    onClick = { onAction(DormUiAction.ConfirmDutyTask(task.taskId)) },
                ) { Text(if (canConfirm) "确认完成" else "需其他成员确认") }
            }
        }
    }
}

@Composable
private fun DutySwapRow(swap: DormDutySwap, members: List<DormMember>, currentMember: DormMember?, actionInFlight: Boolean, onAction: (DormUiAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            memberLabel(members, swap.requestedByMemberId) + " 请求 " + memberLabel(members, swap.targetMemberId) + (if (swap.type == "COVER") " 代值" else " 换班"),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        swap.reason?.let { Text("原因：" + it, style = MaterialTheme.typography.labelSmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val canAcceptOrReject = currentMember != null &&
                (currentMember.memberId == swap.targetMemberId || currentMember.role in setOf("OWNER", "ADMIN"))
            val canCancel = currentMember?.memberId == swap.requestedByMemberId
            TextButton(enabled = !actionInFlight && canAcceptOrReject, onClick = { onAction(DormUiAction.ResolveDutySwap(swap.swapId, "ACCEPT")) }) { Text("接受") }
            TextButton(enabled = !actionInFlight && canAcceptOrReject, onClick = { onAction(DormUiAction.ResolveDutySwap(swap.swapId, "REJECT")) }) { Text("拒绝") }
            TextButton(enabled = !actionInFlight && canCancel, onClick = { onAction(DormUiAction.ResolveDutySwap(swap.swapId, "CANCEL")) }) { Text("取消") }
        }
    }
}

@Composable
internal fun RepairTab(state: DormUiState, snapshot: DormSnapshot?, onAction: (DormUiAction) -> Unit) {
    val draft = state.repairDraft
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        SectionTitle("创建报修协调单", "这里只协调宿舍内部处理，不声称学校或物业系统已受理")
        OutlinedTextField(
            value = draft.title,
            onValueChange = { onAction(DormUiAction.UpdateRepairDraft(draft.copy(title = it.take(120)))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("标题，例如 水龙头漏水") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repairCategoryLabels.forEach { (category, label) ->
                XPeachFilterPill(
                    label = label,
                    selected = draft.category == category,
                    compact = true,
                    onClick = { onAction(DormUiAction.UpdateRepairDraft(draft.copy(category = category))) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft.locationText,
            onValueChange = { onAction(DormUiAction.UpdateRepairDraft(draft.copy(locationText = it.take(120)))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("位置，例如 1 号楼 101 洗手间") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft.description,
            onValueChange = { onAction(DormUiAction.UpdateRepairDraft(draft.copy(description = it.take(2000)))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("问题描述") },
            minLines = 3,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onAction(DormUiAction.SubmitRepairTicket) },
            enabled = !state.actionInFlight && snapshot?.staleness != Staleness.STALE,
            shape = RoundedCornerShape(12.dp),
        ) { Text("创建协调单") }
    }

    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        SectionTitle("报修协调记录", "状态只代表群组内部协调进度")
        val repairs = snapshot?.repairs.orEmpty()
        if (repairs.isEmpty()) Text("暂无报修协调单", style = MaterialTheme.typography.bodySmall)
        val currentMember = snapshot?.members?.firstOrNull { it.cloudUserId == state.currentCloudUserId && it.isActive }
        repairs.forEachIndexed { index, repair ->
            if (index > 0) XPeachInsetDivider(verticalPadding = 8.dp)
            RepairRow(repair, snapshot?.members.orEmpty(), currentMember, state.actionInFlight, onAction)
        }
    }
}

@Composable
private fun RepairRow(repair: DormRepairTicket, members: List<DormMember>, currentMember: DormMember?, actionInFlight: Boolean, onAction: (DormUiAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(repair.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            XPeachStatusBadge(text = repairStatusLabel(repair.status))
        }
        Text(repair.locationText + " · " + (repairCategoryLabels[repair.category] ?: repair.category), style = MaterialTheme.typography.labelSmall)
        Text(repair.description, style = MaterialTheme.typography.bodySmall)
        Text(
            "报告人 " + memberLabel(members, repair.reporterMemberId) + (repair.assignedMemberId?.let { " · 协调人 " + memberLabel(members, it) } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        repair.resolutionNote?.let { Text("处理备注：" + it, style = MaterialTheme.typography.labelSmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val canManage = (currentMember?.role ?: "") in setOf("OWNER", "ADMIN")
            val transitions = nextRepairTransitions(repair.status).filter { it != "CLOSED" || repair.reviewId != null }
            transitions.forEach { next ->
                TextButton(
                    enabled = !actionInFlight && canManage,
                    onClick = { onAction(DormUiAction.TransitionRepair(repair.repairId, next)) },
                ) { Text(repairStatusLabel(next)) }
            }
            if (repair.status == "RESOLVED" && repair.reviewId == null) {
                val canReview = currentMember?.memberId == repair.reporterMemberId
                if (canReview) {
                    (1..5).forEach { rating ->
                        TextButton(
                            enabled = !actionInFlight,
                            onClick = { onAction(DormUiAction.ReviewRepair(repair.repairId, rating)) },
                        ) { Text(rating.toString() + "分") }
                    }
                } else {
                    TextButton(enabled = false, onClick = {}) { Text("待报告人评价") }
                }
            }
        }
    }
}
