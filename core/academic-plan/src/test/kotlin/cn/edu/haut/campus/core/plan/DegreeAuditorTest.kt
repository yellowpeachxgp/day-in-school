package cn.edu.haut.campus.core.plan

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.AttemptType
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DegreeAuditor 验收测试：重修、改名、替代课程、跨专业选课、人工认定与依据追踪。
 * 全部为合成数据（UNVERIFIED），不代表任何真实学校。
 */
class DegreeAuditorTest {

    private val scope = SchoolAccountScope(
        schoolId = SchoolId("demo-school"),
        accountId = AccountId("demo-account"),
        profileId = ProfileId("demo-profile"),
        campusId = CampusId("main")
    )

    private val now = LocalDate.of(2026, 3, 1)

    private fun planFixture(): TrainingPlan = TrainingPlan(
        id = "demo-plan",
        title = "示范培养方案",
        version = "2023.1",
        applicableGradeYears = setOf(1, 2, 3, 4),
        majors = setOf("计算机科学与技术"),
        source = PlanSource.OFFICIAL_IMPORT,
        confirmedForStudent = true,
        groups = listOf(
            RequirementGroup(
                id = "root",
                title = "全部要求",
                kind = RequirementKind.REQUIRED,
                requiredCredits = BigDecimal("20"),
                children = listOf(
                    RequirementGroup(
                        id = "basic",
                        title = "学科基础",
                        kind = RequirementKind.REQUIRED,
                        requiredCredits = BigDecimal("9"),
                        courses = listOf(
                            PlanCourseRef("MATH1", "高等数学A", CourseOffering.AUTUMN_ONLY),
                            PlanCourseRef("CS101", "程序设计基础", CourseOffering.SPRING_ONLY, listOf("高等数学A")),
                            PlanCourseRef("ETHIC1", "思想道德与法治")
                        )
                    ),
                    RequirementGroup(
                        id = "elective",
                        title = "专业选修",
                        kind = RequirementKind.ELECTIVE,
                        requiredCredits = BigDecimal("4"),
                        courses = listOf(PlanCourseRef("CS201", "数据结构"))
                    ),
                    RequirementGroup(
                        id = "open",
                        title = "开放选修",
                        kind = RequirementKind.GENERAL,
                        requiredCredits = BigDecimal("2"),
                        courses = emptyList()
                    )
                )
            )
        ),
        substitutions = listOf(
            SubstitutionRule(
                course = PlanCourseRef("CS202", "数据结构与算法"),
                replaces = PlanCourseRef("CS201", "数据结构"),
                note = "新版课程替代旧版"
            )
        ),
        renames = listOf(
            CourseRename(
                was = PlanCourseRef(null, "思想道德修养"),
                now = PlanCourseRef("ETHIC1", "思想道德与法治")
            )
        )
    )

    private fun makeOverride(
        id: String,
        attemptId: String,
        action: OverrideAction,
        mapTo: String?,
        reason: String,
        at: Instant = Instant.parse("2026-02-01T00:00:00Z")
    ) = AuditOverride(
        id = id,
        attemptRef = EntityRef(EntityType.GRADE_ATTEMPT, attemptId, scope),
        action = action,
        mapToGroupId = mapTo,
        reason = reason,
        actor = Actor.USER,
        at = at
    )

    @Test
    fun retakeAttemptsAreCreditedOnlyOnce() {
        val plan = planFixture()
        val attempts = listOf(
            PlanFixtures.attempt("MATH1", name = "高等数学A", credits = "5", passed = false, rawGrade = "45",
                academicYear = "2023-2024", termNumber = 1, id = "a1"),
            PlanFixtures.attempt("MATH1", name = "高等数学A", credits = "5", passed = true, rawGrade = "72",
                academicYear = "2024-2025", termNumber = 1, attemptType = AttemptType.RETAKE, id = "a2")
        )
        val report = DegreeAuditor.audit(plan, attempts, emptyList(), now)
        val basic = report.groupById("basic")!!
        PlanFixtures.assertAmount("5", basic.earnedCredits, "重修后学分只入账一次")
        assertEquals(1, basic.assignedCourses.size)
        val dedupeSteps = report.trace.filter { it.rule == "retake-dedupe" }
        assertEquals(1, dedupeSteps.size)
        assertTrue(dedupeSteps.first().basis.contains("重修") || dedupeSteps.first().decision.contains("RETAKE")
            || dedupeSteps.first().decision.contains("重修"))
    }

