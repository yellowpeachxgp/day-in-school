package cn.edu.haut.campus.core.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCredentialSyncPolicyTest {
    private fun allowed(
        status: String = "ARMED",
        remainingDays: Int = 1,
        fireAt: Long = 2_000L,
        mode: String = "CLOUD_HOSTED",
        jobId: String? = "test-job"
    ) = CloudCredentialSyncPolicy.requiresCredentials(mode, status, remainingDays, fireAt, jobId, 1_000L)

    @Test fun pendingFutureCloudJobCanRefreshCredentials() {
        listOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK").forEach { assertTrue(allowed(status = it)) }
        assertTrue(allowed(remainingDays = 3))
    }

    @Test fun completedBookingNeverUploadsEvenIfLegacyRemainingDaysIsPositive() {
        assertFalse(allowed(status = "CONFIRMED", remainingDays = 0))
        assertFalse(allowed(status = "CONFIRMED", remainingDays = 1))
        assertFalse(allowed(remainingDays = 0))
        assertFalse(allowed(remainingDays = -1))
    }

    @Test fun terminalRevokedInFlightAndUnknownStatesFailClosed() {
        listOf("CANCELLED", "FAILED", "EXPIRED", "REVOKED", "EXECUTING", "RESULT_UNKNOWN", "UNKNOWN", "")
            .forEach { assertFalse(it, allowed(status = it)) }
    }

    @Test fun staleOrNotYetRegisteredJobsAndLocalPlansDoNotUpload() {
        assertFalse(allowed(fireAt = 999L))
        assertFalse(allowed(fireAt = 1_000L))
        assertFalse(allowed(jobId = null))
        assertFalse(allowed(jobId = " "))
        assertFalse(allowed(mode = "LOCAL_DEVICE"))
        assertFalse(allowed(mode = "MANUAL_REMINDER"))
    }
}
