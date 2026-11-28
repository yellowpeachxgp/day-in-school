package cn.edu.haut.campus.feature.settings.library.seat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.extension.ExtensionPluginSlot
import cn.edu.haut.campus.feature.settings.library.seat.state.LibrarySeatUiState
import cn.edu.haut.campus.feature.settings.library.seat.ui.LibrarySeatBookingScreenContent
import cn.edu.haut.campus.feature.settings.state.rememberSettingsSessionViewModelStoreOwner
import cn.edu.haut.campus.feature.settings.state.settingsSessionKey

/** 图书馆选座 Route。repository 为 null 时保持拆分前纯网页选座降级路径。 */
@Composable
fun LibrarySeatRoute(
    onBack: () -> Unit,
    repository: CampusRepository? = null,
    initialViewMode: Int = 1,
    onNavigateToXpeachCloud: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val sessionStates = remember(context) { AccountSessionCoordinator.getInstance(context).states }
    if (repository == null) {
        LibrarySeatBookingScreenContent(
            state = LibrarySeatUiState(),
            actions = null,
            onBack = onBack,
            initialViewMode = initialViewMode,
            onNavigateToXpeachCloud = onNavigateToXpeachCloud
        )
    } else {
        val sessionKey = "library-seat-" + settingsSessionKey(repository.profileId, repository.sessionGeneration)
        val viewModelStoreOwner = rememberSettingsSessionViewModelStoreOwner(sessionKey)
        val viewModel: LibrarySeatViewModel = viewModel(
            viewModelStoreOwner = viewModelStoreOwner,
            key = sessionKey,
            factory = LibrarySeatViewModel.factory(repository, sessionStates)
        )
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val extensionSlotContent: @Composable () -> Unit = {
            ExtensionPluginSlot(
                profileId = repository.profileId,
                schoolId = repository.schoolId,
                sessionGeneration = repository.sessionGeneration,
                slotId = "library.actions",
                allowedHostRoutes = if (onNavigateToXpeachCloud == null) {
                    emptySet()
                } else {
                    setOf("settings.extensions")
                },
                onOpenHostRoute = { route ->
                    if (route == "settings.extensions") onNavigateToXpeachCloud?.invoke()
                }
            )
        }
        LibrarySeatBookingScreenContent(
            state = state,
            actions = viewModel,
            onBack = onBack,
            initialViewMode = initialViewMode,
            onNavigateToXpeachCloud = onNavigateToXpeachCloud,
            extensionSlotContent = extensionSlotContent
        )
    }
}
