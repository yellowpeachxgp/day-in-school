package cn.edu.haut.campus.feature.dorm

import cn.edu.haut.campus.feature.dorm.preview.DormPreviewFixtures
import cn.edu.haut.campus.feature.dorm.preview.DormPreviewHarness
import cn.edu.haut.campus.feature.dorm.state.DormScreenStatus
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 宿舍协作 11 态矩阵截图；golden 只允许在 devbox Linux 录制。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class DormScreenRoborazziTest {
    @Test
    fun loadingGolden() = capture(DormScreenStatus.LOADING)

    @Test
    fun emptyGolden() = capture(DormScreenStatus.EMPTY)

    @Test
    fun contentGolden() = capture(DormScreenStatus.CONTENT)

    @Test
    fun partialGolden() = capture(DormScreenStatus.PARTIAL)

    @Test
    fun staleGolden() = capture(DormScreenStatus.STALE)

    @Test
    fun errorRetryableGolden() = capture(DormScreenStatus.ERROR_RETRYABLE)

    @Test
    fun errorNonRetryableGolden() = capture(DormScreenStatus.ERROR_NON_RETRYABLE)

    @Test
    fun needsPermissionGolden() = capture(DormScreenStatus.NEEDS_PERMISSION)

    @Test
    fun needsLoginGolden() = capture(DormScreenStatus.NEEDS_LOGIN)

    @Test
    fun offlineGolden() = capture(DormScreenStatus.OFFLINE)

    @Test
    fun unsupportedGolden() = capture(DormScreenStatus.UNSUPPORTED)

    private fun capture(status: DormScreenStatus) {
        captureRoboImage {
            DormPreviewHarness(DormPreviewFixtures.stateFor(status))
        }
    }
}
