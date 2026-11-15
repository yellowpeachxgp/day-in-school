package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * #13 自习室名录完整性回归：名录条目唯一性、楼宇引用一致性、跨校区同名不合并、
 * 空课表输入不得冒充「全部空闲」、来源诚实标注。
 */
class CampusStudyRoomCatalogIntegrityTest {

    @Test
    fun all17SongshanluStudyRoomsUniqueAndReferencedBuildingsExistInResolver() {
        val songshanRooms = CampusStudyRoomCatalog.SONGSHANLU_STUDY_ROOMS_MAP.values.flatten()
        assertEquals(17, songshanRooms.size)
        assertEquals("Songshanlu room entries must all be unique", 17, songshanRooms.toSet().size)
        assertEquals(17, CampusStudyRoomCatalog.ALL_SONGSHANLU_CANDIDATE_ROOMS.size)

        val resolverNames = ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS.map { it.name }.toSet()
        for (buildingKey in CampusStudyRoomCatalog.SONGSHANLU_STUDY_ROOMS_MAP.keys) {
            assertTrue(
                "Study-room building key must exist in ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS",
                buildingKey in resolverNames
            )
        }
        for (room in songshanRooms) {
            assertTrue(
                "Songshanlu room must carry a building prefix so same-numbered rooms never merge",
                room.startsWith("嵩山1教") || room.startsWith("嵩山2教") || room.startsWith("嵩山综合楼")
            )
        }

        val lhjRooms = CampusStudyRoomCatalog.LIANHUAJIE_STUDY_ROOMS_MAP.values.flatten()
        assertEquals(lhjRooms.size, lhjRooms.toSet().size)
    }

    @Test
    fun sameRoomNumberInDifferentBuildingsDoesNotMergeBusyState() {
        val busyT1 = listOf("嵩山路校区1号教学楼101")
        assertFalse(CampusStudyRoomCatalog.isRoomFree("嵩山1教101", busyT1))
        assertTrue(
            "嵩山2教101 in a different building must NOT merge with 1教101",
            CampusStudyRoomCatalog.isRoomFree("嵩山2教101", busyT1)
        )
        assertTrue(CampusStudyRoomCatalog.isRoomFree("嵩山综合楼201", busyT1))

        val busyT2 = listOf("嵩山路校区2号教学楼101")
        assertFalse(CampusStudyRoomCatalog.isRoomFree("嵩山2教101", busyT2))
        assertTrue(CampusStudyRoomCatalog.isRoomFree("嵩山1教101", busyT2))

        // 跨校区隔离：莲花街占用串不会标记嵩山路房间，反之亦然
        assertTrue(CampusStudyRoomCatalog.isRoomFree("嵩山1教101", listOf("3号教学楼3101")))
        assertTrue(CampusStudyRoomCatalog.isRoomFree("3101", listOf("嵩山路校区1号教学楼101")))
        // 无校区前缀的裸数字串绝不能归并到嵩山路房间键
        assertTrue(CampusStudyRoomCatalog.isRoomFree("嵩山1教101", listOf("101")))
    }

    @Test
    fun emptyScheduleInputMarksSummaryWithoutBasisNotAllFree() {
        val slot = CampusStudyRoomCatalog.STUDY_PERIOD_SLOTS.first()

        val fromOccurrences = CampusStudyRoomCatalog.computeAvailabilityForOccurrences(
            occurrences = emptyList(),
            slot = slot,
            campusId = "songshanlu"
        )
        assertEquals(17, fromOccurrences.freeRoomsCount)
        assertFalse(
            "Empty occurrence input must be flagged hasScheduleBasis=false (unobserved != all free)",
            fromOccurrences.hasScheduleBasis
        )

        val fromDate = CampusStudyRoomCatalog.computeAvailabilityForDate(
            date = LocalDate.of(2026, 9, 7),
            term = null,
            courses = emptyList(),
            rules = emptyList(),
            periodTimes = emptyList(),
            slot = slot,
            campusId = "lianhuajie"
        )
        assertFalse(fromDate.hasScheduleBasis)

        val withRule = CampusStudyRoomCatalog.computeAvailabilityForDate(
            date = LocalDate.of(2026, 9, 7),
            term = null,
            courses = emptyList(),
            rules = listOf(
                CourseScheduleRule(
                    id = "r1", courseId = "c1", termId = "t1",
                    weekday = DayOfWeek.MONDAY,
                    startPeriod = 1, endPeriod = 2, weeks = setOf(1),
                    rawRoom = "5号教学楼5101"
                )
            ),
            periodTimes = emptyList(),
            slot = slot,
            campusId = "lianhuajie"
        )
        assertTrue(withRule.hasScheduleBasis)
    }

