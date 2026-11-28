package cn.edu.haut.campus.core.knowledge

import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.solver.ConstraintRef
import cn.edu.haut.campus.core.solver.SolveCompletion
import cn.edu.haut.campus.core.solver.SolverStats
import cn.edu.haut.campus.core.timeline.SlotConstraints
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineEventId
import java.time.Duration
import java.time.Instant

/** W3-05 复习排程请求。window 与 constraints 都必须显式给出，禁止猜测校区时区或可用时间。 */
data class ReviewScheduleRequest(
    val scope: SchoolAccountScope,
    val window: TimeWindow,
    val constraints: SlotConstraints,
    val courseId: String? = null,
    val taskIds: Set<String> = emptySet(),
    val maxTasks: Int = 20,
    val maxCandidatesPerTask: Int = 12,
    val solverBudget: Duration = Duration.ofSeconds(2)
) {
    init {
        require(courseId == null || courseId.isNotBlank()) { "courseId 不能为空白" }
        require(taskIds.all { it.isNotBlank() && it.none { ch -> ch.isISOControl() || ch.isWhitespace() } }) {
            "复习任务 ID 过滤条件非法"
        }
        require(maxTasks in 1..50) { "单次复习排程任务数必须在 1..50" }
        require(maxCandidatesPerTask in 1..24) { "每个复习任务候选方案数必须在 1..24" }
        require(!solverBudget.isZero && !solverBudget.isNegative) { "求解预算必须为正时长" }
    }
}

enum class ReviewScheduleResultStatus {
    /** 所有入选任务都有未来排程（包括幂等重放得到的相同排程）。 */
    SCHEDULED,

    /** 至少一个任务已排程，但至少一个任务因空档/冲突/进行中块未能排程。 */
    PARTIAL,

    /** 有任务需要排程，但本次没有任何任务得到新排程。 */
    INFEASIBLE,

    /** 过滤后没有 TODO 任务，或窗口已经完全过去。 */
    EMPTY
}

data class ReviewBlockPlacement(
    val taskId: String,
    val blockId: String,
    val repetitionIndex: Int,
    val start: Instant,
    val end: Instant,
    val timelineEventId: TimelineEventId
)

data class UnscheduledReviewTask(
    val task: ReviewTask,
    val reason: String
)

/** 排程结果同时返回任务快照与扁平 placements，调用方无需理解求解器候选编码。 */
data class ReviewScheduleResult(
    val status: ReviewScheduleResultStatus,
    val scheduledTasks: List<ReviewTask>,
    val unscheduledTasks: List<UnscheduledReviewTask>,
    val placements: List<ReviewBlockPlacement>,
    val solverStats: SolverStats? = null,
    val completion: SolveCompletion? = null,
    val conflictCore: List<ConstraintRef> = emptyList(),
    val warnings: List<String> = emptyList()
)
