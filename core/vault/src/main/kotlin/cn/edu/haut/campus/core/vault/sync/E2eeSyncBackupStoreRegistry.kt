package cn.edu.haut.campus.core.vault.sync

import java.util.concurrent.ConcurrentHashMap

/** 按 Profile 暴露已装配的 dis-backup 同步适配器；生命周期与 VaultBackupServiceRegistry 相同。 */
object E2eeSyncBackupStoreRegistry {
    private val stores = ConcurrentHashMap<String, VaultBackupSyncStore>()

    fun register(profileId: String, store: VaultBackupSyncStore) {
        require(profileId.isNotBlank()) { "profileId 不能为空" }
        stores.putIfAbsent(profileId, store)
    }

    fun forProfile(profileId: String): VaultBackupSyncStore? = stores[profileId]

    internal fun clearForTest() {
        stores.clear()
    }
}
