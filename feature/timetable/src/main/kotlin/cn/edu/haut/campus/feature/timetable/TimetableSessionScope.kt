package cn.edu.haut.campus.feature.timetable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** 课表页 ViewModel 会话标识：profileId + sessionGeneration。 */
internal fun timetableSessionKey(profileId: String, sessionGeneration: Long?): String =
    profileId + ":" + (sessionGeneration ?: 0L)

/** 组合作用域 ViewModelStoreOwner：组合销毁立即清理 VM，生命周期不越过底栏/账号切换。 */
internal class TimetableCompositionViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

@Composable
internal fun rememberTimetableViewModelStoreOwner(sessionKey: String): TimetableCompositionViewModelStoreOwner {
    val owner = remember(sessionKey) { TimetableCompositionViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}
