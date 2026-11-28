package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.TimelineFilter
import cn.edu.haut.campus.core.timeline.TimelineService
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import cn.edu.haut.campus.core.timeline.TimeWindow
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

/** 阈值进入边沿判定上下文。previousSamples 可包含当前事件，实现会按 eventId/meter 排除。 */
data class ThresholdEvaluationContext(
    val previousSamples: List<DomainEvent.UtilityBalanceSampled> = emptyList(),
    val lastTriggeredAt: Instant? = null,
    val evaluatedAt: Instant
)

/** 一次由内置时间类触发器规划出的未来唤醒实例。key 不含随机量，重启/补唤醒天然幂等。 */
data class PlannedTriggerInstance(
    val ruleId: String,
    val triggerInstanceKey: String,
    val fireAt: Instant,
    val source: String
) {
    init {
        requireAutomationToken(ruleId, "ruleId")
        requireAutomationToken(triggerInstanceKey, "triggerInstanceKey", 512)
        requireAutomationToken(source, "source", 64)
    }
}

/**
 * 内置触发器的纯 JVM 规划/事件匹配内核。
 *
 * Android 不常驻监听：定时与课前触发先规划成批量唤醒；事件、阈值和地理围栏触发统一由
 * domain_outbox 的 at-least-once 消费驱动。无论哪条路径，实例 key 都在这里确定性派生。
 */
object BuiltinTriggerPlanner {
    const val SOURCE_AT_TIME: String = "builtin-at-time"
    const val SOURCE_BEFORE_EVENT: String = "builtin-before-event"
    const val MAX_PLANNED_PER_RULE: Int = 32

    fun planAtTime(
        rule: AutomationRule,
        now: Instant,
        zone: ZoneId,
        horizon: Duration,
        lookbehind: Duration = Duration.ZERO
    ): List<PlannedTriggerInstance> {
        val spec = rule.trigger as? TriggerSpec.AtTime ?: return emptyList()
        require(!horizon.isZero && !horizon.isNegative) { "规划 horizon 必须为正时长" }
        require(!lookbehind.isNegative) { "补处理 lookbehind 不能为负" }
        val end = now.plus(horizon)
        val startDate = now.minus(lookbehind).atZone(zone).toLocalDate()
        val days = horizon.toDays().coerceAtLeast(1L) + lookbehind.toDays().coerceAtLeast(0L) + 3L
        val out = ArrayList<PlannedTriggerInstance>()
        for (offset in 0..days) {
            val date = startDate.plusDays(offset)
            if (date.dayOfWeek !in spec.days) continue
            val fireAt = date.atTime(spec.local).atZone(zone).toInstant()
            val inFutureWindow = fireAt.isAfter(now) && fireAt.isBefore(end)
            val inMissedWindow = !lookbehind.isZero && !fireAt.isAfter(now) && !fireAt.isBefore(now.minus(lookbehind))
            if (!inFutureWindow && !inMissedWindow) continue
            val localKey = date.atTime(spec.local).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            out += PlannedTriggerInstance(
                ruleId = rule.id,
                triggerInstanceKey = "at-time/v1/" + rule.id + "/" + localKey,
                fireAt = fireAt,
                source = SOURCE_AT_TIME
            )
            if (out.size >= MAX_PLANNED_PER_RULE) break
        }
        return out.sortedBy { it.fireAt }
    }

