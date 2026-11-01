package cn.edu.haut.campus.core.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 毕业进度测试。
 *
 * UNVERIFIED：方案学分、课程归属与已修记录均为合成夹具；「一门课只归一个组」「未归类单列」
 * 「总缺口按组相加」都是本模块的本地推导规则，不代表任何学校的官方认定口径。
 */
class DegreeProgressCalculatorTest {

    private fun basePlan(): TrainingPlan = PlanFixtures.plan(
        groups = listOf(
            PlanFixtures.group(
                id = "g-root",
                credits = "26",
                title = "总要求",
                children = listOf(
                    PlanFixtures.group(
                        "g-math", "12",
                        listOf(PlanFixtures.ref("MATH101", "高等数学"), PlanFixtures.ref("MATH102", "线性代数")),
                        title = "数学必修"
                    ),
                    PlanFixtures.group(
                        "g-ele", "8",
                        listOf(PlanFixtures.ref(null, "数据结构"), PlanFixtures.ref(null, "操作系统")),
                        kind = RequirementKind.ELECTIVE,
                        title = "专业选修"
                    ),
                    PlanFixtures.group(
                        "g-open", "6",
                        kind = RequirementKind.GENERAL,
                        title = "通识任选"
                    )
                )
            )
        )
    )

    private fun baseCompleted(): List<CompletedCourse> = listOf(
        PlanFixtures.completed("MATH101", "高等数学", "6.0"),
        PlanFixtures.completed("MATH102", "线性代数", "6.0"),
        PlanFixtures.completed("DS101", "数据结构", "4.0")
    )

    @Test
    fun coursesListedInTwoGroupsAreCountedOnce() {
        val plan = PlanFixtures.plan(
            listOf(
                PlanFixtures.group(
                    id = "g-root", credits = "20", title = "总要求",
                    children = listOf(
                        PlanFixtures.group("g-a", "10", listOf(PlanFixtures.ref("MATH101", "高等数学")), title = "A组"),
                        PlanFixtures.group("g-b", "10", listOf(PlanFixtures.ref("MATH101", "高等数学")), title = "B组")
                    )
                )
            )
        )
        val progress = DegreeProgressCalculator.evaluate(plan, listOf(PlanFixtures.completed("MATH101", "高等数学", "6.0")))
        PlanFixtures.assertAmount("6", progress.totalEarnedCredits, "UNVERIFIED：同一课程只能计一次学分")
        PlanFixtures.assertAmount("6", progress.groupById("g-a")!!.earnedCredits)
        PlanFixtures.assertAmount("0", progress.groupById("g-b")!!.earnedCredits, "UNVERIFIED：第二个组不得重复计分")
        val assignment = progress.groupById("g-a")!!.assignedCourses.single()
        assertEquals("UNVERIFIED：被排除的重复组必须记录在案", listOf("g-b"), assignment.otherCandidateGroupIds)
        assertTrue(
            "UNVERIFIED：必须说明课程只归一个组，实际为 ${progress.notes}",
            progress.notes.any { it.contains("不重复计分") }
        )
    }

    @Test
    fun gapIsPerGroupRequiredMinusEarned() {
        val progress = DegreeProgressCalculator.evaluate(basePlan(), baseCompleted())
        PlanFixtures.assertAmount("12", progress.groupById("g-math")!!.earnedCredits)
        PlanFixtures.assertAmount("0", progress.groupById("g-math")!!.remainingCredits)
        PlanFixtures.assertAmount("4", progress.groupById("g-ele")!!.remainingCredits, "UNVERIFIED：8 - 4 = 4")
        PlanFixtures.assertAmount("6", progress.groupById("g-open")!!.remainingCredits)
        PlanFixtures.assertAmount("26", progress.totalRequiredCredits)
        PlanFixtures.assertAmount("16", progress.totalEarnedCredits)
        PlanFixtures.assertAmount("10", progress.totalRemainingCredits)
    }

