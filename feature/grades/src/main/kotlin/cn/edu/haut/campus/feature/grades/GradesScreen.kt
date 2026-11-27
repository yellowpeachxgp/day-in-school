package cn.edu.haut.campus.feature.grades

import androidx.compose.runtime.Composable
import cn.edu.haut.campus.core.database.CampusRepository

/** 兼容既有调用方的薄入口；实际装配在 [GradesRoute]，渲染在 ui/GradesScreen。 */
@Composable
fun GradesScreen(
    repository: CampusRepository,
    onNavigateToImporter: () -> Unit,
    onNavigateToMapWithRoom: (String) -> Unit = {},
    onNavigateToLibrarySeats: () -> Unit = {},
    onNavigateToLibraryCatalog: () -> Unit = {},
    onNavigateToFocus: () -> Unit = {},
    onNavigateToEmptyClassrooms: () -> Unit = {},
    onNavigateToCompetitions: () -> Unit = {},
    resetTrigger: Int = 0
) {
    GradesRoute(
        repository = repository,
        onNavigateToImporter = onNavigateToImporter,
        onNavigateToMapWithRoom = onNavigateToMapWithRoom,
        onNavigateToLibrarySeats = onNavigateToLibrarySeats,
        onNavigateToLibraryCatalog = onNavigateToLibraryCatalog,
        onNavigateToFocus = onNavigateToFocus,
        onNavigateToEmptyClassrooms = onNavigateToEmptyClassrooms,
        onNavigateToCompetitions = onNavigateToCompetitions,
        resetTrigger = resetTrigger
    )
}
