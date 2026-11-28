package cn.edu.haut.campus.feature.timetable.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import cn.edu.haut.campus.core.designsystem.HautCampusTheme
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.PeriodTimeScheme
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.feature.timetable.state.TimetableScreenStatus
import cn.edu.haut.campus.feature.timetable.state.TimetableUiState
import cn.edu.haut.campus.feature.timetable.state.UnscheduledPracticalCourseUiModel
import cn.edu.haut.campus.feature.timetable.ui.LocalTimetableClock
import cn.edu.haut.campus.feature.timetable.ui.TimetableClock
import cn.edu.haut.campus.feature.timetable.ui.TimetableScreen
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** 课表页完整状态矩阵 fixtures；不使用真实学生、课程代码或账号数据。 */
object TimetablePreviewFixtures {
    val fixedDate: LocalDate = LocalDate.of(2026, 1, 7)

    private val previewTerm = Term(
        id = "2025-2026-1",
        academicYear = "2025-2026",
        termNumber = 1,
        startDate = LocalDate.of(2026, 1, 5),
        weekCount = 20,
        current = true
    )

    private fun period(no: Int, start: LocalTime, end: LocalTime) = PeriodTime(
        id = "preview-period-" + no,
        timeTableId = previewTerm.timeTableId,
        periodNo = no,
        startLocalTime = start,
        endLocalTime = end
    )

    private val previewPeriodTimes = listOf(
        period(1, LocalTime.of(8, 30), LocalTime.of(9, 15)),
        period(2, LocalTime.of(9, 20), LocalTime.of(10, 5)),
        period(3, LocalTime.of(10, 25), LocalTime.of(11, 10)),
        period(4, LocalTime.of(11, 15), LocalTime.of(12, 0)),
        period(5, LocalTime.of(14, 0), LocalTime.of(14, 45)),
        period(6, LocalTime.of(14, 50), LocalTime.of(15, 35)),
        period(7, LocalTime.of(15, 55), LocalTime.of(16, 40)),
        period(8, LocalTime.of(16, 45), LocalTime.of(17, 30)),
        period(9, LocalTime.of(19, 0), LocalTime.of(19, 45)),
        period(10, LocalTime.of(19, 50), LocalTime.of(20, 35))
    )

    private val previewCourse = Course(
        id = "preview-course-math",
        name = "示例高等数学",
        teacher = "示例教师",
        colorArgb = 0xFF0072BC
    )

    private val previewRules = listOf(
        CourseScheduleRule(
            id = "preview-rule-math-wed",
            courseId = previewCourse.id,
            termId = previewTerm.id,
            weekday = DayOfWeek.WEDNESDAY,
            startPeriod = 1,
            endPeriod = 2,
            weeks = (1..16).toSet(),
            rawRoom = "示例教学楼 A101"
        ),
        CourseScheduleRule(
            id = "preview-rule-math-fri",
            courseId = previewCourse.id,
            termId = previewTerm.id,
            weekday = DayOfWeek.FRIDAY,
            startPeriod = 3,
            endPeriod = 4,
            weeks = (1..16).toSet(),
            rawRoom = "示例教学楼 B202"
        )
    )

    private val previewScheme = PeriodTimeScheme(
        id = "preview-scheme-exam-week",
        termId = previewTerm.id,
        timeTableId = previewTerm.timeTableId,
        name = "示例考试周作息",
        startDate = LocalDate.of(2026, 1, 12),
        endDate = LocalDate.of(2026, 1, 18)
    )

    private val previewUnscheduledPractical = UnscheduledPracticalCourseUiModel(
        termId = previewTerm.id,
        sourceKey = "preview-practical-key",
        sourceId = "preview-practical-source",
        name = "示例实践训练",
        teacher = "示例教师",
        creditPlain = "2.0",
        rawWeeks = "14-16周",
        category = "集中实践",
        syncedAtEpochMilli = 0L
    )

    private val contentState = TimetableUiState(
        status = TimetableScreenStatus.CONTENT,
        terms = listOf(previewTerm),
        currentTerm = previewTerm,
        courses = listOf(previewCourse),
        scheduleRules = previewRules,
        periodTimes = previewPeriodTimes,
        periodTimeSchemes = listOf(previewScheme),
        unscheduledCourses = listOf(previewUnscheduledPractical)
    )

    private val emptyState = TimetableUiState(
        status = TimetableScreenStatus.EMPTY,
        terms = listOf(previewTerm),
        currentTerm = previewTerm
    )

    /** 统一矩阵：Loading/Empty/Content/Partial/Stale/Error×2/NeedsPermission/NeedsLogin/Offline/Unsupported。 */
    val states: Map<TimetableScreenStatus, TimetableUiState> = mapOf(
        TimetableScreenStatus.LOADING to emptyState.copy(status = TimetableScreenStatus.LOADING),
        TimetableScreenStatus.EMPTY to emptyState,
        TimetableScreenStatus.CONTENT to contentState,
        TimetableScreenStatus.PARTIAL to contentState.copy(
            status = TimetableScreenStatus.PARTIAL,
            periodTimes = emptyList()
        ),
        TimetableScreenStatus.STALE to contentState.copy(status = TimetableScreenStatus.STALE),
        TimetableScreenStatus.ERROR_RETRYABLE to emptyState.copy(status = TimetableScreenStatus.ERROR_RETRYABLE),
        TimetableScreenStatus.ERROR_TERMINAL to emptyState.copy(status = TimetableScreenStatus.ERROR_TERMINAL),
        TimetableScreenStatus.NEEDS_PERMISSION to emptyState.copy(status = TimetableScreenStatus.NEEDS_PERMISSION),
        TimetableScreenStatus.NEEDS_LOGIN to emptyState.copy(status = TimetableScreenStatus.NEEDS_LOGIN),
        TimetableScreenStatus.OFFLINE to contentState.copy(status = TimetableScreenStatus.OFFLINE),
        TimetableScreenStatus.UNSUPPORTED to emptyState.copy(status = TimetableScreenStatus.UNSUPPORTED)
    )

    fun stateFor(status: TimetableScreenStatus): TimetableUiState = states.getValue(status)
}

@Composable
internal fun TimetablePreviewHarness(state: TimetableUiState) {
    CompositionLocalProvider(
        LocalTimetableClock provides TimetableClock { TimetablePreviewFixtures.fixedDate }
    ) {
        HautCampusTheme(
            darkTheme = false,
            themeMode = "LIGHT",
            uiDesignStyleKey = "FLUTTER_M3",
            dynamicColor = false,
            themePaletteKey = "HAUT_BLUE"
        ) {
            TimetableScreen(state = state, onAction = {})
        }
    }
}

@Preview(name = "Timetable Content", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun TimetableContentPreview() {
    TimetablePreviewHarness(TimetablePreviewFixtures.stateFor(TimetableScreenStatus.CONTENT))
}

@Preview(name = "Timetable Empty", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun TimetableEmptyPreview() {
    TimetablePreviewHarness(TimetablePreviewFixtures.stateFor(TimetableScreenStatus.EMPTY))
}

@Preview(name = "Timetable Retryable Error", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun TimetableErrorPreview() {
    TimetablePreviewHarness(TimetablePreviewFixtures.stateFor(TimetableScreenStatus.ERROR_RETRYABLE))
}
