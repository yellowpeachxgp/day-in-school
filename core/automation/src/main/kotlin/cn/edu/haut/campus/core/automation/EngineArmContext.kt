package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.events.DomainEventBus
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.timeline.TimelineService
import java.time.Instant

/**
 * 绑定单条规则与引擎的 [ArmContext] 参考实现。W2-02 的 Android 调度装配可以直接使用，
 * 或按同一契约自行实现：fire 是触发进入引擎前的第一道边界。
 *
 * - OnEvent 规则的 fire 必须携带非空 event；event=null 在 fire 边界直接抛
 *   [OnEventTriggerMissingEventException]，不进入引擎、不消耗 dedupe。即使实现方绕过本类
 *   直接调用引擎，引擎入口仍会复核同一不变量。
 * - 非 OnEvent 触发器（定时、课前、阈值、地理围栏回执、插件等）不由 DomainEvent 驱动，
 *   event=null 是合法语义，fire 原样转发给引擎。
 * - 事件类型/filter 与规则声明的一致性由引擎入口统一校验，fire 不重复检查，
 *   不匹配的调用同样以异常形式返回给触发实现方。
 */
class EngineArmContext(
    private val rule: AutomationRule,
    private val engine: AutomationEngine,
    override val scope: SchoolAccountScope,
    override val timelineService: TimelineService? = null,
    override val domainEventBus: DomainEventBus? = null
) : ArmContext {

    init {
        require(rule.scope == scope) { "ArmContext scope 与规则 scope 不一致" }
    }

    override suspend fun fire(
        triggerInstanceKey: String,
        firedAt: Instant,
        event: DomainEvent?,
        source: String
    ) {
        if (rule.trigger is TriggerSpec.OnEvent && event == null) {
            throw OnEventTriggerMissingEventException(rule.id)
        }
        engine.executeTrigger(
            TriggerInstance(
                ruleId = rule.id,
                triggerInstanceKey = triggerInstanceKey,
                firedAt = firedAt,
                event = event,
                source = source
            )
        )
    }
}