    @Test
    fun catalogProvenanceHonestlyMarksPersonalReferenceUnverifiedData() {
        val ss = CampusStudyRoomCatalog.catalogProvenanceForCampus("songshanlu")
        assertEquals("songshanlu", ss.campusKey)
        assertEquals(StudyRoomAvailabilityMeaning.PERSONAL_REFERENCE_NOT_REALTIME, ss.availabilityMeaning)
        assertNull("No official verification evidence exists - verifiedAt must stay null", ss.verifiedAt)
        assertTrue(ss.sourceReference!!.contains("非官方"))
        assertTrue(ss.note!!.contains("不代表"))

        val lhj = CampusStudyRoomCatalog.catalogProvenanceForCampus("lianhuajie")
        assertEquals("lianhuajie", lhj.campusKey)
        assertEquals(StudyRoomAvailabilityMeaning.PERSONAL_REFERENCE_NOT_REALTIME, lhj.availabilityMeaning)
        assertNull(lhj.verifiedAt)

        assertEquals("lianhuajie", CampusStudyRoomCatalog.catalogProvenanceForCampus(null).campusKey)
        assertEquals("lianhuajie", CampusStudyRoomCatalog.catalogProvenanceForCampus("unknown_x").campusKey)
        assertEquals("songshanlu", CampusStudyRoomCatalog.catalogProvenanceForCampus("嵩山").campusKey)
        assertEquals("嵩山路", CampusStudyRoomCatalog.campusShortLabel("嵩山"))
    }

    private fun testTerm(id: String = "2026-2027-1") = Term(
        id = id,
        academicYear = "2026-2027",
        termNumber = 1,
        startDate = LocalDate.of(2026, 8, 31),
        weekStartDay = DayOfWeek.MONDAY,
        weekCount = 20,
        timeTableId = "tt_study",
        current = true
    )

    private fun termRule(termId: String, courseId: String = "c1") = CourseScheduleRule(
        id = "r_$termId",
        courseId = courseId,
        termId = termId,
        weekday = DayOfWeek.MONDAY,
        startPeriod = 1,
        endPeriod = 2,
        weeks = setOf(1),
        rawRoom = "5号教学楼5101"
    )

    private fun termPeriods(timeTableId: String = "tt_study") = listOf(
        cn.edu.haut.campus.core.model.PeriodTime(
            "p1", timeTableId, 1, LocalTime.of(8, 30), LocalTime.of(9, 15), "上午"
        ),
        cn.edu.haut.campus.core.model.PeriodTime(
            "p2", timeTableId, 2, LocalTime.of(9, 20), LocalTime.of(10, 5), "上午"
        )
    )

    private fun termCourse(id: String = "c1") = cn.edu.haut.campus.core.model.Course(
        id = id,
        name = "数据结构",
        colorArgb = 0xFF2563EB,
        source = cn.edu.haut.campus.core.model.CourseSource.OFFICIAL_JW
    )

