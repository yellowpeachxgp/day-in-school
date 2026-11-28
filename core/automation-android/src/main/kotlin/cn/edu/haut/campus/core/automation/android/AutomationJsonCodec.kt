package cn.edu.haut.campus.core.automation.android

import cn.edu.haut.campus.core.automation.ActionOutcome
import cn.edu.haut.campus.core.automation.ActionOutcomeStatus
import cn.edu.haut.campus.core.automation.ActionRisk
import cn.edu.haut.campus.core.automation.ActionSpec
import cn.edu.haut.campus.core.automation.AutomationRule
import cn.edu.haut.campus.core.automation.AutomationRun
import cn.edu.haut.campus.core.automation.ActionConfirmation
import cn.edu.haut.campus.core.automation.Certainty
import cn.edu.haut.campus.core.automation.CompareOp
import cn.edu.haut.campus.core.automation.ConditionResult
import cn.edu.haut.campus.core.automation.ConditionSpec
import cn.edu.haut.campus.core.automation.DedupePolicy
import cn.edu.haut.campus.core.automation.Evidence
import cn.edu.haut.campus.core.automation.MetricRef
import cn.edu.haut.campus.core.automation.QuietHours
import cn.edu.haut.campus.core.automation.RunStatus
import cn.edu.haut.campus.core.automation.TriggerSpec
import cn.edu.haut.campus.core.model.CampusId
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

/** automation_* 表 JSON 字段的严格编解码；解析失败必须抛错，绝不静默丢字段。 */
object AutomationJsonCodec {
    fun encodeRulePayload(rule: AutomationRule): RulePayload = RulePayload(
        triggerJson = canonical(encodeTrigger(rule.trigger)),
        conditionsJson = canonical(JsonValue.Arr(rule.conditions.map(::encodeConditionSpec))),
        actionsJson = canonical(JsonValue.Arr(rule.actions.map(::encodeActionSpec))),
        dedupe = when (rule.dedupe) {
            DedupePolicy.OncePerTriggerInstance -> "once-per-trigger-instance"
        },
        quietHoursJson = rule.quietHours?.let { canonical(encodeQuietHours(it)) }
    )

    fun decodeRulePayload(
        triggerJson: String,
        conditionsJson: String,
        actionsJson: String,
        dedupe: String,
        quietHoursJson: String?,
        ruleId: String,
        scope: cn.edu.haut.campus.core.model.SchoolAccountScope,
        name: String,
        enabled: Boolean,
        templateId: String?,
        schemaVersion: Int
    ): AutomationRule = AutomationRule(
        id = ruleId,
        scope = scope,
        name = name,
        enabled = enabled,
        trigger = decodeTrigger(parseObj(triggerJson)),
        conditions = parseArr(conditionsJson).items.map(::decodeConditionSpec),
        actions = parseArr(actionsJson).items.map(::decodeActionSpec),
        dedupe = when (dedupe) {
            "once-per-trigger-instance" -> DedupePolicy.OncePerTriggerInstance
            else -> throw IllegalArgumentException("未知 automation dedupe: " + dedupe)
        },
        quietHours = quietHoursJson?.let { decodeQuietHours(parseObj(it)) },
        templateId = templateId,
        schemaVersion = schemaVersion
    )

    fun encodeRunContent(run: AutomationRun): RunContent = RunContent(
        evaluationsJson = canonical(JsonValue.Arr(run.evaluations.map(::encodeConditionResult))),
        outcomesJson = canonical(JsonValue.Arr(run.outcomes.map(::encodeActionOutcomeValue)))
    )

    fun decodeRunContent(
        runId: String,
        ruleId: String,
        triggerInstanceKey: String,
        dedupeKey: String,
        firedAt: Instant,
        startedAt: Instant,
        finishedAt: Instant,
        status: String,
        dryRun: Boolean,
        evaluationsJson: String,
        outcomesJson: String,
        statusReason: String?,
        schemaVersion: Int
    ): AutomationRun = AutomationRun(
        runId = runId,
        ruleId = ruleId,
        triggerInstanceKey = triggerInstanceKey,
        firedAt = firedAt,
        evaluations = parseArr(evaluationsJson).items.map(::decodeConditionResult),
        outcomes = parseArr(outcomesJson).items.map(::decodeActionOutcome),
        status = RunStatus.valueOf(status),
        dryRun = dryRun,
        dedupeKey = dedupeKey,
        startedAt = startedAt,
        finishedAt = finishedAt,
        statusReason = statusReason,
        schemaVersion = schemaVersion
    )

