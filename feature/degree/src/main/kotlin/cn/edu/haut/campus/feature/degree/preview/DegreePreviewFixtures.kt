package cn.edu.haut.campus.feature.degree.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import cn.edu.haut.campus.core.degree.TrainingPlanImportRecord
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.plan.AuditOverride
import cn.edu.haut.campus.core.plan.AuditReport
import cn.edu.haut.campus.core.plan.AuditStep
import cn.edu.haut.campus.core.plan.CompletedCourse
import cn.edu.haut.campus.core.plan.CourseAssignment
import cn.edu.haut.campus.core.plan.CourseMatchKind
import cn.edu.haut.campus.core.plan.GroupProgress
import cn.edu.haut.campus.core.plan.PlanRisk
import cn.edu.haut.campus.core.plan.OverrideAction
import cn.edu.haut.campus.core.plan.OverrideApplication
import cn.edu.haut.campus.core.plan.PlanRiskKind
import cn.edu.haut.campus.core.plan.PlanSource
import cn.edu.haut.campus.core.plan.Provenance
import cn.edu.haut.campus.core.plan.RequirementGap
import cn.edu.haut.campus.core.plan.RequirementKind
import cn.edu.haut.campus.feature.degree.state.DegreeStatus
import cn.edu.haut.campus.feature.degree.state.DegreeUiState
import cn.edu.haut.campus.feature.degree.state.OverrideDraft
import cn.edu.haut.campus.feature.degree.ui.DegreeScreen
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 毕业进度看板状态矩阵夹具：覆盖加载、空、就绪（推导/估算/风险/未归类）、
 * 依据展开、导入对话框、人工认定对话框与错误态。全部为合成数据（UNVERIFIED）。
 */
object DegreePreviewFixtures {

    private fun group(
        id: String,
        title: String,
        kind: RequirementKind,
        required: String,
        earned: String,
        assigned: List<CourseAssignment> = emptyList()
    ) = GroupProgress(
        groupId = id,
        title = title,
        kind = kind,
        requiredCredits = BigDecimal(required),
        earnedCredits = BigDecimal(earned),
        remainingCredits = (BigDecimal(required) - BigDecimal(earned)).max(BigDecimal.ZERO),
        surplusCredits = (BigDecimal(earned) - BigDecimal(required)).max(BigDecimal.ZERO),
        assignedCourses = assigned,
        pendingCourses = emptyList()
    )

    private fun assignment(key: String, name: String, credits: String, groupId: String) = CourseAssignment(
        course = CompletedCourse(key, name, BigDecimal(credits), representativeAttemptId = "at-" + key),
        groupId = groupId,
        matchedBy = CourseMatchKind.CODE,
        otherCandidateGroupIds = emptyList()
    )

    fun reportDerived(): AuditReport = AuditReport(
        planId = "demo-cs-2023",
        planVersion = "2023.1",
        generatedAt = LocalDate.of(2026, 3, 1),
        groups = listOf(
            group(
                "basic", "学科基础", RequirementKind.REQUIRED, "9", "5",
                assigned = listOf(assignment("MATH1", "高等数学A", "5", "basic"))
            ),
            group("open", "开放选修", RequirementKind.GENERAL, "2", "2",
                assigned = listOf(assignment("ART101", "艺术鉴赏", "2", "open"))
            )
        ),
        gaps = listOf(
            RequirementGap("basic", "学科基础", RequirementKind.REQUIRED, BigDecimal("4"), emptyList(), null)
        ),
        risks = listOf(
            PlanRisk(
                kind = PlanRiskKind.SEASONAL_OFFERING,
                title = "「程序设计基础」仅春季开设",
                detail = "该课是「学科基础」的未修要求且仅春季开设，错过开课学期需等待下一学年",
                courseNames = listOf("程序设计基础")
            )
        ),
        trace = listOf(
            AuditStep(1, "match-code", "MATH1", "高等数学A", BigDecimal("5"), "按课程代码匹配归入「学科基础」", "方案叶子组「学科基础」列有该课程"),
            AuditStep(2, "override-assign", "ART101", "艺术鉴赏", BigDecimal("2"), "人工认定归入「开放选修」，不再参与自动匹配", "认定依据：教务处认定可计入开放选修（USER @ 2026-02-01T00:00:00Z）"),
            AuditStep(3, "group-total", null, null, BigDecimal("7"), "各叶子组独立统计，某组超额不抵扣其他组缺口，总计入学分不重复计算", "规则见任务卡 C11")
        ),
        trust = Provenance.DERIVED,
        unclassified = emptyList(),
        totalRequiredCredits = BigDecimal("11"),
        totalEarnedCredits = BigDecimal("7"),
        totalRemainingCredits = BigDecimal("4"),
        notes = listOf("本报告含 1 项生效人工认定，每项依据可在审计轨迹中展开"),
        basis = "按培养方案「计算机示范方案」2023.1 的 2 个叶子要求组审计 3 条成绩记录与 1 项人工认定",
        representativeAttemptIds = mapOf("MATH1" to "at-math", "ART101" to "at-art"),
        overrideApplications = listOf(
            OverrideApplication(
                override = AuditOverride(
                    id = "ovr-demo",
                    attemptRef = cn.edu.haut.campus.core.model.EntityRef(
                        cn.edu.haut.campus.core.model.EntityType.GRADE_ATTEMPT,
                        "at-art",
                        cn.edu.haut.campus.core.model.SchoolAccountScope(
                            cn.edu.haut.campus.core.model.SchoolId("demo"),
                            cn.edu.haut.campus.core.model.AccountId("acct"),
                            cn.edu.haut.campus.core.model.ProfileId("prof")
                        )
                    ),
                    action = OverrideAction.ASSIGN,
                    mapToGroupId = "open",
                    reason = "教务处认定可计入开放选修",
                    actor = Actor.USER,
                    at = Instant.parse("2026-02-01T00:00:00Z"),
                    planImportId = "tpl-demo",
                    planId = "demo-cs-2023",
                    planVersion = "2023.1"
                ),
                courseKey = "ART101",
                courseName = "艺术鉴赏",
                representativeAttemptId = "at-art",
                targetGroupId = "open"
            )
        )
    )

