package cn.edu.haut.campus.feature.settings.dorm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.dorm.ui.DormElectricityScreenContent

/** 宿舍水电 Route：只装配 ViewModel 与纯渲染 UI。 */
@Composable
fun DormElectricityRoute(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val viewModel: DormElectricityViewModel = viewModel(
        key = "dorm-electricity-" + repository.profileId + "-" + repository.sessionGeneration,
        factory = DormElectricityViewModel.factory(repository)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DormElectricityScreenContent(
        state = state,
        actions = viewModel,
        onBack = onBack
    )
}
