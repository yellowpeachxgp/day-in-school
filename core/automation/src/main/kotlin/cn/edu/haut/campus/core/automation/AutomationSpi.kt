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

    /** 触发一次规则实例。DomainEvent 触发必须传 event，并用 eventId 派生 triggerInstanceKey。 */
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

/** NEEDS_USER_CONFIRM 动作的确认端口；UI/通知确认链路在后续卡实现，本核心只做校验。 */
interface ActionConfirmationPort {
    suspend fun confirmationFor(
        rule: AutomationRule,
        action: ActionSpec,
        ctx: RunContext
    ): ActionConfirmation?
}

/** 执行上下文。triggerEvent 只携带 DomainEvent 小型事实，不携带原文 blob、凭据或大 payload。 */
data class RunContext(
    val scope: SchoolAccountScope,
    val rule: AutomationRule,
    val triggerInstanceKey: String,
    val firedAt: Instant,
    val now: Instant,
    val dryRun: Boolean,
    val triggerEvent: DomainEvent? = null,
    val timelineService: TimelineService? = null,
    val domainEventBus: DomainEventBus? = null,
    val confirmation: ActionConfirmation? = null
)
