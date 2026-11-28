package cn.edu.haut.campus.feature.today

import cn.edu.haut.campus.feature.today.preview.TodayPreviewFixtures
import cn.edu.haut.campus.feature.today.preview.TodayPreviewHarness
import cn.edu.haut.campus.feature.today.state.TodayScreenStatus
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Today 全状态矩阵截图；golden 只允许在 devbox Linux 录制。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class TodayScreenRoborazziTest {

    @Test
    fun loadingGolden() {
        captureRoboImage {
            TodayPreviewHarness(TodayPreviewFixtures.stateFor(TodayScreenStatus.LOADING))
        }
    }

    @Test
    fun emptyGolden() {
        captureRoboImage {
            TodayPreviewHarness(TodayPreviewFixtures.stateFor(TodayScreenStatus.EMPTY))
        }
    }

    @Test
    fun contentGolden() {
        captureRoboImage {
            TodayPreviewHarness(TodayPreviewFixtures.stateFor(TodayScreenStatus.CONTENT))
        }
    }

    @Test
    fun partialGolden() {
        captureRoboImage {
            TodayPreviewHarness(TodayPreviewFixtures.stateFor(TodayScreenStatus.PARTIAL))
        }
    }

    @Test
    fun staleGolden() {
        captureRoboImage {
            TodayPreviewHarness(TodayPreviewFixtures.stateFor(TodayScreenStatus.STALE))
        }
    }

    @Test
    fun errorRetryableGolden() {
        captureRoboImage {
            TodayPreviewHarness(TodayPreviewFixtures.stateFor(TodayScreenStatus.ERROR_RETRYABLE))
        }
    }

    @Test
    fun errorTerminalGolden() {
        captureRoboImage {
            TodayPreviewHarness(TodayPreviewFixtures.stateFor(TodayScreenStatus.ERROR_TERMINAL))
        }
    }

    @Test
    fun needsPermissionGolden() {
        captureRoboImage {
            TodayPreviewHarness(TodayPreviewFixtures.stateFor(TodayScreenStatus.NEEDS_PERMISSION))
        }
    }

    @Test
    fun needsLoginGolden() {
        captureRoboImage {
            TodayPreviewHarness(TodayPreviewFixtures.stateFor(TodayScreenStatus.NEEDS_LOGIN))
        }
    }

    @Test
    fun offlineGolden() {
        captureRoboImage {
            TodayPreviewHarness(TodayPreviewFixtures.stateFor(TodayScreenStatus.OFFLINE))
        }
    }

    @Test
    fun unsupportedGolden() {
        captureRoboImage {
            TodayPreviewHarness(TodayPreviewFixtures.stateFor(TodayScreenStatus.UNSUPPORTED))
        }
    }
}