    @Test
    fun unclassifiedCourseIsListedSeparatelyAndExcludedFromTotals() {
        val progress = DegreeProgressCalculator.evaluate(
            basePlan(),
            baseCompleted() + PlanFixtures.completed("PE101", "体育", "1.0")
        )
        assertEquals("UNVERIFIED：未映射课程必须单列", 1, progress.unclassified.size)
        assertEquals("UNVERIFIED：未归类课程不应被塞进任何组", "PE101", progress.unclassified.single().courseKey)
        PlanFixtures.assertAmount("16", progress.totalEarnedCredits, "UNVERIFIED：未归类学分不得计入已修合计")
        PlanFixtures.assertAmount("1", progress.unclassifiedCredits)
        assertTrue(
            "UNVERIFIED：必须说明未归类课程不计入任何组，实际为 ${progress.notes}",
            progress.notes.any { it.contains("未在方案中映射") }
        )
    }

    @Test
    fun duplicateCourseRecordsCountedOnce() {
        val progress = DegreeProgressCalculator.evaluate(
            basePlan(),
            baseCompleted() + PlanFixtures.completed("MATH101", "高等数学", "3.0")
        )
        PlanFixtures.assertAmount("12", progress.groupById("g-math")!!.earnedCredits, "UNVERIFIED：重复记录不得累加学分")
        PlanFixtures.assertAmount("16", progress.totalEarnedCredits)
        assertTrue(
            "UNVERIFIED：必须说明已按课程去重，实际为 ${progress.notes}",
            progress.notes.any { it.contains("重复") }
        )
    }

    @Test
    fun unknownPlanSourceDowngradesToEstimated() {
        val progress = DegreeProgressCalculator.evaluate(
            PlanFixtures.plan(basePlan().groups, source = PlanSource.UNKNOWN),
            baseCompleted()
        )
        assertEquals("UNVERIFIED：方案来源未知时只能给估算", Provenance.ESTIMATED, progress.provenance)
        assertTrue(
            "UNVERIFIED：必须提示方案来源未知，实际为 ${progress.notes}",
            progress.notes.any { it.contains("来源未知") }
        )
    }

    @Test
    fun unconfirmedPlanDowngradesToEstimated() {
        val progress = DegreeProgressCalculator.evaluate(
            PlanFixtures.plan(basePlan().groups, source = PlanSource.OFFICIAL_IMPORT, confirmed = false),
            baseCompleted()
        )
        assertEquals("UNVERIFIED：未确认适用于本人时只能给估算", Provenance.ESTIMATED, progress.provenance)
        assertTrue(
            "UNVERIFIED：必须提示方案未确认适用，实际为 ${progress.notes}",
            progress.notes.any { it.contains("尚未确认适用于本人") }
        )
    }

    @Test
    fun userConfirmedPlanSourceStillCountsAsConfirmed() {
        val progress = DegreeProgressCalculator.evaluate(
            PlanFixtures.plan(basePlan().groups, source = PlanSource.USER_CONFIRMED),
            baseCompleted()
        )
        assertEquals(
            "UNVERIFIED：用户已确认适用且来源明确时，进度属于本地推导而不是估算",
            Provenance.DERIVED,
            progress.provenance
        )
    }

    @Test
    fun confirmedOfficialPlanProducesDerivedProgress() {
        val progress = DegreeProgressCalculator.evaluate(basePlan(), baseCompleted())
        assertEquals("UNVERIFIED：官方方案 + 官方成绩的确定性推导应为 DERIVED", Provenance.DERIVED, progress.provenance)
        assertTrue("UNVERIFIED：必须给出计算依据", progress.basis.contains("叶子要求组"))
        assertEquals("UNVERIFIED：方案版本必须随进度返回", "2026.1", progress.planVersion)
        assertEquals("UNVERIFIED：方案 ID 必须随进度返回", "plan-fixture", progress.planId)
    }

    @Test
    fun pendingCoursesListOnlyUnfinishedRefs() {
        val progress = DegreeProgressCalculator.evaluate(basePlan(), baseCompleted())
        val pending = progress.groupById("g-ele")!!.pendingCourses
        assertEquals("UNVERIFIED：只应剩未完成的方案课程", 1, pending.size)
        assertEquals("UNVERIFIED：应剩「操作系统」", "操作系统", pending.single().name)
        assertTrue("UNVERIFIED：已完成的方案课程不应出现在待修列表", progress.groupById("g-math")!!.pendingCourses.isEmpty())
    }

