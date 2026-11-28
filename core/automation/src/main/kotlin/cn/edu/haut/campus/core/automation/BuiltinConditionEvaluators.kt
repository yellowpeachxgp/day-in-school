package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineKind
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/** 粗定位在校状态；只保留校区级事实，不把精确坐标写入自动化运行记录。 */
data class CampusPresence(
    val campusId: CampusId?,
    val inside: Boolean,
    val accuracyMeters: Float?,
    val sampledAt: Instant
)

interface CampusPresencePort {
    suspend fun currentPresence(scope: SchoolAccountScope, now: Instant): CampusPresence?
}

interface FocusStatePort {
    /** null 表示端侧专注状态证据不足，条件必须为 UNKNOWN。 */
    suspend fun isFocusActive(scope: SchoolAccountScope, now: Instant): Boolean?
}

interface CommuteDurationPort {
    /** 返回分钟；null 表示缺少可信路线/时刻表证据。 */
    suspend fun estimateMinutes(
        scope: SchoolAccountScope,
        fromCampus: CampusId?,
        toCampus: CampusId?,
        at: Instant
    ): Int?
}

interface DataFreshnessPort {
    /** 某能力最近一次可信成功刷新时间；null 表示没有可用证据。 */
    suspend fun lastSuccessfulAt(scope: SchoolAccountScope, capability: CapabilityKind): Instant?
}

class HasClassTodayConditionEvaluator(
    private val zoneProvider: (RunContext) -> ZoneId
) : ConditionEvaluator {
    override val type: String = BuiltinAutomationTypes.CONDITION_HAS_CLASS_TODAY
    override val configSchema: JsonSchema = JsonSchema.parse(
        """{"type":"object","additionalProperties":false,"properties":{"includeExams":{"type":"boolean"}}}"""
    )

    override suspend fun evaluate(spec: ConditionSpec, ctx: RunContext): ConditionResult {
        val service = ctx.timelineService ?: return unknown(type, "缺少 TimelineService，无法判断今天是否有课")
        val zone = zoneProvider(ctx)
        val date = ctx.now.atZone(zone).toLocalDate()
        val window = TimeWindow(
            date.atStartOfDay(zone).toInstant(),
            date.plusDays(1).atStartOfDay(zone).toInstant()
        )
        val includeExams = spec.config.obj("includeExams")?.boolOrNull() == true
        val kinds = buildSet {
            add(TimelineKind.COURSE)
            add(TimelineKind.PRACTICE)
            if (includeExams) add(TimelineKind.EXAM)
        }
        val events = runCatching { service.observe(ctx.scope, window).first() }
            .getOrElse { return unknown(type, "Timeline 投影不可用，无法判断今天是否有课") }
        val matches = events.filter {
            it.kind in kinds && it.status != EventStatus.CANCELLED_TODAY && it.status != EventStatus.DONE
        }
        return ConditionResult(
            conditionType = type,
            passed = matches.isNotEmpty(),
            certainty = Certainty.CERTAIN,
            reason = if (matches.isEmpty()) "今天没有可计入的课程/实践" + if (includeExams) "/考试" else "" else null,
            evidence = listOf(
                Evidence(
                    source = "timeline",
                    detail = "当日计入事件数=" + matches.size,
                    at = ctx.now
                )
            )
        )
    }
}

