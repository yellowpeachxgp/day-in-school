package cn.edu.haut.campus.feature.automation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.automation.android.AutomationRuntime
import cn.edu.haut.campus.core.database.ActiveSession
import cn.edu.haut.campus.feature.automation.state.AutomationUiEffect
import cn.edu.haut.campus.feature.automation.ui.AutomationScreen

/** 自动化骨架 Route：只负责组合作用域 owner、当前账号 runtime、ViewModel 与一次性 Effect。 */
@Composable
fun AutomationRoute(activeSession: ActiveSession, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val sessionKey = automationSessionKey(activeSession.profileId, activeSession.generation)
    val viewModelStoreOwner = rememberAutomationViewModelStoreOwner(sessionKey)
    val runtime = AutomationRuntime.current(activeSession) ?: AutomationRuntime.bind(context, activeSession)
    val viewModel: AutomationViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = sessionKey,
        factory = AutomationViewModel.factory(runtime)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                AutomationUiEffect.NavigateBack -> onBack()
                is AutomationUiEffect.ShowMessage -> Unit // 当前消息由 UiState 横幅承载；不引入第二通道。
            }
        }
    }

    AutomationScreen(
        state = state,
        onAction = viewModel::dispatch
    )
}
