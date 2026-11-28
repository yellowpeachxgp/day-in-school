package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.json.JsonValue
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** 最新水电读数。metric 值复用 TriggerSpec.Threshold 的 centiunits 口径，绝不把表计单位伪造成货币金额。 */
data class UtilityBalanceReading(
    val meterId: String,
    val balanceCents: Long,
    val sampledAt: Instant,
    val provenance: DataProvenance
) {
    init {
        requireAutomationToken(meterId, "meterId", 256)
        require(balanceCents >= 0L) { "水电读数不能为负" }
        require(provenance.fetchedAt != null) { "水电读数缺少来源采样时间，必须 fail-closed" }
        require(provenance.fetchedAt == sampledAt) { "水电读数 sampledAt 必须与 provenance.fetchedAt 一致" }
    }
}

interface UtilityBalanceReadPort {
    suspend fun latestSamples(scope: SchoolAccountScope): List<UtilityBalanceReading>
}

/** 天气降水条件。任何 Provider、位置、条款、过期或字段证据缺口都必须是 UNKNOWN，不得误触发。 */
class WeatherPrecipitationConditionEvaluator(
    private val weatherProvider: WeatherProvider?,
    private val weatherLocationPort: WeatherLocationPort?
) : ConditionEvaluator {
    override val type: String = BuiltinAutomationTypes.CONDITION_WEATHER_PRECIPITATION
    override val configSchema: JsonSchema = JsonSchema.parse(
        """{"type":"object","additionalProperties":false,"properties":{"horizonMinutes":{"type":"integer","minimum":30,"maximum":720},"minProbabilityPercent":{"type":"integer","minimum":0,"maximum":100},"minPrecipitationMillimeters":{"type":"number","minimum":0,"maximum":100},"termsAccepted":{"type":"boolean"},"campusId":{"type":"string","minLength":1,"maxLength":128}}}"""
    )

    override suspend fun evaluate(spec: ConditionSpec, ctx: RunContext): ConditionResult {
        val provider = weatherProvider ?: return unknown(type, "缺少 WeatherProvider，无法判断降水")
        val locationPort = weatherLocationPort ?: return unknown(type, "缺少校区参考坐标端口，无法判断降水")
        val termsAccepted = spec.config.obj("termsAccepted")?.boolOrNull() == true
        if (!termsAccepted) return unknown(type, "尚未在本规则中确认天气 Provider 条款与署名要求")
        val horizonMinutes = spec.config.obj("horizonMinutes")?.intOrNull() ?: DEFAULT_HORIZON_MINUTES
        val horizon = Duration.ofMinutes(horizonMinutes.toLong())
        val campusId = spec.config.str("campusId")?.takeIf { it.isNotBlank() }?.let(::CampusId) ?: ctx.scope.campusId
        val location = try {
            locationPort.referencePoint(ctx.scope, campusId)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException || error is Error) throw error
            return unknown(type, "校区参考坐标读取失败")
        } ?: return unknown(type, "缺少校区参考坐标，天气条件不猜测位置")

        val result = try {
            provider.forecast(
                WeatherForecastRequest(
                    scope = ctx.scope,
                    location = location,
                    at = ctx.now,
                    horizon = horizon,
                    termsAccepted = termsAccepted,
                    campusId = campusId
                )
            )
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException || error is Error) throw error
            return unknown(type, "天气 Provider 调用异常（" + (error::class.simpleName ?: "Throwable") + "）")
        }
        val snapshot = when (result) {
            is WeatherProviderResult.Success -> result.snapshot
            is WeatherProviderResult.Unavailable -> return ConditionResult(
                conditionType = type,
                passed = false,
                certainty = Certainty.UNKNOWN,
                reason = "天气不可用：" + result.detail,
                evidence = listOf(
                    Evidence(
                        source = "weather-provider",
                        detail = "provider=" + result.providerId + ",reason=" + result.reason.name,
                        at = ctx.now
                    )
                )
            )
        }
        if (snapshot.isExpiredAt(ctx.now)) {
            return unknownWithAttribution(
                snapshot = snapshot,
                reason = "天气数据已超过本地可信缓存时间，不能触发确定性结论",
                at = ctx.now
            )
        }
        val windowEnd = ctx.now.plus(horizon)
        val relevant = snapshot.periods.filter { it.end.isAfter(ctx.now) && it.start.isBefore(windowEnd) }
        if (relevant.isEmpty()) {
            return unknownWithAttribution(snapshot, "天气快照没有覆盖未来 " + horizonMinutes + " 分钟", ctx.now)
        }
        val missingPrecipitationEvidence = relevant.any {
            it.precipitationProbabilityPercent == null && it.precipitationAmountMillimeters == null && it.weatherSymbol == null
        }
        if (missingPrecipitationEvidence) {
            return unknownWithAttribution(snapshot, "天气快照缺少降水概率/降水量/天气现象字段", ctx.now)
        }

        val probabilityThreshold = spec.config.obj("minProbabilityPercent")?.intOrNull() ?: DEFAULT_PROBABILITY_PERCENT
        val amountThreshold = spec.config.obj("minPrecipitationMillimeters")?.decimalOrNull() ?: BigDecimal.ZERO
        val matched = relevant.filter { period ->
            val probabilityMatches = period.precipitationProbabilityPercent?.let { it >= probabilityThreshold } == true
            val amountMatches = period.precipitationAmountMillimeters?.let { amount ->
                if (amountThreshold > BigDecimal.ZERO) amount >= amountThreshold else amount > BigDecimal.ZERO
            } == true
            val symbolMatches = period.weatherSymbol?.lowercase()?.let { symbol ->
                PRECIPITATION_SYMBOL_MARKERS.any { marker -> symbol.contains(marker) }
            } == true
            probabilityMatches || amountMatches || symbolMatches
        }
        val passed = matched.isNotEmpty()
        val baseEvidence = weatherEvidence(snapshot, ctx.now)
        return ConditionResult(
            conditionType = type,
            passed = passed,
            certainty = Certainty.CERTAIN,
            reason = if (passed) null else "未来 " + horizonMinutes + " 分钟降水证据未达到阈值",
            evidence = baseEvidence + Evidence(
                source = "weather-precipitation",
                detail = "periods=" + relevant.size + ",matched=" + matched.size +
                    ",probabilityThreshold=" + probabilityThreshold +
                    ",amountThresholdMm=" + amountThreshold.stripTrailingZeros().toPlainString(),
                at = ctx.now
            )
        )
    }

    private fun unknownWithAttribution(
        snapshot: WeatherSnapshot,
        reason: String,
        at: Instant
    ): ConditionResult = ConditionResult(
        conditionType = type,
        passed = false,
        certainty = Certainty.UNKNOWN,
        reason = reason,
        evidence = weatherEvidence(snapshot, at)
    )

    private fun weatherEvidence(snapshot: WeatherSnapshot, at: Instant): List<Evidence> = listOf(
        Evidence(
            source = "weather-provider",
            detail = "provider=" + snapshot.providerId + ",fetchedAt=" + snapshot.fetchedAt +
                ",cacheExpiresAt=" + snapshot.cacheExpiresAt + ",validUntil=" + snapshot.validUntil,
            at = at
        ),
        Evidence(
            source = "weather-attribution",
            detail = (snapshot.attributionLines + snapshot.licenseUrl).joinToString(" · ").take(500),
            at = snapshot.fetchedAt
        )
    )

    private companion object {
        const val DEFAULT_HORIZON_MINUTES: Int = 180
        const val DEFAULT_PROBABILITY_PERCENT: Int = 35
        val PRECIPITATION_SYMBOL_MARKERS: List<String> = listOf(
            "rain", "drizzle", "shower", "sleet", "snow", "thunder"
        )
    }
}

