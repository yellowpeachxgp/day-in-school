package cn.edu.haut.campus.core.solver

import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.timeline.TimelineEvent
import java.math.BigDecimal
import java.time.Duration

/** 求解输入。前五个字段对应 MASTER_PLAN §6.6；scope/timeGrid 显式化以避免身份和时间语义猜测。 */
data class SelectionProblem(
    val candidates: List<TeachingClassOption>,
    val hard: List<HardConstraint>,
    val soft: List<WeightedPreference>,
    val travel: TravelTimeMatrix,
    val creditRange: ClosedRange<BigDecimal>,
    val scope: SchoolAccountScope,
    val timeGrid: TeachingTimeGrid,
    val fixedOccupancy: List<FixedOccupancy> = emptyList(),
    val fixedEvents: List<TimelineEvent> = emptyList()
) {
    init {
        require(candidates.map { it.id }.distinct().size == candidates.size) { "候选教学班 ID 不能重复" }
        require(hard.map { it.ref.id }.distinct().size == hard.size) { "硬约束 ID 不能重复" }
        require(soft.map { it.id }.distinct().size == soft.size) { "软偏好 ID 不能重复" }
        require(hard.count { it is HardConstraint.CapacityPolicy } <= 1) { "容量策略最多一个" }
        require(hard.count { it is HardConstraint.CommutePolicy } <= 1) { "通勤策略最多一个" }
        require(creditRange.start.signum() >= 0 && creditRange.endInclusive.signum() >= 0) { "学分范围不能为负" }
        require(creditRange.start <= creditRange.endInclusive) { "学分下限不能高于上限" }
        creditsToMilli(creditRange.start)
        creditsToMilli(creditRange.endInclusive)
        candidates.forEach { candidate ->
            candidate.provenance?.let { provenance ->
                require(provenance.schoolId == scope.schoolId) { "候选证据学校与求解 scope 不一致" }
                require(provenance.accountId == scope.accountId) { "候选证据账号与求解 scope 不一致" }
            }
        }
        fixedOccupancy.forEach { occupancy ->
            occupancy.provenance?.let { provenance ->
                require(provenance.schoolId == scope.schoolId) { "固定占用证据学校与求解 scope 不一致" }
                require(provenance.accountId == scope.accountId) { "固定占用证据账号与求解 scope 不一致" }
            }
        }
        require(fixedEvents.all { it.scope == scope }) { "Timeline 固定事件必须属于同一学校账号 Profile scope" }
    }
}

interface TimetableSolver {
    fun solve(
        problem: SelectionProblem,
        maxSolutions: Int = 5,
        budget: Duration = Duration.ofSeconds(2)
    ): SolveResult
}

sealed interface SolveResult {
    data class Solutions(
        val ranked: List<ScheduleSolution>,
        val completion: SolveCompletion,
        val stats: SolverStats
    ) : SolveResult

    data class Infeasible(
        val conflictCore: List<ConstraintRef>,
        val relaxations: List<Relaxation>,
        val explanationStatus: ExplanationStatus,
        val stats: SolverStats
    ) : SolveResult
}

/** PROVEN_COST_TOP_K 表示不存在被遗漏的严格更低成本方案；同成本代表按确定性搜索顺序选取。 */
enum class SolveCompletion {
    PROVEN_COST_TOP_K,
    BEST_FOUND_BUDGET_EXHAUSTED
}

enum class ExplanationStatus {
    MINIMAL_VERIFIED,
    BACKGROUND_INFEASIBLE,
    EXPLANATION_UNKNOWN,
    VERIFICATION_FAILED
}

data class SelectedTeachingClass(
    val option: TeachingClassOption
)

data class ScheduleSolution(
    val selections: List<SelectedTeachingClass>,
    val totalCostMilli: Long,
    val costBreakdown: List<CostBreakdownItem>,
    val creditTotal: BigDecimal,
    val uncertainties: List<SolverUncertainty>,
    val tradeoffs: List<String>,
    val signature: String
) {
    init {
        require(selections.isNotEmpty() || creditTotal.signum() == 0) { "空方案的学分必须为零" }
        require(totalCostMilli >= 0) { "方案成本不能为负" }
    }
}

data class CostBreakdownItem(
    val preferenceId: String,
    val label: String,
    val costMilli: Long,
    val detail: String
) {
    init { require(costMilli >= 0) { "成本分解不能为负" } }
}

enum class UncertaintyKind {
    CAPACITY,
    WEEKS,
    LOCATION,
    TRAVEL,
    PROVENANCE
}

data class SolverUncertainty(
    val kind: UncertaintyKind,
    val message: String,
    val candidateIds: List<String> = emptyList()
)

enum class RelaxationAction {
    REMOVE_REQUIRED_OFFERING,
    LOWER_CREDIT_MINIMUM,
    RAISE_CREDIT_MAXIMUM,
    RELAX_CAPACITY_POLICY,
    DISABLE_TIME_EXCLUSION,
    RELAX_CAMPUS_RESTRICTION,
    REMOVE_MUTUAL_EXCLUSION,
    RELAX_COMMUTE_POLICY
}

data class Relaxation(
    val constraint: ConstraintRef,
    val action: RelaxationAction,
    val explanation: String
)

data class SolverStats(
    val elapsedNanos: Long,
    val nodes: Long,
    val propagations: Long,
    val prunedBranches: Long,
    val oracleCalls: Long = 0,
    val oracleMemoHits: Long = 0,
    val candidateCount: Int = 0,
    val variableCount: Int = 0
) {
    init {
        require(elapsedNanos >= 0 && nodes >= 0 && propagations >= 0 && prunedBranches >= 0) { "求解统计不能为负" }
        require(oracleCalls >= 0 && oracleMemoHits >= 0) { "解释统计不能为负" }
    }
}
