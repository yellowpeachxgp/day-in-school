package cn.edu.haut.campus.feature.settings.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.ai.android.AiAndroidServiceRegistry
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.automationDatabase
import cn.edu.haut.campus.core.database.automationScope
import cn.edu.haut.campus.feature.settings.ai.ui.AiServiceScreenContent
import cn.edu.haut.campus.feature.settings.state.rememberSettingsSessionViewModelStoreOwner
import cn.edu.haut.campus.feature.settings.state.settingsSessionKey

/** AI 服务 Route：只装配 Profile 作用域 BYOK 服务与纯状态 UI。 */
@Composable
fun AiServiceRoute(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val sessionStates = remember(context) { AccountSessionCoordinator.getInstance(context).states }
    val scope = remember(repository) { repository.automationScope }
    val database = remember(repository) { repository.automationDatabase }
    val service = remember(context.applicationContext, scope, database) {
        AiAndroidServiceRegistry.forProfile(context.applicationContext, scope, database)
    }
    val gateway = remember(scope, service) { AndroidAiServiceGateway(scope, service) }
    val sessionKey = "ai-service-" + settingsSessionKey(repository.profileId, repository.sessionGeneration)
    val viewModelStoreOwner = rememberSettingsSessionViewModelStoreOwner(sessionKey)
    val viewModel: AiServiceViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = sessionKey,
        factory = AiServiceViewModel.factory(repository, gateway, sessionStates)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AiServiceScreenContent(state = state, onBack = onBack, actions = viewModel)
}