    suspend fun planBeforeEvent(
        rule: AutomationRule,
        timelineService: TimelineService?,
        now: Instant,
        horizon: Duration,
        lookbehind: Duration = Duration.ZERO
    ): List<PlannedTriggerInstance> {
        val spec = rule.trigger as? TriggerSpec.BeforeEvent ?: return emptyList()
        val service = timelineService ?: return emptyList()
        require(!horizon.isZero && !horizon.isNegative) { "规划 horizon 必须为正时长" }
        require(!lookbehind.isNegative) { "补处理 lookbehind 不能为负" }
        val window = TimeWindow(now.minus(lookbehind), now.plus(horizon).plus(spec.offset))
        val events = runCatching {
            service.observe(rule.scope, window, TimelineFilter(kinds = spec.kinds)).first()
        }.getOrElse { return emptyList() }
        return events.asSequence()
            .filter { it.status != EventStatus.CANCELLED_TODAY }
            .filter { it.timeSemantics != TimelineTimeSemantics.UNCERTAIN }
            .mapNotNull { event ->
                val fireAt = event.start.minus(spec.offset)
                val inFutureWindow = fireAt.isAfter(now) && fireAt.isBefore(now.plus(horizon))
                val inMissedWindow = !lookbehind.isZero && !fireAt.isAfter(now) && !fireAt.isBefore(now.minus(lookbehind))
                if (!inFutureWindow && !inMissedWindow) return@mapNotNull null
                PlannedTriggerInstance(
                    ruleId = rule.id,
                    triggerInstanceKey = "before-event/v1/" + rule.id + "/" +
                        AutomationKeys.sha256Hex(event.id.value).substring(0, 32) + "/" + spec.offset.seconds +
                        "/" + event.start.epochSecond,
                    fireAt = fireAt,
                    source = SOURCE_BEFORE_EVENT
                )
            }
            .distinctBy { it.triggerInstanceKey }
            .sortedBy { it.fireAt }
            .take(MAX_PLANNED_PER_RULE)
            .toList()
    }

    /** 把 outbox 事件映射成规则触发实例；不匹配返回 null。scope 不符也返回 null，由消费者按账号库隔离。 */
    fun instanceForDomainEvent(
        rule: AutomationRule,
        event: DomainEvent,
        thresholdContext: ThresholdEvaluationContext? = null
    ): TriggerInstance? {
        if (event.scope != rule.scope) return null
        val matches = when (val trigger = rule.trigger) {
            is TriggerSpec.OnEvent -> DomainEventTriggerSupport.matches(trigger, event)
            is TriggerSpec.Threshold -> thresholdMatches(trigger, event, thresholdContext)
            is TriggerSpec.Geofence -> geofenceMatches(trigger, event)
            else -> false
        }
        if (!matches) return null
        return DomainEventTriggerSupport.triggerInstance(rule.id, event)
    }

    private fun thresholdMatches(
        spec: TriggerSpec.Threshold,
        event: DomainEvent,
        evaluationContext: ThresholdEvaluationContext?
    ): Boolean {
        val sampled = event as? DomainEvent.UtilityBalanceSampled ?: return false
        val requiredMeterPrefix = when (spec.metric.value) {
            BuiltinAutomationTypes.METRIC_UTILITY_ELECTRICITY_CENTIUNITS -> "online/electricity/"
            BuiltinAutomationTypes.METRIC_UTILITY_WATER_CENTIUNITS -> "online/water/"
            else -> return false
        }
        if (!sampled.meterRef.id.startsWith(requiredMeterPrefix)) return false
        val context = evaluationContext ?: ThresholdEvaluationContext(evaluatedAt = sampled.occurredAt)
        if (!spec.cooldown.isZero) {
            val lastTriggeredAt = context.lastTriggeredAt
            if (lastTriggeredAt != null && context.evaluatedAt.isBefore(lastTriggeredAt.plus(spec.cooldown))) {
                return false
            }
        }
        val current = BigDecimal.valueOf(sampled.balanceCents)
        if (!compare(current, spec.op, spec.value)) return false
        val previousValues = context.previousSamples
            .filter { it.meterRef.id == sampled.meterRef.id && it.eventId != sampled.eventId && !it.occurredAt.isAfter(sampled.occurredAt) }
            .sortedByDescending { it.occurredAt }
            .map { BigDecimal.valueOf(it.balanceCents) }
        if (previousValues.isEmpty()) return true
        return when (spec.op) {
            CompareOp.LESS_THAN -> wasRearmedForFallingEdge(previousValues, spec.value, spec.hysteresis, inclusiveBoundary = false)
            CompareOp.LESS_OR_EQUAL -> wasRearmedForFallingEdge(previousValues, spec.value, spec.hysteresis, inclusiveBoundary = true)
            CompareOp.GREATER_THAN -> wasRearmedForRisingEdge(previousValues, spec.value, spec.hysteresis, inclusiveBoundary = false)
            CompareOp.GREATER_OR_EQUAL -> wasRearmedForRisingEdge(previousValues, spec.value, spec.hysteresis, inclusiveBoundary = true)
            CompareOp.EQUAL,
            CompareOp.NOT_EQUAL -> !compare(previousValues.first(), spec.op, spec.value)
        }
    }

