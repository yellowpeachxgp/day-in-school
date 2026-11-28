package cn.edu.haut.campus.feature.dorm.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.feature.dorm.DormLedgerEntry
import cn.edu.haut.campus.feature.dorm.DormLedgerEntryKind
import cn.edu.haut.campus.feature.dorm.DormMember
import cn.edu.haut.campus.feature.dorm.DormSettlement
import cn.edu.haut.campus.feature.dorm.DormSettlementStatus
import cn.edu.haut.campus.feature.dorm.DormSnapshot
import cn.edu.haut.campus.feature.dorm.DormSplitRuleType
import cn.edu.haut.campus.feature.dorm.buildDormSplitRule
import cn.edu.haut.campus.feature.dorm.calculateDormShares
import cn.edu.haut.campus.feature.dorm.formatFenAsYuan
import cn.edu.haut.campus.feature.dorm.parseYuanToFen
import cn.edu.haut.campus.feature.dorm.state.DormUiAction
import cn.edu.haut.campus.feature.dorm.state.DormUiState
import cn.edu.haut.campus.feature.dorm.state.LedgerEntryDraft

@Composable
internal fun LedgerTab(state: DormUiState, snapshot: DormSnapshot?, onAction: (DormUiAction) -> Unit) {
    LedgerEntryEditor(state, snapshot, onAction)
    SettlementSection(state, snapshot, onAction)
    LedgerHistorySection(snapshot, onAction)
}

