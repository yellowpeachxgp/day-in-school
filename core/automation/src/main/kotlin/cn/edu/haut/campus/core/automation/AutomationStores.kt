package cn.edu.haut.campus.core.automation

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

/** 去重端口。tryAcquire 必须是原子 check-and-insert；并发同一 dedupeKey 只能有一个 Acquired。 */
interface AutomationDedupeStore {
    suspend fun tryAcquire(record: AutomationDedupeRecord): DedupeAcquireResult
    suspend fun find(dedupeKey: String): AutomationDedupeRecord?
    suspend fun count(): Int
}

class InMemoryAutomationDedupeStore : AutomationDedupeStore {
    private val mutex = Mutex()
    private val records = LinkedHashMap<String, AutomationDedupeRecord>()

    override suspend fun tryAcquire(record: AutomationDedupeRecord): DedupeAcquireResult = mutex.withLock {
        val existing = records[record.dedupeKey]
        if (existing != null) {
            DedupeAcquireResult.AlreadyExists(existing)
        } else {
            records[record.dedupeKey] = record
            DedupeAcquireResult.Acquired(record)
        }
    }

    override suspend fun find(dedupeKey: String): AutomationDedupeRecord? = mutex.withLock { records[dedupeKey] }

    override suspend fun count(): Int = mutex.withLock { records.size }
}
