package cn.edu.haut.campus.feature.knowledge.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import cn.edu.haut.campus.core.designsystem.HautCampusTheme
import cn.edu.haut.campus.feature.knowledge.CourseDeadlineUi
import cn.edu.haut.campus.feature.knowledge.CourseMaterialCardUi
import cn.edu.haut.campus.feature.knowledge.CourseSearchHitUi
import cn.edu.haut.campus.feature.knowledge.CourseSpaceScreen
import cn.edu.haut.campus.feature.knowledge.CourseSpaceStatus
import cn.edu.haut.campus.feature.knowledge.CourseSpaceUiState

/** W3-04 课程空间骨架 UI 状态夹具；覆盖阶段 S 要求的完整 11 态矩阵。 */
object CourseSpacePreviewFixtures {
    private val sampleMaterials = listOf(
        CourseMaterialCardUi(
            id = "mat-linear-w7-note",
            title = "线代第七周笔记",
            kindLabel = "笔记",
            teachingWeek = 7,
            fileLabel = "week7.txt",
            extractionLabel = "文本已确认",
            extractionNeedsConfirmation = false,
            updatedLabel = "今天 08:30"
        ),
        CourseMaterialCardUi(
            id = "mat-linear-w7-pdf",
            title = "第五章课件",
            kindLabel = "PDF",
            teachingWeek = 7,
            fileLabel = "chapter5.pdf",
            extractionLabel = "抽取待确认",
            extractionNeedsConfirmation = true,
            updatedLabel = "昨天 21:10"
        ),
        CourseMaterialCardUi(
            id = "mat-linear-w6-photo",
            title = "黑板照片：特征值",
            kindLabel = "照片",
            teachingWeek = 6,
            fileLabel = "board.jpg",
            extractionLabel = "OCR 待确认",
            extractionNeedsConfirmation = true,
            updatedLabel = "周一 12:02"
        )
    )
    private val sampleHits = listOf(
        CourseSearchHitUi(
            id = "hit-1",
            materialId = "mat-linear-w7-pdf",
            title = "第五章课件",
            snippet = "矩阵可对角化的判定：存在 n 个线性无关的特征向量。",
            locationLabel = "第 3 页",
            needsConfirmation = true
        ),
        CourseSearchHitUi(
            id = "hit-2",
            materialId = "mat-linear-w6-photo",
            title = "黑板照片：特征值",
            snippet = "特征值与特征向量板书照片命中。",
            locationLabel = "图片区域",
            needsConfirmation = true
        )
    )
    private val sampleDeadlines = listOf(
        CourseDeadlineUi(
            id = "due-1",
            title = "第七周作业",
            dueLabel = "3 月 9 日 20:00 截止",
            inTimeline = true
        )
    )
    private val base = CourseSpaceUiState(
        status = CourseSpaceStatus.CONTENT,
        courseName = "线性代数",
        termLabel = "2025 秋 · 第 7 周",
        selectedWeek = 7,
        availableWeeks = listOf(5, 6, 7, 8),
        query = "",
        materials = sampleMaterials,
        deadlines = sampleDeadlines
    )
    private val searching = base.copy(query = "对角化", searchHits = sampleHits)

    val states: Map<CourseSpaceStatus, CourseSpaceUiState> = linkedMapOf(
        CourseSpaceStatus.LOADING to base.copy(status = CourseSpaceStatus.LOADING, materials = emptyList(), deadlines = emptyList()),
        CourseSpaceStatus.EMPTY to base.copy(status = CourseSpaceStatus.EMPTY, materials = emptyList(), deadlines = emptyList()),
        CourseSpaceStatus.CONTENT to searching.copy(status = CourseSpaceStatus.CONTENT),
        CourseSpaceStatus.PARTIAL to searching.copy(status = CourseSpaceStatus.PARTIAL),
        CourseSpaceStatus.STALE to searching.copy(status = CourseSpaceStatus.STALE),
        CourseSpaceStatus.ERROR_RETRYABLE to base.copy(status = CourseSpaceStatus.ERROR_RETRYABLE, errorMessage = "索引暂时不可用（预览）"),
        CourseSpaceStatus.ERROR_TERMINAL to base.copy(status = CourseSpaceStatus.ERROR_TERMINAL, errorMessage = "资料库快照损坏（预览）", exportEnabled = false),
        CourseSpaceStatus.NEEDS_PERMISSION to base.copy(status = CourseSpaceStatus.NEEDS_PERMISSION),
        CourseSpaceStatus.NEEDS_LOGIN to base.copy(status = CourseSpaceStatus.NEEDS_LOGIN),
        CourseSpaceStatus.OFFLINE to searching.copy(status = CourseSpaceStatus.OFFLINE),
        CourseSpaceStatus.UNSUPPORTED to base.copy(status = CourseSpaceStatus.UNSUPPORTED)
    )

    fun stateFor(status: CourseSpaceStatus): CourseSpaceUiState = states.getValue(status)
}

@Composable
internal fun CourseSpacePreviewHarness(state: CourseSpaceUiState) {
    HautCampusTheme(
        darkTheme = false,
        themeMode = "LIGHT",
        uiDesignStyleKey = "FLUTTER_M3",
        dynamicColor = false,
        themePaletteKey = "HAUT_BLUE"
    ) {
        CourseSpaceScreen(state = state, onAction = {})
    }
}

@Preview(name = "课程空间 Content", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun CourseSpaceContentPreview() {
    CourseSpacePreviewHarness(CourseSpacePreviewFixtures.stateFor(CourseSpaceStatus.CONTENT))
}

@Preview(name = "课程空间 Empty", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun CourseSpaceEmptyPreview() {
    CourseSpacePreviewHarness(CourseSpacePreviewFixtures.stateFor(CourseSpaceStatus.EMPTY))
}

@Preview(name = "课程空间 Error", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun CourseSpaceErrorPreview() {
    CourseSpacePreviewHarness(CourseSpacePreviewFixtures.stateFor(CourseSpaceStatus.ERROR_RETRYABLE))
}
