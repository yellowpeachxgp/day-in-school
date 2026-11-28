package cn.edu.haut.campus.feature.dorm

import cn.edu.haut.campus.core.model.Staleness

/** W4-08 宿舍群组概要；member_id 是 membership-period id，绝不是 cloud user id。 */
data class DormGroupSummary(
    val groupId: String,
    val schoolId: String,
    val name: String,
    val description: String?,
    val memberCount: Int,
    val myRole: String,
)

data class DormMember(
    val memberId: String,
    val cloudUserId: String,
    val displayName: String?,
    val role: String,
    val status: String,
) {
    val isActive: Boolean get() = status == "ACTIVE"
    val displayLabel: String get() = displayName?.takeIf { it.isNotBlank() } ?: "成员 " + memberId.takeLast(4)
}

enum class DormSplitRuleType { EQUAL, SELECTED, RATIOS, STAY_DAYS }

/** 契约线账本分摊规则；权重和入住天数均为非负整数，零表示不参与。 */
data class DormSplitRule(
    val type: DormSplitRuleType,
    val participantMemberIds: List<String> = emptyList(),
    val ratioWeights: Map<String, Long> = emptyMap(),
    val stayDays: Map<String, Long> = emptyMap(),
) {
    init {
        when (type) {
            DormSplitRuleType.EQUAL -> require(participantMemberIds.isEmpty() && ratioWeights.isEmpty() && stayDays.isEmpty()) {
                "EQUAL 不允许附带参与人、比例或入住天数"
            }
            DormSplitRuleType.SELECTED -> require(participantMemberIds.isNotEmpty() && ratioWeights.isEmpty() && stayDays.isEmpty()) {
                "SELECTED 必须且只能包含参与人"
            }
            DormSplitRuleType.RATIOS -> require(ratioWeights.isNotEmpty() && participantMemberIds.isEmpty() && stayDays.isEmpty()) {
                "RATIOS 必须且只能包含比例权重"
            }
            DormSplitRuleType.STAY_DAYS -> require(stayDays.isNotEmpty() && participantMemberIds.isEmpty() && ratioWeights.isEmpty()) {
                "STAY_DAYS 必须且只能包含入住天数"
            }
        }
        require(participantMemberIds.distinct().size == participantMemberIds.size) { "参与人不得重复" }
        require(ratioWeights.values.all { it >= 0L }) { "比例权重不得为负" }
        require(stayDays.values.all { it >= 0L }) { "入住天数不得为负" }
    }
}

data class DormSplitShare(val participantMemberId: String, val amountFen: Long)

enum class DormLedgerEntryKind { EXPENSE, REFUND, CORRECTION }

/** 只追加账本记录。amountFen 非零；正负方向只描述账，不描述支付。 */
data class DormLedgerEntry(
    val entryId: String,
    val groupId: String,
    val schoolId: String,
    val kind: DormLedgerEntryKind,
    val correctsEntryId: String?,
    val correctionReason: String?,
    val title: String,
    val note: String?,
    val amountFen: Long,
    val paidByMemberId: String,
    val occurredAt: String,
    val splitRule: DormSplitRule,
    val shares: List<DormSplitShare>,
    val createdByMemberId: String,
    val createdAt: String,
) {
    init {
        require(amountFen != 0L) { "账本金额不能为 0 分" }
    }
}

data class DormMemberBalance(
    val memberId: String,
    val postingCreditFen: Long,
    val shareCostFen: Long,
    val outboundSettlementFen: Long,
    val inboundSettlementFen: Long,
    val balanceFen: Long,
)

data class DormLedgerBalances(
    val groupId: String,
    val balanceRevision: Long,
    val generatedAt: String,
    val zeroSumCheckFen: Long,
    val members: List<DormMemberBalance>,
)

enum class DormSettlementStatus {
    PENDING_BOTH_CONFIRMATIONS,
    PENDING_DEBTOR_CONFIRMATION,
    PENDING_CREDITOR_CONFIRMATION,
    CONFIRMED,
    CANCELLED,
}

/** 两方确认结算记录。CONFIRMED 仍然只是账本确认，不是支付证明。 */
data class DormSettlement(
    val settlementId: String,
    val debtorMemberId: String,
    val creditorMemberId: String,
    val amountFen: Long,
    val basisBalanceRevision: Long,
    val status: DormSettlementStatus,
    val note: String?,
    val createdAt: String,
    val debtorConfirmedAt: String?,
    val creditorConfirmedAt: String?,
    val confirmedAt: String?,
    val cancelledAt: String?,
) {
    init { require(amountFen > 0L) { "结算金额必须严格为正" } }
}

data class DormDutyRota(
    val rotaId: String,
    val title: String,
    val description: String?,
    val rotationMemberIds: List<String>,
    val frequency: String,
    val daysOfWeek: List<Int>,
    val startDate: String,
    val endDate: String?,
    val timeZone: String,
    val status: String,
    val generatedThroughDate: String?,
)

enum class DormDutyTaskStatus { PENDING, COMPLETED, CONFIRMED, SKIPPED }

data class DormDutyTask(
    val taskId: String,
    val rotaId: String,
    val title: String,
    val assignedMemberId: String,
    val dueDate: String,
    val status: DormDutyTaskStatus,
    val completionNote: String?,
    val confirmationNote: String?,
    val skipReason: String?,
)

data class DormDutySwap(
    val swapId: String,
    val rotaId: String,
    val dutyTaskId: String,
    val type: String,
    val requestedByMemberId: String,
    val targetMemberId: String,
    val reason: String?,
    val status: String,
    val resolutionNote: String?,
)

/** 宿舍内部报修协调单；状态绝不声称学校/物业系统已受理或完成。 */
data class DormRepairTicket(
    val repairId: String,
    val title: String,
    val category: String,
    val locationText: String,
    val description: String,
    val reporterMemberId: String,
    val assignedMemberId: String?,
    val status: String,
    val resolutionNote: String?,
    val reviewId: String?,
    val createdAt: String,
    val updatedAt: String,
)

data class DormUtilityReminder(
    val meterLabel: String,
    val utilityKind: String,
    val balanceText: String,
    val lowBalance: Boolean,
    val observedAtEpochMillis: Long?,
    val detail: String,
)

/** 一个宿舍群组协作快照。协作明细不离线持久化；staleness 只能来自 W4-05 群组元数据缓存。 */
data class DormSnapshot(
    val groups: List<DormGroupSummary>,
    val selectedGroupId: String?,
    val members: List<DormMember> = emptyList(),
    val ledgerEntries: List<DormLedgerEntry> = emptyList(),
    val balances: DormLedgerBalances? = null,
    val settlements: List<DormSettlement> = emptyList(),
    val rotas: List<DormDutyRota> = emptyList(),
    val dutyTasks: List<DormDutyTask> = emptyList(),
    val dutySwaps: List<DormDutySwap> = emptyList(),
    val repairs: List<DormRepairTicket> = emptyList(),
    val staleness: Staleness = Staleness.FRESH,
    val cachedAtEpochMillis: Long? = null,
    val partialWarnings: List<String> = emptyList(),
)

fun formatFenAsYuan(fen: Long): String {
    val negative = fen < 0L
    val magnitude = if (negative) -fen else fen
    val text = (magnitude / 100L).toString() + "." + (magnitude % 100L).toString().padStart(2, '0')
    return if (negative) "-¥" + text else "¥" + text
}
