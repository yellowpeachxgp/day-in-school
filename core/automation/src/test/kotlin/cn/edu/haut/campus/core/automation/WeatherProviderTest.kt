package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.model.GeoPoint
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherProviderTest {
    private val scope = AutomationTestFixtures.scope
    private val now: Instant = Instant.parse("2026-03-02T00:00:00Z")

    @Test
    fun termsNotAcceptedDoesNotNetworkAndReturnsUnknownInput() = runTest {
        val transport = RecordingTransport()
        val provider = MetNorwayWeatherProvider(
            transport = transport,
            clock = Clock.fixed(now, ZoneOffset.UTC)
        )

        val result = provider.forecast(request(termsAccepted = false))

        val unavailable = result as WeatherProviderResult.Unavailable
        assertEquals(WeatherUnavailableReason.TERMS_NOT_ACCEPTED, unavailable.reason)
        assertEquals(0, transport.urls.size)
    }

    @Test
    fun metNorwayCompactFixtureParsesWithAttributionAndSecondCallUsesCache() = runTest {
        val transport = RecordingTransport()
        val provider = MetNorwayWeatherProvider(
            transport = transport,
            cacheStore = InMemoryWeatherResponseCacheStore(),
            clock = Clock.fixed(now, ZoneOffset.UTC)
        )

        val first = provider.forecast(request(termsAccepted = true)) as WeatherProviderResult.Success
        val second = provider.forecast(request(termsAccepted = true)) as WeatherProviderResult.Success

        assertEquals(1, transport.urls.size)
        assertTrue(transport.urls.single().contains("lat=34.1235"))
        assertTrue(transport.urls.single().contains("lon=113.1235"))
        assertEquals("met-norway", first.snapshot.providerId)
        assertEquals(listOf("Data from MET Norway"), first.snapshot.attributionLines)
        assertEquals("https://api.met.no/doc/License", first.snapshot.licenseUrl)
        assertEquals("https://api.met.no/doc/TermsOfService", first.snapshot.termsUrl)
        assertEquals(1, first.snapshot.periods.size)
        assertEquals(80, first.snapshot.periods.single().precipitationProbabilityPercent)
        assertEquals("rain", first.snapshot.periods.single().weatherSymbol)
        assertEquals(first.snapshot, second.snapshot)
    }

    @Test
    fun http429IsRateLimitedAndDoesNotBecomeEvidence() = runTest {
        val transport = RecordingTransport(statusCode = 429)
        val provider = MetNorwayWeatherProvider(
            transport = transport,
            clock = Clock.fixed(now, ZoneOffset.UTC)
        )

        val result = provider.forecast(request(termsAccepted = true))

        val unavailable = result as WeatherProviderResult.Unavailable
        assertEquals(WeatherUnavailableReason.RATE_LIMITED, unavailable.reason)
    }

    @Test
    fun corruptedCachedResponseIsInvalidAndDoesNotNetworkAgain() = runTest {
        val transport = RecordingTransport()
        val corrupted = WeatherCachedResponse(
            body = "not-json",
            fetchedAt = now.minusSeconds(600),
            cacheExpiresAt = now.plusSeconds(3600),
            nextRequestAt = now.plusSeconds(3 * 3600)
        )
        val provider = MetNorwayWeatherProvider(
            transport = transport,
            cacheStore = StaticWeatherCacheStore(corrupted),
            clock = Clock.fixed(now, ZoneOffset.UTC)
        )

        val result = provider.forecast(request(termsAccepted = true))

        val unavailable = result as WeatherProviderResult.Unavailable
        assertEquals(WeatherUnavailableReason.INVALID_RESPONSE, unavailable.reason)
        assertEquals(0, transport.urls.size)
    }

    @Test
    fun malformedWeatherResponseIsUnavailableRatherThanEvidence() = runTest {
        val transport = RecordingTransport(body = "{\"properties\":{\"meta\":{}}}")
        val provider = MetNorwayWeatherProvider(
            transport = transport,
            clock = Clock.fixed(now, ZoneOffset.UTC)
        )

        val result = provider.forecast(request(termsAccepted = true))

        val unavailable = result as WeatherProviderResult.Unavailable
        assertEquals(WeatherUnavailableReason.INVALID_RESPONSE, unavailable.reason)
    }

    private fun request(termsAccepted: Boolean): WeatherForecastRequest = WeatherForecastRequest(
        scope = scope,
        location = GeoPoint(34.1234567, 113.1234567),
        at = now,
        horizon = Duration.ofHours(3),
        termsAccepted = termsAccepted,
        campusId = scope.campusId
    )

    private class StaticWeatherCacheStore(
        private val cached: WeatherCachedResponse
    ) : WeatherResponseCacheStore {
        override suspend fun read(key: String): WeatherCachedResponse = cached
        override suspend fun write(key: String, value: WeatherCachedResponse) = Unit
    }

    private class RecordingTransport(
        private val body: String = COMPACT_FIXTURE,
        private val statusCode: Int = 200
    ) : WeatherHttpTransport {
        val urls = ArrayList<String>()

        override suspend fun get(url: String, userAgent: String): WeatherHttpResponse {
            urls += url
            return WeatherHttpResponse(
                statusCode = statusCode,
                body = body,
                headers = mapOf("expires" to "Mon, 02 Mar 2026 03:00:00 GMT")
            )
        }
    }

    private companion object {
        const val COMPACT_FIXTURE: String = """{"type":"Feature","properties":{"meta":{"updated_at":"2026-03-02T00:00:00Z"},"timeseries":[{"time":"2026-03-02T00:00:00Z","data":{"instant":{"details":{"air_temperature":8.5}},"next_1_hours":{"summary":{"symbol_code":"rain"},"details":{"precipitation_amount":1.2,"probability_of_precipitation":80}}}}]}}"""
    }
}
