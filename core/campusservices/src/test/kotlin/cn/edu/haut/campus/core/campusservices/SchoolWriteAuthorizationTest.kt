package cn.edu.haut.campus.core.campusservices

import org.junit.Assert.assertFalse
import org.junit.Test

class SchoolWriteAuthorizationTest {
    @Test fun automaticSchoolWritesDefaultClosed() {
        assertFalse(SchoolWriteAuthorization.automaticWritesEnabled)
        assertFalse(BookingExecutionMode.LOCAL_DEVICE.requiresXpeachSso)
        assertFalse(BookingExecutionMode.MANUAL_REMINDER.requiresXpeachSso)
    }
    @Test(expected = IllegalStateException::class)
    fun realWritesCannotBeEnabledBySavedCredentials() { SchoolWriteAuthorization.requireVerified() }
}
