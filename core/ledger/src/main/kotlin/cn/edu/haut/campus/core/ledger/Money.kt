package cn.edu.haut.campus.core.ledger

/**
 * 以“分”为最小单位的账本金额。
 *
 * 金额永远使用 signed [Long] 分，不使用浮点数。它只表示账本中的应收/应付价值，
 * 不代表已经发生的支付、转账或钱包扣款。加减与取负使用 checked arithmetic；结果超出
 * signed Long 可表示范围时抛出 [LedgerException]，禁止静默环绕。
 */
@JvmInline
value class Money(val fen: Long) : Comparable<Money> {
    val isZero: Boolean
        get() = fen == 0L

    val isPositive: Boolean
        get() = fen > 0L

    val isNegative: Boolean
        get() = fen < 0L

    operator fun plus(other: Money): Money = Money(exactly { Math.addExact(fen, other.fen) })

    operator fun minus(other: Money): Money = Money(exactly { Math.subtractExact(fen, other.fen) })

    operator fun unaryMinus(): Money = Money(exactly { Math.negateExact(fen) })

    override fun compareTo(other: Money): Int = fen.compareTo(other.fen)

    companion object {
        val ZERO: Money = Money(0L)

        fun ofFen(fen: Long): Money = Money(fen)
    }
}

private inline fun exactly(operation: () -> Long): Long = try {
    operation()
} catch (error: ArithmeticException) {
    throw LedgerException(
        LedgerErrorCode.ARITHMETIC_OVERFLOW,
        "money arithmetic overflowed signed 64-bit fen",
        error,
    )
}
