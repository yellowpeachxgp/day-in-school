package cn.edu.haut.campus.core.uiruntime

import cn.edu.haut.campus.core.uiruntime.preview.ExtensionUiPreviewFixtures
import cn.edu.haut.campus.core.uiruntime.preview.ExtensionUiPreviewHarness
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** AC-31：首批 8 种节点的浅色、深色、200% 字号截图；未知节点单独降级 smoke。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class FeatureUiRendererRoborazziTest {
    @Test fun textLight() = captureNode("Text", dark = false, fontScale = 1f)
    @Test fun textDark() = captureNode("Text", dark = true, fontScale = 1f)
    @Test fun textFont200() = captureNode("Text", dark = false, fontScale = 2f)
    @Test fun metricLight() = captureNode("Metric", dark = false, fontScale = 1f)
    @Test fun metricDark() = captureNode("Metric", dark = true, fontScale = 1f)
    @Test fun metricFont200() = captureNode("Metric", dark = false, fontScale = 2f)
    @Test fun keyValueLight() = captureNode("KeyValue", dark = false, fontScale = 1f)
    @Test fun keyValueDark() = captureNode("KeyValue", dark = true, fontScale = 1f)
    @Test fun keyValueFont200() = captureNode("KeyValue", dark = false, fontScale = 2f)
    @Test fun badgeLight() = captureNode("Badge", dark = false, fontScale = 1f)
    @Test fun badgeDark() = captureNode("Badge", dark = true, fontScale = 1f)
    @Test fun badgeFont200() = captureNode("Badge", dark = false, fontScale = 2f)
    @Test fun actionButtonLight() = captureNode("ActionButton", dark = false, fontScale = 1f)
    @Test fun actionButtonDark() = captureNode("ActionButton", dark = true, fontScale = 1f)
    @Test fun actionButtonFont200() = captureNode("ActionButton", dark = false, fontScale = 2f)
    @Test fun listLight() = captureNode("List", dark = false, fontScale = 1f)
    @Test fun listDark() = captureNode("List", dark = true, fontScale = 1f)
    @Test fun listFont200() = captureNode("List", dark = false, fontScale = 2f)
    @Test fun emptyStateLight() = captureNode("EmptyState", dark = false, fontScale = 1f)
    @Test fun emptyStateDark() = captureNode("EmptyState", dark = true, fontScale = 1f)
    @Test fun emptyStateFont200() = captureNode("EmptyState", dark = false, fontScale = 2f)
    @Test fun errorStateLight() = captureNode("ErrorState", dark = false, fontScale = 1f)
    @Test fun errorStateDark() = captureNode("ErrorState", dark = true, fontScale = 1f)
    @Test fun errorStateFont200() = captureNode("ErrorState", dark = false, fontScale = 2f)

    @Test
    fun unknownNodeDegradesToErrorStateLight() {
        captureRoboImage {
            ExtensionUiPreviewHarness {
                FeatureUiCardContent(
                    card = ExtensionUiPreviewFixtures.unknownNodeCard,
                    commands = emptyList(),
                    grantedPermissions = emptySet(),
                    allowedOrigins = emptySet(),
                    allowedHostRoutes = emptySet(),
                    pluginId = "org.example.fixture",
                    maxNodes = 10,
                    onCommand = {}
                )
            }
        }
    }

    private fun captureNode(kind: String, dark: Boolean, fontScale: Float) {
        captureRoboImage {
            ExtensionUiPreviewHarness(darkTheme = dark, fontScale = fontScale) {
                FeatureUiCardContent(
                    card = ExtensionUiPreviewFixtures.nodeCards.getValue(kind),
                    commands = ExtensionUiPreviewFixtures.commands,
                    grantedPermissions = ExtensionUiPreviewFixtures.grantedPermissions,
                    allowedOrigins = ExtensionUiPreviewFixtures.allowedOrigins,
                    allowedHostRoutes = DEFAULT_EXTENSION_HOST_ROUTES,
                    pluginId = "org.example.fixture",
                    maxNodes = 10,
                    onCommand = {}
                )
            }
        }
    }
}
