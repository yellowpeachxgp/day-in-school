package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import cn.edu.haut.campus.core.campusservices.SchoolWebSessionManager
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.SchoolProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ActiveSession(
    val profileId: String,
    val generation: Long,
    val repository: CampusRepository,
    val school: SchoolProfile,
    val label: String
)

sealed interface AccountSessionState {
    data object Loading : AccountSessionState
    data class Switching(val targetProfileId: String) : AccountSessionState
    data class Ready(val active: ActiveSession) : AccountSessionState
    data class Failure(val message: String) : AccountSessionState
}

/** 全进程账号生命周期入口；切换失败时不恢复可能仍含旧网页身份的界面。 */
class AccountSessionCoordinator private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val registry = Room.databaseBuilder(appContext, GlobalProfileRegistry::class.java, "campus_profile_registry.db").build()
    private val dao = registry.profiles()
    private val factory = AccountDatabaseFactory(appContext)
    private val mutex = Mutex()
    private val mutableStates = MutableStateFlow<AccountSessionState>(AccountSessionState.Loading)
    private data class Hook(
        val before: suspend (ActiveSession?) -> Unit,
        val after: suspend (ActiveSession) -> Unit
    )
    private val hooks = ConcurrentHashMap<String, Hook>()
    private var boundSession: ActiveSession? = null

    val states: StateFlow<AccountSessionState> = mutableStates.asStateFlow()
    val profiles: Flow<List<AccountProfile>> = dao.observeProfiles().map { rows -> rows.map { it.toDomain() } }

    fun currentSnapshot(): ActiveSession? = (states.value as? AccountSessionState.Ready)
        ?.active
        ?.takeIf { it.repository.isSessionActive && it.repository.sessionGeneration == it.generation }
    fun peekActiveSession(): ActiveSession? = currentSnapshot()

    fun registerLifecycleHook(
        key: String,
        beforeSwitch: suspend (ActiveSession?) -> Unit,
        afterSwitch: suspend (ActiveSession) -> Unit
    ) {
        hooks[key] = Hook(beforeSwitch, afterSwitch)
    }

    suspend fun initialize(): ActiveSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentSnapshot()?.let { return@withLock it }
            var candidate: CampusRepository? = null
            try {
                ensureLegacyAdopted()
                val active = checkNotNull(dao.active()) { "缺少当前账号记录" }
                val profile = checkNotNull(dao.profile(active.profileId)) { "当前账号记录不可用" }
                val repository = factory.open(profile).also { candidate = it }
                repository.activateSession(active.generation)
                repository.initializeForSession()
                val session = ActiveSession(profile.id, active.generation, repository, profile.school(), profile.label)
                boundSession = session
                for (hook in hooks.toSortedMap().values) hook.after(session)
                mutableStates.value = AccountSessionState.Ready(session)
                session
            } catch (error: Throwable) {
                candidate?.deactivateSession()
                boundSession?.repository?.deactivateSession()
                boundSession = null
                mutableStates.value = AccountSessionState.Failure("账号数据初始化失败，原有数据已保留，请重试")
                if (error is CancellationException || error is Error) throw error
                throw IllegalStateException("账号数据初始化失败，原有数据已保留", error)
            }
        }
    }

    suspend fun createOfflineProfile(school: SchoolProfile, label: String): AccountProfile = withContext(Dispatchers.IO) {
        require(label.isNotBlank() && label.length <= 80) { "请输入不超过 80 字的身份名称" }
        val id = UUID.randomUUID().toString()
        val storage = isolatedAccountStoragePartition(id)
        val profile = AccountProfileEntity(
            id = id, accountId = UUID.randomUUID().toString(), schoolId = school.id.value,
            schoolName = school.displayName, province = school.province, city = school.city,
            campusId = school.primaryCampus?.id?.value, campusName = school.primaryCampus?.name,
            timeZoneId = school.primaryCampus?.timeZone?.id ?: "Asia/Shanghai", label = label.trim(),
            isLegacy = false, databaseName = storage.databaseName, preferencesName = storage.preferencesName,
            createdAtEpochMs = System.currentTimeMillis()
        )
        mutex.withLock {
            check(currentSnapshot() != null) { "请等待账号初始化完成" }
            dao.insertProfile(profile)
        }
        profile.toDomain()
    }

    suspend fun switchProfile(profileId: String): ActiveSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = checkNotNull(currentSnapshot()) { "账号尚未就绪，请先重新初始化当前身份" }
            check(boundSession === old) { "账号会话状态不一致，已停止切换" }
            if (old.profileId == profileId) return@withLock old
            val profile = requireNotNull(dao.profile(profileId)) { "目标账号不存在" }
            mutableStates.value = AccountSessionState.Switching(profileId)
            old.repository.deactivateSession()
            var candidate: CampusRepository? = null
            try {
                for (hook in hooks.toSortedMap().values) hook.before(old)
                old.repository.awaitSessionOperations()
                SchoolWebSessionManager.clearForProfileSwitch()
                val persisted = checkNotNull(dao.active()) { "缺少账号状态" }
                val generation = Math.addExact(persisted.generation, 1)
                val repository = factory.open(profile).also { candidate = it }
                repository.activateSession(generation)
                repository.initializeForSession()
                val session = ActiveSession(profile.id, generation, repository, profile.school(), profile.label)
                registry.withTransaction { dao.saveActive(ActiveProfileEntity(profileId = profile.id, generation = generation)) }
                boundSession = session
                for (hook in hooks.toSortedMap().values) hook.after(session)
                mutableStates.value = AccountSessionState.Ready(session)
                session
            } catch (error: Throwable) {
                candidate?.deactivateSession()
                boundSession?.repository?.deactivateSession()
                boundSession = null
                mutableStates.value = AccountSessionState.Failure("账号切换未完成，已停止新请求；数据已保留，请重新选择账号")
                if (error is CancellationException || error is Error) throw error
                throw IllegalStateException("账号切换未完成，数据已保留", error)
            }
        }
    }

    suspend fun getRepository(profileId: String): CampusRepository = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentSnapshot()?.takeIf { it.profileId == profileId }?.repository
                ?: error("账号正在初始化或切换，请等待当前身份就绪")
        }
    }

    /** 校验与操作共享切换锁，旧闹钟/Worker 不可在通过校验后跨入新会话。 */
    suspend fun <T> withActiveSession(
        profileId: String,
        generation: Long,
        block: suspend (ActiveSession) -> T
    ): T? = mutex.withLock {
        val active = currentSnapshot()?.takeIf { it.profileId == profileId && it.generation == generation }
            ?: return@withLock null
        block(active)
    }

    private suspend fun ensureLegacyAdopted() {
        var profileToAdopt: AccountProfileEntity? = null
        registry.withTransaction {
            val journal = dao.journal(LEGACY_ADOPTION_JOURNAL_ID)
            if (journal?.status == ADOPTION_COMPLETE) {
                val profile = checkNotNull(dao.profile(journal.profileId)) { "已采用 Profile 的注册记录缺失" }
                check(profile.isLegacy) { "已采用的 Profile 类型无效" }
                val currentVersion = factory.currentSchemaVersion(profile)
                check(
                    journal.adoptedSchemaVersion > 0 &&
                        currentVersion > 0 &&
                        currentVersion >= journal.adoptedSchemaVersion
                ) {
                    "已采用数据库缺失或发生非预期降级"
                }
                return@withTransaction
            }
            val profiles = dao.allProfiles()
            val legacyProfiles = dao.legacyProfiles()
            var createLegacy = false
            val legacy = if (journal != null) {
                check(journal.status in setOf(ADOPTION_REGISTERED, ADOPTION_SOURCE_CAPTURED)) {
                    "未知的旧库 adoption 状态"
                }
                checkNotNull(dao.profile(journal.profileId)) { "待采用 Profile 的注册记录缺失" }
                    .also { check(it.isLegacy) { "待采用 Profile 类型无效" } }
            } else {
                check(profiles.isEmpty() || legacyProfiles.size == 1) {
                    "Profile registry 缺少可验证的旧库 adoption 记录"
                }
                legacyProfiles.singleOrNull() ?: run {
                    createLegacy = true
                    val school = SchoolDirectory.hautProfile()
                    AccountProfileEntity(
                        id = UUID.randomUUID().toString(), accountId = UUID.randomUUID().toString(),
                        schoolId = school.id.value, schoolName = school.displayName,
                        province = school.province, city = school.city,
                        campusId = school.primaryCampus?.id?.value, campusName = school.primaryCampus?.name,
                        timeZoneId = "Asia/Shanghai", label = "原有身份", isLegacy = true,
                        databaseName = LEGACY_DATABASE_NAME, preferencesName = LEGACY_PREFERENCES_NAME,
                        createdAtEpochMs = System.currentTimeMillis()
                    )
                }
            }
            val active = dao.active()
            if (active == null) {
                check(createLegacy) { "Profile registry 的当前账号记录缺失" }
                dao.insertProfile(legacy)
                dao.saveActive(ActiveProfileEntity(profileId = legacy.id, generation = 1))
                dao.saveJournal(ProfileAdoptionJournalEntity(
                    LEGACY_ADOPTION_JOURNAL_ID, legacy.id, legacy.databaseName,
                    0, 0, "", ADOPTION_REGISTERED, System.currentTimeMillis()
                ))
            } else {
                check(active.profileId == legacy.id && active.generation > 0) {
                    "旧库 Profile 不是当前账号，已停止自动 adoption"
                }
                if (journal == null) {
                    dao.saveJournal(ProfileAdoptionJournalEntity(
                        LEGACY_ADOPTION_JOURNAL_ID, legacy.id, legacy.databaseName,
                        0, 0, "", ADOPTION_REGISTERED, System.currentTimeMillis()
                    ))
                }
            }
            profileToAdopt = legacy
        }
        val legacy = profileToAdopt ?: return
        val source = registry.withTransaction {
            val journal = checkNotNull(dao.journal(LEGACY_ADOPTION_JOURNAL_ID))
            check(journal.profileId == legacy.id && journal.sourceDatabase == legacy.databaseName) {
                "旧库 adoption 记录与 Profile 不一致"
            }
            if (journal.status == ADOPTION_SOURCE_CAPTURED || journal.sourceSchemaVersion > 0) {
                AccountDatabaseFactory.LegacySnapshot(
                    oldVersion = journal.sourceSchemaVersion,
                    tableCounts = decodeAdoptionTableCounts(journal.originalTableCounts)
                )
            } else {
                val inspected = factory.inspectLegacy(legacy)
                dao.saveJournal(journal.copy(
                    sourceSchemaVersion = inspected.oldVersion,
                    adoptedSchemaVersion = 0,
                    originalTableCounts = inspected.serializedTableCounts,
                    status = ADOPTION_SOURCE_CAPTURED,
                    recordedAtEpochMs = System.currentTimeMillis()
                ))
                inspected
            }
        }
        val evidence = factory.openAndVerifyLegacy(legacy, source)
        registry.withTransaction {
            val journal = checkNotNull(dao.journal(LEGACY_ADOPTION_JOURNAL_ID))
            check(journal.profileId == legacy.id && journal.sourceDatabase == legacy.databaseName) {
                "旧库 adoption 记录在迁移期间发生变化"
            }
            check(journal.sourceSchemaVersion == evidence.oldVersion) {
                "旧库 adoption 源版本在迁移期间发生变化"
            }
            if (journal.status != ADOPTION_COMPLETE) {
                dao.saveJournal(journal.copy(
                    adoptedSchemaVersion = evidence.newVersion,
                    originalTableCounts = evidence.serializedTableCounts,
                    status = ADOPTION_COMPLETE,
                    recordedAtEpochMs = System.currentTimeMillis()
                ))
            }
        }
    }

    companion object {
        @Volatile private var instance: AccountSessionCoordinator? = null
        fun getInstance(context: Context): AccountSessionCoordinator = instance ?: synchronized(this) {
            instance ?: AccountSessionCoordinator(context).also { instance = it }
        }
    }
}

internal fun decodeAdoptionTableCounts(serialized: String): Map<String, Long> {
    if (serialized.isBlank()) return emptyMap()
    require(!serialized.endsWith('\n') && !serialized.endsWith('\r')) {
        "旧库 adoption 计数记录包含空行"
    }
    val counts = linkedMapOf<String, Long>()
    serialized.lineSequence().forEach { line ->
        val separator = line.lastIndexOf('=')
        require(separator > 0 && separator < line.lastIndex) { "旧库 adoption 计数记录无效" }
        val table = line.substring(0, separator)
        require(table.all { it.isLetterOrDigit() || it == '_' }) { "旧库 adoption 表名无效" }
        val count = requireNotNull(line.substring(separator + 1).toLongOrNull()) {
            "旧库 adoption 行数记录无效"
        }
        require(count >= 0) { "旧库 adoption 行数不能为负" }
        check(table !in counts) { "旧库 adoption 计数记录重复" }
        counts[table] = count
    }
    return counts
}
