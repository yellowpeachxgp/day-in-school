package cn.edu.haut.campus.feature.settings.root.academic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.reminder.ReminderCoordinator
import cn.edu.haut.campus.feature.settings.root.academic.ui.SettingsAcademicSectionContent

/** 设置二级「学期/小组件/提醒」分区 Route。 */
@Composable
fun SettingsAcademicRouteSection(
    repository: CampusRepository,
    onNavigateToImporter: () -> Unit,
    onFeedback: (String) -> Unit,
    onRefreshWidgets: () -> Unit
) {
    val context = LocalContext.current
    val reminderCoordinator = remember(context) { ReminderCoordinator.getInstance(context) }
    val viewModel: SettingsAcademicViewModel = viewModel(
        key = "settings-academic-" + repository.profileId + "-" + repository.sessionGeneration,
        factory = SettingsAcademicViewModel.factory(repository, reminderCoordinator)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsAcademicSectionContent(
        state = state,
        actions = viewModel,
        onNavigateToImporter = onNavigateToImporter,
        onFeedback = onFeedback,
        onRefreshWidgets = onRefreshWidgets
    )
}
