package cn.edu.haut.campus.core.automation

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 规则读取端口。Room 持久化由 W2-02 实现；本模块提供内存实现用于核心测试与未来的纯 JVM 工具。 */
interface AutomationRuleStore {
    suspend fun get(ruleId: String): AutomationRule?
    suspend fun list(): List<AutomationRule>
}

interface MutableAutomationRuleStore : AutomationRuleStore {
    suspend fun upsert(rule: AutomationRule)
    suspend fun delete(ruleId: String): Boolean
}

class InMemoryAutomationRuleStore : MutableAutomationRuleStore {
    private val mutex = Mutex()
    private val rules = LinkedHashMap<String, AutomationRule>()

    override suspend fun get(ruleId: String): AutomationRule? = mutex.withLock { rules[ruleId] }

    override suspend fun list(): List<AutomationRule> = mutex.withLock { rules.values.toList() }

    override suspend fun upsert(rule: AutomationRule) {
        mutex.withLock { rules[rule.id] = rule }
    }

    override suspend fun delete(ruleId: String): Boolean = mutex.withLock { rules.remove(ruleId) != null }
}

class AutomationRunConflictException(runId: String) :
    IllegalStateException("runId 已存在但运行记录内容不同: " + runId)

/** 运行记录端口。append 按 runId 幂等；同一 runId 内容冲突 fail-closed，语义与 core/events outbox 一致。 */
interface AutomationRunStore {
    /** 返回 true 表示新追加；false 表示完全相同记录已存在。 */
    suspend fun append(run: AutomationRun): Boolean
    suspend fun get(runId: String): AutomationRun?
    fun runs(ruleId: String): Flow<List<AutomationRun>>
}

class InMemoryAutomationRunStore : AutomationRunStore {
    private val mutex = Mutex()
    private val byId = LinkedHashMap<String, AutomationRun>()
    private val allRuns = MutableStateFlow<List<AutomationRun>>(emptyList())

    override suspend fun append(run: AutomationRun): Boolean {
        var inserted = false
        mutex.withLock {
            val existing = byId[run.runId]
            when {
                existing == null -> {
                    byId[run.runId] = run
                    allRuns.value = byId.values.toList()
                    inserted = true
                }
                existing != run -> throw AutomationRunConflictException(run.runId)
                else -> inserted = false
            }
        }
        return inserted
    }

    override suspend fun get(runId: String): AutomationRun? = mutex.withLock { byId[runId] }

    override fun runs(ruleId: String): Flow<List<AutomationRun>> = allRuns.map { runs ->
        runs.filter { it.ruleId == ruleId }
            .sortedWith(compareByDescending<AutomationRun> { it.firedAt }.thenBy { it.runId })
    }
}

sealed interface DedupeAcquireResult {
    data class Acquired(val record: AutomationDedupeRecord) : DedupeAcquireResult
    data class AlreadyExists(val existing: AutomationDedupeRecord) : DedupeAcquireResult
}

class AutomationDedupeConflictException(dedupeKey: String) :
    IllegalStateException("dedupeKey 已存在但触发实例身份或策略不一致: " + dedupeKey)

class AutomationDedupeCompletionException(dedupeKey: String) :
    IllegalStateException("dedupe PENDING 记录在运行结束前失去所有权: " + dedupeKey)

/**
 * 去重端口。tryAcquire 必须是原子 check-and-insert/reacquire：
 * RUN_RECORDED 或 lease 未过期的 PENDING 会直接判重；lease 过期的 PENDING 允许崩溃恢复重投原子替换。
 * markRunRecorded 必须按获取到的整条 PENDING 记录做 compare-and-set，避免过期恢复后的旧执行体错误关闭新执行窗口。
 * 终态转换本身幂等：目标 runId 与触发实例身份相同的 RUN_RECORDED 记录视为已完成并返回 true；
 * 归属不同实例、运行身份矛盾或所有权已被新 PENDING 窗口取代时返回 false。
 */
interface AutomationDedupeStore {
    suspend fun tryAcquire(record: AutomationDedupeRecord): DedupeAcquireResult
    suspend fun markRunRecorded(expected: AutomationDedupeRecord, completedAt: Instant): Boolean
    suspend fun find(dedupeKey: String): AutomationDedupeRecord?
    suspend fun count(): Int
}

internal fun AutomationDedupeRecord.hasSameRunIdentityAs(other: AutomationDedupeRecord): Boolean =
    dedupeKey == other.dedupeKey &&
        ruleId == other.ruleId &&
        triggerInstanceKey == other.triggerInstanceKey &&
        runId == other.runId &&
        policyFingerprint == other.policyFingerprint

class InMemoryAutomationDedupeStore : AutomationDedupeStore {
    private val mutex = Mutex()
    private val records = LinkedHashMap<String, AutomationDedupeRecord>()

    override suspend fun tryAcquire(record: AutomationDedupeRecord): DedupeAcquireResult = mutex.withLock {
        val existing = records[record.dedupeKey]
        if (existing != null && !existing.sameTriggerIdentityAs(record)) {
            throw AutomationDedupeConflictException(record.dedupeKey)
        }
        val activePending = existing != null &&
            existing.state == AutomationDedupeState.PENDING &&
            record.acquiredAt.isBefore(existing.leaseExpiresAt)
        when {
            existing == null -> {
                records[record.dedupeKey] = record
                DedupeAcquireResult.Acquired(record)
            }
            existing != null && (existing.state == AutomationDedupeState.RUN_RECORDED || activePending) ->
                DedupeAcquireResult.AlreadyExists(existing)
            else -> {
                records[record.dedupeKey] = record
                DedupeAcquireResult.Acquired(record)
            }
        }
    }

    override suspend fun markRunRecorded(expected: AutomationDedupeRecord, completedAt: Instant): Boolean = mutex.withLock {
        val existing = records[expected.dedupeKey]
        val alreadyRecordedForSameRun = existing != null &&
            existing.state == AutomationDedupeState.RUN_RECORDED &&
            existing.hasSameRunIdentityAs(expected)
        if (existing != expected || existing.state != AutomationDedupeState.PENDING) {
            alreadyRecordedForSameRun
        } else {
            records[expected.dedupeKey] = expected.copy(
                state = AutomationDedupeState.RUN_RECORDED,
                completedAt = completedAt
            )
            true
        }
    }

    override suspend fun find(dedupeKey: String): AutomationDedupeRecord? = mutex.withLock { records[dedupeKey] }

    override suspend fun count(): Int = mutex.withLock { records.size }

    private fun AutomationDedupeRecord.sameTriggerIdentityAs(other: AutomationDedupeRecord): Boolean =
        ruleId == other.ruleId &&
            triggerInstanceKey == other.triggerInstanceKey &&
            policyFingerprint == other.policyFingerprint
}