@Composable
private fun LedgerEntryEditor(state: DormUiState, snapshot: DormSnapshot?, onAction: (DormUiAction) -> Unit) {
    val members = snapshot?.members?.filter { it.isActive }.orEmpty()
    val draft = state.ledgerDraft
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        SectionTitle("追加账本记录", "金额精确到分；历史只追加更正，绝不改写或执行支付")
        OutlinedTextField(
            value = draft.title,
            onValueChange = { value -> updateLedgerDraft(onAction, draft) { copy(title = value.take(120)) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("标题，例如 公共洗衣液") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft.amountYuan,
            onValueChange = { value -> updateLedgerDraft(onAction, draft) { copy(amountYuan = value.take(24)) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("金额（元；负数表示退款/冲正方向）") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft.note,
            onValueChange = { value -> updateLedgerDraft(onAction, draft) { copy(note = value.take(500)) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("备注（可选）") },
            minLines = 2,
        )
        Spacer(Modifier.height(10.dp))
        Text("垫付人", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        MemberPillRow(members, draft.paidByMemberId) { memberId ->
            updateLedgerDraft(onAction, draft) { copy(paidByMemberId = memberId) }
        }
        Spacer(Modifier.height(8.dp))
        Text("分摊方式", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            splitTypeLabels.forEach { (type, label) ->
                XPeachFilterPill(
                    label = label,
                    selected = draft.splitType == type,
                    compact = true,
                    onClick = { updateLedgerDraft(onAction, draft) { copy(splitType = type) } },
                )
            }
        }
        when (draft.splitType) {
            DormSplitRuleType.SELECTED -> SelectedMemberEditor(members, draft, onAction)
            DormSplitRuleType.RATIOS -> WeightInputs("整数比例权重（0 或留空为不参与）", members, draft.ratioWeightTexts) { memberId, value ->
                updateLedgerDraft(onAction, draft) { copy(ratioWeightTexts = ratioWeightTexts + (memberId to value)) }
            }
            DormSplitRuleType.STAY_DAYS -> WeightInputs("入住天数（0 或留空为不参与）", members, draft.stayDayTexts) { memberId, value ->
                updateLedgerDraft(onAction, draft) { copy(stayDayTexts = stayDayTexts + (memberId to value)) }
            }
            DormSplitRuleType.EQUAL -> Unit
        }
        SplitPreview(draft, members)
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onAction(DormUiAction.SubmitLedgerEntry) },
            enabled = !state.actionInFlight && members.isNotEmpty(),
            shape = RoundedCornerShape(12.dp),
        ) { Text("追加记账（不付款）") }
    }
}

@Composable
private fun SelectedMemberEditor(members: List<DormMember>, draft: LedgerEntryDraft, onAction: (DormUiAction) -> Unit) {
    Spacer(Modifier.height(8.dp))
    Text("选择参与人", style = MaterialTheme.typography.labelSmall)
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        members.forEach { member ->
            val selected = member.memberId in draft.selectedParticipantIds
            XPeachFilterPill(
                label = member.displayLabel,
                selected = selected,
                compact = true,
                onClick = {
                    updateLedgerDraft(onAction, draft) {
                        copy(
                            selectedParticipantIds = if (selected) {
                                selectedParticipantIds - member.memberId
                            } else {
                                selectedParticipantIds + member.memberId
                            },
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun WeightInputs(title: String, members: List<DormMember>, values: Map<String, String>, onValue: (String, String) -> Unit) {
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.labelSmall)
    members.forEach { member ->
        OutlinedTextField(
            value = values[member.memberId].orEmpty(),
            onValueChange = { onValue(member.memberId, it.filter(Char::isDigit).take(7)) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            label = { Text(member.displayLabel) },
            singleLine = true,
        )
    }
}

@Composable
private fun SplitPreview(draft: LedgerEntryDraft, members: List<DormMember>) {
    val preview = remember(draft, members) {
        runCatching {
            val amount = parseYuanToFen(draft.amountYuan)
            val activeIds = members.map { it.memberId }
            val rule = buildDormSplitRule(
                type = draft.splitType,
                activeMemberIds = activeIds.toSet(),
                selectedParticipantIds = draft.selectedParticipantIds,
                ratioWeightTexts = draft.ratioWeightTexts,
                stayDayTexts = draft.stayDayTexts,
            )
            calculateDormShares(amount, rule, activeIds)
        }.getOrNull()
    }
    if (preview != null && members.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            "largest-remainder 预览：" + preview.joinToString("；") { share ->
                memberLabel(members, share.participantMemberId) + " " + formatFenAsYuan(share.amountFen)
            },
            style = MaterialTheme.typography.labelSmall,
            color = XPeachTheme.readablePrimary,
        )
    }
}

@Composable
private fun SettlementSection(state: DormUiState, snapshot: DormSnapshot?, onAction: (DormUiAction) -> Unit) {
    val members = snapshot?.members.orEmpty()
    val balances = snapshot?.balances
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        SectionTitle("余额与两方确认结算", "CONFIRMED 只表示两方确认了一次账本记录，绝不表示支付成功")
        if (balances == null || balances.members.isEmpty()) {
            Text("暂无余额信息", style = MaterialTheme.typography.bodySmall)
        } else {
            balances.members.forEach { balance ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(memberLabel(members, balance.memberId), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatFenAsYuan(balance.balanceFen),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (balance.balanceFen < 0L) MaterialTheme.colorScheme.error else XPeachTheme.readablePrimary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = { onAction(DormUiAction.ProposeSettlement) },
                enabled = !state.actionInFlight,
                shape = RoundedCornerShape(12.dp),
            ) { Text("按当前余额提出结算") }
        }
        snapshot?.settlements.orEmpty().forEach { settlement ->
            XPeachInsetDivider(verticalPadding = 10.dp)
            SettlementRow(settlement, members, state.currentCloudUserId, balances?.balanceRevision, state.actionInFlight, onAction)
        }
    }
}

@Composable
private fun SettlementRow(settlement: DormSettlement, members: List<DormMember>, currentCloudUserId: String?, currentBalanceRevision: Long?, actionInFlight: Boolean, onAction: (DormUiAction) -> Unit) {
    val currentMemberId = members.firstOrNull { it.cloudUserId == currentCloudUserId }?.memberId
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                memberLabel(members, settlement.debtorMemberId) + " → " + memberLabel(members, settlement.creditorMemberId),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            XPeachStatusBadge(text = settlementStatusLabel(settlement.status))
        }
        Text(
            formatFenAsYuan(settlement.amountFen) + " · revision " + settlement.basisBalanceRevision + " · 只记账不付款",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "确认进度：债务方" + (if (settlement.debtorConfirmedAt != null) "✓" else "待确认") + " · 债权方" + (if (settlement.creditorConfirmedAt != null) "✓" else "待确认"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val staleBasis = currentBalanceRevision != null && currentBalanceRevision != settlement.basisBalanceRevision
        if (staleBasis) {
            Text(
                "账本已更正/变化：此结算基于旧 revision，需两方重新确认或重新提出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (settlement.status != DormSettlementStatus.CONFIRMED && settlement.status != DormSettlementStatus.CANCELLED) {
            val currentPartyConfirmed = when (currentMemberId) {
                settlement.debtorMemberId -> settlement.debtorConfirmedAt != null
                settlement.creditorMemberId -> settlement.creditorConfirmedAt != null
                else -> true
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onAction(DormUiAction.ConfirmSettlement(settlement.settlementId)) },
                    enabled = !actionInFlight && !staleBasis && currentMemberId != null && !currentPartyConfirmed,
                    shape = RoundedCornerShape(10.dp),
                ) { Text(if (currentPartyConfirmed) "本方已确认" else "确认到账记录") }
                TextButton(onClick = { onAction(DormUiAction.CancelSettlement(settlement.settlementId)) }, enabled = !actionInFlight) { Text("取消") }
            }
        }
    }
}

@Composable
private fun LedgerHistorySection(snapshot: DormSnapshot?, onAction: (DormUiAction) -> Unit) {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        SectionTitle("账本历史", "更正记录与原记录同时保留，修改可追溯并重新确认")
        val entries = snapshot?.ledgerEntries.orEmpty()
        if (entries.isEmpty()) Text("暂无账本记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        entries.forEachIndexed { index, entry ->
            if (index > 0) XPeachInsetDivider(verticalPadding = 8.dp)
            LedgerEntryRow(entry, snapshot?.members.orEmpty(), onAction)
        }
    }
}

@Composable
private fun LedgerEntryRow(entry: DormLedgerEntry, members: List<DormMember>, onAction: (DormUiAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(entry.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            XPeachStatusBadge(
                text = when (entry.kind) {
                    DormLedgerEntryKind.EXPENSE -> "支出"
                    DormLedgerEntryKind.REFUND -> "退款"
                    DormLedgerEntryKind.CORRECTION -> "更正"
                },
                containerColor = if (entry.kind == DormLedgerEntryKind.CORRECTION) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
            )
        }
        Text(
            formatFenAsYuan(entry.amountFen) + " · 垫付 " + memberLabel(members, entry.paidByMemberId) + " · " + entry.occurredAt.take(10),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "分摊：" + entry.shares.joinToString("；") { memberLabel(members, it.participantMemberId) + " " + formatFenAsYuan(it.amountFen) },
            style = MaterialTheme.typography.labelSmall,
        )
        entry.correctionReason?.let { Text("更正原因：" + it, style = MaterialTheme.typography.labelSmall, color = XPeachTheme.readablePrimary) }
        entry.correctsEntryId?.let { Text("更正链：" + it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (entry.kind != DormLedgerEntryKind.CORRECTION) {
            TextButton(onClick = { onAction(DormUiAction.ReverseLedgerEntry(entry.entryId)) }) { Text("追加全额冲正") }
        }
    }
}
