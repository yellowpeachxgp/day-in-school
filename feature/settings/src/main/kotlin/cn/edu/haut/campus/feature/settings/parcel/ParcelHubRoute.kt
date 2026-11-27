package cn.edu.haut.campus.feature.settings.parcel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.parcel.ui.ParcelHubScreenContent

/** 快递聚合页 Route。 */
@Composable
fun ParcelHubRoute(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val viewModel: ParcelHubViewModel = viewModel(
        key = "parcel-hub-" + repository.profileId + "-" + repository.sessionGeneration,
        factory = ParcelHubViewModel.factory(repository)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ParcelHubScreenContent(state = state, actions = viewModel, onBack = onBack)
}
