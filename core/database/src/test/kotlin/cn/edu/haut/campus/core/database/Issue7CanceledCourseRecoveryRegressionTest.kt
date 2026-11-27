package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.Term
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * #7 「选择今日停课后无法恢复」端到端回归：
 * 停课后课程虽然从 active 排课隐藏，但必须保留可发现、可精确恢复的单日覆盖身份；
 * 恢复后原时段重新出现，重复恢复不得制造第二条排课或残留覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class Issue7CanceledCourseRecoveryRegressionTest {
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
    private val course = Course(
        id = "c_issue7",
        name = " issue7 合成课 ".trim(),
        teacher = "王老师",
        colorArgb = 0xFF053968,
        source = CourseSource.MANUAL,
        userModified = true
    )
    private val date = LocalDate.of(2026, 10, 12) // 第 7 周周一

    @Before
    fun setUp() = runBlocking {
        fixture = RepairTestFixture.create(RuntimeEnvironment.getApplication(), "prof_issue7_" + System.nanoTime())
        dao.upsertTerms(listOf(term.toEntity()))
        dao.upsertPeriodTimes(HautMockSeedData.defaultPeriodTimes.map { it.toEntity() })
        dao.upsertBuildings(HautMockSeedData.lianhuajieBuildings.map { it.toEntity() })
        dao.upsertBuildingAliases(HautMockSeedData.defaultBuildingAliases.map { it.toEntity() })
        dao.upsertCourse(course.toEntity())
    }

    @After
    fun tearDown() {
        repo.onScheduleOrPolicyChangedListener = null
        fixture.close()
    }

    private fun rule(id: String, startPeriod: Int, endPeriod: Int) = CourseScheduleRule(
        id = id,
        courseId = course.id,
        termId = term.id,
        weekday = DayOfWeek.MONDAY,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        weeks = (1..16).toSet(),
        rawRoom = "31号楼31101",
        buildingId = "b_t1",
        userModified = true
    )

    private suspend fun activeOccurrences(rules: List<CourseScheduleRule>) = ScheduleEngine.resolveOccurrencesForDate(
        date = date,
        term = term,
        courses = listOf(course),
        rules = rules,
        periodTimes = HautMockSeedData.defaultPeriodTimes,
        occurrenceOverrides = dao.getOccurrenceOverridesSnapshot().map { it.toDomain() },
        buildings = HautMockSeedData.lianhuajieBuildings
    )

    private suspend fun canceledOccurrences(rules: List<CourseScheduleRule>) = ScheduleEngine.resolveCanceledOccurrencesForDate(
        date = date,
        term = term,
        courses = listOf(course),
        rules = rules,
        periodTimes = HautMockSeedData.defaultPeriodTimes,
        occurrenceOverrides = dao.getOccurrenceOverridesSnapshot().map { it.toDomain() }
    )

    @Test
    fun canceledCourseRemainsDiscoverableAndExactRestoreReturnsItOnce() = runBlocking {
        val r1 = rule("r_issue7_main", 1, 2)
        dao.upsertRule(r1.toEntity())
        val before = activeOccurrences(listOf(r1)).single()

        repo.cancelClassOccurrenceOnDate(before, term.id, reason = "误操作停课")
        assertTrue("停课后 active 排课必须隐藏", activeOccurrences(listOf(r1)).isEmpty())
        val canceled = canceledOccurrences(listOf(r1))
        assertEquals("停课课程必须仍能在「当日已停课」中发现", 1, canceled.size)
        assertEquals(course.name, canceled.single().courseName)
        assertTrue(canceled.single().overrideId?.startsWith("ov_cancel_") == true)

        var restoreNotifications = 0
        repo.onScheduleOrPolicyChangedListener = { reason ->
            if (reason == "OCCURRENCE_RESTORED") restoreNotifications += 1
        }
        val applied = restoreSelectionFromState(
            dao = dao,
            repository = repo,
            termId = term.id,
            date = date,
            ruleId = r1.id,
            mergedRuleIds = listOf(r1.id)
        )
        assertEquals(CampusRepository.OccurrenceCommandStatus.APPLIED, applied.status)
        assertEquals(listOf(canceled.single().overrideId), applied.restoredSourceOverrideIds)
        assertEquals(1, restoreNotifications)
        assertTrue("恢复后覆盖行不得残留", dao.getOccurrenceOverridesSnapshot().isEmpty())
        assertEquals("恢复后原时段必须重新出现", 1, activeOccurrences(listOf(r1)).size)

        // 用户重复点按/重复提交时，新快照已无可恢复行：安全 NO_CHANGE，且不复制课程。
        val second = restoreSelectionFromState(
            dao = dao,
            repository = repo,
            termId = term.id,
            date = date,
            ruleId = r1.id,
            mergedRuleIds = listOf(r1.id)
        )
        assertEquals(CampusRepository.OccurrenceCommandStatus.NO_CHANGE, second.status)
        assertEquals(1, activeOccurrences(listOf(r1)).size)
        assertTrue(dao.getOccurrenceOverridesSnapshot().isEmpty())
    }

    @Test
    fun mergedAdjacentCourseCancellationRestoresWholeAtomicSelection() = runBlocking {
        val r1 = rule("r_issue7_merged_1", 1, 2)
        val r2 = rule("r_issue7_merged_2", 3, 4)
        dao.upsertRules(listOf(r1, r2).map { it.toEntity() })
        val mergedBefore = activeOccurrences(listOf(r1, r2)).single()
        assertEquals(setOf(r1.id, r2.id), mergedBefore.mergedRuleIds.toSet())

        repo.cancelClassOccurrenceOnDate(mergedBefore, term.id, reason = "误操作停课")
        assertTrue(activeOccurrences(listOf(r1, r2)).isEmpty())
        assertEquals("合并连排课的两条规则都必须保留停课身份", 2, dao.getOccurrenceOverridesSnapshot().size)
        val canceled = canceledOccurrences(listOf(r1, r2))
        assertEquals("UI 可发现的停课课程仍应折叠为一节课", 1, canceled.size)
        assertEquals(setOf(r1.id, r2.id), canceled.single().mergedRuleIds.toSet())

        val applied = restoreSelectionFromState(
            dao = dao,
            repository = repo,
            termId = term.id,
            date = date,
            mergedRuleIds = mergedBefore.mergedRuleIds
        )
        assertEquals(CampusRepository.OccurrenceCommandStatus.APPLIED, applied.status)
        assertEquals(2, applied.restoredSourceOverrideIds.size)
        assertTrue(dao.getOccurrenceOverridesSnapshot().isEmpty())
        val restored = activeOccurrences(listOf(r1, r2)).single()
        assertEquals(setOf(r1.id, r2.id), restored.mergedRuleIds.toSet())
    }
}
