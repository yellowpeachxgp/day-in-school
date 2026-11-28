package cn.edu.haut.campus.core.automation.android

import androidx.room.withTransaction
import cn.edu.haut.campus.core.automation.ActionCheckpointClaimResult
import cn.edu.haut.campus.core.automation.ActionConfirmation
import cn.edu.haut.campus.core.automation.ActionOutcome
import cn.edu.haut.campus.core.automation.AutomationActionCheckpoint
import cn.edu.haut.campus.core.automation.AutomationActionCheckpointConflictException
import cn.edu.haut.campus.core.automation.AutomationActionCheckpointState
import cn.edu.haut.campus.core.automation.AutomationDedupeConflictException
import cn.edu.haut.campus.core.automation.AutomationDedupeRecord
import cn.edu.haut.campus.core.automation.AutomationDedupeState
import cn.edu.haut.campus.core.automation.AutomationDedupeStore
import cn.edu.haut.campus.core.automation.AutomationRule
import cn.edu.haut.campus.core.automation.AutomationRun
import cn.edu.haut.campus.core.automation.AutomationRunConflictException
import cn.edu.haut.campus.core.automation.AutomationRunStore
import cn.edu.haut.campus.core.automation.DedupeAcquireResult
import cn.edu.haut.campus.core.automation.MutableAutomationRuleStore
import cn.edu.haut.campus.core.database.AutomationActionCheckpointEntity
import cn.edu.haut.campus.core.database.AutomationDedupeEntity
import cn.edu.haut.campus.core.database.AutomationRuleEntity
import cn.edu.haut.campus.core.database.AutomationRunEntity
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 当前账号 scope 的 Room 规则 store；跨 scope 行在读取边界 fail-closed，绝不泄露到其他 Profile。 */
class RoomAutomationRuleStore(
    private val database: HautDatabase,
    private val scope: SchoolAccountScope,
    private val clock: () -> Instant = Instant::now
) : MutableAutomationRuleStore {
    private val dao = database.automationDao()

    override suspend fun get(ruleId: String): AutomationRule? =
        dao.getRule(ruleId)?.takeIf { it.inScope(scope) }?.toRule()

    override suspend fun list(): List<AutomationRule> =
        dao.getRulesSnapshot().filter { it.inScope(scope) }.map { it.toRule() }

    override suspend fun upsert(rule: AutomationRule) {
        require(rule.scope == scope) { "AutomationRuleStore 只能写入本 profile scope" }
        val existing = dao.getRule(rule.id)
        val payload = AutomationJsonCodec.encodeRulePayload(rule)
        val nowMs = clock().toEpochMilli()
        val activatedAtMs = if (!rule.enabled) {
            null
        } else if (existing == null || !existing.enabled || existing.triggerJson != payload.triggerJson) {
            nowMs
        } else {
            existing.activatedAtEpochMs ?: nowMs
        }
        dao.upsertRule(
            AutomationRuleEntity(
                id = rule.id,
                scopeSchoolId = scope.schoolId.value,
                scopeAccountId = scope.accountId.value,
                scopeProfileId = scope.profileId.value,
                scopeCampusId = scope.campusId?.value,
                name = rule.name,
                enabled = rule.enabled,
                triggerJson = payload.triggerJson,
                conditionsJson = payload.conditionsJson,
                actionsJson = payload.actionsJson,
                dedupe = payload.dedupe,
                quietHoursJson = payload.quietHoursJson,
                templateId = rule.templateId,
                schemaVersion = rule.schemaVersion,
                createdAtEpochMs = existing?.createdAtEpochMs ?: nowMs,
                updatedAtEpochMs = nowMs,
                activatedAtEpochMs = activatedAtMs
            )
        )
    }

    override suspend fun delete(ruleId: String): Boolean {
        val existing = dao.getRule(ruleId) ?: return false
        require(existing.inScope(scope)) { "AutomationRuleStore 不能删除跨 scope 规则" }
        return dao.deleteRule(ruleId) > 0
    }

    fun observeRules(): Flow<List<AutomationRule>> = dao.observeRules().map { rows ->
        rows.filter { it.inScope(scope) }.map { it.toRule() }
    }

    private fun AutomationRuleEntity.toRule(): AutomationRule = AutomationJsonCodec.decodeRulePayload(
        triggerJson = triggerJson,
        conditionsJson = conditionsJson,
        actionsJson = actionsJson,
        dedupe = dedupe,
        quietHoursJson = quietHoursJson,
        ruleId = id,
        scope = scope(),
        name = name,
        enabled = enabled,
        templateId = templateId,
        schemaVersion = schemaVersion
    )

    private fun AutomationRuleEntity.inScope(expected: SchoolAccountScope): Boolean =
        scopeSchoolId == expected.schoolId.value &&
            scopeAccountId == expected.accountId.value &&
            scopeProfileId == expected.profileId.value &&
            scopeCampusId == expected.campusId?.value

    private fun AutomationRuleEntity.scope(): SchoolAccountScope = SchoolAccountScope(
        schoolId = SchoolId(scopeSchoolId),
        accountId = AccountId(scopeAccountId),
        profileId = ProfileId(scopeProfileId),
        campusId = scopeCampusId?.let(::CampusId)
    )
}

