package cn.edu.haut.campus.feature.teamtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.ActiveSession
import cn.edu.haut.campus.feature.teamtime.state.TeamTimeUiEffect
import cn.edu.haut.campus.feature.teamtime.ui.TeamTimeScreen

/** 组队找时间装配边界：组合 owner、当前 Profile runtime、ViewModel 与一次性 Effect。 */
@Composable
fun TeamTimeRoute(
    activeSession: ActiveSession,
    onBack: () -> Unit,
    onNavigateToCloudLogin: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val sessionKey = teamTimeSessionKey(activeSession.profileId, activeSession.generation)
    val viewModelStoreOwner = rememberTeamTimeViewModelStoreOwner(sessionKey)
    val factory = remember(context, activeSession.profileId, activeSession.generation) {
        TeamTimeViewModel.factory(
            repositoryFactory = {
                createCommunityTeamTimeRepository(
                    context = context,
                    activeSession = activeSession,
                )
            },
        )
    }
    val viewModel: TeamTimeViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = sessionKey,
        factory = factory,
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                TeamTimeUiEffect.NavigateBack -> onBack()
                TeamTimeUiEffect.NavigateToCloudLogin -> onNavigateToCloudLogin()
            }
        }
    }

    TeamTimeScreen(state = state, onAction = viewModel::dispatch)
}
