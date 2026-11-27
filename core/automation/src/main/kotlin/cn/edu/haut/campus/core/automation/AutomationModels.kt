package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.timeline.LocalTimeWindow
import cn.edu.haut.campus.core.timeline.TimelineKind
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

private val AUTOMATION_TYPE_REGEX = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9_-]*)+$")

internal fun requireAutomationToken(value: String, field: String, maxLength: Int = 128) {
    require(value.isNotBlank() && value.length <= maxLength) { field + " 为空或过长" }
    require(value.none { it.isISOControl() || it.isWhitespace() }) { field + " 不能包含空白或控制字符" }
}

internal fun requireAutomationType(value: String, field: String = "type") {
    requireAutomationToken(value, field)
    require(AUTOMATION_TYPE_REGEX.matches(value)) { field + " 必须是 lowercase.dot.namespace 形式: " + value }
}

/** 自动化动作配置的空 JSON object。JsonValue.Obj 本身构造时会复制并冻结输入 map。 */
val EMPTY_AUTOMATION_CONFIG: JsonValue.Obj = JsonValue.Obj(emptyMap())

/** 指标引用使用与扩展 type 相同的命名空间格式，避免后续不同来源互相覆盖。 */
@JvmInline
value class MetricRef(val value: String) {
    init { requireAutomationType(value, "metric") }
}

enum class CompareOp {
    LESS_THAN,
    LESS_OR_EQUAL,
    EQUAL,
    GREATER_OR_EQUAL,
    GREATER_THAN,
    NOT_EQUAL
}

/**
 * MASTER_PLAN §6.3 的触发器模型。
 *
 * 内建触发器使用强类型字段；插件触发器使用 config(JsonObject)。触发器 Provider 的 configSchema
 * 描述这些字段的可编辑 JSON 表达，运行时对 Plugin.config 做 fail-closed 校验。
 */
sealed interface TriggerSpec {
    val type: String

    data class AtTime(
        val local: LocalTime,
        val days: Set<DayOfWeek>
    ) : TriggerSpec {
        override val type: String get() = TYPE

        init {
            require(days.isNotEmpty()) { "AtTime.days 不能为空" }
        }

        companion object { const val TYPE: String = "core.at-time" }
    }

    data class BeforeEvent(
        val kinds: Set<TimelineKind>,
        val offset: Duration
    ) : TriggerSpec {
        override val type: String get() = TYPE

        init {
            require(kinds.isNotEmpty()) { "BeforeEvent.kinds 不能为空" }
            require(!offset.isZero && !offset.isNegative) { "BeforeEvent.offset 必须为正时长" }
        }

        companion object { const val TYPE: String = "core.before-event" }
    }

    data class OnEvent(
        val eventType: String,
        val filter: JsonValue.Obj? = null
    ) : TriggerSpec {
        override val type: String get() = TYPE

        init {
            requireAutomationType(eventType, "eventType")
            filter?.let(AutomationDataSafety::validateJsonValue)
        }

        companion object { const val TYPE: String = "core.on-event" }
    }

    data class Threshold(
        val metric: MetricRef,
        val op: CompareOp,
        val value: BigDecimal
    ) : TriggerSpec {
        override val type: String get() = TYPE

        companion object { const val TYPE: String = "core.threshold" }
    }

    data class Geofence(
        val campusId: CampusId,
        val enter: Boolean
    ) : TriggerSpec {
        override val type: String get() = TYPE

        companion object { const val TYPE: String = "core.geofence" }
    }

    data class Plugin(
        val pluginId: String,
        val triggerId: String,
        val config: JsonValue.Obj = EMPTY_AUTOMATION_CONFIG
    ) : TriggerSpec {
        override val type: String get() = typeFor(pluginId, triggerId)

        init {
            requireAutomationToken(pluginId, "pluginId")
            requireAutomationToken(triggerId, "triggerId")
            requireAutomationType(type, "plugin trigger type")
            AutomationDataSafety.validateJsonValue(config)
        }

        companion object {
            fun typeFor(pluginId: String, triggerId: String): String =
                "plugin." + pluginId + "." + triggerId
        }
    }
}

/** 条件与动作按 type + config(JsonObject) 组织；具体语义由 SPI 实现提供。 */
data class ConditionSpec(
    val type: String,
    val config: JsonValue.Obj = EMPTY_AUTOMATION_CONFIG
) {
    init {
        requireAutomationType(type)
        AutomationDataSafety.validateJsonValue(config)
    }
}

data class ActionSpec(
    val type: String,
    val config: JsonValue.Obj = EMPTY_AUTOMATION_CONFIG
) {
    init {
        requireAutomationType(type)
        AutomationDataSafety.validateJsonValue(config)
    }
}

