package cn.edu.haut.campus.feature.grades

import cn.edu.haut.campus.feature.grades.preview.GradesPreviewFixtures
import cn.edu.haut.campus.feature.grades.preview.GradesPreviewHarness
import cn.edu.haut.campus.feature.grades.state.GradesScreenStatus
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Grades 全状态矩阵截图；golden 只允许在 devbox Linux 录制。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class GradesScreenRoborazziTest {

    @Test
    fun loadingGolden() {
        captureRoboImage {
            GradesPreviewHarness(GradesPreviewFixtures.stateFor(GradesScreenStatus.LOADING))
        }
    }

    @Test
    fun emptyGolden() {
        captureRoboImage {
            GradesPreviewHarness(GradesPreviewFixtures.stateFor(GradesScreenStatus.EMPTY))
        }
    }

    @Test
    fun contentGolden() {
        captureRoboImage {
            GradesPreviewHarness(GradesPreviewFixtures.stateFor(GradesScreenStatus.CONTENT))
        }
    }

    @Test
    fun partialGolden() {
        captureRoboImage {
            GradesPreviewHarness(GradesPreviewFixtures.stateFor(GradesScreenStatus.PARTIAL))
        }
    }

    @Test
    fun staleGolden() {
        captureRoboImage {
            GradesPreviewHarness(GradesPreviewFixtures.stateFor(GradesScreenStatus.STALE))
        }
    }

    @Test
    fun errorRetryableGolden() {
        captureRoboImage {
            GradesPreviewHarness(GradesPreviewFixtures.stateFor(GradesScreenStatus.ERROR_RETRYABLE))
        }
    }

    @Test
    fun errorTerminalGolden() {
        captureRoboImage {
            GradesPreviewHarness(GradesPreviewFixtures.stateFor(GradesScreenStatus.ERROR_TERMINAL))
        }
    }

    @Test
    fun needsPermissionGolden() {
        captureRoboImage {
            GradesPreviewHarness(GradesPreviewFixtures.stateFor(GradesScreenStatus.NEEDS_PERMISSION))
        }
    }

    @Test
    fun needsLoginGolden() {
        captureRoboImage {
            GradesPreviewHarness(GradesPreviewFixtures.stateFor(GradesScreenStatus.NEEDS_LOGIN))
        }
    }

    @Test
    fun offlineGolden() {
        captureRoboImage {
            GradesPreviewHarness(GradesPreviewFixtures.stateFor(GradesScreenStatus.OFFLINE))
        }
    }

    @Test
    fun unsupportedGolden() {
        captureRoboImage {
            GradesPreviewHarness(GradesPreviewFixtures.stateFor(GradesScreenStatus.UNSUPPORTED))
        }
    }
}
