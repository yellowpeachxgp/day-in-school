package cn.edu.haut.campus.core.ledger

import java.math.BigInteger

/**
 * 确定性费用分摊器。
 *
 * 固定余数规则（云端必须使用同一规则）：先按精确有理数（总金额 × 权重 ÷ 权重和）
 * 向下取整；再把未分完的 1 分单位按 fractional remainder 较大者优先分配。余数相同
 * 时按参与人 ID 的字典序升序优先。输出本身也按参与人 ID 升序排列，因此结果与输入顺序无关。
 *
 * 负金额表示冲正/退款方向的账记录：先对其绝对值执行同一规则，再给所有份额加负号；
 * 这样正负两个方向都严格满足总额守恒。分摊只产生账记录，不触发支付。
 */
object ExpenseSplitter {

    fun split(total: Money, members: Collection<ParticipantId>, rule: SplitRule): List<SplitShare> {
        val memberSet = validateMembers(members)
        val weights = positiveWeightsFor(rule, memberSet)
        return allocate(total, weights)
    }

    private fun validateMembers(members: Collection<ParticipantId>): Set<ParticipantId> {
        val list = members.toList()
        if (list.isEmpty()) {
            throw LedgerException(LedgerErrorCode.EMPTY_PARTICIPANTS, "at least one participant is required")
        }
        val counts = linkedMapOf<ParticipantId, Int>()
        for (id in list) {
            counts[id] = (counts[id] ?: 0) + 1
        }
        val duplicates = counts.filterValues { it > 1 }.keys.sorted()
        if (duplicates.isNotEmpty()) {
            throw LedgerException(
                LedgerErrorCode.DUPLICATE_PARTICIPANT,
                "duplicate participant ids: " + duplicates.joinToString(","),
            )
        }
        return counts.keys
    }

    private fun positiveWeightsFor(rule: SplitRule, members: Set<ParticipantId>): Map<ParticipantId, Long> = when (rule) {
        SplitRule.Equal -> members.associateWith { 1L }
        is SplitRule.Selected -> {
            validateKnown(rule.participantIds, members)
            if (rule.participantIds.isEmpty()) {
                throw noEffectiveParticipants()
            }
            rule.participantIds.associateWith { 1L }
        }
        is SplitRule.Ratios -> positiveWeights(
            raw = rule.weights,
            members = members,
            invalidCode = LedgerErrorCode.INVALID_RATIO_WEIGHT,
            label = "ratio weight",
        )
        is SplitRule.StayDays -> positiveWeights(
            raw = rule.days,
            members = members,
            invalidCode = LedgerErrorCode.INVALID_STAY_DAYS,
            label = "stay days",
        )
    }

    private fun positiveWeights(
        raw: Map<ParticipantId, Long>,
        members: Set<ParticipantId>,
        invalidCode: LedgerErrorCode,
        label: String,
    ): Map<ParticipantId, Long> {
        validateKnown(raw.keys, members)
        val negative = raw.filterValues { it < 0L }.keys.sorted()
        if (negative.isNotEmpty()) {
            throw LedgerException(
                invalidCode,
                label + " must not be negative for: " + negative.joinToString(","),
            )
        }
        val positive = raw.filterValues { it > 0L }
        if (positive.isEmpty()) {
            throw noEffectiveParticipants()
        }
        return positive
    }

    private fun validateKnown(ids: Collection<ParticipantId>, members: Set<ParticipantId>) {
        val unknown = ids.filter { it !in members }.sorted()
        if (unknown.isNotEmpty()) {
            throw LedgerException(
                LedgerErrorCode.UNKNOWN_PARTICIPANT,
                "participants are not members of this split: " + unknown.joinToString(","),
            )
        }
    }

    private fun noEffectiveParticipants(): LedgerException = LedgerException(
        LedgerErrorCode.NO_EFFECTIVE_PARTICIPANTS,
        "the split rule selects no participant with a positive weight",
    )

    private fun allocate(total: Money, weights: Map<ParticipantId, Long>): List<SplitShare> {
        val participants = weights.keys.sorted()
        if (total.isZero) {
            return participants.map { SplitShare(it, Money.ZERO) }
        }

        val negative = total.isNegative
        val magnitude = BigInteger.valueOf(total.fen).abs()
        val denominator = participants.fold(BigInteger.ZERO) { acc, id ->
            acc.add(BigInteger.valueOf(weights.getValue(id)))
        }
        val rows = participants.map { id ->
            val numerator = magnitude.multiply(BigInteger.valueOf(weights.getValue(id)))
            AllocationBase(
                participantId = id,
                base = numerator.divide(denominator),
                remainder = numerator.remainder(denominator),
            )
        }
        val baseSum = rows.fold(BigInteger.ZERO) { acc, row -> acc.add(row.base) }
        val extra = magnitude.subtract(baseSum)
        if (extra.signum() < 0 || extra.compareTo(BigInteger.valueOf(rows.size.toLong())) >= 0) {
            error("largest-remainder invariant violated")
        }
        val extraCount = extra.intValueExact()
        val priority = rows.sortedWith(
            compareByDescending<AllocationBase> { it.remainder }
                .thenBy { it.participantId },
        )
        val extraRecipients = priority.take(extraCount).map { it.participantId }.toSet()

        return rows.map { row ->
            var shareMagnitude = row.base
            if (row.participantId in extraRecipients) {
                shareMagnitude = shareMagnitude.add(BigInteger.ONE)
            }
            val signedShare = if (negative) shareMagnitude.negate() else shareMagnitude
            val fen = try {
                signedShare.longValueExact()
            } catch (error: ArithmeticException) {
                throw LedgerException(
                    LedgerErrorCode.ARITHMETIC_OVERFLOW,
                    "split share overflowed signed 64-bit fen",
                    error,
                )
            }
            SplitShare(row.participantId, Money(fen))
        }
    }
}

private data class AllocationBase(
    val participantId: ParticipantId,
    val base: BigInteger,
    val remainder: BigInteger,
)