/**
 * 去重策略。首期只开放 OncePerTriggerInstance：triggerInstanceKey 是触发实例身份，
 * 同一实例无论是否重投、何时补处理，都只能执行一次。未来新增更粗粒度策略时，
 * 必须先保留这一实例级不变量，再叠加额外合并键。
 */
sealed interface DedupePolicy {
    data object OncePerTriggerInstance : DedupePolicy
}

/** quietHours 只抑制 LOCAL_NOTIFY 动作；LOCAL_SILENT 的本地数据动作仍可执行。 */
data class QuietHours(
    val window: LocalTimeWindow,
    val zone: ZoneId
)

/** 自动化规则。scope 与 id 均无默认值，禁止把规则误放到全局或错误账号。 */
data class AutomationRule(
    val id: String,
    val scope: SchoolAccountScope,
    val name: String,
    val enabled: Boolean,
    val trigger: TriggerSpec,
    val conditions: List<ConditionSpec>,
    val actions: List<ActionSpec>,
    val dedupe: DedupePolicy = DedupePolicy.OncePerTriggerInstance,
    val quietHours: QuietHours? = null,
    val templateId: String? = null,
    val schemaVersion: Int = 1
) {
    init {
        requireAutomationToken(id, "rule id")
        require(name.isNotBlank() && name.length <= 128) { "规则名称为空或过长" }
        require(name.none { it.isISOControl() }) { "规则名称不能包含控制字符" }
        require(actions.isNotEmpty()) { "自动化规则至少需要一个动作" }
        require(schemaVersion > 0) { "schemaVersion 必须大于 0" }
        templateId?.let { requireAutomationToken(it, "templateId") }
    }
}

/**
 * 一次触发实例。DomainEvent 触发时 triggerInstanceKey 只是调用方提示；引擎入口会强制用 eventId 重新派生，
 * 因此改写传入 key 不能让同一事件获得第二次执行权，outbox 重投也不会重复执行。
 * OnEvent 规则的实例必须携带 event 且事件类型/filter 满足规则声明；event=null 或事件不匹配的
 * OnEvent 触发在任何 dedupe/run 写入之前被引擎 fail-closed 拒绝，不消耗合法事件的执行权。
 */
data class TriggerInstance(
    val ruleId: String,
    val triggerInstanceKey: String,
    val firedAt: Instant,
    val event: cn.edu.haut.campus.core.events.DomainEvent? = null,
    val source: String = "manual"
) {
    init {
        requireAutomationToken(ruleId, "ruleId")
        requireAutomationToken(triggerInstanceKey, "triggerInstanceKey", 512)
        requireAutomationToken(source, "source", 64)
    }
}

/** 运行证据只允许小型事实；大文本、凭据与原文 blob 不得进入运行记录。 */
data class Evidence(
    val source: String,
    val detail: String,
    val at: Instant? = null,
    val data: JsonValue? = null
) {
    init {
        requireAutomationToken(source, "evidence source", 128)
        require(detail.isNotBlank() && detail.length <= 512) { "证据 detail 为空或过长" }
        require(detail.none { it.isISOControl() }) { "证据 detail 不能包含控制字符" }
        if (data != null) {
            AutomationDataSafety.validateJsonValue(data)
            require(Json.canonical(data).length <= 2048) { "证据 JSON 过大" }
        }
    }
}

enum class Certainty {
    CERTAIN,
    UNKNOWN
}

/**
 * 条件结果。UNKNOWN 的语义是“证据不足”，AutomationEngine 一律按不通过处理；
 * 即使 evaluator 错误地把 passed 标成 true，运行记录也会被归一化为 false 并写明原因。
 */
data class ConditionResult(
    val conditionType: String,
    val passed: Boolean,
    val evidence: List<Evidence> = emptyList(),
    val certainty: Certainty = Certainty.CERTAIN,
    val reason: String? = null
) {
    init {
        requireAutomationType(conditionType, "conditionType")
        reason?.let {
            require(it.isNotBlank() && it.length <= 512) { "条件 reason 为空或过长" }
            require(it.none { ch -> ch.isISOControl() }) { "条件 reason 不能包含控制字符" }
        }
    }

    val effectivePassed: Boolean
        get() = passed && certainty == Certainty.CERTAIN
}

enum class ActionRisk {
    LOCAL_SILENT,
    LOCAL_NOTIFY,
    NEEDS_USER_CONFIRM,
    EXTERNAL_WRITE_FORBIDDEN
}

enum class ActionOutcomeStatus {
    SUCCESS,
    FAILED,
    DRY_RUN_PREVIEW,
    BLOCKED_CONFIRMATION_REQUIRED,
    SKIPPED_QUIET_HOURS
}

