package cn.edu.haut.campus.feature.settings.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.reminder.focus.FocusCoordinator
import cn.edu.haut.campus.core.reminder.focus.FocusDeliveryTracker
import cn.edu.haut.campus.feature.settings.focus.ui.StudyFocusScreenContent
import cn.edu.haut.campus.feature.settings.state.rememberSettingsSessionViewModelStoreOwner
import cn.edu.haut.campus.feature.settings.state.settingsSessionKey

/** 自习专注 Route：只负责创建平台 FocusCoordinator 并装配状态。 */
@Composable
fun StudyFocusRoute(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val focusCoordinator = remember(repository.profileId) { FocusCoordinator.getInstance(context) }
    val diagnosticProvider = remember(context) {
        { FocusDeliveryTracker.buildDiagnosticSummary(context, FocusDeliveryTracker.KIND_TEST) }
    }
    val sessionKey = "study-focus-" + settingsSessionKey(repository.profileId, repository.sessionGeneration)
    val viewModelStoreOwner = rememberSettingsSessionViewModelStoreOwner(sessionKey)
    val viewModel: StudyFocusViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = sessionKey,
        factory = StudyFocusViewModel.factory(repository, focusCoordinator, diagnosticProvider)
    )
    DisposableEffect(viewModel) {
        onDispose { viewModel.resetTransientStateOnExit() }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StudyFocusScreenContent(state = state, onBack = onBack, onAction = viewModel::onAction)
}