/** 周五等星期条件使用触发逻辑时间，补处理延迟不会把周五提醒误判到周六。 */
class DayOfWeekConditionEvaluator(
    private val zoneProvider: (RunContext) -> ZoneId
) : ConditionEvaluator {
    override val type: String = BuiltinAutomationTypes.CONDITION_DAY_OF_WEEK
    override val configSchema: JsonSchema = JsonSchema.parse(
        """{"type":"object","additionalProperties":false,"required":["days"],"properties":{"days":{"type":"array","minItems":1,"maxItems":7,"items":{"type":"string","enum":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"]}}}}"""
    )

    override suspend fun evaluate(spec: ConditionSpec, ctx: RunContext): ConditionResult {
        val names = spec.config.arr("days")?.mapNotNull { it.stringOrNull() }?.toSet().orEmpty()
        if (names.isEmpty()) return unknown(type, "缺少 days 配置")
        val days = names.mapNotNull { name -> runCatching { DayOfWeek.valueOf(name) }.getOrNull() }.toSet()
        if (days.size != names.size) return unknown(type, "days 配置包含不支持的星期")
        val actual = ctx.firedAt.atZone(zoneProvider(ctx)).dayOfWeek
        val passed = actual in days
        return ConditionResult(
            conditionType = type,
            passed = passed,
            certainty = Certainty.CERTAIN,
            reason = if (passed) null else "触发日不是模板要求的星期",
            evidence = listOf(Evidence(source = "local-clock", detail = "dayOfWeek=" + actual.name, at = ctx.firedAt))
        )
    }
}

