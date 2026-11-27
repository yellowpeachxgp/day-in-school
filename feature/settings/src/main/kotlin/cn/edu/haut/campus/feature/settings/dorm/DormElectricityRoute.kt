package cn.edu.haut.campus.feature.settings.dorm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.dorm.ui.DormElectricityScreenContent

/** 宿舍水电 Route：只装配 ViewModel 与纯渲染 UI。 */
@Composable
fun DormElectricityRoute(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val sessionStates = remember(context) { AccountSessionCoordinator.getInstance(context).states }
    val viewModel: DormElectricityViewModel = viewModel(
        key = "dorm-electricity-" + repository.profileId + "-" + repository.sessionGeneration,
        factory = DormElectricityViewModel.factory(repository, sessionStates)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DormElectricityScreenContent(
        state = state,
        actions = viewModel,
        onBack = onBack
    )
}