    fun importRecord(active: Boolean) = TrainingPlanImportRecord(
        importId = "tpl-demo",
        planId = "demo-cs-2023",
        title = "计算机示范方案",
        planVersion = "2023.1",
        format = "univ-training-plan/v1",
        contentDigest = "0".repeat(64),
        warnings = emptyList(),
        skippedRowCount = 0,
        source = PlanSource.USER_CONFIRMED,
        confirmedForStudent = true,
        importedAt = Instant.parse("2026-02-20T00:00:00Z"),
        importedBy = Actor.USER,
        active = active
    )

    fun loading() = DegreeUiState(status = DegreeStatus.LOADING)

    fun empty() = DegreeUiState(status = DegreeStatus.EMPTY)

    fun readyDerived() = DegreeUiState(
        status = DegreeStatus.READY,
        imports = listOf(importRecord(active = true)),
        activeImportId = "tpl-demo",
        report = reportDerived(),
        gradeAttemptCount = 3,
        expandedGroupIds = setOf("basic")
    )

    fun readyEstimatedUnconfirmed() = DegreeUiState(
        status = DegreeStatus.READY,
        imports = listOf(importRecord(active = true).copy(confirmedForStudent = false, source = PlanSource.UNKNOWN)),
        activeImportId = "tpl-demo",
        report = reportDerived().copy(
            trust = Provenance.ESTIMATED,
            notes = listOf("培养方案尚未确认适用于本人（年级/专业），结论为估算")
        ),
        gradeAttemptCount = 3
    )

    fun readyWithUnclassified() = DegreeUiState(
        status = DegreeStatus.READY,
        imports = listOf(importRecord(active = true)),
        activeImportId = "tpl-demo",
        report = reportDerived().copy(
            unclassified = listOf(CompletedCourse("MUSIC1", "音乐欣赏", BigDecimal("1"), representativeAttemptId = "at-music")),
            notes = listOf("有 1 门已修课程（合计 1 学分）未归类，可能是跨专业选课或方案外课程，可用人工认定归组"),
            representativeAttemptIds = mapOf("MUSIC1" to "at-music")
        ),
        gradeAttemptCount = 4
    )

    fun importDialogOpen() = readyDerived().copy(
        showImportDialog = true,
        importText = "{ incomplete",
        importError = "univ-training-plan/v1 顶层必须是 JSON object"
    )

    fun overrideDialogOpen() = readyWithUnclassified().copy(
        overrideDraft = OverrideDraft(
            attemptId = "at-music",
            courseName = "音乐欣赏",
            importId = "tpl-demo",
            targetGroupId = "open",
            exclude = false,
            reason = ""
        )
    )

    fun error() = DegreeUiState(status = DegreeStatus.ERROR, errorMessage = "数据库读取失败（示例）")
}

@Preview(showBackground = true, name = "loading")
@Composable
private fun DegreePreviewLoading() {
    DegreeScreen(state = DegreePreviewFixtures.loading(), onAction = {}, onBack = {})
}

@Preview(showBackground = true, name = "empty")
@Composable
private fun DegreePreviewEmpty() {
    DegreeScreen(state = DegreePreviewFixtures.empty(), onAction = {}, onBack = {})
}

@Preview(showBackground = true, name = "ready-derived-trace-expanded")
@Composable
private fun DegreePreviewReadyDerived() {
    DegreeScreen(state = DegreePreviewFixtures.readyDerived(), onAction = {}, onBack = {})
}

@Preview(showBackground = true, name = "ready-estimated-unconfirmed")
@Composable
private fun DegreePreviewEstimated() {
    DegreeScreen(state = DegreePreviewFixtures.readyEstimatedUnconfirmed(), onAction = {}, onBack = {})
}

@Preview(showBackground = true, name = "ready-unclassified")
@Composable
private fun DegreePreviewUnclassified() {
    DegreeScreen(state = DegreePreviewFixtures.readyWithUnclassified(), onAction = {}, onBack = {})
}

@Preview(showBackground = true, name = "import-dialog")
@Composable
private fun DegreePreviewImportDialog() {
    DegreeScreen(state = DegreePreviewFixtures.importDialogOpen(), onAction = {}, onBack = {})
}

@Preview(showBackground = true, name = "override-dialog")
@Composable
private fun DegreePreviewOverrideDialog() {
    DegreeScreen(state = DegreePreviewFixtures.overrideDialogOpen(), onAction = {}, onBack = {})
}

@Preview(showBackground = true, name = "error")
@Composable
private fun DegreePreviewError() {
    DegreeScreen(state = DegreePreviewFixtures.error(), onAction = {}, onBack = {})
}
