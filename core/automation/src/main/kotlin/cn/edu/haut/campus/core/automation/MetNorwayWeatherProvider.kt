package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WeatherHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
) {
    init {
        require(statusCode in 100..599) { "HTTP status 非法" }
        require(body.length <= MAX_WEATHER_RESPONSE_CHARS) { "天气响应体过大" }
        headers.forEach { (name, value) ->
            require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() || it.isWhitespace() }) {
                "HTTP 头名称非法"
            }
            require(value.length <= 512 && value.none { it.isISOControl() }) { "HTTP 头值非法" }
        }
    }
}

interface WeatherHttpTransport {
    suspend fun get(url: String, userAgent: String): WeatherHttpResponse
}

/** HTTPS-only 最小传输层：禁重定向、限超时、限响应大小；错误细节不向自动化记录泄漏响应体。 */
class HttpsWeatherHttpTransport(
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 8_000
) : WeatherHttpTransport {
    override suspend fun get(url: String, userAgent: String): WeatherHttpResponse = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "天气传输只允许 HTTPS" }
        val connection = URL(url).openConnection() as HttpURLConnection
        if (connection !is HttpsURLConnection) throw IOException("天气传输只允许 HTTPS")
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val bytes = readLimited(input, MAX_WEATHER_RESPONSE_BYTES)
                String(bytes, Charsets.UTF_8)
            }.orEmpty()
            val headers = connection.headerFields.orEmpty().mapNotNull { (name, values) ->
                val key = name?.lowercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val value = values?.firstOrNull()?.takeIf { it.length <= 512 && it.none { ch -> ch.isISOControl() } } ?: return@mapNotNull null
                key to value
            }.toMap()
            WeatherHttpResponse(status, body.take(MAX_WEATHER_RESPONSE_CHARS), headers)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(8 * 1024)
        val output = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("天气响应超过大小上限")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

/** 原始响应缓存同时记录数据可信过期时间与下一次允许请求时间；过期响应绝不 resurrect 成确定证据。 */
data class WeatherCachedResponse(
    val body: String,
    val fetchedAt: Instant,
    val cacheExpiresAt: Instant,
    val nextRequestAt: Instant,
    val contentRevision: String? = null
) {
    init {
        require(body.isNotBlank() && body.length <= MAX_WEATHER_RESPONSE_CHARS) { "天气缓存响应为空或过大" }
        require(!cacheExpiresAt.isBefore(fetchedAt)) { "天气缓存 cacheExpiresAt 早于 fetchedAt" }
        require(!nextRequestAt.isBefore(fetchedAt)) { "天气缓存 nextRequestAt 早于 fetchedAt" }
        contentRevision?.let {
            require(it.isNotBlank() && it.length <= 256 && it.none { ch -> ch.isISOControl() || ch.isWhitespace() }) {
                "天气 contentRevision 非法"
            }
        }
    }
}

interface WeatherResponseCacheStore {
    suspend fun read(key: String): WeatherCachedResponse?
    suspend fun write(key: String, value: WeatherCachedResponse)
}

class InMemoryWeatherResponseCacheStore : WeatherResponseCacheStore {
    private val values = ConcurrentHashMap<String, WeatherCachedResponse>()

    override suspend fun read(key: String): WeatherCachedResponse? = values[key]

    override suspend fun write(key: String, value: WeatherCachedResponse) {
        values[key] = value
    }
}

/**
 * R03 推荐的默认免 Key Provider：MET Norway Locationforecast 2.0 compact。
 *
 * 保守边界：
 * - 调用前必须收到用户在本规则上的 termsAccepted=true；否则不发起网络请求。
 * - 坐标固定截断到 4 位小数；不使用精确 GPS；不在证据中记录坐标。
 * - rest-of-world 同一坐标至少 3 小时才允许再次请求；缓存失败时 fail-closed，不绕过频率约束。
 * - 响应过期、HTTP/解析失败、字段缺失都不会被提升为确定性天气结论。
 */
