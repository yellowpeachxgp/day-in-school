package cn.edu.haut.campus.feature.settings.root.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.feature.settings.extension.ExtensionPluginSlot
import cn.edu.haut.campus.feature.settings.root.home.state.SettingsHomeUiEffect
import cn.edu.haut.campus.feature.settings.root.home.ui.SettingsHomeSectionContent
import cn.edu.haut.campus.feature.settings.state.SettingsTransientResetRegistry
import cn.edu.haut.campus.feature.settings.state.settingsSessionKey

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
    onNavigateToAutomation: () -> Unit,
    onNavigateToAiService: () -> Unit,
    onFeedback: (String) -> Unit,
    onRefreshWidgets: () -> Unit,
    onShowImportHistory: () -> Unit,
    transientResetRegistry: SettingsTransientResetRegistry,
    viewModelStoreOwner: ViewModelStoreOwner
) {
    val context = LocalContext.current
    val sessionStates = remember(context) { AccountSessionCoordinator.getInstance(context).states }
    val sessionKey = "settings-home-" + settingsSessionKey(repository.profileId, repository.sessionGeneration)
    val viewModel: SettingsHomeViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = sessionKey,
        factory = SettingsHomeViewModel.factory(repository, sessionStates)
    )
    // 拆分前这些 remember 挂在根 SettingsScreen；这里只登记重置，二级页内部切换不触发。
    DisposableEffect(transientResetRegistry, viewModel) {
        transientResetRegistry.register(viewModel) { viewModel.resetTransientStateOnExit() }
        onDispose { }
    }
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
    val settingsEntriesContent: @Composable () -> Unit = {
        ExtensionPluginSlot(
            profileId = repository.profileId,
            schoolId = repository.schoolId,
            sessionGeneration = repository.sessionGeneration,
            slotId = "settings.entries",
            allowedHostRoutes = setOf("settings.extensions", "parcel.hub", "library.seats", "library.catalog", "campus.services"),
            onOpenHostRoute = { route ->
                when (route) {
                    "settings.extensions" -> onNavigateToXpeachCloud()
                    "parcel.hub" -> onNavigateToParcelHub()
                    "library.seats" -> onNavigateToLibrarySeats()
                    "library.catalog" -> onNavigateToLibraryCatalog()
                    "campus.services" -> onNavigateToSchoolCenter()
                }
            }
        )
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
        onNavigateToXpeachCloud = onNavigateToXpeachCloud,
        onNavigateToAutomation = onNavigateToAutomation,
        onNavigateToAiService = onNavigateToAiService,
        onShowImportHistory = onShowImportHistory,
        settingsEntriesContent = settingsEntriesContent
    )
}
