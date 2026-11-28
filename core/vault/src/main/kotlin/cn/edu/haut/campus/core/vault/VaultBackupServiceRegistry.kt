package cn.edu.haut.campus.core.vault

import java.util.concurrent.ConcurrentHashMap

/** 按 Profile 暴露当前进程已装配的备份服务；服务实例与 AccountDatabaseFactory 的账号库实例同生命周期。 */
object VaultBackupServiceRegistry {
    private val services = ConcurrentHashMap<String, VaultBackupService>()

    internal fun register(profileId: String, service: VaultBackupService) {
        require(profileId.isNotBlank()) { "profileId 不能为空" }
        services.putIfAbsent(profileId, service)
    }

    fun forProfile(profileId: String): VaultBackupService? = services[profileId]

    internal fun clearForTest() {
        services.clear()
    }
}
