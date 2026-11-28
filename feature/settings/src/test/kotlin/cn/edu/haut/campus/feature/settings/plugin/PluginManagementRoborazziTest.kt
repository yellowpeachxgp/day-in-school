package cn.edu.haut.campus.feature.settings.plugin

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.edu.haut.campus.feature.settings.plugin.preview.PluginManagementPreviewFixtures
import cn.edu.haut.campus.feature.settings.plugin.state.PluginManagementUiState
import cn.edu.haut.campus.feature.settings.plugin.ui.PluginManagementScreen
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 插件管理页 Roborazzi smoke 基线；完整状态矩阵固定在 PluginManagementPreviewFixtures.states11。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class PluginManagementRoborazziTest {
    @Test
    fun pluginManagementContent() {
        capture(PluginManagementPreviewFixtures.content)
    }

    @Test
    fun pluginManagementPermissionDiff() {
        capture(
            PluginManagementPreviewFixtures.content.copy(
                plugins = listOf(PluginManagementPreviewFixtures.pendingUpgradePlugin)
            )
        )
    }

    @Test
    fun pluginManagementEmpty() {
        capture(PluginManagementPreviewFixtures.empty)
    }

    @Test
    fun pluginManagementNeedsPermission() {
        capture(PluginManagementPreviewFixtures.needsPermission)
    }

    private fun capture(state: PluginManagementUiState) {
        captureRoboImage {
            TestFrame {
                Surface(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PluginManagementScreen(state = state, onAction = {})
                }
            }
        }
    }

    @Composable
    private fun TestFrame(content: @Composable () -> Unit) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}
