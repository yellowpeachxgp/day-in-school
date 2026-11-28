package cn.edu.haut.campus.core.database

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** C27/W2-02：automation_rules/runs/dedupe/action_checkpoints 全部进入全量擦除链路。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AutomationWipeTest {
    private lateinit var fixture: RepairTestFixture

    @Before
    fun setUp() {
        fixture = RepairTestFixture.create(RuntimeEnvironment.getApplication(), "prof_automation_wipe")
    }

    @After
    fun tearDown() = fixture.close()

    @Test
    fun wipeAllPersonalDataLeavesEveryAutomationTableAtZero() = runBlocking {
        val dao = fixture.database.automationDao()
        dao.upsertRule(
            AutomationRuleEntity(
                id = "rule-wipe",
                scopeSchoolId = "haut",
                scopeAccountId = "acct_prof_automation_wipe",
                scopeProfileId = "prof_automation_wipe",
                scopeCampusId = "haut-lianhuajie",
                name = "擦除测试",
                enabled = true,
                triggerJson = "{}",
                conditionsJson = "[]",
                actionsJson = "[]",
                dedupe = "once-per-trigger-instance",
                quietHoursJson = null,
                templateId = null,
                schemaVersion = 1,
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L
            )
        )
        dao.insertRun(
            AutomationRunEntity(
                runId = "run-wipe",
                ruleId = "rule-wipe",
                triggerInstanceKey = "trigger-wipe",
                dedupeKey = "dedupe-wipe",
                firedAtEpochMs = 1L,
                startedAtEpochMs = 1L,
                finishedAtEpochMs = 2L,
                status = "EXECUTED",
                dryRun = false,
                evaluationsJson = "[]",
                outcomesJson = "[]",
                statusReason = null,
                schemaVersion = 1
            )
        )
        dao.insertDedupe(
            AutomationDedupeEntity(
                dedupeKey = "dedupe-wipe",
                ruleId = "rule-wipe",
                triggerInstanceKey = "trigger-wipe",
                runId = "run-wipe",
                executionOwner = "owner-wipe",
                acquiredAtEpochMs = 1L,
                policyFingerprint = "once-per-trigger-instance",
                leaseExpiresAtEpochMs = 2L,
                state = "RUN_RECORDED",
                completedAtEpochMs = 2L
            )
        )
        dao.insertCheckpoint(
            AutomationActionCheckpointEntity(
                checkpointKey = "checkpoint-wipe",
                dedupeKey = "dedupe-wipe",
                runId = "run-wipe",
                ruleId = "rule-wipe",
                triggerInstanceKey = "trigger-wipe",
                actionIndex = 0,
                actionType = "core.refresh-home",
                configDigest = "0".repeat(64),
                executionOwner = "owner-wipe",
                attempt = 1L,
                state = "COMPLETED",
                outcomeJson = "{}",
                confirmationJson = null,
                startedAtEpochMs = 1L,
                updatedAtEpochMs = 2L,
                completedAtEpochMs = 2L
            )
        )

        fixture.repository.wipeAllPersonalData()

        assertEquals(0, dao.countRules())
        assertEquals(0, dao.countRuns())
        assertEquals(0, dao.countDedupe())
        assertEquals(0, dao.countCheckpoints())
    }
}
