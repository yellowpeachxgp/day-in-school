package cn.edu.haut.campus.feature.settings.hub

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.hub.ui.CampusCommunityScreenContent
import cn.edu.haut.campus.feature.settings.state.rememberSettingsSessionViewModelStoreOwner
import cn.edu.haut.campus.feature.settings.state.settingsSessionKey

/** 校园社区页 Route。 */
@Composable
fun CampusCommunityRoute(
    repository: CampusRepository,
    initialTab: Int = 0,
    onOpenMoments: () -> Unit = {},
    onBack: () -> Unit
) {
    val sessionKey = "campus-community-" + settingsSessionKey(repository.profileId, repository.sessionGeneration)
    val viewModelStoreOwner = rememberSettingsSessionViewModelStoreOwner(sessionKey)
    val viewModel: CampusCommunityViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = sessionKey,
        factory = CampusCommunityViewModel.factory(repository)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CampusCommunityScreenContent(
        state = state,
        actions = viewModel,
        initialTab = initialTab,
        onOpenMoments = onOpenMoments,
        onBack = onBack
    )
}