class RoomAutomationRunStore(
    private val database: HautDatabase
) : AutomationRunStore {
    private val dao = database.automationDao()

    override suspend fun append(run: AutomationRun): Boolean = database.withTransaction {
        val existing = dao.getRun(run.runId)
        when {
            existing == null -> {
                dao.insertRun(run.toEntity())
                true
            }
            existing.toRun() != run -> throw AutomationRunConflictException(run.runId)
            else -> false
        }
    }

    override suspend fun get(runId: String): AutomationRun? = dao.getRun(runId)?.toRun()

    override fun runs(ruleId: String): Flow<List<AutomationRun>> = dao.observeRuns(ruleId).map { rows ->
        rows.map { it.toRun() }
    }

    private fun AutomationRun.toEntity(): AutomationRunEntity {
        val content = AutomationJsonCodec.encodeRunContent(this)
        return AutomationRunEntity(
            runId = runId,
            ruleId = ruleId,
            triggerInstanceKey = triggerInstanceKey,
            dedupeKey = dedupeKey,
            firedAtEpochMs = firedAt.toEpochMilli(),
            startedAtEpochMs = startedAt.toEpochMilli(),
            finishedAtEpochMs = finishedAt.toEpochMilli(),
            status = status.name,
            dryRun = dryRun,
            evaluationsJson = content.evaluationsJson,
            outcomesJson = content.outcomesJson,
            statusReason = statusReason,
            schemaVersion = schemaVersion
        )
    }

    private fun AutomationRunEntity.toRun(): AutomationRun = AutomationJsonCodec.decodeRunContent(
        runId = runId,
        ruleId = ruleId,
        triggerInstanceKey = triggerInstanceKey,
        dedupeKey = dedupeKey,
        firedAt = Instant.ofEpochMilli(firedAtEpochMs),
        startedAt = Instant.ofEpochMilli(startedAtEpochMs),
        finishedAt = Instant.ofEpochMilli(finishedAtEpochMs),
        status = status,
        dryRun = dryRun,
        evaluationsJson = evaluationsJson,
        outcomesJson = outcomesJson,
        statusReason = statusReason,
        schemaVersion = schemaVersion
    )
}

