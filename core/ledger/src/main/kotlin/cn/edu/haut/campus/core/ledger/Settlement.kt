package cn.edu.haut.campus.core.ledger

import java.time.Instant

/** 结算记录中承担应付的一方或应收的一方。 */
enum class SettlementParty {
    DEBTOR,
    CREDITOR,
}

/**
 * 结算记录状态。
 *
 * CONFIRMED 只表示债务人与债权人都在账本上确认了这笔结清记录；它不是支付成功、
 * 转账成功或钱包扣款成功的证明。本模块没有也不应有任何支付执行入口。
 */
enum class SettlementStatus {
    PENDING_BOTH_CONFIRMATIONS,
    PENDING_DEBTOR_CONFIRMATION,
    PENDING_CREDITOR_CONFIRMATION,
    CONFIRMED,
    CANCELLED,
}

enum class SettlementBlockReason {
    PARTY_MISMATCH,
    INVALID_STATE,
}

sealed interface SettlementTransition {
    data class Applied(val settlement: Settlement) : SettlementTransition
    data class Blocked(val reason: SettlementBlockReason, val detail: String) : SettlementTransition
}

/**
 * 两方确认的记账型结算状态机。
 *
 * 合法流转：PENDING_BOTH_CONFIRMATIONS 任一方确认后进入等待另一方确认的状态；
 * 另一方也确认后进入终态 CONFIRMED。在完成前任一方可取消并进入终态 CANCELLED。
 * 重复确认同一方是幂等空操作； outsiders、终态后的确认/取消都会被拒绝。
 *
 * 金额必须严格为正。负向调整应另建冲正账记录，不允许用负结算金额改写方向。
 */
class Settlement private constructor(
    val id: SettlementId,
    val debtor: ParticipantId,
    val creditor: ParticipantId,
    val amount: Money,
    val createdAt: Instant,
    val debtorConfirmedAt: Instant?,
    val creditorConfirmedAt: Instant?,
    val confirmedAt: Instant?,
    val cancelledAt: Instant?,
    val cancelledBy: SettlementParty?,
) {
    val status: SettlementStatus
        get() = when {
            cancelledAt != null -> SettlementStatus.CANCELLED
            debtorConfirmedAt != null && creditorConfirmedAt != null -> SettlementStatus.CONFIRMED
            debtorConfirmedAt != null -> SettlementStatus.PENDING_CREDITOR_CONFIRMATION
            creditorConfirmedAt != null -> SettlementStatus.PENDING_DEBTOR_CONFIRMATION
            else -> SettlementStatus.PENDING_BOTH_CONFIRMATIONS
        }

    val isTerminal: Boolean
        get() = status == SettlementStatus.CONFIRMED || status == SettlementStatus.CANCELLED

    val confirmedParties: Set<SettlementParty>
        get() {
            val parties = linkedSetOf<SettlementParty>()
            if (debtorConfirmedAt != null) parties += SettlementParty.DEBTOR
            if (creditorConfirmedAt != null) parties += SettlementParty.CREDITOR
            return parties
        }

    fun partyOf(actor: ParticipantId): SettlementParty? = when (actor) {
        debtor -> SettlementParty.DEBTOR
        creditor -> SettlementParty.CREDITOR
        else -> null
    }

    /** 由 debtor 或 creditor 本人确认；重复确认同一方返回原记录，保证重试幂等。 */
    fun confirm(actor: ParticipantId, at: Instant): SettlementTransition {
        val party = partyOf(actor) ?: return SettlementTransition.Blocked(
            SettlementBlockReason.PARTY_MISMATCH,
            "only the debtor or creditor can confirm this settlement",
        )
        when (status) {
            SettlementStatus.CANCELLED -> return SettlementTransition.Blocked(
                SettlementBlockReason.INVALID_STATE,
                "a cancelled settlement cannot be confirmed",
            )
            SettlementStatus.CONFIRMED -> return SettlementTransition.Blocked(
                SettlementBlockReason.INVALID_STATE,
                "a confirmed record cannot be confirmed again",
            )
            else -> Unit
        }

        val alreadyConfirmed = when (party) {
            SettlementParty.DEBTOR -> debtorConfirmedAt != null
            SettlementParty.CREDITOR -> creditorConfirmedAt != null
        }
        if (alreadyConfirmed) {
            return SettlementTransition.Applied(this)
        }

        val nextDebtorConfirmedAt = if (party == SettlementParty.DEBTOR) at else debtorConfirmedAt
        val nextCreditorConfirmedAt = if (party == SettlementParty.CREDITOR) at else creditorConfirmedAt
        val nowConfirmed = nextDebtorConfirmedAt != null && nextCreditorConfirmedAt != null
        return SettlementTransition.Applied(
            Settlement(
                id = id,
                debtor = debtor,
                creditor = creditor,
                amount = amount,
                createdAt = createdAt,
                debtorConfirmedAt = nextDebtorConfirmedAt,
                creditorConfirmedAt = nextCreditorConfirmedAt,
                confirmedAt = if (nowConfirmed) at else null,
                cancelledAt = cancelledAt,
                cancelledBy = cancelledBy,
            ),
        )
    }

    /** 在双方完成确认前，任一方都可以取消这条账记录；取消不删除历史。 */
    fun cancel(actor: ParticipantId, at: Instant): SettlementTransition {
        val party = partyOf(actor) ?: return SettlementTransition.Blocked(
            SettlementBlockReason.PARTY_MISMATCH,
            "only the debtor or creditor can cancel this settlement",
        )
        return when (status) {
            SettlementStatus.CONFIRMED -> SettlementTransition.Blocked(
                SettlementBlockReason.INVALID_STATE,
                "a confirmed record is terminal and cannot be cancelled",
            )
            SettlementStatus.CANCELLED -> SettlementTransition.Blocked(
                SettlementBlockReason.INVALID_STATE,
                "the settlement has already been cancelled",
            )
            else -> SettlementTransition.Applied(
                Settlement(
                    id = id,
                    debtor = debtor,
                    creditor = creditor,
                    amount = amount,
                    createdAt = createdAt,
                    debtorConfirmedAt = debtorConfirmedAt,
                    creditorConfirmedAt = creditorConfirmedAt,
                    confirmedAt = null,
                    cancelledAt = at,
                    cancelledBy = party,
                ),
            )
        }
    }

    companion object {
        fun propose(
            id: SettlementId,
            debtor: ParticipantId,
            creditor: ParticipantId,
            amount: Money,
            createdAt: Instant,
        ): Settlement {
            if (debtor == creditor) {
                throw LedgerException(
                    LedgerErrorCode.SAME_SETTLEMENT_PARTIES,
                    "debtor and creditor must be different participants",
                )
            }
            if (!amount.isPositive) {
                throw LedgerException(
                    LedgerErrorCode.NON_POSITIVE_SETTLEMENT_AMOUNT,
                    "settlement amount must be strictly positive fen",
                )
            }
            return Settlement(
                id = id,
                debtor = debtor,
                creditor = creditor,
                amount = amount,
                createdAt = createdAt,
                debtorConfirmedAt = null,
                creditorConfirmedAt = null,
                confirmedAt = null,
                cancelledAt = null,
                cancelledBy = null,
            )
        }
    }
}
