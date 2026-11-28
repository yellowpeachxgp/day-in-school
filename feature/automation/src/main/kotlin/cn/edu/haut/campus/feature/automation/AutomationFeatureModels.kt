package cn.edu.haut.campus.feature.automation

import cn.edu.haut.campus.core.automation.ActionSpec
import cn.edu.haut.campus.core.automation.AutomationRule
import cn.edu.haut.campus.core.automation.AutomationRun
import cn.edu.haut.campus.core.automation.BuiltinAutomationTypes
import cn.edu.haut.campus.core.automation.CompareOp
import cn.edu.haut.campus.core.automation.ConditionSpec
import cn.edu.haut.campus.core.automation.MetricRef
import cn.edu.haut.campus.core.automation.TriggerSpec
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.timeline.TimelineKind
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.util.UUID

/** 编辑器内置触发器选择。插件触发器不进入首期骨架表单。 */
enum class AutomationTriggerChoice(val label: String) {
    AT_TIME("定时"),
    BEFORE_EVENT("课前 N 分钟"),
    ON_EVENT("领域事件"),
    THRESHOLD("水电阈值"),
    GEOFENCE("校区围栏")
}

data class AutomationEditorDraft(
    val id: String = "rule-" + UUID.randomUUID().toString().substring(0, 8),
    val name: String = "新的自动化",
    val enabled: Boolean = true,
    val triggerChoice: AutomationTriggerChoice = AutomationTriggerChoice.AT_TIME,
    val atTime: LocalTime = LocalTime.of(8, 0),
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val beforeMinutesText: String = "20",
    val eventTypeText: String = "data.stale",
    val thresholdYuanText: String = "15",
    val campusIdText: String = "haut-lianhuajie",
    val geofenceEnter: Boolean = true,
    val hasClassToday: Boolean = true,
    val onCampus: Boolean = false,
    val focusPeriod: Boolean = false,
    val commuteTime: Boolean = false,
    val commuteMaxMinutesText: String = "45",
    val dataFreshness: Boolean = false,
    val freshnessMaxAgeHoursText: String = "24",
    val notify: Boolean = true,
    val notificationTitle: String = "自动化提醒",
    val notificationBody: String = "规则已触发",
    val createTask: Boolean = false,
    val taskTitle: String = "自动化待办",
    val taskDueMinutesText: String = "60",
    val refreshHome: Boolean = true,
    val error: String? = null
) {
    fun validate(): String? {
        if (name.isBlank()) return "请填写规则名称"
        val beforeMinutes = beforeMinutesText.toIntOrNull()
        if (triggerChoice == AutomationTriggerChoice.BEFORE_EVENT && (beforeMinutes == null || beforeMinutes !in 1..720)) {
            return "课前提前分钟必须是 1..720"
        }
        if (triggerChoice == AutomationTriggerChoice.ON_EVENT && eventTypeText.isBlank()) return "请填写事件类型"
        if (triggerChoice == AutomationTriggerChoice.THRESHOLD && thresholdYuanText.toBigDecimalOrNull() == null) {
            return "水电阈值必须是数字"
        }
        if (campusIdText.isBlank() && (triggerChoice == AutomationTriggerChoice.GEOFENCE || onCampus)) return "请填写校区 ID"
        val commuteMaxMinutes = commuteMaxMinutesText.toIntOrNull()
        if (commuteTime && (commuteMaxMinutes == null || commuteMaxMinutes !in 0..1440)) return "通勤上限必须是 0..1440 分钟"
        val freshnessHours = freshnessMaxAgeHoursText.toIntOrNull()
        if (dataFreshness && (freshnessHours == null || freshnessHours !in 1..168)) return "新鲜度上限必须是 1..168 小时"
        val dueMinutes = taskDueMinutesText.toIntOrNull()
        if (createTask && (dueMinutes == null || dueMinutes !in 0..10080)) return "待办截止必须是 0..10080 分钟"
        if (!notify && !createTask && !refreshHome) return "至少选择一个动作"
        return null
    }

    fun toRule(scope: SchoolAccountScope): AutomationRule {
        validate()?.let { throw IllegalArgumentException(it) }
        return AutomationRule(
            id = id,
            scope = scope,
            name = name.trim(),
            enabled = enabled,
            trigger = toTrigger(),
            conditions = toConditions(),
            actions = toActions()
        )
    }

    private fun toTrigger(): TriggerSpec = when (triggerChoice) {
        AutomationTriggerChoice.AT_TIME -> TriggerSpec.AtTime(atTime, days)
        AutomationTriggerChoice.BEFORE_EVENT -> TriggerSpec.BeforeEvent(
            kinds = setOf(TimelineKind.COURSE, TimelineKind.PRACTICE),
            offset = Duration.ofMinutes(beforeMinutesText.toLong())
        )
        AutomationTriggerChoice.ON_EVENT -> TriggerSpec.OnEvent(eventType = eventTypeText.trim())
        AutomationTriggerChoice.THRESHOLD -> TriggerSpec.Threshold(
            metric = MetricRef(BuiltinAutomationTypes.METRIC_UTILITY_BALANCE_CENTS),
            op = CompareOp.LESS_THAN,
            value = BigDecimal(thresholdYuanText).movePointRight(2)
        )
        AutomationTriggerChoice.GEOFENCE -> TriggerSpec.Geofence(
            campusId = CampusId(campusIdText.trim()),
            enter = geofenceEnter
        )
    }

    private fun toConditions(): List<ConditionSpec> = buildList {
        if (hasClassToday) add(ConditionSpec(BuiltinAutomationTypes.CONDITION_HAS_CLASS_TODAY))
        if (onCampus) {
            add(
                ConditionSpec(
                    BuiltinAutomationTypes.CONDITION_ON_CAMPUS,
                    jsonObject("campusId" to JsonValue.Str(campusIdText.trim()))
                )
            )
        }
        if (focusPeriod) {
            add(
                ConditionSpec(
                    BuiltinAutomationTypes.CONDITION_FOCUS_PERIOD,
                    jsonObject("active" to JsonValue.Bool(true))
                )
            )
        }
        if (commuteTime) {
            add(
                ConditionSpec(
                    BuiltinAutomationTypes.CONDITION_COMMUTE_TIME,
                    jsonObject(
                        "maxMinutes" to JsonValue.Num(commuteMaxMinutesText.toInt().toString()),
                        "toCampus" to JsonValue.Str(campusIdText.trim())
                    )
                )
            )
        }
        if (dataFreshness) {
            add(
                ConditionSpec(
                    BuiltinAutomationTypes.CONDITION_DATA_FRESHNESS,
                    jsonObject(
                        "capability" to JsonValue.Str("TIMETABLE_READ"),
                        "maxAgeSeconds" to JsonValue.Num((freshnessMaxAgeHoursText.toInt() * 3600).toString())
                    )
                )
            )
        }
    }

    private fun toActions(): List<ActionSpec> = buildList {
        if (notify) {
            add(
                ActionSpec(
                    BuiltinAutomationTypes.ACTION_LOCAL_NOTIFICATION,
                    jsonObject(
                        "title" to JsonValue.Str(notificationTitle.ifBlank { name }),
                        "body" to JsonValue.Str(notificationBody.ifBlank { name })
                    )
                )
            )
        }
        if (createTask) {
            add(
                ActionSpec(
                    BuiltinAutomationTypes.ACTION_CREATE_PERSONAL_EVENT,
                    jsonObject(
                        "kind" to JsonValue.Str("TASK_DUE"),
                        "title" to JsonValue.Str(taskTitle.ifBlank { name }),
                        "dueInMinutes" to JsonValue.Num(taskDueMinutesText.toInt().toString())
                    )
                )
            )
        }
        if (refreshHome) add(ActionSpec(BuiltinAutomationTypes.ACTION_REFRESH_HOME))
    }
}