class MetNorwayWeatherProvider(
    private val transport: WeatherHttpTransport = HttpsWeatherHttpTransport(),
    private val cacheStore: WeatherResponseCacheStore = InMemoryWeatherResponseCacheStore(),
    private val clock: Clock = Clock.systemUTC(),
    private val userAgent: String = DEFAULT_USER_AGENT
) : WeatherProvider {
    override val info: WeatherProviderInfo = WeatherProviderInfo(
        providerId = PROVIDER_ID,
        displayName = "MET Norway Locationforecast",
        authenticationRequirement = WeatherAuthenticationRequirement.NONE,
        attributionLines = listOf("Data from MET Norway"),
        licenseUrl = "https://api.met.no/doc/License",
        termsUrl = "https://api.met.no/doc/TermsOfService"
    )

    override suspend fun forecast(request: WeatherForecastRequest): WeatherProviderResult {
        val now = clock.instant()
        if (!request.termsAccepted) {
            return unavailable(WeatherUnavailableReason.TERMS_NOT_ACCEPTED, "用户尚未在本规则中确认 MET Norway 条款与署名要求")
        }
        val roundedLocation = roundToFourDecimals(request.location)
        val cacheKey = cacheKey(roundedLocation)
        val cached = try {
            cacheStore.read(cacheKey)
        } catch (error: Throwable) {
            if (error is CancellationException || error is Error) throw error
            return unavailable(WeatherUnavailableReason.CACHE_UNUSABLE, "天气缓存读取失败，无法保证请求频率约束")
        }
        if (cached != null) {
            if (now.isBefore(cached.cacheExpiresAt)) {
                return parseCached(cached, roundedLocation, request)
            }
            if (now.isBefore(cached.nextRequestAt)) {
                return unavailable(WeatherUnavailableReason.RATE_LIMITED, "同一校区坐标的 MET Norway 请求间隔尚未达到 3 小时")
            }
        }

        val url = ENDPOINT + "?lat=" + formatCoordinate(roundedLocation.latitude) +
            "&lon=" + formatCoordinate(roundedLocation.longitude)
        val response = try {
            transport.get(url, userAgent)
        } catch (error: Throwable) {
            if (error is CancellationException || error is Error) throw error
            return unavailable(WeatherUnavailableReason.NETWORK_ERROR, "MET Norway 网络请求失败（" + errorName(error) + "）")
        }
        when {
            response.statusCode == 429 -> return unavailable(WeatherUnavailableReason.RATE_LIMITED, "MET Norway 返回 429 限流")
            response.statusCode !in 200..299 -> return unavailable(
                WeatherUnavailableReason.HTTP_ERROR,
                "MET Norway HTTP " + response.statusCode + "，未使用响应体作为证据"
            )
        }
        if (response.body.isBlank() || response.body.length > MAX_WEATHER_RESPONSE_CHARS) {
            return unavailable(WeatherUnavailableReason.INVALID_RESPONSE, "MET Norway 响应为空或超过大小上限")
        }
        val updatedAt = parseUpdatedAt(response.body)
            ?: return unavailable(WeatherUnavailableReason.INVALID_RESPONSE, "MET Norway 响应缺少上游更新时间")
        val headerExpiresAt = response.headers["expires"]?.let(::parseHttpDate)
        val cacheExpiresAt = when {
            headerExpiresAt != null && headerExpiresAt.isAfter(now) -> headerExpiresAt
            headerExpiresAt != null -> now
            else -> now.plus(REST_OF_WORLD_UPDATE_INTERVAL)
        }
        val cachedResponse = WeatherCachedResponse(
            body = response.body,
            fetchedAt = now,
            cacheExpiresAt = cacheExpiresAt,
            nextRequestAt = now.plus(MIN_IDENTICAL_REQUEST_INTERVAL),
            contentRevision = updatedAt.toString()
        )
        try {
            cacheStore.write(cacheKey, cachedResponse)
        } catch (error: Throwable) {
            if (error is CancellationException || error is Error) throw error
            return unavailable(WeatherUnavailableReason.CACHE_UNUSABLE, "天气缓存写入失败，无法保证请求频率约束")
        }
        return parseCached(cachedResponse, roundedLocation, request)
    }

    private fun parseCached(
        cached: WeatherCachedResponse,
        location: GeoPoint,
        request: WeatherForecastRequest
    ): WeatherProviderResult {
        return try {
            val snapshot = parseLocationForecastSnapshot(
                body = cached.body,
                location = location,
                fetchedAt = cached.fetchedAt,
                cacheExpiresAt = cached.cacheExpiresAt,
                attributionLines = info.attributionLines,
                licenseUrl = info.licenseUrl,
                termsUrl = info.termsUrl,
                scopeProviderId = PROVIDER_ID,
                scope = request.scope
            )
            WeatherProviderResult.Success(snapshot)
        } catch (error: Throwable) {
            if (error is CancellationException || error is Error) throw error
            unavailable(WeatherUnavailableReason.INVALID_RESPONSE, "MET Norway 响应解析失败，天气条件按 UNKNOWN")
        }
    }

    private fun unavailable(reason: WeatherUnavailableReason, detail: String): WeatherProviderResult.Unavailable =
        WeatherProviderResult.Unavailable(PROVIDER_ID, reason, detail.take(240))

    private fun cacheKey(location: GeoPoint): String = AutomationKeys.sha256Hex(
        PROVIDER_ID + "|" + formatCoordinate(location.latitude) + "|" + formatCoordinate(location.longitude)
    )

    companion object {
        const val PROVIDER_ID: String = "met-norway"
        const val ENDPOINT: String = "https://api.met.no/weatherapi/locationforecast/2.0/compact"
        const val DEFAULT_USER_AGENT: String =
            "day-in-school automation-weather (+https://github.com/yellowpeachxgp/day-in-school)"
        val MIN_IDENTICAL_REQUEST_INTERVAL: Duration = Duration.ofHours(3)
        val REST_OF_WORLD_UPDATE_INTERVAL: Duration = Duration.ofHours(6)
    }
}

