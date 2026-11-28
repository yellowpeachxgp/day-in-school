package cn.edu.haut.campus.core.automation.android

import cn.edu.haut.campus.core.automation.WeatherCachedResponse
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FileWeatherResponseCacheStoreTest {
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("weather-cache-account"),
        profileId = ProfileId("weather-cache-profile"),
        campusId = CampusId("haut-lianhuajie")
    )

    @Test
    fun weatherCacheRoundTripUsesHashedProfileAndCoordinateFreeFileNames() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = FileWeatherResponseCacheStore(context, scope)
        val key = "a".repeat(64)
        val value = WeatherCachedResponse(
            body = "{"properties":{"meta":{}}}",
            fetchedAt = Instant.ofEpochMilli(1_777_000_000_000L),
            cacheExpiresAt = Instant.ofEpochMilli(1_777_000_600_000L),
            nextRequestAt = Instant.ofEpochMilli(1_777_001_200_000L),
            contentRevision = "2026-03-02T00:00:00Z"
        )

        store.write(key, value)
        val restored = store.read(key)

        assertEquals(value, restored)
        val cacheRoot = java.io.File(context.cacheDir, "automation-weather")
        val payload = cacheRoot.walkTopDown().filter { it.isFile }.joinToString("\n") { it.name + ":" + it.readText() }
        assertFalse(payload.contains("weather-cache-account"))
        assertFalse(payload.contains("weather-cache-profile"))
        assertFalse(payload.contains("haut-lianhuajie"))
        assertFalse(payload.contains("34.8301"))
    }
}
