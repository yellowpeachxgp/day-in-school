package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.events.DomainEventBus
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.timeline.TimelineService
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

class AutomationRuleNotFoundException(ruleId: String) :
    IllegalArgumentException("自动化规则不存在: " + ruleId)

class CrossScopeAutomationException(ruleId: String) :
    IllegalArgumentException("触发事件与自动化规则 scope 不一致: " + ruleId)

sealed interface TriggerExecutionResult {
    data class Executed(val run: AutomationRun) : TriggerExecutionResult
    data class Duplicate(val dedupeKey: String, val existingRun: AutomationRun?) : TriggerExecutionResult
}

interface AutomationEngine {
    /** 试运行：求值规则并预览动作，但不调用 ActionExecutor、不写 dedupe/run store。 */
    suspend fun dryRun(rule: AutomationRule, at: Instant): AutomationRun

    /** 幂等键 = ruleId + triggerInstanceKey（或 DedupePolicy 指定的更粗时间桶）。 */
    suspend fun onTrigger(ruleId: String, triggerInstanceKey: String)

    /** 带事件的完整触发入口；重复实例返回 Duplicate，不追加第二份运行记录。 */
    suspend fun executeTrigger(instance: TriggerInstance): TriggerExecutionResult

    fun runs(ruleId: String): Flow<List<AutomationRun>>
}

