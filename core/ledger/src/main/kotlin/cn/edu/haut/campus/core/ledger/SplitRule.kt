package cn.edu.haut.campus.core.ledger

/**
 * 分摊规则。
 *
 * 所有权重都是无量纲整数；只有严格大于零的权重会产生有效参与人。零权重/零入住天数
 * 明确表示该成员不参与本笔分摊，负值一律非法。
 */
sealed interface SplitRule {
    /** 群体全部成员均摊。 */
    data object Equal : SplitRule

    /** 仅在显式指定的参与人之间均摊；指定人必须属于群体成员。 */
    data class Selected(val participantIds: Set<ParticipantId>) : SplitRule

    /** 按整数比例权重分摊；未列出的成员权重为零。 */
    data class Ratios(val weights: Map<ParticipantId, Long>) : SplitRule

    /** 按入住天数分摊；未列出的成员入住天数为零。 */
    data class StayDays(val days: Map<ParticipantId, Long>) : SplitRule
}

/** 一名有效参与人分到的一行账本金额；零金额行保留用于完整解释分摊结果。 */
data class SplitShare(
    val participantId: ParticipantId,
    val amount: Money,
)
