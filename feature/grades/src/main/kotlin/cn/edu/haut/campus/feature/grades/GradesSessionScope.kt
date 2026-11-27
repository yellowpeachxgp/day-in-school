package cn.edu.haut.campus.feature.grades

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * 学业页 ViewModel 的会话标识：profileId + sessionGeneration。
 * Profile 切换后 sessionGeneration 必变化，保证新会话不复用旧 ViewModel。
 */
internal fun gradesSessionKey(profileId: String, sessionGeneration: Long?): String =
    profileId + ":" + (sessionGeneration ?: 0L)

/**
 * 组合作用域的 ViewModelStoreOwner：随组合销毁立即清空内部 ViewModel。
 * 拆分前学业页依靠 collectAsState 在组合内收集数据，组合离开即停止；
 * 该 Owner 让 ViewModel 的数据收集生命周期与拆分前完全一致，
 * 同时杜绝 Profile 切换后旧 ViewModel 继续收集旧 Repository 的跨账号串扰。
 */
internal class GradesCompositionViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

@Composable
internal fun rememberGradesViewModelStoreOwner(sessionKey: String): GradesCompositionViewModelStoreOwner {
    val owner = remember(sessionKey) { GradesCompositionViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}
