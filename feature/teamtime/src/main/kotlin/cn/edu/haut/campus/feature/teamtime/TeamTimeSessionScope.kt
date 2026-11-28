package cn.edu.haut.campus.feature.teamtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** 组队找时间 ViewModel 会话标识：profileId + sessionGeneration。 */
internal fun teamTimeSessionKey(profileId: String, sessionGeneration: Long?): String =
    profileId + ":" + (sessionGeneration ?: 0L)

/** 组合作用域 ViewModelStoreOwner：组合销毁立即清理 VM，取消在途云端/时间流任务。 */
internal class TeamTimeCompositionViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

@Composable
internal fun rememberTeamTimeViewModelStoreOwner(sessionKey: String): TeamTimeCompositionViewModelStoreOwner {
    val owner = remember(sessionKey) { TeamTimeCompositionViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}
