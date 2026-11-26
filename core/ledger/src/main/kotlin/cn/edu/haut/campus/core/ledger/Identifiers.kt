package cn.edu.haut.campus.core.ledger

/**
 * 校验稳定账本标识符。
 *
 * 参与人、结算单等标识符是跨端同步用的 opaque id，不是展示名。为了让客户端与云端有一致的
 * 字典序，标识符只接受不含空白/control 字符的可打印 ASCII（代码点 33..126）。
 */
internal fun requireOpaqueAsciiIdentifier(value: String, label: String): String {
    if (value.isEmpty()) {
        throw LedgerException(LedgerErrorCode.EMPTY_IDENTIFIER, "$label must not be empty")
    }
    if (value.any { it.code !in 33..126 }) {
        throw LedgerException(
            LedgerErrorCode.INVALID_IDENTIFIER,
            "$label must contain only printable non-whitespace ASCII characters",
        )
    }
    return value
}

/** 分摊与结算中的稳定参与人标识。 */
@JvmInline
value class ParticipantId(val value: String) : Comparable<ParticipantId> {
    init {
        requireOpaqueAsciiIdentifier(value, "participant id")
    }

    override fun compareTo(other: ParticipantId): Int = value.compareTo(other.value)

    override fun toString(): String = value
}

/** 结算记录标识。 */
@JvmInline
value class SettlementId(val value: String) {
    init {
        requireOpaqueAsciiIdentifier(value, "settlement id")
    }

    override fun toString(): String = value
}
