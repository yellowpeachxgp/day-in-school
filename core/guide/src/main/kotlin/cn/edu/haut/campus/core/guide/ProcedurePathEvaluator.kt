package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.packages.datapack.DisDatapackItem
import cn.edu.haut.campus.core.packages.datapack.ProcedureQuestion

/** 一次确定性求值结果；个人路径只包含已解析步骤，不用默认分支冒充未知分支。 */
data class ProcedureEvaluation(
    val path: PersonalPath,
    val stepStates: List<GuidePathStep>,
    val currentStep: GuidePathStep?,
    val pendingQuestion: ProcedureQuestion?,
    val actionableQuestion: ProcedureQuestion?,
    val status: GuideRunStatus,
    val completedCount: Int,
    val resolvedStepCount: Int
)

/**
 * dis-datapack/v1 流程分支的保守解释器。
 *
 * 语义固定且声明式：steps 是有序前向控制流；question 在「其任意选项目标的最小下标」
 * 处触发；答案的 nextStepId 只能向前跳转（相同下标视为不跳转），被跳过的步骤与问题
 * 不属于本次个人路径。nextStepId=null 表示不改写顺序。该解释没有 eval、脚本、反射或
 * 任意表达式；遇到尚未回答的问题就停止求值，绝不猜测后续路径。
 */
object ProcedurePathEvaluator {

    fun evaluate(
        item: DisDatapackItem.ProcedureGuide,
        answers: Map<String, String>,
        completedStepIds: List<String>
    ): ProcedureEvaluation {
        validateAnswers(item, answers)
        validateCompletedSteps(item, completedStepIds)
        val stepIndex = item.steps.mapIndexed { index, step -> step.id to index }.toMap()
        val triggers = triggerQuestions(item, stepIndex)
        val route = mutableListOf<cn.edu.haut.campus.core.packages.datapack.ProcedureStep>()
        var cursor = 0
        var blocked: ProcedureQuestion? = null

        while (cursor < item.steps.size) {
            val questionsHere = triggers[cursor].orEmpty()
            var jumpTo: Int? = null
            for (question in questionsHere) {
                val answer = answers[question.id]
                if (answer == null) {
                    blocked = question
                    break
                }
                val option = question.options.firstOrNull { it.value == answer }
                    ?: throw GuidePersistenceException("持久化答案不属于声明式选项：" + question.id)
                val target = option.nextStepId
                if (target != null) {
                    val targetIndex = stepIndex[target]
                        ?: throw GuideDefinitionException("流程分支目标不存在：" + target)
                    if (targetIndex < cursor) {
                        throw GuideDefinitionException("流程分支不得向后跳转形成循环：" + question.id)
                    }
                    if (targetIndex > cursor) {
                        jumpTo = targetIndex
                        break
                    }
                }
            }
            if (blocked != null) break
            if (jumpTo != null) {
                cursor = jumpTo
                continue
            }
            route += item.steps[cursor]
            cursor += 1
        }

        val completedSet = completedStepIds.toSet()
        val currentIndex = route.indexOfFirst { it.id !in completedSet }.takeIf { it >= 0 }
        val stepStates = route.mapIndexed { index, step ->
            GuidePathStep(
                id = step.id,
                title = step.title,
                instruction = step.instruction,
                placeRef = step.placeRef,
                estimatedMinutes = step.estimatedMinutes,
                status = when {
                    step.id in completedSet -> GuideStepStatus.COMPLETED
                    index == currentIndex -> GuideStepStatus.CURRENT
                    else -> GuideStepStatus.UPCOMING
                }
            )
        }
        val current = currentIndex?.let { index -> stepStates[index] }
        val actionable = if (blocked != null && current == null) blocked else null
        val status = when {
            blocked == null && current == null -> GuideRunStatus.COMPLETED
            actionable != null -> GuideRunStatus.AWAITING_QUESTION
            else -> GuideRunStatus.IN_PROGRESS
        }
        val path = PersonalPath(
            steps = route.toList(),
            materials = item.materials,
            complete = blocked == null,
            blockedByQuestionId = blocked?.id
        )
        return ProcedureEvaluation(
            path = path,
            stepStates = stepStates,
            currentStep = current,
            pendingQuestion = blocked,
            actionableQuestion = actionable,
            status = status,
            completedCount = route.count { it.id in completedSet },
            resolvedStepCount = route.size
        )
    }

    /** 分支问题触发点是其最小目标下标；无目标的问题是流程开始前的事实采集。 */
    internal fun triggerQuestions(
        item: DisDatapackItem.ProcedureGuide,
        stepIndex: Map<String, Int>
    ): Map<Int, List<ProcedureQuestion>> = item.questions.groupBy { question ->
        val targets = question.options.mapNotNull { it.nextStepId }
        if (targets.isEmpty()) 0 else targets.minOf { stepIndex.getValue(it) }
    }

    private fun validateAnswers(item: DisDatapackItem.ProcedureGuide, answers: Map<String, String>) {
        val questions = item.questions.associateBy { it.id }
        answers.forEach { (questionId, value) ->
            val question = questions[questionId]
                ?: throw GuidePersistenceException("持久化答案引用不存在的问题：" + questionId)
            if (question.options.none { it.value == value }) {
                throw GuidePersistenceException("持久化答案不属于声明式选项：" + questionId)
            }
        }
    }

    private fun validateCompletedSteps(item: DisDatapackItem.ProcedureGuide, completedStepIds: List<String>) {
        val stepIds = item.steps.mapTo(hashSetOf()) { it.id }
        completedStepIds.forEach { stepId ->
            if (stepId !in stepIds) {
                throw GuidePersistenceException("持久化进度引用不存在的步骤：" + stepId)
            }
        }
    }
}