class OnCampusConditionEvaluator(
    private val presencePort: CampusPresencePort?
) : ConditionEvaluator {
    override val type: String = BuiltinAutomationTypes.CONDITION_ON_CAMPUS
    override val configSchema: JsonSchema = JsonSchema.parse(
        """{"type":"object","additionalProperties":false,"properties":{"campusId":{"type":"string","minLength":1,"maxLength":128},"maxAccuracyMeters":{"type":"integer","minimum":1,"maximum":10000}}}"""
    )

    override suspend fun evaluate(spec: ConditionSpec, ctx: RunContext): ConditionResult {
        val port = presencePort ?: return unknown(type, "缺少粗定位在校状态端口")
        val presence = port.currentPresence(ctx.scope, ctx.now)
            ?: return unknown(type, "缺少可用的粗定位在校状态")
        val maxAccuracy = spec.config.obj("maxAccuracyMeters")?.intOrNull() ?: 1000
        val accuracy = presence.accuracyMeters
        if (accuracy == null || accuracy > maxAccuracy.toFloat()) {
            return unknown(type, "粗定位精度不足，不能得出在校确定性结论")
        }
        val expectedCampus = spec.config.str("campusId")?.takeIf { it.isNotBlank() }?.let(::CampusId)
        val campusMatches = expectedCampus == null || presence.campusId == expectedCampus
        val passed = presence.inside && campusMatches
        return ConditionResult(
            conditionType = type,
            passed = passed,
            certainty = Certainty.CERTAIN,
            reason = if (passed) null else "当前不满足在校条件",
            evidence = listOf(
                Evidence(
                    source = "coarse-geofence",
                    detail = "inside=" + presence.inside + ",campus=" + (presence.campusId?.value ?: "unknown"),
                    at = presence.sampledAt
                )
            )
        )
    }
}

class FocusPeriodConditionEvaluator(
    private val focusStatePort: FocusStatePort?
) : ConditionEvaluator {
    override val type: String = BuiltinAutomationTypes.CONDITION_FOCUS_PERIOD
    override val configSchema: JsonSchema = JsonSchema.parse(
        """{"type":"object","additionalProperties":false,"properties":{"active":{"type":"boolean"}}}"""
    )

    override suspend fun evaluate(spec: ConditionSpec, ctx: RunContext): ConditionResult {
        val port = focusStatePort ?: return unknown(type, "缺少专注状态端口")
        val expected = spec.config.obj("active")?.boolOrNull() ?: true
        val actual = port.isFocusActive(ctx.scope, ctx.now)
            ?: return unknown(type, "专注状态证据不足")
        return ConditionResult(
            conditionType = type,
            passed = actual == expected,
            certainty = Certainty.CERTAIN,
            reason = if (actual == expected) null else "当前专注状态不满足条件",
            evidence = listOf(Evidence(source = "focus-state", detail = "active=" + actual, at = ctx.now))
        )
    }
}

class CommuteTimeConditionEvaluator(
    private val commuteDurationPort: CommuteDurationPort?
) : ConditionEvaluator {
    override val type: String = BuiltinAutomationTypes.CONDITION_COMMUTE_TIME
    override val configSchema: JsonSchema = JsonSchema.parse(
        """{"type":"object","additionalProperties":false,"required":["maxMinutes"],"properties":{"minMinutes":{"type":"integer","minimum":0,"maximum":1440},"maxMinutes":{"type":"integer","minimum":0,"maximum":1440},"fromCampus":{"type":"string","minLength":1,"maxLength":128},"toCampus":{"type":"string","minLength":1,"maxLength":128}}}"""
    )

    override suspend fun evaluate(spec: ConditionSpec, ctx: RunContext): ConditionResult {
        val port = commuteDurationPort ?: return unknown(type, "缺少通勤时长端口")
        val maxMinutes = spec.config.obj("maxMinutes")?.intOrNull()
            ?: return unknown(type, "缺少 maxMinutes 配置")
        val minMinutes = spec.config.obj("minMinutes")?.intOrNull() ?: 0
        if (minMinutes > maxMinutes) return unknown(type, "minMinutes 不能大于 maxMinutes")
        val from = spec.config.str("fromCampus")?.takeIf { it.isNotBlank() }?.let(::CampusId)
        val to = spec.config.str("toCampus")?.takeIf { it.isNotBlank() }?.let(::CampusId)
        val estimate = port.estimateMinutes(ctx.scope, from, to, ctx.now)
            ?: return unknown(type, "通勤时长证据不足")
        val passed = estimate in minMinutes..maxMinutes
        return ConditionResult(
            conditionType = type,
            passed = passed,
            certainty = Certainty.CERTAIN,
            reason = if (passed) null else "预计通勤 " + estimate + " 分钟，不在 " + minMinutes + ".." + maxMinutes + " 分钟范围内",
            evidence = listOf(Evidence(source = "commute-estimate", detail = "minutes=" + estimate + ",min=" + minMinutes + ",max=" + maxMinutes, at = ctx.now))
        )
    }
}

