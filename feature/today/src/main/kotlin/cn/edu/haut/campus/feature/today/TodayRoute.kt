package cn.edu.haut.campus.feature.today

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
import cn.edu.haut.campus.core.reminder.ReminderCoordinator
import cn.edu.haut.campus.feature.today.state.TodayUiEffect
import cn.edu.haut.campus.feature.today.ui.TodayScreen as TodayUiScreen
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator

private class TodayViewModelFactory(
    private val repository: CampusRepository,
    private val reminderCoordinator: ReminderCoordinator,
    private val writeExecutor: TodayWriteExecutor
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TodayViewModel::class.java))
        return TodayViewModel(repository, reminderCoordinator, writeExecutor) as T
    }
}

/** 今日页装配边界：只负责 ViewModel、一次性导航 Effect 与 Android 小组件刷新。 */
@Composable
fun TodayRoute(
    repository: CampusRepository,
    onNavigateToMapWithRoom: (String) -> Unit,
    onNavigateToImporter: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToFocus: () -> Unit = {},
    onNavigateToElectricity: () -> Unit = {},
    onNavigateToLibraryCatalog: () -> Unit = {},
    onNavigateToLibrarySeats: () -> Unit = {},
    onNavigateToEmptyClassrooms: () -> Unit = {},
    onNavigateToCommunity: () -> Unit = {},
    onNavigateToCompetitions: () -> Unit = onNavigateToCommunity,
    onNavigateToMoments: () -> Unit = onNavigateToCommunity,
    onNavigateToParcelHub: () -> Unit = {},
    onNavigateToGrades: () -> Unit = {},
    onNavigateToTimetable: () -> Unit = {},
    resetTrigger: Int = 0
) {
    val context = LocalContext.current
    val sessionKey = todaySessionKey(repository.profileId, repository.sessionGeneration)
    val viewModelStoreOwner = rememberTodayViewModelStoreOwner(sessionKey)
    val reminderCoordinator = remember(context) { ReminderCoordinator.getInstance(context) }
    val writeExecutor = remember(context.applicationContext) {
        TodayWriteExecutor.android(context.applicationContext)
    }
    val factory = remember(repository, reminderCoordinator, writeExecutor) {
        TodayViewModelFactory(repository, reminderCoordinator, writeExecutor)
    }
    val viewModel: TodayViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = sessionKey,
        factory = factory
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                TodayUiEffect.RefreshWidgets -> WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                TodayUiEffect.NavigateToImporter -> onNavigateToImporter()
                TodayUiEffect.NavigateToSettings -> onNavigateToSettings()
                is TodayUiEffect.NavigateToMapWithRoom -> onNavigateToMapWithRoom(effect.room)
                TodayUiEffect.NavigateToFocus -> onNavigateToFocus()
                TodayUiEffect.NavigateToElectricity -> onNavigateToElectricity()
                TodayUiEffect.NavigateToLibraryCatalog -> onNavigateToLibraryCatalog()
                TodayUiEffect.NavigateToLibrarySeats -> onNavigateToLibrarySeats()
                TodayUiEffect.NavigateToEmptyClassrooms -> onNavigateToEmptyClassrooms()
                TodayUiEffect.NavigateToCommunity -> onNavigateToCommunity()
                TodayUiEffect.NavigateToCompetitions -> onNavigateToCompetitions()
                TodayUiEffect.NavigateToMoments -> onNavigateToMoments()
                TodayUiEffect.NavigateToParcelHub -> onNavigateToParcelHub()
                TodayUiEffect.NavigateToGrades -> onNavigateToGrades()
                TodayUiEffect.NavigateToTimetable -> onNavigateToTimetable()
            }
        }
    }

    TodayUiScreen(state = state, onAction = viewModel::onAction, resetTrigger = resetTrigger)
}