    fun encodeActionOutcome(outcome: ActionOutcome): String = canonical(encodeActionOutcomeValue(outcome))
    fun decodeActionOutcome(raw: String): ActionOutcome = decodeActionOutcome(parseObj(raw))

    fun encodeConfirmation(confirmation: ActionConfirmation): String = canonical(
        obj(
            "confirmationId" to JsonValue.Str(confirmation.confirmationId),
            "ruleId" to JsonValue.Str(confirmation.ruleId),
            "actionType" to JsonValue.Str(confirmation.actionType),
            "configDigest" to JsonValue.Str(confirmation.configDigest),
            "confirmedAt" to JsonValue.Str(confirmation.confirmedAt.toString()),
            "expiresAt" to JsonValue.Str(confirmation.expiresAt.toString())
        )
    )

    fun decodeConfirmation(raw: String): ActionConfirmation {
        val node = parseObj(raw)
        return ActionConfirmation(
            confirmationId = node.reqString("confirmationId"),
            ruleId = node.reqString("ruleId"),
            actionType = node.reqString("actionType"),
            configDigest = node.reqString("configDigest"),
            confirmedAt = Instant.parse(node.reqString("confirmedAt")),
            expiresAt = Instant.parse(node.reqString("expiresAt"))
        )
    }

    private fun encodeTrigger(trigger: TriggerSpec): JsonValue.Obj = when (trigger) {
        is TriggerSpec.AtTime -> obj(
            "type" to str(trigger.type),
            "local" to str(trigger.local.toString()),
            "days" to JsonValue.Arr(trigger.days.map { str(it.name) })
        )
        is TriggerSpec.BeforeEvent -> obj(
            "type" to str(trigger.type),
            "kinds" to JsonValue.Arr(trigger.kinds.map { str(it.name) }),
            "offsetSeconds" to num(trigger.offset.seconds.toString())
        )
        is TriggerSpec.OnEvent -> obj(
            "type" to str(trigger.type),
            "eventType" to str(trigger.eventType),
            "filter" to (trigger.filter ?: JsonValue.Null)
        )
        is TriggerSpec.Threshold -> obj(
            "type" to str(trigger.type),
            "metric" to str(trigger.metric.value),
            "op" to str(trigger.op.name),
            "value" to str(trigger.value.toPlainString()),
            "hysteresis" to str(trigger.hysteresis.toPlainString()),
            "cooldownSeconds" to num(trigger.cooldown.seconds.toString())
        )
        is TriggerSpec.Geofence -> obj(
            "type" to str(trigger.type),
            "campusId" to str(trigger.campusId.value),
            "enter" to bool(trigger.enter)
        )
        is TriggerSpec.Plugin -> obj(
            "type" to str(trigger.type),
            "pluginId" to str(trigger.pluginId),
            "triggerId" to str(trigger.triggerId),
            "config" to trigger.config
        )
    }

