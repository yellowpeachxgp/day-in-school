package cn.edu.haut.campus.core.solver

internal fun interface ConsistencyOracle {
    fun check(activeConstraintIds: Set<String>, budget: SolverBudget, stats: MutableSolverStats): Consistency
}

internal data class ExplanationResult(
    val core: List<ConstraintRef>,
    val relaxations: List<Relaxation>,
    val status: ExplanationStatus
)

/** Junker QuickXplain；用户级约束集保持很小，候选两两时段冲突留在一致性 oracle 内部。 */
internal class QuickXplainExplainer(
    private val constraints: List<UserConstraint>,
    private val oracle: ConsistencyOracle
) {
    private val consistentCache = HashSet<Set<String>>()
    private val inconsistentCache = HashSet<Set<String>>()

    private class OracleUnknown : RuntimeException()

    fun explain(budget: SolverBudget, stats: MutableSolverStats): ExplanationResult {
        val ordered = constraints.map { it.ref.id }
        val all = ordered.toSet()
        when (cachedCheck(emptySet(), budget, stats)) {
            Consistency.INCONSISTENT -> return ExplanationResult(emptyList(), emptyList(), ExplanationStatus.BACKGROUND_INFEASIBLE)
            Consistency.UNKNOWN -> return ExplanationResult(emptyList(), emptyList(), ExplanationStatus.EXPLANATION_UNKNOWN)
            Consistency.CONSISTENT -> Unit
        }
        when (cachedCheck(all, budget, stats)) {
            Consistency.CONSISTENT -> return ExplanationResult(emptyList(), emptyList(), ExplanationStatus.VERIFICATION_FAILED)
            Consistency.UNKNOWN -> return ExplanationResult(emptyList(), emptyList(), ExplanationStatus.EXPLANATION_UNKNOWN)
            Consistency.INCONSISTENT -> Unit
        }

        val coreIds = try {
            quickXplain(emptySet(), emptySet(), ordered, budget, stats)
        } catch (unknown: OracleUnknown) {
            return ExplanationResult(emptyList(), emptyList(), ExplanationStatus.EXPLANATION_UNKNOWN)
        }
        if (coreIds.isEmpty()) {
            return ExplanationResult(emptyList(), emptyList(), ExplanationStatus.BACKGROUND_INFEASIBLE)
        }

        var fullyVerified = true
        for (constraintId in coreIds) {
            when (cachedCheck(coreIds - constraintId, budget, stats)) {
                Consistency.CONSISTENT -> Unit
                Consistency.UNKNOWN -> fullyVerified = false
                Consistency.INCONSISTENT -> return ExplanationResult(emptyList(), emptyList(), ExplanationStatus.VERIFICATION_FAILED)
            }
        }
        if (!fullyVerified) return ExplanationResult(emptyList(), emptyList(), ExplanationStatus.EXPLANATION_UNKNOWN)

        val refs = constraints.filter { it.ref.id in coreIds }.map { it.ref }
        return ExplanationResult(
            core = refs,
            relaxations = refs.map { relaxationFor(it) },
            status = ExplanationStatus.MINIMAL_VERIFIED
        )
    }

    /**
     * R04 §5.5 的递归结构。B 是已并入背景的用户约束；固定课表、时段事实和变量结构
     * 永远在 oracle 的背景中，不作为可放松建议。
     */
    private fun quickXplain(
        background: Set<String>,
        delta: Set<String>,
        candidates: List<String>,
        budget: SolverBudget,
        stats: MutableSolverStats
    ): Set<String> {
        if (delta.isNotEmpty()) {
            when (cachedCheck(background, budget, stats)) {
                Consistency.INCONSISTENT -> return emptySet()
                Consistency.UNKNOWN -> throw OracleUnknown()
                Consistency.CONSISTENT -> Unit
            }
        }
        if (candidates.size == 1) return setOf(candidates.single())
        val middle = candidates.size / 2
        val first = candidates.subList(0, middle)
        val second = candidates.subList(middle, candidates.size)
        val firstSet = first.toSet()
        val delta2 = quickXplain(background + firstSet, firstSet, second, budget, stats)
        val delta1 = quickXplain(background + delta2, delta2, first, budget, stats)
        return delta1 + delta2
    }

    private fun cachedCheck(ids: Set<String>, budget: SolverBudget, stats: MutableSolverStats): Consistency {
        if (inconsistentCache.any { bad -> ids.containsAll(bad) }) return Consistency.INCONSISTENT
        if (consistentCache.any { good -> good.containsAll(ids) }) {
            stats.oracleMemoHits++
            return Consistency.CONSISTENT
        }
        stats.oracleCalls++
        return when (val result = oracle.check(ids, budget, stats)) {
            Consistency.CONSISTENT -> {
                consistentCache += ids
                result
            }
            Consistency.INCONSISTENT -> {
                inconsistentCache += ids
                result
            }
            Consistency.UNKNOWN -> result
        }
    }

    private fun relaxationFor(ref: ConstraintRef): Relaxation = when (ref.kind) {
        ConstraintKind.REQUIRED_OFFERING -> Relaxation(
            ref,
            RelaxationAction.REMOVE_REQUIRED_OFFERING,
            "由用户确认后移除该必选要求，或改选该课程的其他可用教学班；求解器不会自动修改。"
        )
        ConstraintKind.CREDIT_LOWER_BOUND -> Relaxation(
            ref,
            RelaxationAction.LOWER_CREDIT_MINIMUM,
            "降低总学分下限，或减少需要补足的选修学分目标。"
        )
        ConstraintKind.CREDIT_UPPER_BOUND -> Relaxation(
            ref,
            RelaxationAction.RAISE_CREDIT_MAXIMUM,
            "提高总学分上限，或移除部分非必要候选课程。"
        )
        ConstraintKind.CAPACITY_POLICY -> Relaxation(
            ref,
            RelaxationAction.RELAX_CAPACITY_POLICY,
            "允许容量未知候选进入方案，或重新评估是否排除已知满员候选；不会伪造实时名额。"
        )
        ConstraintKind.TIME_EXCLUSION -> Relaxation(
            ref,
            RelaxationAction.DISABLE_TIME_EXCLUSION,
            "为该时间排除规则增加例外或暂时停用它。"
        )
        ConstraintKind.CAMPUS_RESTRICTION -> Relaxation(
            ref,
            RelaxationAction.RELAX_CAMPUS_RESTRICTION,
            "放宽校区限制，或先补齐候选楼宇/校区证据。"
        )
        ConstraintKind.MUTUAL_EXCLUSION -> Relaxation(
            ref,
            RelaxationAction.REMOVE_MUTUAL_EXCLUSION,
            "由用户确认后移除该互斥关系，或只保留其中一门课程。"
        )
        ConstraintKind.COMMUTE_POLICY -> Relaxation(
            ref,
            RelaxationAction.RELAX_COMMUTE_POLICY,
            "增大课间间隔、降低通勤缓冲或显式允许通勤证据不足；不会把未知改写成可达。"
        )
    }
}
