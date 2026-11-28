package cn.edu.haut.campus.core.vault.sync

import cn.edu.haut.campus.core.vault.BackupTablePolicy

/**
 * 同步复制在 dis-backup 可移植表上的进一步收窄。
 *
 * 备份导出可以携带这些行用于同一设备灾难恢复；跨设备持续复制不得复制设备局部自动化执行态，
 * 否则两台设备会把同一抢座/专注计时当成两个独立 owner 重复执行。将来如需协同执行，必须先引入
 * 显式 device owner、lease、幂等执行归属与跨设备冲突协议，不能用普通行同步蒙混。
 */
internal object SyncBackupTableSelection {
    private val excludedReasons: Map<String, String> = mapOf(
        "library_booking_plans" to "图书馆预约执行状态/generation/cloudJobId 是设备局部自动化 lease，不是可安全复制的用户事实",
        "focus_sessions" to "专注会话运行态由本机调度拥有，跨设备复制会重复计时/提醒执行"
    )

    fun isIncluded(table: String): Boolean = table !in excludedReasons

    fun includedPolicies(policies: List<BackupTablePolicy>): List<BackupTablePolicy> =
        policies.filter { isIncluded(it.table) }

    fun requireIncluded(table: String) {
        val reason = excludedReasons[table]
        if (reason != null) throw E2eeSyncCryptoException("该表禁止跨设备同步复制: " + table + "；" + reason)
    }

    fun excludedSummary(): String = excludedReasons.entries.joinToString("；") { (table, reason) -> table + "=" + reason }
}
