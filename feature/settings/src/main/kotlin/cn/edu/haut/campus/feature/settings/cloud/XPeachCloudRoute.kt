package cn.edu.haut.campus.feature.settings.cloud

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.cloud.ui.XPeachCloudAccountScreenContent
import cn.edu.haut.campus.feature.settings.state.rememberSettingsSessionViewModelStoreOwner
import cn.edu.haut.campus.feature.settings.state.settingsSessionKey

/** 云账号 Route：只装配状态与纯渲染 UI。 */
@Composable
fun XPeachCloudRoute(
    repository: CampusRepository,
    onBack: () -> Unit,
    onNavigateToCloudSeatBooking: () -> Unit = {}
) {
    val context = LocalContext.current
    val sessionStates = remember(context) { AccountSessionCoordinator.getInstance(context).states }
    val sessionKey = "xpeach-cloud-" + settingsSessionKey(repository.profileId, repository.sessionGeneration)
    val viewModelStoreOwner = rememberSettingsSessionViewModelStoreOwner(sessionKey)
    val viewModel: XPeachCloudViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = sessionKey,
        factory = XPeachCloudViewModel.factory(repository, sessionStates)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    XPeachCloudAccountScreenContent(
        state = state,
        actions = viewModel,
        onBack = onBack,
        onNavigateToCloudSeatBooking = onNavigateToCloudSeatBooking
    )
}
