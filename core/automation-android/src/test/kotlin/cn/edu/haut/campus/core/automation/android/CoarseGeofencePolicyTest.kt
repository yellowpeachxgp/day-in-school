package cn.edu.haut.campus.core.automation.android

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoarseGeofencePolicyTest {
    private val now: Instant = Instant.parse("2026-03-02T08:00:00Z")

    @Test
    fun staleMissingOrCoarseLocationIsRejectedForBothEmitAndEvaluation() {
        assertFalse(CoarseGeofencePolicy.isUsable(now.toEpochMilli(), null, now))
        assertFalse(CoarseGeofencePolicy.isUsable(now.minusSeconds(2 * 3600 + 1).toEpochMilli(), 50f, now))
        assertFalse(CoarseGeofencePolicy.isUsable(now.toEpochMilli(), 1001f, now))
        assertTrue(CoarseGeofencePolicy.isUsable(now.minusSeconds(3600).toEpochMilli(), 1000f, now))
    }
}
