package cn.edu.haut.campus.core.knowledge

import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.solver.BranchAndBoundTimetableSolver
import cn.edu.haut.campus.core.solver.SolveCompletion
import cn.edu.haut.campus.core.solver.SolveResult
import cn.edu.haut.campus.core.solver.ScheduleVerificationReport
import cn.edu.haut.campus.core.solver.TimetableSolver
import cn.edu.haut.campus.core.timeline.BusyBlock
import cn.edu.haut.campus.core.timeline.BusyFreeCalculator
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineEvent
import cn.edu.haut.campus.core.timeline.TimelineEventId
import cn.edu.haut.campus.core.timeline.TimelineFilter
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineService
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * W3-05 Timeline 集成编排器。
 *
 * 数据流：ReviewTask(blob_store 过渡持久化) + Timeline 统一占用 → 30 分钟网格候选
 * → W3-02 TimetableSolver 选择互不冲突的完整间隔重复方案 → upsertReviewTask 原子投影为
 * personal_events 的 REVIEW_BLOCK。重跑会替换未来 PLANNED 块；DONE/MISSED 历史块绝不丢弃。
 */
class TimelineReviewScheduler(
    private val scope: SchoolAccountScope,
    private val repository: KnowledgeRepository,
    private val timelineService: TimelineService,
    solver: TimetableSolver = BranchAndBoundTimetableSolver.create(),
    private val clock: () -> Instant = Instant::now
) {
    private val planner = ReviewSchedulePlanner(solver)

    suspend fun schedule(request: ReviewScheduleRequest, actor: Actor = Actor.USER): ReviewScheduleResult {
        require(request.scope == scope) { "TimelineReviewScheduler 只能访问本 profile scope" }
        val now = clock()
        val effectiveStart = if (request.window.start.isAfter(now)) request.window.start else now
        if (!request.window.end.isAfter(effectiveStart)) {
            return ReviewScheduleResult(
                status = ReviewScheduleResultStatus.EMPTY,
                scheduledTasks = emptyList(),
                unscheduledTasks = emptyList(),
                placements = emptyList()
            )
        }
        val window = TimeWindow(effectiveStart, request.window.end)
        require(Duration.between(window.start, window.end) <= MAX_SCHEDULING_WINDOW) {
            "复习排程窗口不能超过 " + MAX_SCHEDULING_WINDOW.toDays() + " 天"
        }

        val timelineEvents = timelineService.observe(scope, window, TimelineFilter.ALL).first()
        val todoTasks = repository.listReviewTasks(scope, request.courseId, ReviewTaskState.TODO)
            .filter { request.taskIds.isEmpty() || it.id in request.taskIds }
        if (todoTasks.isEmpty()) {
            return ReviewScheduleResult(
                status = ReviewScheduleResultStatus.EMPTY,
                scheduledTasks = emptyList(),
                unscheduledTasks = emptyList(),
                placements = emptyList()
            )
        }

        val warnings = ArrayList<String>()
        val afterMissed = ArrayList<ReviewTask>()
        var newlyMissedCount = 0
        for (task in todoTasks) {
            val result = markExpiredBlocksMissed(task, now, actor)
            newlyMissedCount += result.second
            afterMissed += result.first
        }
        if (newlyMissedCount > 0) {
            warnings += "已将 " + newlyMissedCount + " 个过期未完成复习块标记为漏复习，并纳入重排"
        }

        val ongoing = ArrayList<ReviewTask>()
        val candidates = ArrayList<ReviewTask>()
        for (task in afterMissed) {
            if (task.hasOngoingPlannedBlock(now)) {
                ongoing += task
            } else {
                candidates += task
            }
        }

        val specs = candidates.map { task ->
            val deadline = resolveDeadline(task, timelineEvents, now)
            val schedulingEnd = minInstant(deadline.deadlineAt ?: window.end, window.end)
            ReviewTaskSchedulingSpec(
                task = task,
                deadlineAt = deadline.deadlineAt,
                schedulingEnd = schedulingEnd,
                overdue = deadline.overdue
            )
        }.filter { it.schedulingEnd.isAfter(now) }
            .sortedWith(
                compareBy<ReviewTaskSchedulingSpec> { if (it.overdue) 0 else 1 }
                    .thenBy { it.deadlineAt ?: Instant.MAX }
                    .thenBy { SpacedRepetitionPolicy.effectiveMasteryPercent(it.task) }
                    .thenBy { it.task.id }
            )
            .take(request.maxTasks)

        val initialUnscheduled = LinkedHashMap<String, UnscheduledReviewTask>()
        ongoing.forEach { task ->
            initialUnscheduled[task.id] = UnscheduledReviewTask(task, "存在进行中的复习块；完成或等待其结束后再重排")
        }
        val specIds = specs.mapTo(linkedSetOf()) { it.task.id }
        afterMissed.filter { it.id !in specIds && it.id !in initialUnscheduled.keys }.forEach { task ->
            initialUnscheduled[task.id] = UnscheduledReviewTask(task, "排程窗口或截止时间在当前时间之前，或超出单次任务数上限")
        }
        if (specs.isEmpty()) {
            return ReviewScheduleResult(
                status = if (initialUnscheduled.isEmpty()) ReviewScheduleResultStatus.EMPTY else ReviewScheduleResultStatus.INFEASIBLE,
                scheduledTasks = emptyList(),
                unscheduledTasks = initialUnscheduled.values.toList(),
                placements = emptyList(),
                warnings = warnings
            )
        }

        val replaceableEventIds = specs.flatMap { spec -> spec.task.futurePlannedBlocks(now).map { it.timelineEventId } }.toSet()
        val fixedEvents = timelineEvents.filterNot { it.id in replaceableEventIds }
        val constraints = request.constraints.copy(tentativeCountsAsBusy = true)
        val freeSlots = BusyFreeCalculator.freeSlots(
            window = window,
            intervals = busyIntervals(fixedEvents),
            minLength = SpacedRepetitionPolicy.QUANTUM,
            constraints = constraints
        )
        val generation = planner.generateCandidates(
            specs = specs,
            freeSlots = freeSlots,
            window = window,
            zone = constraints.zone,
            maxCandidatesPerTask = request.maxCandidatesPerTask
        )

        val dropReasons = LinkedHashMap<String, String>()
        generation.failures.forEach { (taskId, reason) -> dropReasons[taskId] = reason }
        var active = specs.filter { generation.plansByTaskId.containsKey(it.task.id) }
        var chosen: ReviewSolveOutcome? = null
        var latest: ReviewSolveOutcome? = null
        var invalidVerification: ReviewSolveOutcome? = null
        while (active.isNotEmpty()) {
            val attempt = planner.solve(
                activeSpecs = active,
                generation = generation,
                fixedEvents = fixedEvents,
                scope = scope,
                budget = request.solverBudget
            )
            latest = attempt
            val solution = attempt?.solution
            if (solution != null && attempt.verification?.isValid == true) {
                chosen = attempt
                break
            }
            if (solution != null && attempt.verification?.isValid != true) {
                invalidVerification = attempt
                break
            }
            val removed = active.last()
            dropReasons[removed.task.id] = solverFailureDescription(attempt?.result)
            active = active.dropLast(1)
        }

        if (invalidVerification != null) {
            warnings += "求解器结果未通过独立校验，已 fail-closed，未写入新复习排程"
            val allUnscheduled = specs.map { spec ->
                UnscheduledReviewTask(spec.task, "求解结果校验失败")
            } + initialUnscheduled.values
            return ReviewScheduleResult(
                status = ReviewScheduleResultStatus.INFEASIBLE,
                scheduledTasks = emptyList(),
                unscheduledTasks = allUnscheduled,
                placements = emptyList(),
                solverStats = (invalidVerification.result as? SolveResult.Solutions)?.stats
                    ?: (invalidVerification.result as? SolveResult.Infeasible)?.stats,
                conflictCore = (invalidVerification.result as? SolveResult.Infeasible)?.conflictCore.orEmpty(),
                warnings = warnings + invalidVerification.verification.orEmptyViolations()
            )
        }

        val selectedPlans = chosen?.selectedPlans.orEmpty()
        val scheduledTasks = ArrayList<ReviewTask>()
        val unscheduled = initialUnscheduled.values.toMutableList()
        val placements = ArrayList<ReviewBlockPlacement>()
        for (spec in specs) {
            val generated = selectedPlans[spec.task.id]
            val updated = taskWithReplacedPlan(spec, generated, constraints.zone, now)
            val persisted = if (updated == spec.task) spec.task else repository.upsertReviewTask(updated, actor)
            if (generated != null) {
                scheduledTasks += persisted
                persisted.schedule?.blocks.orEmpty()
                    .filter { it.state == ReviewBlockState.PLANNED && it.end.isAfter(now) }
                    .forEach { block ->
                        placements += ReviewBlockPlacement(
                            taskId = persisted.id,
                            blockId = block.id,
                            repetitionIndex = block.repetitionIndex,
                            start = block.start,
                            end = block.end,
                            timelineEventId = block.timelineEventId
                        )
                    }
            } else {
                unscheduled += UnscheduledReviewTask(
                    task = persisted,
                    reason = dropReasons[spec.task.id] ?: "求解预算内没有可共存的复习方案"
                )
            }
        }

        if ((chosen?.result as? SolveResult.Solutions)?.completion == SolveCompletion.BEST_FOUND_BUDGET_EXHAUSTED) {
            warnings += "求解预算已用尽，返回当前最优可验证方案"
        }
        val status = when {
            scheduledTasks.isNotEmpty() && unscheduled.isEmpty() -> ReviewScheduleResultStatus.SCHEDULED
            scheduledTasks.isNotEmpty() -> ReviewScheduleResultStatus.PARTIAL
            else -> ReviewScheduleResultStatus.INFEASIBLE
        }
        return ReviewScheduleResult(
            status = status,
            scheduledTasks = scheduledTasks,
            unscheduledTasks = unscheduled,
            placements = placements.sortedWith(compareBy<ReviewBlockPlacement> { it.start }.thenBy { it.taskId }),
            solverStats = (chosen?.result as? SolveResult.Solutions)?.stats
                ?: (latest?.result as? SolveResult.Solutions)?.stats
                ?: (latest?.result as? SolveResult.Infeasible)?.stats,
            completion = (chosen?.result as? SolveResult.Solutions)?.completion,
            conflictCore = (latest?.result as? SolveResult.Infeasible)?.conflictCore.orEmpty(),
            warnings = warnings.distinct()
        )
    }

    /**
     * 记录一次复习完成并更新掌握度/记忆状态。
     *
     * 只允许完成当前 PLANNED 或已被判 MISSED 的块；重复完成不会重复累计次数。
     * 完成后的下一次 schedule() 会从 lastReviewedAt 与新掌握度继续推进间隔阶梯。
     */
    suspend fun completeReviewBlock(
        taskId: String,
        blockId: String,
        masteryPercent: Int,
        actor: Actor = Actor.USER
    ): ReviewTask {
        require(masteryPercent in 0..100) { "掌握度必须在 0..100" }
        val task = repository.listReviewTasks(scope).firstOrNull { it.id == taskId }
            ?: throw IllegalArgumentException("复习任务不存在: " + taskId)
        require(task.state == ReviewTaskState.TODO) { "只有 TODO 复习任务能完成复习块" }
        val schedule = task.schedule ?: throw IllegalArgumentException("复习任务还没有排程: " + taskId)
        val block = schedule.blocks.firstOrNull { it.id == blockId }
            ?: throw IllegalArgumentException("复习块不存在: " + blockId)
        require(block.state != ReviewBlockState.DONE) { "复习块已完成，不能重复累计: " + blockId }
        val now = clock()
        val blocks = schedule.blocks.map { current ->
            if (current.id == blockId) current.copy(state = ReviewBlockState.DONE) else current
        }
        val updated = task.copy(
            masteryPercent = masteryPercent,
            successfulReviewCount = task.successfulReviewCount + 1,
            lastReviewedAt = now,
            schedule = schedule.copy(blocks = blocks),
            revision = task.revision + 1L,
            updatedAt = now
        )
        return repository.upsertReviewTask(updated, actor)
    }

    /** 过期仍未完成的 PLANNED 块转为 MISSED；该转换本身也是 Vault 审计写入，绝不做内存态静默丢失。 */
    private suspend fun markExpiredBlocksMissed(task: ReviewTask, now: Instant, actor: Actor): Pair<ReviewTask, Int> {
        val schedule = task.schedule ?: return task to 0
        var missed = 0
        val blocks = schedule.blocks.map { block ->
            if (block.state == ReviewBlockState.PLANNED && !block.end.isAfter(now)) {
                missed++
                block.copy(state = ReviewBlockState.MISSED)
            } else {
                block
            }
        }
        if (missed == 0) return task to 0
        val updated = task.copy(
            lapseCount = task.lapseCount + missed,
            schedule = schedule.copy(blocks = blocks),
            revision = task.revision + 1L,
            updatedAt = now
        )
        return repository.upsertReviewTask(updated, actor) to missed
    }

    private fun resolveDeadline(task: ReviewTask, events: List<TimelineEvent>, now: Instant): DeadlineResolution {
        val futureDeadlines = ArrayList<Instant>()
        var overdue = false
        task.dueAt?.let { due ->
            if (due.isAfter(now)) futureDeadlines += due else overdue = true
        }
        val relatedExams = events.filter { event ->
            event.kind == TimelineKind.EXAM && event.isRelatedTo(task)
        }
        relatedExams.forEach { exam ->
            if (exam.start.isAfter(now)) {
                futureDeadlines += exam.start
            } else {
                overdue = true
            }
        }
        return DeadlineResolution(
            deadlineAt = futureDeadlines.minOrNull(),
            overdue = overdue
        )
    }

    private fun TimelineEvent.isRelatedTo(task: ReviewTask): Boolean {
        if (links.any { it.type == EntityType.COURSE && it.id == task.course.courseId }) return true
        return title.trim() == task.course.courseName.trim()
    }

    private fun busyIntervals(events: List<TimelineEvent>): List<BusyBlock> =
        events.mapNotNull { event ->
            if (event.busy == BusyLevel.FREE || event.status == EventStatus.CANCELLED_TODAY) {
                null
            } else {
                event.windowOrNull?.let { BusyBlock(it.start, it.end, event.busy) }
            }
        }

    private fun taskWithReplacedPlan(
        spec: ReviewTaskSchedulingSpec,
        generated: GeneratedReviewPlan?,
        zone: java.time.ZoneId,
        now: Instant
    ): ReviewTask {
        val task = spec.task
        val existing = task.schedule
        val existingBlocks = existing?.blocks.orEmpty()
        val futurePlanned = existingBlocks.filter { it.state == ReviewBlockState.PLANNED && it.end.isAfter(now) }
        val history = existingBlocks.filterNot { it in futurePlanned }
        if (generated == null && futurePlanned.isEmpty()) return task

        val planned: List<ReviewBlock>
        val planRevision: Long
        val generatedAt: Instant
        val algorithm = existing?.algorithm ?: SpacedRepetitionPolicy.ALGORITHM_ID
        if (generated != null && existing != null && futurePlanned.sameTimingAs(generated) && existing.deadlineAt == spec.deadlineAt) {
            val byRepetition = futurePlanned.associateBy { it.repetitionIndex }
            planned = generated.blocks.map { block -> byRepetition.getValue(block.repetitionIndex) }
            planRevision = existing.planRevision
            generatedAt = existing.generatedAt
        } else {
            planRevision = (existing?.planRevision ?: 0L) + 1L
            generatedAt = now
            planned = generated?.blocks.orEmpty().map { block ->
                ReviewBlock(
                    id = reviewBlockId(task.id, planRevision, block.repetitionIndex),
                    repetitionIndex = block.repetitionIndex,
                    targetStart = block.targetStart,
                    start = block.start,
                    end = block.end,
                    state = ReviewBlockState.PLANNED,
                    timelineEventId = reviewBlockEventId(task.id, planRevision, block.repetitionIndex)
                )
            }
        }
        val blocks = (history + planned).sortedWith(compareBy<ReviewBlock> { it.start }.thenBy { it.repetitionIndex })
        val schedule = if (blocks.isEmpty()) {
            null
        } else {
            ReviewSchedule(
                algorithm = algorithm,
                planRevision = planRevision,
                generatedAt = generatedAt,
                zone = zone,
                deadlineAt = spec.deadlineAt,
                blocks = blocks
            )
        }
        if (schedule == task.schedule) return task
        return task.copy(
            schedule = schedule,
            revision = task.revision + 1L,
            updatedAt = now
        )
    }

    private fun List<ReviewBlock>.sameTimingAs(plan: GeneratedReviewPlan): Boolean {
        val left = sortedBy { it.repetitionIndex }.map { BlockTiming(it.repetitionIndex, it.targetStart, it.start, it.end) }
        val right = plan.blocks.sortedBy { it.repetitionIndex }.map { BlockTiming(it.repetitionIndex, it.targetStart, it.start, it.end) }
        return left == right
    }

    private fun ReviewTask.futurePlannedBlocks(now: Instant): List<ReviewBlock> =
        schedule?.blocks.orEmpty().filter { it.state == ReviewBlockState.PLANNED && it.end.isAfter(now) }

    private fun ReviewTask.hasOngoingPlannedBlock(now: Instant): Boolean =
        schedule?.blocks.orEmpty().any {
            it.state == ReviewBlockState.PLANNED && it.start.isBefore(now) && it.end.isAfter(now)
        }

    private fun solverFailureDescription(result: SolveResult?): String = when (result) {
        is SolveResult.Infeasible -> if (result.conflictCore.isEmpty()) {
            "空闲块无法满足全部复习任务的硬约束"
        } else {
            "空闲块冲突：" + result.conflictCore.joinToString("、") { it.label }
        }
        is SolveResult.Solutions -> "求解预算内没有找到完整共存方案"
        null -> "没有可参与求解的候选方案"
    }

    private fun ScheduleVerificationReport?.orEmptyViolations(): List<String> = this?.violations.orEmpty()

    private fun minInstant(left: Instant, right: Instant): Instant = if (left.isBefore(right)) left else right

    private data class DeadlineResolution(val deadlineAt: Instant?, val overdue: Boolean)
    private data class BlockTiming(val repetitionIndex: Int, val targetStart: Instant, val start: Instant, val end: Instant)

    companion object {
        private val MAX_SCHEDULING_WINDOW: Duration = Duration.ofDays(62)

        fun reviewBlockEventId(taskId: String, planRevision: Long, repetitionIndex: Int): TimelineEventId =
            TimelineEventId(
                "personal:review-block-" + sha256Hex(
                    (taskId + "|" + planRevision + "|" + repetitionIndex).toByteArray(Charsets.UTF_8)
                ).take(40)
            )

        private fun reviewBlockId(taskId: String, planRevision: Long, repetitionIndex: Int): String =
            "rb-" + sha256Hex(
                ("block|" + taskId + "|" + planRevision + "|" + repetitionIndex).toByteArray(Charsets.UTF_8)
            ).take(28)
    }
}

