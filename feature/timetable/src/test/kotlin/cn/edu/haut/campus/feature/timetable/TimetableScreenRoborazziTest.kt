package cn.edu.haut.campus.feature.timetable

import cn.edu.haut.campus.feature.timetable.preview.TimetablePreviewFixtures
import cn.edu.haut.campus.feature.timetable.preview.TimetablePreviewHarness
import cn.edu.haut.campus.feature.timetable.state.TimetableScreenStatus
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Timetable 全状态矩阵截图；golden 只允许在 devbox Linux 录制。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class TimetableScreenRoborazziTest {

    @Test
    fun loadingGolden() {
        captureRoboImage {
            TimetablePreviewHarness(TimetablePreviewFixtures.stateFor(TimetableScreenStatus.LOADING))
        }
    }

    @Test
    fun emptyGolden() {
        captureRoboImage {
            TimetablePreviewHarness(TimetablePreviewFixtures.stateFor(TimetableScreenStatus.EMPTY))
        }
    }

    @Test
    fun contentGolden() {
        captureRoboImage {
            TimetablePreviewHarness(TimetablePreviewFixtures.stateFor(TimetableScreenStatus.CONTENT))
        }
    }

    @Test
    fun partialGolden() {
        captureRoboImage {
            TimetablePreviewHarness(TimetablePreviewFixtures.stateFor(TimetableScreenStatus.PARTIAL))
        }
    }

    @Test
    fun staleGolden() {
        captureRoboImage {
            TimetablePreviewHarness(TimetablePreviewFixtures.stateFor(TimetableScreenStatus.STALE))
        }
    }

    @Test
    fun errorRetryableGolden() {
        captureRoboImage {
            TimetablePreviewHarness(TimetablePreviewFixtures.stateFor(TimetableScreenStatus.ERROR_RETRYABLE))
        }
    }

    @Test
    fun errorTerminalGolden() {
        captureRoboImage {
            TimetablePreviewHarness(TimetablePreviewFixtures.stateFor(TimetableScreenStatus.ERROR_TERMINAL))
        }
    }

    @Test
    fun needsPermissionGolden() {
        captureRoboImage {
            TimetablePreviewHarness(TimetablePreviewFixtures.stateFor(TimetableScreenStatus.NEEDS_PERMISSION))
        }
    }

    @Test
    fun needsLoginGolden() {
        captureRoboImage {
            TimetablePreviewHarness(TimetablePreviewFixtures.stateFor(TimetableScreenStatus.NEEDS_LOGIN))
        }
    }

    @Test
    fun offlineGolden() {
        captureRoboImage {
            TimetablePreviewHarness(TimetablePreviewFixtures.stateFor(TimetableScreenStatus.OFFLINE))
        }
    }

    @Test
    fun unsupportedGolden() {
        captureRoboImage {
            TimetablePreviewHarness(TimetablePreviewFixtures.stateFor(TimetableScreenStatus.UNSUPPORTED))
        }
    }
}
