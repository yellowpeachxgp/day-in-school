package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.SourceRef

/**
 * core:database 对 Vault EntityWriter 的依赖反转端口。
 *
 * database 模块不能反向依赖 core:vault；生产运行时由 VaultRuntimeInstaller 注册工厂，
 * 纯 database 单测或未安装 Vault 时使用 Direct 实现，行为与接入前完全一致（只执行原写入块）。
 */
interface AcademicEntityWritePort {
    suspend fun <T> write(
        ref: EntityRef,
        actor: Actor,
        actorDetail: String?,
        source: SourceRef?,
        writeGroupId: String?,
        block: suspend () -> T
    ): T

    /**
     * 批量实体写入边界。refsProvider 必须在实现的事务内、任何业务写入前求值；
     * 这允许调用方把“当前全部课程/成绩/考试”作为过宽但安全的审计候选集。
     */
    suspend fun <T> writeMany(
        refsProvider: suspend () -> List<EntityRef>,
        actor: Actor,
        actorDetail: String? = null,
        source: SourceRef? = null,
        writeGroupId: String? = null,
        block: suspend () -> T
    ): T
}

/** 未安装 Vault 时的直通实现；不吞异常、不改变调用方事务形状。 */
object DirectAcademicEntityWritePort : AcademicEntityWritePort {
    override suspend fun <T> write(
        ref: EntityRef,
        actor: Actor,
        actorDetail: String?,
        source: SourceRef?,
        writeGroupId: String?,
        block: suspend () -> T
    ): T = block()

    override suspend fun <T> writeMany(
        refsProvider: suspend () -> List<EntityRef>,
        actor: Actor,
        actorDetail: String?,
        source: SourceRef?,
        writeGroupId: String?,
        block: suspend () -> T
    ): T {
        refsProvider()
        return block()
    }
}

fun interface AcademicEntityWritePortFactory {
    fun create(database: HautDatabase, accountProfile: AccountProfileEntity): AcademicEntityWritePort
}

/** 进程级组合根注册点；只允许安装一个工厂，避免多模块静默争夺实体写入边界。 */
object AcademicEntityWritePortRegistry {
    private val lock = Any()
    @Volatile
    private var factory: AcademicEntityWritePortFactory? = null

    fun installFactory(factory: AcademicEntityWritePortFactory) {
        synchronized(lock) {
            val existing = this.factory
            check(existing == null || existing === factory) { "AcademicEntityWritePortFactory 已安装，禁止重复覆盖" }
            this.factory = factory
        }
    }

    internal fun create(database: HautDatabase, accountProfile: AccountProfileEntity): AcademicEntityWritePort =
        factory?.create(database, accountProfile) ?: DirectAcademicEntityWritePort

    internal fun resetForTest() {
        synchronized(lock) { factory = null }
    }
}
