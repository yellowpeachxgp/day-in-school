package cn.edu.haut.campus.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 静态接线守卫：防止 resetOnExit、跨账号防串扰或单一事实源在后续搬移中丢失。 */
class SettingsLifecycleWiringGuardTest {
    private val repoRoot = File("../..").canonicalFile

    private fun read(relative: String): String {
        val file = File(repoRoot, relative)
        assertTrue("$relative must exist", file.isFile)
        return file.readText()
    }

    @Test
    fun overlayRoutesResetTransientViewStateOnDispose() {
        val cases = listOf(
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/focus/StudyFocusRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/library/catalog/LibraryCatalogRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/schoolcenter/SchoolCenterRoute.kt"
        )
        for (relative in cases) {
            val source = read(relative)
            assertTrue("$relative must reset on dispose", source.contains("DisposableEffect"))
            assertTrue("$relative must call resetTransientStateOnExit", source.contains("resetTransientStateOnExit"))
        }
    }

    @Test
    fun rootSettingsUsesOneExitRegistryAndOneImportHistoryDialog() {
        val settingsScreen = read("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/SettingsScreen.kt")
        val homeUi = read("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/root/home/ui/SettingsHomeSection.kt")
        val homeRoute = read("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/root/home/SettingsHomeRouteSection.kt")
        val updateRoute = read("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/root/update/SettingsUpdateRouteSection.kt")

        assertTrue(settingsScreen.contains("SettingsTransientResetRegistry"))
        assertTrue(settingsScreen.contains("transientResetRegistry.resetAll()"))
        assertTrue(homeRoute.contains("transientResetRegistry.register"))
        assertTrue(updateRoute.contains("transientResetRegistry.register"))
        assertFalse(homeUi.contains("SyncHistoryDialog("))
        assertEquals(1, Regex("SyncHistoryDialog\\(").findAll(settingsScreen + homeUi).count())
    }

    @Test
    fun documentedKeyExceptionsStayExplicit() {
        val catalogRoute = read("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/library/catalog/LibraryCatalogRoute.kt")
        val schoolRoute = read("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/schoolcenter/SchoolCenterRoute.kt")
        val schoolVm = read("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/schoolcenter/SchoolCenterViewModel.kt")

        assertTrue(catalogRoute.contains("不注入 Repository"))
        assertTrue(catalogRoute.contains("不按 profileId/generation 加 key"))
        assertTrue(schoolRoute.contains("静态 key"))
        assertTrue(schoolRoute.contains("跨账号管理"))
        assertTrue(schoolVm.contains("有意收集全局 profiles"))
    }

    @Test
    fun transientAndHighPrivacyRoutesUseCompositionScopedOwner() {
        val localOwnerRoutes = listOf(
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/focus/StudyFocusRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/library/catalog/LibraryCatalogRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/schoolcenter/SchoolCenterRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/cloud/XPeachCloudRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/dorm/DormElectricityRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/library/seat/LibrarySeatRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/moments/CampusMomentsRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/parcel/ParcelHubRoute.kt"
        )
        for (relative in localOwnerRoutes) {
            val source = read(relative)
            assertTrue("$relative must remember a composition owner", source.contains("rememberSettingsSessionViewModelStoreOwner"))
            assertTrue("$relative must install that owner", source.contains("viewModelStoreOwner = viewModelStoreOwner"))
        }

        val settingsScreen = read("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/SettingsScreen.kt")
        val homeRoute = read("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/root/home/SettingsHomeRouteSection.kt")
        assertTrue(settingsScreen.contains("rememberSettingsSessionViewModelStoreOwner"))
        assertTrue(settingsScreen.contains("viewModelStoreOwner = rootViewModelStoreOwner"))
        assertTrue(homeRoute.contains("viewModelStoreOwner: ViewModelStoreOwner"))
        assertTrue(homeRoute.contains("viewModelStoreOwner = viewModelStoreOwner"))
    }

    @Test
    fun updateCheckerKeepsRootLifetimeAndAutomaticKey() {
        val settingsScreen = read("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/SettingsScreen.kt")
        val updateViewModel = read("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/root/update/SettingsUpdateViewModel.kt")

        assertTrue(settingsScreen.contains("val settingsUpdateViewModel"))
        assertTrue(settingsScreen.contains("viewModelStoreOwner = rootViewModelStoreOwner"))
        assertTrue(updateViewModel.contains("lastAutoCheckKey"))
        assertTrue(updateViewModel.contains("checkLatestRelease(manual = false)"))
    }

    @Test
    fun highPrivacyViewModelsUseProfileSessionGuard() {
        val viewModels = listOf(
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/cloud/XPeachCloudViewModel.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/dorm/DormElectricityViewModel.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/library/seat/LibrarySeatViewModel.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/moments/CampusMomentsViewModel.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/parcel/ParcelHubViewModel.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/root/home/SettingsHomeViewModel.kt"
        )
        for (relative in viewModels) {
            val source = read(relative)
            assertTrue("$relative must inherit guard", source.contains("ProfileSessionScopedViewModel"))
            assertTrue("$relative must collect through guard", source.contains("launchSessionCollect"))
            assertTrue("$relative must clear invalid state", source.contains("resetStateForInvalidSession"))
        }
    }
}