    @Test
    fun renamedCourseMatchesByPlanDeclaration() {
        val plan = planFixture()
        val attempts = listOf(
            PlanFixtures.attempt("ETHIC-OLD", name = "思想道德修养", credits = "3", id = "e1")
        )
        val report = DegreeAuditor.audit(plan, attempts, emptyList(), now)
        val basic = report.groupById("basic")!!
        PlanFixtures.assertAmount("3", basic.earnedCredits, "改名课程应归入方案组")
        val renameStep = report.trace.firstOrNull { it.rule == "rename" }
        assertNotNull(renameStep)
        assertTrue(renameStep!!.basis.contains("思想道德"))
        assertTrue(report.unclassified.isEmpty())
    }

    @Test
    fun substituteCourseFulfillsReplacedRequirement() {
        val plan = planFixture()
        val attempts = listOf(
            PlanFixtures.attempt("CS202", name = "数据结构与算法", credits = "4", id = "s1",
                numericScore = "85", gradePoint = "3.5")
        )
        val report = DegreeAuditor.audit(plan, attempts, emptyList(), now)
        val elective = report.groupById("elective")!!
        PlanFixtures.assertAmount("4", elective.earnedCredits, "替代课程应抵数据结构要求")
        val step = report.trace.firstOrNull { it.rule == "substitute" }
        assertNotNull(step)
        assertTrue(step!!.decision.contains("数据结构"))
    }

    @Test
    fun crossMajorCourseLandsInUnclassifiedWithoutCreditInflation() {
        val plan = planFixture()
        val attempts = listOf(
            PlanFixtures.attempt("ART101", name = "艺术鉴赏", credits = "2", id = "x1"),
            PlanFixtures.attempt("MATH1", name = "高等数学A", credits = "5", id = "x2")
        )
        val report = DegreeAuditor.audit(plan, attempts, emptyList(), now)
        assertEquals(1, report.unclassified.size)
        assertEquals("艺术鉴赏", report.unclassified.first().courseName)
        val totalInGroups = report.groups.fold(BigDecimal.ZERO) { acc, group -> acc.add(group.earnedCredits) }
        PlanFixtures.assertAmount("5", totalInGroups, "跨专业课程不得计入任何组")
        PlanFixtures.assertAmount("5", report.totalEarnedCredits)
        assertTrue(report.notes.any { it.contains("未归类") })
        assertTrue(report.trace.any { it.rule == "unclassified" && it.courseName == "艺术鉴赏" })
    }

    @Test
    fun overrideAssignMapsCourseToChosenGroup() {
        val plan = planFixture()
        val attempts = listOf(
            PlanFixtures.attempt("ART101", name = "艺术鉴赏", credits = "2", id = "ov1")
        )
        val report = DegreeAuditor.audit(
            plan, attempts,
            listOf(makeOverride("o1", "ov1", OverrideAction.ASSIGN, "open", "教务处认定可计入开放选修")),
            now
        )
        val open = report.groupById("open")!!
        PlanFixtures.assertAmount("2", open.earnedCredits)
        assertTrue(report.unclassified.isEmpty())
        val step = report.trace.first { it.rule == "override-assign" }
        assertTrue(step.basis.contains("教务处认定"))
        assertTrue(step.basis.contains("USER"))
    }

    @Test
    fun overrideExcludeRemovesCourseEverywhere() {
        val plan = planFixture()
        val attempts = listOf(
            PlanFixtures.attempt("MATH1", name = "高等数学A", credits = "5", id = "ov2")
        )
        val report = DegreeAuditor.audit(
            plan, attempts,
            listOf(makeOverride("o2", "ov2", OverrideAction.EXCLUDE, null, "该成绩系误判同步，不计入")),
            now
        )
        val basic = report.groupById("basic")!!
        PlanFixtures.assertAmount("0", basic.earnedCredits)
        assertTrue(report.unclassified.isEmpty())
        assertTrue(report.trace.any { it.rule == "override-exclude" })
    }

    @Test
    fun overrideRetreatRestoresAutomaticMatching() {
        val plan = planFixture()
        val attempts = listOf(
            PlanFixtures.attempt("MATH1", name = "高等数学A", credits = "5", id = "ov3")
        )
        val overrides = listOf(
            makeOverride("o3a", "ov3", OverrideAction.EXCLUDE, null, "先排除", Instant.parse("2026-01-01T00:00:00Z")),
            makeOverride("o3b", "ov3", OverrideAction.RETRACT, null, "撤销排除恢复自动归属", Instant.parse("2026-02-01T00:00:00Z"))
        )
        val report = DegreeAuditor.audit(plan, attempts, overrides, now)
        val basic = report.groupById("basic")!!
        PlanFixtures.assertAmount("5", basic.earnedCredits, "撤销认定后应回到自动匹配")
        assertTrue(report.trace.any { it.rule == "override-retract" })
    }