enum class AutomationScreenMode {
    LIST,
    EDITOR,
    RUNS
}

data class AutomationUiState(
    val loading: Boolean = true,
    val rules: List<AutomationRule> = emptyList(),
    val mode: AutomationScreenMode = AutomationScreenMode.LIST,
    val draft: AutomationEditorDraft = AutomationEditorDraft(),
    val selectedRuleId: String? = null,
    val runs: List<AutomationRun> = emptyList(),
    val message: String? = null,
    val error: String? = null
)

fun triggerSummary(rule: AutomationRule): String = when (val trigger = rule.trigger) {
    is TriggerSpec.AtTime -> "定时 " + trigger.local + " · " + trigger.days.size + " 天/周"
    is TriggerSpec.BeforeEvent -> "课前 " + trigger.offset.toMinutes() + " 分钟"
    is TriggerSpec.OnEvent -> "事件 " + trigger.eventType
    is TriggerSpec.Threshold -> "水电阈值 " + trigger.op + " " + trigger.value + " 分"
    is TriggerSpec.Geofence -> (if (trigger.enter) "进入 " else "离开 ") + trigger.campusId.value
    is TriggerSpec.Plugin -> "插件触发 " + trigger.pluginId
}

fun actionSummary(rule: AutomationRule): String = rule.actions.joinToString("、") { spec ->
    when (spec.type) {
        BuiltinAutomationTypes.ACTION_LOCAL_NOTIFICATION -> "通知"
        BuiltinAutomationTypes.ACTION_CREATE_PERSONAL_EVENT -> "待办/日程"
        BuiltinAutomationTypes.ACTION_REFRESH_HOME -> "刷新首页"
        else -> spec.type
    }
}

private fun jsonObject(vararg fields: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(fields.toMap())
