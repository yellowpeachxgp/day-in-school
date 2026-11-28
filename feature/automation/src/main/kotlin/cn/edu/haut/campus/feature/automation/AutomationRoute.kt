package cn.edu.haut.campus.feature.automation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.automation.android.AutomationRuntime
import cn.edu.haut.campus.core.database.ActiveSession
import cn.edu.haut.campus.feature.automation.state.AutomationUiAction
import cn.edu.haut.campus.feature.automation.ui.AutomationScreen

/** 自动化骨架 Route：只负责组合当前账号 runtime、ViewModel 与纯 UI。 */
@Composable
fun AutomationRoute(activeSession: ActiveSession, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val runtime = AutomationRuntime.current(activeSession) ?: AutomationRuntime.bind(context, activeSession)
    val viewModel: AutomationViewModel = viewModel(
        key = "automation-" + activeSession.profileId + "-" + activeSession.generation,
        factory = AutomationViewModel.factory(runtime)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AutomationScreen(
        state = state,
        onAction = { action ->
            if (action == AutomationUiAction.Back) onBack() else viewModel.dispatch(action)
        }
    )
}
