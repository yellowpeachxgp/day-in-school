package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * 课程/考试修复回归测试共用的 in-memory fixture：
 * 独立 Room 实例 + 临时 DataStore + 合成账号 Profile，绝不访问真实学校或网络。
 */
internal data class RepairTestFixture(
    val database: HautDatabase,
    val repository: CampusRepository,
    val dataStore: DataStore<Preferences>,
    val profile: AccountProfileEntity,
    private val storeFile: File
) {
    val dao: CampusDao get() = database.campusDao()

    fun close() {
        drainRepositoryStartupJobsForTest()
        database.close()
        runCatching { storeFile.delete() }
    }

    /**
     * CampusRepository 初始化时会向私有 repoScope 投递一次 null-literal 清洗。
     * 单测 fixture 生命周期极短，若直接 close Room，清洗协程可能与 close 形成
     * SQLiteOpenHelper/InvalidationTracker 死锁（devbox quick 已实证）。测试-only
     * 反射只在销毁前排空该启动子任务；生产 API 与行为不变。
     */
    private fun drainRepositoryStartupJobsForTest() {
        runCatching {
            val field = CampusRepository::class.java.getDeclaredField("repoScope")
            field.isAccessible = true
            val scope = field.get(repository) as CoroutineScope
            val rootJob = scope.coroutineContext[Job] ?: return@runCatching
            val children = rootJob.children.toList()
            runBlocking {
                withTimeoutOrNull(2_000L) {
                    children.forEach { it.join() }
                }
                children.filter { it.isActive }.forEach { job ->
                    job.cancel()
                    withTimeoutOrNull(1_000L) { job.join() }
                }
            }
        }
    }

    companion object {
        private var counter = 0

        fun create(
            context: Context,
            profileSeed: String = "prof_repair_test",
            isLegacy: Boolean = false
        ): RepairTestFixture {
            val n = ++counter
            val database = Room.inMemoryDatabaseBuilder(context, HautDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val storeFile = File.createTempFile("haut_repair_test_${n}_", ".preferences_pb")
            storeFile.deleteOnExit()
            val dataStore = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                produceFile = { storeFile }
            )
            val storage = isolatedAccountStoragePartition(profileSeed)
            val profile = AccountProfileEntity(
                id = profileSeed,
                accountId = "acct_${profileSeed}",
                schoolId = "haut",
                schoolName = "河南工业大学",
                province = "河南省",
                city = "郑州市",
                campusId = "lianhuajie",
                campusName = "莲花街校区",
                timeZoneId = "Asia/Shanghai",
                label = "合成测试账号",
                isLegacy = isLegacy,
                databaseName = storage.databaseName,
                preferencesName = storage.preferencesName,
                createdAtEpochMs = 1_000L
            )
            val repository = CampusRepository(
                appContext = context,
                database = database,
                preferences = dataStore,
                accountProfile = profile
            )
            repository.activateSession(1L)
            return RepairTestFixture(database, repository, dataStore, profile, storeFile)
        }
    }
}

/**
 * 恢复命令测试构造器：以当前 DAO 快照构造与旧适配器选择等价的不可变 RestoreCommand
 * （overrideId 指定的纯覆盖行按暂停恢复、规则关联行带 moveId 的归入调课组整组选择、
 * 规则+日期命中的停课行逐行选择），再经 executeRestoreCommand 单事务执行。
 * 测试侧等价于 UI 点击瞬间冻结快照——与生产路径的「点击时固化」同一时序。
 */
internal suspend fun restoreSelectionFromState(
    dao: CampusDao,
    repository: CampusRepository,
    termId: String,
    date: LocalDate,
    ruleId: String? = null,
    overrideId: String? = null,
    mergedRuleIds: List<String> = emptyList()
): CampusRepository.OccurrenceCommandResult {
    val snapshot = dao.getOccurrenceOverridesSnapshot()
    val allRuleIds = (mergedRuleIds + listOfNotNull(ruleId)).distinct().filter { it.isNotBlank() }
    val dateIso = date.toString()
    val selectedRows = LinkedHashMap<String, CampusRepository.SelectedOverride>()
    val selectedMoves = LinkedHashMap<String, CampusRepository.SelectedMove>()

    fun selectMove(moveId: String) {
        if (moveId !in selectedMoves) {
            selectedMoves[moveId] = CampusRepository.SelectedMove(
                moveId = moveId,
                expectedMembers = snapshot
                    .filter { it.termId == termId && it.moveId == moveId }
                    .map { CampusRepository.RowIdentity(it.id, it.mutationToken) }
            )
        }
    }

    fun selectRow(row: OccurrenceOverrideEntity) {
        selectedRows[row.id] = CampusRepository.SelectedOverride(
            id = row.id,
            mutationToken = row.mutationToken,
            ruleId = row.courseRuleId,
            dateIso = row.dateIso,
            expectedMove = row.moveId?.takeIf { it.isNotBlank() }
                ?.let { CampusRepository.ExpectedMove.Move(it) }
                ?: CampusRepository.ExpectedMove.NoMove,
            role = row.moveRole
        )
    }

    if (!overrideId.isNullOrBlank()) {
        snapshot.firstOrNull { it.id == overrideId }?.let { existing ->
            if (existing.courseRuleId == null) {
                selectRow(existing)
            } else if (!existing.moveId.isNullOrBlank()) {
                selectMove(existing.moveId!!)
            } else {
                selectRow(existing)
            }
        }
    }
    for (rId in allRuleIds) {
        for (row in snapshot.filter {
            it.dateIso == dateIso && it.courseRuleId == rId && it.canceled
        }) {
            if (!row.moveId.isNullOrBlank()) {
                selectMove(row.moveId!!)
            } else {
                selectRow(row)
            }
        }
    }
    return repository.executeRestoreCommand(
        CampusRepository.RestoreCommand(
            commandId = "test-" + UUID.randomUUID().toString(),
            accountProfileId = repository.profileId,
            termId = termId,
            intent = CampusRepository.RestoreIntent.RESTORE_SELECTION,
            selectedRows = selectedRows.values.toList(),
            selectedMoves = selectedMoves.values.toList()
        )
    )
}
