package cn.edu.haut.campus.feature.today

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** 今日页 ViewModel 会话标识：profileId + sessionGeneration。 */
internal fun todaySessionKey(profileId: String, sessionGeneration: Long?): String =
    profileId + ":" + (sessionGeneration ?: 0L)

/** 组合作用域 ViewModelStoreOwner：组合销毁立即清理 VM，生命周期不越过底栏/账号切换。 */
internal class TodayCompositionViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

@Composable
internal fun rememberTodayViewModelStoreOwner(sessionKey: String): TodayCompositionViewModelStoreOwner {
    val owner = remember(sessionKey) { TodayCompositionViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}
