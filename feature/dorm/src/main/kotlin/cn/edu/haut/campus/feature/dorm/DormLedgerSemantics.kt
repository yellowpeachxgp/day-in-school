package cn.edu.haut.campus.feature.dorm

import cn.edu.haut.campus.core.community.LedgerRatioWeightInput
import cn.edu.haut.campus.core.community.LedgerSplitRuleInput
import cn.edu.haut.campus.core.community.LedgerStayDaysInput
import cn.edu.haut.campus.core.ledger.ExpenseSplitter
import cn.edu.haut.campus.core.ledger.Money
import cn.edu.haut.campus.core.ledger.ParticipantId
import cn.edu.haut.campus.core.ledger.SplitRule
import java.math.BigDecimal

/** 十进制字符串精确转分；不使用浮点，最多两位小数，且拒绝零与 Long.MIN_VALUE（避免 UI 取负溢出）。 */
fun parseYuanToFen(input: String, requirePositive: Boolean = false): Long {
    val text = input.trim().removePrefix("¥").trim()
    require(text.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"))) { "金额必须是最多两位小数的十进制元" }
    val decimal = try {
        BigDecimal(text)
    } catch (error: NumberFormatException) {
        throw IllegalArgumentException("金额必须是有效十进制元", error)
    }
    require(decimal.stripTrailingZeros().scale() <= 2) { "金额最多精确到分（两位小数）" }
    val fen = try {
        decimal.movePointRight(2).longValueExact()
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("金额超出账本整数分范围", error)
    }
    require(fen != 0L) { "账本金额不能为 0 分" }
    require(fen != Long.MIN_VALUE) { "账本金额超出可展示/冲正范围" }
    if (requirePositive) require(fen > 0L) { "结算金额必须严格为正" }
    return fen
}

/** UI/ViewModel 共用的草稿转换，保证预览与提交使用完全相同的分摊规则构造。 */
fun buildDormSplitRule(
    type: DormSplitRuleType,
    activeMemberIds: Collection<String>,
    selectedParticipantIds: Set<String>,
    ratioWeightTexts: Map<String, String>,
    stayDayTexts: Map<String, String>,
): DormSplitRule = when (type) {
    DormSplitRuleType.EQUAL -> DormSplitRule(DormSplitRuleType.EQUAL)
    DormSplitRuleType.SELECTED -> {
        val selected = selectedParticipantIds.filter { it in activeMemberIds }.sorted()
        require(selected.isNotEmpty()) { "请至少选择一名分摊成员" }
        DormSplitRule(DormSplitRuleType.SELECTED, participantMemberIds = selected)
    }
    DormSplitRuleType.RATIOS -> {
        val weights = activeMemberIds.mapNotNull { memberId ->
            val raw = ratioWeightTexts[memberId]?.trim().orEmpty()
            if (raw.isEmpty() || raw == "0") {
                null
            } else {
                val weight = raw.toLongOrNull() ?: throw IllegalArgumentException("比例权重必须是非负整数")
                require(weight >= 0L) { "比例权重必须是非负整数" }
                memberId to weight
            }
        }.toMap()
        require(weights.values.any { it > 0L }) { "比例分摊至少需要一个正权重" }
        DormSplitRule(DormSplitRuleType.RATIOS, ratioWeights = weights)
    }
    DormSplitRuleType.STAY_DAYS -> {
        val days = activeMemberIds.mapNotNull { memberId ->
            val raw = stayDayTexts[memberId]?.trim().orEmpty()
            if (raw.isEmpty() || raw == "0") {
                null
            } else {
                val value = raw.toLongOrNull() ?: throw IllegalArgumentException("入住天数必须是非负整数")
                require(value >= 0L) { "入住天数必须是非负整数" }
                memberId to value
            }
        }.toMap()
        require(days.values.any { it > 0L }) { "入住天数分摊至少需要一个正天数" }
        DormSplitRule(DormSplitRuleType.STAY_DAYS, stayDays = days)
    }
}

fun DormSplitRule.toCoreLedgerRule(): SplitRule = when (type) {
    DormSplitRuleType.EQUAL -> SplitRule.Equal
    DormSplitRuleType.SELECTED -> SplitRule.Selected(participantMemberIds.map(::ParticipantId).toSet())
    DormSplitRuleType.RATIOS -> SplitRule.Ratios(ratioWeights.mapKeys { (id, _) -> ParticipantId(id) })
    DormSplitRuleType.STAY_DAYS -> SplitRule.StayDays(stayDays.mapKeys { (id, _) -> ParticipantId(id) })
}

fun DormSplitRule.toCommunityInput(): LedgerSplitRuleInput = when (type) {
    DormSplitRuleType.EQUAL -> LedgerSplitRuleInput(type = "EQUAL")
    DormSplitRuleType.SELECTED -> LedgerSplitRuleInput(type = "SELECTED", participantMemberIds = participantMemberIds)
    DormSplitRuleType.RATIOS -> LedgerSplitRuleInput(
        type = "RATIOS",
        ratioWeights = ratioWeights.entries.sortedBy { it.key }.map { (memberId, weight) ->
            LedgerRatioWeightInput(memberId = memberId, weight = weight)
        },
    )
    DormSplitRuleType.STAY_DAYS -> LedgerSplitRuleInput(
        type = "STAY_DAYS",
        stayDays = stayDays.entries.sortedBy { it.key }.map { (memberId, days) ->
            LedgerStayDaysInput(memberId = memberId, days = days)
        },
    )
}

/** 直接复用 core:ledger 的唯一分摊实现；activeMemberIds 是规则评估时的全体成员集合。 */
fun calculateDormShares(
    amountFen: Long,
    rule: DormSplitRule,
    activeMemberIds: Collection<String>,
): List<DormSplitShare> = ExpenseSplitter.split(
    total = Money.ofFen(amountFen),
    members = activeMemberIds.map(::ParticipantId),
    rule = rule.toCoreLedgerRule(),
).map { share -> DormSplitShare(share.participantId.value, share.amount.fen) }

/** 校验云端账本记录的跨端不变量：总额守恒、输出按 member id 升序、正负方向不得混号。 */
fun validateDormLedgerEntryInvariants(entry: DormLedgerEntry, knownMemberIds: Set<String>? = null) {
    require(entry.shares.isNotEmpty()) { "账本记录缺少分摊结果" }
    require(entry.shares.map { it.participantMemberId } == entry.shares.map { it.participantMemberId }.sorted()) {
        "分摊结果必须按 participant_member_id 升序"
    }
    require(entry.shares.distinctBy { it.participantMemberId }.size == entry.shares.size) { "分摊结果参与人重复" }
    if (knownMemberIds != null) {
        val unknown = entry.shares.map { it.participantMemberId }.filter { it !in knownMemberIds }
        require(unknown.isEmpty()) { "分摊结果包含未知成员: " + unknown.joinToString(",") }
    }
    val sum = entry.shares.fold(0L) { acc, share -> Math.addExact(acc, share.amountFen) }
    require(sum == entry.amountFen) { "分摊总额不守恒" }
    if (entry.amountFen > 0L) {
        require(entry.shares.all { it.amountFen >= 0L }) { "正金额分摊不得含负份额" }
    } else {
        require(entry.shares.all { it.amountFen <= 0L }) { "负金额分摊不得含正份额" }
    }
}

/** 新建/预览路径可做严格重算；历史 EQUAL 记录可能依赖当时成员快照，不能用当前成员强行重算。 */
fun validateSharesMatchCoreLedger(
    amountFen: Long,
    rule: DormSplitRule,
    activeMemberIds: Collection<String>,
    expectedShares: List<DormSplitShare>,
) {
    val actual = calculateDormShares(amountFen, rule, activeMemberIds)
    require(actual == expectedShares) {
        "云端分摊结果与 core:ledger largest-remainder 语义不一致"
    }
}

fun dormSettlementStatusFromWire(value: String): DormSettlementStatus = when (value) {
    "PENDING_BOTH_CONFIRMATIONS" -> DormSettlementStatus.PENDING_BOTH_CONFIRMATIONS
    "PENDING_DEBTOR_CONFIRMATION" -> DormSettlementStatus.PENDING_DEBTOR_CONFIRMATION
    "PENDING_CREDITOR_CONFIRMATION" -> DormSettlementStatus.PENDING_CREDITOR_CONFIRMATION
    "CONFIRMED" -> DormSettlementStatus.CONFIRMED
    "CANCELLED" -> DormSettlementStatus.CANCELLED
    else -> throw IllegalArgumentException("未知结算状态: " + value)
}

fun dormDutyTaskStatusFromWire(value: String): DormDutyTaskStatus = when (value) {
    "PENDING" -> DormDutyTaskStatus.PENDING
    "COMPLETED" -> DormDutyTaskStatus.COMPLETED
    "CONFIRMED" -> DormDutyTaskStatus.CONFIRMED
    "SKIPPED" -> DormDutyTaskStatus.SKIPPED
    else -> throw IllegalArgumentException("未知值日任务状态: " + value)
}
