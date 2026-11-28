package cn.edu.haut.campus.feature.knowledge

import androidx.compose.runtime.Immutable

/** 阶段 S 统一 11 态矩阵。 */
enum class CourseSpaceStatus {
    LOADING,
    EMPTY,
    CONTENT,
    PARTIAL,
    STALE,
    ERROR_RETRYABLE,
    ERROR_TERMINAL,
    NEEDS_PERMISSION,
    NEEDS_LOGIN,
    OFFLINE,
    UNSUPPORTED
}

@Immutable
data class CourseMaterialCardUi(
    val id: String,
    val title: String,
    val kindLabel: String,
    val teachingWeek: Int,
    val fileLabel: String,
    val extractionLabel: String,
    val extractionNeedsConfirmation: Boolean,
    val updatedLabel: String
)

@Immutable
data class CourseSearchHitUi(
    val id: String,
    val materialId: String,
    val title: String,
    val snippet: String,
    val locationLabel: String,
    val needsConfirmation: Boolean
)

@Immutable
data class CourseReviewTaskUi(
    val id: String,
    val title: String,
    val stateLabel: String,
    val dueLabel: String,
    val estimatedLabel: String
)

@Immutable
data class CourseDeadlineUi(
    val id: String,
    val title: String,
    val dueLabel: String,
    val inTimeline: Boolean
)

@Immutable
data class CourseSpaceUiState(
    val status: CourseSpaceStatus,
    val courseName: String,
    val termLabel: String,
    val selectedWeek: Int?,
    val availableWeeks: List<Int>,
    val query: String,
    val materials: List<CourseMaterialCardUi> = emptyList(),
    val searchHits: List<CourseSearchHitUi> = emptyList(),
    val deadlines: List<CourseDeadlineUi> = emptyList(),
    val reviewTasks: List<CourseReviewTaskUi> = emptyList(),
    val errorMessage: String? = null,
    val notice: String? = null,
    val exportEnabled: Boolean = true
)

sealed interface CourseSpaceAction {
    data class QueryChanged(val value: String) : CourseSpaceAction
    data class WeekSelected(val week: Int?) : CourseSpaceAction
    data class MaterialOpened(val materialId: String) : CourseSpaceAction
    data object ExportClicked : CourseSpaceAction
    data object RetryClicked : CourseSpaceAction
    data object PermissionRequested : CourseSpaceAction
    data object LoginRequested : CourseSpaceAction
}
