package cn.edu.haut.campus.core.ledger

/**
 * 账本校验与算术错误码。
 *
 * 这些名字是客户端与云端共享语义的一部分：云端后续实现同一规则时应复用同一套稳定编码，
 * 不把本地化文案当作程序判断依据。
 */
enum class LedgerErrorCode {
    EMPTY_PARTICIPANTS,
    DUPLICATE_PARTICIPANT,
    UNKNOWN_PARTICIPANT,
    INVALID_RATIO_WEIGHT,
    INVALID_STAY_DAYS,
    NO_EFFECTIVE_PARTICIPANTS,
    EMPTY_IDENTIFIER,
    INVALID_IDENTIFIER,
    ARITHMETIC_OVERFLOW,
    SAME_SETTLEMENT_PARTIES,
    NON_POSITIVE_SETTLEMENT_AMOUNT,
}

/** 账本领域输入非法或整数算术不可安全表示时抛出。 */
class LedgerException(
    val code: LedgerErrorCode,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
