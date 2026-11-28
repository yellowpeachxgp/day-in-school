package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.model.SchoolAccountScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * guide 进度持久化 Port。core:guide 只定义可恢复快照契约；
 * Android/Room 实现在后续 feature 层接入，测试与纯 JVM 调用方可使用内存实现。
 */
interface GuideProgressStore {
    suspend fun saveProcedureRun(snapshot: ProcedureRunSnapshot)
    suspend fun loadProcedureRun(scope: SchoolAccountScope, packId: String, itemId: String): ProcedureRunSnapshot?
    suspend fun deleteProcedureRun(scope: SchoolAccountScope, packId: String, itemId: String)
    fun observeProcedureRuns(scope: SchoolAccountScope): Flow<List<ProcedureRunSnapshot>>

    suspend fun saveOpportunityProgress(snapshot: OpportunityProgressSnapshot)
    suspend fun loadOpportunityProgress(scope: SchoolAccountScope, packId: String, itemId: String): OpportunityProgressSnapshot?
    suspend fun deleteOpportunityProgress(scope: SchoolAccountScope, packId: String, itemId: String)
    fun observeOpportunityProgress(scope: SchoolAccountScope): Flow<List<OpportunityProgressSnapshot>>
}

/** 进程内存实现，快照均按 (scope, packId, itemId) 隔离；不会跨账号或学校合并。 */
class InMemoryGuideProgressStore : GuideProgressStore {
    private data class ProcedureKey(
        val scope: SchoolAccountScope,
        val packId: String,
        val itemId: String
    )

    private data class OpportunityKey(
        val scope: SchoolAccountScope,
        val packId: String,
        val itemId: String
    )

    private val mutex = Mutex()
    private val procedureRuns = MutableStateFlow<Map<ProcedureKey, ProcedureRunSnapshot>>(emptyMap())
    private val opportunityProgress = MutableStateFlow<Map<OpportunityKey, OpportunityProgressSnapshot>>(emptyMap())

    override suspend fun saveProcedureRun(snapshot: ProcedureRunSnapshot) {
        mutex.withLock {
            val key = ProcedureKey(snapshot.scope, snapshot.packId, snapshot.itemId)
            procedureRuns.value = procedureRuns.value + (key to snapshot)
        }
    }

    override suspend fun loadProcedureRun(
        scope: SchoolAccountScope,
        packId: String,
        itemId: String
    ): ProcedureRunSnapshot? = procedureRuns.value[ProcedureKey(scope, packId, itemId)]

    override suspend fun deleteProcedureRun(scope: SchoolAccountScope, packId: String, itemId: String) {
        mutex.withLock {
            procedureRuns.value = procedureRuns.value - ProcedureKey(scope, packId, itemId)
        }
    }

    override fun observeProcedureRuns(scope: SchoolAccountScope): Flow<List<ProcedureRunSnapshot>> =
        procedureRuns.map { runs ->
            runs.values.filter { it.scope == scope }.sortedWith(
                compareBy<ProcedureRunSnapshot> { it.packId }.thenBy { it.itemId }
            )
        }

    override suspend fun saveOpportunityProgress(snapshot: OpportunityProgressSnapshot) {
        mutex.withLock {
            val key = OpportunityKey(snapshot.scope, snapshot.packId, snapshot.itemId)
            opportunityProgress.value = opportunityProgress.value + (key to snapshot)
        }
    }

    override suspend fun loadOpportunityProgress(
        scope: SchoolAccountScope,
        packId: String,
        itemId: String
    ): OpportunityProgressSnapshot? = opportunityProgress.value[OpportunityKey(scope, packId, itemId)]

    override suspend fun deleteOpportunityProgress(scope: SchoolAccountScope, packId: String, itemId: String) {
        mutex.withLock {
            opportunityProgress.value = opportunityProgress.value - OpportunityKey(scope, packId, itemId)
        }
    }

    override fun observeOpportunityProgress(scope: SchoolAccountScope): Flow<List<OpportunityProgressSnapshot>> =
        opportunityProgress.map { progress ->
            progress.values.filter { it.scope == scope }.sortedWith(
                compareBy<OpportunityProgressSnapshot> { it.packId }.thenBy { it.itemId }
            )
        }
}
