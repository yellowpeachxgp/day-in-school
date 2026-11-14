package cn.edu.haut.campus.core.database

import androidx.room.withTransaction
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.Term
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 跨日调课稳定关联（moveId）恢复回归：
 * 覆盖计划第六节的恢复矩阵——同日同课程不同时段的两次独立调课、合并多源调课、
 * 重复恢复 no-op、跨学期/跨账号隔离、旧数据模糊调课待核对流程、重移动链与孤儿目标清理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CourseMoveRecoveryTest {
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
        id = "c_move_test",
        name = "合成数据结构",
        teacher = "张老师",
        colorArgb = 0xFF053968,
        source = CourseSource.MANUAL,
        userModified = true
    )

    @Before
    fun setUp() = runBlocking {
        fixture = RepairTestFixture.create(RuntimeEnvironment.getApplication(), "prof_move_${System.nanoTime()}")
        dao.upsertTerms(listOf(term.toEntity()))
        dao.upsertPeriodTimes(HautMockSeedData.defaultPeriodTimes.map { it.toEntity() })
        dao.upsertBuildings(HautMockSeedData.lianhuajieBuildings.map { it.toEntity() })
        dao.upsertBuildingAliases(HautMockSeedData.defaultBuildingAliases.map { it.toEntity() })
        dao.upsertCourse(course.toEntity())
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private fun rule(id: String, startPeriod: Int, endPeriod: Int, weekday: DayOfWeek = DayOfWeek.MONDAY) =
        CourseScheduleRule(
            id = id,
            courseId = course.id,
            termId = term.id,
            weekday = weekday,
            startPeriod = startPeriod,
            endPeriod = endPeriod,
            weeks = (1..16).toSet(),
            rawRoom = "合成教室101",
            userModified = true
        )

    private suspend fun occFor(rules: List<CourseScheduleRule>, date: LocalDate): ClassOccurrence {
        val list = ScheduleEngine.resolveOccurrencesForDate(
            date = date,
            term = term,
            courses = listOf(course),
            rules = rules,
            periodTimes = HautMockSeedData.defaultPeriodTimes,
            occurrenceOverrides = dao.getOccurrenceOverridesSnapshot().map { it.toDomain() },
            buildings = HautMockSeedData.lianhuajieBuildings
        )
        return list.single()
    }

    private suspend fun overrides() = dao.getOccurrenceOverridesSnapshot()

    @Test
    fun `同课程同源日期两节分别调到不同日期只恢复所选那次调课`() = runBlocking {
        val rMorning = rule("r_am", 1, 2)
        val rAfternoon = rule("r_pm", 5, 6)
        dao.upsertRules(listOf(rMorning, rAfternoon).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12) // 周一，在两规则周次内

        // 两次独立调课：上午 -> 周三，下午 -> 周四
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(rMorning, rAfternoon).filter { it.id == "r_am" }, sourceDate),
            termId = term.id, targetDate = LocalDate.of(2026, 10, 14),
            newStartPeriod = 1, newEndPeriod = 2, newRoom = "合成教室201"
        )
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(rAfternoon), sourceDate),
            termId = term.id, targetDate = LocalDate.of(2026, 10, 15),
            newStartPeriod = 5, newEndPeriod = 6, newRoom = "合成教室202"
        )

        val afterMoves = overrides()
        val moveA = afterMoves.filter { it.reason == "已调至 2026-10-14" }
        val moveB = afterMoves.filter { it.reason == "已调至 2026-10-15" }
        assertEquals(1, moveA.size)
        assertEquals(1, moveB.size)
        val moveAId = moveA.single().moveId!!
        val moveBId = moveB.single().moveId!!
        assertNotEquals(moveAId, moveBId)
        assertEquals(OccurrenceOverride.MOVE_ROLE_SOURCE, moveA.single().moveRole)
        val targetA = afterMoves.single { it.moveId == moveAId && it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        val targetB = afterMoves.single { it.moveId == moveBId && it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }

        // 只恢复上午的调课（moveA）
        val result = repo.restoreCanceledOccurrence(
            date = sourceDate, ruleId = rMorning.id, mergedRuleIds = listOf(rMorning.id)
        )
        assertTrue(result.restoredMoveIds.contains(moveAId))
        val remaining = overrides()
        // moveA 全部行被清除
        assertTrue(remaining.none { it.moveId == moveAId })
        // moveB 的源停课与目标完整保留
        assertTrue(remaining.any { it.id == moveB.single().id && it.canceled })
        assertTrue(remaining.any { it.id == targetB.id && !it.canceled })
        // 目标 A 被删除
        assertTrue(remaining.none { it.id == targetA.id })
    }

    @Test
    fun `同目标日期的两个独立调课互不干扰且自定义 reason 保留`() = runBlocking {
        val r1 = rule("r_1", 1, 2)
        val r2 = rule("r_2", 5, 6)
        dao.upsertRules(listOf(r1, r2).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val targetDate = LocalDate.of(2026, 10, 14)

        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id, targetDate = targetDate,
            newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室A", reason = "实验课调教室"
        )
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r2), sourceDate), termId = term.id, targetDate = targetDate,
            newStartPeriod = 5, newEndPeriod = 6, newRoom = "教室B"
        )

        val rows = overrides()
        val customTarget = rows.single { it.reason == "实验课调教室" }
        assertEquals(OccurrenceOverride.MOVE_ROLE_TARGET, customTarget.moveRole)
        assertEquals(2, rows.count { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET })
        val moveIds = rows.filter { it.moveRole == OccurrenceOverride.MOVE_ROLE_SOURCE }.map { it.moveId }.toSet()
        assertEquals(2, moveIds.size)

        // 恢复第一个调课（自定义 reason 的那次）
        repo.restoreCanceledOccurrence(date = sourceDate, ruleId = r1.id, mergedRuleIds = listOf(r1.id))
        val remaining = overrides()
        assertEquals(1, remaining.count { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET })
        assertTrue(remaining.any { it.courseRuleId == r2.id && it.canceled })
        // 自定义 reason 的目标行已随其 moveId 删除
        assertTrue(remaining.none { it.reason == "实验课调教室" })
    }

    @Test
    fun `合并 occurrence 多源一目标调课原子恢复且重复恢复安全`() = runBlocking {
        val r1 = rule("r_m1", 1, 2)
        val r2 = rule("r_m2", 3, 4)
        dao.upsertRules(listOf(r1, r2).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)

        val mergedOcc = occFor(listOf(r1, r2), sourceDate)
        assertEquals(listOf("r_m1", "r_m2"), mergedOcc.mergedRuleIds)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = mergedOcc, termId = term.id, targetDate = LocalDate.of(2026, 10, 14),
            newStartPeriod = 1, newEndPeriod = 4, newRoom = "合成大教室"
        )

        val moved = overrides()
        val moveIds = moved.mapNotNull { it.moveId }.toSet()
        assertEquals(1, moveIds.size)
        val moveId = moveIds.single()
        assertEquals(2, moved.count { it.moveRole == OccurrenceOverride.MOVE_ROLE_SOURCE })
        assertEquals(1, moved.count { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET })

        // 恢复整个调课（一次操作）
        val result = repo.restoreCanceledOccurrence(
            date = sourceDate, ruleId = r1.id, mergedRuleIds = listOf(r1.id, r2.id)
        )
        assertEquals(listOf(moveId), result.restoredMoveIds)
        assertTrue(overrides().none { it.moveId == moveId })

        // 重复恢复：安全 no-op，不影响数据
        val second = repo.restoreCanceledOccurrence(
            date = sourceDate, ruleId = r1.id, mergedRuleIds = listOf(r1.id, r2.id)
        )
        assertTrue(second.restoredMoveIds.isEmpty())
        assertTrue(second.restoredSourceOverrideIds.isEmpty())
        assertEquals(0, overrides().size)
    }

    @Test
    fun `普通停课仍可精确恢复且不受调课行影响`() = runBlocking {
        val r1 = rule("r_plain", 1, 2)
        val r2 = rule("r_moved", 5, 6)
        dao.upsertRules(listOf(r1, r2).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)

        // 普通停课 r1
        repo.cancelClassOccurrenceOnDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id, reason = "临时停课"
        )
        // 独立调课 r2
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r2), sourceDate), termId = term.id,
            targetDate = LocalDate.of(2026, 10, 14),
            newStartPeriod = 5, newEndPeriod = 6, newRoom = "教室"
        )
        val moveId = overrides().first { it.moveRole == OccurrenceOverride.MOVE_ROLE_SOURCE }.moveId!!

        // 恢复普通停课：只删 r1 的取消行，调课保持
        repo.restoreCanceledOccurrence(date = sourceDate, ruleId = r1.id, mergedRuleIds = listOf(r1.id))
        val remaining = overrides()
        assertTrue(remaining.none { it.courseRuleId == r1.id })
        assertTrue(remaining.any { it.moveId == moveId })
        assertEquals(2, remaining.size)
    }

    @Test
    fun `调课目标被编辑后仍按 moveId 精确删除`() = runBlocking {
        val r1 = rule("r_edit", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = LocalDate.of(2026, 10, 14),
            newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室X"
        )
        val target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        // 用户随后编辑了调入课的教室（保留 moveId 关联）
        dao.upsertOccurrenceOverride(target.copy(room = "改后教室", reason = "用户改教室"))
        val result = repo.restoreCanceledOccurrence(
            date = sourceDate, ruleId = r1.id, mergedRuleIds = listOf(r1.id)
        )
        assertTrue(result.deletedTargetOverrideIds.contains(target.id))
        assertTrue(overrides().isEmpty())
    }

    @Test
    fun `再次跨日移动调入目标串联 moveId 恢复回到最初源时段`() = runBlocking {
        val r1 = rule("r_chain", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val firstTarget = LocalDate.of(2026, 10, 14)
        val secondTarget = LocalDate.of(2026, 10, 16)

        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = firstTarget, newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室A"
        )
        val moveA = overrides().filter { it.courseRuleId == r1.id }.single().moveId!!

        // 在目标日解析调入 occurrence 并再次移动到新日期
        val movedInOcc = ScheduleEngine.resolveOccurrencesForDate(
            date = firstTarget, term = term, courses = listOf(course), rules = listOf(r1),
            periodTimes = HautMockSeedData.defaultPeriodTimes,
            occurrenceOverrides = overrides().map { it.toDomain() },
            buildings = HautMockSeedData.lianhuajieBuildings
        ).single { it.overrideId != null }
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = movedInOcc, termId = term.id,
            targetDate = secondTarget, newStartPeriod = 3, newEndPeriod = 4, newRoom = "教室B"
        )

        val afterChained = overrides()
        val moveIds = afterChained.mapNotNull { it.moveId }.toSet()
        assertEquals(1, moveIds.size)
        val moveB = moveIds.single()
        assertNotEquals(moveA, moveB)
        // 旧 moveA 已不存在；r1 的源停课行挂到新 moveId，新目标在 secondTarget
        assertTrue(afterChained.any { it.courseRuleId == r1.id && it.moveRole == OccurrenceOverride.MOVE_ROLE_SOURCE && it.moveId == moveB })
        assertTrue(afterChained.any { it.dateIso == secondTarget.toString() && it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET && it.moveId == moveB })
        assertTrue(afterChained.none { it.moveId == moveA })

        // 恢复 moveB：最初源时段恢复 + 最新目标删除，不残留双份排课
        repo.restoreCanceledOccurrence(date = sourceDate, ruleId = r1.id, mergedRuleIds = listOf(r1.id))
        assertTrue(overrides().isEmpty())
    }

    @Test
    fun `删除最后一条调课源规则同事务清理孤儿调入目标`() = runBlocking {
        val r1 = rule("r_orphan", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = LocalDate.of(2026, 10, 14),
            newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室"
        )
        assertEquals(2, overrides().size)
        // 删除该规则：源停课行随规则删除，孤儿 TARGET 一并清理
        repo.deleteCourseScheduleRules(listOf(r1.id))
        assertTrue(overrides().isEmpty())
    }

    @Test
    fun `不同学期的同名 moveId 不会跨学期恢复`() = runBlocking {
        val r1 = rule("r_term1", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val otherTerm = term.copy(id = "2026-2027-2", termNumber = 2, current = false)
        dao.upsertTerms(listOf(otherTerm.toEntity()))
        val sourceDate = LocalDate.of(2026, 10, 12)

        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = LocalDate.of(2026, 10, 14),
            newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室"
        )
        val moveId = overrides().first { it.courseRuleId == r1.id }.moveId!!
        // 在另一学期手工塞入同 moveId 的行，验证 termId 隔离
        dao.upsertOccurrenceOverride(
            OccurrenceOverride(
                id = "ov_other_term_src", termId = otherTerm.id, courseId = course.id,
                courseRuleId = "r_other", date = sourceDate, canceled = true,
                reason = "已调至 2026-10-14", moveId = moveId,
                moveRole = OccurrenceOverride.MOVE_ROLE_SOURCE
            ).toEntity()
        )
        dao.upsertOccurrenceOverride(
            OccurrenceOverride(
                id = "ov_other_term_tgt", termId = otherTerm.id, courseId = course.id,
                courseRuleId = null, date = LocalDate.of(2026, 10, 14), canceled = false,
                reason = "由 2026-10-12 调入", moveId = moveId,
                moveRole = OccurrenceOverride.MOVE_ROLE_TARGET
            ).toEntity()
        )

        repo.restoreCanceledOccurrence(date = sourceDate, ruleId = r1.id, mergedRuleIds = listOf(r1.id))
        val remaining = overrides()
        // 本学期的调课已撤销
        assertTrue(remaining.none { it.termId == term.id })
        // 另一学期同 moveId 的行完整保留
        assertEquals(2, remaining.count { it.termId == otherTerm.id && it.moveId == moveId })
    }

    @Test
    fun `旧版无 moveId 模糊调课恢复源后目标保留待核对由用户选择`() = runBlocking {
        val r1 = rule("r_legacy", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        // 模拟旧版写入：无 moveId 的源停课 + 两个候选调入目标（模糊，无法确定唯一）
        dao.upsertOccurrenceOverride(
            OccurrenceOverride(
                id = "ov_legacy_src", termId = term.id, courseId = course.id,
                courseRuleId = r1.id, date = sourceDate, canceled = true,
                reason = "已调至 2026-10-14"
            ).toEntity()
        )
        dao.upsertOccurrenceOverride(
            OccurrenceOverride(
                id = "ov_legacy_tgt_a", termId = term.id, courseId = course.id,
                courseRuleId = null, date = LocalDate.of(2026, 10, 14),
                startPeriod = 1, endPeriod = 2, room = "旧教室A", canceled = false,
                reason = "由 2026-10-12 调入"
            ).toEntity()
        )
        dao.upsertOccurrenceOverride(
            OccurrenceOverride(
                id = "ov_legacy_tgt_b", termId = term.id, courseId = course.id,
                courseRuleId = null, date = LocalDate.of(2026, 10, 14),
                startPeriod = 5, endPeriod = 6, room = "旧教室B", canceled = false,
                reason = "由 2026-10-12 调入"
            ).toEntity()
        )

        val result = repo.restoreCanceledOccurrence(
            date = sourceDate, ruleId = r1.id, mergedRuleIds = listOf(r1.id)
        )
        // 源已恢复，两个候选目标全部保留待核对
        assertTrue(result.legacyReviewRequired)
        assertEquals(2, result.legacyAmbiguousTargets.size)
        val remaining = overrides()
        assertTrue(remaining.none { it.id == "ov_legacy_src" })
        assertTrue(remaining.any { it.id == "ov_legacy_tgt_a" })
        assertTrue(remaining.any { it.id == "ov_legacy_tgt_b" })

        // 用户确认删除其中一个，另一个保留待核对
        repo.deleteOccurrenceOverride("ov_legacy_tgt_a")
        val afterUser = overrides()
        assertTrue(afterUser.none { it.id == "ov_legacy_tgt_a" })
        assertTrue(afterUser.any { it.id == "ov_legacy_tgt_b" })
    }

    @Test
    fun `外层事务失败时恢复操作整体回滚不留半恢复`() = runBlocking {
        val r1 = rule("r_tx", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = LocalDate.of(2026, 10, 14),
            newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室"
        )
        val before = overrides()
        assertEquals(2, before.size)

        val crashed = runCatching {
            fixture.database.withTransaction {
                repo.restoreCanceledOccurrence(
                    date = sourceDate, ruleId = r1.id, mergedRuleIds = listOf(r1.id)
                )
                error("模拟事务中途失败")
            }
        }
        assertTrue(crashed.isFailure)
        // 无半恢复：源与目标保持原样
        assertEquals(before.map { it.id }.toSet(), overrides().map { it.id }.toSet())
    }

    @Test
    fun `调课恢复通过 ScheduleEngine 验证恢复后原时段正常排课`() = runBlocking {
        val r1 = rule("r_engine", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val targetDate = LocalDate.of(2026, 10, 14)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = targetDate, newStartPeriod = 3, newEndPeriod = 4, newRoom = "教室"
        )
        // 源日期已无课，目标日期有调入课
        val sourceAfter = ScheduleEngine.resolveOccurrencesForDate(
            date = sourceDate, term = term, courses = listOf(course), rules = listOf(r1),
            periodTimes = HautMockSeedData.defaultPeriodTimes,
            occurrenceOverrides = overrides().map { it.toDomain() },
            buildings = HautMockSeedData.lianhuajieBuildings
        )
        assertTrue(sourceAfter.isEmpty())
        val targetAfter = ScheduleEngine.resolveOccurrencesForDate(
            date = targetDate, term = term, courses = listOf(course), rules = listOf(r1),
            periodTimes = HautMockSeedData.defaultPeriodTimes,
            occurrenceOverrides = overrides().map { it.toDomain() },
            buildings = HautMockSeedData.lianhuajieBuildings
        )
        assertEquals(1, targetAfter.size)

        repo.restoreCanceledOccurrence(date = sourceDate, ruleId = r1.id, mergedRuleIds = listOf(r1.id))
        // 恢复后：源日期重新有课，目标日期不再有调入课
        val restoredSource = ScheduleEngine.resolveOccurrencesForDate(
            date = sourceDate, term = term, courses = listOf(course), rules = listOf(r1),
            periodTimes = HautMockSeedData.defaultPeriodTimes,
            occurrenceOverrides = overrides().map { it.toDomain() },
            buildings = HautMockSeedData.lianhuajieBuildings
        )
        assertEquals(1, restoredSource.size)
        val restoredTarget = ScheduleEngine.resolveOccurrencesForDate(
            date = targetDate, term = term, courses = listOf(course), rules = listOf(r1),
            periodTimes = HautMockSeedData.defaultPeriodTimes,
            occurrenceOverrides = overrides().map { it.toDomain() },
            buildings = HautMockSeedData.lianhuajieBuildings
        )
        assertTrue(restoredTarget.isEmpty())
    }
}
