package cn.edu.haut.campus.core.database

import androidx.room.withTransaction
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.Term
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
@Suppress("DEPRECATION")
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
    fun `调入目标的重复与活跃恢复一律安全 撤销整次调课只能经 undoMove`() = runBlocking {
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
        val source = overrides().single { it.courseRuleId == r1.id }

        // 未暂停的 TARGET 行被恢复：旧适配器与显式命令均为安全空操作，绝不视同撤销整次调课
        val staleActive = repo.restoreCanceledOccurrence(
            date = targetDate, ruleId = null, overrideId = target.id
        )
        assertFalse(staleActive.changed)
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
            repo.unpauseOccurrence(term.id, target.id, expectedMoveId = moveId).status
        )
        assertEquals(2, overrides().size)
        assertTrue(overrides().single { it.id == source.id }.canceled)

        // 暂停目标 → 恢复暂停 APPLIED；同参重复调用 NO_CHANGE，moveId 与源停课行保持不动
        val targetOcc = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id }
        repo.cancelClassOccurrenceOnDate(targetOcc, term.id, reason = "临时停课")
        val first = repo.unpauseOccurrence(term.id, target.id, expectedMoveId = moveId)
        assertEquals(CampusRepository.OccurrenceCommandStatus.APPLIED, first.status)
        assertEquals(listOf(target.id), first.unpausedOverrideIds)
        assertFalse(overrides().single { it.id == target.id }.canceled)
        val second = repo.unpauseOccurrence(term.id, target.id, expectedMoveId = moveId)
        assertEquals(CampusRepository.OccurrenceCommandStatus.NO_CHANGE, second.status)
        val adapterRepeat = repo.restoreCanceledOccurrence(
            date = targetDate, ruleId = null, overrideId = target.id
        )
        assertFalse(adapterRepeat.changed)
        assertTrue(overrides().single { it.id == source.id }.canceled)
        assertFalse(overrides().single { it.id == target.id }.canceled)
        assertEquals(moveId, overrides().single { it.id == target.id }.moveId)
        assertEquals(OccurrenceOverride.MOVE_ROLE_TARGET, overrides().single { it.id == target.id }.moveRole)

        // 显式撤销整次调课：SOURCE+TARGET 全清，源时段恢复排课
        val undo = repo.undoMove(term.id, moveId)
        assertEquals(CampusRepository.OccurrenceCommandStatus.APPLIED, undo.status)
        assertTrue(undo.restoredMoveIds.contains(moveId))
        assertTrue(undo.deletedTargetOverrideIds.contains(target.id))
        assertTrue(undo.restoredSourceOverrideIds.contains(source.id))
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

    // ---------- Round-3 A1：显式意图命令（unpauseOccurrence / undoMove / restoreRuleCancellation） ----------

    @Test
    fun `并发恢复暂停经注入闩锁仅一次 APPLIED`() = runBlocking {
        val r1 = rule("r_conc_unpause", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val targetDate = LocalDate.of(2026, 10, 14)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = targetDate, newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室X"
        )
        val moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        val targetOcc = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id }
        repo.cancelClassOccurrenceOnDate(targetOcc, term.id, reason = "临时停课")

        // 注入闩锁：第一个并发调用在事务内等待放行，第二个调用排队于 Room 事务执行器
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repo.occurrenceCommandInTransactionHook = { command ->
            if (command == "unpauseOccurrence") {
                entered.complete(Unit)
                release.await()
            }
        }
        try {
            val first = async { repo.unpauseOccurrence(term.id, target.id, expectedMoveId = moveId) }
            entered.await() // 第一个调用已在事务内持锁
            val second = async { repo.unpauseOccurrence(term.id, target.id, expectedMoveId = moveId) }
            release.complete(Unit)
            val statuses = listOf(first.await().status, second.await().status)
            assertEquals(1, statuses.count { it == CampusRepository.OccurrenceCommandStatus.APPLIED })
            assertEquals(1, statuses.count { it == CampusRepository.OccurrenceCommandStatus.NO_CHANGE })
        } finally {
            repo.occurrenceCommandInTransactionHook = null
        }
        val row = overrides().single { it.id == target.id }
        assertFalse(row.canceled)
        assertEquals(moveId, row.moveId)
        assertEquals(OccurrenceOverride.MOVE_ROLE_TARGET, row.moveRole)
        assertTrue(overrides().any { it.courseRuleId == r1.id && it.canceled })
    }

    @Test
    fun `恢复暂停后的陈旧回调与仓库重建均不再撤销整次调课`() = runBlocking {
        val r1 = rule("r_stale_cb", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val targetDate = LocalDate.of(2026, 10, 14)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = targetDate, newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室X"
        )
        val moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        val targetOcc = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id }
        repo.cancelClassOccurrenceOnDate(targetOcc, term.id, reason = "临时停课")

        assertEquals(
            CampusRepository.OccurrenceCommandStatus.APPLIED,
            repo.unpauseOccurrence(term.id, target.id, expectedMoveId = moveId).status
        )
        // 迟到的陈旧回调（旧适配器入口）：行已活跃 → 安全空操作，绝不落入整组撤销
        val stale = repo.restoreCanceledOccurrence(
            date = targetDate, ruleId = null, overrideId = target.id
        )
        assertFalse(stale.changed)
        assertEquals(2, overrides().size)

        // 模拟关闭/重开：同一库上的新仓库实例，陈旧回调依旧安全
        val repoReopened = CampusRepository(
            appContext = RuntimeEnvironment.getApplication(),
            database = fixture.database,
            preferences = fixture.dataStore,
            accountProfile = fixture.profile
        )
        val staleAfterReopen = repoReopened.restoreCanceledOccurrence(
            date = targetDate, ruleId = null, overrideId = target.id
        )
        assertFalse(staleAfterReopen.changed)
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
            repoReopened.unpauseOccurrence(term.id, target.id, expectedMoveId = moveId).status
        )
        val rows = overrides()
        assertEquals(2, rows.size)
        assertTrue(rows.single { it.courseRuleId == r1.id }.canceled)
        assertFalse(rows.single { it.id == target.id }.canceled)
        assertEquals(moveId, rows.single { it.id == target.id }.moveId)
    }

    @Test
    fun `编辑后的调入目标经规则恢复仍整组清除 独立调课不受影响`() = runBlocking {
        val rA = rule("r_ea", 1, 2)
        val rB = rule("r_eb", 5, 6)
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

        // 真实编辑路径改 B 的教室/节次：moveId 关联保留
        val targetAOcc = resolveOcc(LocalDate.of(2026, 10, 14), listOf(rA))
            .single { it.overrideId == targetA.id }
        repo.saveSingleDayOccurrenceEdit(targetAOcc, term.id, 9, 10, "改后教室")
        assertEquals(moveA, overrides().single { it.id == targetA.id }.moveId)

        // 按点击时捕获的 moveId 恢复源：编辑后的目标行随整组删除
        val result = repo.restoreRuleCancellation(
            term.id, listOf(rA.id), sourceDate, expectedMoveId = moveA
        )
        assertEquals(CampusRepository.OccurrenceCommandStatus.APPLIED, result.status)
        assertTrue(result.deletedTargetOverrideIds.contains(targetA.id))
        assertTrue(result.restoredMoveIds.contains(moveA))
        val remaining = overrides()
        assertTrue(remaining.none { it.moveId == moveA })
        assertTrue(remaining.any { it.id == targetB.id && it.moveId == moveB })
        assertTrue(remaining.any { it.courseRuleId == rB.id && it.moveId == moveB && it.canceled })
        assertEquals(2, remaining.size)
        assertEquals(1, resolveOcc(sourceDate, listOf(rA)).size)
    }

    @Test
    fun `调入目标再次移动后旧回调判 CONFLICT 且绝不触碰新调课`() = runBlocking {
        val r1 = rule("r_stale_chain", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val firstTarget = LocalDate.of(2026, 10, 14)
        val secondTarget = LocalDate.of(2026, 10, 16)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = firstTarget, newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室A"
        )
        val moveA = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val targetA = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }

        // 再次跨日移动调入目标：旧目标行删除重建，源行整体改挂新 moveId
        val movedInOcc = resolveOcc(firstTarget, listOf(r1)).single { it.overrideId != null }
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = movedInOcc, termId = term.id,
            targetDate = secondTarget, newStartPeriod = 3, newEndPeriod = 4, newRoom = "教室B"
        )
        val moveB = overrides().mapNotNull { it.moveId }.toSet().single()
        assertNotEquals(moveA, moveB)
        val targetB = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }

        // 旧 B 行 id 的恢复暂停：行已被删除重建 → NO_CHANGE
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
            repo.unpauseOccurrence(term.id, targetA.id, expectedMoveId = moveA).status
        )
        // 旧 moveId 校验新目标行：关联不符 → CONFLICT
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.unpauseOccurrence(term.id, targetB.id, expectedMoveId = moveA).status
        )
        // 旧 moveId 的规则恢复：源行已改挂 moveB → CONFLICT，整组不动
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.restoreRuleCancellation(term.id, listOf(r1.id), sourceDate, expectedMoveId = moveA).status
        )
        val rows = overrides()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.moveId == moveB })
        assertTrue(rows.none { it.moveId == moveA })
        assertEquals(1, resolveOcc(secondTarget, listOf(r1)).size)
        assertTrue(resolveOcc(sourceDate, listOf(r1)).isEmpty())
    }

    @Test
    fun `恢复暂停与撤销调课并发只允许整体撤销结局 不产生孤儿行`() = runBlocking {
        val r1 = rule("r_conc_mix", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = LocalDate.of(2026, 10, 14),
            newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室X"
        )
        var moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        var target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        repo.cancelClassOccurrenceOnDate(
            resolveOcc(LocalDate.of(2026, 10, 14), listOf(r1)).single { it.overrideId == target.id },
            term.id, reason = "临时停课"
        )

        // 顺序一：恢复暂停先进入事务（闩锁持住），撤销调课排队 → 先 APPLIED 再整体撤销
        val entered1 = CompletableDeferred<Unit>()
        val release1 = CompletableDeferred<Unit>()
        repo.occurrenceCommandInTransactionHook = { command ->
            if (command == "unpauseOccurrence") {
                entered1.complete(Unit)
                release1.await()
            }
        }
        try {
            val unpause = async { repo.unpauseOccurrence(term.id, target.id, expectedMoveId = moveId) }
            entered1.await()
            val undo = async { repo.undoMove(term.id, moveId) }
            release1.complete(Unit)
            assertEquals(CampusRepository.OccurrenceCommandStatus.APPLIED, unpause.await().status)
            assertEquals(CampusRepository.OccurrenceCommandStatus.APPLIED, undo.await().status)
        } finally {
            repo.occurrenceCommandInTransactionHook = null
        }
        assertTrue(overrides().isEmpty()) // 既无孤儿 TARGET，也无复活行

        // 顺序二：撤销调课先进入事务，恢复暂停排队 → 整组删除后恢复暂停安全 NO_CHANGE
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = LocalDate.of(2026, 10, 15),
            newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室Y"
        )
        moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        repo.cancelClassOccurrenceOnDate(
            resolveOcc(LocalDate.of(2026, 10, 15), listOf(r1)).single { it.overrideId == target.id },
            term.id, reason = "临时停课"
        )
        val entered2 = CompletableDeferred<Unit>()
        val release2 = CompletableDeferred<Unit>()
        repo.occurrenceCommandInTransactionHook = { command ->
            if (command == "undoMove") {
                entered2.complete(Unit)
                release2.await()
            }
        }
        try {
            val undo = async { repo.undoMove(term.id, moveId) }
            entered2.await()
            val unpause = async { repo.unpauseOccurrence(term.id, target.id, expectedMoveId = moveId) }
            release2.complete(Unit)
            assertEquals(CampusRepository.OccurrenceCommandStatus.APPLIED, undo.await().status)
            assertEquals(CampusRepository.OccurrenceCommandStatus.NO_CHANGE, unpause.await().status)
        } finally {
            repo.occurrenceCommandInTransactionHook = null
        }
        assertTrue(overrides().isEmpty())
    }

    @Test
    fun `undoMove 重复与并发至多一次生效 他学期同名关联组不受影响`() = runBlocking {
        val r1 = rule("r_undo_idem", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val otherTerm = term.copy(id = "2026-2027-2", termNumber = 2, current = false)
        dao.upsertTerms(listOf(otherTerm.toEntity()))
        val sourceDate = LocalDate.of(2026, 10, 12)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = LocalDate.of(2026, 10, 14),
            newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室X"
        )
        val moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        // 另一学期同 moveId 的关联组：撤销只允许命中本学期的组
        dao.upsertOccurrenceOverride(
            OccurrenceOverride(
                id = "ov_xterm_src", termId = otherTerm.id, courseId = course.id,
                courseRuleId = "r_x", date = sourceDate, canceled = true,
                reason = "已调至 2026-10-14", moveId = moveId,
                moveRole = OccurrenceOverride.MOVE_ROLE_SOURCE
            ).toEntity()
        )
        dao.upsertOccurrenceOverride(
            OccurrenceOverride(
                id = "ov_xterm_tgt", termId = otherTerm.id, courseId = course.id,
                courseRuleId = null, date = LocalDate.of(2026, 10, 14), canceled = false,
                reason = "由 2026-10-12 调入", moveId = moveId,
                moveRole = OccurrenceOverride.MOVE_ROLE_TARGET
            ).toEntity()
        )

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repo.occurrenceCommandInTransactionHook = { command ->
            if (command == "undoMove") {
                entered.complete(Unit)
                release.await()
            }
        }
        try {
            val first = async { repo.undoMove(term.id, moveId) }
            entered.await()
            val second = async { repo.undoMove(term.id, moveId) }
            release.complete(Unit)
            val statuses = listOf(first.await().status, second.await().status)
            assertEquals(1, statuses.count { it == CampusRepository.OccurrenceCommandStatus.APPLIED })
            assertEquals(1, statuses.count { it == CampusRepository.OccurrenceCommandStatus.NO_CHANGE })
        } finally {
            repo.occurrenceCommandInTransactionHook = null
        }
        // 第三次顺序调用：依旧 NO_CHANGE，无残留
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
            repo.undoMove(term.id, moveId).status
        )
        val remaining = overrides()
        assertTrue(remaining.none { it.termId == term.id })
        assertEquals(2, remaining.count { it.termId == otherTerm.id && it.moveId == moveId })
    }

    @Test
    fun `跨学期与跨账号的恢复命令互不可见`() = runBlocking {
        val r1 = rule("r_iso", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val otherTerm = term.copy(id = "2026-2027-2", termNumber = 2, current = false)
        dao.upsertTerms(listOf(otherTerm.toEntity()))
        val sourceDate = LocalDate.of(2026, 10, 12)
        val targetDate = LocalDate.of(2026, 10, 14)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = targetDate, newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室X"
        )
        val moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        val targetOcc = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id }
        repo.cancelClassOccurrenceOnDate(targetOcc, term.id, reason = "临时停课")

        // 跨学期：termId 不符 → 显式 CONFLICT / 不命中 NO_CHANGE，绝不写入
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.unpauseOccurrence(otherTerm.id, target.id, expectedMoveId = moveId).status
        )
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
            repo.restoreRuleCancellation(
                otherTerm.id, listOf(r1.id), sourceDate, expectedMoveId = moveId
            ).status
        )
        assertTrue(overrides().single { it.id == target.id }.canceled)

        // 跨账号：另一账号仓库看不到该行 → 全部 NO_CHANGE
        val fixtureB = RepairTestFixture.create(
            RuntimeEnvironment.getApplication(), "prof_move_iso_${System.nanoTime()}"
        )
        try {
            assertEquals(
                CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
                fixtureB.repository.unpauseOccurrence(term.id, target.id, expectedMoveId = moveId).status
            )
            assertEquals(
                CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
                fixtureB.repository.undoMove(term.id, moveId).status
            )
            assertEquals(
                CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
                fixtureB.repository.restoreRuleCancellation(
                    term.id, listOf(r1.id), sourceDate, expectedMoveId = moveId
                ).status
            )
        } finally {
            fixtureB.close()
        }
        val rows = overrides()
        assertEquals(2, rows.size)
        assertTrue(rows.single { it.id == target.id }.canceled)
        assertTrue(rows.single { it.courseRuleId == r1.id }.canceled)
    }

    @Test
    fun `普通停课与合并多源恢复经 restoreRuleCancellation 语义保持`() = runBlocking {
        val r1 = rule("r_plain9", 1, 2)
        val r2 = rule("r_plain9b", 3, 4)
        dao.upsertRules(listOf(r1, r2).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)

        // 普通停课：按规则+日期精确恢复，重复恢复 NO_CHANGE
        repo.cancelClassOccurrenceOnDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id, reason = "临时停课"
        )
        val plain = repo.restoreRuleCancellation(term.id, listOf(r1.id), sourceDate)
        assertEquals(CampusRepository.OccurrenceCommandStatus.APPLIED, plain.status)
        assertTrue(plain.restoredSourceOverrideIds.isNotEmpty())
        assertEquals(1, resolveOcc(sourceDate, listOf(r1)).size)
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
            repo.restoreRuleCancellation(term.id, listOf(r1.id), sourceDate).status
        )

        // 合并多源一目标调课：一次调用原子撤销整组，重复恢复安全
        val mergedOcc = occFor(listOf(r1, r2), sourceDate)
        assertEquals(listOf("r_plain9", "r_plain9b"), mergedOcc.mergedRuleIds)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = mergedOcc, termId = term.id,
            targetDate = LocalDate.of(2026, 10, 14),
            newStartPeriod = 1, newEndPeriod = 4, newRoom = "合成大教室"
        )
        val moveId = overrides().mapNotNull { it.moveId }.toSet().single()
        val merged = repo.restoreRuleCancellation(
            term.id, listOf(r1.id, r2.id), sourceDate, expectedMoveId = moveId
        )
        assertEquals(CampusRepository.OccurrenceCommandStatus.APPLIED, merged.status)
        assertTrue(merged.restoredMoveIds.contains(moveId))
        assertEquals(3, merged.restoredSourceOverrideIds.size + merged.deletedTargetOverrideIds.size)
        assertTrue(overrides().isEmpty())
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
            repo.restoreRuleCancellation(
                term.id, listOf(r1.id, r2.id), sourceDate, expectedMoveId = moveId
            ).status
        )
    }

    @Test
    fun `恢复命令事务内失败整体回滚且不发出通知`() = runBlocking {
        val r1 = rule("r_tx_fail", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val targetDate = LocalDate.of(2026, 10, 14)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = targetDate, newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室X"
        )
        val moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        val targetOcc = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id }
        repo.cancelClassOccurrenceOnDate(targetOcc, term.id, reason = "临时停课")

        val notified = mutableListOf<String>()
        repo.onScheduleOrPolicyChangedListener = { reason -> notified += reason }
        val before = overrides()
        try {
            // 三条命令分别在事务内注入失败：全部原子回滚，且绝不发出课表变更通知
            repo.occurrenceCommandInTransactionHook = { command ->
                if (command == "restoreRuleCancellation") error("注入事务失败")
            }
            assertTrue(
                runCatching {
                    repo.restoreRuleCancellation(
                        term.id, listOf(r1.id), sourceDate, expectedMoveId = moveId
                    )
                }.isFailure
            )
            repo.occurrenceCommandInTransactionHook = { command ->
                if (command == "undoMove") error("注入事务失败")
            }
            assertTrue(runCatching { repo.undoMove(term.id, moveId) }.isFailure)
            repo.occurrenceCommandInTransactionHook = { command ->
                if (command == "unpauseOccurrence") error("注入事务失败")
            }
            assertTrue(
                runCatching {
                    repo.unpauseOccurrence(term.id, target.id, expectedMoveId = moveId)
                }.isFailure
            )
        } finally {
            repo.occurrenceCommandInTransactionHook = null
            repo.onScheduleOrPolicyChangedListener = null
        }
        assertEquals(before, overrides())
        assertTrue(overrides().single { it.id == target.id }.canceled)
        assertTrue(notified.none { it == "OCCURRENCE_RESTORED" })
    }

    // ---------- Round-4 A1：expectedMoveId 双向断言（null = 断言独立非调课行） ----------

    @Test
    fun `调入目标再移动后 null 期望恢复判 CONFLICT 新调课目标保持暂停`() = runBlocking {
        val r1 = rule("r_r4_null_tgt", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val firstTarget = LocalDate.of(2026, 10, 14)
        val secondTarget = LocalDate.of(2026, 10, 16)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id,
            targetDate = firstTarget, newStartPeriod = 1, newEndPeriod = 2, newRoom = "教室A"
        )
        val targetA = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }

        // 调入目标再次移动 B→C：旧目标删除重建，源行整体改挂新 moveId
        val movedInOcc = resolveOcc(firstTarget, listOf(r1)).single { it.overrideId == targetA.id }
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = movedInOcc, termId = term.id,
            targetDate = secondTarget, newStartPeriod = 3, newEndPeriod = 4, newRoom = "教室B"
        )
        val moveB = overrides().mapNotNull { it.moveId }.toSet().single()
        val targetB = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        repo.cancelClassOccurrenceOnDate(
            resolveOcc(secondTarget, listOf(r1)).single { it.overrideId == targetB.id },
            term.id, reason = "临时停课"
        )

        // 迟到的泛化恢复携带 null 期望（旧实现跳过 moveId 校验直接复活新调课 TARGET）：
        // 双向断言下 null = 断言独立非调课行 → 行带 moveB 即 CONFLICT，C 目标保持暂停
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.unpauseOccurrence(term.id, targetB.id, expectedMoveId = null).status
        )
        val row = overrides().single { it.id == targetB.id }
        assertTrue(row.canceled)
        assertEquals(moveB, row.moveId)
        assertEquals(OccurrenceOverride.MOVE_ROLE_TARGET, row.moveRole)
        // 错误 moveId 同样 CONFLICT；点击时正确捕获 moveB 才能恢复
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.unpauseOccurrence(term.id, targetB.id, expectedMoveId = "stale_move_id").status
        )
        assertTrue(overrides().single { it.id == targetB.id }.canceled)
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.APPLIED,
            repo.unpauseOccurrence(term.id, targetB.id, expectedMoveId = moveB).status
        )
        assertFalse(overrides().single { it.id == targetB.id }.canceled)
    }

    @Test
    fun `独立暂停行 null 期望恢复仍应用且重复调用安全幂等`() = runBlocking {
        dao.upsertOccurrenceOverride(
            OccurrenceOverride(
                id = "ov_r4_standalone",
                termId = term.id,
                courseId = course.id,
                courseRuleId = null,
                date = LocalDate.of(2026, 10, 12),
                startPeriod = 1,
                endPeriod = 2,
                canceled = true,
                reason = "单日新增暂停"
            ).toEntity()
        )
        // null 期望命中真正的独立非调课行：正常恢复
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.APPLIED,
            repo.unpauseOccurrence(term.id, "ov_r4_standalone", expectedMoveId = null).status
        )
        assertFalse(overrides().single { it.id == "ov_r4_standalone" }.canceled)
        // 已活跃行重复恢复：安全幂等 NO_CHANGE
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
            repo.unpauseOccurrence(term.id, "ov_r4_standalone", expectedMoveId = null).status
        )
    }

    @Test
    fun `停课规则行点击后改挂调课 null 期望规则恢复判 CONFLICT 新调课不动`() = runBlocking {
        val r1 = rule("r_r4_rule", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)
        val sourceOcc = occFor(listOf(r1), sourceDate)

        // 普通停课：规则行 moveId=null —— 点击时捕获 expectedMoveId=null
        repo.cancelClassOccurrenceOnDate(sourceOcc, term.id, reason = "临时停课")
        assertNull(overrides().single { it.courseRuleId == r1.id }.moveId)

        // 点击后该行被再次调课：同 id 规则行改挂新 moveId，C 日期生成新 TARGET
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = sourceOcc, termId = term.id,
            targetDate = LocalDate.of(2026, 10, 16),
            newStartPeriod = 3, newEndPeriod = 4, newRoom = "教室C"
        )
        val moveB = overrides().mapNotNull { it.moveId }.toSet().single()
        val source = overrides().single { it.courseRuleId == r1.id }
        val targetB = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }

        // 旧 null 期望的规则恢复：命中行已带 moveId → CONFLICT，新调课 TARGET 原样保留
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.restoreRuleCancellation(term.id, listOf(r1.id), sourceDate, expectedMoveId = null).status
        )
        assertTrue(overrides().single { it.id == source.id }.canceled)
        assertEquals(moveB, overrides().single { it.id == source.id }.moveId)
        assertTrue(overrides().any { it.id == targetB.id && !it.canceled })

        // 错误 moveId 同样 CONFLICT；正确捕获 moveB 才原子撤销整组
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.restoreRuleCancellation(term.id, listOf(r1.id), sourceDate, expectedMoveId = "stale_move_id").status
        )
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.APPLIED,
            repo.restoreRuleCancellation(term.id, listOf(r1.id), sourceDate, expectedMoveId = moveB).status
        )
        assertTrue(overrides().isEmpty())
        assertEquals(1, resolveOcc(sourceDate, listOf(r1)).size)
    }

    @Test
    fun `普通停课 null 期望规则恢复保持应用 混合组含调课时逐组撤销`() = runBlocking {
        val r1 = rule("r_r4_plain", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val sourceDate = LocalDate.of(2026, 10, 12)

        // 纯普通停课组（无任何 moveId）：null 期望断言成立 → 正常恢复
        repo.cancelClassOccurrenceOnDate(
            occ = occFor(listOf(r1), sourceDate), termId = term.id, reason = "临时停课"
        )
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.APPLIED,
            repo.restoreRuleCancellation(term.id, listOf(r1.id), sourceDate, expectedMoveId = null).status
        )
        assertEquals(1, resolveOcc(sourceDate, listOf(r1)).size)
    }
}