    @Test
    fun sameCourseNeverCountsTwiceAcrossGroups() {
        // 同一门课同时列在两个组：只能进先序第一组
        val plan = TrainingPlan(
            id = "dup-plan",
            title = "重复候选方案",
            version = "1",
            applicableGradeYears = setOf(1, 2, 3, 4),
            majors = emptySet(),
            source = PlanSource.OFFICIAL_IMPORT,
            confirmedForStudent = true,
            groups = listOf(
                RequirementGroup("g1", "组一", RequirementKind.REQUIRED, BigDecimal("3"),
                    courses = listOf(PlanCourseRef("MATH1", "高等数学A"))),
                RequirementGroup("g2", "组二", RequirementKind.ELECTIVE, BigDecimal("3"),
                    courses = listOf(PlanCourseRef("MATH1", "高等数学A")))
            )
        )
        val attempts = listOf(PlanFixtures.attempt("MATH1", name = "高等数学A", credits = "5", id = "d1"))
        val report = DegreeAuditor.audit(plan, attempts, emptyList(), now)
        val g1 = report.groupById("g1")!!
        val g2 = report.groupById("g2")!!
        PlanFixtures.assertAmount("5", g1.earnedCredits)
        PlanFixtures.assertAmount("0", g2.earnedCredits)
        PlanFixtures.assertAmount("5", report.totalEarnedCredits, "总学分不得重复计算")
    }

    @Test
    fun gapsAndRisksExposeSeasonalAndPrerequisite() {
        val plan = planFixture()
        // 只修高等数学：程序设计基础（春季+先修已修）与思想道德与法治未修
        val attempts = listOf(
            PlanFixtures.attempt("MATH1", name = "高等数学A", credits = "5", id = "g1")
        )
        val report = DegreeAuditor.audit(plan, attempts, emptyList(), now)
        assertTrue(report.gaps.any { it.groupId == "basic" })
        val seasonal = report.risks.filter { it.kind == PlanRiskKind.SEASONAL_OFFERING }
        assertTrue(seasonal.any { it.title.contains("程序设计基础") })
        // 先修已修完，不应出现 PREREQUISITE_UNMET for 程序设计基础
        assertFalse(report.risks.any { it.kind == PlanRiskKind.PREREQUISITE_UNMET && it.title.contains("程序设计基础") })
    }

    @Test
    fun prerequisiteRiskRaisedWhenPrereqMissing() {
        val plan = planFixture()
        // 什么都没修：程序设计基础的先修「高等数学A」未完成
        val report = DegreeAuditor.audit(plan, emptyList(), emptyList(), now)
        assertTrue(report.risks.any {
            it.kind == PlanRiskKind.PREREQUISITE_UNMET && it.title.contains("程序设计基础")
        })
    }

    @Test
    fun trustIsEstimatedWhenPlanNotConfirmed() {
        val plan = planFixture().copy(confirmedForStudent = false)
        val report = DegreeAuditor.audit(plan, emptyList(), emptyList(), now)
        assertEquals(Provenance.ESTIMATED, report.trust)
        val confirmed = DegreeAuditor.audit(planFixture(), emptyList(), emptyList(), now)
        assertEquals(Provenance.DERIVED, confirmed.trust)
        assertEquals("本报告是个人规划参考，不代表学校毕业审核结论", confirmed.personalPlanningDisclaimer)
    }

    @Test
    fun failedAttemptDoesNotCount() {
        val plan = planFixture()
        val attempts = listOf(
            PlanFixtures.attempt("MATH1", name = "高等数学A", credits = "5", passed = false, rawGrade = "40", id = "f1")
        )
        val report = DegreeAuditor.audit(plan, attempts, emptyList(), now)
        PlanFixtures.assertAmount("0", report.totalEarnedCredits)
        assertTrue(report.unclassified.isEmpty())
        assertTrue(report.trace.any { it.rule == "not-passed" })
    }

    @Test
    fun traceCoversEveryCreditedCourse() {
        val plan = planFixture()
        val attempts = listOf(
            PlanFixtures.attempt("MATH1", name = "高等数学A", credits = "5", id = "t1"),
            PlanFixtures.attempt("ART9", name = "方案外课程", credits = "1", id = "t2")
        )
        val report = DegreeAuditor.audit(plan, attempts, emptyList(), now)
        assertTrue(report.trace.any { it.courseName == "高等数学A" })
        assertTrue(report.trace.any { it.courseName == "方案外课程" })
        // 轨迹序列号连续
        report.trace.forEachIndexed { index, step -> assertEquals(index + 1, step.sequence) }
        assertTrue(report.basis.contains("示范培养方案"))
    }
}
