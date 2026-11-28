package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.timeline.TimelineKind
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

/** 一个可实例化的内置规则模板。模板只生成默认关闭的本地规则；用户必须在编辑器中显式启用。 */
class AutomationRuleTemplate(
    val id: String,
    val title: String,
    val description: String,
    val requiredEvidence: List<String>,
    private val factory: (SchoolAccountScope) -> AutomationRule
) {
    init {
        requireAutomationType(id, "template id")
        require(title.isNotBlank() && title.length <= 120 && title.none { it.isISOControl() }) { "模板标题为空、过长或含控制字符" }
        require(description.isNotBlank() && description.length <= 600 && description.none { it.isISOControl() }) {
            "模板说明为空、过长或含控制字符"
        }
        requiredEvidence.forEach {
            require(it.isNotBlank() && it.length <= 240 && it.none { ch -> ch.isISOControl() }) { "模板证据要求为空、过长或含控制字符" }
        }
    }

    fun instantiate(scope: SchoolAccountScope): AutomationRule {
        val rule = factory(scope)
        require(rule.templateId == id) { "模板生成的 templateId 不一致" }
        require(!rule.enabled) { "模板生成的规则必须默认关闭" }
        require(rule.scope == scope) { "模板生成的规则 scope 不一致" }
        return rule
    }
}

/** MASTER_PLAN §6.3 / W2-03 的四个首期内置模板。 */
object BuiltinAutomationTemplates {
    const val TEMPLATE_EARLY_RAIN_UMBRELLA: String = "core.template.early-rain-umbrella"
    const val TEMPLATE_CROSS_CAMPUS_COMMUTE: String = "core.template.cross-campus-commute"
    const val TEMPLATE_FRIDAY_UTILITY_LOW_BALANCE: String = "core.template.friday-utility-low-balance"
    const val TEMPLATE_COURSE_CANCELLED_STUDY: String = "core.template.course-cancelled-study"

    val all: List<AutomationRuleTemplate> = listOf(
        AutomationRuleTemplate(
            id = TEMPLATE_EARLY_RAIN_UMBRELLA,
            title = "早八带伞",
            description = "工作日早晨检查今天是否有课，并结合校区粗坐标天气预报判断未来 3 小时是否可能降水。天气条款未确认、位置缺失、网络失败、数据过期或降水字段缺失时一律不触发。",
            requiredEvidence = listOf("课表 Timeline", "校区参考坐标", "MET Norway 天气快照与署名"),
            factory = ::earlyRainUmbrella
        ),
        AutomationRuleTemplate(
            id = TEMPLATE_CROSS_CAMPUS_COMMUTE,
            title = "跨校区通勤提醒",
            description = "课前 60 分钟检查是否存在非零且不超过 120 分钟的通勤证据。当前没有可信跨校区路线证据时条件为 UNKNOWN，不会提醒。",
            requiredEvidence = listOf("课程/实践 Timeline", "通勤时长估计"),
            factory = ::crossCampusCommute
        ),
        AutomationRuleTemplate(
            id = TEMPLATE_FRIDAY_UTILITY_LOW_BALANCE,
            title = "水电低余额周五提醒",
            description = "周五傍晚检查最新电表余额是否低于 20 度（默认模板，可后续扩展为水表）。读数缺失或超过 7 天不新鲜时不触发。",
            requiredEvidence = listOf("本地水电采样", "水电数据新鲜度"),
            factory = ::fridayUtilityLowBalance
        ),
        AutomationRuleTemplate(
            id = TEMPLATE_COURSE_CANCELLED_STUDY,
            title = "临时停课推荐自习",
            description = "监听课程变更事件，只有差异中明确出现停课语义才创建一段本地自习日程并通知。普通调课、事件缺失或证据不足不会触发。",
            requiredEvidence = listOf("CourseChanged 领域事件", "明确停课字段差异"),
            factory = ::courseCancelledStudy
        )
    )

    fun byId(id: String): AutomationRuleTemplate? = all.firstOrNull { it.id == id }

    private fun earlyRainUmbrella(scope: SchoolAccountScope): AutomationRule = AutomationRule(
        id = "rule-template-early-rain-umbrella",
        scope = scope,
        name = "早八带伞",
        enabled = false,
        trigger = TriggerSpec.AtTime(
            local = LocalTime.of(7, 20),
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        ),
        conditions = listOf(
            ConditionSpec(BuiltinAutomationTypes.CONDITION_HAS_CLASS_TODAY),
            ConditionSpec(
                BuiltinAutomationTypes.CONDITION_WEATHER_PRECIPITATION,
                jsonObject(
                    "horizonMinutes" to JsonValue.Num("180"),
                    "minProbabilityPercent" to JsonValue.Num("35"),
                    "minPrecipitationMillimeters" to JsonValue.Num("0.1"),
                    "termsAccepted" to JsonValue.Bool(false)
                )
            )
        ),
        actions = listOf(
            notificationAction(
                title = "早八天气提醒",
                body = "未来 3 小时可能有降水，出门前记得带伞。天气来源与署名可在运行记录中查看。"
            ),
            ActionSpec(BuiltinAutomationTypes.ACTION_REFRESH_HOME)
        ),
        templateId = TEMPLATE_EARLY_RAIN_UMBRELLA
    )