    @Test
    fun currentTermScheduleBasisRequiresStrictExpandableInputs() {
        val term = testTerm()
        val periods = termPeriods(term.timeTableId)
        val course = termCourse()

        // 无当前学期：即便残留其他学期 rules，也绝无课表依据（跨学期混杂不得当依据）
        assertFalse(
            CampusStudyRoomCatalog.hasCurrentTermScheduleBasis(
                term = null,
                courses = listOf(course),
                rules = listOf(termRule("old_term_1")),
                periodTimes = periods
            )
        )

        // rules 非空但全部属于其他学期：不足以证明本学期有可展开排课
        assertFalse(
            CampusStudyRoomCatalog.hasCurrentTermScheduleBasis(
                term = term,
                courses = listOf(course),
                rules = listOf(termRule("2025-2026-2"), termRule("2025-2026-1")),
                periodTimes = periods
            )
        )

        // 本学期可展开规则存在：依据成立（当日无课时的"全部无冲突"才是有效推导）
        assertTrue(
            CampusStudyRoomCatalog.hasCurrentTermScheduleBasis(
                term = term,
                courses = listOf(course),
                rules = listOf(termRule(term.id)),
                periodTimes = periods
            )
        )

        // 完整个人课表但今日无课：依据仍成立（依据看学期输入而非当日展开结果）
        assertTrue(
            CampusStudyRoomCatalog.hasCurrentTermScheduleBasis(
                term = term,
                courses = listOf(course),
                rules = listOf(termRule(term.id).copy(weeks = setOf(19))),
                periodTimes = periods
            )
        )

        // 学期作息表无任何节次：排课无法落到有效时间段，不构成依据
        assertFalse(
            CampusStudyRoomCatalog.hasCurrentTermScheduleBasis(
                term = term,
                courses = listOf(course),
                rules = listOf(termRule(term.id)),
                periodTimes = emptyList()
            )
        )
        assertFalse(
            CampusStudyRoomCatalog.hasCurrentTermScheduleBasis(
                term = term,
                courses = listOf(course),
                rules = listOf(termRule(term.id)),
                periodTimes = termPeriods("other_timetable")
            )
        )

        // 规则指向缺失课程：不可展开，不构成依据
        assertFalse(
            CampusStudyRoomCatalog.hasCurrentTermScheduleBasis(
                term = term,
                courses = emptyList(),
                rules = listOf(termRule(term.id)),
                periodTimes = periods
            )
        )

        // R3 回归：孤儿停课覆盖（指向已删除规则 r_x，无支撑规则）绝不构成依据
        assertFalse(
            "orphan canceled override without backing rule must NOT be a basis",
            CampusStudyRoomCatalog.hasCurrentTermScheduleBasis(
                term = term,
                courses = listOf(course),
                rules = emptyList(),
                periodTimes = periods,
                occurrenceOverrides = listOf(
                    OccurrenceOverride(
                        id = "oo_orphan_cancel",
                        termId = term.id,
                        courseRuleId = "r_x",
                        date = LocalDate.of(2026, 9, 7),
                        canceled = true
                    )
                )
            )
        )

        // 停课覆盖即便自带 courseId 也不可展开为事件（停课只屏蔽不产生），同样不构成依据
        assertFalse(
            CampusStudyRoomCatalog.hasCurrentTermScheduleBasis(
                term = term,
                courses = listOf(course),
                rules = emptyList(),
                periodTimes = periods,
                occurrenceOverrides = listOf(
                    OccurrenceOverride(
                        id = "oo_cancel_with_course",
                        termId = term.id,
                        courseId = course.id,
                        date = LocalDate.of(2026, 9, 7),
                        canceled = true
                    )
                )
            )
        )

        // 非停课补课覆盖可解析出真实课程：构成依据（无规则时的单日新增课）
        assertTrue(
            CampusStudyRoomCatalog.hasCurrentTermScheduleBasis(
                term = term,
                courses = listOf(course),
                rules = emptyList(),
                periodTimes = periods,
                occurrenceOverrides = listOf(
                    OccurrenceOverride(
                        id = "oo_makeup",
                        termId = term.id,
                        courseId = course.id,
                        date = LocalDate.of(2026, 9, 7),
                        startPeriod = 1,
                        endPeriod = 2,
                        canceled = false
                    )
                )
            )
        )

        // 非停课覆盖经现存规则关联解析出课程：构成依据
        // （支撑规则自身零周次不可展开，但覆盖行凭它解析到真实课程即可单独成立）
        assertTrue(
            CampusStudyRoomCatalog.hasCurrentTermScheduleBasis(
                term = term,
                courses = listOf(course),
                rules = listOf(termRule(term.id).copy(id = "r_empty_weeks", weeks = emptySet())),
                periodTimes = periods,
                occurrenceOverrides = listOf(
                    OccurrenceOverride(
                        id = "oo_linked",
                        termId = term.id,
                        courseRuleId = "r_empty_weeks",
                        date = LocalDate.of(2026, 9, 7),
                        canceled = false
                    )
                )
            )
        )

        // 非停课覆盖但课程不可解析（无 courseId 且规则不存在）：不构成依据
        assertFalse(
            CampusStudyRoomCatalog.hasCurrentTermScheduleBasis(
                term = term,
                courses = listOf(course),
                rules = emptyList(),
                periodTimes = periods,
                occurrenceOverrides = listOf(
                    OccurrenceOverride(
                        id = "oo_unresolvable",
                        termId = term.id,
                        courseRuleId = "r_missing",
                        date = LocalDate.of(2026, 9, 7),
                        canceled = false
                    )
                )
            )
        )

        // 其他学期覆盖不算数
        assertFalse(
            CampusStudyRoomCatalog.hasCurrentTermScheduleBasis(
                term = term,
                courses = listOf(course),
                rules = emptyList(),
                periodTimes = periods,
                occurrenceOverrides = listOf(
                    OccurrenceOverride(
                        id = "oo_foreign_term",
                        termId = "old_term_1",
                        courseId = course.id,
                        date = LocalDate.of(2026, 9, 7),
                        canceled = false
                    )
                )
            )
        )

        // 学期存在但零排课输入：无依据
        assertFalse(
            CampusStudyRoomCatalog.hasCurrentTermScheduleBasis(
                term = term,
                courses = listOf(course),
                rules = emptyList(),
                periodTimes = periods
            )
        )
    }

