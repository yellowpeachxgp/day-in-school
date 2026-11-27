package cn.edu.haut.campus.feature.map

import cn.edu.haut.campus.feature.map.preview.MapPreviewFixtures
import cn.edu.haut.campus.feature.map.preview.MapPreviewScenario
import cn.edu.haut.campus.feature.map.state.MapUiState
import cn.edu.haut.campus.feature.map.tiles.CampusTileCache
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 校园地图状态矩阵 Roborazzi 基线；夹具不访问网络、定位或 Repository。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class CampusMapScreenshotTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun disableNondeterministicTileLoading() {
            CampusTileCache.tileLoadingEnabled = false
        }

        @AfterClass
        @JvmStatic
        fun restoreTileLoading() {
            CampusTileCache.tileLoadingEnabled = true
        }
    }

    @Test fun content() = capture(MapPreviewFixtures.content)
    @Test fun needsLocationPermission() = capture(MapPreviewFixtures.needsLocationPermission)
    @Test fun tilesLoading() = capture(MapPreviewFixtures.tilesLoading)
    @Test fun tilesFailed() = capture(MapPreviewFixtures.tilesFailed)
    @Test fun offline() = capture(MapPreviewFixtures.offline)
    @Test fun searchEmpty() = capture(MapPreviewFixtures.searchEmpty)
    @Test fun unresolvedBuilding() = capture(MapPreviewFixtures.unresolvedBuilding)
    @Test fun route() = capture(MapPreviewFixtures.route)
    @Test fun indoorMap() = capture(MapPreviewFixtures.indoorMap)
    @Test fun indoorLoading() = capture(MapPreviewFixtures.indoorLoading)
    @Test fun customZone() = capture(MapPreviewFixtures.customZone)
    @Test fun officialGis() = capture(MapPreviewFixtures.officialGis)
    @Test fun officialGisDialog() = capture(MapPreviewFixtures.officialGisDialog)
    @Test fun hospitalPharmacy() = capture(MapPreviewFixtures.hospitalPharmacy)
    @Test fun unsupportedSchool() = capture(MapPreviewFixtures.unsupportedSchool)

    private fun capture(state: MapUiState) {
        captureRoboImage {
            MapPreviewScenario(state)
        }
    }
}
