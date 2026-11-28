package cn.edu.haut.campus.feature.dorm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DormLedgerSemanticsTest {
    @Test
    fun parseYuanToFenUsesExactIntegerCents() {
        assertEquals(1000L, parseYuanToFen("10"))
        assertEquals(1001L, parseYuanToFen("10.01"))
        assertEquals(-235L, parseYuanToFen("-2.35"))
        assertEquals(1L, parseYuanToFen("¥0.01", requirePositive = true))
    }

    @Test
    fun parseYuanToFenRejectsZeroFractionalOverflowAndNonPositiveSettlement() {
        assertThrows(IllegalArgumentException::class.java) { parseYuanToFen("0") }
        assertThrows(IllegalArgumentException::class.java) { parseYuanToFen("1.001") }
        assertThrows(IllegalArgumentException::class.java) { parseYuanToFen("999999999999999999999") }
        assertThrows(IllegalArgumentException::class.java) { parseYuanToFen("-0.01", requirePositive = true) }
    }

    @Test
    fun negativeAmountUsesAbsThenNegatesEveryLargestRemainderShare() {
        val members = listOf("a", "b", "c")
        val positive = calculateDormShares(1000L, DormSplitRule(DormSplitRuleType.EQUAL), members)
        val negative = calculateDormShares(-1000L, DormSplitRule(DormSplitRuleType.EQUAL), members)
        assertEquals(listOf(334L, 333L, 333L), positive.map { it.amountFen })
        assertEquals(positive.map { -it.amountFen }, negative.map { it.amountFen })
        assertEquals(-1000L, negative.sumOf { it.amountFen })
    }

    @Test
    fun weightedRemainderTieBreakAndOutputOrderingAreDeterministic() {
        val rule = DormSplitRule(
            DormSplitRuleType.RATIOS,
            ratioWeights = mapOf("c" to 1L, "a" to 1L, "b" to 1L),
        )
        val shares = calculateDormShares(5L, rule, listOf("c", "b", "a"))
        assertEquals(listOf("a", "b", "c"), shares.map { it.participantMemberId })
        assertEquals(5L, shares.sumOf { it.amountFen })
        assertEquals(shares, calculateDormShares(5L, rule, listOf("a", "c", "b")))
    }

    @Test
    fun invariantRejectsNonConservationAndMixedSigns() {
        val entry = previewEntry(amount = 100L, shares = listOf(50L, 49L))
        assertThrows(IllegalArgumentException::class.java) { validateDormLedgerEntryInvariants(entry) }
        val mixed = previewEntry(amount = 100L, shares = listOf(150L, -50L))
        assertThrows(IllegalArgumentException::class.java) { validateDormLedgerEntryInvariants(mixed) }
        val negativeValid = previewEntry(amount = -100L, shares = listOf(-50L, -50L))
        validateDormLedgerEntryInvariants(negativeValid)
        assertTrue(negativeValid.shares.all { it.amountFen <= 0L })
    }

    @Test
    fun settlementStatusMustMatchTwoPartyConfirmationTimestamps() {
        val pendingCreditor = settlement(
            status = DormSettlementStatus.PENDING_CREDITOR_CONFIRMATION,
            debtorAt = "2026-01-01T00:01:00Z",
            creditorAt = null,
        )
        validateDormSettlementInvariants(pendingCreditor)
        assertThrows(IllegalArgumentException::class.java) {
            validateDormSettlementInvariants(pendingCreditor.copy(status = DormSettlementStatus.CONFIRMED, confirmedAt = "2026-01-01T00:02:00Z"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateDormSettlementInvariants(pendingCreditor.copy(debtorMemberId = pendingCreditor.creditorMemberId))
        }
    }

    @Test
    fun ledgerEntryRejectsLongMinBecauseItCannotBeSafelyNegatedOrDisplayed() {
        assertThrows(IllegalArgumentException::class.java) {
            previewEntry(amount = Long.MIN_VALUE, shares = listOf(Long.MIN_VALUE))
        }
    }

    private fun settlement(
        status: DormSettlementStatus,
        debtorAt: String?,
        creditorAt: String?,
    ): DormSettlement = DormSettlement(
        settlementId = "settlement",
        debtorMemberId = "debtor",
        creditorMemberId = "creditor",
        amountFen = 1L,
        basisBalanceRevision = 0L,
        status = status,
        note = null,
        createdAt = "2026-01-01T00:00:00Z",
        debtorConfirmedAt = debtorAt,
        creditorConfirmedAt = creditorAt,
        confirmedAt = null,
        cancelledAt = null,
    )

    private fun previewEntry(amount: Long, shares: List<Long>): DormLedgerEntry = DormLedgerEntry(
        entryId = "entry",
        groupId = "group",
        schoolId = "school",
        kind = DormLedgerEntryKind.EXPENSE,
        correctsEntryId = null,
        correctionReason = null,
        title = "test",
        note = null,
        amountFen = amount,
        paidByMemberId = "a",
        occurredAt = "2026-01-01T00:00:00Z",
        splitRule = DormSplitRule(DormSplitRuleType.EQUAL),
        shares = shares.mapIndexed { index, fen -> DormSplitShare(if (index == 0) "a" else "b", fen) },
        createdByMemberId = "a",
        createdAt = "2026-01-01T00:00:00Z",
    )
}
