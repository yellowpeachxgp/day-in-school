package cn.edu.haut.campus.feature.grades

import android.app.Activity
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import org.robolectric.Robolectric
import org.robolectric.Shadows

/**
 * Robolectric 组合测试基座：真实 Activity + ComposeView，
 * 组合随 dispose() 同步销毁，覆盖「组合离开即重置/清理」类回归场景。
 * 只依赖本模块既有测试类路径（compose-ui / lifecycle / savedstate / robolectric）。
 */
internal class GradesCompositionHarness {

    /** 自给自足的三合一 Owner：不依赖 androidx.activity，避免新增测试接线。 */
    private class HostOwners : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun restoreThenResume() {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
    }

    private val owners = HostOwners()
    val activity: Activity = Robolectric
        .buildActivity(Activity::class.java)
        .setup()
        .get()
    val composeView: ComposeView = ComposeView(activity)

    init {
        ViewTreeLifecycleOwner.set(composeView, owners)
        ViewTreeViewModelStoreOwner.set(composeView, owners)
        ViewTreeSavedStateRegistryOwner.set(composeView, owners)
        owners.restoreThenResume()
        activity.setContentView(composeView)
    }

    fun compose(content: @Composable () -> Unit) {
        composeView.setContent(content)
        pumpMain()
    }

    fun dispose() {
        composeView.disposeComposition()
        pumpMain()
    }

    fun pumpMain() {
        repeat(3) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        Robolectric.flushForegroundThreadScheduler()
    }
}
