package cn.edu.haut.campus.feature.timetable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.timetable.state.TimetableUiEffect
import cn.edu.haut.campus.feature.timetable.ui.TimetableScreen as TimetableUiScreen
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator

private class TimetableViewModelFactory(
    private val repository: CampusRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TimetableViewModel::class.java))
        return TimetableViewModel(repository) as T
    }
}

/** 课表页装配边界：只负责 ViewModel、一次性导航 Effect 与 Android 小组件刷新。 */
@Composable
fun TimetableRoute(
    repository: CampusRepository,
    onNavigateToImporter: () -> Unit,
    onNavigateToMapWithRoom: (String) -> Unit,
    resetTrigger: Int = 0
) {
    val context = LocalContext.current
    val sessionKey = timetableSessionKey(repository.profileId, repository.sessionGeneration)
    val viewModelStoreOwner = rememberTimetableViewModelStoreOwner(sessionKey)
    val factory = remember(repository) { TimetableViewModelFactory(repository) }
    val viewModel: TimetableViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = sessionKey,
        factory = factory
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                TimetableUiEffect.RefreshWidgets -> WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                TimetableUiEffect.NavigateToImporter -> onNavigateToImporter()
                is TimetableUiEffect.NavigateToMapWithRoom -> onNavigateToMapWithRoom(effect.room)
            }
        }
    }

    TimetableUiScreen(state = state, onAction = viewModel::onAction, resetTrigger = resetTrigger)
}
