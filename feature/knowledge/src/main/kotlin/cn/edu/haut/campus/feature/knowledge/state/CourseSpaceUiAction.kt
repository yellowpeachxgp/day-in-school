package cn.edu.haut.campus.feature.knowledge.state

/** 课程空间用户意图；UI 只发送动作，不直接触碰 Repository、平台单例或导航回调。 */
sealed interface CourseSpaceUiAction {
    data class QueryChanged(val value: String) : CourseSpaceUiAction
    data class WeekSelected(val week: Int?) : CourseSpaceUiAction
    data class MaterialOpened(val materialId: String) : CourseSpaceUiAction
    data object ExportClicked : CourseSpaceUiAction
    data object RetryClicked : CourseSpaceUiAction
    data object PermissionRequested : CourseSpaceUiAction
    data object LoginRequested : CourseSpaceUiAction
}
