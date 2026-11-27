package cn.edu.haut.campus.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.edu.haut.campus.core.domain.SchoolDirectory
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import cn.edu.haut.campus.feature.settings.schoolcenter.preview.SchoolCenterPreviewFixtures
import cn.edu.haut.campus.feature.settings.schoolcenter.ui.SchoolCenterScreenContent
import cn.edu.haut.campus.feature.settings.library.catalog.preview.LibraryCatalogPreviewFixtures
import cn.edu.haut.campus.feature.settings.library.catalog.ui.LibraryCatalogScreenContent
import cn.edu.haut.campus.feature.settings.focus.preview.StudyFocusPreviewFixtures
import cn.edu.haut.campus.feature.settings.focus.ui.StudyFocusScreenContent
import cn.edu.haut.campus.feature.settings.root.update.preview.SettingsUpdatePreviewFixtures
import cn.edu.haut.campus.feature.settings.root.update.ui.SettingsUpdateSectionContent

/**
 * W0-06c 代表性设置页面 Roborazzi 状态矩阵截图。
 * 每个公开矩阵状态单独一个测试方法，保证基线文件名稳定且失败可定位。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class SettingsPreviewRoborazziTest {

    @Test
    fun schoolCenter_loading() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SchoolCenterPreviewFixtures.loading
                SchoolCenterScreenContent(state = state, directory = directory, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun schoolCenter_empty() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SchoolCenterPreviewFixtures.empty
                SchoolCenterScreenContent(state = state, directory = directory, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun schoolCenter_content() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SchoolCenterPreviewFixtures.content
                SchoolCenterScreenContent(state = state, directory = directory, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun schoolCenter_partial() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SchoolCenterPreviewFixtures.partial
                SchoolCenterScreenContent(state = state, directory = directory, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun schoolCenter_stale() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SchoolCenterPreviewFixtures.stale
                SchoolCenterScreenContent(state = state, directory = directory, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun schoolCenter_error_retryable() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SchoolCenterPreviewFixtures.errorRetryable
                SchoolCenterScreenContent(state = state, directory = directory, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun schoolCenter_error_terminal() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SchoolCenterPreviewFixtures.errorTerminal
                SchoolCenterScreenContent(state = state, directory = directory, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun schoolCenter_needs_permission() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SchoolCenterPreviewFixtures.needsPermission
                SchoolCenterScreenContent(state = state, directory = directory, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun schoolCenter_needs_login() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SchoolCenterPreviewFixtures.needsLogin
                SchoolCenterScreenContent(state = state, directory = directory, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun schoolCenter_offline() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SchoolCenterPreviewFixtures.offline
                SchoolCenterScreenContent(state = state, directory = directory, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun schoolCenter_unsupported() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SchoolCenterPreviewFixtures.unsupported
                SchoolCenterScreenContent(state = state, directory = directory, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun libraryCatalog_loading() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = LibraryCatalogPreviewFixtures.loading
                LibraryCatalogScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun libraryCatalog_empty() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = LibraryCatalogPreviewFixtures.empty
                LibraryCatalogScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun libraryCatalog_content() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = LibraryCatalogPreviewFixtures.content
                LibraryCatalogScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun libraryCatalog_partial() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = LibraryCatalogPreviewFixtures.partial
                LibraryCatalogScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun libraryCatalog_stale() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = LibraryCatalogPreviewFixtures.stale
                LibraryCatalogScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun libraryCatalog_error_retryable() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = LibraryCatalogPreviewFixtures.errorRetryable
                LibraryCatalogScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun libraryCatalog_error_terminal() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = LibraryCatalogPreviewFixtures.errorTerminal
                LibraryCatalogScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun libraryCatalog_needs_permission() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = LibraryCatalogPreviewFixtures.needsPermission
                LibraryCatalogScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun libraryCatalog_needs_login() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = LibraryCatalogPreviewFixtures.needsLogin
                LibraryCatalogScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun libraryCatalog_offline() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = LibraryCatalogPreviewFixtures.offline
                LibraryCatalogScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun libraryCatalog_unsupported() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = LibraryCatalogPreviewFixtures.unsupported
                LibraryCatalogScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun studyFocus_loading() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = StudyFocusPreviewFixtures.loading
                StudyFocusScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun studyFocus_empty() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = StudyFocusPreviewFixtures.empty
                StudyFocusScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun studyFocus_content() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = StudyFocusPreviewFixtures.content
                StudyFocusScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun studyFocus_partial() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = StudyFocusPreviewFixtures.partial
                StudyFocusScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun studyFocus_stale() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = StudyFocusPreviewFixtures.stale
                StudyFocusScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun studyFocus_error_retryable() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = StudyFocusPreviewFixtures.errorRetryable
                StudyFocusScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun studyFocus_error_terminal() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = StudyFocusPreviewFixtures.errorTerminal
                StudyFocusScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun studyFocus_needs_permission() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = StudyFocusPreviewFixtures.needsPermission
                StudyFocusScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun studyFocus_needs_login() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = StudyFocusPreviewFixtures.needsLogin
                StudyFocusScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun studyFocus_offline() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = StudyFocusPreviewFixtures.offline
                StudyFocusScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun studyFocus_unsupported() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = StudyFocusPreviewFixtures.unsupported
                StudyFocusScreenContent(state = state, onBack = {}, onAction = {})
            }
        }
    }

    @Test
    fun settingsUpdate_loading() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SettingsUpdatePreviewFixtures.loading
                SettingsUpdateSectionContent(state = state, onAction = {})
            }
        }
    }

    @Test
    fun settingsUpdate_empty() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SettingsUpdatePreviewFixtures.empty
                SettingsUpdateSectionContent(state = state, onAction = {})
            }
        }
    }

    @Test
    fun settingsUpdate_content() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SettingsUpdatePreviewFixtures.content
                SettingsUpdateSectionContent(state = state, onAction = {})
            }
        }
    }

    @Test
    fun settingsUpdate_partial() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SettingsUpdatePreviewFixtures.partial
                SettingsUpdateSectionContent(state = state, onAction = {})
            }
        }
    }

    @Test
    fun settingsUpdate_stale() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SettingsUpdatePreviewFixtures.stale
                SettingsUpdateSectionContent(state = state, onAction = {})
            }
        }
    }

    @Test
    fun settingsUpdate_error_retryable() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SettingsUpdatePreviewFixtures.errorRetryable
                SettingsUpdateSectionContent(state = state, onAction = {})
            }
        }
    }

    @Test
    fun settingsUpdate_error_terminal() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SettingsUpdatePreviewFixtures.errorTerminal
                SettingsUpdateSectionContent(state = state, onAction = {})
            }
        }
    }

    @Test
    fun settingsUpdate_needs_permission() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SettingsUpdatePreviewFixtures.needsPermission
                SettingsUpdateSectionContent(state = state, onAction = {})
            }
        }
    }

    @Test
    fun settingsUpdate_needs_login() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SettingsUpdatePreviewFixtures.needsLogin
                SettingsUpdateSectionContent(state = state, onAction = {})
            }
        }
    }

    @Test
    fun settingsUpdate_offline() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SettingsUpdatePreviewFixtures.offline
                SettingsUpdateSectionContent(state = state, onAction = {})
            }
        }
    }

    @Test
    fun settingsUpdate_unsupported() {
        captureRoboImage {
            TestPreviewFrame {
                val directory = SchoolDirectory.builtIn()
                val state = SettingsUpdatePreviewFixtures.unsupported
                SettingsUpdateSectionContent(state = state, onAction = {})
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