    private fun decodeTrigger(node: JsonValue.Obj): TriggerSpec = when (val type = node.reqString("type")) {
        TriggerSpec.AtTime.TYPE -> TriggerSpec.AtTime(
            local = LocalTime.parse(node.reqString("local")),
            days = node.reqArr("days").map { DayOfWeek.valueOf(it.asString()) }.toSet()
        )
        TriggerSpec.BeforeEvent.TYPE -> TriggerSpec.BeforeEvent(
            kinds = node.reqArr("kinds").map { TimelineKind.valueOf(it.asString()) }.toSet(),
            offset = Duration.ofSeconds(node.reqLong("offsetSeconds"))
        )
        TriggerSpec.OnEvent.TYPE -> TriggerSpec.OnEvent(
            eventType = node.reqString("eventType"),
            filter = node.fields["filter"]?.let { filterNode ->
                if (filterNode == JsonValue.Null) {
                    null
                } else {
                    filterNode.asObjOrNull ?: throw IllegalArgumentException("OnEvent filter 必须是 object 或 null")
                }
            }
        )
        TriggerSpec.Threshold.TYPE -> TriggerSpec.Threshold(
            metric = MetricRef(node.reqString("metric")),
            op = CompareOp.valueOf(node.reqString("op")),
            value = BigDecimal(node.reqString("value")),
            hysteresis = node.optString("hysteresis")?.let(::BigDecimal)
                ?: TriggerSpec.Threshold.DEFAULT_THRESHOLD_HYSTERESIS_CENTIUNITS,
            cooldown = Duration.ofSeconds(node.optLong("cooldownSeconds") ?: TriggerSpec.Threshold.DEFAULT_THRESHOLD_COOLDOWN.seconds)
        )
        TriggerSpec.Geofence.TYPE -> TriggerSpec.Geofence(
            campusId = CampusId(node.reqString("campusId")),
            enter = node.reqBool("enter")
        )
        else -> {
            if (type.startsWith("plugin.")) {
                val configNode = node.fields["config"] ?: JsonValue.Null
                val decoded = TriggerSpec.Plugin(
                    pluginId = node.reqString("pluginId"),
                    triggerId = node.reqString("triggerId"),
                    config = if (configNode == JsonValue.Null) {
                        JsonValue.Obj(emptyMap())
                    } else {
                        configNode.asObjOrNull ?: throw IllegalArgumentException("plugin trigger config 必须是 object")
                    }
                )
                require(decoded.type == type) { "plugin trigger type 与 pluginId/triggerId 不一致" }
                decoded
            } else {
                throw IllegalArgumentException("未知 automation trigger type: " + type)
            }
        }
    }

    private fun encodeQuietHours(value: QuietHours): JsonValue.Obj = obj(
        "start" to str(value.window.start.toString()),
        "end" to str(value.window.end.toString()),
        "zone" to str(value.zone.id)
    )

    private fun decodeQuietHours(node: JsonValue.Obj): QuietHours = QuietHours(
        window = LocalTimeWindow(
            LocalTime.parse(node.reqString("start")),
            LocalTime.parse(node.reqString("end"))
        ),
        zone = ZoneId.of(node.reqString("zone"))
    )

    private fun encodeConditionSpec(spec: ConditionSpec): JsonValue.Obj = obj(
        "type" to str(spec.type),
        "config" to spec.config
    )

    private fun decodeConditionSpec(value: JsonValue): ConditionSpec {
        val node = value.asObjOrNull ?: throw IllegalArgumentException("condition spec 必须是 object")
        return ConditionSpec(node.reqString("type"), decodeRequiredConfigObject(node, "condition"))
    }

    private fun encodeActionSpec(spec: ActionSpec): JsonValue.Obj = obj(
        "type" to str(spec.type),
        "config" to spec.config
    )

    private fun decodeActionSpec(value: JsonValue): ActionSpec {
        val node = value.asObjOrNull ?: throw IllegalArgumentException("action spec 必须是 object")
        return ActionSpec(node.reqString("type"), decodeRequiredConfigObject(node, "action"))
    }

    private fun encodeConditionResult(result: ConditionResult): JsonValue.Obj = obj(
        "conditionType" to str(result.conditionType),
        "passed" to bool(result.passed),
        "certainty" to str(result.certainty.name),
        "reason" to nullableStr(result.reason),
        "evidence" to JsonValue.Arr(result.evidence.map(::encodeEvidence))
    )

    private fun decodeConditionResult(value: JsonValue): ConditionResult {
        val node = value.asObjOrNull ?: throw IllegalArgumentException("condition result 必须是 object")
        return ConditionResult(
            conditionType = node.reqString("conditionType"),
            passed = node.reqBool("passed"),
            certainty = Certainty.valueOf(node.reqString("certainty")),
            reason = node.optString("reason"),
            evidence = node.reqArr("evidence").map(::decodeEvidence)
        )
    }

    private fun encodeEvidence(evidence: Evidence): JsonValue.Obj = obj(
        "source" to str(evidence.source),
        "detail" to str(evidence.detail),
        "at" to nullableStr(evidence.at?.toString()),
        "data" to (evidence.data ?: JsonValue.Null)
    )

