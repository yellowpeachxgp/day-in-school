package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.academic.HautLiveSyncResult
import cn.edu.haut.campus.core.academic.UnscheduledAcademicCourse
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.OccurrenceRule
import cn.edu.haut.campus.core.model.Term
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * #7/#8/#15 功能验收回归：
 * - #7 普通停课恢复后原时段重新排课；
 * - #8 手动新增参数校验（空名/越界节次/空周次明确拒绝，不静默兜底）；
 * - #15 实践课删除最后规则回待定、非最后不回、重复设置不产生重复课程、sourceKey 跨学期隔离。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CourseManualAndPracticeAcceptanceTest {
    private lateinit var fixture: RepairTestFixture
    private val dao get() = fixture.dao
    private val repo get() = fixture.repository

    private val term = Term(
        id = "2026-2027-1",
        academicYear = "2026-2027",
        termNumber = 1,
        startDate = LocalDate.of(2026, 8, 31),
        weekCount = 20,
        timeTableId = HautMockSeedData.DEFAULT_TIMETABLE_ID,
        current = true
    )

    @Before
    fun setUp() = runBlocking {
        fixture = RepairTestFixture.create(RuntimeEnvironment.getApplication(), "prof_accept_${System.nanoTime()}")
        dao.upsertTerms(listOf(term.toEntity()))
        dao.upsertPeriodTimes(HautMockSeedData.defaultPeriodTimes.map { it.toEntity() })
        dao.upsertBuildings(HautMockSeedData.lianhuajieBuildings.map { it.toEntity() })
        dao.upsertBuildingAliases(HautMockSeedData.defaultBuildingAliases.map { it.toEntity() })
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    // ---- #7 普通停课恢复 ----

    @Test
    fun `普通停课恢复后原时段重新出现排课`() = runBlocking {
        val course = Course("c_a", name = "合成数学", teacher = null, colorArgb = 0xFF053968, source = CourseSource.MANUAL, userModified = true)
        val rule = CourseScheduleRule("r_a", course.id, term.id, DayOfWeek.MONDAY, 1, 2, (1..16).toSet(), "合成教室")
        dao.upsertCourse(course.toEntity())
        dao.upsertRule(rule.toEntity())
        val date = LocalDate.of(2026, 10, 12)
        val occ = ScheduleEngine.resolveOccurrencesForDate(
            date = date, term = term, courses = listOf(course), rules = listOf(rule),
            periodTimes = HautMockSeedData.defaultPeriodTimes,
            occurrenceOverrides = emptyList(),
            buildings = HautMockSeedData.lianhuajieBuildings
        ).single()
        repo.cancelClassOccurrenceOnDate(occ = occ, termId = term.id, reason = "临时停课")
        assertTrue(
            ScheduleEngine.resolveOccurrencesForDate(
                date = date, term = term, courses = listOf(course), rules = listOf(rule),
                periodTimes = HautMockSeedData.defaultPeriodTimes,
                occurrenceOverrides = dao.getOccurrenceOverridesSnapshot().map { it.toDomain() },
                buildings = HautMockSeedData.lianhuajieBuildings
            ).isEmpty()
        )
        restoreSelectionFromState(dao, repo, term.id, date = date, ruleId = rule.id, mergedRuleIds = listOf(rule.id))
        val restored = ScheduleEngine.resolveOccurrencesForDate(
            date = date, term = term, courses = listOf(course), rules = listOf(rule),
            periodTimes = HautMockSeedData.defaultPeriodTimes,
            occurrenceOverrides = dao.getOccurrenceOverridesSnapshot().map { it.toDomain() },
            buildings = HautMockSeedData.lianhuajieBuildings
        )
        assertEquals(1, restored.size)
    }

    // ---- #8 手动新增校验 ----

    @Test
    fun `手动新增拒绝空课程名越界节次与空周次`() = runBlocking {
        assertTrue(
            runCatching {
                repo.addManualCourseWithRule(term.id, "  ", null, "教室", DayOfWeek.MONDAY, 1, 2, setOf(1), 0xFF1)
            }.isFailure
        )
        assertTrue(
            runCatching {
                repo.addManualCourseWithRule(term.id, "合法课", null, "教室", DayOfWeek.MONDAY, 0, 2, setOf(1), 0xFF1)
            }.isFailure
        )
        assertTrue(
            runCatching {
                repo.addManualCourseWithRule(term.id, "合法课", null, "教室", DayOfWeek.MONDAY, 3, 2, setOf(1), 0xFF1)
            }.isFailure
        )
        assertTrue(
            runCatching {
                repo.addManualCourseWithRule(term.id, "合法课", null, "教室", DayOfWeek.MONDAY, 1, 2, emptySet(), 0xFF1)
            }.isFailure
        )
        assertTrue(
            runCatching {
                repo.addManualCourseWithRule(term.id, "合法课", null, "教室", DayOfWeek.MONDAY, 1, 2, setOf(99), 0xFF1)
            }.isFailure
        )
        // 全部拒绝后不得有任何残留写入
        assertTrue(dao.getCoursesSnapshot().isEmpty())
        assertTrue(dao.getRulesSnapshot().isEmpty())
    }

    @Test
    fun `手动新增合法参数正常落库且 userModified 不被同步覆盖语义保留`() = runBlocking {
        repo.addManualCourseWithRule(
            termId = term.id, name = "合成手动课", teacher = "王老师", rawRoom = "合成教室",
            weekday = DayOfWeek.TUESDAY, startPeriod = 3, endPeriod = 4,
            weeks = setOf(1, 3, 5), colorArgb = 0xFF0072BC
        )
        val courses = dao.getCoursesSnapshot()
        val rules = dao.getRulesSnapshot()
        assertEquals(1, courses.size)
        assertEquals(1, rules.size)
        assertTrue(courses.single().userModified)
        assertTrue(rules.single().userModified)
        assertEquals(setOf(1, 3, 5), rules.single().toDomain().weeks)
        assertEquals(CourseSource.MANUAL.name, courses.single().source)
    }

    // ---- #15 实践课待定 ----

    private fun unscheduled(termId: String = term.id, name: String = "合成实践课") = UnscheduledAcademicCourse(
        sourceId = "sjk_src_1", name = name, teacher = "李老师",
        credit = BigDecimal("2.0"), rawWeeks = "14-16周", category = "集中实践"
    ).let { listOf(it).toUnscheduledEntities(termId).single() }

    @Test
    fun `删除实践课最后一条规则回待定 删非最后不回`() = runBlocking {
        val pending = unscheduled()
        dao.upsertUnscheduledCourses(listOf(pending))

        // 给实践课排两个时段（两条规则 → 同课程）
        repo.scheduleUnscheduledPracticalCourse(
            unscheduled = pending, rawRoom = "实践场地A", weekday = DayOfWeek.WEDNESDAY,
            startPeriod = 7, endPeriod = 8, weeks = setOf(14, 15)
        )
        val course = dao.getCoursesSnapshot().single { it.externalId == "sjk:${term.id}:${pending.sourceKey}" }
        // 手动再插一条同学期同课程规则作为第二条排课
        val secondRule = CourseScheduleRule(
            id = "r_prac_second", courseId = course.id, termId = term.id,
            weekday = DayOfWeek.FRIDAY, startPeriod = 9, endPeriod = 10,
            weeks = setOf(16), rawRoom = "实践场地B", userModified = true
        )
        dao.upsertRule(secondRule.toEntity())
        assertTrue(dao.getUnscheduledCoursesSnapshot().isEmpty())

        // 删除第一条：还有规则 → 不回待定
        val firstRule = dao.getRulesSnapshot().first { it.id != "r_prac_second" }
        repo.deleteCourseScheduleRules(listOf(firstRule.id))
        assertTrue(dao.getUnscheduledCoursesSnapshot().isEmpty())

        // 删除最后一条：回到待定
        repo.deleteCourseScheduleRules(listOf("r_prac_second"))
        val pendingAgain = dao.getUnscheduledCoursesSnapshot()
        assertEquals(1, pendingAgain.size)
        assertEquals(term.id, pendingAgain.single().termId)
        assertEquals(pending.sourceKey, pendingAgain.single().sourceKey)
    }

    @Test
    fun `重复设置实践课不产生重复课程或重复待定项`() = runBlocking {
        val pending = unscheduled()
        dao.upsertUnscheduledCourses(listOf(pending))
        repo.scheduleUnscheduledPracticalCourse(
            unscheduled = pending, rawRoom = "实践场地A", weekday = DayOfWeek.WEDNESDAY,
            startPeriod = 7, endPeriod = 8, weeks = setOf(14, 15)
        )
        val firstCourseCount = dao.getCoursesSnapshot().size
        val firstRuleCount = dao.getRulesSnapshot().size
        // 再同步：待定项已清除；用户又设置一次（模拟重复提交）
        repo.scheduleUnscheduledPracticalCourse(
            unscheduled = pending, rawRoom = "实践场地A", weekday = DayOfWeek.WEDNESDAY,
            startPeriod = 7, endPeriod = 8, weeks = setOf(14, 15)
        )
        assertEquals(firstCourseCount, dao.getCoursesSnapshot().size)
        assertEquals(firstRuleCount, dao.getRulesSnapshot().size)
        assertTrue(dao.getUnscheduledCoursesSnapshot().isEmpty())
        // 课程保持 userModified（再同步不得静默覆盖用户排课）
        assertTrue(dao.getCoursesSnapshot().single().userModified)
    }

    @Test
    fun `实践课排课后权威重同步不重复待定项或课程`() = runBlocking {
        val pending = unscheduled()
        dao.upsertUnscheduledCourses(listOf(pending))
        repo.scheduleUnscheduledPracticalCourse(
            unscheduled = pending, rawRoom = "实践场地A", weekday = DayOfWeek.WEDNESDAY,
            startPeriod = 7, endPeriod = 8, weeks = setOf(14, 15)
        )
        val courseCount = dao.getCoursesSnapshot().size
        val ruleCount = dao.getRulesSnapshot().size

        // 模拟权威重同步返回同一门待定实践课：已排课的 sourceKey 不得再次进入待定列表
        repo.applyLiveSyncData(
            HautLiveSyncResult(
                termId = term.id,
                studentProfile = null,
                timetableCandidates = emptyList(),
                gradeAttempts = emptyList(),
                unscheduledCourses = listOf(unscheduled().toDomain()),
                semesterStart = term.startDate,
                totalWeeks = term.weekCount
            )
        )
        assertTrue(dao.getUnscheduledCoursesSnapshot().isEmpty())
        assertEquals(courseCount, dao.getCoursesSnapshot().size)
        assertEquals(ruleCount, dao.getRulesSnapshot().size)
        assertTrue(dao.getCoursesSnapshot().single().userModified)
    }

    @Test
    fun `同 sourceKey 实践课在不同账号数据库互不串数据`() = runBlocking {
        val other = RepairTestFixture.create(RuntimeEnvironment.getApplication(), "prof_accept_other_${System.nanoTime()}")
        try {
            val pending = unscheduled()
            dao.upsertUnscheduledCourses(listOf(pending))
            repo.scheduleUnscheduledPracticalCourse(
                unscheduled = pending, rawRoom = "实践场地", weekday = DayOfWeek.WEDNESDAY,
                startPeriod = 7, endPeriod = 8, weeks = setOf(14)
            )
            // 本账号已排课并清除待定；另一账号的同 sourceKey 待定项不受影响
            val otherPending = unscheduled()
            other.database.campusDao().upsertUnscheduledCourses(listOf(otherPending))
            assertTrue(dao.getUnscheduledCoursesSnapshot().isEmpty())
            assertEquals(1, other.database.campusDao().getUnscheduledCoursesSnapshot().size)
            assertTrue(other.database.campusDao().getCoursesSnapshot().isEmpty())
        } finally {
            other.close()
        }
    }

    @Test
    fun `同 sourceKey 实践课在不同学期互不串数据`() = runBlocking {
        val pendingT1 = unscheduled(termId = "2025-2026-1")
        val pendingT2 = unscheduled(termId = term.id)
        dao.upsertUnscheduledCourses(listOf(pendingT1, pendingT2))
        repo.scheduleUnscheduledPracticalCourse(
            unscheduled = pendingT2, rawRoom = "实践场地", weekday = DayOfWeek.WEDNESDAY,
            startPeriod = 7, endPeriod = 8, weeks = setOf(14)
        )
        val pending = dao.getUnscheduledCoursesSnapshot()
        assertEquals(1, pending.size)
        assertEquals("2025-2026-1", pending.single().termId)
        // 本学期实践课程与旧学期待定项各自独立
        assertTrue(dao.getCoursesSnapshot().all { it.externalId?.startsWith("sjk:${term.id}:") == true })
    }

    @Test
    fun 实践课多日矩阵一次性落库并保留未变规则标识() = runBlocking {
        val pending = unscheduled()
        dao.upsertUnscheduledCourses(listOf(pending))
        val monday = OccurrenceRule(DayOfWeek.MONDAY, 1, 4, setOf(14, 15), "工程中心A")
        val wednesday = OccurrenceRule(
            DayOfWeek.WEDNESDAY, 7, 8, setOf(14), "工程中心B",
            customStartTime = LocalTime.of(13, 30), customEndTime = LocalTime.of(16, 30)
        )
        repo.timetableRepository.scheduleUnscheduledPracticalCourseWithOccurrences(
            unscheduled = pending,
            occurrences = listOf(monday, wednesday)
        )
        assertTrue(dao.getUnscheduledCoursesSnapshot().isEmpty())
        assertEquals(1, dao.getCoursesSnapshot().size)
        assertEquals(2, dao.getRulesSnapshot().size)
        assertTrue(dao.getCoursesSnapshot().single().userModified)
        assertTrue(dao.getRulesSnapshot().all { it.userModified })
        val firstMonday = dao.getRulesSnapshot().single { it.weekdayIso == DayOfWeek.MONDAY.value }
        assertEquals("14,15", firstMonday.weeksCsv)
        assertEquals(1, firstMonday.startPeriod)
        assertEquals(4, firstMonday.endPeriod)
        val wednesdayRow = dao.getRulesSnapshot().single { it.weekdayIso == DayOfWeek.WEDNESDAY.value }
        assertEquals("13:30", wednesdayRow.customStartTimeIso)
        assertEquals("16:30", wednesdayRow.customEndTimeIso)

        val friday = OccurrenceRule(DayOfWeek.FRIDAY, 9, 10, setOf(15, 16), "工程中心C")
        repo.timetableRepository.scheduleUnscheduledPracticalCourseWithOccurrences(
            unscheduled = pending,
            occurrences = listOf(monday, friday)
        )
        val rows = dao.getRulesSnapshot()
        assertEquals(2, rows.size)
        assertEquals(firstMonday.id, rows.single { it.weekdayIso == DayOfWeek.MONDAY.value }.id)
        assertTrue(rows.none { it.weekdayIso == DayOfWeek.WEDNESDAY.value })
        assertEquals(1, rows.count { it.weekdayIso == DayOfWeek.FRIDAY.value })
        assertTrue(dao.getUnscheduledCoursesSnapshot().isEmpty())
    }

    @Test
    fun 实践课矩阵非法输入不写库且权威重同步不覆盖用户修改() = runBlocking {
        val pending = unscheduled()
        dao.upsertUnscheduledCourses(listOf(pending))
        val valid = OccurrenceRule(DayOfWeek.MONDAY, 1, 4, setOf(14), "工程中心A")
        val invalid = OccurrenceRule(DayOfWeek.TUESDAY, 3, 2, emptySet(), " ")
        assertTrue(
            runCatching {
                repo.timetableRepository.scheduleUnscheduledPracticalCourseWithOccurrences(
                    unscheduled = pending,
                    occurrences = listOf(valid, invalid)
                )
            }.isFailure
        )
        assertTrue(dao.getCoursesSnapshot().isEmpty())
        assertTrue(dao.getRulesSnapshot().isEmpty())
        assertEquals(1, dao.getUnscheduledCoursesSnapshot().size)

        repo.timetableRepository.scheduleUnscheduledPracticalCourseWithOccurrences(
            unscheduled = pending,
            occurrences = listOf(valid, OccurrenceRule(DayOfWeek.THURSDAY, 5, 8, setOf(15), "工程中心B"))
        )
        val ruleCount = dao.getRulesSnapshot().size
        repo.applyLiveSyncData(
            HautLiveSyncResult(
                termId = term.id,
                studentProfile = null,
                timetableCandidates = emptyList(),
                gradeAttempts = emptyList(),
                unscheduledCourses = listOf(unscheduled().toDomain()),
                semesterStart = term.startDate,
                totalWeeks = term.weekCount
            )
        )
        assertTrue(dao.getUnscheduledCoursesSnapshot().isEmpty())
        assertEquals(ruleCount, dao.getRulesSnapshot().size)
        assertTrue(dao.getCoursesSnapshot().single().userModified)
        assertTrue(dao.getRulesSnapshot().all { it.userModified })
    }
}
