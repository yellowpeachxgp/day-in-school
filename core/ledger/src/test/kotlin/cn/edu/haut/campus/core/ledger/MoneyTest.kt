package cn.edu.haut.campus.core.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {

    @Test
    fun additionAndSubtractionStayInExactFen() {
        assertEquals(Money(150L), Money(100L) + Money(50L))
        assertEquals(Money(75L), Money(100L) - Money(25L))
        assertEquals(Money(-25L), Money(0L) - Money(25L))
    }

    @Test
    fun signAccessorsDescribeZeroPositiveAndNegative() {
        assertTrue(Money.ZERO.isZero)
        assertTrue(Money(1L).isPositive)
        assertTrue(Money(-1L).isNegative)
        assertFalse(Money.ZERO.isPositive)
        assertFalse(Money.ZERO.isNegative)
    }

    @Test
    fun additionOverflowIsRejected() {
        val error = assertThrows(LedgerException::class.java) {
            Money(Long.MAX_VALUE) + Money(1L)
        }
        assertEquals(LedgerErrorCode.ARITHMETIC_OVERFLOW, error.code)
    }

    @Test
    fun subtractionOverflowIsRejected() {
        val error = assertThrows(LedgerException::class.java) {
            Money(Long.MIN_VALUE) - Money(1L)
        }
        assertEquals(LedgerErrorCode.ARITHMETIC_OVERFLOW, error.code)
    }

    @Test
    fun negatingMinimumLongIsRejectedBecausePositiveMagnitudeDoesNotFit() {
        val error = assertThrows(LedgerException::class.java) {
            -Money(Long.MIN_VALUE)
        }
        assertEquals(LedgerErrorCode.ARITHMETIC_OVERFLOW, error.code)
    }

    @Test
    fun minimumLongItselfIsAValidSignedFenAmount() {
        val amount = Money(Long.MIN_VALUE)
        assertTrue(amount.isNegative)
        assertEquals(Long.MIN_VALUE, amount.fen)
    }
}
