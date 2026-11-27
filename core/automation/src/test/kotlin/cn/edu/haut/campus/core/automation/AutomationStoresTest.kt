package cn.edu.haut.campus.core.automation

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationStoresTest {
    private val acquiredAt: Instant = Instant.parse("2026-03-02T08:00:01Z")

    @Test
    fun markRunRecordedIsIdempotentForSameRunIdentity() = runTest {
        val store = InMemoryAutomationDedupeStore()
        val record = record()
        assertTrue(store.tryAcquire(record) is DedupeAcquireResult.Acquired)

        assertTrue(store.markRunRecorded(record, acquiredAt.plusSeconds(2)))
        assertTrue(store.markRunRecorded(record, acquiredAt.plusSeconds(5)))

        val stored = store.find(record.dedupeKey) ?: error("dedupe 应存在")
        assertEquals(AutomationDedupeState.RUN_RECORDED, stored.state)
        assertEquals("幂等终态不得覆盖首次完成时间", acquiredAt.plusSeconds(2), stored.completedAt)
    }

    @Test
    fun markRunRecordedRejectsTerminalWithDifferentRunIdentity() = runTest {
        val store = InMemoryAutomationDedupeStore()
        val record = record()
        assertTrue(store.tryAcquire(record) is DedupeAcquireResult.Acquired)
        assertTrue(store.markRunRecorded(record, acquiredAt.plusSeconds(2)))

        assertFalse(store.markRunRecorded(record.copy(runId = "run-other"), acquiredAt.plusSeconds(3)))
        assertFalse(store.markRunRecorded(record.copy(triggerInstanceKey = "other-instance"), acquiredAt.plusSeconds(3)))
        assertFalse(store.markRunRecorded(record.copy(policyFingerprint = "other-policy"), acquiredAt.plusSeconds(3)))
    }

    @Test
    fun markRunRecordedDoesNotCloseReacquiredPendingWindow() = runTest {
        val store = InMemoryAutomationDedupeStore()
        val expired = record(leaseSeconds = 10)
        assertTrue(store.tryAcquire(expired) is DedupeAcquireResult.Acquired)
        val reacquired = record(acquiredAt = this.acquiredAt.plusSeconds(11), leaseSeconds = 30)
        assertTrue(store.tryAcquire(reacquired) is DedupeAcquireResult.Acquired)

        assertFalse("旧执行体不得关闭新 PENDING 执行窗口", store.markRunRecorded(expired, acquiredAt.plusSeconds(12)))
        val stored = store.find(expired.dedupeKey) ?: error("dedupe 应存在")
        assertEquals(AutomationDedupeState.PENDING, stored.state)
        assertEquals(reacquired.acquiredAt, stored.acquiredAt)
    }

    private fun record(
        acquiredAt: Instant = this.acquiredAt,
        leaseSeconds: Long = 30
    ): AutomationDedupeRecord = AutomationDedupeRecord(
        dedupeKey = "dedupe-test",
        ruleId = "rule-1",
        triggerInstanceKey = "trigger-instance",
        runId = "run-test",
        acquiredAt = acquiredAt,
        policyFingerprint = "once-per-trigger-instance",
        leaseExpiresAt = acquiredAt.plusSeconds(leaseSeconds)
    )
}
