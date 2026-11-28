package cn.edu.haut.campus.feature.automation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** 自动化页 ViewModel 会话标识：profileId + generation；账号重建/世代切换后绝不复用旧 VM。 */
internal fun automationSessionKey(profileId: String, generation: Long): String =
    profileId + ":" + generation

/** 组合作用域 ViewModelStoreOwner：overlay/账号切换离开组合时立即清理 VM。 */
internal class AutomationCompositionViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

@Composable
internal fun rememberAutomationViewModelStoreOwner(sessionKey: String): AutomationCompositionViewModelStoreOwner {
    val owner = remember(sessionKey) { AutomationCompositionViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}
