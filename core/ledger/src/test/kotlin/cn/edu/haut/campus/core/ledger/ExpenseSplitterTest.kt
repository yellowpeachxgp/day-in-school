package cn.edu.haut.campus.core.ledger

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExpenseSplitterTest {

    private val a = ParticipantId("a")
    private val b = ParticipantId("b")
    private val c = ParticipantId("c")
    private val d = ParticipantId("d")

    @Test
    fun equalSplitUsesLargestRemainderAndAsciiTieBreak() {
        val shares = ExpenseSplitter.split(
            total = Money(10L),
            members = listOf(c, a, b),
            rule = SplitRule.Equal,
        )

        assertEquals(
            mapOf(a to 4L, b to 3L, c to 3L),
            shares.associate { it.participantId to it.amount.fen },
        )
        assertEquals(listOf(a, b, c), shares.map { it.participantId })
    }

    @Test
    fun oneFenRemainderGoesToLexicographicallyFirstParticipantOnTie() {
        val shares = ExpenseSplitter.split(
            total = Money(1L),
            members = listOf(c, b, a),
            rule = SplitRule.Equal,
        )

        assertEquals(mapOf(a to 1L, b to 0L, c to 0L), shares.associate { it.participantId to it.amount.fen })
    }

    @Test
    fun resultDoesNotDependOnMemberInputOrder() {
        val first = ExpenseSplitter.split(Money(97L), listOf(a, b, c, d), SplitRule.Equal)
        val second = ExpenseSplitter.split(Money(97L), listOf(d, c, b, a), SplitRule.Equal)
        assertEquals(first, second)
    }

    @Test
    fun ratioSplitUsesWeightsAndConservesTotal() {
        val shares = ExpenseSplitter.split(
            total = Money(10L),
            members = listOf(a, b, c),
            rule = SplitRule.Ratios(mapOf(a to 1L, b to 2L, c to 3L)),
        )

        assertEquals(mapOf(a to 2L, b to 3L, c to 5L), shares.associate { it.participantId to it.amount.fen })
    }

    @Test
    fun selectedParticipantsSplitEquallyAndUnselectedMembersAreAbsent() {
        val shares = ExpenseSplitter.split(
            total = Money(9L),
            members = listOf(a, b, c),
            rule = SplitRule.Selected(setOf(c, b)),
        )

        assertEquals(mapOf(b to 5L, c to 4L), shares.associate { it.participantId to it.amount.fen })
    }

    @Test
    fun stayDaysSplitTreatsZeroDaysAsNonParticipant() {
        val shares = ExpenseSplitter.split(
            total = Money(30L),
            members = listOf(a, b, c),
            rule = SplitRule.StayDays(mapOf(a to 1L, b to 2L, c to 0L)),
        )

        assertEquals(mapOf(a to 10L, b to 20L), shares.associate { it.participantId to it.amount.fen })
    }

    @Test
    fun zeroAmountKeepsEffectiveParticipantsAtZero() {
        val shares = ExpenseSplitter.split(
            total = Money.ZERO,
            members = listOf(a, b, c),
            rule = SplitRule.Selected(setOf(b, c)),
        )

        assertEquals(mapOf(b to 0L, c to 0L), shares.associate { it.participantId to it.amount.fen })
    }

    @Test
    fun negativeAmountUsesAbsoluteMagnitudeThenNegatesEveryShare() {
        val shares = ExpenseSplitter.split(
            total = Money(-10L),
            members = listOf(a, b, c),
            rule = SplitRule.Equal,
        )

        assertEquals(mapOf(a to -4L, b to -3L, c to -3L), shares.associate { it.participantId to it.amount.fen })
        assertEquals(-10L, shares.sumOf { it.amount.fen })
    }

    @Test
    fun minimumLongCanBeSplitWithoutIntermediateOverflow() {
        val shares = ExpenseSplitter.split(
            total = Money(Long.MIN_VALUE),
            members = listOf(a, b),
            rule = SplitRule.Equal,
        )

        assertEquals(
            mapOf(a to -4611686018427387904L, b to -4611686018427387904L),
            shares.associate { it.participantId to it.amount.fen },
        )
    }

    @Test
    fun hugeWeightsDoNotOverflowIntermediateMultiplicationOrWeightSum() {
        val shares = ExpenseSplitter.split(
            total = Money(Long.MAX_VALUE),
            members = listOf(a, b),
            rule = SplitRule.Ratios(mapOf(a to Long.MAX_VALUE, b to Long.MAX_VALUE)),
        )

        assertEquals(
            mapOf(a to 4611686018427387904L, b to 4611686018427387903L),
            shares.associate { it.participantId to it.amount.fen },
        )
    }

    @Test
    fun propertyTotalIsConservedAcrossAllSplitModesAndOutputIsCanonical() {
        val random = Random(20261127)
        repeat(400) {
            val amount = random.nextInt(-50_000, 50_001).toLong()
            val count = random.nextInt(1, 9)
            val ids = (0 until count).map { index -> ParticipantId("p" + index) }
            val rules = mutableListOf<SplitRule>(SplitRule.Equal)

            val selected = ids.filter { random.nextBoolean() }.ifEmpty { listOf(ids.first()) }.toSet()
            rules += SplitRule.Selected(selected)

            val ratios = ids.associate { it to random.nextInt(0, 10).toLong() }.toMutableMap()
            if (ratios.values.all { it == 0L }) ratios[ids.first()] = 1L
            rules += SplitRule.Ratios(ratios)

            val days = ids.associate { it to random.nextInt(0, 32).toLong() }.toMutableMap()
            if (days.values.all { it == 0L }) days[ids.first()] = 1L
            rules += SplitRule.StayDays(days)

            for (rule in rules) {
                val shares = ExpenseSplitter.split(Money(amount), ids.shuffled(random), rule)
                assertEquals("amount=$amount rule=$rule", amount, shares.sumOf { it.amount.fen })
                assertEquals(shares.map { it.participantId }.sorted(), shares.map { it.participantId })
            }
        }
    }

    @Test
    fun emptyMemberListIsRejected() {
        val error = assertThrows(LedgerException::class.java) {
            ExpenseSplitter.split(Money(1L), emptyList(), SplitRule.Equal)
        }
        assertEquals(LedgerErrorCode.EMPTY_PARTICIPANTS, error.code)
    }

    @Test
    fun duplicateMembersAreRejected() {
        val error = assertThrows(LedgerException::class.java) {
            ExpenseSplitter.split(Money(1L), listOf(a, b, a), SplitRule.Equal)
        }
        assertEquals(LedgerErrorCode.DUPLICATE_PARTICIPANT, error.code)
    }

    @Test
    fun unknownSelectedParticipantIsRejected() {
        val error = assertThrows(LedgerException::class.java) {
            ExpenseSplitter.split(Money(1L), listOf(a, b), SplitRule.Selected(setOf(c)))
        }
        assertEquals(LedgerErrorCode.UNKNOWN_PARTICIPANT, error.code)
    }

    @Test
    fun unknownRatioParticipantIsRejected() {
        val error = assertThrows(LedgerException::class.java) {
            ExpenseSplitter.split(Money(1L), listOf(a, b), SplitRule.Ratios(mapOf(c to 1L)))
        }
        assertEquals(LedgerErrorCode.UNKNOWN_PARTICIPANT, error.code)
    }

    @Test
    fun negativeRatioWeightIsRejected() {
        val error = assertThrows(LedgerException::class.java) {
            ExpenseSplitter.split(Money(1L), listOf(a, b), SplitRule.Ratios(mapOf(a to -1L, b to 1L)))
        }
        assertEquals(LedgerErrorCode.INVALID_RATIO_WEIGHT, error.code)
    }

    @Test
    fun negativeStayDaysAreRejected() {
        val error = assertThrows(LedgerException::class.java) {
            ExpenseSplitter.split(Money(1L), listOf(a, b), SplitRule.StayDays(mapOf(a to -1L, b to 1L)))
        }
        assertEquals(LedgerErrorCode.INVALID_STAY_DAYS, error.code)
    }

    @Test
    fun allZeroWeightsOrDaysMeanNoEffectiveParticipants() {
        val ratioError = assertThrows(LedgerException::class.java) {
            ExpenseSplitter.split(Money(1L), listOf(a, b), SplitRule.Ratios(mapOf(a to 0L)))
        }
        assertEquals(LedgerErrorCode.NO_EFFECTIVE_PARTICIPANTS, ratioError.code)

        val dayError = assertThrows(LedgerException::class.java) {
            ExpenseSplitter.split(Money(1L), listOf(a, b), SplitRule.StayDays(mapOf(a to 0L)))
        }
        assertEquals(LedgerErrorCode.NO_EFFECTIVE_PARTICIPANTS, dayError.code)
    }

    @Test
    fun emptySelectedSetMeansNoEffectiveParticipants() {
        val error = assertThrows(LedgerException::class.java) {
            ExpenseSplitter.split(Money(1L), listOf(a, b), SplitRule.Selected(emptySet()))
        }
        assertEquals(LedgerErrorCode.NO_EFFECTIVE_PARTICIPANTS, error.code)
    }
}
