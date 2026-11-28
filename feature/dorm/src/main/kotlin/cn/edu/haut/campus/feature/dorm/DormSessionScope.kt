package cn.edu.haut.campus.feature.dorm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** 宿舍协作 ViewModel 会话标识：profileId + sessionGeneration，切号/重登后绝不复用旧协作任务。 */
internal fun dormSessionKey(profileId: String, sessionGeneration: Long?): String =
    profileId + ":" + (sessionGeneration ?: 0L)

/** 组合作用域 ViewModelStoreOwner：离开页面立即清理 VM 与在途云端任务。 */
internal class DormCompositionViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

@Composable
internal fun rememberDormViewModelStoreOwner(sessionKey: String): DormCompositionViewModelStoreOwner {
    val owner = remember(sessionKey) { DormCompositionViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}
