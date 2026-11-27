package cn.edu.haut.campus.feature.grades.state

/** 学业页一次性平台/导航事件；不作为渲染状态保存。 */
sealed interface GradesUiEffect {
    data class ShareGradesCsv(val csv: String) : GradesUiEffect
    data object NavigateToImporter : GradesUiEffect
    data class NavigateToMapWithRoom(val room: String) : GradesUiEffect
    data object NavigateToLibrarySeats : GradesUiEffect
    data object NavigateToLibraryCatalog : GradesUiEffect
    data object NavigateToFocus : GradesUiEffect
    data object NavigateToEmptyClassrooms : GradesUiEffect
    data object NavigateToCompetitions : GradesUiEffect
}
