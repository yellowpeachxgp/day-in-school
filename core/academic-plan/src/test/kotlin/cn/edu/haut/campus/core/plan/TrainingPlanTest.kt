package cn.edu.haut.campus.core.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 培养方案模型测试。
 *
 * UNVERIFIED：方案结构、学分与适用年级均为合成夹具，不代表任何真实学校的培养方案。
 */
class TrainingPlanTest {

    private fun tree(): List<RequirementGroup> = listOf(
        PlanFixtures.group(
            id = "g-root",
            credits = "60",
            title = "总要求",
            children = listOf(
                PlanFixtures.group("g-req", "30", listOf(PlanFixtures.ref("MATH101", "高等数学")), title = "必修模块"),
                PlanFixtures.group(
                    "g-ele",
                    "30",
                    listOf(PlanFixtures.ref(null, "数据结构")),
                    kind = RequirementKind.ELECTIVE,
                    title = "选修模块"
                )
            )
        )
    )

    @Test
    fun totalRequiredCreditsSumsLeafGroupsOnly() {
        val plan = PlanFixtures.plan(tree())
        PlanFixtures.assertAmount("60", plan.totalRequiredCredits, "UNVERIFIED：父组 60 分 + 叶子 60 分不得累加成 120")
        assertEquals("UNVERIFIED：两个叶子组", 2, plan.leafGroups().size)
    }

    @Test
    fun allGroupsTraversalIsPreOrder() {
        val plan = PlanFixtures.plan(tree())
        assertEquals(
            "UNVERIFIED：先序遍历顺序必须稳定",
            listOf("g-root", "g-req", "g-ele"),
            plan.allGroups().map { it.id }
        )
    }

    @Test
    fun groupByIdFindsNestedGroup() {
        val plan = PlanFixtures.plan(tree())
        assertEquals("UNVERIFIED：应能按 ID 找到嵌套组", "选修模块", plan.groupById("g-ele")!!.title)
        assertNull("UNVERIFIED：未知组 ID 应返回 null", plan.groupById("g-missing"))
    }

    @Test
    fun duplicateNestedGroupIdIsRejected() {
        var thrown: IllegalArgumentException? = null
        try {
            PlanFixtures.plan(
                listOf(
                    PlanFixtures.group(
                        id = "g-root",
                        credits = "10",
                        children = listOf(
                            PlanFixtures.group("g-dup", "5"),
                            PlanFixtures.group("g-dup", "5")
                        )
                    )
                )
            )
        } catch (error: IllegalArgumentException) {
            thrown = error
        }
        assertTrue("UNVERIFIED：同一层重复组 ID 必须被拒绝", thrown != null)
    }

    @Test
    fun planRequiresApplicableGradeYears() {
        var emptyYears: IllegalArgumentException? = null
        try {
            PlanFixtures.plan(tree(), gradeYears = emptySet())
        } catch (error: IllegalArgumentException) {
            emptyYears = error
        }
        assertTrue("UNVERIFIED：未声明适用年级的方案不可用", emptyYears != null)

        var badYear: IllegalArgumentException? = null
        try {
            PlanFixtures.plan(tree(), gradeYears = setOf(0, 1))
        } catch (error: IllegalArgumentException) {
            badYear = error
        }
        assertTrue("UNVERIFIED：适用年级必须在 1..8 之间", badYear != null)
    }

    @Test
    fun matchesChecksGradeYearAndMajor() {
        val plan = PlanFixtures.plan(tree(), gradeYears = setOf(1, 2), majors = setOf("软件工程"))
        assertTrue("UNVERIFIED：年级与专业都匹配时应适用", plan.matches(2, "软件工程"))
        assertTrue("UNVERIFIED：专业名应忽略首尾空白", plan.matches(1, " 软件工程 "))
        assertTrue("UNVERIFIED：未提供专业时不能认定适用", !plan.matches(2, null))
        assertTrue("UNVERIFIED：年级不匹配时不能认定适用", !plan.matches(5, "软件工程"))
        val openPlan = PlanFixtures.plan(tree())
        assertTrue("UNVERIFIED：方案未限定专业时应按年级适用", openPlan.matches(3, null))
    }

    @Test
    fun groupRejectsNegativeCredits() {
        var thrown: IllegalArgumentException? = null
        try {
            PlanFixtures.group("g-bad", "-1")
        } catch (error: IllegalArgumentException) {
            thrown = error
        }
        assertTrue("UNVERIFIED：负数学分必须被拒绝", thrown != null)
    }

    @Test
    fun planCourseRefRequiresName() {
        var thrown: IllegalArgumentException? = null
        try {
            PlanCourseRef("MATH101", "  ")
        } catch (error: IllegalArgumentException) {
            thrown = error
        }
        assertTrue("UNVERIFIED：课程引用必须有名称", thrown != null)
    }
}