    @Test
    fun studySlotStatusLabelHonestAboutManualPickAndAutoTiming() {
        val slot = CampusStudyRoomCatalog.STUDY_PERIOD_SLOTS[1] // "3-4节"
        val upcoming = CampusStudyRoomCatalog.StudySlotResolution(
            slot = slot,
            timing = CampusStudyRoomCatalog.StudySlotTiming.UPCOMING_NEXT,
            usedFallbackTimetable = false
        )
        // 手动点选后：必须明示所选槽位，绝不再显示"已预选下一时段"
        assertEquals(
            "已手动选择：3-4节",
            CampusStudyRoomCatalog.studySlotStatusLabel("3-4节", upcoming)
        )
        // 自动模式按命中性质区分
        assertEquals(
            "课间休息中 · 已预选下一时段：3-4节",
            CampusStudyRoomCatalog.studySlotStatusLabel(null, upcoming)
        )
        val inSession = upcoming.copy(timing = CampusStudyRoomCatalog.StudySlotTiming.IN_SESSION)
        assertEquals(
            "自动定位当前时段：3-4节",
            CampusStudyRoomCatalog.studySlotStatusLabel(null, inSession)
        )
        val afterLast = upcoming.copy(timing = CampusStudyRoomCatalog.StudySlotTiming.AFTER_LAST)
        assertEquals(
            "今日课程已结束 · 参考时段：3-4节",
            CampusStudyRoomCatalog.studySlotStatusLabel(null, afterLast)
        )
        // 手动选择优先于任何自动 timing
        assertEquals(
            "已手动选择：1-2节",
            CampusStudyRoomCatalog.studySlotStatusLabel("1-2节", afterLast)
        )
    }
}
