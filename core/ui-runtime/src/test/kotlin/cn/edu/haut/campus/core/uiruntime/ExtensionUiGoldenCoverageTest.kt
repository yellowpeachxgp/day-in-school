package cn.edu.haut.campus.core.uiruntime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Machine-checkable AC-31/AC-32 golden inventory; files are recorded only on devbox Linux. */
class ExtensionUiGoldenCoverageTest {
    @Test
    fun nodeAndSlotGoldenInventoryCoversRequiredMatrix() {
        val dir = File("src/test/roborazzi/debug")
        assertTrue("Roborazzi golden directory missing: " + dir.absolutePath, dir.isDirectory)
        val names = dir.listFiles()?.map { it.name }.orEmpty()

        listOf("text", "metric", "keyValue", "badge", "actionButton", "list", "emptyState", "errorState").forEach { node ->
            listOf("Light", "Dark", "Font200").forEach { variant ->
                val expected = "cn.edu.haut.campus.core.uiruntime.FeatureUiRendererRoborazziTest." + node + variant + ".png"
                assertTrue("missing node golden: " + expected, expected in names)
            }
        }
        mapOf(
            "homeCards" to "home.cards",
            "campusServices" to "campus.services",
            "libraryActions" to "library.actions",
            "parcelCards" to "parcel.cards",
            "parcelProviders" to "parcel.providers",
            "settingsEntries" to "settings.entries"
        ).keys.forEach { slot ->
            listOf("WithPlugin", "NoPlugin", "Disabled", "Error").forEach { state ->
                val expected = "cn.edu.haut.campus.core.uiruntime.ExtensionSlotHostRoborazziTest." + slot + state + ".png"
                assertTrue("missing slot golden: " + expected, expected in names)
            }
        }
        assertTrue(names.any { it.endsWith("unknownNodeDegradesToErrorStateLight.png") })
        assertEquals("exact golden inventory changed unexpectedly", 49, names.count { it.endsWith(".png") })
    }
}
