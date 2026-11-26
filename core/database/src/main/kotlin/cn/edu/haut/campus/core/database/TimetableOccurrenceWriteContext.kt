package cn.edu.haut.campus.core.database

import java.util.UUID

/**
 * occurrence_overrides 统一写入上下文：编辑入口与恢复命令共享同一令牌盖印和测试 hook。
 * 该对象不改变并发/事务语义，只把原 CampusRepository 私有写入口下沉为内部共享 seam。
 */
internal class TimetableOccurrenceWriteContext(
    private val environment: CampusRepositoryEnvironment
) {
    private val dao = environment.dao

    /** 恢复/撤销命令事务内测试 hook；生产恒为 null。 */
    internal var occurrenceCommandInTransactionHook: (suspend (command: String) -> Unit)? = null

    /** 恢复/撤销命令逐行写入进度测试 hook；生产恒为 null。 */
    internal var occurrenceCommandWriteProgressHook: (suspend (command: String, rowId: String) -> Unit)? = null

    /**
     * occurrence_overrides 统一写入口：任何路径的每一次真实写入都必须经此函数盖印
     * 全新不可复用 mutationToken（随机 UUID）；跳过写入的纯重复/冲突路径自然保留旧令牌。
     * 恢复/撤销命令以点击时捕获的令牌逐条断言身份——同 ID 重建、改挂调课、编辑、暂停
     * 等一切改写都会使旧命令判 CONFLICT，绝不误删替换后的新行。
     */
    internal suspend fun upsertOccurrenceOverrideStamped(entity: OccurrenceOverrideEntity) {
        dao.upsertOccurrenceOverride(entity.copy(mutationToken = UUID.randomUUID().toString()))
    }

    internal suspend fun notifyScheduleOrPolicyChanged(reason: String) {
        environment.notifyScheduleOrPolicyChanged(reason)
    }
}
