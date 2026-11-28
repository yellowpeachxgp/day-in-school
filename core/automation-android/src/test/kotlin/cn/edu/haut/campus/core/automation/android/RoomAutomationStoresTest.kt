package cn.edu.haut.campus.core.automation.android

import androidx.room.Room
import cn.edu.haut.campus.core.automation.ActionOutcome
import cn.edu.haut.campus.core.automation.ActionOutcomeStatus
import cn.edu.haut.campus.core.automation.ActionRisk
import cn.edu.haut.campus.core.automation.ActionSpec
import cn.edu.haut.campus.core.automation.AutomationActionCheckpoint
import cn.edu.haut.campus.core.automation.AutomationActionCheckpointState
import cn.edu.haut.campus.core.automation.AutomationDedupeRecord
import cn.edu.haut.campus.core.automation.AutomationDedupeState
import cn.edu.haut.campus.core.automation.AutomationKeys
import cn.edu.haut.campus.core.automation.AutomationRule
import cn.edu.haut.campus.core.automation.AutomationRun
import cn.edu.haut.campus.core.automation.AutomationRunConflictException
import cn.edu.haut.campus.core.automation.BuiltinAutomationTypes
import cn.edu.haut.campus.core.automation.DedupeAcquireResult
import cn.edu.haut.campus.core.automation.RunStatus
import cn.edu.haut.campus.core.automation.TriggerSpec
import cn.edu.haut.campus.core.automation.ActionCheckpointClaimResult
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RoomAutomationStoresTest {
    private lateinit var database: HautDatabase
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct-automation-store"),
        profileId = ProfileId("prof-automation-store"),
        campusId = CampusId("haut-lianhuajie")
    )
    private val now: Instant = Instant.parse("2026-03-02T08:00:00Z")

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun ruleAndRunStoresRoundTripWithIdempotentAppend() = runBlocking {
        val ruleStore = RoomAutomationRuleStore(database, scope)
        val rule = sampleRule()
        ruleStore.upsert(rule)
        assertEquals(rule, ruleStore.get(rule.id))
        assertEquals(listOf(rule), ruleStore.list())
        assertEquals(listOf(rule), ruleStore.observeRules().first())

        val runStore = RoomAutomationRunStore(database)
        val run = sampleRun(rule)
        assertTrue(runStore.append(run))
        assertFalse(runStore.append(run))
        assertEquals(run, runStore.get(run.runId))
        assertEquals(listOf(run), runStore.runs(rule.id).first())
        val conflict = runCatching { runStore.append(run.copy(status = RunStatus.FAILED)) }.exceptionOrNull()
        assertTrue(conflict is AutomationRunConflictException)
    }

    @Test
    fun activationCutoffIsPreservedAcrossContentEditAndResetOnTriggerOrEnableTransition() = runBlocking {
        var clockMs = now.toEpochMilli()
        val ruleStore = RoomAutomationRuleStore(database, scope) { Instant.ofEpochMilli(clockMs) }
        val rule = sampleRule()
        ruleStore.upsert(rule)
        val first = database.automationDao().getRule(rule.id)?.activatedAtEpochMs
        assertEquals(clockMs, first)

        clockMs += 1000L
        ruleStore.upsert(rule.copy(actions = rule.actions))
        assertEquals("触发器未变化的内容保存不得重置激活边界", first, database.automationDao().getRule(rule.id)?.activatedAtEpochMs)

        clockMs += 1000L
        ruleStore.upsert(rule.copy(enabled = false))
        assertEquals(null, database.automationDao().getRule(rule.id)?.activatedAtEpochMs)

        clockMs += 1000L
        ruleStore.upsert(rule.copy(enabled = true))
        assertEquals(clockMs, database.automationDao().getRule(rule.id)?.activatedAtEpochMs)

        clockMs += 1000L
        val changedTrigger = rule.copy(trigger = TriggerSpec.AtTime(LocalTime.of(9, 0), setOf(DayOfWeek.MONDAY)))
        ruleStore.upsert(changedTrigger)
        assertEquals(clockMs, database.automationDao().getRule(rule.id)?.activatedAtEpochMs)
    }

    @Test
    fun dedupeRoomStoreRenewsOnlyLiveOwnerAndFencesStaleCompletion() = runBlocking {
        val store = RoomAutomationDedupeStore(database)
        val acquiredAt = now
        val first = dedupeRecord(acquiredAt, owner = "owner-1", leaseEnd = acquiredAt.plusSeconds(10))
        assertTrue(store.tryAcquire(first) is DedupeAcquireResult.Acquired)
        assertTrue(store.renewLease(first, acquiredAt.plusSeconds(1), acquiredAt.plusSeconds(40)))

        val earlyRecovery = dedupeRecord(acquiredAt.plusSeconds(11), owner = "owner-2", leaseEnd = acquiredAt.plusSeconds(51))
        assertTrue(store.tryAcquire(earlyRecovery) is DedupeAcquireResult.AlreadyExists)

        val lateRecovery = dedupeRecord(acquiredAt.plusSeconds(41), owner = "owner-2", leaseEnd = acquiredAt.plusSeconds(81))
        assertTrue(store.tryAcquire(lateRecovery) is DedupeAcquireResult.Acquired)
        assertFalse(store.markRunRecorded(first, acquiredAt.plusSeconds(82)))
        assertTrue(store.markRunRecorded(lateRecovery, acquiredAt.plusSeconds(82)))
        assertEquals(AutomationDedupeState.RUN_RECORDED, store.find(first.dedupeKey)?.state)
    }

    @Test
    fun expiredLeaseCannotBeRenewedOrRevivedInRoomStore() = runBlocking {
        val store = RoomAutomationDedupeStore(database)
        val acquiredAt = now
        val original = dedupeRecord(acquiredAt, owner = "owner-expired", leaseEnd = acquiredAt.plusSeconds(10))
        assertTrue(store.tryAcquire(original) is DedupeAcquireResult.Acquired)

        assertFalse(
            "过期 PENDING lease 不得被原 owner 续租复活",
            store.renewLease(original, acquiredAt.plusSeconds(11), acquiredAt.plusSeconds(40))
        )

        val recovery = dedupeRecord(acquiredAt.plusSeconds(11), owner = "owner-recovery", leaseEnd = acquiredAt.plusSeconds(50))
        assertTrue(store.tryAcquire(recovery) is DedupeAcquireResult.Acquired)
        assertFalse(
            "恢复者接管后旧 owner 更不得续租",
            store.renewLease(original, acquiredAt.plusSeconds(12), acquiredAt.plusSeconds(60))
        )
    }

    @Test
    fun checkpointRoomStoreReplaysCompletedOutcomeAndFencesStaleOwner() = runBlocking {
        val store = RoomAutomationActionCheckpointStore(database)
        val checkpoint = checkpoint("checkpoint-1", owner = "owner-1", attempt = 1)
        assertTrue(store.claim(checkpoint) is ActionCheckpointClaimResult.Execute)
        val outcome = ActionOutcome(
            actionType = checkpoint.actionType,
            risk = ActionRisk.LOCAL_SILENT,
            status = ActionOutcomeStatus.SUCCESS,
            detail = "完成"
        )
        assertTrue(store.complete(checkpoint, outcome, now.plusSeconds(1)))

        val replay = store.claim(checkpoint.copy(executionOwner = "owner-2"))
        assertTrue(replay is ActionCheckpointClaimResult.ReplayCompleted)
        assertEquals(outcome, (replay as ActionCheckpointClaimResult.ReplayCompleted).checkpoint.outcome)

        val contested = checkpoint("checkpoint-2", owner = "owner-old", attempt = 1, runId = "run-room-2")
        assertTrue(store.claim(contested) is ActionCheckpointClaimResult.Execute)
        val takeoverClaim = store.claim(contested.copy(executionOwner = "owner-new"))
        assertTrue(takeoverClaim is ActionCheckpointClaimResult.Execute)
        val takeover = (takeoverClaim as ActionCheckpointClaimResult.Execute).checkpoint
        assertEquals(2L, takeover.attempt)
        assertFalse(store.complete(contested, outcome, now.plusSeconds(2)))
        assertTrue(store.complete(takeover, outcome, now.plusSeconds(2)))
    }

    private fun sampleRule(): AutomationRule = AutomationRule(
        id = "rule-room",
        scope = scope,
        name = "Room 测试",
        enabled = true,
        trigger = TriggerSpec.AtTime(LocalTime.of(8, 30), setOf(DayOfWeek.MONDAY)),
        conditions = emptyList(),
        actions = listOf(ActionSpec(BuiltinAutomationTypes.ACTION_REFRESH_HOME))
    )

    private fun sampleRun(rule: AutomationRule): AutomationRun = AutomationRun(
        runId = "run-room",
        ruleId = rule.id,
        triggerInstanceKey = "trigger-room",
        firedAt = now,
        evaluations = emptyList(),
        outcomes = emptyList(),
        status = RunStatus.EXECUTED,
        dryRun = false,
        dedupeKey = "dedupe-room",
        startedAt = now,
        finishedAt = now.plusSeconds(1),
        statusReason = null
    )

    private fun dedupeRecord(acquiredAt: Instant, owner: String, leaseEnd: Instant) = AutomationDedupeRecord(
        dedupeKey = "dedupe-room",
        ruleId = "rule-room",
        triggerInstanceKey = "trigger-room",
        runId = "run-room",
        executionOwner = owner,
        acquiredAt = acquiredAt,
        policyFingerprint = "once-per-trigger-instance",
        leaseExpiresAt = leaseEnd
    )

    private fun checkpoint(key: String, owner: String, attempt: Long, runId: String = "run-room") = AutomationActionCheckpoint(
        checkpointKey = key,
        dedupeKey = "dedupe-room",
        runId = runId,
        ruleId = "rule-room",
        triggerInstanceKey = "trigger-room",
        actionIndex = 0,
        actionType = BuiltinAutomationTypes.ACTION_REFRESH_HOME,
        configDigest = "0".repeat(64),
        executionOwner = owner,
        attempt = attempt,
        state = AutomationActionCheckpointState.STARTED,
        startedAt = now,
        updatedAt = now
    )
}
