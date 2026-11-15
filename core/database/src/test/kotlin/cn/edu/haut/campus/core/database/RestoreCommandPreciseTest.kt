package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.Term
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

/**
 * R5-D1：RestoreCommand 精确身份 + 原子批事务回归矩阵。
 * 覆盖 mutationToken 不可复用断言、NoMove/Move 双向身份、同 ID 删除重建、
 * 成员集合增删、跨账号/跨学期、写中失败整体回滚零通知等全部审查要求。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RestoreCommandPreciseTest {
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
        id = "c_r5_test",
        name = "合成精确命令",
        teacher = "王老师",
        colorArgb = 0xFF053968,
        source = CourseSource.MANUAL,
        userModified = true
    )

    @Before
    fun setUp() = runBlocking {
        fixture = RepairTestFixture.create(RuntimeEnvironment.getApplication(), "prof_r5_" + System.nanoTime())
        dao.upsertTerms(listOf(term.toEntity()))
        dao.upsertPeriodTimes(HautMockSeedData.defaultPeriodTimes.map { it.toEntity() })
        dao.upsertBuildings(HautMockSeedData.lianhuajieBuildings.map { it.toEntity() })
        dao.upsertBuildingAliases(HautMockSeedData.defaultBuildingAliases.map { it.toEntity() })
        dao.upsertCourse(course.toEntity())
    }

    @After
    fun tearDown() {
        repo.occurrenceCommandInTransactionHook = null
        repo.occurrenceCommandWriteProgressHook = null
        repo.onScheduleOrPolicyChangedListener = null
        fixture.close()
    }

    private fun rule(id: String, startPeriod: Int, endPeriod: Int, weekday: DayOfWeek = DayOfWeek.MONDAY) =
        CourseScheduleRule(
            id = id, courseId = course.id, termId = term.id, weekday = weekday,
            startPeriod = startPeriod, endPeriod = endPeriod,
            weeks = (1..16).toSet(), rawRoom = "合成教室101", userModified = true
        )

    private suspend fun overrides() = dao.getOccurrenceOverridesSnapshot()

    @Test
    fun `拒绝覆盖调课目标前不得删除另一规则的既有覆盖`() = runBlocking {
        val date = LocalDate.of(2026, 10, 14)
        val ordinary = OccurrenceOverride(
            id = "ov_ordinary", termId = term.id, courseId = course.id,
            courseRuleId = "r_other", date = date, canceled = true, reason = "普通停课"
        ).toEntity()
        val target = OccurrenceOverride(
            id = "ov_protected_target", termId = term.id, courseId = course.id,
            courseRuleId = null, date = date, canceled = false, reason = "调入",
            moveId = "move_protected", moveRole = OccurrenceOverride.MOVE_ROLE_TARGET
        ).toEntity()
        dao.upsertOccurrenceOverrides(listOf(ordinary, target))
        val before = overrides()
        var notifications = 0
        repo.onScheduleOrPolicyChangedListener = { notifications++ }
        // 陈旧对象复用目标 id，同时携带另一规则。所有拒绝条件必须先于任何删除。
        repo.saveOccurrenceOverride(target.toDomain().copy(
            courseRuleId = ordinary.courseRuleId, moveId = null, moveRole = null
        ))
        assertEquals(before, overrides())
        assertEquals(0, notifications)
    }

    private suspend fun resolveOcc(date: LocalDate, rules: List<CourseScheduleRule>): List<ClassOccurrence> =
        ScheduleEngine.resolveOccurrencesForDate(
            date = date, term = term, courses = listOf(course), rules = rules,
            periodTimes = HautMockSeedData.defaultPeriodTimes,
            occurrenceOverrides = dao.getOccurrenceOverridesSnapshot().map { it.toDomain() },
            buildings = HautMockSeedData.lianhuajieBuildings
        )

    private suspend fun occFor(rules: List<CourseScheduleRule>, date: LocalDate): ClassOccurrence =
        resolveOcc(date, rules).single()

    // ---------- 命令构造辅助 ----------

    private fun selRow(
        row: OccurrenceOverrideEntity,
        expectedMove: CampusRepository.ExpectedMove? = null,
        tokenOverride: String? = null
    ) = CampusRepository.SelectedOverride(
        id = row.id,
        mutationToken = tokenOverride ?: row.mutationToken,
        ruleId = row.courseRuleId,
        dateIso = row.dateIso,
        expectedMove = expectedMove ?: row.moveId?.takeIf { it.isNotBlank() }
            ?.let { CampusRepository.ExpectedMove.Move(it) }
            ?: CampusRepository.ExpectedMove.NoMove,
        role = row.moveRole
    )

    private suspend fun selMove(
        moveId: String,
        members: List<OccurrenceOverrideEntity>? = null
    ) = CampusRepository.SelectedMove(
        moveId = moveId,
        expectedMembers = (members ?: overrides().filter { it.termId == term.id && it.moveId == moveId })
            .map { CampusRepository.RowIdentity(it.id, it.mutationToken) }
    )

    private fun command(
        intent: CampusRepository.RestoreIntent,
        rows: List<CampusRepository.SelectedOverride> = emptyList(),
        moves: List<CampusRepository.SelectedMove> = emptyList(),
        termId: String = term.id,
        accountProfileId: String = repo.profileId
    ) = CampusRepository.RestoreCommand(
        commandId = "test-" + UUID.randomUUID().toString(),
        accountProfileId = accountProfileId,
        termId = termId,
        intent = intent,
        selectedRows = rows,
        selectedMoves = moves
    )

    private val sourceDate = LocalDate.of(2026, 10, 12)
    private val targetDate = LocalDate.of(2026, 10, 14)

    private suspend fun moveRule(
        r: CourseScheduleRule,
        target: LocalDate = targetDate,
        sp: Int = 1,
        ep: Int = 2,
        room: String = "教室X",
        occ: ClassOccurrence? = null
    ) {
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = occ ?: occFor(listOf(r), sourceDate), termId = term.id,
            targetDate = target, newStartPeriod = sp, newEndPeriod = ep, newRoom = room
        )
    }

    // ---------- 1. NoMove↔Move / MoveA→MoveB 方向冲突 ----------

    @Test
    fun `NoMove期望行被改挂调课后旧命令CONFLICT零写 新调课原样保留`() = runBlocking {
        val r1 = rule("r_dir_nm", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        val occBefore = occFor(listOf(r1), sourceDate)
        repo.cancelClassOccurrenceOnDate(occBefore, term.id, reason = "临时停课")
        val c1 = overrides().single { it.courseRuleId == r1.id }
        assertNull(c1.moveId)
        val staleCmd = command(
            CampusRepository.RestoreIntent.RESTORE_SELECTION,
            rows = listOf(selRow(c1, expectedMove = CampusRepository.ExpectedMove.NoMove))
        )

        // 点击后同一规则行被再次调课（同 id 重建为 SOURCE 挂新 moveId）
        moveRule(r1, occ = occBefore)
        val movedRow = overrides().single { it.courseRuleId == r1.id }
        assertNotEquals(c1.mutationToken, movedRow.mutationToken)
        assertTrue(!movedRow.moveId.isNullOrBlank())

        val res = repo.executeRestoreCommand(staleCmd)
        assertEquals(CampusRepository.OccurrenceCommandStatus.CONFLICT, res.status)
        // 新调课 SOURCE+TARGET 全部原样保留
        val rows = overrides()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.moveId == movedRow.moveId })
    }

    @Test
    fun `Move期望行退化为普通停课后旧命令CONFLICT零写 普通行原样保留`() = runBlocking {
        val r1 = rule("r_dir_mn", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        moveRule(r1)
        val moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val src = overrides().single { it.courseRuleId == r1.id }
        val staleCmd = command(
            CampusRepository.RestoreIntent.RESTORE_SELECTION,
            rows = listOf(selRow(src)),
            moves = listOf(selMove(moveId))
        )

        // 调课被撤销后同一规则行重建为无 moveId 普通停课
        repo.undoMove(term.id, moveId)
        repo.cancelClassOccurrenceOnDate(occFor(listOf(r1), sourceDate), term.id, reason = "再次停课")
        val plain = overrides().single { it.courseRuleId == r1.id }
        assertNull(plain.moveId)
        assertNotEquals(src.mutationToken, plain.mutationToken)

        val res = repo.executeRestoreCommand(staleCmd)
        assertEquals(CampusRepository.OccurrenceCommandStatus.CONFLICT, res.status)
        assertTrue(overrides().single { it.id == plain.id }.canceled)
    }

    @Test
    fun `MoveA期望恢复时行已改挂MoveB 旧命令CONFLICT零写`() = runBlocking {
        val r1 = rule("r_dir_ab", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        moveRule(r1)
        val moveA = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val staleCmd = command(
            CampusRepository.RestoreIntent.UNDO_MOVES,
            moves = listOf(selMove(moveA))
        )

        // 调入目标再次移动到第三日期：源改挂 moveB，旧目标删除重建
        val firstTarget = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        val movedIn = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == firstTarget.id }
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = movedIn, termId = term.id,
            targetDate = LocalDate.of(2026, 10, 16), newStartPeriod = 3, newEndPeriod = 4, newRoom = "教室C"
        )
        val moveB = overrides().mapNotNull { it.moveId }.toSet().single()
        assertNotEquals(moveA, moveB)

        val res = repo.executeRestoreCommand(staleCmd)
        // moveA 整组已消失 → 全部消失 NO_CHANGE（旧命令绝不可触碰 moveB）
        assertEquals(CampusRepository.OccurrenceCommandStatus.NO_CHANGE, res.status)
        val rows = overrides()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.moveId == moveB })
    }

    // ---------- 2/3. 同 ID 删除重建 / 相同内容重建 令牌区分 ----------

    @Test
    fun `普通停课删除后同ID重建C2 旧命令令牌不符CONFLICT不能误删`() = runBlocking {
        val r1 = rule("r_rebuild_same", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        repo.cancelClassOccurrenceOnDate(occFor(listOf(r1), sourceDate), term.id, reason = "临时停课")
        val c1 = overrides().single { it.courseRuleId == r1.id }
        val staleCmd = command(
            CampusRepository.RestoreIntent.RESTORE_SELECTION,
            rows = listOf(selRow(c1))
        )

        // 删除 C1 后经真实路径重建同 ID 的 C2（deterministic id ov_cancel_<date>_<rule>）
        repo.deleteOccurrenceOverride(c1.id)
        repo.cancelClassOccurrenceOnDate(occFor(listOf(r1), sourceDate), term.id, reason = "再次停课")
        val c2 = overrides().single { it.courseRuleId == r1.id }
        assertEquals(c1.id, c2.id)
        assertNotEquals("同 ID 重建必须产生新令牌", c1.mutationToken, c2.mutationToken)

        val res = repo.executeRestoreCommand(staleCmd)
        assertEquals(CampusRepository.OccurrenceCommandStatus.CONFLICT, res.status)
        assertTrue("C2 绝不可被旧命令误删", overrides().single { it.id == c2.id }.canceled)
    }

    @Test
    fun `普通停课删除后不同ID重建C2 旧命令不命中绝不误删`() = runBlocking {
        val r1 = rule("r_rebuild_diff", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        repo.cancelClassOccurrenceOnDate(occFor(listOf(r1), sourceDate), term.id, reason = "临时停课")
        val c1 = overrides().single { it.courseRuleId == r1.id }
        val staleCmd = command(
            CampusRepository.RestoreIntent.RESTORE_SELECTION,
            rows = listOf(selRow(c1))
        )

        repo.deleteOccurrenceOverride(c1.id)
        // 同 rule/date 不同 id 的新取消行
        dao.upsertOccurrenceOverride(
            OccurrenceOverride(
                id = "ov_recreated_new_id", termId = term.id, courseId = course.id,
                courseRuleId = r1.id, date = sourceDate, canceled = true, reason = "再次停课"
            ).toEntity()
        )

        val res = repo.executeRestoreCommand(staleCmd)
        assertEquals(CampusRepository.OccurrenceCommandStatus.NO_CHANGE, res.status)
        assertTrue(overrides().single { it.id == "ov_recreated_new_id" }.canceled)
    }

    @Test
    fun `相同内容同毫秒重建行令牌仍区分 写层盖印不可复用`() = runBlocking {
        val base = OccurrenceOverride(
            id = "ov_tok_a", termId = term.id, courseId = course.id,
            courseRuleId = null, date = sourceDate, canceled = true, reason = "单日新增"
        )
        val e1 = base.toEntity()
        val e2 = base.copy(id = "ov_tok_b").toEntity()
        val e3 = base.toEntity()
        // toEntity 未盖印对象每次生成独立令牌；三行字段相同（含同毫秒语义）令牌全不同
        assertNotEquals(e1.mutationToken, e2.mutationToken)
        assertNotEquals(e1.mutationToken, e3.mutationToken)
        assertTrue(e1.mutationToken.isNotBlank() && e2.mutationToken.isNotBlank())

        // 同 ID 删除重建：字段完全相同的两版行令牌仍不同
        val r1 = rule("r_tok_rebuild", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        repo.cancelClassOccurrenceOnDate(occFor(listOf(r1), sourceDate), term.id, reason = "临时停课")
        val c1 = overrides().single { it.courseRuleId == r1.id }
        repo.deleteOccurrenceOverride(c1.id)
        repo.cancelClassOccurrenceOnDate(occFor(listOf(r1), sourceDate), term.id, reason = "临时停课")
        val c2 = overrides().single { it.courseRuleId == r1.id }
        assertNotEquals(c1.mutationToken, c2.mutationToken)
    }

    // ---------- 4. unpause/undo 顺序与并发重复 ----------

    @Test
    fun `恢复暂停与整组撤销顺序与并发重复 无孤儿无误撤`() = runBlocking {
        val r1 = rule("r_seq_a", 1, 2)
        val r2 = rule("r_seq_b", 5, 6)
        dao.upsertRules(listOf(r1, r2).map { it.toEntity() })
        moveRule(r1)
        moveRule(r2, target = LocalDate.of(2026, 10, 15), sp = 5, ep = 6)
        val moveA = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val moveB = overrides().single { it.courseRuleId == r2.id }.moveId!!
        val targetA = overrides().single { it.moveId == moveA && it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        repo.cancelClassOccurrenceOnDate(
            resolveOcc(targetDate, listOf(r1)).single { it.overrideId == targetA.id },
            term.id, reason = "临时停课"
        )

        // 顺序：UNPAUSE 首次 APPLIED，重放（行身份已变）CONFLICT，绝不误撤整组
        val unpauseCmd = command(
            CampusRepository.RestoreIntent.UNPAUSE,
            rows = listOf(selRow(overrides().single { it.id == targetA.id }))
        )
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.APPLIED,
            repo.executeRestoreCommand(unpauseCmd).status
        )
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.executeRestoreCommand(unpauseCmd).status
        )
        assertEquals(4, overrides().size) // 两 move 全部行都在

        // 顺序：UNDO_MOVES 首次 APPLIED，重放 NO_CHANGE，moveB 不受影响
        val undoCmd = command(
            CampusRepository.RestoreIntent.UNDO_MOVES,
            moves = listOf(selMove(moveA))
        )
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.APPLIED,
            repo.executeRestoreCommand(undoCmd).status
        )
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
            repo.executeRestoreCommand(undoCmd).status
        )
        assertTrue(overrides().all { it.moveId == moveB })
        assertEquals(2, overrides().size)

        // 并发：两条相同 UNDO_MOVES 命令经注入闩锁竞争——至多一次 APPLIED、无孤儿行
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repo.occurrenceCommandInTransactionHook = { cmd ->
            if (cmd == "restoreCommand") {
                entered.complete(Unit)
                release.await()
            }
        }
        try {
            val frozen = undoCmdForMoveB()
            val first = async { repo.executeRestoreCommand(frozen) }
            entered.await()
            val second = async { repo.executeRestoreCommand(frozen) }
            release.complete(Unit)
            val statuses = listOf(first.await().status, second.await().status)
            assertEquals(1, statuses.count { it == CampusRepository.OccurrenceCommandStatus.APPLIED })
            assertEquals(1, statuses.count { it == CampusRepository.OccurrenceCommandStatus.NO_CHANGE })
        } finally {
            repo.occurrenceCommandInTransactionHook = null
        }
        assertTrue(overrides().isEmpty())
    }

    private suspend fun undoCmdForMoveB() = command(
        CampusRepository.RestoreIntent.UNDO_MOVES,
        moves = listOf(selMove(overrides().mapNotNull { it.moveId }.toSet().single()))
    )

    // ---------- 5. 混合两 move + 普通取消：任一冲突全组零写 / 全匹配一次提交 ----------

    @Test
    fun `混合两条调课与普通停课组 任一行版本冲突全组零写 全匹配一次提交一次通知`() = runBlocking {
        val r1 = rule("r_mix_a", 1, 2)
        val r2 = rule("r_mix_b", 5, 6)
        val r3 = rule("r_mix_c", 9, 10, weekday = DayOfWeek.WEDNESDAY)
        dao.upsertRules(listOf(r1, r2, r3).map { it.toEntity() })
        moveRule(r1)
        moveRule(r2, target = LocalDate.of(2026, 10, 15), sp = 5, ep = 6)
        repo.cancelClassOccurrenceOnDate(
            resolveOcc(LocalDate.of(2026, 10, 14), listOf(r3)).single { it.ruleId == r3.id },
            term.id, reason = "临时停课"
        )
        val moveA = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val moveB = overrides().single { it.courseRuleId == r2.id }.moveId!!
        val plain = overrides().single { it.courseRuleId == r3.id }
        val snapshot = overrides()
        assertEquals(5, snapshot.size)

        suspend fun buildCmd(corruptTokenFor: String? = null) = command(
            CampusRepository.RestoreIntent.RESTORE_SELECTION,
            rows = listOf(
                selRow(plain, tokenOverride = corruptTokenFor?.let { "forged-token" })
            ),
            moves = listOf(selMove(moveA), selMove(moveB))
        )

        // 任一选中身份不符（普通停课行令牌伪造）→ 全组 CONFLICT 零写
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.executeRestoreCommand(buildCmd(corruptTokenFor = plain.id)).status
        )
        assertEquals(5, overrides().size)

        // 普通停课行被先行删除（部分消失）→ 同样 CONFLICT 零写
        dao.deleteOccurrenceOverrideById(plain.id)
        val cmdPartial = command(
            CampusRepository.RestoreIntent.RESTORE_SELECTION,
            rows = listOf(selRow(plain)),
            moves = listOf(selMove(moveA), selMove(moveB))
        )
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.executeRestoreCommand(cmdPartial).status
        )
        assertEquals(4, overrides().size) // 两 move 完全未动

        // 全匹配：一次提交、一次通知
        val notified = mutableListOf<String>()
        repo.onScheduleOrPolicyChangedListener = { reason -> notified += reason }
        val allValid = command(
            CampusRepository.RestoreIntent.RESTORE_SELECTION,
            moves = listOf(selMove(moveA), selMove(moveB))
        )
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.APPLIED,
            repo.executeRestoreCommand(allValid).status
        )
        assertTrue(overrides().isEmpty())
        assertEquals(listOf("OCCURRENCE_RESTORED"), notified.filter { it == "OCCURRENCE_RESTORED" })
        repo.onScheduleOrPolicyChangedListener = null
    }

    // ---------- 6. 交错/编辑/链式移动后的旧回调 ----------

    @Test
    fun `恢复暂停命令在整组先被撤销后安全消失判 NO_CHANGE`() = runBlocking {
        val r1 = rule("r_ix_undofirst", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        moveRule(r1)
        val moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        repo.cancelClassOccurrenceOnDate(
            resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id },
            term.id, reason = "临时停课"
        )
        val staleUnpause = command(
            CampusRepository.RestoreIntent.UNPAUSE,
            rows = listOf(selRow(overrides().single { it.id == target.id }))
        )
        repo.undoMove(term.id, moveId)
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
            repo.executeRestoreCommand(staleUnpause).status
        )
        assertTrue(overrides().isEmpty())
    }

    @Test
    fun `暂停目标被编辑后旧恢复命令令牌不符判 CONFLICT 编辑原样保留`() = runBlocking {
        val r1 = rule("r_ix_edit", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        moveRule(r1)
        val target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        val targetOcc = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id }
        repo.cancelClassOccurrenceOnDate(targetOcc, term.id, reason = "临时停课")
        val stale = command(
            CampusRepository.RestoreIntent.UNPAUSE,
            rows = listOf(selRow(overrides().single { it.id == target.id }))
        )
        // 真实编辑路径改写目标：令牌换新
        repo.saveSingleDayOccurrenceEdit(targetOcc, term.id, 7, 8, "改后教室")
        assertNotEquals(target.mutationToken, overrides().single { it.id == target.id }.mutationToken)
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.executeRestoreCommand(stale).status
        )
        // 真实编辑路径合法解除了暂停并换了令牌：编辑字段与调课身份原样保留
        val row = overrides().single { it.id == target.id }
        assertFalse(row.canceled)
        assertEquals("改后教室", row.room)
        assertEquals(OccurrenceOverride.MOVE_ROLE_TARGET, row.moveRole)
    }

    @Test
    fun `链式移动后旧源行与旧目标行回调均判终态 不触碰新调课`() = runBlocking {
        val r1 = rule("r_ix_chain", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        moveRule(r1)
        val moveA = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val targetA = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        val staleMoveCmd = command(
            CampusRepository.RestoreIntent.UNDO_MOVES,
            moves = listOf(selMove(moveA))
        )
        val staleTargetCmd = command(
            CampusRepository.RestoreIntent.UNPAUSE,
            rows = listOf(selRow(targetA))
        )
        // 链式再移动：moveA 消亡，moveB 建立
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = resolveOcc(targetDate, listOf(r1)).single { it.overrideId == targetA.id },
            termId = term.id, targetDate = LocalDate.of(2026, 10, 16),
            newStartPeriod = 3, newEndPeriod = 4, newRoom = "教室B"
        )
        val moveB = overrides().mapNotNull { it.moveId }.toSet().single()
        assertNotEquals(moveA, moveB)
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
            repo.executeRestoreCommand(staleMoveCmd).status
        )
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
            repo.executeRestoreCommand(staleTargetCmd).status
        )
        assertEquals(2, overrides().size)
        assertTrue(overrides().all { it.moveId == moveB })
    }

    // ---------- 7. 跨账号/跨学期/不存在/成员增删/非法输入 ----------

    @Test
    fun `跨账号与跨学期与非法输入一律整体拒绝`() = runBlocking {
        val r1 = rule("r_iso_cmd", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        moveRule(r1)
        val moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        repo.cancelClassOccurrenceOnDate(
            resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id },
            term.id, reason = "临时停课"
        )
        val pausedTarget = overrides().single { it.id == target.id }
        val before = overrides()

        // 跨账号：accountProfileId 不符 → CONFLICT 零写
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.executeRestoreCommand(
                command(
                    CampusRepository.RestoreIntent.UNPAUSE,
                    rows = listOf(selRow(pausedTarget)),
                    accountProfileId = "other-account"
                )
            ).status
        )
        // 跨学期：命令 termId 与行所属不符 → CONFLICT 零写
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.executeRestoreCommand(
                command(
                    CampusRepository.RestoreIntent.UNPAUSE,
                    rows = listOf(selRow(pausedTarget)),
                    termId = "2026-2027-2"
                )
            ).status
        )
        // 不存在的选择 → 全部消失 NO_CHANGE
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.NO_CHANGE,
            repo.executeRestoreCommand(
                command(
                    CampusRepository.RestoreIntent.UNPAUSE,
                    rows = listOf(selRow(pausedTarget).copy(id = "ghost_row"))
                )
            ).status
        )
        // 成员增：旧成员集合（2 成员）命令在多出一行后判 CONFLICT
        val membersBeforeAdd = overrides().filter { it.moveId == moveId }
        dao.upsertOccurrenceOverride(
            OccurrenceOverride(
                id = "ov_extra_member", termId = term.id, courseId = course.id,
                courseRuleId = null, date = LocalDate.of(2026, 10, 16),
                canceled = false, reason = "额外行",
                moveId = moveId, moveRole = OccurrenceOverride.MOVE_ROLE_TARGET
            ).toEntity()
        )
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.executeRestoreCommand(
                command(
                    CampusRepository.RestoreIntent.UNDO_MOVES,
                    moves = listOf(selMove(moveId, members = membersBeforeAdd))
                )
            ).status
        )
        dao.deleteOccurrenceOverrideById("ov_extra_member")
        // 成员减：删掉一个成员后旧成员集合不符 → CONFLICT
        val twoMembers = overrides().filter { it.moveId == moveId }
        dao.deleteOccurrenceOverrideById(twoMembers.first { it.moveRole == OccurrenceOverride.MOVE_ROLE_SOURCE }.id)
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.executeRestoreCommand(
                command(
                    CampusRepository.RestoreIntent.UNDO_MOVES,
                    moves = listOf(selMove(moveId, members = twoMembers))
                )
            ).status
        )
        // 非法输入：重复行 id / 空白令牌 / 重复 moveId / 空调课成员
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.executeRestoreCommand(
                command(
                    CampusRepository.RestoreIntent.UNPAUSE,
                    rows = listOf(selRow(pausedTarget), selRow(pausedTarget))
                )
            ).status
        )
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.executeRestoreCommand(
                command(
                    CampusRepository.RestoreIntent.UNPAUSE,
                    rows = listOf(selRow(pausedTarget).copy(mutationToken = ""))
                )
            ).status
        )
        val mv = selMove(moveId)
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.executeRestoreCommand(
                command(CampusRepository.RestoreIntent.UNDO_MOVES, moves = listOf(mv, mv))
            ).status
        )
        // 意图形态不符：UNPAUSE 混入规则行 → CONFLICT（形态校验先于行读取）
        val ruleLinkedSel = CampusRepository.SelectedOverride(
            id = "ov_rule_linked", mutationToken = "tok-x", ruleId = r1.id,
            dateIso = sourceDate.toString(),
            expectedMove = CampusRepository.ExpectedMove.NoMove, role = null
        )
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.executeRestoreCommand(
                command(CampusRepository.RestoreIntent.UNPAUSE, rows = listOf(ruleLinkedSel))
            ).status
        )
        // 最终状态：除手动删除的一行外全部原样，无任何误写
        assertTrue(overrides().any { it.id == pausedTarget.id && it.canceled })
        assertTrue(before.size - 1 == overrides().size)
    }

    // ---------- 8. 旧普通取消/合并多源/旧模糊目标/部分源删除不回退 ----------

    @Test
    fun `旧版模糊调课经命令恢复仍只报待核对不自动删除`() = runBlocking {
        val r1 = rule("r_legacy_cmd", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        dao.upsertOccurrenceOverride(
            OccurrenceOverride(
                id = "ov_lsrc", termId = term.id, courseId = course.id,
                courseRuleId = r1.id, date = sourceDate, canceled = true, reason = "已调至 2026-10-14"
            ).toEntity()
        )
        dao.upsertOccurrenceOverride(
            OccurrenceOverride(
                id = "ov_ltgt", termId = term.id, courseId = course.id,
                courseRuleId = null, date = targetDate, startPeriod = 1, endPeriod = 2,
                room = "旧教室", canceled = false, reason = "由 2026-10-12 调入"
            ).toEntity()
        )
        val src = overrides().single { it.id == "ov_lsrc" }
        val res = repo.executeRestoreCommand(
            command(
                CampusRepository.RestoreIntent.RESTORE_SELECTION,
                rows = listOf(selRow(src))
            )
        )
        assertEquals(CampusRepository.OccurrenceCommandStatus.APPLIED, res.status)
        assertTrue(res.legacyReviewRequired)
        assertEquals(listOf("ov_ltgt"), res.legacyAmbiguousTargets.map { it.id })
        assertTrue(overrides().single { it.id == "ov_ltgt" }.let { !it.canceled })
    }

    @Test
    fun `合并多源调课整组撤销 部分源规则删除后旧成员集判CONFLICT新命令可恢复`() = runBlocking {
        val r1 = rule("r_part_a", 1, 2)
        val r2 = rule("r_part_b", 3, 4)
        dao.upsertRules(listOf(r1, r2).map { it.toEntity() })
        val mergedOcc = occFor(listOf(r1, r2), sourceDate)
        repo.rescheduleSingleOccurrenceCrossDate(
            occ = mergedOcc, termId = term.id, targetDate = targetDate,
            newStartPeriod = 1, newEndPeriod = 4, newRoom = "合成大教室"
        )
        val moveId = overrides().mapNotNull { it.moveId }.toSet().single()
        assertEquals(3, overrides().filter { it.moveId == moveId }.size)
        val staleCmd = command(
            CampusRepository.RestoreIntent.UNDO_MOVES,
            moves = listOf(selMove(moveId))
        )
        // 删除部分源规则：该源行随规则删除，目标+另一源存活（孤儿清理仅在删尽全部源时触发）
        repo.deleteCourseScheduleRules(listOf(r1.id))
        assertEquals(2, overrides().filter { it.moveId == moveId }.size)
        // 旧命令（3 成员快照）成员集合不符 → CONFLICT，零写
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.CONFLICT,
            repo.executeRestoreCommand(staleCmd).status
        )
        assertEquals(2, overrides().size)
        // 基于当前快照的新命令（2 成员）→ APPLIED 整组清除
        val fresh = command(
            CampusRepository.RestoreIntent.UNDO_MOVES,
            moves = listOf(selMove(moveId))
        )
        assertEquals(
            CampusRepository.OccurrenceCommandStatus.APPLIED,
            repo.executeRestoreCommand(fresh).status
        )
        assertTrue(overrides().isEmpty())
    }

    // ---------- 9. 写中失败注入：删一半即回滚 + 通知零次 ----------

    @Test
    fun `写中任一阶段注入失败整体回滚且通知零次`() = runBlocking {
        val r1 = rule("r_rollback", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        moveRule(r1)
        val moveId = overrides().single { it.courseRuleId == r1.id }.moveId!!
        val target = overrides().single { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET }
        val src = overrides().single { it.courseRuleId == r1.id }
        val before = overrides()

        val notified = mutableListOf<String>()
        repo.onScheduleOrPolicyChangedListener = { reason -> notified += reason }
        notified.clear() // 只统计注入命令产生的通知
        try {
            // 失败点一：第一条 SOURCE 删除完成后
            repo.occurrenceCommandWriteProgressHook = { _, rowId ->
                if (rowId == src.id) error("注入写中失败：首行删除后")
            }
            assertTrue(
                runCatching {
                    repo.executeRestoreCommand(
                        command(CampusRepository.RestoreIntent.UNDO_MOVES, moves = listOf(selMove(moveId)))
                    )
                }.isFailure
            )
            assertEquals(before, overrides())

            // 失败点二：TARGET 删除完成后（部分写入已发生）
            repo.occurrenceCommandWriteProgressHook = { _, rowId ->
                if (rowId == target.id) error("注入写中失败：目标删除后")
            }
            assertTrue(
                runCatching {
                    repo.executeRestoreCommand(
                        command(CampusRepository.RestoreIntent.UNDO_MOVES, moves = listOf(selMove(moveId)))
                    )
                }.isFailure
            )
            assertEquals(before, overrides())

            // 失败点三：解除暂停写入完成后
            repo.cancelClassOccurrenceOnDate(
                resolveOcc(targetDate, listOf(r1)).single { it.overrideId == target.id },
                term.id, reason = "临时停课"
            )
            val paused = overrides().single { it.id == target.id }
            notified.clear() // 上一步 cancelClassOccurrenceOnDate 的合法通知不计入
            repo.occurrenceCommandWriteProgressHook = { _, rowId ->
                if (rowId == target.id) error("注入写中失败：解除暂停后")
            }
            assertTrue(
                runCatching {
                    repo.executeRestoreCommand(
                        command(CampusRepository.RestoreIntent.UNPAUSE, rows = listOf(selRow(paused)))
                    )
                }.isFailure
            )
            assertTrue(overrides().single { it.id == target.id }.canceled)
            assertEquals(paused.mutationToken, overrides().single { it.id == target.id }.mutationToken)
        } finally {
            repo.occurrenceCommandWriteProgressHook = null
            repo.onScheduleOrPolicyChangedListener = null
        }
        assertTrue("任何写中失败都不允许发出通知", notified.isEmpty())
    }

    // ---------- 弃用入口保守 no-op ----------

    @Test
    fun `弃用适配器入口零写入仅刷新提示`() = runBlocking {
        val r1 = rule("r_dep", 1, 2)
        dao.upsertRules(listOf(r1).map { it.toEntity() })
        repo.cancelClassOccurrenceOnDate(occFor(listOf(r1), sourceDate), term.id, reason = "临时停课")
        val before = overrides()
        val notified = mutableListOf<String>()
        repo.onScheduleOrPolicyChangedListener = { reason -> notified += reason }
        @Suppress("DEPRECATION")
        val res = repo.restoreCanceledOccurrence(date = sourceDate, ruleId = r1.id, mergedRuleIds = listOf(r1.id))
        assertFalse(res.changed)
        assertEquals(before, overrides())
        assertEquals(listOf("DEPRECATED_RESTORE_IGNORED"), notified)
        repo.onScheduleOrPolicyChangedListener = null
    }
}
