package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.packages.datapack.DisDatapackItem
import cn.edu.haut.campus.core.packages.datapack.OpportunityRequirement

data class RequirementAssessment(
    val requirement: OpportunityRequirement,
    val fact: OpportunityRequirementFact?,
    val satisfaction: RequirementSatisfaction
)

data class EligibilityAssessment(
    val baseState: EligibilityState,
    val effectiveState: EligibilityState,
    val requirements: List<RequirementAssessment>,
    val missingRequirementIds: List<String>,
    val manualOverride: ManualEligibilityOverride?
) {
    val overridden: Boolean get() = manualOverride != null && manualOverride.state != baseState
}

/**
 * 机会资格三态判断。信息缺失只可能是 NEEDS_VERIFICATION 或 NOT_ELIGIBLE，
 * 永远不会因为 requirement.computable=true 就自动补成符合。
 */
object OpportunityEligibilityEvaluator {

    fun assess(
        item: DisDatapackItem.OpportunityCatalog,
        facts: Map<String, OpportunityRequirementFact> = emptyMap(),
        manualOverride: ManualEligibilityOverride? = null
    ): EligibilityAssessment {
        val requirementsById = item.requirements.associateBy { it.id }
        facts.keys.forEach { requirementId ->
            if (requirementId !in requirementsById) {
                throw GuideOperationException("资格事实引用不存在的要求：" + requirementId)
            }
        }
        val assessments = item.requirements.map { requirement ->
            val fact = facts[requirement.id]
            if (fact?.source == RequirementFactSource.COMPUTED && !requirement.computable) {
                throw GuideOperationException("不可计算要求不能接受 COMPUTED 事实：" + requirement.id)
            }
            RequirementAssessment(
                requirement = requirement,
                fact = fact,
                satisfaction = fact?.satisfaction ?: RequirementSatisfaction.UNKNOWN
            )
        }
        val baseState = when {
            assessments.any { it.satisfaction == RequirementSatisfaction.NOT_SATISFIED } -> EligibilityState.NOT_ELIGIBLE
            assessments.all { it.satisfaction == RequirementSatisfaction.SATISFIED } -> EligibilityState.ELIGIBLE
            else -> EligibilityState.NEEDS_VERIFICATION
        }
        return EligibilityAssessment(
            baseState = baseState,
            effectiveState = manualOverride?.state ?: baseState,
            requirements = assessments,
            missingRequirementIds = assessments.filter { it.satisfaction == RequirementSatisfaction.UNKNOWN }.map { it.requirement.id },
            manualOverride = manualOverride
        )
    }
}
