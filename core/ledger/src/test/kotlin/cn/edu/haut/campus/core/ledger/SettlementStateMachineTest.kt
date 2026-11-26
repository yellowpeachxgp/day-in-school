package cn.edu.haut.campus.core.ledger

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettlementStateMachineTest {

    private val debtor = ParticipantId("debtor")
    private val creditor = ParticipantId("creditor")
    private val outsider = ParticipantId("outsider")
    private val t0: Instant = Instant.parse("2026-11-27T00:00:00Z")
    private val t1: Instant = Instant.parse("2026-11-27T00:01:00Z")
    private val t2: Instant = Instant.parse("2026-11-27T00:02:00Z")
    private val t3: Instant = Instant.parse("2026-11-27T00:03:00Z")

    @Test
    fun proposalStartsPendingBothConfirmations() {
        val settlement = propose()

        assertEquals(SettlementStatus.PENDING_BOTH_CONFIRMATIONS, settlement.status)
        assertEquals(emptySet<SettlementParty>(), settlement.confirmedParties)
        assertFalse(settlement.isTerminal)
        assertNull(settlement.confirmedAt)
    }

    @Test
    fun debtorThenCreditorConfirmationSettlesOnlyAfterBothSides() {
        val settlement = propose()
        val afterDebtor = applied(settlement.confirm(debtor, t1))

        assertEquals(SettlementStatus.PENDING_CREDITOR_CONFIRMATION, afterDebtor.status)
        assertEquals(setOf(SettlementParty.DEBTOR), afterDebtor.confirmedParties)
        assertFalse(afterDebtor.isTerminal)
        assertNull(afterDebtor.confirmedAt)

        val settled = applied(afterDebtor.confirm(creditor, t2))
        assertEquals(SettlementStatus.CONFIRMED, settled.status)
        assertEquals(setOf(SettlementParty.DEBTOR, SettlementParty.CREDITOR), settled.confirmedParties)
        assertTrue(settled.isTerminal)
        assertEquals(t2, settled.confirmedAt)
    }

    @Test
    fun creditorThenDebtorConfirmationUsesTheSameTwoPartyRule() {
        val settlement = propose()
        val afterCreditor = applied(settlement.confirm(creditor, t1))
        assertEquals(SettlementStatus.PENDING_DEBTOR_CONFIRMATION, afterCreditor.status)

        val settled = applied(afterCreditor.confirm(debtor, t2))
        assertEquals(SettlementStatus.CONFIRMED, settled.status)
        assertEquals(t2, settled.confirmedAt)
    }

    @Test
    fun repeatedConfirmationBySamePartyIsIdempotentAndCannotCompleteSettlement() {
        val settlement = propose()
        val afterDebtor = applied(settlement.confirm(debtor, t1))
        val repeated = afterDebtor.confirm(debtor, t2)

        assertTrue(repeated is SettlementTransition.Applied)
        assertSame(afterDebtor, (repeated as SettlementTransition.Applied).settlement)
        assertEquals(SettlementStatus.PENDING_CREDITOR_CONFIRMATION, afterDebtor.status)
    }

    @Test
    fun outsiderCannotConfirmOrCancel() {
        val settlement = propose()

        val confirm = settlement.confirm(outsider, t1)
        assertTrue(confirm is SettlementTransition.Blocked)
        assertEquals(SettlementBlockReason.PARTY_MISMATCH, (confirm as SettlementTransition.Blocked).reason)

        val cancel = settlement.cancel(outsider, t1)
        assertTrue(cancel is SettlementTransition.Blocked)
        assertEquals(SettlementBlockReason.PARTY_MISMATCH, (cancel as SettlementTransition.Blocked).reason)
    }

    @Test
    fun cancellationBeforeCompletionIsTerminalAndKeepsPriorConfirmationEvidence() {
        val settlement = propose()
        val afterDebtor = applied(settlement.confirm(debtor, t1))
        val cancelled = applied(afterDebtor.cancel(creditor, t2))

        assertEquals(SettlementStatus.CANCELLED, cancelled.status)
        assertEquals(SettlementParty.CREDITOR, cancelled.cancelledBy)
        assertEquals(t2, cancelled.cancelledAt)
        assertEquals(t1, cancelled.debtorConfirmedAt)
        assertTrue(cancelled.isTerminal)

        val confirmAfterCancel = cancelled.confirm(creditor, t3)
        assertTrue(confirmAfterCancel is SettlementTransition.Blocked)
        assertEquals(SettlementBlockReason.INVALID_STATE, (confirmAfterCancel as SettlementTransition.Blocked).reason)
    }

    @Test
    fun confirmedRecordIsTerminalForBothConfirmationAndCancellation() {
        val settled = applied(applied(propose().confirm(debtor, t1)).confirm(creditor, t2))

        val confirmAgain = settled.confirm(debtor, t3)
        assertTrue(confirmAgain is SettlementTransition.Blocked)
        assertEquals(SettlementBlockReason.INVALID_STATE, (confirmAgain as SettlementTransition.Blocked).reason)

        val cancel = settled.cancel(creditor, t3)
        assertTrue(cancel is SettlementTransition.Blocked)
        assertEquals(SettlementBlockReason.INVALID_STATE, (cancel as SettlementTransition.Blocked).reason)
    }

    @Test
    fun zeroAndNegativeSettlementAmountsAreRejected() {
        val zeroError = assertThrows(LedgerException::class.java) {
            propose(amount = Money.ZERO)
        }
        assertEquals(LedgerErrorCode.NON_POSITIVE_SETTLEMENT_AMOUNT, zeroError.code)

        val negativeError = assertThrows(LedgerException::class.java) {
            propose(amount = Money(-1L))
        }
        assertEquals(LedgerErrorCode.NON_POSITIVE_SETTLEMENT_AMOUNT, negativeError.code)
    }

    @Test
    fun debtorAndCreditorMustBeDifferent() {
        val error = assertThrows(LedgerException::class.java) {
            Settlement.propose(
                id = SettlementId("settlement-1"),
                debtor = debtor,
                creditor = debtor,
                amount = Money(1L),
                createdAt = t0,
            )
        }
        assertEquals(LedgerErrorCode.SAME_SETTLEMENT_PARTIES, error.code)
    }

    private fun propose(amount: Money = Money(100L)): Settlement = Settlement.propose(
        id = SettlementId("settlement-1"),
        debtor = debtor,
        creditor = creditor,
        amount = amount,
        createdAt = t0,
    )

    private fun applied(transition: SettlementTransition): Settlement {
        assertTrue(transition is SettlementTransition.Applied)
        return (transition as SettlementTransition.Applied).settlement
    }
}