class DataFreshnessConditionEvaluator(
    private val freshnessPort: DataFreshnessPort?
) : ConditionEvaluator {
    override val type: String = BuiltinAutomationTypes.CONDITION_DATA_FRESHNESS
    override val configSchema: JsonSchema = JsonSchema.parse(
        """{"type":"object","additionalProperties":false,"required":["capability","maxAgeSeconds"],"properties":{"capability":{"type":"string","minLength":1,"maxLength":64},"maxAgeSeconds":{"type":"integer","minimum":1,"maximum":604800}}}"""
    )

    override suspend fun evaluate(spec: ConditionSpec, ctx: RunContext): ConditionResult {
        val port = freshnessPort ?: return unknown(type, "缺少数据新鲜度端口")
        val capabilityName = spec.config.str("capability") ?: return unknown(type, "缺少 capability 配置")
        val capability = runCatching { CapabilityKind.valueOf(capabilityName) }.getOrElse {
            return unknown(type, "capability 配置不受支持")
        }
        val maxAgeSeconds = spec.config.obj("maxAgeSeconds")?.intOrNull()
            ?: return unknown(type, "缺少 maxAgeSeconds 配置")
        val lastSuccess = port.lastSuccessfulAt(ctx.scope, capability)
            ?: return unknown(type, capability.name + " 没有可信刷新时间")
        val age = Duration.between(lastSuccess, ctx.now).seconds.coerceAtLeast(0L)
        val passed = age <= maxAgeSeconds.toLong()
        return ConditionResult(
            conditionType = type,
            passed = passed,
            certainty = Certainty.CERTAIN,
            reason = if (passed) null else capability.name + " 数据已陈旧（" + age + " 秒）",
            evidence = listOf(Evidence(source = "data-freshness", detail = "ageSeconds=" + age, at = lastSuccess))
        )
    }
}

/** 内置条件集合。端口缺失时保持注册但返回 UNKNOWN，绝不用缺失证据得出确定性结论。 */
class BuiltinConditionEvaluators(
    zoneProvider: (RunContext) -> ZoneId,
    campusPresencePort: CampusPresencePort?,
    focusStatePort: FocusStatePort?,
    commuteDurationPort: CommuteDurationPort?,
    dataFreshnessPort: DataFreshnessPort?,
    weatherProvider: WeatherProvider? = null,
    weatherLocationPort: WeatherLocationPort? = null,
    utilityBalanceReadPort: UtilityBalanceReadPort? = null,
    courseCancellationStatePort: CourseCancellationStatePort? = null
) {
    private val evaluators = listOf(
        HasClassTodayConditionEvaluator(zoneProvider),
        OnCampusConditionEvaluator(campusPresencePort),
        FocusPeriodConditionEvaluator(focusStatePort),
        CommuteTimeConditionEvaluator(commuteDurationPort),
        DataFreshnessConditionEvaluator(dataFreshnessPort),
        WeatherPrecipitationConditionEvaluator(weatherProvider, weatherLocationPort),
        DayOfWeekConditionEvaluator(zoneProvider),
        UtilityBalanceConditionEvaluator(utilityBalanceReadPort),
        CourseCancellationConditionEvaluator(courseCancellationStatePort)
    )

    fun all(): List<ConditionEvaluator> = evaluators
}

private fun unknown(type: String, reason: String): ConditionResult = ConditionResult(
    conditionType = type,
    passed = false,
    certainty = Certainty.UNKNOWN,
    reason = reason
)