internal fun parseLocationForecastSnapshot(
    body: String,
    location: GeoPoint,
    fetchedAt: Instant,
    cacheExpiresAt: Instant,
    attributionLines: List<String>,
    licenseUrl: String,
    termsUrl: String,
    scopeProviderId: String,
    scope: cn.edu.haut.campus.core.model.SchoolAccountScope
): WeatherSnapshot {
    val root = Json.parse(body) as? JsonValue.Obj ?: throw IllegalArgumentException("天气响应根节点不是 object")
    val properties = root.obj("properties") as? JsonValue.Obj ?: throw IllegalArgumentException("天气响应缺少 properties")
    val meta = properties.obj("meta") as? JsonValue.Obj ?: throw IllegalArgumentException("天气响应缺少 meta")
    val updatedAtText = meta.str("updated_at") ?: throw IllegalArgumentException("天气响应缺少 updated_at")
    val updatedAt = Instant.parse(updatedAtText)
    val series = properties.arr("timeseries") ?: throw IllegalArgumentException("天气响应缺少 timeseries")
    require(series.isNotEmpty()) { "天气 timeseries 为空" }
    require(series.size <= 240) { "天气 timeseries 过大" }

    val periods = series.mapNotNull { item ->
        val obj = item as? JsonValue.Obj ?: return@mapNotNull null
        val timeText = obj.str("time") ?: return@mapNotNull null
        val start = runCatching { Instant.parse(timeText) }.getOrNull() ?: return@mapNotNull null
        val data = obj.obj("data") as? JsonValue.Obj ?: return@mapNotNull null
        val instantDetails = (data.obj("instant") as? JsonValue.Obj)?.obj("details") as? JsonValue.Obj
        val temperature = instantDetails?.obj("air_temperature")?.decimalOrNull()
        val nextOne = data.obj("next_1_hours") as? JsonValue.Obj
        val nextSix = data.obj("next_6_hours") as? JsonValue.Obj
        val chosen = nextOne ?: nextSix ?: return@mapNotNull null
        val durationHours = if (nextOne != null) 1L else 6L
        val details = chosen.obj("details") as? JsonValue.Obj
        val summary = chosen.obj("summary") as? JsonValue.Obj
        WeatherPeriodForecast(
            start = start,
            end = start.plusSeconds(durationHours * 3600L),
            precipitationProbabilityPercent = details?.obj("probability_of_precipitation")?.probabilityIntOrNull(),
            precipitationAmountMillimeters = details?.obj("precipitation_amount")?.decimalOrNull(),
            weatherSymbol = summary?.str("symbol_code"),
            temperatureCelsius = temperature
        )
    }
    require(periods.isNotEmpty()) { "天气 timeseries 没有可用预报时段" }
    val complete = periods.all {
        it.precipitationProbabilityPercent != null || it.precipitationAmountMillimeters != null || it.weatherSymbol != null
    }
    return WeatherSnapshot(
        providerId = scopeProviderId,
        location = location,
        fetchedAt = fetchedAt,
        validFrom = periods.minOf { it.start },
        validUntil = periods.maxOf { it.end },
        cacheExpiresAt = cacheExpiresAt,
        attributionLines = attributionLines,
        licenseUrl = licenseUrl,
        termsUrl = termsUrl,
        periods = periods,
        provenance = DataProvenance(
            providerId = scopeProviderId,
            schoolId = scope.schoolId,
            accountId = scope.accountId,
            sourceSystem = "met-norway-locationforecast/2.0/compact",
            fetchedAt = fetchedAt,
            completeness = if (complete) SnapshotCompleteness.COMPLETE else SnapshotCompleteness.PARTIAL,
            trust = VerificationLevel.MOCK_TESTED,
            schemaVersion = 1,
            contentRevision = updatedAt.toString()
        )
    )
}

