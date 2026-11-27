package cn.edu.haut.campus.core.database

import android.util.Log
import androidx.room.withTransaction
import cn.edu.haut.campus.core.database.CampusRepository.ExpectedMove
import cn.edu.haut.campus.core.database.CampusRepository.OccurrenceCommandResult
import cn.edu.haut.campus.core.database.CampusRepository.OccurrenceCommandStatus
import cn.edu.haut.campus.core.database.CampusRepository.OccurrenceRestoreResult
import cn.edu.haut.campus.core.database.CampusRepository.RestoreCommand
import cn.edu.haut.campus.core.database.CampusRepository.RestoreIntent
import cn.edu.haut.campus.core.model.OccurrenceOverride
import java.time.LocalDate

/** occurrence 精确恢复/撤销命令服务：身份校验、单事务提交、mutationToken 与通知语义保持原样。 */
internal class TimetableRestoreCommandService(
    private val environment: CampusRepositoryEnvironment,
    private val writeContext: TimetableOccurrenceWriteContext
) {
    private val database = environment.database
    private val dao = environment.dao
    private val profileId: String get() = environment.accountProfile.id

    private companion object {
        const val LOG_TAG = "CampusRepository"
    }

    private suspend fun upsertOccurrenceOverrideStamped(entity: OccurrenceOverrideEntity) {
        writeContext.upsertOccurrenceOverrideStamped(entity)
    }

    private suspend fun notifyScheduleOrPolicyChanged(reason: String) {
        writeContext.notifyScheduleOrPolicyChanged(reason)
    }

    /**
     * 「恢复暂停」命令：仅对**纯覆盖行**（暂停的调入目标/单日新增）取消 canceled 标记。
     * - 行不存在或已活跃 → [OccurrenceCommandStatus.NO_CHANGE]（双击/陈旧回调安全幂等）；
     * - termId 不符、行实为规则关联行 → [OccurrenceCommandStatus.CONFLICT]；
     * - [expectedMoveId] 为**双向断言**：传 null = 断言该行为独立非调课行（行当前带
     *   moveId 即陈旧冲突）；非 null = 要求与当前行 moveId 精确一致——任何不一致
     *   （例如点击后目标已被再次移走改挂新 moveId）一律 [OccurrenceCommandStatus.CONFLICT]，
     *   绝不放行去改动新调课的 TARGET；
     * - 仅置 canceled=false，保留 id/moveId/moveRole/教室/节次等全部身份字段，
     *   绝不删除任何 SOURCE/TARGET——撤销整次调课只能走 [undoMove]。
     */
    suspend fun unpauseOccurrence(
        termId: String,
        overrideId: String,
        expectedMoveId: String? = null
    ): OccurrenceCommandResult {
        val result = database.withTransaction {
            unpauseOccurrenceInTransaction(termId, overrideId, expectedMoveId)
        }
        if (result.status == OccurrenceCommandStatus.APPLIED) {
            notifyScheduleOrPolicyChanged("OCCURRENCE_RESTORED")
        }
        return result
    }

    /**
     * 「撤销调课」命令：原子删除同一学期内该 moveId 的全部 SOURCE+TARGET 行——
     * 整次调课撤销的**唯一**入口。无此关联组 → [OccurrenceCommandStatus.NO_CHANGE]；
     * 绝不按 courseId+日期模糊配对删除，严格 termId 隔离。
     */
    suspend fun undoMove(
        termId: String,
        moveId: String
    ): OccurrenceCommandResult {
        if (moveId.isBlank()) return OccurrenceCommandResult(OccurrenceCommandStatus.NO_CHANGE)
        val result = database.withTransaction {
            undoMoveInTransaction(termId, moveId)
        }
        if (result.status == OccurrenceCommandStatus.APPLIED) {
            notifyScheduleOrPolicyChanged("OCCURRENCE_RESTORED")
        }
        return result
    }

    /**
     * 「恢复规则停课」命令：恢复指定日期、给定规则集合下的已停课行。
     * - 普通停课行（无 moveId）：删除取消行；旧版「已调至 …」源顺带检出待核对候选目标；
     * - 命中调课 SOURCE 行：按 [expectedMoveId]（点击时捕获）做**双向断言**——
     *   传 null = 断言命中行全部为独立非调课行（行被再次调课改挂 moveId 即陈旧冲突）；
     *   非 null = 要求每条调课关联行的 moveId 精确一致。任一不符 →
     *   [OccurrenceCommandStatus.CONFLICT] 整体拒绝，绝不顺手撤销别次新调课；
     *   无 moveId 的普通停课行不与期望构成矛盾；
     * - 未命中任何行 → [OccurrenceCommandStatus.NO_CHANGE]，严格 termId 隔离。
     */
    suspend fun restoreRuleCancellation(
        termId: String,
        ruleIds: List<String>,
        date: LocalDate,
        expectedMoveId: String? = null
    ): OccurrenceCommandResult {
        val cleanRuleIds = ruleIds.distinct().filter { it.isNotBlank() }
        if (cleanRuleIds.isEmpty()) {
            return OccurrenceCommandResult(OccurrenceCommandStatus.NO_CHANGE)
        }
        val dateIso = date.toString()
        val result = database.withTransaction {
            val snapshot = dao.getOccurrenceOverridesSnapshot()
            val rows = snapshot.filter {
                it.termId == termId && it.dateIso == dateIso && it.canceled &&
                    it.courseRuleId != null && it.courseRuleId in cleanRuleIds
            }
            if (rows.isEmpty()) {
                OccurrenceCommandResult(OccurrenceCommandStatus.NO_CHANGE)
            } else {
                // 双向身份核验：expectedMoveId=null 断言「命中行均为独立非调课行」，
                // 命中任何 moveId 关联行即陈旧点击；非 null 要求每条调课关联行精确一致——
                // 行在点击后被再次调课改挂新 moveId 同样判 CONFLICT，绝不误撤新调课。
                // 无 moveId 的普通停课行不构成矛盾。空白串归一化为 null 后比较。
                val expectedMid = expectedMoveId?.takeIf { it.isNotBlank() }
                val moveIds = linkedSetOf<String>()
                var conflict = false
                for (row in rows) {
                    val mid = row.moveId?.takeIf { it.isNotBlank() }
                    if (mid != null && mid != expectedMid) {
                        conflict = true
                        break
                    }
                    if (mid != null) moveIds += mid
                }
                if (conflict) {
                    Log.w(
                        LOG_TAG,
                        "restoreRuleCancellation: " + cleanRuleIds + " on " + dateIso +
                            " 命中行 moveId 与期望 " + expectedMoveId + " 不符，拒绝陈旧恢复"
                    )
                    OccurrenceCommandResult(OccurrenceCommandStatus.CONFLICT)
                } else {
                    writeContext.occurrenceCommandInTransactionHook?.invoke("restoreRuleCancellation")
                    val restoredSourceIds = linkedSetOf<String>()
                    val deletedTargetIds = linkedSetOf<String>()
                    val restoredMoveIds = linkedSetOf<String>()
                    val legacySources = mutableListOf<OccurrenceOverrideEntity>()
                    for (row in rows) {
                        if (row.moveId.isNullOrBlank()) {
                            dao.deleteOccurrenceOverrideById(row.id)
                            restoredSourceIds += row.id
                            legacySources += row
                        }
                    }
                    for (mid in moveIds) {
                        val undo = undoMoveInTransaction(termId, mid)
                        restoredSourceIds += undo.restoredSourceOverrideIds
                        deletedTargetIds += undo.deletedTargetOverrideIds
                        if (undo.status == OccurrenceCommandStatus.APPLIED) restoredMoveIds += mid
                    }
                    val ambiguous = findLegacyAmbiguousTargets(snapshot, legacySources)
                    OccurrenceCommandResult(
                        status = OccurrenceCommandStatus.APPLIED,
                        restoredSourceOverrideIds = restoredSourceIds.toList(),
                        deletedTargetOverrideIds = deletedTargetIds.toList(),
                        restoredMoveIds = restoredMoveIds.toList(),
                        legacyAmbiguousTargets = ambiguous.map { it.toDomain() }
                    )
                }
            }
        }
        if (result.status == OccurrenceCommandStatus.APPLIED) {
            notifyScheduleOrPolicyChanged("OCCURRENCE_RESTORED")
        }
        return result
    }

    /**
     * 执行 [RestoreCommand]：唯一接受「点击时冻结的完整不可变选择」的原子恢复入口。
     *
     * 单一事务内：
     * 1. 账号/term/结构校验——跨账号、空 term、重复 id、空白令牌、意图形态不符
     *    （UNPAUSE 混入规则行/调课组、UNDO_MOVES 混入单选行、RESTORE_SELECTION 空选）
     *    一律整体拒绝，绝不写任何东西；
     * 2. 按选中 ID 精确读取（禁止 rule/date 扩展），逐条验
     *    mutationToken + termId + ruleId + dateIso + expectedMove + role，
     *    调课组额外验成员集合（id+token 全等）；
     * 3. 任一冲突 → 整组 [OccurrenceCommandStatus.CONFLICT] 零写；
     *    全部选中行/组消失 → [OccurrenceCommandStatus.NO_CHANGE]；
     *    部分消失 → CONFLICT（保守），绝不顺手恢复幸存行；
     * 4. 全部校验通过后一次性提交写入，事务外仅通知一次。
     *
     * 旧规则停课/调课行若 reason 为「已调至 …」且 moveId 缺失，恢复后仍检出
     * 旧版模糊调入目标交由用户核对，绝不自动猜测删除。
     */
    suspend fun executeRestoreCommand(command: RestoreCommand): OccurrenceCommandResult {
        // 形态校验：结构性非法输入整体拒绝（含重复 id / 空白字段 / 意图-选择形态不符）。
        val malformed = run {
            if (command.termId.isBlank() || command.accountProfileId.isBlank()) return@run true
            val rowIds = command.selectedRows.map { it.id }
            if (rowIds.size != rowIds.toSet().size) return@run true
            if (command.selectedRows.any { sel ->
                    sel.id.isBlank() || sel.mutationToken.isBlank() || sel.dateIso.isBlank() ||
                        (sel.expectedMove as? ExpectedMove.Move)?.moveId?.isBlank() == true
                }
            ) return@run true
            val rowIdSet = rowIds.toSet()
            val moveIds = command.selectedMoves.map { it.moveId }
            if (moveIds.size != moveIds.toSet().size) return@run true
            if (command.selectedMoves.any { sm ->
                    sm.moveId.isBlank() || sm.expectedMembers.isEmpty() ||
                        sm.expectedMembers.any { it.id.isBlank() || it.mutationToken.isBlank() } ||
                        sm.expectedMembers.map { it.id }.toSet().size != sm.expectedMembers.size
                }
            ) return@run true
            when (command.intent) {
                RestoreIntent.UNPAUSE ->
                    command.selectedMoves.isNotEmpty() ||
                        command.selectedRows.any { it.ruleId != null }
                RestoreIntent.UNDO_MOVES ->
                    command.selectedRows.isNotEmpty() || command.selectedMoves.isEmpty()
                RestoreIntent.RESTORE_SELECTION -> false
            }
        }
        if (malformed || command.accountProfileId != profileId) {
            Log.w(
                LOG_TAG,
                "executeRestoreCommand: " + command.commandId + " 账号/结构校验失败 " +
                    "(profile=" + command.accountProfileId + ",term=" + command.termId + ")，整体拒绝"
            )
            return OccurrenceCommandResult(OccurrenceCommandStatus.CONFLICT)
        }
        if (command.selectedRows.isEmpty() && command.selectedMoves.isEmpty()) {
            return OccurrenceCommandResult(OccurrenceCommandStatus.NO_CHANGE)
        }
        val result = database.withTransaction {
            // 旧版模糊调入目标检出需要删除前快照：先于任何写入截取
            val preWriteSnapshot = dao.getOccurrenceOverridesSnapshot()
            // 仅按选中 ID 精确读取——绝不按 rule/date 扩展选中范围
            val resolvedRows = LinkedHashMap<String, OccurrenceOverrideEntity>()
            var missingSelections = 0
            for (sel in command.selectedRows) {
                val row = dao.getOccurrenceOverrideById(sel.id)
                if (row == null) missingSelections++ else resolvedRows[sel.id] = row
            }
            val resolvedMoves = LinkedHashMap<String, List<OccurrenceOverrideEntity>>()
            for (sm in command.selectedMoves) {
                val members = dao.getOccurrenceOverridesForMove(command.termId, sm.moveId)
                if (members.isEmpty()) missingSelections++ else resolvedMoves[sm.moveId] = members
            }
            when {
                // 全部选中行/组消失：旧命令目标已不存在 → 安全幂等 NO_CHANGE
                resolvedRows.isEmpty() && resolvedMoves.isEmpty() ->
                    OccurrenceCommandResult(OccurrenceCommandStatus.NO_CHANGE)
                // 部分消失：保守整体拒绝，绝不只恢复幸存行
                missingSelections > 0 -> {
                    Log.w(
                        LOG_TAG,
                        "executeRestoreCommand " + command.commandId + ": " + missingSelections +
                            " 条选择已消失，整体拒绝"
                    )
                    OccurrenceCommandResult(OccurrenceCommandStatus.CONFLICT)
                }
                else -> {
                    var conflict = false
                    fun fail(reason: String) {
                        Log.w(LOG_TAG, "executeRestoreCommand " + command.commandId + ": " + reason)
                        conflict = true
                    }
                    for (sel in command.selectedRows) {
                        val row = resolvedRows.getValue(sel.id)
                        if (row.termId != command.termId) {
                            fail("行 " + sel.id + " 属于 " + row.termId + "，拒绝跨学期恢复")
                            break
                        }
                        if (row.mutationToken != sel.mutationToken) {
                            fail("行 " + sel.id + " 已被改写，旧令牌失效")
                            break
                        }
                        if (row.courseRuleId?.takeIf { it.isNotBlank() } !=
                            sel.ruleId?.takeIf { it.isNotBlank() }
                        ) {
                            fail("行 " + sel.id + " 规则关联与选择不符")
                            break
                        }
                        if (row.dateIso != sel.dateIso) {
                            fail("行 " + sel.id + " 日期与选择不符")
                            break
                        }
                        val expectedMid = (sel.expectedMove as? ExpectedMove.Move)
                            ?.moveId?.takeIf { it.isNotBlank() }
                        if (row.moveId?.takeIf { it.isNotBlank() } != expectedMid) {
                            fail("行 " + sel.id + " moveId=" + row.moveId + " 与期望不符")
                            break
                        }
                        if (row.moveRole?.takeIf { it.isNotBlank() } !=
                            sel.role?.takeIf { it.isNotBlank() }
                        ) {
                            fail("行 " + sel.id + " 调课角色与选择不符")
                            break
                        }
                        // 规则关联的调课行（SOURCE）只能随其调课组整体撤销——
                        // 组未随命令选中则孤儿化对端，保守拒绝；
                        // 纯覆盖行的 moveId（暂停的调入目标）不在此限——可单独解除暂停
                        if (row.courseRuleId != null && !row.moveId.isNullOrBlank() &&
                            resolvedMoves.keys.none { it == row.moveId }
                        ) {
                            fail("行 " + sel.id + " 属调课组 " + row.moveId + " 但组未被选中")
                            break
                        }
                    }
                    if (!conflict) {
                        for (sm in command.selectedMoves) {
                            val members = resolvedMoves.getValue(sm.moveId)
                            val expected = sm.expectedMembers
                                .map { it.id to it.mutationToken }.toSet()
                            val actual = members
                                .map { it.id to it.mutationToken }.toSet()
                            if (expected != actual) {
                                fail("调课组 " + sm.moveId + " 成员集合与点击时捕获的不符")
                                break
                            }
                        }
                    }
                    if (conflict) {
                        OccurrenceCommandResult(OccurrenceCommandStatus.CONFLICT)
                    } else {
                        writeContext.occurrenceCommandInTransactionHook?.invoke("restoreCommand")
                        val restoredSourceIds = linkedSetOf<String>()
                        val deletedTargetIds = linkedSetOf<String>()
                        val restoredMoveIds = linkedSetOf<String>()
                        val unpausedIds = linkedSetOf<String>()
                        val legacySources = mutableListOf<OccurrenceOverrideEntity>()
                        var wrote = false
                        for (sm in command.selectedMoves) {
                            for (row in resolvedMoves.getValue(sm.moveId)) {
                                dao.deleteOccurrenceOverrideById(row.id)
                                writeContext.occurrenceCommandWriteProgressHook?.invoke("restoreCommand", row.id)
                                if (row.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET) {
                                    deletedTargetIds += row.id
                                } else {
                                    restoredSourceIds += row.id
                                }
                                wrote = true
                            }
                            restoredMoveIds += sm.moveId
                        }
                        for (sel in command.selectedRows) {
                            val row = resolvedRows.getValue(sel.id)
                            if (row.courseRuleId != null) {
                                // 带 moveId 的规则行由上方调课组删除覆盖，跳过单独动作
                                if (!row.moveId.isNullOrBlank()) continue
                                dao.deleteOccurrenceOverrideById(row.id)
                                writeContext.occurrenceCommandWriteProgressHook?.invoke("restoreCommand", row.id)
                                restoredSourceIds += row.id
                                if (row.reason?.startsWith("已调至 ") == true) legacySources += row
                                wrote = true
                            } else if (row.canceled) {
                                // 纯覆盖行仅解除暂停：保留 id/moveId/moveRole 等全部身份字段，
                                // 统一写层盖印新令牌
                                upsertOccurrenceOverrideStamped(
                                    row.copy(canceled = false, reason = null)
                                )
                                writeContext.occurrenceCommandWriteProgressHook?.invoke("restoreCommand", row.id)
                                unpausedIds += row.id
                                wrote = true
                            }
                        }
                        val ambiguous = findLegacyAmbiguousTargets(preWriteSnapshot, legacySources)
                        OccurrenceCommandResult(
                            status = if (wrote) {
                                OccurrenceCommandStatus.APPLIED
                            } else {
                                OccurrenceCommandStatus.NO_CHANGE
                            },
                            unpausedOverrideIds = unpausedIds.toList(),
                            restoredSourceOverrideIds = restoredSourceIds.toList(),
                            deletedTargetOverrideIds = deletedTargetIds.toList(),
                            restoredMoveIds = restoredMoveIds.toList(),
                            legacyAmbiguousTargets = ambiguous.map { it.toDomain() }
                        )
                    }
                }
            }
        }
        if (result.status == OccurrenceCommandStatus.APPLIED) {
            notifyScheduleOrPolicyChanged("OCCURRENCE_RESTORED")
        }
        return result
    }

    /**
     * 事务内「恢复暂停」：按 id 精确读行，校验 termId 与点击时捕获的 [expectedMoveId]；
     * 仅取消 canceled 标记，保留全部身份字段，绝不触碰任何其他行。
     */
    private suspend fun unpauseOccurrenceInTransaction(
        termId: String,
        overrideId: String,
        expectedMoveId: String?
    ): OccurrenceCommandResult {
        val row = dao.getOccurrenceOverrideById(overrideId)
            ?: return OccurrenceCommandResult(OccurrenceCommandStatus.NO_CHANGE)
        if (row.termId != termId) {
            Log.w(
                LOG_TAG,
                "unpauseOccurrence: " + overrideId + " 属于 " + row.termId +
                    "，拒绝跨学期恢复 " + termId
            )
            return OccurrenceCommandResult(OccurrenceCommandStatus.CONFLICT)
        }
        if (row.courseRuleId != null) {
            // 规则关联行不是「恢复暂停」对象：规则停课走 restoreRuleCancellation，撤销调课走 undoMove。
            Log.w(
                LOG_TAG,
                "unpauseOccurrence: " + overrideId + " 是规则关联行(rule=" + row.courseRuleId +
                    ")，拒绝以恢复暂停入口处理"
            )
            return OccurrenceCommandResult(OccurrenceCommandStatus.CONFLICT)
        }
        // 双向断言：expectedMoveId=null 即「断言独立非调课行」——行当前带 moveId
        // （点击后被再次移走/改挂新调课）绝不放行，否则会把新调课的 TARGET 静默改状态；
        // 非 null 则要求精确一致。空白串一律归一化为 null 后比较。
        val expectedMid = expectedMoveId?.takeIf { it.isNotBlank() }
        val actualMid = row.moveId?.takeIf { it.isNotBlank() }
        if (actualMid != expectedMid) {
            Log.w(
                LOG_TAG,
                "unpauseOccurrence: " + overrideId + " moveId=" + row.moveId +
                    " 与点击时捕获的期望 " + expectedMoveId + " 不符，拒绝陈旧恢复"
            )
            return OccurrenceCommandResult(OccurrenceCommandStatus.CONFLICT)
        }
        if (!row.canceled) return OccurrenceCommandResult(OccurrenceCommandStatus.NO_CHANGE)
        writeContext.occurrenceCommandInTransactionHook?.invoke("unpauseOccurrence")
        // 仅取消暂停标记：moveId/moveRole/id/教室等身份字段原样保留；reason 清空交由引擎回退展示。
        upsertOccurrenceOverrideStamped(row.copy(canceled = false, reason = null))
        return OccurrenceCommandResult(
            status = OccurrenceCommandStatus.APPLIED,
            unpausedOverrideIds = listOf(row.id)
        )
    }

    /** 事务内整组撤销：删除同一学期该 moveId 全部 SOURCE+TARGET；无此组 → NO_CHANGE。 */
    private suspend fun undoMoveInTransaction(
        termId: String,
        moveId: String
    ): OccurrenceCommandResult {
        val rows = dao.getOccurrenceOverridesForMove(termId, moveId)
        if (rows.isEmpty()) return OccurrenceCommandResult(OccurrenceCommandStatus.NO_CHANGE)
        writeContext.occurrenceCommandInTransactionHook?.invoke("undoMove")
        val restoredSourceIds = mutableListOf<String>()
        val deletedTargetIds = mutableListOf<String>()
        for (row in rows) {
            dao.deleteOccurrenceOverrideById(row.id)
            writeContext.occurrenceCommandWriteProgressHook?.invoke("undoMove", row.id)
            if (row.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET) {
                deletedTargetIds += row.id
            } else {
                restoredSourceIds += row.id
            }
        }
        return OccurrenceCommandResult(
            status = OccurrenceCommandStatus.APPLIED,
            restoredSourceOverrideIds = restoredSourceIds,
            deletedTargetOverrideIds = deletedTargetIds,
            restoredMoveIds = listOf(moveId)
        )
    }

    /** 旧版模糊调课（moveId==null、reason 为「已调至 …」）的疑似调入目标检出：只列出不删除。 */
    private fun findLegacyAmbiguousTargets(
        snapshot: List<OccurrenceOverrideEntity>,
        restoredSources: List<OccurrenceOverrideEntity>
    ): List<OccurrenceOverrideEntity> {
        val ambiguous = linkedMapOf<String, OccurrenceOverrideEntity>()
        for (src in restoredSources) {
            if (src.reason?.startsWith("已调至 ") != true) continue
            val candidates = snapshot.filter { ov ->
                ov.courseRuleId == null &&
                    !ov.canceled &&
                    ov.moveId.isNullOrBlank() &&
                    ov.courseId == src.courseId &&
                    ov.reason == "由 " + src.dateIso + " 调入"
            }
            for (c in candidates) {
                ambiguous[c.id] = c
            }
        }
        return ambiguous.values.toList()
    }

    /**
     * 旧兼容适配器（R5 起保守 no-op）：规则+日期的模糊选择在执行时以当前版本「补齐」
     * 为旧选择本身就是 R5-D1 的缺陷根因——同 ID 删除重建的行会被旧命令误删。
     * 本入口不再执行任何写入：仅记日志并发出一次刷新提示，让陈旧调用方把界面
     * 刷新到最新快照后重新以 [executeRestoreCommand] 精确选择。返回值恒为未变更。
     */
    @Deprecated(
        "模糊选择已于 R5 废止：改用 executeRestoreCommand(RestoreCommand) 提交点击时冻结的完整不可变命令"
    )
    suspend fun restoreCanceledOccurrence(
        date: LocalDate,
        ruleId: String?,
        overrideId: String? = null,
        mergedRuleIds: List<String> = emptyList()
    ): OccurrenceRestoreResult {
        Log.w(
            LOG_TAG,
            "restoreCanceledOccurrence 已弃用且零写入(date=" + date.toString() +
                ",rule=" + ruleId + ",override=" + overrideId + ")——请改用 executeRestoreCommand"
        )
        // 刷新提示：让调用方/小组件按最新快照重绘，绝不按旧选择补做任何恢复
        notifyScheduleOrPolicyChanged("DEPRECATED_RESTORE_IGNORED")
        return OccurrenceRestoreResult()
    }

}
