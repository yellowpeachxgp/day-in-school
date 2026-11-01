package cn.edu.haut.campus.core.selection

import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * UNVERIFIED：课程、教学班与周次全部是合成数据，按公开协议格式推导，
 * 只验证半开区间与周次交集的判定逻辑，不代表任何真实学校的课表。
 */
class SelectionConflictAnalysisUnverifiedTest {

    private fun candidate(vararg slots: ClassSlot): SelectionCandidate = SelectionCandidate(
        offeringId = "off-1",
        courseName = "高等数学（合成）",
        teachingClassId = "class-1",
        termId = SelectionFixtures.TERM,
        slots = slots.toList()
    )

    private fun timetable(
        name: String,
        slot: ClassSlot,
        termId: String = SelectionFixtures.TERM
    ): OccupiedSlot = OccupiedSlot(OccupiedSource.TIMETABLE, termId, name, slot)

    private fun draft(
        name: String,
        slot: ClassSlot,
        classId: String = "draft-class-1"
    ): OccupiedSlot = OccupiedSlot(OccupiedSource.DRAFT, SelectionFixtures.TERM, name, slot, classId)

    @Test
    fun `相邻节次不算冲突`() {
        val report = SelectionConflictAnalyzer.analyze(
            candidate(ClassSlot(1, 1, 2, TeachingWeeks.range(1, 16))),
            listOf(timetable("线性代数（合成）", ClassSlot(1, 3, 4, TeachingWeeks.range(1, 16))))
        )
        assertFalse("半开区间相邻节次不应误报（UNVERIFIED 合成课表）", report.hasConflict)
        assertTrue(report.details.isEmpty())
        assertEquals("与已有课表、选课草稿未发现时间冲突", report.summary)
    }

    @Test
    fun `共享一节的冲突返回精确重叠区段`() {
        val report = SelectionConflictAnalyzer.analyze(
            candidate(ClassSlot(1, 1, 2, TeachingWeeks.range(1, 16))),
            listOf(timetable("线性代数（合成）", ClassSlot(1, 2, 3, TeachingWeeks.range(1, 16))))
        )
        assertTrue(report.hasConflict)
        val detail = report.details.single()
        assertEquals(OccupiedSource.TIMETABLE, detail.source)
        assertEquals("线性代数（合成）", detail.conflictingCourseName)
        assertEquals(1, detail.weekday)
        assertEquals("重叠区间必须收敛到第 2 节", 2, detail.sectionStart)
        assertEquals(2, detail.sectionEnd)
        assertEquals((1..16).toSet(), detail.sharedWeeks)
        assertFalse(detail.weeksUncertain)
        assertTrue("提示语必须包含课程与节次：" + detail.message, detail.message.contains("线性代数（合成）"))
        assertTrue(detail.message.contains("第 2 节"))
        assertTrue(detail.message.contains("共同周次 1-16周"))
    }

    @Test
    fun `单双周交错不算冲突`() {
        val report = SelectionConflictAnalyzer.analyze(
            candidate(ClassSlot(1, 1, 2, TeachingWeeks.odd(1, 15))),
            listOf(timetable("线性代数（合成）", ClassSlot(1, 1, 2, TeachingWeeks.even(2, 16))))
        )
        assertFalse("单周课与双周课不共用周次（UNVERIFIED 合成周次）", report.hasConflict)
    }

    @Test
    fun `跨周次区间不算冲突`() {
        val report = SelectionConflictAnalyzer.analyze(
            candidate(ClassSlot(2, 5, 6, TeachingWeeks.range(1, 8))),
            listOf(timetable("线性代数（合成）", ClassSlot(2, 5, 6, TeachingWeeks.range(9, 16))))
        )
        assertFalse("前八周与后八周不重叠", report.hasConflict)
    }

    @Test
    fun `周次未知时给出带不确定性标记的冲突明细`() {
        val report = SelectionConflictAnalyzer.analyze(
            candidate(ClassSlot(3, 3, 4, TeachingWeeks.UNKNOWN)),
            listOf(timetable("大学物理（合成）", ClassSlot(3, 3, 4, TeachingWeeks.range(1, 16))))
        )
        val detail = report.details.single()
        assertTrue("未知周次必须保守报警", detail.weeksUncertain)
        assertTrue("未知时不得伪造共同周次", detail.sharedWeeks.isEmpty())
        assertTrue("提示语必须说明不确定性：" + detail.message, detail.message.contains("周次未知"))
    }

    @Test
    fun `同时报告已有课表与草稿内的冲突`() {
        val report = SelectionConflictAnalyzer.analyze(
            candidate(ClassSlot(1, 1, 2, TeachingWeeks.range(1, 16))),
            listOf(
                timetable("线性代数（合成）", ClassSlot(1, 1, 2, TeachingWeeks.range(1, 16))),
                draft("大学英语（合成）", ClassSlot(1, 2, 3, TeachingWeeks.range(1, 16)))
            )
        )
        assertEquals("两条来源都要返回明细", 2, report.details.size)
        assertEquals(1, report.timetableConflicts.size)
        assertEquals(1, report.draftConflicts.size)
        assertEquals("大学英语（合成）", report.draftConflicts.single().conflictingCourseName)
        assertEquals("draft-class-1", report.draftConflicts.single().conflictingTeachingClassId)
        assertTrue(report.summary.contains("已有课表 1 门"))
        assertTrue(report.summary.contains("选课草稿 1 门"))
    }

