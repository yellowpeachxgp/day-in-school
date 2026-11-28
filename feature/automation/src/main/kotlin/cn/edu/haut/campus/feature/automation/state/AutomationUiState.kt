package cn.edu.haut.campus.feature.automation.state

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

/** MASTER_PLAN §10.2 的统一状态矩阵。 */
enum class AutomationScreenStatus {
    LOADING,
    EMPTY,
    CONTENT,
    PARTIAL,
    STALE,
    ERROR_RETRYABLE,
    ERROR_NON_RETRYABLE,
    NEEDS_PERMISSION,
    NEEDS_LOGIN,
    OFFLINE,
    UNSUPPORTED
}

data class AutomationTemplateUiItem(
    val id: String,
    val title: String,
    val description: String,
    val requiredEvidence: List<String>
)

/** 表单式编辑器草稿。无法被骨架表单表达的高级条件/动作放入 preserved*，保存时绝不静默丢弃。 */
data class AutomationEditorDraft(
    val id: String = "rule-" + UUID.randomUUID().toString(),
    val name: String = "新的自动化",
    val enabled: Boolean = false,
    val templateId: String? = null,
    val triggerChoice: AutomationTriggerChoice = AutomationTriggerChoice.AT_TIME,
    val atTime: LocalTime = LocalTime.of(8, 0),
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val beforeMinutesText: String = "20",
    val eventTypeText: String = "utility.balance.sampled",
    val eventFilter: JsonValue.Obj? = null,
    val thresholdValueText: String = "15",
    val thresholdWater: Boolean = false,
    val thresholdHysteresisText: String = "1",
    val thresholdCooldownMinutesText: String = "360",
    val campusIdText: String = "haut-lianhuajie",
    val geofenceEnter: Boolean = true,
    val hasClassToday: Boolean = true,
    val onCampus: Boolean = false,
    val focusPeriod: Boolean = false,
    val commuteTime: Boolean = false,
    val commuteFromCampusText: String = "",
    val commuteMinMinutesText: String = "0",
    val commuteMaxMinutesText: String = "45",
    val dataFreshness: Boolean = false,
    val freshnessMaxAgeHoursText: String = "24",
    val weatherPrecipitation: Boolean = false,
    val weatherTermsAccepted: Boolean = false,
    val weatherHorizonMinutesText: String = "180",
    val weatherMinProbabilityText: String = "35",
    val utilityBalance: Boolean = false,
    val utilityBalanceWater: Boolean = false,
    val utilityBalanceValueText: String = "20",
    val courseCancellation: Boolean = false,
    val notify: Boolean = true,
    val notificationTitle: String = "自动化提醒",
    val notificationBody: String = "规则已触发",
    val createTask: Boolean = false,
    val taskTitle: String = "自动化待办",
    val taskDueMinutesText: String = "60",
    val createEvent: Boolean = false,
    val eventTitle: String = "自动化日程",
    val eventStartMinutesText: String = "0",
    val eventDurationMinutesText: String = "60",
    val refreshHome: Boolean = true,
    val preservedConditions: List<ConditionSpec> = emptyList(),
    val preservedActions: List<ActionSpec> = emptyList(),
    val error: String? = null
) {
    fun validate(): String? {
        if (name.isBlank()) return "请填写规则名称"
        val beforeMinutes = beforeMinutesText.toIntOrNull()
        if (triggerChoice == AutomationTriggerChoice.BEFORE_EVENT && (beforeMinutes == null || beforeMinutes !in 1..720)) {
            return "课前提前分钟必须是 1..720"
        }
        if (triggerChoice == AutomationTriggerChoice.ON_EVENT && eventTypeText.isBlank()) return "请填写事件类型"
        if (triggerChoice == AutomationTriggerChoice.THRESHOLD) {
            if (thresholdValueText.toBigDecimalOrNull() == null) return "水电阈值必须是数字"
            val hysteresis = thresholdHysteresisText.toBigDecimalOrNull()
            if (hysteresis == null || hysteresis < BigDecimal.ZERO || hysteresis > BigDecimal("100")) {
                return "滞后带必须是 0..100 表计单位"
            }
            val cooldown = thresholdCooldownMinutesText.toIntOrNull()
            if (cooldown == null || cooldown !in 0..10080) return "冷却期必须是 0..10080 分钟"
        }
        if (campusIdText.isBlank() && (triggerChoice == AutomationTriggerChoice.GEOFENCE || onCampus)) return "请填写校区 ID"
        val commuteMinMinutes = commuteMinMinutesText.toIntOrNull()
        val commuteMaxMinutes = commuteMaxMinutesText.toIntOrNull()
        if (commuteTime && (commuteMinMinutes == null || commuteMinMinutes !in 0..1440)) return "通勤下限必须是 0..1440 分钟"
        if (commuteTime && (commuteMaxMinutes == null || commuteMaxMinutes !in 0..1440)) return "通勤上限必须是 0..1440 分钟"
        if (commuteTime && commuteMinMinutes != null && commuteMaxMinutes != null && commuteMinMinutes > commuteMaxMinutes) {
            return "通勤下限不能大于上限"
        }
        val freshnessHours = freshnessMaxAgeHoursText.toIntOrNull()
        if (dataFreshness && (freshnessHours == null || freshnessHours !in 1..168)) return "新鲜度上限必须是 1..168 小时"
        val weatherHorizon = weatherHorizonMinutesText.toIntOrNull()
        if (weatherPrecipitation && (weatherHorizon == null || weatherHorizon !in 30..720)) return "天气预测窗口必须是 30..720 分钟"
        val weatherProbability = weatherMinProbabilityText.toIntOrNull()
        if (weatherPrecipitation && (weatherProbability == null || weatherProbability !in 0..100)) return "降水概率阈值必须是 0..100"
        if (utilityBalance && utilityBalanceValueText.toBigDecimalOrNull() == null) return "水电余额阈值必须是数字"
        val dueMinutes = taskDueMinutesText.toIntOrNull()
        if (createTask && (dueMinutes == null || dueMinutes !in 0..10080)) return "待办截止必须是 0..10080 分钟"
        val eventStartMinutes = eventStartMinutesText.toIntOrNull()
        if (createEvent && (eventStartMinutes == null || eventStartMinutes !in 0..10080)) return "日程开始必须是 0..10080 分钟"
        val eventDurationMinutes = eventDurationMinutesText.toIntOrNull()
        if (createEvent && (eventDurationMinutes == null || eventDurationMinutes !in 1..1440)) return "日程时长必须是 1..1440 分钟"
        if (!notify && !createTask && !createEvent && !refreshHome && preservedActions.isEmpty()) return "至少选择一个动作"
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
            conditions = toConditions() + preservedConditions,
            actions = toActions() + preservedActions,
            templateId = templateId
        )
    }

    private fun toTrigger(): TriggerSpec = when (triggerChoice) {
        AutomationTriggerChoice.AT_TIME -> TriggerSpec.AtTime(atTime, days)
        AutomationTriggerChoice.BEFORE_EVENT -> TriggerSpec.BeforeEvent(
            kinds = setOf(TimelineKind.COURSE, TimelineKind.PRACTICE),
            offset = Duration.ofMinutes(beforeMinutesText.toLong())
        )
        AutomationTriggerChoice.ON_EVENT -> TriggerSpec.OnEvent(eventType = eventTypeText.trim(), filter = eventFilter)
        AutomationTriggerChoice.THRESHOLD -> TriggerSpec.Threshold(
            metric = MetricRef(
                if (thresholdWater) {
                    BuiltinAutomationTypes.METRIC_UTILITY_WATER_CENTIUNITS
                } else {
                    BuiltinAutomationTypes.METRIC_UTILITY_ELECTRICITY_CENTIUNITS
                }
            ),
            op = CompareOp.LESS_THAN,
            value = BigDecimal(thresholdValueText).movePointRight(2),
            hysteresis = BigDecimal(thresholdHysteresisText).movePointRight(2),
            cooldown = Duration.ofMinutes(thresholdCooldownMinutesText.toLong())
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
                    automationJsonObject("campusId" to JsonValue.Str(campusIdText.trim()))
                )
            )
        }
        if (focusPeriod) {
            add(
                ConditionSpec(
                    BuiltinAutomationTypes.CONDITION_FOCUS_PERIOD,
                    automationJsonObject("active" to JsonValue.Bool(true))
                )
            )
        }
        if (commuteTime) {
            val commuteConfig = linkedMapOf<String, JsonValue>(
                "minMinutes" to JsonValue.Num(commuteMinMinutesText.ifBlank { "0" }.toInt().toString()),
                "maxMinutes" to JsonValue.Num(commuteMaxMinutesText.toInt().toString())
            )
            commuteFromCampusText.trim().takeIf { it.isNotEmpty() }?.let { commuteConfig["fromCampus"] = JsonValue.Str(it) }
            campusIdText.trim().takeIf { it.isNotEmpty() }?.let { commuteConfig["toCampus"] = JsonValue.Str(it) }
            add(ConditionSpec(BuiltinAutomationTypes.CONDITION_COMMUTE_TIME, JsonValue.Obj(commuteConfig)))
        }
        if (dataFreshness) {
            add(
                ConditionSpec(
                    BuiltinAutomationTypes.CONDITION_DATA_FRESHNESS,
                    automationJsonObject(
                        "capability" to JsonValue.Str("TIMETABLE_READ"),
                        "maxAgeSeconds" to JsonValue.Num((freshnessMaxAgeHoursText.toInt() * 3600).toString())
                    )
                )
            )
        }
        if (weatherPrecipitation) {
            add(
                ConditionSpec(
                    BuiltinAutomationTypes.CONDITION_WEATHER_PRECIPITATION,
                    automationJsonObject(
                        "horizonMinutes" to JsonValue.Num(weatherHorizonMinutesText.toInt().toString()),
                        "minProbabilityPercent" to JsonValue.Num(weatherMinProbabilityText.toInt().toString()),
                        "minPrecipitationMillimeters" to JsonValue.Num("0.1"),
                        "termsAccepted" to JsonValue.Bool(weatherTermsAccepted)
                    )
                )
            )
        }
        if (utilityBalance) {
            add(
                ConditionSpec(
                    BuiltinAutomationTypes.CONDITION_UTILITY_BALANCE,
                    automationJsonObject(
                        "metric" to JsonValue.Str(
                            if (utilityBalanceWater) {
                                BuiltinAutomationTypes.METRIC_UTILITY_WATER_CENTIUNITS
                            } else {
                                BuiltinAutomationTypes.METRIC_UTILITY_ELECTRICITY_CENTIUNITS
                            }
                        ),
                        "op" to JsonValue.Str(CompareOp.LESS_THAN.name),
                        "value" to JsonValue.Num(BigDecimal(utilityBalanceValueText).movePointRight(2).toPlainString()),
                        "maxAgeSeconds" to JsonValue.Num("604800")
                    )
                )
            )
        }
        if (courseCancellation) add(ConditionSpec(BuiltinAutomationTypes.CONDITION_COURSE_CANCELLATION))
    }

    private fun toActions(): List<ActionSpec> = buildList {
        if (notify) {
            add(
                ActionSpec(
                    BuiltinAutomationTypes.ACTION_LOCAL_NOTIFICATION,
                    automationJsonObject(
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
                    automationJsonObject(
                        "kind" to JsonValue.Str("TASK_DUE"),
                        "title" to JsonValue.Str(taskTitle.ifBlank { name }),
                        "dueInMinutes" to JsonValue.Num(taskDueMinutesText.toInt().toString())
                    )
                )
            )
        }
        if (createEvent) {
            add(
                ActionSpec(
                    BuiltinAutomationTypes.ACTION_CREATE_PERSONAL_EVENT,
                    automationJsonObject(
                        "kind" to JsonValue.Str("PERSONAL"),
                        "title" to JsonValue.Str(eventTitle.ifBlank { name }),
                        "startInMinutes" to JsonValue.Num(eventStartMinutesText.toInt().toString()),
                        "durationMinutes" to JsonValue.Num(eventDurationMinutesText.toInt().toString())
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
    val status: AutomationScreenStatus = AutomationScreenStatus.LOADING,
    val statusDetail: String? = null,
    val statusRetryable: Boolean = false,
    val rules: List<AutomationRule> = emptyList(),
    val templates: List<AutomationTemplateUiItem> = emptyList(),
    val mode: AutomationScreenMode = AutomationScreenMode.LIST,
    val draft: AutomationEditorDraft = AutomationEditorDraft(),
    val selectedRuleId: String? = null,
    val runs: List<AutomationRun> = emptyList(),
    val message: String? = null,
    val error: String? = null
) {
    val effectiveStatus: AutomationScreenStatus
        get() = when {
            loading -> AutomationScreenStatus.LOADING
            status != AutomationScreenStatus.LOADING -> status
            rules.isEmpty() -> AutomationScreenStatus.EMPTY
            else -> AutomationScreenStatus.CONTENT
        }
}

/** §10.2 用户意图；纯 UI 只发 action，不触碰 Repository 或副作用。 */
sealed interface AutomationUiAction {
    data object Back : AutomationUiAction
    data object BackToList : AutomationUiAction
    data object NewRule : AutomationUiAction
    data object SaveDraft : AutomationUiAction
    data object DryRunDraft : AutomationUiAction
    data object ClearMessage : AutomationUiAction
    data object Retry : AutomationUiAction
    data class UseTemplate(val templateId: String) : AutomationUiAction
    data class EditRule(val rule: AutomationRule) : AutomationUiAction
    data class DeleteRule(val rule: AutomationRule) : AutomationUiAction
    data class OpenRuns(val rule: AutomationRule) : AutomationUiAction
    data class SetEnabled(val rule: AutomationRule, val enabled: Boolean) : AutomationUiAction
    data class UpdateDraft(val transform: (AutomationEditorDraft) -> AutomationEditorDraft) : AutomationUiAction
}

/** 首期 skeleton 尚无不中断导航 effect；类型先固定，后续 neon/交互卡不得再另造平行语义。 */
sealed interface AutomationUiEffect {
    data class ShowMessage(val message: String) : AutomationUiEffect
    data object NavigateBack : AutomationUiEffect
}

fun triggerSummary(rule: AutomationRule): String = when (val trigger = rule.trigger) {
    is TriggerSpec.AtTime -> "定时 " + trigger.local + " · " + trigger.days.size + " 天/周"
    is TriggerSpec.BeforeEvent -> "课前 " + trigger.offset.toMinutes() + " 分钟"
    is TriggerSpec.OnEvent -> "事件 " + trigger.eventType
    is TriggerSpec.Threshold -> {
        val amount = trigger.value.movePointLeft(2).stripTrailingZeros().toPlainString()
        val op = when (trigger.op) {
            CompareOp.LESS_THAN -> "低于"
            CompareOp.LESS_OR_EQUAL -> "不高于"
            CompareOp.EQUAL -> "等于"
            CompareOp.GREATER_OR_EQUAL -> "不低于"
            CompareOp.GREATER_THAN -> "高于"
            CompareOp.NOT_EQUAL -> "不等于"
        }
        when (trigger.metric.value) {
            BuiltinAutomationTypes.METRIC_UTILITY_ELECTRICITY_CENTIUNITS -> "电表剩余量" + op + " " + amount + " 度"
            BuiltinAutomationTypes.METRIC_UTILITY_WATER_CENTIUNITS -> "水表剩余量" + op + " " + amount + " 吨"
            else -> "水电阈值 " + trigger.op + " " + amount + "（表计单位）"
        }
    }
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

fun automationJsonObject(vararg fields: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(fields.toMap())
