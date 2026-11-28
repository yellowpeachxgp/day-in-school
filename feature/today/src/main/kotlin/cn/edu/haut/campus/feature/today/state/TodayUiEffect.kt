package cn.edu.haut.campus.feature.today.state

/** 今日页一次性导航/平台事件；不作为渲染状态保存。 */
sealed interface TodayUiEffect {
    data object RefreshWidgets : TodayUiEffect
    data object NavigateToImporter : TodayUiEffect
    data object NavigateToSettings : TodayUiEffect
    data class NavigateToMapWithRoom(val room: String) : TodayUiEffect
    data object NavigateToFocus : TodayUiEffect
    data object NavigateToElectricity : TodayUiEffect
    data object NavigateToLibraryCatalog : TodayUiEffect
    data object NavigateToLibrarySeats : TodayUiEffect
    data object NavigateToEmptyClassrooms : TodayUiEffect
    data object NavigateToCommunity : TodayUiEffect
    data object NavigateToCompetitions : TodayUiEffect
    data object NavigateToMoments : TodayUiEffect
    data object NavigateToParcelHub : TodayUiEffect
    data object NavigateToGrades : TodayUiEffect
    data object NavigateToTimetable : TodayUiEffect
}
