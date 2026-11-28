package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.core.model.SchoolAccountScope
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** 天气 Provider 的认证形态。BYOK 只描述能力边界；任何 Key 都必须由专门加密存储注入，绝不进入自动化 config/evidence。 */
enum class WeatherAuthenticationRequirement {
    NONE,
    USER_KEY_REQUIRED
}

data class WeatherProviderInfo(
    val providerId: String,
    val displayName: String,
    val authenticationRequirement: WeatherAuthenticationRequirement,
    val attributionLines: List<String>,
    val licenseUrl: String,
    val termsUrl: String
) {
    init {
        requireAutomationToken(providerId, "weather providerId", 128)
        require(displayName.isNotBlank() && displayName.length <= 120) { "天气 Provider 显示名为空或过长" }
        require(attributionLines.isNotEmpty()) { "天气 Provider 必须携带署名行" }
        attributionLines.forEach { line ->
            require(line.isNotBlank() && line.length <= 240 && line.none { it.isISOControl() }) {
                "天气署名行为空、过长或含控制字符"
            }
        }
        requireHttpsUrl(licenseUrl, "licenseUrl")
        requireHttpsUrl(termsUrl, "termsUrl")
    }
}

/** 自动化天气查询请求。location 必须是校区级参考坐标或用户显式提供的粗坐标，不得是后台精确 GPS。 */
data class WeatherForecastRequest(
    val scope: SchoolAccountScope,
    val location: GeoPoint,
    val at: Instant,
    val horizon: Duration,
    val termsAccepted: Boolean,
    val campusId: CampusId? = scope.campusId
) {
    init {
        requireValidGeoPoint(location)
        require(!horizon.isZero && !horizon.isNegative && horizon <= MAX_FORECAST_HORIZON) {
            "天气查询 horizon 必须是 0..9 天内的正时长"
        }
    }

    companion object {
        val MAX_FORECAST_HORIZON: Duration = Duration.ofDays(9)
    }
}

data class WeatherPeriodForecast(
    val start: Instant,
    val end: Instant,
    val precipitationProbabilityPercent: Int? = null,
    val precipitationAmountMillimeters: BigDecimal? = null,
    val weatherSymbol: String? = null,
    val temperatureCelsius: BigDecimal? = null
) {
    init {
        require(end.isAfter(start)) { "天气预报时段 end 必须晚于 start" }
        precipitationProbabilityPercent?.let { require(it in 0..100) { "降水概率必须在 0..100" } }
        precipitationAmountMillimeters?.let {
            require(it >= BigDecimal.ZERO && it <= BigDecimal("1000")) { "降水量必须是 0..1000 毫米" }
        }
        weatherSymbol?.let {
            require(it.isNotBlank() && it.length <= 80 && it.none { ch -> ch.isISOControl() }) {
                "天气符号为空、过长或含控制字符"
            }
        }
        temperatureCelsius?.let {
            require(it >= BigDecimal("-100") && it <= BigDecimal("100")) { "温度超出合理范围" }
        }
    }
}

/**
 * Provider 输出的不可拆分事实快照。署名、许可、有效期与 provenance 不是展示层可选装饰；
 * 自动化试运行/运行记录必须能把条件结论回溯到这些字段。
 */
data class WeatherSnapshot(
    val providerId: String,
    val location: GeoPoint,
    val fetchedAt: Instant,
    val validFrom: Instant,
    val validUntil: Instant,
    val cacheExpiresAt: Instant,
    val attributionLines: List<String>,
    val licenseUrl: String,
    val termsUrl: String,
    val periods: List<WeatherPeriodForecast>,
    val provenance: DataProvenance
) {
    init {
        requireAutomationToken(providerId, "weather providerId", 128)
        requireValidGeoPoint(location)
        require(validUntil.isAfter(validFrom)) { "天气快照 validUntil 必须晚于 validFrom" }
        require(!cacheExpiresAt.isBefore(fetchedAt)) { "天气快照 cacheExpiresAt 早于 fetchedAt" }
        require(attributionLines.isNotEmpty()) { "天气快照必须携带署名行" }
        attributionLines.forEach { line ->
            require(line.isNotBlank() && line.length <= 240 && line.none { it.isISOControl() }) {
                "天气署名行为空、过长或含控制字符"
            }
        }
        requireHttpsUrl(licenseUrl, "licenseUrl")
        requireHttpsUrl(termsUrl, "termsUrl")
        require(periods.size <= 240) { "天气快照时段数量过大" }
        require(provenance.providerId == providerId) { "天气 provenance.providerId 与快照不一致" }
    }

    fun isExpiredAt(now: Instant): Boolean = !now.isBefore(cacheExpiresAt)
}

enum class WeatherUnavailableReason {
    TERMS_NOT_ACCEPTED,
    PROVIDER_DISABLED,
    KEY_REQUIRED,
    KEY_REJECTED,
    LOCATION_UNSUPPORTED,
    NETWORK_ERROR,
    HTTP_ERROR,
    RATE_LIMITED,
    INVALID_RESPONSE,
    CACHE_UNUSABLE
}

sealed interface WeatherProviderResult {
    data class Success(val snapshot: WeatherSnapshot) : WeatherProviderResult

    data class Unavailable(
        val providerId: String,
        val reason: WeatherUnavailableReason,
        val detail: String
    ) : WeatherProviderResult {
        init {
            requireAutomationToken(providerId, "weather providerId", 128)
            require(detail.isNotBlank() && detail.length <= 240 && detail.none { it.isISOControl() }) {
                "天气不可用 detail 为空、过长或含控制字符"
            }
        }
    }
}

/** WeatherProvider SPI：实现必须只读外部天气数据，不得把自动化安全边界扩展到任何外部写动作。 */
interface WeatherProvider {
    val info: WeatherProviderInfo

    suspend fun forecast(request: WeatherForecastRequest): WeatherProviderResult
}

/** 校区参考坐标解析端口。返回 null 时必须让天气条件 UNKNOWN，而不是猜测位置。 */
interface WeatherLocationPort {
    suspend fun referencePoint(scope: SchoolAccountScope, campusId: CampusId?): GeoPoint?
}

internal fun requireValidGeoPoint(point: GeoPoint) {
    require(point.latitude.isFinite() && point.latitude in -90.0..90.0) { "纬度必须是 -90..90 的有限值" }
    require(point.longitude.isFinite() && point.longitude in -180.0..180.0) { "经度必须是 -180..180 的有限值" }
}

internal fun requireHttpsUrl(value: String, field: String) {
    require(value.startsWith("https://") && value.length <= 512 && value.none { it.isISOControl() || it.isWhitespace() }) {
        field + " 必须是简短 HTTPS URL"
    }
}
