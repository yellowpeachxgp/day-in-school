package cn.edu.haut.campus.feature.grades

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import org.robolectric.Robolectric
import org.robolectric.Shadows

/**
 * Robolectric 组合测试基座：真实 ComponentActivity + ComposeView，
 * 组合随 dispose() 同步销毁，覆盖「组合离开即重置/清理」类回归场景。
 */
internal class GradesCompositionHarness {
    val activity: ComponentActivity = Robolectric
        .buildActivity(ComponentActivity::class.java)
        .setup()
        .get()
    val composeView: ComposeView = ComposeView(activity)

    init {
        ViewTreeLifecycleOwner.set(composeView, activity)
        ViewTreeViewModelStoreOwner.set(composeView, activity)
        ViewTreeSavedStateRegistryOwner.set(composeView, activity)
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
