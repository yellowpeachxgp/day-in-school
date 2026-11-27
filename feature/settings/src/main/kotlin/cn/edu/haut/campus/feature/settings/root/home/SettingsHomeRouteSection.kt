package cn.edu.haut.campus.feature.settings.root.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.feature.settings.root.home.state.SettingsHomeUiEffect
import cn.edu.haut.campus.feature.settings.root.home.ui.SettingsHomeSectionContent

/** 设置一级「我的」页分区 Route；根壳只传入导航与共享反馈回调。 */
@Composable
fun SettingsHomeRouteSection(
    repository: CampusRepository,
    activeSchool: SchoolProfile,
    onNavigateToImporter: () -> Unit,
    onNavigateToElectricity: () -> Unit,
    onNavigateToLibraryCatalog: () -> Unit,
    onNavigateToLibrarySeats: () -> Unit,
    onNavigateToCloudSeatBooking: () -> Unit,
    onNavigateToFocus: () -> Unit,
    onNavigateToEmptyClassrooms: () -> Unit,
    onNavigateToCommunity: () -> Unit,
    onNavigateToCompetitions: () -> Unit,
    onNavigateToFeedbackChannels: () -> Unit,
    onNavigateToMoments: () -> Unit,
    onNavigateToSchoolCenter: () -> Unit,
    onNavigateToParcelHub: () -> Unit,
    onNavigateToXpeachCloud: () -> Unit,
    onFeedback: (String) -> Unit,
    onRefreshWidgets: () -> Unit
) {
    val viewModel: SettingsHomeViewModel = viewModel(
        key = "settings-home-" + repository.profileId + "-" + repository.sessionGeneration,
        factory = SettingsHomeViewModel.factory(repository)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, onFeedback, onRefreshWidgets, onNavigateToImporter) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsHomeUiEffect.NavigateToImporter -> onNavigateToImporter()
                SettingsHomeUiEffect.RefreshWidgets -> onRefreshWidgets()
                is SettingsHomeUiEffect.ShowFeedback -> onFeedback(effect.message)
            }
        }
    }
    SettingsHomeSectionContent(
        state = state,
        activeSchool = activeSchool,
        onAction = viewModel::onAction,
        onNavigateToImporter = onNavigateToImporter,
        onNavigateToElectricity = onNavigateToElectricity,
        onNavigateToLibraryCatalog = onNavigateToLibraryCatalog,
        onNavigateToLibrarySeats = onNavigateToLibrarySeats,
        onNavigateToCloudSeatBooking = onNavigateToCloudSeatBooking,
        onNavigateToFocus = onNavigateToFocus,
        onNavigateToEmptyClassrooms = onNavigateToEmptyClassrooms,
        onNavigateToCommunity = onNavigateToCommunity,
        onNavigateToCompetitions = onNavigateToCompetitions,
        onNavigateToFeedbackChannels = onNavigateToFeedbackChannels,
        onNavigateToMoments = onNavigateToMoments,
        onNavigateToSchoolCenter = onNavigateToSchoolCenter,
        onNavigateToParcelHub = onNavigateToParcelHub,
        onNavigateToXpeachCloud = onNavigateToXpeachCloud
    )
}
