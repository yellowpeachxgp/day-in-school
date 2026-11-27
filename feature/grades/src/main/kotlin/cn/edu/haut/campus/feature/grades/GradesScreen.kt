package cn.edu.haut.campus.feature.grades

import androidx.compose.runtime.Composable
import cn.edu.haut.campus.core.database.CampusServicesRepository
import cn.edu.haut.campus.core.database.ExamRepository
import cn.edu.haut.campus.core.database.GradesRepository
import cn.edu.haut.campus.core.database.TermRepository
import cn.edu.haut.campus.core.database.TimetableRepository

/** 学业页入口；实际装配在 [GradesRoute]，渲染在 ui/GradesScreen。 */
@Composable
fun GradesScreen(
    gradesRepository: GradesRepository,
    examRepository: ExamRepository,
    termRepository: TermRepository,
    timetableRepository: TimetableRepository,
    campusServicesRepository: CampusServicesRepository,
    profileId: String,
    sessionGeneration: Long?,
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
        gradesRepository = gradesRepository,
        examRepository = examRepository,
        termRepository = termRepository,
        timetableRepository = timetableRepository,
        campusServicesRepository = campusServicesRepository,
        profileId = profileId,
        sessionGeneration = sessionGeneration,
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
