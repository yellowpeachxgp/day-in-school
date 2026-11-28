package cn.edu.haut.campus.feature.dorm.ui

import cn.edu.haut.campus.feature.dorm.DormDutyTaskStatus
import cn.edu.haut.campus.feature.dorm.DormMember
import cn.edu.haut.campus.feature.dorm.DormSettlementStatus
import cn.edu.haut.campus.feature.dorm.DormSplitRuleType
import cn.edu.haut.campus.feature.dorm.state.DormUiAction
import cn.edu.haut.campus.feature.dorm.state.LedgerEntryDraft

internal val splitTypeLabels = linkedMapOf(
    DormSplitRuleType.EQUAL to "全员均摊",
    DormSplitRuleType.SELECTED to "指定成员",
    DormSplitRuleType.RATIOS to "比例",
    DormSplitRuleType.STAY_DAYS to "入住天数",
)

internal val repairCategoryLabels = linkedMapOf(
    "PLUMBING" to "上下水",
    "ELECTRICAL" to "电路",
    "FURNITURE" to "家具",
    "NETWORK" to "网络",
    "CLEANING" to "清洁",
    "SAFETY" to "安全",
    "OTHER" to "其他",
)

internal fun memberLabel(members: List<DormMember>, memberId: String): String =
    members.firstOrNull { it.memberId == memberId }?.displayLabel ?: ("成员 " + memberId.takeLast(4))

internal fun settlementStatusLabel(status: DormSettlementStatus): String = when (status) {
    DormSettlementStatus.PENDING_BOTH_CONFIRMATIONS -> "待两方确认"
    DormSettlementStatus.PENDING_DEBTOR_CONFIRMATION -> "待债务方确认"
    DormSettlementStatus.PENDING_CREDITOR_CONFIRMATION -> "待债权方确认"
    DormSettlementStatus.CONFIRMED -> "已确认（非支付）"
    DormSettlementStatus.CANCELLED -> "已取消"
}

internal fun dutyStatusLabel(status: DormDutyTaskStatus): String = when (status) {
    DormDutyTaskStatus.PENDING -> "待完成"
    DormDutyTaskStatus.COMPLETED -> "待确认"
    DormDutyTaskStatus.CONFIRMED -> "已确认"
    DormDutyTaskStatus.SKIPPED -> "已请假"
}

internal fun repairStatusLabel(status: String): String = when (status) {
    "OPEN" -> "待协调"
    "TRIAGED" -> "已分派"
    "IN_PROGRESS" -> "处理中"
    "RESOLVED" -> "已解决"
    "REJECTED" -> "不处理"
    "CLOSED" -> "已关闭"
    else -> status
}

internal fun nextRepairTransitions(status: String): List<String> = when (status) {
    "OPEN" -> listOf("TRIAGED", "REJECTED")
    "TRIAGED" -> listOf("IN_PROGRESS", "REJECTED")
    "IN_PROGRESS" -> listOf("RESOLVED")
    "RESOLVED" -> listOf("CLOSED")
    else -> emptyList()
}

internal fun updateLedgerDraft(
    onAction: (DormUiAction) -> Unit,
    draft: LedgerEntryDraft,
    transform: LedgerEntryDraft.() -> LedgerEntryDraft,
) {
    onAction(DormUiAction.UpdateLedgerDraft(draft.transform()))
}
