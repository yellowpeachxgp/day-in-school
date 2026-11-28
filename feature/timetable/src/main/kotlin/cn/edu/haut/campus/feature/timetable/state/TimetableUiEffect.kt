package cn.edu.haut.campus.feature.timetable.state

/** 课表页一次性导航/平台事件；不作为渲染状态保存。 */
sealed interface TimetableUiEffect {
    data object RefreshWidgets : TimetableUiEffect
    data object NavigateToImporter : TimetableUiEffect
    data class NavigateToMapWithRoom(val room: String) : TimetableUiEffect
}
