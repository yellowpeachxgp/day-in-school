package cn.edu.haut.campus.feature.degree.ui

import cn.edu.haut.campus.core.plan.AuditStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 毕业进度审计轨迹用户可见文案回归。 */
class DegreeScreenTextTest {

    @Test
    fun groupTotalTitleDoesNotRenderNullCourseName() {
        val step = AuditStep(
            sequence = 1,
            rule = "group-total",
            courseKey = null,
            courseName = null,
            credits = null,
            decision = "层次要求树汇总完成",
            basis = "子组成绩计入父组汇总约束"
        )
        val title = auditStepTitle(step)
        assertEquals("层次要求树汇总完成", title)
        assertFalse(title.startsWith("null"))
    }

    @Test
    fun courseStepTitleKeepsCourseNamePrefix() {
        val step = AuditStep(
            sequence = 2,
            rule = "match-code",
            courseKey = "MATH1",
            courseName = "高等数学A",
            credits = null,
            decision = "按课程代码匹配归入「学科基础」",
            basis = "方案要求组列有该课程"
        )
        assertEquals("高等数学A · 按课程代码匹配归入「学科基础」", auditStepTitle(step))
    }
}
