package cn.edu.haut.campus.feature.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** AC-32 static integration evidence: all six runtime slots are wired into real host surfaces. */
class ExtensionSlotHostIntegrationGuardTest {
    @Test
    fun sixRuntimeSlotsHaveExactlyMappedHostIntegration() {
        val repoRoot = File("../..").canonicalFile
        fun text(path: String) = File(repoRoot, path).readText()

        assertTrue(text("feature/today/src/main/kotlin/cn/edu/haut/campus/feature/today/TodayExtensionSlot.kt").contains("home.cards"))
        assertTrue(text("feature/today/src/main/kotlin/cn/edu/haut/campus/feature/today/TodayScreen.kt").contains("TodayExtensionSlot("))
        assertTrue(text("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/schoolcenter/SchoolCenterRoute.kt").contains("campus.services"))
        assertTrue(text("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/library/seat/LibrarySeatRoute.kt").contains("library.actions"))
        val parcel = text("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/parcel/ParcelHubRoute.kt")
        assertTrue(parcel.contains("parcel.cards"))
        assertTrue(parcel.contains("parcel.providers"))
        assertTrue(text("feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/root/home/SettingsHomeRouteSection.kt").contains("settings.entries"))
    }

    @Test
    fun pluginManagementUsesSharedInstallerForEveryLifecycleOperation() {
        val repoRoot = File("../..").canonicalFile
        val text = File(repoRoot, "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/plugin/PluginManagementViewModel.kt").readText()
        listOf("revokePermission", "setEnabled", "confirmPendingPermissions", "rollback", "uninstall").forEach { operation ->
            assertTrue(operation, text.contains("installer." + operation))
        }
        assertTrue(!text.contains("private val repository: CampusRepository"))
    }

    @Test
    fun slotHostsConsumeSharedInstallerRatherThanFacadeLifecycleWrites() {
        val repoRoot = File("../..").canonicalFile
        val bindings = listOf(
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/extension/ExtensionPluginSlotBinding.kt",
            "feature/today/src/main/kotlin/cn/edu/haut/campus/feature/today/TodayExtensionSlot.kt"
        )
        bindings.forEach { path ->
            val text = File(repoRoot, path).readText()
            assertTrue(path, text.contains("ExtensionPluginRuntimeRegistry"))
            assertTrue(path, text.contains("rememberExtensionUiContributions"))
            assertTrue(path, !text.contains("upsertExtensionPlugin"))
        }
    }
}
