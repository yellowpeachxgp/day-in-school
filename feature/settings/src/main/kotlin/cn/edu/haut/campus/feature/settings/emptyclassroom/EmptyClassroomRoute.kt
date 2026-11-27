package cn.edu.haut.campus.feature.settings.emptyclassroom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.feature.settings.emptyclassroom.ui.EmptyClassroomScreenContent
import java.time.ZonedDateTime

/** 空教室 Route。 */
@Composable
fun EmptyClassroomRoute(
    repository: CampusRepository,
    onNavigateToMapWithRoom: (String) -> Unit,
    onBack: () -> Unit,
    nowProvider: () -> ZonedDateTime = { ZonedDateTime.now(HAUT_ZONE_ID) }
) {
    val viewModel: EmptyClassroomViewModel = viewModel(
        key = "empty-classroom-" + repository.profileId + "-" + repository.sessionGeneration,
        factory = EmptyClassroomViewModel.factory(repository)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    EmptyClassroomScreenContent(
        state = state,
        onNavigateToMapWithRoom = onNavigateToMapWithRoom,
        onBack = onBack,
        nowProvider = nowProvider
    )
}
