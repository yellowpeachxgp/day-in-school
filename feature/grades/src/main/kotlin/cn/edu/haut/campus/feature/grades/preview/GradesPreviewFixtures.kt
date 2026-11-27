package cn.edu.haut.campus.feature.grades.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.designsystem.HautCampusTheme
import cn.edu.haut.campus.core.model.AttemptType
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.feature.grades.state.GradesScreenStatus
import cn.edu.haut.campus.feature.grades.state.GradesUiState
import cn.edu.haut.campus.feature.grades.ui.GradesScreen
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * 学业页完整状态矩阵 fixtures。所有时间都固定，供 Preview 与 devbox Roborazzi 基线复用；
 * 不携带真实学生、真实课程代码或真实学校账号数据。
 */
object GradesPreviewFixtures {
    private val fixedSyncedAt: Instant = Instant.parse("2026-01-15T02:00:00Z")
    private val staleSyncedAt: Instant = Instant.parse("2025-09-01T02:00:00Z")

    private val previewTerm = Term(
        id = "2025-2026-1",
        academicYear = "2025-2026",
        termNumber = 1,
        startDate = LocalDate.of(2025, 9, 1),
        weekCount = 20,
        current = true
    )

    private val previewAttempts = listOf(
        GradeAttempt(
            id = "preview-attempt-math",
            termId = previewTerm.id,
            academicYear = previewTerm.academicYear,
            termNumber = previewTerm.termNumber,
            courseKey = "PX-MATH-01",
            name = "示例高等数学",
            category = CourseCategory.COMPULSORY,
            credits = BigDecimal("5.0"),
            rawGrade = "91",
            numericScore = BigDecimal("91"),
            gradePoint = BigDecimal("4.1"),
            attemptType = AttemptType.NORMAL,
            passed = true,
            syncedAt = fixedSyncedAt
        ),
        GradeAttempt(
            id = "preview-attempt-programming",
            termId = previewTerm.id,
            academicYear = previewTerm.academicYear,
            termNumber = previewTerm.termNumber,
            courseKey = "PX-CS-01",
            name = "示例程序设计基础",
            category = CourseCategory.COMPULSORY,
            credits = BigDecimal("4.0"),
            rawGrade = "86",
            numericScore = BigDecimal("86"),
            gradePoint = BigDecimal("3.6"),
            attemptType = AttemptType.NORMAL,
            passed = true,
            syncedAt = fixedSyncedAt
        ),
        GradeAttempt(
            id = "preview-attempt-elective",
            termId = "2024-2025-2",
            academicYear = "2024-2025",
            termNumber = 2,
            courseKey = "PX-ART-01",
            name = "示例艺术通识课",
            category = CourseCategory.PUBLIC_ELECTIVE,
            credits = BigDecimal("2.0"),
            rawGrade = "良好",
            numericScore = BigDecimal("85"),
            gradePoint = BigDecimal("3.5"),
            attemptType = AttemptType.FIVE_LEVEL,
            passed = true,
            syncedAt = fixedSyncedAt
        )
    )

    private val previewExams = listOf(
        ExamScheduleItem(
            id = "preview-exam-math",
            termId = previewTerm.id,
            courseCode = "PX-MATH-01",
            courseName = "示例高等数学",
            examBatchName = "示例期末考试批次",
            examStageLabel = "期末",
            examFormat = "闭卷笔试",
            rawTimeText = "2026年1月12日 09:00-11:00",
            examDate = LocalDate.of(2026, 1, 12),
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            rawRoom = "示例教学楼 A101",
            seatNumber = "12",
            syncedAt = fixedSyncedAt,
            originTermId = previewTerm.id
        )
    )

    private val previewProfile = StudentProfile(
        id = "preview-profile",
        schoolId = "preview-school",
        displayName = "预览用户",
        major = "示例专业",
        enrollmentYear = 2024
    )

    private val previewPrefs = AppPreferencesState(
        officialGpaValuePlain = "3.80",
        officialGpaNote = "预览教务汇总口径"
    )

    private val contentState = GradesUiState(
        status = GradesScreenStatus.CONTENT,
        currentTerm = previewTerm,
        attempts = previewAttempts,
        examSchedules = previewExams,
        courses = emptyList(),
        scheduleRules = emptyList(),
        prefs = previewPrefs,
        studentProfile = previewProfile
    )

    private val emptyState = contentState.copy(
        status = GradesScreenStatus.EMPTY,
        attempts = emptyList(),
        examSchedules = emptyList(),
        studentProfile = null,
        prefs = AppPreferencesState()
    )

    /** 统一矩阵：Loading/Empty/Content/Partial/Stale/Error×2/NeedsPermission/NeedsLogin/Offline/Unsupported。 */
    val states: Map<GradesScreenStatus, GradesUiState> = mapOf(
        GradesScreenStatus.LOADING to emptyState.copy(status = GradesScreenStatus.LOADING),
        GradesScreenStatus.EMPTY to emptyState,
        GradesScreenStatus.CONTENT to contentState,
        GradesScreenStatus.PARTIAL to contentState.copy(
            status = GradesScreenStatus.PARTIAL,
            examSchedules = emptyList(),
            studentProfile = null
        ),
        GradesScreenStatus.STALE to contentState.copy(
            status = GradesScreenStatus.STALE,
            attempts = previewAttempts.map { it.copy(syncedAt = staleSyncedAt) },
            examSchedules = previewExams.map { it.copy(syncedAt = staleSyncedAt) }
        ),
        GradesScreenStatus.ERROR_RETRYABLE to emptyState.copy(status = GradesScreenStatus.ERROR_RETRYABLE),
        GradesScreenStatus.ERROR_TERMINAL to emptyState.copy(status = GradesScreenStatus.ERROR_TERMINAL),
        GradesScreenStatus.NEEDS_PERMISSION to emptyState.copy(status = GradesScreenStatus.NEEDS_PERMISSION),
        GradesScreenStatus.NEEDS_LOGIN to emptyState.copy(status = GradesScreenStatus.NEEDS_LOGIN),
        GradesScreenStatus.OFFLINE to contentState.copy(
            status = GradesScreenStatus.OFFLINE,
            attempts = previewAttempts.map { it.copy(syncedAt = staleSyncedAt) },
            examSchedules = previewExams.map { it.copy(syncedAt = staleSyncedAt) }
        ),
        GradesScreenStatus.UNSUPPORTED to emptyState.copy(status = GradesScreenStatus.UNSUPPORTED)
    )

    fun stateFor(status: GradesScreenStatus): GradesUiState =
        states.getValue(status)
}

@Composable
internal fun GradesPreviewHarness(state: GradesUiState) {
    HautCampusTheme(
        darkTheme = false,
        themeMode = "LIGHT",
        uiDesignStyleKey = "FLUTTER_M3",
        dynamicColor = false,
        themePaletteKey = "HAUT_BLUE"
    ) {
        GradesScreen(state = state, onAction = {})
    }
}

@Preview(name = "Grades Content", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun GradesContentPreview() {
    GradesPreviewHarness(GradesPreviewFixtures.stateFor(GradesScreenStatus.CONTENT))
}

@Preview(name = "Grades Empty", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun GradesEmptyPreview() {
    GradesPreviewHarness(GradesPreviewFixtures.stateFor(GradesScreenStatus.EMPTY))
}

@Preview(name = "Grades Retryable Error", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun GradesErrorPreview() {
    GradesPreviewHarness(GradesPreviewFixtures.stateFor(GradesScreenStatus.ERROR_RETRYABLE))
}
