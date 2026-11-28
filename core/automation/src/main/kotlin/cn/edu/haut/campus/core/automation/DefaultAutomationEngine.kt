package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.events.DomainEventBus
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.timeline.TimelineService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class AutomationRuleNotFoundException(ruleId: String) :
    IllegalArgumentException("自动化规则不存在: " + ruleId)

class CrossScopeAutomationException(ruleId: String) :
    IllegalArgumentException("触发事件与自动化规则 scope 不一致: " + ruleId)

/**
 * OnEvent 规则的触发实例必须携带 DomainEvent。无 event 的 OnEvent 触发只能是实现错误或
 * 绕过 eventId 去重的尝试：fail-closed 在 dedupe/run 写入之前拒绝，不消耗任何执行权，
 * 因此合法事件随后到达时仍能正常执行。异常消息即为拒绝原因。
 */
class OnEventTriggerMissingEventException(ruleId: String) :
    IllegalArgumentException("OnEvent 规则触发必须携带 DomainEvent，拒绝无 event 触发: " + ruleId)

/**
 * OnEvent 规则触发携带的事件必须与规则声明的 eventType/filter 一致。不匹配说明调用方
 * 绕过了订阅侧过滤，fail-closed 在 dedupe/run 写入之前拒绝。reason 不携带 payload 内容。
 */
class OnEventTriggerEventMismatchException(ruleId: String, reason: String) :
    IllegalArgumentException("OnEvent 规则触发事件与规则声明不一致: " + ruleId + "；" + reason)

/** 当前 executionOwner 在副作用或 run 持久化边界前失去 dedupe lease。 */
class AutomationLeaseLostException(dedupeKey: String) :
    IllegalStateException("自动化执行 lease 已丢失，停止 stale owner 写入: " + dedupeKey)

sealed interface TriggerExecutionResult {
    data class Executed(val run: AutomationRun) : TriggerExecutionResult
    data class Duplicate(val dedupeKey: String, val existingRun: AutomationRun?) : TriggerExecutionResult
}

interface AutomationEngine {
    /** 试运行：求值规则并预览动作，但不调用 ActionExecutor、不写 dedupe/run/checkpoint store。 */
    suspend fun dryRun(rule: AutomationRule, at: Instant): AutomationRun

    /**
     * 首期幂等键严格由 ruleId + triggerInstanceKey 派生；同一触发实例只能执行一次。
     * 本入口不携带事件，只适用于非 OnEvent 触发器；OnEvent 规则必须走 executeTrigger 并携带
     * DomainEvent，否则按 OnEventTriggerMissingEventException fail-closed 拒绝。
     */
    suspend fun onTrigger(ruleId: String, triggerInstanceKey: String)

    /**
     * 带事件的完整触发入口；DomainEvent key 由 eventId 强制派生，重复实例返回 Duplicate，不追加第二份运行记录。
     * OnEvent 规则的实例必须携带与规则声明 eventType/filter 一致的事件，否则在任何 dedupe/run
     * 写入之前 fail-closed 拒绝；因此被拒绝的调用不会消耗合法事件的执行权。
     */
    suspend fun executeTrigger(instance: TriggerInstance): TriggerExecutionResult

    fun runs(ruleId: String): Flow<List<AutomationRun>>
}

/**
 * MASTER_PLAN §6.3 的纯 JVM 自动化引擎核心。
 *
 * W2-02 在 W2-01 的 PENDING lease/终态幂等之上补齐三条硬边界：
 *
 * 1. 执行期间按 leaseRenewalInterval 条件续租；续租只允许未过期 owner 延长自己的 lease，过期 owner 不能复活。
 * 2. 每个动作都有稳定 actionExecutionKey 与 durable checkpoint。run append 失败后的重投先对账 checkpoint，
 *    不重复消费确认、不把已成功动作误记为 blocked；动作实现必须用同一稳定键做原子幂等副作用。
 * 3. run append 前重新做 lease fencing；checkpoint completion 再用 executionOwner+attempt CAS 挡住 stale owner。
 */
