package cn.edu.haut.campus.core.uiruntime

import cn.edu.haut.campus.core.uiruntime.preview.ExtensionSlotFixtureState
import cn.edu.haut.campus.core.uiruntime.preview.ExtensionUiPreviewFixtures
import cn.edu.haut.campus.core.uiruntime.preview.ExtensionUiPreviewHarness
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** AC-32：6 个槽位分别覆盖有插件 / 无插件 / 插件停用 / 插件出错。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class ExtensionSlotHostRoborazziTest {
    @Test fun homeCardsWithPlugin() = captureSlot("home.cards", ExtensionSlotFixtureState.WITH_PLUGIN)
    @Test fun homeCardsNoPlugin() = captureSlot("home.cards", ExtensionSlotFixtureState.NO_PLUGIN)
    @Test fun homeCardsDisabled() = captureSlot("home.cards", ExtensionSlotFixtureState.DISABLED)
    @Test fun homeCardsError() = captureSlot("home.cards", ExtensionSlotFixtureState.ERROR)
    @Test fun campusServicesWithPlugin() = captureSlot("campus.services", ExtensionSlotFixtureState.WITH_PLUGIN)
    @Test fun campusServicesNoPlugin() = captureSlot("campus.services", ExtensionSlotFixtureState.NO_PLUGIN)
    @Test fun campusServicesDisabled() = captureSlot("campus.services", ExtensionSlotFixtureState.DISABLED)
    @Test fun campusServicesError() = captureSlot("campus.services", ExtensionSlotFixtureState.ERROR)
    @Test fun libraryActionsWithPlugin() = captureSlot("library.actions", ExtensionSlotFixtureState.WITH_PLUGIN)
    @Test fun libraryActionsNoPlugin() = captureSlot("library.actions", ExtensionSlotFixtureState.NO_PLUGIN)
    @Test fun libraryActionsDisabled() = captureSlot("library.actions", ExtensionSlotFixtureState.DISABLED)
    @Test fun libraryActionsError() = captureSlot("library.actions", ExtensionSlotFixtureState.ERROR)
    @Test fun parcelCardsWithPlugin() = captureSlot("parcel.cards", ExtensionSlotFixtureState.WITH_PLUGIN)
    @Test fun parcelCardsNoPlugin() = captureSlot("parcel.cards", ExtensionSlotFixtureState.NO_PLUGIN)
    @Test fun parcelCardsDisabled() = captureSlot("parcel.cards", ExtensionSlotFixtureState.DISABLED)
    @Test fun parcelCardsError() = captureSlot("parcel.cards", ExtensionSlotFixtureState.ERROR)
    @Test fun parcelProvidersWithPlugin() = captureSlot("parcel.providers", ExtensionSlotFixtureState.WITH_PLUGIN)
    @Test fun parcelProvidersNoPlugin() = captureSlot("parcel.providers", ExtensionSlotFixtureState.NO_PLUGIN)
    @Test fun parcelProvidersDisabled() = captureSlot("parcel.providers", ExtensionSlotFixtureState.DISABLED)
    @Test fun parcelProvidersError() = captureSlot("parcel.providers", ExtensionSlotFixtureState.ERROR)
    @Test fun settingsEntriesWithPlugin() = captureSlot("settings.entries", ExtensionSlotFixtureState.WITH_PLUGIN)
    @Test fun settingsEntriesNoPlugin() = captureSlot("settings.entries", ExtensionSlotFixtureState.NO_PLUGIN)
    @Test fun settingsEntriesDisabled() = captureSlot("settings.entries", ExtensionSlotFixtureState.DISABLED)
    @Test fun settingsEntriesError() = captureSlot("settings.entries", ExtensionSlotFixtureState.ERROR)

    private fun captureSlot(slotId: String, state: ExtensionSlotFixtureState) {
        captureRoboImage {
            ExtensionUiPreviewHarness {
                ExtensionSlotHost(
                    slotId = slotId,
                    contributions = ExtensionUiPreviewFixtures.slotFixture(slotId, state),
                    showEmptyState = true
                )
            }
        }
    }
}