class RoomAutomationDedupeStore(
    private val database: HautDatabase
) : AutomationDedupeStore {
    private val dao = database.automationDao()

    override suspend fun tryAcquire(record: AutomationDedupeRecord): DedupeAcquireResult = database.withTransaction {
        val existing = dao.getDedupe(record.dedupeKey)?.toRecord()
        if (existing != null && !existing.sameTriggerIdentityAs(record)) {
            throw AutomationDedupeConflictException(record.dedupeKey)
        }
        val activePending = existing != null &&
            existing.state == AutomationDedupeState.PENDING &&
            record.acquiredAt.isBefore(existing.leaseExpiresAt)
        when {
            existing == null -> {
                dao.insertDedupe(record.toEntity())
                DedupeAcquireResult.Acquired(record)
            }
            existing.state == AutomationDedupeState.RUN_RECORDED || activePending ->
                DedupeAcquireResult.AlreadyExists(existing)
            else -> {
                dao.replaceDedupe(record.toEntity())
                DedupeAcquireResult.Acquired(record)
            }
        }
    }

    override suspend fun renewLease(
        expected: AutomationDedupeRecord,
        renewedAt: Instant,
        renewedUntil: Instant
    ): Boolean {
        if (!renewedUntil.isAfter(renewedAt)) return false
        return dao.renewDedupeLease(
            dedupeKey = expected.dedupeKey,
            executionOwner = expected.executionOwner,
            runId = expected.runId,
            triggerInstanceKey = expected.triggerInstanceKey,
            policyFingerprint = expected.policyFingerprint,
            acquiredAtEpochMs = expected.acquiredAt.toEpochMilli(),
            renewedAtEpochMs = renewedAt.toEpochMilli(),
            renewedUntilEpochMs = renewedUntil.toEpochMilli()
        ) == 1
    }

    override suspend fun markRunRecorded(expected: AutomationDedupeRecord, completedAt: Instant): Boolean {
        val existing = dao.getDedupe(expected.dedupeKey)?.toRecord() ?: return false
        if (existing.state == AutomationDedupeState.RUN_RECORDED && existing.hasSameRunIdentity(expected)) return true
        return dao.markDedupeRunRecorded(
            dedupeKey = expected.dedupeKey,
            executionOwner = expected.executionOwner,
            runId = expected.runId,
            triggerInstanceKey = expected.triggerInstanceKey,
            policyFingerprint = expected.policyFingerprint,
            acquiredAtEpochMs = expected.acquiredAt.toEpochMilli(),
            completedAtEpochMs = completedAt.toEpochMilli()
        ) == 1
    }

    override suspend fun find(dedupeKey: String): AutomationDedupeRecord? = dao.getDedupe(dedupeKey)?.toRecord()
    override suspend fun count(): Int = dao.countDedupe()

    private fun AutomationDedupeRecord.toEntity() = AutomationDedupeEntity(
        dedupeKey = dedupeKey,
        ruleId = ruleId,
        triggerInstanceKey = triggerInstanceKey,
        runId = runId,
        executionOwner = executionOwner,
        acquiredAtEpochMs = acquiredAt.toEpochMilli(),
        policyFingerprint = policyFingerprint,
        leaseExpiresAtEpochMs = leaseExpiresAt.toEpochMilli(),
        state = state.name,
        completedAtEpochMs = completedAt?.toEpochMilli()
    )

    private fun AutomationDedupeEntity.toRecord() = AutomationDedupeRecord(
        dedupeKey = dedupeKey,
        ruleId = ruleId,
        triggerInstanceKey = triggerInstanceKey,
        runId = runId,
        executionOwner = executionOwner,
        acquiredAt = Instant.ofEpochMilli(acquiredAtEpochMs),
        policyFingerprint = policyFingerprint,
        leaseExpiresAt = Instant.ofEpochMilli(leaseExpiresAtEpochMs),
        state = AutomationDedupeState.valueOf(state),
        completedAt = completedAtEpochMs?.let(Instant::ofEpochMilli)
    )

    private fun AutomationDedupeRecord.sameTriggerIdentityAs(other: AutomationDedupeRecord): Boolean =
        ruleId == other.ruleId && triggerInstanceKey == other.triggerInstanceKey &&
            policyFingerprint == other.policyFingerprint

    private fun AutomationDedupeRecord.hasSameRunIdentity(other: AutomationDedupeRecord): Boolean =
        sameTriggerIdentityAs(other) && runId == other.runId
}