class DefaultAutomationEngine(
    private val ruleStore: AutomationRuleStore,
    private val runStore: AutomationRunStore,
    private val dedupeStore: AutomationDedupeStore,
    private val registry: AutomationExtensionRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val timelineService: TimelineService? = null,
    private val domainEventBus: DomainEventBus? = null,
    private val confirmationPort: ActionConfirmationPort? = null,
    private val pendingLease: Duration = DEFAULT_PENDING_LEASE,
    private val checkpointStore: AutomationActionCheckpointStore = InMemoryAutomationActionCheckpointStore(),
    leaseRenewalInterval: Duration? = null,
    private val executionOwnerFactory: () -> String = { "exec-" + UUID.randomUUID().toString() }
) : AutomationEngine {

    private val effectiveLeaseRenewalInterval: Duration = leaseRenewalInterval ?: defaultLeaseRenewalInterval(pendingLease)

    init {
        require(!pendingLease.isZero && !pendingLease.isNegative) { "dedupe pendingLease 必须为正时长" }
        require(!effectiveLeaseRenewalInterval.isZero && !effectiveLeaseRenewalInterval.isNegative) {
            "leaseRenewalInterval 必须为正时长"
        }
        require(effectiveLeaseRenewalInterval < pendingLease) {
            "leaseRenewalInterval 必须小于 pendingLease，才能在使用期内续租"
        }
    }

    override suspend fun dryRun(rule: AutomationRule, at: Instant): AutomationRun {
        val triggerKey = AutomationKeys.dryRunTriggerInstanceKey(rule.id, at)
        val instance = TriggerInstance(
            ruleId = rule.id,
            triggerInstanceKey = triggerKey,
            firedAt = at,
            source = "dry-run"
        )
        return executeRule(rule, instance, dryRun = true, executionFence = null)
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
        val boundInstance = bindDomainEventInstance(rule, instance)

        val dedupeKey = AutomationKeys.dedupeKey(rule.id, boundInstance.triggerInstanceKey, rule.dedupe, boundInstance.firedAt)
        val runId = AutomationKeys.runId(rule.id, boundInstance.triggerInstanceKey)
        val acquiredAt = clock.instant()
        val executionOwner = executionOwnerFactory()
        requireAutomationToken(executionOwner, "executionOwner", 128)
        val acquire = dedupeStore.tryAcquire(
            AutomationDedupeRecord(
                dedupeKey = dedupeKey,
                ruleId = rule.id,
                triggerInstanceKey = boundInstance.triggerInstanceKey,
                runId = runId,
                executionOwner = executionOwner,
                acquiredAt = acquiredAt,
                policyFingerprint = AutomationKeys.policyFingerprint(rule.dedupe),
                leaseExpiresAt = acquiredAt.plus(pendingLease)
            )
        )
        if (acquire is DedupeAcquireResult.AlreadyExists) {
            val existingRun = runStore.get(acquire.existing.runId)
            if (existingRun != null && acquire.existing.state == AutomationDedupeState.PENDING) {
                // 崩溃可能发生在 run append 成功之后、dedupe 转终态之前；发现 run 已持久化时直接修复终态。
                dedupeStore.markRunRecorded(acquire.existing, clock.instant())
            }
            return TriggerExecutionResult.Duplicate(
                dedupeKey = dedupeKey,
                existingRun = existingRun
            )
        }

        val acquired = (acquire as DedupeAcquireResult.Acquired).record
        val alreadyAppendedRun = runStore.get(runId)
        if (alreadyAppendedRun != null) {
            dedupeStore.markRunRecorded(acquired, clock.instant())
            return TriggerExecutionResult.Duplicate(
                dedupeKey = dedupeKey,
                existingRun = alreadyAppendedRun
            )
        }

        return executeOwnedRun(rule, boundInstance, acquired)
    }

    override fun runs(ruleId: String): Flow<List<AutomationRun>> = runStore.runs(ruleId)

    private suspend fun executeOwnedRun(
        rule: AutomationRule,
        instance: TriggerInstance,
        acquired: AutomationDedupeRecord
    ): TriggerExecutionResult = coroutineScope {
        val fence = ExecutionFence(acquired)
        val renewal = launch {
            while (true) {
                delay(effectiveLeaseRenewalInterval.toMillis().coerceAtLeast(1L))
                if (!fence.renew()) break
            }
        }
        try {
            val run = executeRule(rule, instance, dryRun = false, executionFence = fence)
            fence.requireOwned()
            runStore.append(run)
            val markedTerminal = dedupeStore.markRunRecorded(fence.currentRecord(), clock.instant())
            if (!markedTerminal && !hasRunRecordedTerminalForSameInstance(acquired)) {
                throw AutomationDedupeCompletionException(acquired.dedupeKey)
            }
            TriggerExecutionResult.Executed(run)
        } finally {
            renewal.cancel()
        }
    }

    /** 严格 CAS 实现上的防御性复核：并发修复方已写入同一触发实例的终态时，完成结果幂等成功。 */
    private suspend fun hasRunRecordedTerminalForSameInstance(acquired: AutomationDedupeRecord): Boolean {
        val current = dedupeStore.find(acquired.dedupeKey) ?: return false
        return current.state == AutomationDedupeState.RUN_RECORDED && current.hasSameRunIdentityAs(acquired)
    }

    /**
     * DomainEvent 触发绑定是 fail-closed 边界，全部校验都在 dedupe/run 写入之前完成：
     *
     * - OnEvent 规则的实例必须携带事件；event=null 时用任意 key 反复调用都只会得到
     *   [OnEventTriggerMissingEventException]，不会执行动作、不会消耗 dedupe，合法事件到达后仍能执行。
     * - 携带的事件 scope 必须与规则一致；OnEvent 规则还要求事件类型与 filter
     *   满足规则声明，否则说明调用方绕过了订阅侧过滤。
     * - 实例身份只能由 eventId 派生。即使调用方传入伪造或被改写的 key，
     *   引擎也会用同一个确定性 key 计算 dedupe/run/context，重放不会获得新的执行权。
     *
     * 非 OnEvent 触发器（定时、课前、阈值、地理围栏回执、插件等）不由 DomainEvent 驱动，
     * event=null 是合法语义，实例身份按调用方 key 派生；若附带事件上下文则同样强制 eventId 派生。
     */
    private fun bindDomainEventInstance(rule: AutomationRule, instance: TriggerInstance): TriggerInstance {
        val event = instance.event
        val onEvent = rule.trigger as? TriggerSpec.OnEvent
        if (onEvent != null && event == null) {
            throw OnEventTriggerMissingEventException(rule.id)
        }
        if (event == null) return instance
        if (event.scope != rule.scope) throw CrossScopeAutomationException(rule.id)
        if (onEvent != null) {
            val inconsistency = DomainEventTriggerSupport.inconsistencyReason(onEvent, event)
            if (inconsistency != null) {
                throw OnEventTriggerEventMismatchException(rule.id, inconsistency)
            }
        }
        val boundKey = DomainEventTriggerSupport.triggerInstanceKey(event)
        return if (instance.triggerInstanceKey == boundKey) {
            instance
        } else {
            instance.copy(triggerInstanceKey = boundKey)
        }
    }

    private suspend fun executeRule(
        rule: AutomationRule,
        instance: TriggerInstance,
        dryRun: Boolean,
        executionFence: ExecutionFence?
    ): AutomationRun {
        val startedAt = clock.instant()
        val dedupeKey = AutomationKeys.dedupeKey(rule.id, instance.triggerInstanceKey, rule.dedupe, instance.firedAt)
        val runId = AutomationKeys.runId(rule.id, instance.triggerInstanceKey)
        val context = RunContext(
            scope = rule.scope,
            rule = rule,
            triggerInstanceKey = instance.triggerInstanceKey,
            firedAt = instance.firedAt,
            now = startedAt,
            dryRun = dryRun,
            runId = runId,
            dedupeKey = dedupeKey,
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

        val plan = resolveRulePlan(rule)
        if (plan.validationProblems.isNotEmpty()) {
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
                statusReason = plan.validationProblems.take(6).joinToString("; ")
            )
        }

        val recoveryActionPhase = !dryRun && executionFence != null &&
            checkpointStore.listForRun(runId).isNotEmpty()
        val evaluations = evaluateConditions(plan, context)
        val failures = evaluations.mapIndexedNotNull { index, result ->
            if (result.effectivePassed) null else {
                val reason = result.reason ?: "未通过"
                "condition[" + index + "] " + result.conditionType + ": " + reason
            }
        }
        if (failures.isNotEmpty() && !recoveryActionPhase) {
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
            previewActions(rule, plan, context)
        } else {
            executeActions(rule, plan, context, checkNotNull(executionFence))
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
            statusReason = if (recoveryActionPhase && failures.isNotEmpty()) {
                "崩溃恢复：当前条件重估不再通过，但动作检查点证明原执行已进入动作阶段；按 durable outcome 对账"
            } else {
                null
            }
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
        statusReason = statusReason?.let { sanitizeDetail(it, 1000) }
    )

    /** 每次运行只解析一次扩展快照，校验、求值、预览和执行使用同一批实例，避免插件热卸载造成分裂。 */
    private fun resolveRulePlan(rule: AutomationRule): RunPlan {
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

        val conditionSteps = rule.conditions.mapIndexed { index, spec ->
            val evaluator = registry.conditionEvaluator(spec.type)
            if (evaluator == null) {
                problems += "condition[" + index + "] 未注册: " + spec.type
            } else {
                evaluator.configSchema.validate(spec.config).take(4).forEach { violation ->
                    problems += "condition[" + index + "] config " + violation.path + " " + violation.rule
                }
            }
            ConditionStep(index, spec, evaluator)
        }

        val actionSteps = rule.actions.mapIndexed { index, spec ->
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
            ActionStep(index, spec, executor)
        }
        return RunPlan(
            triggerProvider = triggerProvider,
            conditionSteps = conditionSteps,
            actionSteps = actionSteps,
            validationProblems = problems.map { sanitizeDetail(it, 180) }
        )
    }

    private suspend fun evaluateConditions(
        plan: RunPlan,
        context: RunContext
    ): List<ConditionResult> = plan.conditionSteps.map { step ->
        val evaluator = checkNotNull(step.evaluator)
        val raw = try {
            evaluator.evaluate(step.spec, context)
        } catch (error: Throwable) {
            if (error is CancellationException || error is Error) throw error
            ConditionResult(
                conditionType = step.spec.type,
                passed = false,
                certainty = Certainty.UNKNOWN,
                reason = "条件求值异常（" + safeErrorName(error) + "），按不通过"
            )
        }
        normalizeConditionResult(step.spec, raw)
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

    private fun previewActions(rule: AutomationRule, plan: RunPlan, context: RunContext): List<ActionOutcome> {
        val quietActive = isQuietHoursActive(rule, context.firedAt)
        return plan.actionSteps.map { step ->
            val executor = checkNotNull(step.executor)
            val suffix = when {
                executor.risk == ActionRisk.NEEDS_USER_CONFIRM -> "；真实执行前需要一次性用户确认"
                executor.risk == ActionRisk.LOCAL_NOTIFY && quietActive -> "；当前处于 quietHours，真实执行会抑制通知"
                else -> ""
            }
            ActionOutcome(
                actionType = step.spec.type,
                risk = executor.risk,
                status = ActionOutcomeStatus.DRY_RUN_PREVIEW,
                detail = "dry-run 预览：不会执行副作用" + suffix
            )
        }
    }

    private suspend fun executeActions(
        rule: AutomationRule,
        plan: RunPlan,
        context: RunContext,
        executionFence: ExecutionFence
    ): List<ActionOutcome> {
        val quietActive = isQuietHoursActive(rule, context.firedAt)
        val outcomes = ArrayList<ActionOutcome>(plan.actionSteps.size)
        for (step in plan.actionSteps) {
            executionFence.requireOwned()
            val spec = step.spec
            val executor = checkNotNull(step.executor)
            val actionExecutionKey = AutomationKeys.actionExecutionKey(context.runId, step.index)
            val actionContext = context.copy(
                now = clock.instant(),
                actionIndex = step.index,
                actionExecutionKey = actionExecutionKey
            )
            val now = clock.instant()
            val initialCheckpoint = AutomationActionCheckpoint(
                checkpointKey = actionExecutionKey,
                dedupeKey = context.dedupeKey,
                runId = context.runId,
                ruleId = rule.id,
                triggerInstanceKey = context.triggerInstanceKey,
                actionIndex = step.index,
                actionType = spec.type,
                configDigest = AutomationKeys.sha256Hex(Json.canonical(spec.config)),
                executionOwner = executionFence.owner,
                attempt = 1,
                state = AutomationActionCheckpointState.STARTED,
                startedAt = now,
                updatedAt = now
            )
            val outcome = when (val claim = checkpointStore.claim(initialCheckpoint)) {
                is ActionCheckpointClaimResult.ReplayCompleted -> claim.checkpoint.outcome
                    ?: indeterminateOutcome(spec, executor.risk, "COMPLETED 检查点缺少 outcome，结果不可对账")
                is ActionCheckpointClaimResult.Execute -> executeCheckpointedAction(
                    rule = rule,
                    spec = spec,
                    executor = executor,
                    actionContext = actionContext,
                    checkpoint = claim.checkpoint,
                    quietSuppressed = executor.risk == ActionRisk.LOCAL_NOTIFY && quietActive
                )
            }
            outcomes += outcome
        }
        return outcomes
    }

    private suspend fun executeCheckpointedAction(
        rule: AutomationRule,
        spec: ActionSpec,
        executor: ActionExecutor,
        actionContext: RunContext,
        checkpoint: AutomationActionCheckpoint,
        quietSuppressed: Boolean
    ): ActionOutcome {
        val startedAt = clock.instant()
        val outcome = when {
            executor.risk == ActionRisk.EXTERNAL_WRITE_FORBIDDEN -> ActionOutcome(
                actionType = spec.type,
                risk = executor.risk,
                status = ActionOutcomeStatus.FAILED,
                detail = "外部写动作被安全边界拒绝",
                startedAt = startedAt,
                finishedAt = clock.instant()
            )
            quietSuppressed -> ActionOutcome(
                actionType = spec.type,
                risk = executor.risk,
                status = ActionOutcomeStatus.SKIPPED_QUIET_HOURS,
                detail = "quietHours 生效，通知动作已抑制",
                startedAt = startedAt,
                finishedAt = clock.instant()
            )
            executor.risk == ActionRisk.NEEDS_USER_CONFIRM -> executeConfirmedAction(
                rule = rule,
                spec = spec,
                executor = executor,
                actionContext = actionContext,
                checkpoint = checkpoint
            )
            else -> invokeExecutor(spec, executor, actionContext)
        }
        return completeOrReconcile(checkpoint, outcome, spec, executor.risk)
    }

    private suspend fun executeConfirmedAction(
        rule: AutomationRule,
        spec: ActionSpec,
        executor: ActionExecutor,
        actionContext: RunContext,
        checkpoint: AutomationActionCheckpoint
    ): ActionOutcome {
        val digest = AutomationKeys.sha256Hex(Json.canonical(spec.config))
        var working = checkpoint
        var confirmation = working.confirmation?.takeIf {
            it.ruleId == rule.id && it.actionType == spec.type && it.configDigest == digest
        }
        var reconciliationFailure = false
        if (confirmation == null) {
            confirmation = validConfirmation(rule, spec, actionContext)
            if (confirmation != null) {
                val marked = checkpointStore.markConfirmationConsumed(working, confirmation, clock.instant())
                if (!marked) {
                    reconciliationFailure = true
                } else {
                    working = checkpointStore.find(working.checkpointKey) ?: working.copy(confirmation = confirmation)
                }
            }
        }
        return when {
            reconciliationFailure -> indeterminateOutcome(
                spec,
                executor.risk,
                "确认消费检查点写入竞争，动作结果不可对账"
            )
            confirmation == null -> {
                if (working.attempt > 1 || working.confirmation != null) {
                    indeterminateOutcome(
                        spec,
                        executor.risk,
                        "一次性确认的原始消费结果不可对账，不能谎称动作被阻止"
                    )
                } else {
                    ActionOutcome(
                        actionType = spec.type,
                        risk = executor.risk,
                        status = ActionOutcomeStatus.BLOCKED_CONFIRMATION_REQUIRED,
                        detail = "缺少有效的一次性用户确认，动作未执行"
                    )
                }
            }
            !confirmation.isValidAt(clock.instant()) -> indeterminateOutcome(
                spec,
                executor.risk,
                "一次性确认已过期且原执行结果不可对账"
            )
            else -> invokeExecutor(spec, executor, actionContext.copy(confirmation = confirmation))
        }
    }

    private suspend fun completeOrReconcile(
        checkpoint: AutomationActionCheckpoint,
        outcome: ActionOutcome,
        spec: ActionSpec,
        risk: ActionRisk
    ): ActionOutcome {
        return if (checkpointStore.complete(checkpoint, outcome, clock.instant())) {
            outcome
        } else {
            reconcileCheckpointOutcome(checkpoint.checkpointKey, spec, risk)
        }
    }

    private suspend fun reconcileCheckpointOutcome(
        checkpointKey: String,
        spec: ActionSpec,
        risk: ActionRisk
    ): ActionOutcome {
        val completed = checkpointStore.find(checkpointKey)
            ?.takeIf { it.state == AutomationActionCheckpointState.COMPLETED }
            ?.outcome
        return completed ?: indeterminateOutcome(
            spec,
            risk,
            "动作检查点 ownership 竞争，执行结果不可对账"
        )
    }

    private fun indeterminateOutcome(spec: ActionSpec, risk: ActionRisk, detail: String): ActionOutcome {
        val now = clock.instant()
        return ActionOutcome(
            actionType = spec.type,
            risk = risk,
            status = ActionOutcomeStatus.INDETERMINATE,
            detail = sanitizeDetail(detail, 512),
            startedAt = now,
            finishedAt = now
        )
    }

    private suspend fun validConfirmation(
        rule: AutomationRule,
        spec: ActionSpec,
        context: RunContext
    ): ActionConfirmation? {
        val port = confirmationPort ?: return null
        val confirmation = try {
            port.consumeConfirmation(rule, spec, context)
        } catch (error: Throwable) {
            if (error is CancellationException || error is Error) throw error
            null
        } ?: return null
        val digest = AutomationKeys.sha256Hex(Json.canonical(spec.config))
        return confirmation.takeIf {
            it.ruleId == rule.id &&
                it.actionType == spec.type &&
                it.configDigest == digest &&
                it.isValidAt(clock.instant())
        }
    }

    private suspend fun invokeExecutor(
        spec: ActionSpec,
        executor: ActionExecutor,
        context: RunContext
    ): ActionOutcome {
        val startedAt = clock.instant()
        return try {
            val raw = executor.execute(spec, context.copy(now = startedAt))
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
        val executorStart = raw.startedAt
        val executorFinish = raw.finishedAt
        val normalizedTimes = when {
            executorStart != null && executorFinish != null -> executorStart to executorFinish
            executorStart != null && !finishedAt.isBefore(executorStart) -> executorStart to finishedAt
            executorFinish != null && !executorFinish.isBefore(startedAt) -> startedAt to executorFinish
            else -> startedAt to finishedAt
        }
        var outcome = raw.copy(
            actionType = spec.type,
            risk = executor.risk,
            startedAt = normalizedTimes.first,
            finishedAt = normalizedTimes.second,
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
        val indeterminate = outcomes.count { it.status == ActionOutcomeStatus.INDETERMINATE }
        val suppressed = outcomes.count {
            it.status == ActionOutcomeStatus.BLOCKED_CONFIRMATION_REQUIRED ||
                it.status == ActionOutcomeStatus.SKIPPED_QUIET_HOURS
        }
        return when {
            indeterminate > 0 && indeterminate == outcomes.size -> RunStatus.INDETERMINATE
            indeterminate > 0 -> RunStatus.PARTIAL_FAILED
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

    /** 当前 dedupe lease 的 owner fencing 视图；leaseExpiresAt 可续租，executionOwner/acquiredAt 才代表执行权。 */
    private inner class ExecutionFence(
        initial: AutomationDedupeRecord
    ) {
        private val current = AtomicReference(initial)
        val owner: String = initial.executionOwner
        val dedupeKey: String = initial.dedupeKey

        fun currentRecord(): AutomationDedupeRecord = current.get()

        suspend fun renew(): Boolean {
            val base = current.get()
            val now = clock.instant()
            return try {
                val renewed = dedupeStore.renewLease(
                    expected = base,
                    renewedAt = now,
                    renewedUntil = now.plus(pendingLease)
                )
                if (!renewed) return false
                val fresh = dedupeStore.find(base.dedupeKey)
                if (fresh != null && fresh.hasSameExecutionOwnershipAs(base)) {
                    current.set(fresh)
                    true
                } else {
                    false
                }
            } catch (error: Throwable) {
                if (error is CancellationException || error is Error) throw error
                false
            }
        }

        suspend fun requireOwned() {
            val base = current.get()
            val now = clock.instant()
            val fresh = dedupeStore.find(dedupeKey)
            if (fresh != null &&
                fresh.state == AutomationDedupeState.PENDING &&
                fresh.hasSameExecutionOwnershipAs(base) &&
                now.isBefore(fresh.leaseExpiresAt)
            ) {
                current.set(fresh)
                return
            }
            throw AutomationLeaseLostException(dedupeKey)
        }
    }

    private data class RunPlan(
        val triggerProvider: TriggerProvider?,
        val conditionSteps: List<ConditionStep>,
        val actionSteps: List<ActionStep>,
        val validationProblems: List<String>
    )

    private data class ConditionStep(
        val index: Int,
        val spec: ConditionSpec,
        val evaluator: ConditionEvaluator?
    )

    private data class ActionStep(
        val index: Int,
        val spec: ActionSpec,
        val executor: ActionExecutor?
    )

    private companion object {
        const val MAX_EVIDENCE_PER_STEP: Int = 8
        val DEFAULT_PENDING_LEASE: Duration = Duration.ofMinutes(5)

        fun defaultLeaseRenewalInterval(pendingLease: Duration): Duration {
            val thirdMillis = (pendingLease.toMillis() / 3L).coerceAtLeast(1L)
            return Duration.ofMillis(thirdMillis.coerceAtMost(30_000L))
        }
    }
}
