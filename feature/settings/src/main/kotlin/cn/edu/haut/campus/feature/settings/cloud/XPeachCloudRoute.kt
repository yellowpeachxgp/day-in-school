package cn.edu.haut.campus.feature.settings.cloud

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.cloud.ui.XPeachCloudAccountScreenContent

/** 云账号 Route：只装配状态与纯渲染 UI。 */
@Composable
fun XPeachCloudRoute(
    repository: CampusRepository,
    onBack: () -> Unit,
    onNavigateToCloudSeatBooking: () -> Unit = {}
) {
    val viewModel: XPeachCloudViewModel = viewModel(
        key = "xpeach-cloud-" + repository.profileId + "-" + repository.sessionGeneration,
        factory = XPeachCloudViewModel.factory(repository)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    XPeachCloudAccountScreenContent(
        state = state,
        actions = viewModel,
        onBack = onBack,
        onNavigateToCloudSeatBooking = onNavigateToCloudSeatBooking
    )
}