class RoomAutomationActionCheckpointStore(
    private val database: HautDatabase
) : cn.edu.haut.campus.core.automation.AutomationActionCheckpointStore {
    private val dao = database.automationDao()

    override suspend fun claim(checkpoint: AutomationActionCheckpoint): ActionCheckpointClaimResult = database.withTransaction {
        val existing = dao.getCheckpoint(checkpoint.checkpointKey)?.toCheckpoint()
        if (existing != null && !existing.sameActionIdentityAs(checkpoint)) {
            throw AutomationActionCheckpointConflictException(checkpoint.checkpointKey)
        }
        when {
            existing == null -> {
                dao.insertCheckpoint(checkpoint.toEntity())
                ActionCheckpointClaimResult.Execute(checkpoint)
            }
            existing.state == AutomationActionCheckpointState.COMPLETED ->
                ActionCheckpointClaimResult.ReplayCompleted(existing)
            existing.sameExecutionAs(checkpoint) -> ActionCheckpointClaimResult.Execute(existing)
            else -> {
                val takeover = checkpoint.copy(
                    attempt = existing.attempt + 1,
                    confirmation = existing.confirmation
                )
                dao.replaceCheckpoint(takeover.toEntity())
                ActionCheckpointClaimResult.Execute(takeover)
            }
        }
    }

    override suspend fun markConfirmationConsumed(
        expected: AutomationActionCheckpoint,
        confirmation: ActionConfirmation,
        updatedAt: Instant
    ): Boolean = database.withTransaction {
        val existing = dao.getCheckpoint(expected.checkpointKey)?.toCheckpoint() ?: return@withTransaction false
        if (existing.state != AutomationActionCheckpointState.STARTED || !existing.sameExecutionAs(expected)) {
            return@withTransaction false
        }
        val prior = existing.confirmation
        if (prior != null && prior != confirmation) {
            throw AutomationActionCheckpointConflictException(expected.checkpointKey)
        }
        if (prior == confirmation) return@withTransaction true
        dao.markCheckpointConfirmation(
            checkpointKey = expected.checkpointKey,
            executionOwner = expected.executionOwner,
            attempt = expected.attempt,
            confirmationJson = AutomationJsonCodec.encodeConfirmation(confirmation),
            updatedAtEpochMs = updatedAt.toEpochMilli()
        ) == 1
    }

    override suspend fun complete(
        expected: AutomationActionCheckpoint,
        outcome: ActionOutcome,
        completedAt: Instant
    ): Boolean = database.withTransaction {
        val existing = dao.getCheckpoint(expected.checkpointKey)?.toCheckpoint() ?: return@withTransaction false
        if (existing.state == AutomationActionCheckpointState.COMPLETED &&
            existing.sameActionIdentityAs(expected) && existing.outcome == outcome
        ) {
            return@withTransaction true
        }
        if (existing.state != AutomationActionCheckpointState.STARTED || !existing.sameExecutionAs(expected)) {
            return@withTransaction false
        }
        dao.completeCheckpoint(
            checkpointKey = expected.checkpointKey,
            executionOwner = expected.executionOwner,
            attempt = expected.attempt,
            outcomeJson = AutomationJsonCodec.encodeActionOutcome(outcome),
            completedAtEpochMs = completedAt.toEpochMilli()
        ) == 1
    }

    override suspend fun find(checkpointKey: String): AutomationActionCheckpoint? =
        dao.getCheckpoint(checkpointKey)?.toCheckpoint()

    override suspend fun listForRun(runId: String): List<AutomationActionCheckpoint> =
        dao.getCheckpointsForRun(runId).map { it.toCheckpoint() }

    override suspend fun count(): Int = dao.countCheckpoints()

    private fun AutomationActionCheckpoint.toEntity() = AutomationActionCheckpointEntity(
        checkpointKey = checkpointKey,
        dedupeKey = dedupeKey,
        runId = runId,
        ruleId = ruleId,
        triggerInstanceKey = triggerInstanceKey,
        actionIndex = actionIndex,
        actionType = actionType,
        configDigest = configDigest,
        executionOwner = executionOwner,
        attempt = attempt,
        state = state.name,
        outcomeJson = outcome?.let(AutomationJsonCodec::encodeActionOutcome),
        confirmationJson = confirmation?.let(AutomationJsonCodec::encodeConfirmation),
        startedAtEpochMs = startedAt.toEpochMilli(),
        updatedAtEpochMs = updatedAt.toEpochMilli(),
        completedAtEpochMs = completedAt?.toEpochMilli()
    )

    private fun AutomationActionCheckpointEntity.toCheckpoint() = AutomationActionCheckpoint(
        checkpointKey = checkpointKey,
        dedupeKey = dedupeKey,
        runId = runId,
        ruleId = ruleId,
        triggerInstanceKey = triggerInstanceKey,
        actionIndex = actionIndex,
        actionType = actionType,
        configDigest = configDigest,
        executionOwner = executionOwner,
        attempt = attempt,
        state = AutomationActionCheckpointState.valueOf(state),
        outcome = outcomeJson?.let(AutomationJsonCodec::decodeActionOutcome),
        confirmation = confirmationJson?.let(AutomationJsonCodec::decodeConfirmation),
        startedAt = Instant.ofEpochMilli(startedAtEpochMs),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
        completedAt = completedAtEpochMs?.let(Instant::ofEpochMilli)
    )

    private fun AutomationActionCheckpoint.sameActionIdentityAs(other: AutomationActionCheckpoint): Boolean =
        checkpointKey == other.checkpointKey && dedupeKey == other.dedupeKey && runId == other.runId &&
            ruleId == other.ruleId && triggerInstanceKey == other.triggerInstanceKey &&
            actionIndex == other.actionIndex && actionType == other.actionType && configDigest == other.configDigest

    private fun AutomationActionCheckpoint.sameExecutionAs(other: AutomationActionCheckpoint): Boolean =
        sameActionIdentityAs(other) && executionOwner == other.executionOwner && attempt == other.attempt
}