    private fun decodeEvidence(value: JsonValue): Evidence {
        val node = value.asObjOrNull ?: throw IllegalArgumentException("evidence 必须是 object")
        return Evidence(
            source = node.reqString("source"),
            detail = node.reqString("detail"),
            at = node.optString("at")?.let(Instant::parse),
            data = node.fields["data"]?.takeIf { it != JsonValue.Null }
        )
    }

    private fun encodeActionOutcomeValue(outcome: ActionOutcome): JsonValue.Obj = obj(
        "actionType" to str(outcome.actionType),
        "risk" to str(outcome.risk.name),
        "status" to str(outcome.status.name),
        "detail" to nullableStr(outcome.detail),
        "evidence" to JsonValue.Arr(outcome.evidence.map(::encodeEvidence)),
        "startedAt" to nullableStr(outcome.startedAt?.toString()),
        "finishedAt" to nullableStr(outcome.finishedAt?.toString())
    )

    private fun decodeActionOutcome(value: JsonValue): ActionOutcome {
        val node = value.asObjOrNull ?: throw IllegalArgumentException("action outcome 必须是 object")
        return ActionOutcome(
            actionType = node.reqString("actionType"),
            risk = ActionRisk.valueOf(node.reqString("risk")),
            status = ActionOutcomeStatus.valueOf(node.reqString("status")),
            detail = node.optString("detail"),
            evidence = node.reqArr("evidence").map(::decodeEvidence),
            startedAt = node.optString("startedAt")?.let(Instant::parse),
            finishedAt = node.optString("finishedAt")?.let(Instant::parse)
        )
    }

    data class RulePayload(
        val triggerJson: String,
        val conditionsJson: String,
        val actionsJson: String,
        val dedupe: String,
        val quietHoursJson: String?
    )

    data class RunContent(
        val evaluationsJson: String,
        val outcomesJson: String
    )

    private fun decodeRequiredConfigObject(node: JsonValue.Obj, label: String): JsonValue.Obj {
        val configNode = node.fields["config"] ?: throw IllegalArgumentException(label + " spec 缺少 config")
        return configNode.asObjOrNull ?: throw IllegalArgumentException(label + " spec config 必须是 object")
    }

    private fun canonical(value: JsonValue): String = Json.canonical(value)
    private fun parseObj(raw: String): JsonValue.Obj = Json.parse(raw).asObjOrNull
        ?: throw IllegalArgumentException("automation JSON 必须是 object")
    private fun parseArr(raw: String): JsonValue.Arr = Json.parse(raw).asArrOrNull
        ?: throw IllegalArgumentException("automation JSON 必须是 array")
    private fun obj(vararg fields: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(fields.toMap())
    private fun str(value: String): JsonValue.Str = JsonValue.Str(value)
    private fun nullableStr(value: String?): JsonValue = value?.let(::str) ?: JsonValue.Null
    private fun bool(value: Boolean): JsonValue.Bool = JsonValue.Bool(value)
    private fun num(value: String): JsonValue.Num = JsonValue.Num(value)
    private fun JsonValue.Obj.req(key: String): JsonValue = fields[key]
        ?: throw IllegalArgumentException("automation JSON 缺少字段 " + key)
    private fun JsonValue.Obj.reqString(key: String): String = req(key).asString()
    private fun JsonValue.Obj.optString(key: String): String? = fields[key]?.takeIf { it != JsonValue.Null }?.asString()
    private fun JsonValue.Obj.reqBool(key: String): Boolean = req(key).boolOrNull()
        ?: throw IllegalArgumentException("automation JSON 字段必须是 boolean: " + key)
    private fun JsonValue.Obj.reqArr(key: String): List<JsonValue> = req(key).asArrOrNull?.items
        ?: throw IllegalArgumentException("automation JSON 字段必须是 array: " + key)
    private fun JsonValue.Obj.reqLong(key: String): Long = (req(key) as? JsonValue.Num)?.literal?.toLong()
        ?: throw IllegalArgumentException("automation JSON 字段必须是 number: " + key)
    private fun JsonValue.Obj.optLong(key: String): Long? = (fields[key] as? JsonValue.Num)?.literal?.toLong()
    private fun JsonValue.asString(): String = stringOrNull()
        ?: throw IllegalArgumentException("automation JSON 值必须是 string")
}
