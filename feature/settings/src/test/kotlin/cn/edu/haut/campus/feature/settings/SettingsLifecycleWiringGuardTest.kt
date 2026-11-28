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

    private fun routeFiles(): List<File> {
        val settingsRoot = File(repoRoot, "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings")
        return settingsRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name.contains("Route") }
            .sortedBy { it.relativeTo(repoRoot).invariantSeparatorsPath }
            .toList()
    }

    /** 提取每个 viewModel(...) 调用的参数文本，避免把字符串里的括号误判为调用结束。 */
    private fun viewModelCallBodies(source: String): List<String> {
        val bodies = mutableListOf<String>()
        var searchIndex = 0
        while (searchIndex < source.length) {
            val match = Regex("\\bviewModel\\s*\\(").find(source, searchIndex) ?: break
            val openIndex = match.range.last
            var depth = 0
            var index = openIndex
            var inString = false
            var escaped = false
            while (index < source.length) {
                val ch = source[index]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        ch == '\\' -> escaped = true
                        ch == '"' -> inString = false
                    }
                } else {
                    when (ch) {
                        '"' -> inString = true
                        '(' -> depth += 1
                        ')' -> {
                            depth -= 1
                            if (depth == 0) {
                                bodies += source.substring(openIndex + 1, index)
                                break
                            }
                        }
                    }
                }
                index += 1
            }
            assertTrue("viewModel call must close", index < source.length)
            searchIndex = index + 1
        }
        return bodies
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
    fun everyRouteUsesCompositionScopedOwnerAndNeverBareViewModel() {
        val routes = routeFiles()
        assertEquals("W0-06c 当前 16 组 Route 都必须纳入扫描", 16, routes.size)

        for (file in routes) {
            val relative = file.relativeTo(repoRoot).invariantSeparatorsPath
            val source = file.readText()
            val calls = viewModelCallBodies(source)
            for (call in calls) {
                assertTrue(
                    "$relative must not use the default Activity-level ViewModelStore",
                    call.contains("viewModelStoreOwner")
                )
            }
        }

        val localOwnerRoutes = listOf(
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/focus/StudyFocusRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/library/catalog/LibraryCatalogRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/schoolcenter/SchoolCenterRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/cloud/XPeachCloudRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/dorm/DormElectricityRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/library/seat/LibrarySeatRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/moments/CampusMomentsRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/parcel/ParcelHubRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/emptyclassroom/EmptyClassroomRoute.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/hub/CampusCommunityRoute.kt"
        )
        for (relative in localOwnerRoutes) {
            val source = read(relative)
            assertTrue("$relative must remember a composition owner", source.contains("rememberSettingsSessionViewModelStoreOwner"))
            assertTrue("$relative must install that owner", source.contains("viewModelStoreOwner = viewModelStoreOwner"))
        }

        val rootOwnerRoutes = listOf(
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/root/home/SettingsHomeRouteSection.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/root/academic/SettingsAcademicRouteSection.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/root/theme/SettingsThemeRouteSection.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/root/data/SettingsDataRouteSection.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/root/network/SettingsNetworkRouteSection.kt"
        )
        for (relative in rootOwnerRoutes) {
            val source = read(relative)
            assertTrue("$relative must accept a composition owner", source.contains("viewModelStoreOwner: ViewModelStoreOwner"))
            assertTrue("$relative must install that owner", source.contains("viewModelStoreOwner = viewModelStoreOwner"))
        }

        val settingsScreen = read("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/SettingsScreen.kt")
        assertTrue(settingsScreen.contains("rememberSettingsSessionViewModelStoreOwner"))
        assertEquals(
            "更新、我的、学期、主题、数据、网络都必须安装根组合 owner",
            6,
            Regex("viewModelStoreOwner\\s*=\\s*rootViewModelStoreOwner").findAll(settingsScreen).count()
        )
    }

    @Test
    fun updateCheckerKeepsRootLifetimeAndAutomaticKey() {
        val settingsScreen = read("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/SettingsScreen.kt")
        val updateViewModel = read("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/root/update/SettingsUpdateViewModel.kt")

        assertTrue(settingsScreen.contains("val settingsUpdateViewModel"))
        assertTrue(settingsScreen.contains("viewModelStoreOwner = rootViewModelStoreOwner"))
        assertTrue(updateViewModel.contains("lastAutoCheckKey"))
        assertTrue(updateViewModel.contains("autoCheckJob?.cancel()"))
        assertTrue(updateViewModel.contains("runReleaseCheck(manual = false"))
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