    private fun crossCampusCommute(scope: SchoolAccountScope): AutomationRule {
        val commuteConfig = linkedMapOf<String, JsonValue>(
            "minMinutes" to JsonValue.Num("1"),
            "maxMinutes" to JsonValue.Num("120")
        )
        scope.campusId?.let { commuteConfig["toCampus"] = JsonValue.Str(it.value) }
        return AutomationRule(
            id = "rule-template-cross-campus-commute",
            scope = scope,
            name = "跨校区通勤提醒",
            enabled = false,
            trigger = TriggerSpec.BeforeEvent(
                kinds = setOf(TimelineKind.COURSE, TimelineKind.PRACTICE),
                offset = Duration.ofMinutes(60)
            ),
            conditions = listOf(
                ConditionSpec(
                    BuiltinAutomationTypes.CONDITION_COMMUTE_TIME,
                    JsonValue.Obj(commuteConfig)
                )
            ),
            actions = listOf(
                notificationAction(
                    title = "跨校区通勤提醒",
                    body = "下一节课可能需要跨校区通勤，请预留路上时间。"
                ),
                ActionSpec(BuiltinAutomationTypes.ACTION_REFRESH_HOME)
            ),
            templateId = TEMPLATE_CROSS_CAMPUS_COMMUTE
        )
    }

    private fun fridayUtilityLowBalance(scope: SchoolAccountScope): AutomationRule = AutomationRule(
        id = "rule-template-friday-utility-low-balance",
        scope = scope,
        name = "水电低余额周五提醒",
        enabled = false,
        trigger = TriggerSpec.AtTime(
            local = LocalTime.of(17, 30),
            days = setOf(DayOfWeek.FRIDAY)
        ),
        conditions = listOf(
            ConditionSpec(
                BuiltinAutomationTypes.CONDITION_DAY_OF_WEEK,
                jsonObject("days" to JsonValue.Arr(listOf(JsonValue.Str("FRIDAY"))))
            ),
            ConditionSpec(
                BuiltinAutomationTypes.CONDITION_UTILITY_BALANCE,
                jsonObject(
                    "metric" to JsonValue.Str(BuiltinAutomationTypes.METRIC_UTILITY_ELECTRICITY_CENTIUNITS),
                    "op" to JsonValue.Str(CompareOp.LESS_THAN.name),
                    "value" to JsonValue.Num(BigDecimal("2000").toPlainString()),
                    "maxAgeSeconds" to JsonValue.Num("604800")
                )
            )
        ),
        actions = listOf(
            notificationAction(
                title = "水电余额提醒",
                body = "电表余额低于 20 度。今天是周五，记得及时检查水电余额。"
            ),
            ActionSpec(BuiltinAutomationTypes.ACTION_REFRESH_HOME)
        ),
        templateId = TEMPLATE_FRIDAY_UTILITY_LOW_BALANCE
    )

    private fun courseCancelledStudy(scope: SchoolAccountScope): AutomationRule = AutomationRule(
        id = "rule-template-course-cancelled-study",
        scope = scope,
        name = "临时停课推荐自习",
        enabled = false,
        trigger = TriggerSpec.OnEvent(
            eventType = "course.changed",
            filter = jsonObject("change" to JsonValue.Str("UPDATED"))
        ),
        conditions = listOf(
            ConditionSpec(BuiltinAutomationTypes.CONDITION_COURSE_CANCELLATION)
        ),
        actions = listOf(
            notificationAction(
                title = "临时停课",
                body = "检测到课程停课，已为你安排一段可编辑的自习日程。"
            ),
            ActionSpec(
                BuiltinAutomationTypes.ACTION_CREATE_PERSONAL_EVENT,
                jsonObject(
                    "kind" to JsonValue.Str("PERSONAL"),
                    "title" to JsonValue.Str("自习（临时停课空档）"),
                    "startInMinutes" to JsonValue.Num("15"),
                    "durationMinutes" to JsonValue.Num("90")
                )
            )
        ),
        templateId = TEMPLATE_COURSE_CANCELLED_STUDY
    )

    private fun notificationAction(title: String, body: String): ActionSpec = ActionSpec(
        BuiltinAutomationTypes.ACTION_LOCAL_NOTIFICATION,
        jsonObject(
            "title" to JsonValue.Str(title),
            "body" to JsonValue.Str(body)
        )
    )

    private fun jsonObject(vararg fields: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(fields.toMap())
}
