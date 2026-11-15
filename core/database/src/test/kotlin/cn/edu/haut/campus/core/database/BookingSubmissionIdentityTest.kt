package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.campusservices.BookingExecutionMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class BookingSubmissionIdentityTest {
    @Test
    fun `实际创建入口拒绝旧确认身份且三种引擎均零写入`() = runBlocking {
        val fixture = RepairTestFixture.create(RuntimeEnvironment.getApplication(), "prof_confirmation")
        try {
            val repo = fixture.repository
            val current = repo.snapshotBookingSubmissionIdentity()
            val stale = listOf(
                current.copy(profileId = "other_profile"),
                current.copy(cloudUserId = "other_user"),
                current.copy(generation = current.generation + 1),
                current.copy(apiBaseUrl = "https://other.invalid/api/v1"),
                current.copy(studentNo = "2026999999")
            )
            var networkCalls = 0
            repo.cloudHttpTransportForTest = { _, _, _, _, _ -> networkCalls++; error("禁止联网") }
            for (mode in BookingExecutionMode.entries) for (identity in stale) {
                val result = repo.createDualEngineBookingPlan(
                    executionMode = mode, targetDate = LocalDate.of(2026, 11, 26),
                    targetTimeText = "07:00", preferredAreaName = "测试区域", preferredSeatLabel = "测试座位",
                    nowEpochMillis = 1_700_000_000_000L, expectedIdentity = identity
                )
                assertTrue("旧确认必须以身份变化拒绝：$mode", result.exceptionOrNull()?.message?.contains("确认身份已变化") == true)
                assertTrue(fixture.database.cloudAndParcelDao().getLibraryBookingPlansSnapshot().isEmpty())
            }
            assertEquals(0, networkCalls)
            // 当前身份仍可通过真实入口创建提醒，避免把全部拒绝误当成修复成功。
            val accepted = repo.createDualEngineBookingPlan(
                executionMode = BookingExecutionMode.MANUAL_REMINDER,
                targetDate = LocalDate.of(2026, 11, 26), targetTimeText = "07:00",
                preferredAreaName = "测试区域", preferredSeatLabel = "测试座位",
                nowEpochMillis = java.time.Instant.parse("2026-11-15T14:00:00Z").toEpochMilli(),
                expectedIdentity = current
            ).getOrThrow()
            assertEquals(current.studentNo.ifBlank { null }, accepted.ownerStudentId)
            assertEquals(1, fixture.database.cloudAndParcelDao().getLibraryBookingPlansSnapshot().size)
            assertEquals(0, networkCalls)
        } finally { fixture.close() }
    }
}
