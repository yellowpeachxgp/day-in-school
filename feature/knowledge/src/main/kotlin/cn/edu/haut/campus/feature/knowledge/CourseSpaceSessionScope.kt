package cn.edu.haut.campus.feature.knowledge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** 课程空间 ViewModel 会话标识：profileId + sessionGeneration + courseId。 */
internal fun courseSpaceSessionKey(profileId: String, sessionGeneration: Long?, courseId: String): String =
    profileId + ":" + (sessionGeneration ?: 0L) + ":" + courseId

/** 组合作用域 ViewModelStoreOwner：组合销毁立即清理 VM，并承接 AutoCloseable Repository 生命周期。 */
internal class CourseSpaceCompositionViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

@Composable
internal fun rememberCourseSpaceViewModelStoreOwner(sessionKey: String): CourseSpaceCompositionViewModelStoreOwner {
    val owner = remember(sessionKey) { CourseSpaceCompositionViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}
