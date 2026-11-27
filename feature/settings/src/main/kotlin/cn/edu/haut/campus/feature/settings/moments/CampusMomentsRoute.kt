package cn.edu.haut.campus.feature.settings.moments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.moments.ui.CampusMomentsScreenContent

/** Moments Route。 */
@Composable
fun CampusMomentsRoute(
    repository: CampusRepository,
    initialTab: Int = 0,
    onBack: () -> Unit
) {
    val viewModel: CampusMomentsViewModel = viewModel(
        key = "campus-moments-" + repository.profileId + "-" + repository.sessionGeneration,
        factory = CampusMomentsViewModel.factory(repository)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CampusMomentsScreenContent(
        state = state,
        actions = viewModel,
        initialTab = initialTab,
        onBack = onBack
    )
}