    @Test
    fun openGroupWithoutCourseListIsNoted() {
        val progress = DegreeProgressCalculator.evaluate(basePlan(), baseCompleted())
        assertTrue(
            "UNVERIFIED：开放选修组没有课程清单时必须说明，实际为 ${progress.notes}",
            progress.notes.any { it.contains("未列出具体课程") }
        )
    }

    @Test
    fun totalRemainingIsSumOfGroupGapsNotGlobalDifference() {
        val plan = PlanFixtures.plan(
            listOf(
                PlanFixtures.group(
                    id = "g-root", credits = "20", title = "总要求",
                    children = listOf(
                        PlanFixtures.group("g-a", "10", listOf(PlanFixtures.ref("MATH101", "高等数学")), title = "A组"),
                        PlanFixtures.group("g-b", "10", listOf(PlanFixtures.ref("PHYS101", "大学物理")), title = "B组")
                    )
                )
            )
        )
        val progress = DegreeProgressCalculator.evaluate(
            plan,
            listOf(
                PlanFixtures.completed("MATH101", "高等数学", "12.0"),
                PlanFixtures.completed("PHYS101", "大学物理", "4.0")
            )
        )
        PlanFixtures.assertAmount("20", progress.totalRequiredCredits)
        PlanFixtures.assertAmount("16", progress.totalEarnedCredits)
        PlanFixtures.assertAmount("2", progress.groupById("g-a")!!.surplusCredits, "UNVERIFIED：超额应单独记录")
        PlanFixtures.assertAmount("6", progress.totalRemainingCredits, "UNVERIFIED：总缺口是各组缺口之和，不是 20-16=4")
        PlanFixtures.assertAmount("2", progress.totalSurplusCredits)
    }

    @Test
    fun nameMatchUsedWhenPlanHasNoCourseCode() {
        val progress = DegreeProgressCalculator.evaluate(basePlan(), baseCompleted())
        val assignment = progress.groupById("g-ele")!!.assignedCourses.single()
        assertEquals("UNVERIFIED：方案没给课程代码时按名称匹配", CourseMatchKind.NAME, assignment.matchedBy)
        assertEquals("UNVERIFIED：按名称匹配到的课程", "DS101", assignment.course.courseKey)
    }

    @Test
    fun codeMatchBeatsNameMatchEvenIfNameGroupComesFirst() {
        val plan = PlanFixtures.plan(
            listOf(
                PlanFixtures.group(
                    id = "g-root", credits = "20", title = "总要求",
                    children = listOf(
                        PlanFixtures.group("g-y", "10", listOf(PlanFixtures.ref(null, "共享名称")), title = "Y组"),
                        PlanFixtures.group("g-x", "10", listOf(PlanFixtures.ref("C1", "共享名称")), title = "X组")
                    )
                )
            )
        )
        val progress = DegreeProgressCalculator.evaluate(plan, listOf(PlanFixtures.completed("C1", "共享名称", "5.0")))
        val assignment = progress.groups.single { it.assignedCourses.isNotEmpty() }.assignedCourses.single()
        assertEquals("UNVERIFIED：课程代码匹配优先于名称匹配", "g-x", assignment.groupId)
        assertEquals("UNVERIFIED：匹配方式应记录为代码匹配", CourseMatchKind.CODE, assignment.matchedBy)
        assertTrue("UNVERIFIED：代码命中后不应再产生名称候选", assignment.otherCandidateGroupIds.isEmpty())
    }

    @Test
    fun groupLookupReturnsNullForUnknownGroup() {
        val progress = DegreeProgressCalculator.evaluate(basePlan(), baseCompleted())
        assertNull("UNVERIFIED：未知组应返回 null", progress.groupById("g-missing"))
    }

    @Test
    fun noCompletedCoursesLeavesFullGap() {
        val progress = DegreeProgressCalculator.evaluate(basePlan(), emptyList())
        PlanFixtures.assertAmount("0", progress.totalEarnedCredits)
        PlanFixtures.assertAmount("26", progress.totalRemainingCredits)
        assertTrue("UNVERIFIED：没有任何已修记录时未归类列表为空", progress.unclassified.isEmpty())
        assertEquals("UNVERIFIED：三个叶子组都必须出现", 3, progress.groups.size)
    }
}