    @Test
    fun `不同学期与不同星期的时段不参与冲突`() {
        val report = SelectionConflictAnalyzer.analyze(
            candidate(ClassSlot(1, 1, 2, TeachingWeeks.range(1, 16))),
            listOf(
                timetable("另一学期课程（合成）", ClassSlot(1, 1, 2, TeachingWeeks.range(1, 16)), termId = SelectionFixtures.OTHER_TERM),
                timetable("周二课程（合成）", ClassSlot(2, 1, 2, TeachingWeeks.range(1, 16)))
            )
        )
        assertFalse("跨学期与跨星期都必须被隔离（UNVERIFIED 合成学期）", report.hasConflict)
    }

    @Test
    fun `多时段候选课程逐个时段报告冲突`() {
        val report = SelectionConflictAnalyzer.analyze(
            candidate(
                ClassSlot(1, 1, 2, TeachingWeeks.range(1, 16)),
                ClassSlot(3, 5, 6, TeachingWeeks.range(1, 16))
            ),
            listOf(
                timetable("线性代数（合成）", ClassSlot(3, 6, 7, TeachingWeeks.range(1, 16))),
                timetable("大学物理（合成）", ClassSlot(1, 2, 3, TeachingWeeks.range(1, 16)))
            )
        )
        assertEquals(2, report.details.size)
        assertEquals("明细必须按星期排序", listOf(1, 3), report.details.map { it.weekday })
        assertEquals("周一", report.details.first().weekdayLabel)
    }

    @Test
    fun `已有课表桥接保留节次周次与地点`() {
        val courses = listOf(
            Course(id = "c1", name = "线性代数（合成）", colorArgb = 0L),
            Course(id = "c2", name = "大学物理（合成）", colorArgb = 0L)
        )
        val rules = listOf(
            CourseScheduleRule(
                id = "r1", courseId = "c1", termId = SelectionFixtures.TERM, weekday = DayOfWeek.MONDAY,
                startPeriod = 1, endPeriod = 2, weeks = (1..16).toSet(), rawRoom = "A101"
            ),
            CourseScheduleRule(
                id = "r2", courseId = "c2", termId = SelectionFixtures.TERM, weekday = DayOfWeek.WEDNESDAY,
                startPeriod = 3, endPeriod = 4, weeks = emptySet(), rawRoom = ""
            ),
            CourseScheduleRule(
                id = "r3", courseId = "missing-course", termId = SelectionFixtures.TERM, weekday = DayOfWeek.THURSDAY,
                startPeriod = 5, endPeriod = 6, weeks = emptySet(), rawRoom = "B202"
            ),
            CourseScheduleRule(
                id = "r4", courseId = "c1", termId = SelectionFixtures.OTHER_TERM, weekday = DayOfWeek.FRIDAY,
                startPeriod = 7, endPeriod = 8, weeks = (1..16).toSet(), rawRoom = "C303"
            )
        )
        val occupied = TimetableOccupancy.fromScheduleRules(SelectionFixtures.TERM, courses, rules)

        assertEquals("找不到课程元数据的规则与其它学期的规则都必须被跳过", 2, occupied.size)
        assertTrue(occupied.all { it.source == OccupiedSource.TIMETABLE })
        assertEquals(listOf("周一", "周三"), occupied.map { it.slot.weekdayLabel })
        assertEquals("A101", occupied[0].slot.location)
        assertEquals("空周次按未知处理", TeachingWeeks.UNKNOWN, occupied[1].slot.weeks)
        assertEquals("大学物理（合成）", occupied[1].courseName)
    }

    @Test
    fun `桥接后的占用时段可直接参与冲突分析`() {
        val courses = listOf(Course(id = "c1", name = "线性代数（合成）", colorArgb = 0L))
        val rules = listOf(
            CourseScheduleRule(
                id = "r1", courseId = "c1", termId = SelectionFixtures.TERM, weekday = DayOfWeek.MONDAY,
                startPeriod = 1, endPeriod = 2, weeks = (1..16).toSet(), rawRoom = "A101"
            )
        )
        val report = SelectionConflictAnalyzer.analyze(
            candidate(ClassSlot(1, 1, 2, TeachingWeeks.range(1, 16))),
            TimetableOccupancy.fromScheduleRules(SelectionFixtures.TERM, courses, rules)
        )
        assertEquals(1, report.timetableConflicts.size)
        assertEquals("线性代数（合成）", report.details.single().conflictingCourseName)
    }
}
