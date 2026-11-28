package cn.edu.haut.campus.feature.settings.root.backup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.CampusRepository

/** 备份中心 Route：只负责装配 ViewModel，渲染保持为纯状态输入。 */
@Composable
fun BackupCenterRouteSection(
    repository: CampusRepository,
    viewModelStoreOwner: ViewModelStoreOwner
) {
    val context = LocalContext.current
    val viewModel: BackupCenterViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = "backup-center-" + repository.profileId + "-" + repository.sessionGeneration,
        factory = BackupCenterViewModel.factory(context, repository)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackupCenterSection(state = state, actions = viewModel)
}
