package cn.edu.haut.campus.feature.settings.root.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.root.backup.BackupCenterRouteSection
import cn.edu.haut.campus.feature.settings.root.data.ui.SettingsDataSectionContent

/** 设置「外观、隐私与数据」分区 Route。 */
@Composable
fun SettingsDataRouteSection(
    repository: CampusRepository,
    onBack: (() -> Unit)?,
    isHautCampus: Boolean,
    onFeedback: (String) -> Unit,
    onRefreshWidgets: () -> Unit,
    viewModelStoreOwner: ViewModelStoreOwner
) {
    val viewModel: SettingsDataViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = "settings-data-" + repository.profileId + "-" + repository.sessionGeneration,
        factory = SettingsDataViewModel.factory(repository)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BackupCenterRouteSection(
            repository = repository,
            viewModelStoreOwner = viewModelStoreOwner
        )
        SettingsDataSectionContent(
            state = state,
            actions = viewModel,
            onBack = onBack,
            isHautCampus = isHautCampus,
            onFeedback = onFeedback,
            onRefreshWidgets = onRefreshWidgets
        )
    }
}
