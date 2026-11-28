package cn.edu.haut.campus.core.plan

import java.math.BigDecimal

/**
 * 层次要求树的递归约束计算。
 *
 * requiredCredits 是「本组子树至少要有多少入账学分」的约束。子组缺口所需的未来课程
 * 同时也计入父组汇总，因此父组增量缺口不是父子简单相加；additionalNeeded 取
 * max（子组增量需求之和, 本组自身要求缺口），保证总数不重复也不低估。
 */
internal data class RequirementGroupTotals(
    val minimumRequiredCredits: BigDecimal,
    val aggregateEarnedCredits: BigDecimal,
    val additionalNeededCredits: BigDecimal,
    val childAdditionalNeededCredits: BigDecimal
) {
    /** 扣除子组已表达的增量需求后，本组自身汇总约束额外要求的学分。 */
    val incrementalNeededCredits: BigDecimal
        get() = (additionalNeededCredits - childAdditionalNeededCredits).max(BigDecimal.ZERO)
}

internal object RequirementTreeMath {

    fun analyze(
        plan: TrainingPlan,
        directEarnedByGroup: Map<String, BigDecimal>
    ): Map<String, RequirementGroupTotals> {
        val aggregateMemo = mutableMapOf<String, BigDecimal>()
        val needMemo = mutableMapOf<String, BigDecimal>()
        val childNeedMemo = mutableMapOf<String, BigDecimal>()

        fun aggregate(group: RequirementGroup): BigDecimal {
            aggregateMemo[group.id]?.let { return it }
            val direct = directEarnedByGroup[group.id] ?: BigDecimal.ZERO
            val total = group.children.fold(direct) { acc, child -> acc.add(aggregate(child)) }
            aggregateMemo[group.id] = total
            return total
        }

        fun childNeeds(group: RequirementGroup): BigDecimal {
            childNeedMemo[group.id]?.let { return it }
            val total = group.children.fold(BigDecimal.ZERO) { acc, child -> acc.add(need(child)) }
            childNeedMemo[group.id] = total
            return total
        }

        fun need(group: RequirementGroup): BigDecimal {
            needMemo[group.id]?.let { return it }
            val ownShortfall = (group.requiredCredits - aggregate(group)).max(BigDecimal.ZERO)
            val total = maxOf(childNeeds(group), ownShortfall)
            needMemo[group.id] = total
            return total
        }

        return plan.allGroups().associate { group ->
            val childrenMinimum = group.children.fold(BigDecimal.ZERO) { acc, child ->
                acc.add(TrainingPlan.minimumRequiredCredits(child))
            }
            group.id to RequirementGroupTotals(
