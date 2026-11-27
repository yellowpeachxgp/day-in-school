package cn.edu.haut.campus.feature.settings.library.seat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.library.seat.state.LibrarySeatUiState
import cn.edu.haut.campus.feature.settings.library.seat.ui.LibrarySeatBookingScreenContent

/** 图书馆选座 Route。repository 为 null 时保持拆分前纯网页选座降级路径。 */
@Composable
fun LibrarySeatRoute(
    onBack: () -> Unit,
    repository: CampusRepository? = null,
    initialViewMode: Int = 1,
    onNavigateToXpeachCloud: (() -> Unit)? = null
) {
    if (repository == null) {
        LibrarySeatBookingScreenContent(
            state = LibrarySeatUiState(),
            actions = null,
            onBack = onBack,
            initialViewMode = initialViewMode,
            onNavigateToXpeachCloud = onNavigateToXpeachCloud
        )
    } else {
        val viewModel: LibrarySeatViewModel = viewModel(
            key = "library-seat-" + repository.profileId + "-" + repository.sessionGeneration,
            factory = LibrarySeatViewModel.factory(repository)
        )
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        LibrarySeatBookingScreenContent(
            state = state,
            actions = viewModel,
            onBack = onBack,
            initialViewMode = initialViewMode,
            onNavigateToXpeachCloud = onNavigateToXpeachCloud
        )
    }
}