/** MASTER_PLAN §6.3 的纯 JVM 自动化引擎核心；调度、Room 持久化和 UI 由后续卡接入。 */
class DefaultAutomationEngine(
    private val ruleStore: AutomationRuleStore,
    private val runStore: AutomationRunStore,
    private val dedupeStore: AutomationDedupeStore,
    private val registry: AutomationExtensionRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val timelineService: TimelineService? = null,
    private val domainEventBus: DomainEventBus? = null,
    private val confirmationPort: ActionConfirmationPort? = null
) : AutomationEngine {

    override suspend fun dryRun(rule: AutomationRule, at: Instant): AutomationRun {
        val triggerKey = AutomationKeys.dryRunTriggerInstanceKey(rule.id, at)
        val instance = TriggerInstance(
            ruleId = rule.id,
            triggerInstanceKey = triggerKey,
            firedAt = at,
            source = "dry-run"
        )
        return executeRule(rule, instance, dryRun = true)
    }

    override suspend fun onTrigger(ruleId: String, triggerInstanceKey: String) {
        executeTrigger(
            TriggerInstance(
                ruleId = ruleId,
                triggerInstanceKey = triggerInstanceKey,
                firedAt = clock.instant(),
                source = "manual"
            )
        )
    }

    override suspend fun executeTrigger(instance: TriggerInstance): TriggerExecutionResult {
        val rule = ruleStore.get(instance.ruleId) ?: throw AutomationRuleNotFoundException(instance.ruleId)
        val event = instance.event
        if (event != null && event.scope != rule.scope) throw CrossScopeAutomationException(rule.id)

        val dedupeKey = AutomationKeys.dedupeKey(rule.id, instance.triggerInstanceKey, rule.dedupe, instance.firedAt)
        val runId = AutomationKeys.runId(rule.id, instance.triggerInstanceKey, instance.firedAt)
        val acquire = dedupeStore.tryAcquire(
            AutomationDedupeRecord(
                dedupeKey = dedupeKey,
                ruleId = rule.id,
                triggerInstanceKey = instance.triggerInstanceKey,
                runId = runId,
                acquiredAt = clock.instant(),
                policyFingerprint = AutomationKeys.policyFingerprint(rule.dedupe)
            )
        )
        if (acquire is DedupeAcquireResult.AlreadyExists) {
            return TriggerExecutionResult.Duplicate(
                dedupeKey = dedupeKey,
                existingRun = runStore.get(acquire.existing.runId)
            )
        }

        val run = executeRule(rule, instance, dryRun = false)
        runStore.append(run)
        return TriggerExecutionResult.Executed(run)
    }

    override fun runs(ruleId: String): Flow<List<AutomationRun>> = runStore.runs(ruleId)

    private suspend fun executeRule(
        rule: AutomationRule,
        instance: TriggerInstance,
        dryRun: Boolean
    ): AutomationRun {
        val startedAt = clock.instant()
        val dedupeKey = AutomationKeys.dedupeKey(rule.id, instance.triggerInstanceKey, rule.dedupe, instance.firedAt)
        val runId = AutomationKeys.runId(rule.id, instance.triggerInstanceKey, instance.firedAt)
        val context = RunContext(
            scope = rule.scope,
            rule = rule,
            triggerInstanceKey = instance.triggerInstanceKey,
            firedAt = instance.firedAt,
            now = startedAt,
            dryRun = dryRun,
            triggerEvent = instance.event,
            timelineService = timelineService,
            domainEventBus = domainEventBus
        )

        if (!rule.enabled) {
            return buildRun(
                runId = runId,
                rule = rule,
                instance = instance,
                dedupeKey = dedupeKey,
                startedAt = startedAt,
                evaluations = emptyList(),
                outcomes = emptyList(),
                status = RunStatus.DISABLED,
                dryRun = dryRun,
                statusReason = "规则处于禁用状态"
            )
        }

        val validationProblems = validateRuleAgainstRegistry(rule)
        if (validationProblems.isNotEmpty()) {
            return buildRun(
                runId = runId,
                rule = rule,
                instance = instance,
                dedupeKey = dedupeKey,
                startedAt = startedAt,
                evaluations = emptyList(),
                outcomes = emptyList(),
                status = RunStatus.INVALID_RULE,
                dryRun = dryRun,
                statusReason = validationProblems.take(6).joinToString("; ")
            )
        }

        val evaluations = evaluateConditions(rule, context)
        val failures = evaluations.mapIndexedNotNull { index, result ->
            if (result.effectivePassed) null else {
                val reason = result.reason ?: "未通过"
                "condition[" + index + "] " + result.conditionType + ": " + reason
            }
        }
        if (failures.isNotEmpty()) {
            return buildRun(
                runId = runId,
                rule = rule,
                instance = instance,
                dedupeKey = dedupeKey,
                startedAt = startedAt,
                evaluations = evaluations,
                outcomes = emptyList(),
                status = RunStatus.CONDITIONS_NOT_MET,
                dryRun = dryRun,
                statusReason = failures.take(6).joinToString("; ")
            )
        }

        val outcomes = if (dryRun) {
            previewActions(rule, context)
        } else {
            executeActions(rule, context)
        }
        val status = deriveStatus(dryRun, outcomes)
        return buildRun(
            runId = runId,
            rule = rule,
            instance = instance,
            dedupeKey = dedupeKey,
            startedAt = startedAt,
            evaluations = evaluations,
            outcomes = outcomes,
            status = status,
            dryRun = dryRun,
            statusReason = null
        )
    }

    private fun buildRun(
        runId: String,
        rule: AutomationRule,
        instance: TriggerInstance,
        dedupeKey: String,
        startedAt: Instant,
        evaluations: List<ConditionResult>,
        outcomes: List<ActionOutcome>,
        status: RunStatus,
        dryRun: Boolean,
        statusReason: String?
    ): AutomationRun = AutomationRun(
        runId = runId,
        ruleId = rule.id,
        triggerInstanceKey = instance.triggerInstanceKey,
        firedAt = instance.firedAt,
        evaluations = evaluations,
        outcomes = outcomes,
        status = status,
        dryRun = dryRun,
        dedupeKey = dedupeKey,
        startedAt = startedAt,
        finishedAt = clock.instant(),
        statusReason = statusReason
    )

    private fun validateRuleAgainstRegistry(rule: AutomationRule): List<String> {
        val problems = ArrayList<String>()
        val triggerProvider = registry.triggerProvider(rule.trigger.type)
        if (triggerProvider == null) {
            problems += "trigger 未注册: " + rule.trigger.type
        } else {
            val pluginTrigger = rule.trigger as? TriggerSpec.Plugin
            if (pluginTrigger != null) {
                triggerProvider.configSchema.validate(pluginTrigger.config).take(4).forEach { violation ->
                    problems += "trigger config " + violation.path + " " + violation.rule
                }
            }
        }

        rule.conditions.forEachIndexed { index, spec ->
            val evaluator = registry.conditionEvaluator(spec.type)
            if (evaluator == null) {
                problems += "condition[" + index + "] 未注册: " + spec.type
            } else {
                evaluator.configSchema.validate(spec.config).take(4).forEach { violation ->
                    problems += "condition[" + index + "] config " + violation.path + " " + violation.rule
                }
            }
        }

        rule.actions.forEachIndexed { index, spec ->
            val executor = registry.actionExecutor(spec.type)
            if (executor == null) {
                problems += "action[" + index + "] 未注册: " + spec.type
            } else {
                if (executor.risk == ActionRisk.EXTERNAL_WRITE_FORBIDDEN) {
                    problems += "action[" + index + "] 外部写动作禁止注册: " + spec.type
                }
                executor.configSchema.validate(spec.config).take(4).forEach { violation ->
                    problems += "action[" + index + "] config " + violation.path + " " + violation.rule
                }
            }
        }
        return problems.map { sanitizeDetail(it, 180) }
    }

    private suspend fun evaluateConditions(
        rule: AutomationRule,
        context: RunContext
    ): List<ConditionResult> = rule.conditions.map { spec ->
        val evaluator = registry.requireConditionEvaluator(spec.type)
        val raw = try {
            evaluator.evaluate(spec, context)
        } catch (error: Throwable) {
            if (error is CancellationException || error is Error) throw error
            ConditionResult(
                conditionType = spec.type,
                passed = false,
                certainty = Certainty.UNKNOWN,
                reason = "条件求值异常（" + safeErrorName(error) + "），按不通过"
            )
        }
        normalizeConditionResult(spec, raw)
    }

    private fun normalizeConditionResult(spec: ConditionSpec, raw: ConditionResult): ConditionResult {
        var result = raw
        if (result.conditionType != spec.type) {
            result = result.copy(
                conditionType = spec.type,
                reason = appendReason(result.reason, "求值器返回类型不匹配，已按规则条件类型记录")
            )
        }
        if (result.certainty == Certainty.UNKNOWN) {
            result = result.copy(
                passed = false,
                reason = appendReason(result.reason, "certainty=UNKNOWN，视为不通过")
            )
        }
        if (!result.passed && result.reason == null) {
            result = result.copy(reason = "条件未通过")
        }
        return result.copy(evidence = result.evidence.take(MAX_EVIDENCE_PER_STEP))
    }

    private fun previewActions(rule: AutomationRule, context: RunContext): List<ActionOutcome> {
        val quietActive = isQuietHoursActive(rule, context.firedAt)
        return rule.actions.map { spec ->
            val executor = registry.requireActionExecutor(spec.type)
            val suffix = when {
                executor.risk == ActionRisk.NEEDS_USER_CONFIRM -> "；真实执行前需要一次性用户确认"
                executor.risk == ActionRisk.LOCAL_NOTIFY && quietActive -> "；当前处于 quietHours，真实执行会抑制通知"
                else -> ""
            }
            ActionOutcome(
                actionType = spec.type,
                risk = executor.risk,
                status = ActionOutcomeStatus.DRY_RUN_PREVIEW,
                detail = "dry-run 预览：不会执行副作用" + suffix
            )
        }
    }

    private suspend fun executeActions(rule: AutomationRule, context: RunContext): List<ActionOutcome> {
        val quietActive = isQuietHoursActive(rule, context.firedAt)
        return rule.actions.map { spec ->
            val executor = registry.requireActionExecutor(spec.type)
            when {
                executor.risk == ActionRisk.EXTERNAL_WRITE_FORBIDDEN -> ActionOutcome(
                    actionType = spec.type,
                    risk = executor.risk,
                    status = ActionOutcomeStatus.FAILED,
                    detail = "外部写动作被安全边界拒绝"
                )
                executor.risk == ActionRisk.LOCAL_NOTIFY && quietActive -> ActionOutcome(
                    actionType = spec.type,
                    risk = executor.risk,
                    status = ActionOutcomeStatus.SKIPPED_QUIET_HOURS,
                    detail = "quietHours 生效，通知动作已抑制"
                )
                executor.risk == ActionRisk.NEEDS_USER_CONFIRM -> {
                    val confirmation = validConfirmation(rule, spec, context)
                    if (confirmation == null) {
                        ActionOutcome(
                            actionType = spec.type,
                            risk = executor.risk,
                            status = ActionOutcomeStatus.BLOCKED_CONFIRMATION_REQUIRED,
                            detail = "缺少有效的一次性用户确认，动作未执行"
                        )
                    } else {
                        invokeExecutor(spec, executor, context.copy(confirmation = confirmation))
                    }
                }
                else -> invokeExecutor(spec, executor, context)
            }
        }
    }

    private suspend fun validConfirmation(
        rule: AutomationRule,
        spec: ActionSpec,
        context: RunContext
    ): ActionConfirmation? {
        val port = confirmationPort ?: return null
        val confirmation = try {
            port.confirmationFor(rule, spec, context)
        } catch (error: Throwable) {
            if (error is CancellationException || error is Error) throw error
            null
        } ?: return null
        val digest = AutomationKeys.sha256Hex(Json.canonical(spec.config))
        return confirmation.takeIf {
            it.ruleId == rule.id &&
                it.actionType == spec.type &&
                it.configDigest == digest &&
                it.isValidAt(context.now)
        }
    }

    private suspend fun invokeExecutor(
        spec: ActionSpec,
        executor: ActionExecutor,
        context: RunContext
    ): ActionOutcome {
        val startedAt = clock.instant()
        return try {
            val raw = executor.execute(spec, context)
            normalizeActionOutcome(spec, executor, raw, startedAt, clock.instant())
        } catch (error: Throwable) {
            if (error is CancellationException || error is Error) throw error
            ActionOutcome(
                actionType = spec.type,
                risk = executor.risk,
                status = ActionOutcomeStatus.FAILED,
                detail = "动作执行异常（" + safeErrorName(error) + "）",
                startedAt = startedAt,
                finishedAt = clock.instant()
            )
        }
    }

    private fun normalizeActionOutcome(
        spec: ActionSpec,
        executor: ActionExecutor,
        raw: ActionOutcome,
        startedAt: Instant,
        finishedAt: Instant
    ): ActionOutcome {
        var outcome = raw.copy(
            actionType = spec.type,
            risk = executor.risk,
            startedAt = raw.startedAt ?: startedAt,
            finishedAt = raw.finishedAt ?: finishedAt,
            evidence = raw.evidence.take(MAX_EVIDENCE_PER_STEP)
        )
        if (outcome.status == ActionOutcomeStatus.DRY_RUN_PREVIEW) {
            outcome = outcome.copy(
                status = ActionOutcomeStatus.FAILED,
                detail = appendReason(outcome.detail, "真实执行中执行器错误返回 dry-run 状态")
            )
        }
        return outcome
    }

    private fun deriveStatus(dryRun: Boolean, outcomes: List<ActionOutcome>): RunStatus {
        if (dryRun) return RunStatus.WOULD_EXECUTE
        val success = outcomes.count { it.status == ActionOutcomeStatus.SUCCESS }
        val failed = outcomes.count { it.status == ActionOutcomeStatus.FAILED }
        val suppressed = outcomes.count {
            it.status == ActionOutcomeStatus.BLOCKED_CONFIRMATION_REQUIRED ||
                it.status == ActionOutcomeStatus.SKIPPED_QUIET_HOURS
        }
        return when {
            failed == 0 && success == 0 && suppressed > 0 -> RunStatus.SUPPRESSED
            failed == 0 -> RunStatus.EXECUTED
            success > 0 || suppressed > 0 -> RunStatus.PARTIAL_FAILED
            else -> RunStatus.FAILED
        }
    }

    private fun isQuietHoursActive(rule: AutomationRule, at: Instant): Boolean {
        val quietHours = rule.quietHours ?: return false
        val local = at.atZone(quietHours.zone).toLocalTime()
        val start = quietHours.window.start
        val end = quietHours.window.end
        return if (quietHours.window.crossesMidnight) {
            !local.isBefore(start) || local.isBefore(end)
        } else {
            !local.isBefore(start) && local.isBefore(end)
        }
    }

    private fun appendReason(existing: String?, addition: String): String =
        if (existing.isNullOrBlank()) addition else sanitizeDetail(existing + "；" + addition, 512)

    private fun safeErrorName(error: Throwable): String = error::class.simpleName ?: "Throwable"

    private fun sanitizeDetail(value: String, maxLength: Int): String {
        val cleaned = value.filterNot { it.isISOControl() }
        return if (cleaned.length <= maxLength) cleaned else cleaned.substring(0, maxLength)
    }

    private companion object {
        const val MAX_EVIDENCE_PER_STEP: Int = 8
    }
}
