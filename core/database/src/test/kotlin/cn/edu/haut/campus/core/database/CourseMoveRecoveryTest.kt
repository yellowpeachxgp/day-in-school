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

    private suspend fun resolveOcc(date: LocalDate, rules: List<CourseScheduleRule>): List<ClassOccurrence> =
        ScheduleEngine.resolveOccurrencesForDate(
            date = date,
            term = term,
            courses = listOf(course),
            rules = rules,
            periodTimes = HautMockSeedData.defaultPeriodTimes,
            occurrenceOverrides = dao.getOccurrenceOverridesSnapshot().map { it.toDomain() },
            buildings = HautMockSeedData.lianhuajieBuildings
        )

    private suspend fun canceledOcc(date: LocalDate, rules: List<CourseScheduleRule>): List<ClassOccurrence> =
        ScheduleEngine.resolveCanceledOccurrencesForDate(
            date = date,
            term = term,
            courses = listOf(course),
            rules = rules,
            periodTimes = HautMockSeedData.defaultPeriodTimes,
            occurrenceOverrides = dao.getOccurrenceOverridesSnapshot().map { it.toDomain() },
            buildings = HautMockSeedData.lianhuajieBuildings
        )

    private suspend fun occFor(rules: List<CourseScheduleRule>, date: LocalDate): ClassOccurrence {
        return resolveOcc(date, rules).single()
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
        val targetDate = LocalDate.of(2026, 10, 14)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = targetDate,
            newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室X"
        )
        val target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        // 用户随后经真实同日编辑路径修改调入课节次/教室：moveId 关联必须保留
        val targetOcc = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id }
        repo.saveSingleDayOccurrenceEdit(targetOcc, term.id, 5, 6, "改后教室", reason = "用户改教室")
        val edited = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        assertEquals(target.id, edited.id)
        assertEquals(target.moveId, edited.moveId)
        assertEquals("改后教室", edited.room)
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

    // ---------- Round-2 A1：moved-target 编辑/停课不得丢失 moveId ----------

    @Test
    fun `真实路径编辑调入目标保留 moveId 恢复源后不留任何残留`() = runBlocking {
        val r1 = rule("r_tedit", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val targetDate = LocalDate.of(2026, 10, 14)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = targetDate, newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室X"
        )
        val moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val targetBefore = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }

        // 引擎解析目标日 occurrence（overrideId 指向 TARGET 行、mergedRuleIds 为空）→ 真实同日编辑
        val targetOcc = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == targetBefore.id }
        assertTrue(targetOcc.mergedRuleIds.isEmpty())
        repo.saveSingleDayOccurrenceEdit(targetOcc, term.id, 7, 8, "改后教室")

        val edited = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        assertEquals(targetBefore.id, edited.id)
        assertEquals(moveId, edited.moveId)
        assertEquals(7, edited.startPeriod)
        assertEquals(8, edited.endPeriod)
        assertEquals("改后教室", edited.room)
        assertFalse(edited.canceled)

        // 恢复源：整次调课原子撤销，编辑后的目标行一并删除，不残留
        val result = repo.restoreCanceledOccurrence(
            date = sourceDate, ruleId = r1.id, mergedRuleIds = listOf(r1.id)
        )
        assertTrue(result.restoredMoveIds.contains(moveId))
        assertTrue(result.deletedTargetOverrideIds.contains(targetBefore.id))
        assertTrue(overrides().isEmpty())
        assertEquals(1, resolveOcc(sourceDate, listOf(r1)).size)
        assertTrue(resolveOcc(targetDate, listOf(r1)).isEmpty())
    }

    @Test
    fun `调入目标暂停-恢复暂停-撤销整次调课 三态互不串`() = runBlocking {
        val r1 = rule("r_pause", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val targetDate = LocalDate.of(2026, 10, 14)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = targetDate, newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室X"
        )
        val moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        val source = overrides().single { it.courseRuleId == r1.id }

        // 1) 暂停调入课：真实停课路径只置 canceled，保留 moveId/moveRole
        val targetOcc = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id }
        repo.cancelClassOccurrenceOnDate(targetOcc, term.id, reason = "临时停课")
        val paused = overrides().single { it.id == target.id }
        assertTrue(paused.canceled)
        assertEquals(moveId, paused.moveId)
        assertEquals(OccurrenceOverride.MOVE_ROLE_TARGET, paused.moveRole)
        // 引擎视角：目标日不再有课；停课清单出现该独立停课行
        assertTrue(resolveOcc(targetDate, listOf(r1)).isEmpty())
        assertEquals(1, canceledOcc(targetDate, listOf(r1)).size)

        // 2) 恢复暂停：仅取消标记，不撤销整次调课，源停课行保持
        repo.restoreCanceledOccurrence(
            date = targetDate, ruleId = null, overrideId = target.id, mergedRuleIds = emptyList()
        )
        val unpaused = overrides().single { it.id == target.id }
        assertFalse(unpaused.canceled)
        assertEquals(moveId, unpaused.moveId)
        assertTrue(overrides().any { it.id == source.id && it.canceled })
        assertEquals(1, resolveOcc(targetDate, listOf(r1)).size)

        // 3) 撤销整次调课：经 SOURCE 行恢复，SOURCE+TARGET 全清
        repo.restoreCanceledOccurrence(
            date = sourceDate, ruleId = r1.id, mergedRuleIds = listOf(r1.id)
        )
        assertTrue(overrides().isEmpty())
    }

    @Test
    fun `未暂停的调入目标被恢复视同撤销整次调课`() = runBlocking {
        val r1 = rule("r_tgt_restore", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val targetDate = LocalDate.of(2026, 10, 14)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = targetDate, newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室X"
        )
        val moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        // 直接对仍活跃的 TARGET 行触发恢复入口：原子撤销整次调课，而非只删目标留下源停课
        val result = repo.restoreCanceledOccurrence(
            date = targetDate, ruleId = null, overrideId = target.id
        )
        assertTrue(result.restoredMoveIds.contains(moveId))
        assertTrue(result.deletedTargetOverrideIds.contains(target.id))
        assertTrue(overrides().isEmpty())
        assertEquals(1, resolveOcc(sourceDate, listOf(r1)).size)
    }

    @Test
    fun `编辑后的调入目标再次跨日移动收敛为最新 moveId`() = runBlocking {
        val r1 = rule("r_edit_chain", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val firstTarget = LocalDate.of(2026, 10, 14)
        val secondTarget = LocalDate.of(2026, 10, 16)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = firstTarget, newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室A"
        )
        val moveA = overrides().single { it.courseRuleId == r1.id }.moveId!!

        // 先经真实路径编辑目标，再对编辑后的 occurrence 二次跨日移动
        val firstTargetOcc = resolveOcc(firstTarget, listOf(r1)).single { it.overrideId != null }
        repo.saveSingleDayOccurrenceEdit(firstTargetOcc, term.id, 3, 4, "教室A改")
        val editedTarget = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        assertEquals(moveA, editedTarget.moveId)

        val editedOcc = resolveOcc(firstTarget, listOf(r1)).single { it.overrideId == editedTarget.id }
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = editedOcc, termId = term.id,
            targetDate = secondTarget, newStartPeriod = 5, newEndPeriod = 6, newRoom = "教室B"
        )
        val rows = overrides()
        val moveIds = rows.mapNotNull { it.moveId }.toSet()
        assertEquals(1, moveIds.size)
        val moveB = moveIds.single()
        assertNotEquals(moveA, moveB)
        assertTrue(rows.none { it.moveId == moveA })
        assertTrue(rows.any { it.courseRuleId == r1.id && it.moveRole == OccurrenceOverride.MOVE_ROLE_SOURCE && it.moveId == moveB })
        assertTrue(rows.any { it.dateIso == secondTarget.toString() && it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET && it.moveId == moveB })

        repo.restoreCanceledOccurrence(date = sourceDate, ruleId = r1.id, mergedRuleIds = listOf(r1.id))
        assertTrue(overrides().isEmpty())
    }

    @Test
    fun `同课程同日两次独立调课编辑目标A后恢复A不干扰B`() = runBlocking {
        val rA = rule("r_indep_a", 1, 2)
        val rB = rule("r_indep_b", 5, 6)
        dao.upsertRules(listOf(rA, rB).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(rA), sourceDate), termId = term.id,
            targetDate = LocalDate.of(2026, 10, 14),
            newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室A"
        )
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(rB), sourceDate), termId = term.id,
            targetDate = LocalDate.of(2026, 10, 15),
            newStartPeriod = 5, newEndPeriod = 6, newRoom = "教室B"
        )
        val moveA = overrides().single { it.courseRuleId == rA.id }.moveId!!
        val moveB = overrides().single { it.courseRuleId == rB.id }.moveId!!
        val targetA = overrides().single { it.moveId == moveA && it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        val targetB = overrides().single { it.moveId == moveB && it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }

        // 编辑目标 A（真实路径）
        val targetAOcc = resolveOcc(LocalDate.of(2026, 10, 14), listOf(rA)).single { it.overrideId == targetA.id }
        repo.saveSingleDayOccurrenceEdit(targetAOcc, term.id, 9, 10, "教室A2")
        assertEquals(moveA, overrides().single { it.id == targetA.id }.moveId)

        // 恢复 A：B 的源/目标完好
        repo.restoreCanceledOccurrence(date = sourceDate, ruleId = rA.id, mergedRuleIds = listOf(rA.id))
        val remaining = overrides()
        assertTrue(remaining.none { it.moveId == moveA })
        assertTrue(remaining.any { it.id == targetB.id && it.moveId == moveB })
        assertTrue(remaining.any { it.courseRuleId == rB.id && it.moveId == moveB && it.canceled })
        assertEquals(2, remaining.size)
    }

    @Test
    fun `合并多源调课删除部分源规则目标存活 删尽全部源后孤儿目标被清理`() = runBlocking {
        val r1 = rule("r_ms1", 1, 2)
        val r2 = rule("r_ms2", 3, 4)
        dao.upsertRules(listOf(r1, r2).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val merged = occFor(listOf(r1, r2), sourceDate)
        assertEquals(listOf("r_ms1", "r_ms2"), merged.mergedRuleIds)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = merged, termId = term.id,
            targetDate = LocalDate.of(2026, 10, 14),
            newStartPeriod = 1, newEndPeriod = 4, newRoom = "大教室"
        )
        val moveId = overrides().mapNotNull { it.moveId }.toSet().single()

        // 仅删除 r1：r2 的 SOURCE 仍在 → TARGET 不是孤儿，保留
        repo.deleteCourseScheduleRules(listOf(r1.id), contextDate = sourceDate)
        var remaining = overrides()
        assertTrue(remaining.any { it.courseRuleId == r2.id && it.moveRole == OccurrenceOverride.MOVE_ROLE_SOURCE })
        assertTrue(remaining.any { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET && it.moveId == moveId })

        // 再删除 r2：最后一条 SOURCE 消失 → 孤儿 TARGET 同事务清理
        repo.deleteCourseScheduleRules(listOf(r2.id), contextDate = sourceDate)
        remaining = overrides()
        assertTrue(remaining.none { it.moveId == moveId })
    }

    @Test
    fun `过期目标 id 的编辑与停课均为安全 no-op 绝不静默另建行`() = runBlocking {
        val r1 = rule("r_stale", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val ghost = ClassOccurrence(
            occurrenceId = "ghost",
            courseId = course.id,
            ruleId = null,
            overrideId = "ov_expired_missing",
            courseName = course.name,
            colorArgb = course.colorArgb,
            date = LocalDate.of(2026, 10, 14),
            teachingWeek = 7,
            weekday = DayOfWeek.WEDNESDAY,
            startPeriod = 1,
            endPeriod = 2,
            startTime = java.time.LocalTime.of(8, 30),
            endTime = java.time.LocalTime.of(10, 5),
            rawRoom = "教室",
            mergedRuleIds = emptyList()
        )
        // 编辑：目标行不存在 → 不落新行
        repo.saveSingleDayOccurrenceEdit(ghost, term.id, 3, 4, "新教室")
        assertTrue(overrides().isEmpty())
        // 停课：同样不落新行
        repo.cancelClassOccurrenceOnDate(ghost, term.id, reason = "临时停课")
        assertTrue(overrides().isEmpty())
        // 通用删除入口：安全 no-op
        repo.deleteOccurrenceOverride("ov_expired_missing")
        assertTrue(overrides().isEmpty())
    }

    @Test
    fun `无 overrideId 的独立单日新增仍走兜底建行`() = runBlocking {
        val standalone = ClassOccurrence(
            occurrenceId = "standalone",
            courseId = course.id,
            ruleId = null,
            overrideId = null,
            courseName = course.name,
            colorArgb = course.colorArgb,
            date = LocalDate.of(2026, 10, 14),
            teachingWeek = 7,
            weekday = DayOfWeek.WEDNESDAY,
            startPeriod = 1,
            endPeriod = 2,
            startTime = java.time.LocalTime.of(8, 30),
            endTime = java.time.LocalTime.of(10, 5),
            rawRoom = "合成教室101",
            mergedRuleIds = emptyList()
        )
        repo.saveSingleDayOccurrenceEdit(standalone, term.id, 3, 4, "新教室")
        val rows = overrides()
        assertEquals(1, rows.size)
        assertTrue(rows.single().id.startsWith("ov_mod_2026-10-14_"))
        assertNull(rows.single().moveId)
        assertEquals(3, rows.single().startPeriod)
    }

    @Test
    fun `重复编辑与重复停课幂等 目标行 id 与 moveId 稳定`() = runBlocking {
        val r1 = rule("r_repeat", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val targetDate = LocalDate.of(2026, 10, 14)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = targetDate, newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室X"
        )
        val moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }

        // 双击/重复编辑：行数不变，id 与 moveId 稳定
        val occ1 = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id }
        repo.saveSingleDayOccurrenceEdit(occ1, term.id, 3, 4, "教室Y")
        val occ2 = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id }
        repo.saveSingleDayOccurrenceEdit(occ2, term.id, 5, 6, "教室Z")
        var rows = overrides()
        assertEquals(2, rows.size)
        val targetAfterEdits = rows.single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        assertEquals(target.id, targetAfterEdits.id)
        assertEquals(moveId, targetAfterEdits.moveId)
        assertEquals("教室Z", targetAfterEdits.room)

        // 重复停课：仍只有一行 canceled TARGET
        repo.cancelClassOccurrenceOnDate(occ2, term.id)
        repo.cancelClassOccurrenceOnDate(occ2, term.id)
        rows = overrides()
        assertEquals(2, rows.size)
        assertTrue(rows.single { it.id == target.id }.canceled)
        assertEquals(moveId, rows.single { it.id == target.id }.moveId)
    }

    @Test
    fun `目标编辑事务中途失败整体回滚保留 moveId 关联`() = runBlocking {
        val r1 = rule("r_tx_edit", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val targetDate = LocalDate.of(2026, 10, 14)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = targetDate, newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室X"
        )
        val before = overrides()
        val target = before.single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        val targetOcc = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id }

        val crashed = runCatching {
            fixture.database.withTransaction {
                repo.saveSingleDayOccurrenceEdit(targetOcc, term.id, 9, 10, "不回滚教室")
                error("模拟编辑事务中途失败")
            }
        }
        assertTrue(crashed.isFailure)
        assertEquals(before, overrides())
        assertTrue(overrides().single { it.id == target.id }.moveId == target.moveId)
    }

    @Test
    fun `跨学期写入目标行被拒绝 跨账号互不可见`() = runBlocking {
        val r1 = rule("r_xterm", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val otherTerm = term.copy(id = "2026-2027-2", termNumber = 2, current = false)
        dao.upsertTerms(listOf(otherTerm.toEntity()))
        val sourceDate = LocalDate.of(2026, 10, 12)
        val targetDate = LocalDate.of(2026, 10, 14)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = targetDate, newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室X"
        )
        val target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        val targetOcc = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id }

        // 跨学期写：行属于 term.id，用 otherTerm.id 发起编辑/停课 → 行原样保留
        repo.saveSingleDayOccurrenceEdit(targetOcc, otherTerm.id, 9, 10, "越权教室")
        repo.cancelClassOccurrenceOnDate(targetOcc, otherTerm.id)
        val after = overrides().single { it.id == target.id }
        assertEquals(target, after)

        // 跨账号：另一独立账号仓库对同 id 行的读/删不可见，各自隔离
        val fixtureB = RepairTestFixture.create(RuntimeEnvironment.getApplication(), "prof_move_b_${System.nanoTime()}")
        try {
            fixtureB.dao.upsertTerms(listOf(term.toEntity()))
            fixtureB.dao.upsertPeriodTimes(HautMockSeedData.defaultPeriodTimes.map { it.toEntity() })
            fixtureB.dao.upsertBuildings(HautMockSeedData.lianhuajieBuildings.map { it.toEntity() })
            fixtureB.dao.upsertBuildingAliases(HautMockSeedData.defaultBuildingAliases.map { it.toEntity() })
            fixtureB.dao.upsertCourse(course.toEntity())
            assertTrue(fixtureB.dao.getOccurrenceOverridesSnapshot().isEmpty())
            fixtureB.repository.deleteOccurrenceOverride(target.id) // 账号 B 中无此行 → 安全 no-op
            assertEquals(target, overrides().single { it.id == target.id })
        } finally {
            fixtureB.close()
        }
    }

    @Test
    fun `通用入口一律拒绝触碰调课关联行`() = runBlocking {
        val r1 = rule("r_guard", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val targetDate = LocalDate.of(2026, 10, 14)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = targetDate, newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室X"
        )
        val moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        val source = overrides().single { it.courseRuleId == r1.id }
        val before = overrides()

        // 通用单条删除：SOURCE 与 TARGET 均拒绝
        repo.deleteOccurrenceOverride(target.id)
        repo.deleteOccurrenceOverride(source.id)
        assertEquals(before, overrides())

        // 通用同日重置：无论作用于 TARGET occurrence 还是已停课源 occurrence 均拒绝
        val targetOcc = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id }
        repo.resetSingleDayOccurrenceEdit(targetOcc)
        assertEquals(before, overrides())
        val canceledSourceOcc = canceledOcc(sourceDate, listOf(r1)).single { it.ruleId == r1.id }
        repo.resetSingleDayOccurrenceEdit(canceledSourceOcc)
        assertEquals(before, overrides())

        // 通用覆盖写入：同 id 丢失 moveId 的 REPLACE 拒绝；命中调课规则日期的删除也拒绝
        repo.saveOccurrenceOverride(
            target.toDomain().copy(room = "绕过改", moveId = null, moveRole = null)
        )
        assertEquals(before, overrides())
        repo.saveOccurrenceOverride(
            OccurrenceOverride(
                id = "ov_bypass_edit", termId = term.id, courseId = course.id,
                courseRuleId = r1.id, date = sourceDate, startPeriod = 3, endPeriod = 4,
                room = "绕过改", canceled = false, reason = "单次调课"
            )
        )
        assertEquals(before, overrides())

        // 陈旧 occurrence（有规则但当日已存在调课行）触发的编辑/停课也整体拒绝
        val staleRuleOcc = ClassOccurrence(
            occurrenceId = "stale_rule",
            courseId = course.id,
            ruleId = r1.id,
            overrideId = null,
            courseName = course.name,
            colorArgb = course.colorArgb,
            date = sourceDate,
            teachingWeek = 7,
            weekday = DayOfWeek.MONDAY,
            startPeriod = 1,
            endPeriod = 2,
            startTime = java.time.LocalTime.of(8, 30),
            endTime = java.time.LocalTime.of(10, 5),
            rawRoom = "合成教室101",
            mergedRuleIds = listOf(r1.id)
        )
        repo.saveSingleDayOccurrenceEdit(staleRuleOcc, term.id, 3, 4, "绕过改")
        repo.cancelClassOccurrenceOnDate(staleRuleOcc, term.id)
        assertEquals(before, overrides())
        assertTrue(overrides().all { it.moveId == moveId })
    }
}
