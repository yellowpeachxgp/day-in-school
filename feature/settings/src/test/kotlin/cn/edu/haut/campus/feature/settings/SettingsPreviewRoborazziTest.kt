package cn.edu.haut.campus.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.edu.haut.campus.feature.settings.focus.preview.StudyFocusPreviewFixtures
import cn.edu.haut.campus.feature.settings.focus.ui.StudyFocusScreenContent
import cn.edu.haut.campus.feature.settings.root.update.preview.SettingsUpdatePreviewFixtures
import cn.edu.haut.campus.feature.settings.root.update.ui.SettingsUpdateSectionContent
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * W0-06c 设置页 Roborazzi 基线 smoke。
 *
 * 完整 11 种状态矩阵由各处 PreviewFixtures 固定；基线先录制两个代表页面的
 * Loading/Content 四张稳定图，避免在纯搬移卡中引入长时间截图矩阵维护成本。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class SettingsPreviewRoborazziTest {

    @Test
    fun studyFocus_loading() {
        captureRoboImage {
            TestPreviewFrame {
                StudyFocusScreenContent(
                    state = StudyFocusPreviewFixtures.loading,
                    onBack = {},
                    onAction = {}
                )
            }
        }
    }

    @Test
    fun studyFocus_content() {
        captureRoboImage {
            TestPreviewFrame {
                StudyFocusScreenContent(
                    state = StudyFocusPreviewFixtures.content,
                    onBack = {},
                    onAction = {}
                )
            }
        }
    }

    @Test
    fun settingsUpdate_loading() {
        captureRoboImage {
            TestPreviewFrame {
                SettingsUpdateSectionContent(
                    state = SettingsUpdatePreviewFixtures.loading,
                    onAction = {}
                )
            }
        }
    }

    @Test
    fun settingsUpdate_content() {
        captureRoboImage {
            TestPreviewFrame {
                SettingsUpdateSectionContent(
                    state = SettingsUpdatePreviewFixtures.content,
                    onAction = {}
                )
            }
        }
    }
}

@Composable
private fun TestPreviewFrame(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}
