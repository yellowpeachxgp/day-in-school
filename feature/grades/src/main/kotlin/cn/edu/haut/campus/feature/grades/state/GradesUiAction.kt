package cn.edu.haut.campus.feature.grades.state

import cn.edu.haut.campus.core.model.CourseCategory

/** 学业页用户意图。UI 只发送动作，不直接触碰 Repository、Intent 或导航回调。 */
sealed interface GradesUiAction {
    data object ResetToTop : GradesUiAction
    data class SelectTermFilter(val termId: String?) : GradesUiAction
    data class SelectCategoryFilter(val category: CourseCategory?) : GradesUiAction
    data class SelectSubTab(val index: Int) : GradesUiAction
    data class SelectTrendMetric(val index: Int) : GradesUiAction
    data object ToggleTrendSection : GradesUiAction
    data object ToggleSimulatorSection : GradesUiAction
    data class UpdateSimulatedNewCredits(val value: Float) : GradesUiAction
    data class UpdateSimulatedNewGpa(val value: Float) : GradesUiAction
    data object ToggleGpaPrivacyShield : GradesUiAction
    data object ExportGradesCsv : GradesUiAction
    data object OpenImporter : GradesUiAction
    data class OpenMapRoom(val room: String) : GradesUiAction
    data object OpenLibrarySeats : GradesUiAction
    data object OpenLibraryCatalog : GradesUiAction
    data object OpenFocus : GradesUiAction
    data object OpenEmptyClassrooms : GradesUiAction
    data object OpenCompetitions : GradesUiAction
}
