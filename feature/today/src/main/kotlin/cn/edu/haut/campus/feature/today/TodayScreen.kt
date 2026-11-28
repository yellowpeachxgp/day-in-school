package cn.edu.haut.campus.feature.today

import androidx.compose.runtime.Composable
import cn.edu.haut.campus.core.database.CampusRepository

/** home.cards 宿主槽的真实接线集中于公开兼容入口文件，Route 只负责状态装配。 */
@Composable
internal fun TodayHostExtensionSlot(
    profileId: String,
    schoolId: String,
    sessionGeneration: Long?,
    onNavigateToParcelHub: () -> Unit,
    onNavigateToLibrarySeats: () -> Unit,
    onNavigateToLibraryCatalog: () -> Unit
) {
    TodayExtensionSlot(
        profileId = profileId,
        schoolId = schoolId,
        sessionGeneration = sessionGeneration,
        onNavigateToParcelHub = onNavigateToParcelHub,
        onNavigateToLibrarySeats = onNavigateToLibrarySeats,
        onNavigateToLibraryCatalog = onNavigateToLibraryCatalog
    )
}

/**
 * 公开兼容入口。签名必须与拆分前逐字一致；调用方仍按 Repository + 导航回调使用，
 * 实际装配已迁移到 [TodayRoute] → [TodayViewModel] → state/ui。
 */
@Composable
fun TodayScreen(
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
    TodayRoute(
        repository = repository,
        onNavigateToMapWithRoom = onNavigateToMapWithRoom,
        onNavigateToImporter = onNavigateToImporter,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToFocus = onNavigateToFocus,
        onNavigateToElectricity = onNavigateToElectricity,
        onNavigateToLibraryCatalog = onNavigateToLibraryCatalog,
        onNavigateToLibrarySeats = onNavigateToLibrarySeats,
        onNavigateToEmptyClassrooms = onNavigateToEmptyClassrooms,
        onNavigateToCommunity = onNavigateToCommunity,
        onNavigateToCompetitions = onNavigateToCompetitions,
        onNavigateToMoments = onNavigateToMoments,
        onNavigateToParcelHub = onNavigateToParcelHub,
        onNavigateToGrades = onNavigateToGrades,
        onNavigateToTimetable = onNavigateToTimetable,
        resetTrigger = resetTrigger
    )
}
