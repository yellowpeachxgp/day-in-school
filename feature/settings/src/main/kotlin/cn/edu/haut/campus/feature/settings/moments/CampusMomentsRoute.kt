package cn.edu.haut.campus.feature.settings.moments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.moments.ui.CampusMomentsScreenContent
import cn.edu.haut.campus.feature.settings.state.rememberSettingsSessionViewModelStoreOwner
import cn.edu.haut.campus.feature.settings.state.settingsSessionKey

/** Moments Route。 */
@Composable
fun CampusMomentsRoute(
    repository: CampusRepository,
    initialTab: Int = 0,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val sessionStates = remember(context) { AccountSessionCoordinator.getInstance(context).states }
    val sessionKey = "campus-moments-" + settingsSessionKey(repository.profileId, repository.sessionGeneration)
    val viewModelStoreOwner = rememberSettingsSessionViewModelStoreOwner(sessionKey)
    val viewModel: CampusMomentsViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = sessionKey,
        factory = CampusMomentsViewModel.factory(repository, sessionStates)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CampusMomentsScreenContent(
        state = state,
        actions = viewModel,
        initialTab = initialTab,
        onBack = onBack
    )
}