internal fun JsonValue.decimalOrNull(): BigDecimal? =
    (this as? JsonValue.Num)?.literal?.let { raw -> runCatching { BigDecimal(raw) }.getOrNull() }

internal fun JsonValue.probabilityIntOrNull(): Int? = decimalOrNull()?.let { value ->
    runCatching { value.setScale(0, RoundingMode.HALF_UP).intValueExact() }.getOrNull()?.takeIf { it in 0..100 }
}

internal fun parseUpdatedAt(body: String): Instant? = runCatching {
    val root = Json.parse(body) as? JsonValue.Obj ?: return@runCatching null
    val properties = root.obj("properties") as? JsonValue.Obj ?: return@runCatching null
    val meta = properties.obj("meta") as? JsonValue.Obj ?: return@runCatching null
    meta.str("updated_at")?.let(Instant::parse)
}.getOrNull()

internal fun parseHttpDate(raw: String): Instant? = runCatching {
    ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
}.getOrNull()

internal fun roundToFourDecimals(point: GeoPoint): GeoPoint = GeoPoint(
    latitude = BigDecimal.valueOf(point.latitude).setScale(4, RoundingMode.HALF_UP).toDouble(),
    longitude = BigDecimal.valueOf(point.longitude).setScale(4, RoundingMode.HALF_UP).toDouble()
)

internal fun formatCoordinate(value: Double): String =
    BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).toPlainString()

private fun errorName(error: Throwable): String = error::class.simpleName ?: "Throwable"

internal const val MAX_WEATHER_RESPONSE_BYTES: Int = 256 * 1024
internal const val MAX_WEATHER_RESPONSE_CHARS: Int = 256 * 1024
