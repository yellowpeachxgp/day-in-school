package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.packages.datapack.DisDatapackItem
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 办事流程运行器。所有分支都来自已验签 datapack 的固定字段；
 * 运行状态只保存答案、完成步骤和用户显式计划时间，路径由确定性求值器重建。
 */
class FlowRunner(
    private val store: GuideProgressStore,
    private val clock: Clock = Clock.systemUTC()
) {

    suspend fun start(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String
    ): ProcedureRunState {
        val item = requireProcedure(guidePackage, itemId)
        requireScopeSchool(scope, guidePackage)
        val existing = store.loadProcedureRun(scope, guidePackage.packId, itemId)
        val snapshot = if (existing == null) {
            val now = clock.instant()
            ProcedureRunSnapshot(
                scope = scope,
                packId = guidePackage.packId,
                itemId = item.id,
                packVersion = guidePackage.version,
                artifactSha256 = guidePackage.artifactSha256,
                createdAt = now,
                updatedAt = now,
                revision = 1
            ).also { store.saveProcedureRun(it) }
        } else {
            ensureCompatible(existing, guidePackage, itemId)
            existing
        }
        return evaluate(guidePackage, itemId, snapshot)
    }

    /** 精确恢复同 artifact 的进度；包内容变化时显式报告，绝不把旧答案套进新定义。 */
    suspend fun restore(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String
    ): GuideRestoreResult {
        requireProcedure(guidePackage, itemId)
        requireScopeSchool(scope, guidePackage)
        val snapshot = store.loadProcedureRun(scope, guidePackage.packId, itemId)
            ?: return GuideRestoreResult.NotFound
        if (!isCompatible(snapshot, guidePackage, itemId)) {
            return GuideRestoreResult.DefinitionChanged(snapshot.artifactSha256, guidePackage.artifactSha256)
        }
        return GuideRestoreResult.Restored(evaluate(guidePackage, itemId, snapshot))
    }

    fun evaluate(
        guidePackage: GuidePackage,
        itemId: String,
        snapshot: ProcedureRunSnapshot
    ): ProcedureRunState {
        val item = requireProcedure(guidePackage, itemId)
        ensureCompatible(snapshot, guidePackage, itemId)
        val evaluation = ProcedurePathEvaluator.evaluate(item, snapshot.answers, snapshot.completedStepIds)
        return ProcedureRunState(
            snapshot = snapshot,
            path = evaluation.path,
            stepStates = evaluation.stepStates,
            currentStep = evaluation.currentStep,
            pendingQuestion = evaluation.pendingQuestion,
            actionableQuestion = evaluation.actionableQuestion,
            status = evaluation.status,
            completedCount = evaluation.completedCount,
            resolvedStepCount = evaluation.resolvedStepCount
        )
    }

    suspend fun answer(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String,
        questionId: String,
        value: String
    ): ProcedureRunState {
        val snapshot = loadCompatible(scope, guidePackage, itemId)
        val state = evaluate(guidePackage, itemId, snapshot)
        val pending = state.actionableQuestion
            ?: throw GuideOperationException("当前没有可回答的流程问题：" + questionId)
        if (pending.id != questionId) {
            throw GuideOperationException("流程问题必须按分支触发顺序回答；当前问题是 " + pending.id)
        }
        if (pending.options.none { it.value == value }) {
            throw GuideOperationException("答案不属于声明式选项：" + questionId)
        }
        val updated = touch(snapshot.copy(answers = snapshot.answers + (questionId to value)))
        store.saveProcedureRun(updated)
        return evaluate(guidePackage, itemId, updated)
    }

    suspend fun completeCurrentStep(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String,
        stepId: String
    ): ProcedureRunState {
        val snapshot = loadCompatible(scope, guidePackage, itemId)
        val state = evaluate(guidePackage, itemId, snapshot)
        val current = state.currentStep
            ?: throw GuideOperationException("当前没有可完成的步骤：" + stepId)
        if (current.id != stepId) {
            throw GuideOperationException("流程步骤必须按个人路径顺序完成；当前步骤是 " + current.id)
        }
        val updated = touch(snapshot.copy(completedStepIds = snapshot.completedStepIds + stepId))
        store.saveProcedureRun(updated)
        return evaluate(guidePackage, itemId, updated)
    }

    /**
     * 为路径内步骤登记用户显式计划时间。duration 为空时只使用数据包 estimatedMinutes；
     * 仍得不到正时长时，Timeline 层必须投影 UNCERTAIN，不允许伪造占用窗口。
     */
    suspend fun scheduleStep(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String,
        stepId: String,
        startAt: Instant,
        duration: Duration? = null
    ): ProcedureRunState {
        val snapshot = loadCompatible(scope, guidePackage, itemId)
        val state = evaluate(guidePackage, itemId, snapshot)
        if (state.stepStates.none { it.id == stepId }) {
            throw GuideOperationException("只能为当前个人路径内步骤登记计划时间：" + stepId)
        }
        val schedule = ScheduledStep(startAt, duration)
        val updated = touch(snapshot.copy(scheduledSteps = snapshot.scheduledSteps + (stepId to schedule)))
        store.saveProcedureRun(updated)
        return evaluate(guidePackage, itemId, updated)
    }

    suspend fun unscheduleStep(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String,
        stepId: String
    ): ProcedureRunState {
        val snapshot = loadCompatible(scope, guidePackage, itemId)
        if (!snapshot.scheduledSteps.containsKey(stepId)) {
            throw GuideOperationException("步骤没有已登记的计划时间：" + stepId)
        }
        val updated = touch(snapshot.copy(scheduledSteps = snapshot.scheduledSteps - stepId))
        store.saveProcedureRun(updated)
        return evaluate(guidePackage, itemId, updated)
    }

    /**
     * 允许用户修正早前回答。保守清除该问题及触发点不早于它的全部下游问题答案，
     * 防止旧分支答案在新分支语义中被误用；完成历史保留但只对仍在新路径中的步骤计数。
     */
    suspend fun clearAnswerFrom(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String,
        questionId: String
    ): ProcedureRunState {
        val snapshot = loadCompatible(scope, guidePackage, itemId)
        val item = requireProcedure(guidePackage, itemId)
        val index = item.questions.indexOfFirst { it.id == questionId }
        if (index < 0) throw GuideOperationException("问题不存在：" + questionId)
        if (!snapshot.answers.containsKey(questionId)) {
            throw GuideOperationException("问题尚未回答，不能清除：" + questionId)
        }
        val stepIndex = item.steps.mapIndexed { index, step -> step.id to index }.toMap()
        val triggerByQuestion = ProcedurePathEvaluator.triggerQuestions(item, stepIndex)
            .flatMap { (trigger, questions) -> questions.map { it.id to trigger } }
            .toMap()
        val cutoff = triggerByQuestion[questionId]
            ?: throw GuideOperationException("问题缺少确定触发点：" + questionId)
        val idsToClear = item.questions
            .filter { question -> (triggerByQuestion[question.id] ?: 0) >= cutoff }
            .mapTo(hashSetOf()) { it.id }
        val updated = touch(snapshot.copy(answers = snapshot.answers.filterKeys { it !in idsToClear }))
        store.saveProcedureRun(updated)
        return evaluate(guidePackage, itemId, updated)
    }

    /** 显式重新开始：删除持久快照后创建空进度。调用方必须先获得用户确认。 */
    suspend fun restart(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String
    ): ProcedureRunState {
        requireProcedure(guidePackage, itemId)
        requireScopeSchool(scope, guidePackage)
        store.deleteProcedureRun(scope, guidePackage.packId, itemId)
        return start(scope, guidePackage, itemId)
    }

    private suspend fun loadCompatible(
        scope: SchoolAccountScope,
        guidePackage: GuidePackage,
        itemId: String
    ): ProcedureRunSnapshot {
        requireScopeSchool(scope, guidePackage)
        val snapshot = store.loadProcedureRun(scope, guidePackage.packId, itemId)
            ?: throw GuideOperationException("流程进度不存在：" + itemId)
        ensureCompatible(snapshot, guidePackage, itemId)
        return snapshot
    }

    private fun requireProcedure(guidePackage: GuidePackage, itemId: String): DisDatapackItem.ProcedureGuide =
        guidePackage.procedureOrNull(itemId)
            ?: throw GuideDefinitionException("数据包不包含办事流程条目：" + itemId)

    private fun requireScopeSchool(scope: SchoolAccountScope, guidePackage: GuidePackage) {
        if (scope.schoolId.value != guidePackage.schoolId) {
            throw GuideOperationException("流程数据包学校与当前 scope 不一致")
        }
    }

    private fun ensureCompatible(snapshot: ProcedureRunSnapshot, guidePackage: GuidePackage, itemId: String) {
        if (!isCompatible(snapshot, guidePackage, itemId)) {
            throw GuideOperationException("流程定义已变化，拒绝把旧进度静默套用到新 artifact")
        }
    }

    private fun isCompatible(snapshot: ProcedureRunSnapshot, guidePackage: GuidePackage, itemId: String): Boolean =
        snapshot.packId == guidePackage.packId &&
            snapshot.itemId == itemId &&
            snapshot.packVersion == guidePackage.version &&
            snapshot.artifactSha256 == guidePackage.artifactSha256

    private fun touch(snapshot: ProcedureRunSnapshot): ProcedureRunSnapshot =
        snapshot.copy(updatedAt = clock.instant(), revision = snapshot.revision + 1)
}