    /** 滞后带内读数只保持既有状态；必须向历史回退到最近一次明确的重新武装/仍处触发态读数。 */
    private fun wasRearmedForFallingEdge(
        previousValues: List<BigDecimal>,
        threshold: BigDecimal,
        hysteresis: BigDecimal,
        inclusiveBoundary: Boolean
    ): Boolean {
        val rearm = threshold.add(hysteresis)
        val decisive = previousValues.firstOrNull { value ->
            if (inclusiveBoundary) value > rearm || value <= threshold else value >= rearm || value < threshold
        } ?: return true
        return if (inclusiveBoundary) decisive > rearm else decisive >= rearm
    }

    private fun wasRearmedForRisingEdge(
        previousValues: List<BigDecimal>,
        threshold: BigDecimal,
        hysteresis: BigDecimal,
        inclusiveBoundary: Boolean
    ): Boolean {
        val rearm = threshold.subtract(hysteresis)
        val decisive = previousValues.firstOrNull { value ->
            if (inclusiveBoundary) value < rearm || value >= threshold else value <= rearm || value > threshold
        } ?: return true
        return if (inclusiveBoundary) decisive < rearm else decisive <= rearm
    }

    private fun geofenceMatches(spec: TriggerSpec.Geofence, event: DomainEvent): Boolean {
        val transition = event as? DomainEvent.GeofenceTransition ?: return false
        return transition.campusId == spec.campusId && transition.enter == spec.enter
    }

    private fun compare(actual: BigDecimal, op: CompareOp, expected: BigDecimal): Boolean {
        val cmp = actual.compareTo(expected)
        return when (op) {
            CompareOp.LESS_THAN -> cmp < 0
            CompareOp.LESS_OR_EQUAL -> cmp <= 0
            CompareOp.EQUAL -> cmp == 0
            CompareOp.GREATER_OR_EQUAL -> cmp >= 0
            CompareOp.GREATER_THAN -> cmp > 0
            CompareOp.NOT_EQUAL -> cmp != 0
        }
    }
}

/** 内置时间/事件触发器的注册类型；实际唤醒由 Android 批量调度器按 BuiltinTriggerPlanner 执行。 */
class BuiltinTriggerProvider(
    override val type: String
) : TriggerProvider {
    override val configSchema: JsonSchema = JsonSchema.EMPTY_OBJECT

    override fun arm(rule: AutomationRule, ctx: ArmContext): ArmHandle {
        require(rule.trigger.type == type) { "触发器 provider 与规则类型不一致" }
        return object : ArmHandle {
            override val id: String = "builtin-batch-planned:" + rule.id
            override suspend fun disarm() = Unit
        }
    }
}

fun builtinTriggerProviders(): List<TriggerProvider> =
    BuiltinAutomationTypes.triggerTypes.map(::BuiltinTriggerProvider)

object BuiltinAutomationTypes {
    const val METRIC_UTILITY_ELECTRICITY_CENTIUNITS: String = "core.utility.electricity-centiunits"
    const val METRIC_UTILITY_WATER_CENTIUNITS: String = "core.utility.water-centiunits"

    const val CONDITION_HAS_CLASS_TODAY: String = "core.has-class-today"
    const val CONDITION_ON_CAMPUS: String = "core.on-campus"
    const val CONDITION_FOCUS_PERIOD: String = "core.focus-period"
    const val CONDITION_COMMUTE_TIME: String = "core.commute-time"
    const val CONDITION_DATA_FRESHNESS: String = "core.data-freshness"
    const val CONDITION_WEATHER_PRECIPITATION: String = "core.weather-precipitation"
    const val CONDITION_DAY_OF_WEEK: String = "core.day-of-week"
    const val CONDITION_UTILITY_BALANCE: String = "core.utility-balance"
    const val CONDITION_COURSE_CANCELLATION: String = "core.course-cancellation"

    const val ACTION_LOCAL_NOTIFICATION: String = "core.local-notification"
    const val ACTION_CREATE_PERSONAL_EVENT: String = "core.create-personal-event"
    const val ACTION_REFRESH_HOME: String = "core.refresh-home"

    val triggerTypes: Set<String> = setOf(
        TriggerSpec.AtTime.TYPE,
        TriggerSpec.BeforeEvent.TYPE,
        TriggerSpec.OnEvent.TYPE,
        TriggerSpec.Threshold.TYPE,
        TriggerSpec.Geofence.TYPE
    )
}
