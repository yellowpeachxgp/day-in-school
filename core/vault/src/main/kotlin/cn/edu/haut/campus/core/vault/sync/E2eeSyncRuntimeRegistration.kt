package cn.edu.haut.campus.core.vault.sync

import android.content.Context
import cn.edu.haut.campus.core.database.CampusRepository
import java.io.File
import kotlinx.coroutines.withTimeoutOrNull

/** 全量擦除链路入口。网络撤销只是 best-effort；本地密钥、cursor、pending write 和冲突缓存必须无条件删除。 */
object E2eeSyncRuntimeRegistration {
    const val WIPE_HOOK_KEY = "e2ee_sync_client_wipe"

    fun register(context: Context, repository: CampusRepository) {
        val appContext = context.applicationContext
        repository.registerBeforeWipeHook(WIPE_HOOK_KEY) {
            val client = E2eeSyncClientFactory.create(appContext, repository)
            if (client != null) {
                // 本地擦除不是云端账户删除：先尽力撤销本设备和同意，避免旧安装继续复制；失败绝不阻塞本地密钥销毁。
                runCatching {
                    withTimeoutOrNull(5_000L) {
                        if (client.status().enabled) client.disableAndWithdraw()
                    }
                }
                runCatching { client.wipeLocalOnly() }
            }
            val digest = cn.edu.haut.campus.core.model.json.Digests.sha256Hex(repository.profileId)
            runCatching { File(appContext.filesDir, "e2ee-sync/" + digest).deleteRecursively() }
            runCatching { E2eeSyncScheduler.cancel(appContext, repository.profileId, repository.sessionGeneration) }
        }
    }
}