/** 水电低余额状态条件；只读最新采样，不触发任何外部充值/写操作。 */
class UtilityBalanceConditionEvaluator(
    private val utilityBalanceReadPort: UtilityBalanceReadPort?
) : ConditionEvaluator {
    override val type: String = BuiltinAutomationTypes.CONDITION_UTILITY_BALANCE
    override val configSchema: JsonSchema = JsonSchema.parse(
        """{"type":"object","additionalProperties":false,"required":["metric","op","value","maxAgeSeconds"],"properties":{"metric":{"type":"string","enum":["core.utility.electricity-centiunits","core.utility.water-centiunits"]},"op":{"type":"string","enum":["LESS_THAN","LESS_OR_EQUAL","EQUAL","GREATER_OR_EQUAL","GREATER_THAN","NOT_EQUAL"]},"value":{"type":"number","minimum":0,"maximum":100000000},"maxAgeSeconds":{"type":"integer","minimum":60,"maximum":1209600},"meterId":{"type":"string","minLength":1,"maxLength":256}}}"""
    )

    override suspend fun evaluate(spec: ConditionSpec, ctx: RunContext): ConditionResult {
        val port = utilityBalanceReadPort ?: return unknown(type, "缺少水电读数端口")
        val metric = spec.config.str("metric") ?: return unknown(type, "缺少 metric 配置")
        val op = runCatching { CompareOp.valueOf(spec.config.str("op").orEmpty()) }.getOrElse {
            return unknown(type, "op 配置不受支持")
        }
        val threshold = spec.config.obj("value")?.decimalOrNull() ?: return unknown(type, "缺少 value 配置")
        val maxAgeSeconds = spec.config.obj("maxAgeSeconds")?.intOrNull() ?: return unknown(type, "缺少 maxAgeSeconds 配置")
        val meterFilter = spec.config.str("meterId")?.takeIf { it.isNotBlank() }
        val meterPrefix = when (metric) {
            BuiltinAutomationTypes.METRIC_UTILITY_ELECTRICITY_CENTIUNITS -> "online/electricity/"
            BuiltinAutomationTypes.METRIC_UTILITY_WATER_CENTIUNITS -> "online/water/"
            else -> return unknown(type, "metric 配置不受支持")
        }
        val samples = try {
            port.latestSamples(ctx.scope)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException || error is Error) throw error
            return unknown(type, "水电读数读取失败")
        }.filter { it.meterId.startsWith(meterPrefix) && (meterFilter == null || it.meterId == meterFilter) }
        if (samples.isEmpty()) return unknown(type, "没有匹配的水电读数证据")
        val latest = samples.maxByOrNull { it.sampledAt } ?: return unknown(type, "没有匹配的水电读数证据")
        if (latest.sampledAt.isAfter(ctx.now.plusSeconds(300))) {
            return unknown(type, "水电读数时间戳明显超前于本机时间，证据不一致")
        }
        val age = Duration.between(latest.sampledAt, ctx.now).seconds.coerceAtLeast(0L)
        if (age > maxAgeSeconds) {
            return unknown(type, "水电读数已陈旧（" + age + " 秒），不能触发确定性结论")
        }
        val actual = BigDecimal.valueOf(latest.balanceCents)
        val passed = compareDecimal(actual, op, threshold)
        val meterDigest = AutomationKeys.sha256Hex(latest.meterId).substring(0, 12)
        return ConditionResult(
            conditionType = type,
            passed = passed,
            certainty = Certainty.CERTAIN,
            reason = if (passed) null else "最新水电读数不满足阈值条件",
            evidence = listOf(
                Evidence(
                    source = "utility-balance",
                    detail = "meterDigest=" + meterDigest + ",ageSeconds=" + age + ",metric=" + metric,
                    at = latest.sampledAt
                )
            )
        )
    }

    private fun compareDecimal(actual: BigDecimal, op: CompareOp, expected: BigDecimal): Boolean {
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

/**
 * 当前课程停课状态读取端口。触发事件只是历史事实；执行动作前必须用该端口复核当前行状态。
 */
interface CourseCancellationStatePort {
    /**
     * overrideIds 非空时只核对触发 diff 归因到的行；为空表示事件只携带课程级 status，需按课程聚合
     * 是否存在任何 active canceled 行判断。返回 null 表示当前状态证据不足，条件必须 UNKNOWN。
     */
    suspend fun hasActiveCancellation(
        scope: SchoolAccountScope,
        courseRef: cn.edu.haut.campus.core.model.EntityRef,
        overrideIds: Set<String>
    ): Boolean?
}

/**
 * 临时停课事实条件。只有 CourseChanged.diff 的最终行状态明确为 canceled=true 才通过；
 * 且执行前必须复核当前课程聚合，触发后已经复课/删除的 queued event 不得产生陈旧动作。
 */
class CourseCancellationConditionEvaluator(
    private val currentStatePort: CourseCancellationStatePort? = null
) : ConditionEvaluator {
    override val type: String = BuiltinAutomationTypes.CONDITION_COURSE_CANCELLATION
    override val configSchema: JsonSchema = JsonSchema.EMPTY_OBJECT

    override suspend fun evaluate(spec: ConditionSpec, ctx: RunContext): ConditionResult {
        val event = ctx.triggerEvent as? DomainEvent.CourseChanged
            ?: return unknown(type, "触发上下文缺少 CourseChanged 事件，无法确认临时停课")
        val assessment = assessCourseCancellation(event)
        val evidence = mutableListOf(
            Evidence(
                source = "course-change-diff",
                detail = "event=course.changed,positive=" + assessment.positiveSignals +
                    ",negative=" + assessment.negativeSignals + ",unsupported=" + assessment.unsupportedSignals,
                at = event.occurredAt
            )
        )
        if (assessment.unsupportedSignals > 0) {
            return ConditionResult(
                conditionType = type,
                passed = false,
                certainty = Certainty.UNKNOWN,
                reason = "课程状态字段包含未识别的枚举或类型，不能确认是否为停课",
                evidence = evidence
            )
        }
        if (assessment.positiveSignals == 0) {
            return ConditionResult(
                conditionType = type,
                passed = false,
                certainty = Certainty.CERTAIN,
                reason = "课程变更差异的最终状态中没有明确停课证据",
                evidence = evidence
            )
        }

        val port = currentStatePort
            ?: return unknown(type, "缺少当前课程停课状态端口，不能执行历史停课事件")
        val currentActive = try {
            port.hasActiveCancellation(ctx.scope, event.courseRef, assessment.positiveOverrideIds)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException || error is Error) throw error
            return unknown(type, "当前课程停课状态读取失败")
        } ?: return unknown(type, "当前课程停课状态证据不足")
        evidence += Evidence(
            source = "course-current-state",
            detail = "activeCancellation=" + currentActive + ",attributedRows=" + assessment.positiveOverrideIds.size,
            at = ctx.now
        )
        return ConditionResult(
            conditionType = type,
            passed = currentActive,
            certainty = Certainty.CERTAIN,
            reason = if (currentActive) null else "触发后当前已无有效停课，跳过陈旧 queued cancellation",
            evidence = evidence
        )
    }

    private data class CancellationAssessment(
        val positiveSignals: Int,
        val negativeSignals: Int,
        val unsupportedSignals: Int,
        val positiveOverrideIds: Set<String>
    )

    /**
     * 结构化判定：字段末段与精确枚举决定每行最终状态。删除旧 false 行并新增 true 行不是“混合未知”，
     * 而是最终存在 active cancellation；相反删除 true 行/写入 false 都不得触发。
     */
    private fun assessCourseCancellation(event: DomainEvent.CourseChanged): CancellationAssessment {
        var positive = 0
        var negative = 0
        var unsupported = 0
        val positiveOverrideIds = linkedSetOf<String>()
        event.diff.changes.forEach { change ->
            val segments = jsonPointerSegments(change.path)
            val leaf = segments.lastOrNull()?.lowercase() ?: return@forEach
            val booleanCancellationField = leaf in BOOLEAN_CANCELLATION_FIELD_NAMES
            val statusField = leaf in STATUS_FIELD_NAMES
            if (!booleanCancellationField && !statusField) return@forEach
            val overrideId = overrideIdFromStableProjectionSegments(segments)

            if (booleanCancellationField) {
                when (val after = change.after) {
                    is JsonValue.Bool -> {
                        if (after.value) {
                            positive++
                            overrideId?.let(positiveOverrideIds::add)
                        } else {
                            negative++
                        }
                    }
                    null -> {
                        val beforeWasCancelled = change.before as? JsonValue.Bool
                        if (change.kind == cn.edu.haut.campus.core.model.FieldChangeKind.REMOVED && beforeWasCancelled != null) {
                            negative++
                        } else {
                            unsupported++
                        }
                    }
                    else -> unsupported++
                }
                return@forEach
            }

            when (val after = change.after) {
                is JsonValue.Str -> when (normalizeCourseStatusToken(after.value)) {
                    in POSITIVE_COURSE_STATUS_TOKENS -> {
                        positive++
                        overrideId?.let(positiveOverrideIds::add)
                    }
                    in NEGATIVE_COURSE_STATUS_TOKENS -> negative++
                    else -> unsupported++
                }
                else -> unsupported++
            }
        }
        return CancellationAssessment(positive, negative, unsupported, positiveOverrideIds)
    }

    private fun jsonPointerSegments(path: String): List<String> {
        if (!path.startsWith('/')) return emptyList()
        return path.substring(1).split('/').map { segment ->
            segment.replace("~1", "/").replace("~0", "~")
        }
    }

    private fun overrideIdFromStableProjectionSegments(segments: List<String>): String? {
        if (segments.size != 4) return null
        if (!segments[0].equals("occurrenceOverrides", ignoreCase = true)) return null
        if (!segments[1].equals("byId", ignoreCase = true)) return null
        return segments[2].takeIf { it.isNotBlank() }
    }

    private fun normalizeCourseStatusToken(value: String): String =
        value.trim().uppercase().replace('-', '_').replace(' ', '_')

    private companion object {
        val BOOLEAN_CANCELLATION_FIELD_NAMES: Set<String> = setOf(
            "canceled",
            "cancelled",
            "iscanceled",
            "iscancelled",
            "canceledtoday",
            "cancelledtoday"
        )
        val STATUS_FIELD_NAMES: Set<String> = setOf(
            "status",
            "eventstatus",
            "occurrencestatus"
        )
        val POSITIVE_COURSE_STATUS_TOKENS: Set<String> = setOf(
            "CANCELLED",
            "CANCELED",
            "CANCELLED_TODAY",
            "CANCELED_TODAY",
            "TEMPORARILY_CANCELLED",
            "TEMPORARILY_CANCELED",
            "SCHOOL_CANCELLED",
            "CANCELLED_BY_SCHOOL",
            "CANCELED_BY_SCHOOL",
            "停课",
            "临时停课"
        )
        val NEGATIVE_COURSE_STATUS_TOKENS: Set<String> = setOf(
            "SCHEDULED",
            "NORMAL",
            "ACTIVE",
            "RESTORED",
            "RESUMED",
            "RESCHEDULED",
            "TENTATIVE",
            "DONE",
            "UNCANCELLED",
            "UNCANCELED",
            "UN_CANCELLED",
            "UN_CANCELED",
            "NOT_CANCELLED",
            "NOT_CANCELED",
            "复课",
            "未停课",
            "不停课"
        )
    }
}

private fun unknown(type: String, reason: String): ConditionResult = ConditionResult(
    conditionType = type,
    passed = false,
    certainty = Certainty.UNKNOWN,
    reason = reason
)
