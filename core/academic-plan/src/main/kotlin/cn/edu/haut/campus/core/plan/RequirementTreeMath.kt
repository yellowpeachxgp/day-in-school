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
        data class Computed(
            val aggregateEarned: BigDecimal,
            val additionalNeeded: BigDecimal,
            val childAdditionalNeeded: BigDecimal
        )

        val computed = mutableMapOf<String, Computed>()
        fun compute(group: RequirementGroup): Computed {
            computed[group.id]?.let { return it }
            val childValues = group.children.map(::compute)
            val aggregateEarned = childValues.fold(directEarnedByGroup[group.id] ?: BigDecimal.ZERO) { acc, child ->
                acc.add(child.aggregateEarned)
            }
            val childNeedSum = childValues.fold(BigDecimal.ZERO) { acc, child ->
                acc.add(child.additionalNeeded)
            }
            val ownShortfall = (group.requiredCredits - aggregateEarned).max(BigDecimal.ZERO)
            val result = Computed(
                aggregateEarned = aggregateEarned,
                additionalNeeded = maxOf(childNeedSum, ownShortfall),
                childAdditionalNeeded = childNeedSum
            )
            computed[group.id] = result
            return result
        }

        return plan.allGroups().associate { group ->
            val value = compute(group)
            val childrenMinimum = group.children.fold(BigDecimal.ZERO) { acc, child ->
                acc.add(TrainingPlan.minimumRequiredCredits(child))
            }
            group.id to RequirementGroupTotals(
                minimumRequiredCredits = maxOf(group.requiredCredits, childrenMinimum),
                aggregateEarnedCredits = value.aggregateEarned,
                additionalNeededCredits = value.additionalNeeded,
                childAdditionalNeededCredits = value.childAdditionalNeeded
            )
        }
    }

    fun totalAdditionalNeeded(plan: TrainingPlan, totals: Map<String, RequirementGroupTotals>): BigDecimal =
        plan.groups.fold(BigDecimal.ZERO) { acc, group ->
            acc.add(totals[group.id]?.additionalNeededCredits ?: BigDecimal.ZERO)
        }

    /**
     * 需要在毕业进度中稳定展示的组：全部叶子、带直接课程的混合组、以及
     * 自身要求超过子组最少要求之和的汇总约束组。后者正是网关 root 总要求的承载形式。
     */
    fun isProgressVisible(group: RequirementGroup): Boolean {
        if (group.isLeaf || group.courses.isNotEmpty()) return true
        val childrenMinimum = group.children.fold(BigDecimal.ZERO) { acc, child ->
            acc.add(TrainingPlan.minimumRequiredCredits(child))
        }
        return group.requiredCredits > childrenMinimum
    }
}
