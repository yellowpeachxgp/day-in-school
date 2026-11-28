package cn.edu.haut.campus.feature.today.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import cn.edu.haut.campus.core.designsystem.HautCampusTheme
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.feature.today.state.TodayScreenStatus
import cn.edu.haut.campus.feature.today.state.TodayUiState
import cn.edu.haut.campus.feature.today.ui.LocalTodayClock
import cn.edu.haut.campus.feature.today.ui.TodayClock
import cn.edu.haut.campus.feature.today.ui.TodayScreen
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** 今日页完整状态矩阵 fixtures；不使用真实学生、课程代码或账号数据。 */
object TodayPreviewFixtures {
    val fixedNow: ZonedDateTime = ZonedDateTime.of(2026, 1, 7, 8, 0, 0, 0, HAUT_ZONE_ID)

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

    private val previewRule = CourseScheduleRule(
        id = "preview-rule-math-wed",
        courseId = previewCourse.id,
        termId = previewTerm.id,
        weekday = DayOfWeek.WEDNESDAY,
        startPeriod = 1,
        endPeriod = 2,
        weeks = (1..16).toSet(),
        rawRoom = "示例教学楼 A101"
    )

    private val contentState = TodayUiState(
        status = TodayScreenStatus.CONTENT,
        terms = listOf(previewTerm),
        currentTerm = previewTerm,
        courses = listOf(previewCourse),
        scheduleRules = listOf(previewRule),
        periodTimes = previewPeriodTimes
    )

    private val emptyState = TodayUiState(
        status = TodayScreenStatus.EMPTY,
        terms = listOf(previewTerm),
        currentTerm = previewTerm
    )

    /** 统一矩阵：Loading/Empty/Content/Partial/Stale/Error×2/NeedsPermission/NeedsLogin/Offline/Unsupported。 */
    val states: Map<TodayScreenStatus, TodayUiState> = mapOf(
        TodayScreenStatus.LOADING to emptyState.copy(status = TodayScreenStatus.LOADING),
        TodayScreenStatus.EMPTY to emptyState,
        TodayScreenStatus.CONTENT to contentState,
        TodayScreenStatus.PARTIAL to contentState.copy(
            status = TodayScreenStatus.PARTIAL,
            periodTimes = emptyList()
        ),
        TodayScreenStatus.STALE to contentState.copy(status = TodayScreenStatus.STALE),
        TodayScreenStatus.ERROR_RETRYABLE to emptyState.copy(status = TodayScreenStatus.ERROR_RETRYABLE),
        TodayScreenStatus.ERROR_TERMINAL to emptyState.copy(status = TodayScreenStatus.ERROR_TERMINAL),
        TodayScreenStatus.NEEDS_PERMISSION to emptyState.copy(status = TodayScreenStatus.NEEDS_PERMISSION),
        TodayScreenStatus.NEEDS_LOGIN to emptyState.copy(status = TodayScreenStatus.NEEDS_LOGIN),
        TodayScreenStatus.OFFLINE to contentState.copy(status = TodayScreenStatus.OFFLINE),
        TodayScreenStatus.UNSUPPORTED to emptyState.copy(status = TodayScreenStatus.UNSUPPORTED)
    )

    fun stateFor(status: TodayScreenStatus): TodayUiState = states.getValue(status)
}

@Composable
internal fun TodayPreviewHarness(state: TodayUiState) {
    CompositionLocalProvider(
        LocalTodayClock provides TodayClock { TodayPreviewFixtures.fixedNow }
    ) {
        HautCampusTheme(
            darkTheme = false,
            themeMode = "LIGHT",
            uiDesignStyleKey = "FLUTTER_M3",
            dynamicColor = false,
            themePaletteKey = "HAUT_BLUE"
        ) {
            TodayScreen(state = state, onAction = {})
        }
    }
}

@Preview(name = "Today Content", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun TodayContentPreview() {
    TodayPreviewHarness(TodayPreviewFixtures.stateFor(TodayScreenStatus.CONTENT))
}

@Preview(name = "Today Empty", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun TodayEmptyPreview() {
    TodayPreviewHarness(TodayPreviewFixtures.stateFor(TodayScreenStatus.EMPTY))
}

@Preview(name = "Today Retryable Error", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun TodayErrorPreview() {
    TodayPreviewHarness(TodayPreviewFixtures.stateFor(TodayScreenStatus.ERROR_RETRYABLE))
}