data class ActionOutcome(
    val actionType: String,
    val risk: ActionRisk,
    val status: ActionOutcomeStatus,
    val detail: String? = null,
    val evidence: List<Evidence> = emptyList(),
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null
) {
    init {
        requireAutomationType(actionType, "actionType")
        detail?.let {
            require(it.isNotBlank() && it.length <= 512) { "动作 detail 为空或过长" }
            require(it.none { ch -> ch.isISOControl() }) { "动作 detail 不能包含控制字符" }
        }
        if (startedAt != null && finishedAt != null) {
            require(!finishedAt.isBefore(startedAt)) { "动作结束时间早于开始时间" }
        }
    }
}

enum class RunStatus {
    WOULD_EXECUTE,
    EXECUTED,
    PARTIAL_FAILED,
    FAILED,
    CONDITIONS_NOT_MET,
    DISABLED,
    INVALID_RULE,
    SUPPRESSED
}

/** MASTER_PLAN §6.3 的运行记录模型；dedupeKey、起止时间与 statusReason 是排障所需的最小补充。 */
data class AutomationRun(
    val runId: String,
    val ruleId: String,
    val triggerInstanceKey: String,
    val firedAt: Instant,
    val evaluations: List<ConditionResult>,
    val outcomes: List<ActionOutcome>,
    val status: RunStatus,
    val dryRun: Boolean,
    val dedupeKey: String,
    val startedAt: Instant,
    val finishedAt: Instant,
    val statusReason: String? = null,
    val schemaVersion: Int = 1
) {
    init {
        requireAutomationToken(runId, "runId", 128)
        requireAutomationToken(ruleId, "ruleId")
        requireAutomationToken(triggerInstanceKey, "triggerInstanceKey", 512)
        requireAutomationToken(dedupeKey, "dedupeKey", 1024)
        require(!finishedAt.isBefore(startedAt)) { "运行结束时间早于开始时间" }
        require(schemaVersion > 0) { "schemaVersion 必须大于 0" }
        statusReason?.let {
            require(it.isNotBlank() && it.length <= 1024) { "运行 statusReason 为空或过长" }
            require(it.none { ch -> ch.isISOControl() }) { "运行 statusReason 不能包含控制字符" }
        }
    }
}

/** dedupe 保留状态。PENDING 表示执行窗口仍占用实例；RUN_RECORDED 是终态，表示对应 run 已追加。 */
enum class AutomationDedupeState {
    PENDING,
    RUN_RECORDED
}

/**
 * 去重存根记录。PENDING 记录带有 leaseExpiresAt：崩溃或取消不会把触发实例永久卡死；
 * 超时后的重投可以原子重新获得执行权。run append 成功后必须按同一条 PENDING 记录条件式转为 RUN_RECORDED。
 */
data class AutomationDedupeRecord(
    val dedupeKey: String,
    val ruleId: String,
    val triggerInstanceKey: String,
    val runId: String,
    val acquiredAt: Instant,
    val policyFingerprint: String,
    val leaseExpiresAt: Instant,
    val state: AutomationDedupeState = AutomationDedupeState.PENDING,
    val completedAt: Instant? = null
) {
    init {
        requireAutomationToken(dedupeKey, "dedupeKey", 1024)
        requireAutomationToken(ruleId, "ruleId")
        requireAutomationToken(triggerInstanceKey, "triggerInstanceKey", 512)
        requireAutomationToken(runId, "runId", 128)
        requireAutomationToken(policyFingerprint, "policyFingerprint", 256)
        require(!leaseExpiresAt.isBefore(acquiredAt)) { "dedupe lease 过期时间早于获取时间" }
        when (state) {
            AutomationDedupeState.PENDING -> require(completedAt == null) { "PENDING dedupe 不能有 completedAt" }
            AutomationDedupeState.RUN_RECORDED -> {
                val completed = completedAt
                require(completed != null) { "RUN_RECORDED dedupe 缺少 completedAt" }
                require(!completed.isBefore(acquiredAt)) { "dedupe completedAt 早于 acquiredAt" }
            }
        }
    }
}

/** NEEDS_USER_CONFIRM 动作的一次性确认凭证；不包含用户秘密，也不允许跨规则/跨配置重用。 */
data class ActionConfirmation(
    val confirmationId: String,
    val ruleId: String,
    val actionType: String,
    val configDigest: String,
    val confirmedAt: Instant,
    val expiresAt: Instant
) {
    init {
        requireAutomationToken(confirmationId, "confirmationId", 128)
        requireAutomationToken(ruleId, "ruleId")
        requireAutomationType(actionType, "actionType")
        require(configDigest.matches(Regex("^[0-9a-f]{64}$"))) { "configDigest 必须是 SHA-256 hex" }
        require(expiresAt.isAfter(confirmedAt)) { "确认凭证 expiresAt 必须晚于 confirmedAt" }
    }

    fun isValidAt(now: Instant): Boolean = !now.isBefore(confirmedAt) && now.isBefore(expiresAt)
}
