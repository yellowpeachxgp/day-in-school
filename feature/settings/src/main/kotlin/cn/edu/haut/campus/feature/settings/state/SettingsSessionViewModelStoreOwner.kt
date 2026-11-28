package cn.edu.haut.campus.feature.settings.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * 设置页会话标识：profileId + sessionGeneration。
 * Profile 切换后 generation 必变化；会话停用时的 null 也不会与正式世代冲突。
 */
internal fun settingsSessionKey(profileId: String, sessionGeneration: Long?): String =
    profileId + ":" + (sessionGeneration ?: 0L)

/**
 * 组合作用域的 ViewModelStoreOwner。
 *
 * 拆分前设置页依靠 collectAsState 在组合内收集 Repository Flow，组合离开即停止；
 * 该 Owner 让 ViewModel 的收集生命周期重新回到组合边界。Profile 切换导致 sessionKey
 * 变化或组合销毁时立即清空旧 ViewModel，避免旧实例继续吸收旧账号数据。
 */
internal class SettingsSessionViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

@Composable
internal fun rememberSettingsSessionViewModelStoreOwner(sessionKey: String): SettingsSessionViewModelStoreOwner {
    val owner = remember(sessionKey) { SettingsSessionViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}
