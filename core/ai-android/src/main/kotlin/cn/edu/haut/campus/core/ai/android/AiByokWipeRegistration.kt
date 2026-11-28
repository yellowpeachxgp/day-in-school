package cn.edu.haut.campus.core.ai.android

import android.content.Context
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.automationScope

/** C27 擦除链路注册点：复用 W0-05 hook，不改 CampusRepository 外观。 */
object AiByokWipeRegistration {
    const val HOOK_KEY: String = "ai_byok_key_wipe"

    fun register(context: Context, repository: CampusRepository) {
        repository.registerBeforeWipeHook(HOOK_KEY) {
            val scope = repository.automationScope
            try {
                AndroidAiKeyStore(context).wipeAll(scope)
            } finally {
                AiAndroidServiceRegistry.invalidate(scope)
            }
        }
    }
}
