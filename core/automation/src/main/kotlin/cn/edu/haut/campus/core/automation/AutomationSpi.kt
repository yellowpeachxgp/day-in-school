package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.events.DomainEventBus
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.timeline.TimelineService
import java.time.Instant

/**
 * TriggerProvider 的装配上下文。W2-01 只定义纯 JVM 协议；
 * Android Alarm/WorkManager 与 outbox 消费循环属于 W2-02，不在本模块实现。
 */
interface ArmContext {
    val scope: SchoolAccountScope
    val timelineService: TimelineService?
    val domainEventBus: DomainEventBus?

    /**
     * 触发一次规则实例。event 的 null 语义按规则触发器类型区分：
     *
     * - OnEvent（DomainEvent 驱动）规则必须携带非空 event，且事件类型与 filter 必须满足规则声明；
     *   event=null 的 OnEvent 触发只能是实现错误或绕过 eventId 去重的尝试，会被 fail-closed 拒绝
     *   （实现方应在 fire 边界拒绝，引擎入口仍会复核），且不消耗 dedupe、不产生运行记录。
     * - 非 OnEvent 触发器（定时、课前、阈值、地理围栏回执、插件等）不由 DomainEvent 驱动，
     *   event=null 是合法语义；若实现方附带事件上下文，引擎同样按 eventId 强制派生实例身份。
     *
     * 引擎会忽略传入 key 并用 eventId 强制派生实例身份；改写 key 不能让同一事件获得第二次执行权。
     */
    suspend fun fire(
        triggerInstanceKey: String,
        firedAt: Instant,
        event: DomainEvent? = null,
        source: String = "trigger-provider"
    )
}

/** arm 返回的可撤销句柄；插件禁用或规则删除时必须能停止后续触发。 */
interface ArmHandle {
    val id: String
    suspend fun disarm()
}

interface TriggerProvider {
    val type: String
    val configSchema: JsonSchema
    fun arm(rule: AutomationRule, ctx: ArmContext): ArmHandle
}

interface ConditionEvaluator {
    val type: String
    val configSchema: JsonSchema

    /** 条件求值必须是只读的；UNKNOWN 表示证据不足，不代表通过。 */
    suspend fun evaluate(spec: ConditionSpec, ctx: RunContext): ConditionResult
}

interface ActionExecutor {
    val type: String
    val configSchema: JsonSchema
    val risk: ActionRisk

    /** dry-run 永远不会调用本方法；实现可以把副作用范围写入 outcome.evidence。 */
    suspend fun execute(spec: ActionSpec, ctx: RunContext): ActionOutcome
}

/**
 * NEEDS_USER_CONFIRM 动作的确认端口；UI/通知确认链路在后续卡实现，本核心只做绑定与有效期校验。
 * 实现必须原子消费确认凭证：同一 confirmationId 不得被另一个触发实例重复使用。
 * 同时实现必须按 RunContext.actionExecutionKey 做崩溃恢复幂等：同一 run/action 的重试在
 * consume 响应丢失后要能对账出原确认；无法对账时引擎必须记录 INDETERMINATE，而不是谎称动作被阻止。
 */
interface ActionConfirmationPort {
    suspend fun consumeConfirmation(
        rule: AutomationRule,
        action: ActionSpec,
        ctx: RunContext
    ): ActionConfirmation?
}

/**
 * 执行上下文。triggerEvent 只携带 DomainEvent 小型事实，不携带原文 blob、凭据或大 payload。
 * actionIndex/actionExecutionKey 只在动作执行与确认消费边界非空；动作实现必须用该稳定键做副作用幂等。
 */
data class RunContext(
    val scope: SchoolAccountScope,
    val rule: AutomationRule,
    val triggerInstanceKey: String,
    val firedAt: Instant,
    val now: Instant,
    val dryRun: Boolean,
    val runId: String,
    val dedupeKey: String,
    val triggerEvent: DomainEvent? = null,
    val timelineService: TimelineService? = null,
    val domainEventBus: DomainEventBus? = null,
    val confirmation: ActionConfirmation? = null,
    val actionIndex: Int? = null,
    val actionExecutionKey: String? = null
) {
    init {
        requireAutomationToken(runId, "runId")
        requireAutomationToken(dedupeKey, "dedupeKey", 1024)
        require((actionIndex == null) == (actionExecutionKey == null)) {
            "actionIndex 与 actionExecutionKey 必须同时存在或同时缺省"
        }
        actionIndex?.let { require(it >= 0) { "actionIndex 不能为负" } }
        actionExecutionKey?.let { requireAutomationToken(it, "actionExecutionKey", 160) }
    }
}
