package cn.edu.haut.campus.core.database

import android.util.Log
import androidx.room.withTransaction
import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.model.OccurrenceOverride
import java.time.LocalDate
import java.util.UUID

/** occurrence 单日覆盖编辑、停课与跨日调课写入口；恢复命令由独立内部服务执行。 */
internal class TimetableOccurrenceStore(
    private val environment: CampusRepositoryEnvironment,
    private val writeContext: TimetableOccurrenceWriteContext
) {
    private val database = environment.database
    private val dao = environment.dao

    private companion object {
        const val LOG_TAG = "CampusRepository"
    }

    private suspend fun upsertOccurrenceOverrideStamped(entity: OccurrenceOverrideEntity) {
        writeContext.upsertOccurrenceOverrideStamped(entity)
    }

    private suspend fun notifyScheduleOrPolicyChanged(reason: String) {
        writeContext.notifyScheduleOrPolicyChanged(reason)
    }

    suspend fun saveOccurrenceOverride(override: OccurrenceOverride) {
        val resolvedBuildingId = if (override.buildingId == null && !override.room.isNullOrBlank()) {
            val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
            val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
            val resolved = ClassroomBuildingResolver.resolve(override.room!!, buildings, aliases)
            if (resolved.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED) {
                resolved.building?.id
            } else null
        } else {
            override.buildingId
        }
        val normalized = override.copy(buildingId = resolvedBuildingId)
        val wrote = database.withTransaction {
            val snapshot = dao.getOccurrenceOverridesSnapshot()
            // 先完成全部拒绝条件，再执行任何删除；事务内提前返回不会自动回滚此前写入。
            val existingById = snapshot.firstOrNull { it.id == normalized.id }
            if (existingById != null && !existingById.moveId.isNullOrBlank() &&
                normalized.moveId.isNullOrBlank()
            ) {
                Log.w(LOG_TAG, "saveOccurrenceOverride: 调课关联行不能被普通覆盖替换，已拒绝写入")
                return@withTransaction false
            }
            val ruleId = normalized.courseRuleId
            if (!ruleId.isNullOrBlank()) {
                // 将要被「规则+日期」清除的行若带 moveId，是普通覆盖写入不能触碰的调课关联行：
                // 删掉它们会把 TARGET 变成孤儿复活一节课，必须整体拒绝（专用撤销入口除外）。
                val moveLinked = snapshot.any {
                    it.courseRuleId == ruleId && it.dateIso == normalized.date.toString() &&
                        !it.moveId.isNullOrBlank()
                }
                if (moveLinked) {
                    Log.w(
                        LOG_TAG,
                        "saveOccurrenceOverride: rule $ruleId on ${normalized.date} 存在调课关联行，拒绝覆盖写入；请走调课恢复入口"
                    )
                    return@withTransaction false
                }
                dao.deleteOccurrenceOverridesForRuleOnDate(ruleId, normalized.date.toString())
            }
            upsertOccurrenceOverrideStamped(normalized.toEntity())
            true
        }
        if (wrote) notifyScheduleOrPolicyChanged("OCCURRENCE_OVERRIDE_SAVED")
    }

    /**
     * 对单次课程（含由多条相邻规则合并而成的 1-4 节连堂课）执行当日改教室/改节次：
     * - 主规则写入修改后的起止节次与教室；
     * - 其余被合并的子规则在同一天写入 `MERGED_INTO:<primaryRuleId>` 吸收抑制记录，防止原 3-4 节残留在当日课表造成重叠。
     */
    suspend fun saveSingleDayOccurrenceEdit(
        occ: cn.edu.haut.campus.core.model.ClassOccurrence,
        termId: String,
        newStartPeriod: Int,
        newEndPeriod: Int,
        newRoom: String,
        reason: String = "单次调课"
    ) {
        val cleanRoom = newRoom.trim().ifBlank { occ.rawRoom }
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val resolved = ClassroomBuildingResolver.resolve(cleanRoom, buildings, aliases)
        val verifiedBuildingId = if (resolved.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED) {
            resolved.building?.id
        } else if (cleanRoom == occ.rawRoom) {
            occ.buildingId
        } else null

        val sp = newStartPeriod.coerceIn(1, 12)
        val ep = newEndPeriod.coerceIn(sp, 12)
        val explicitRuleIds = occ.mergedRuleIds.distinct().filter { it.isNotBlank() }

        database.withTransaction {
            val daySnapshot = dao.getOccurrenceOverridesSnapshot()
            val companionIds = if (explicitRuleIds.isNotEmpty()) {
                daySnapshot
                    .filter { ov ->
                        ov.dateIso == occ.date.toString() &&
                            !ov.courseRuleId.isNullOrBlank() &&
                            explicitRuleIds.any { rId -> ov.reason == "MERGED_INTO:$rId" }
                    }
                    .mapNotNull { it.courseRuleId }
            } else emptyList()
            val ruleIds = (explicitRuleIds + companionIds).distinct()
            if (ruleIds.isNotEmpty()) {
                // 编辑会先按「规则+日期」删除旧覆盖：若旧行是调课 SOURCE 等 moveId 关联行，
                // 直接删除会孤儿化 TARGET（凭空复活一节课）。整体拒绝，调课请走专用撤销入口。
                val moveLinked = daySnapshot.any { ov ->
                    ov.dateIso == occ.date.toString() && !ov.moveId.isNullOrBlank() &&
                        ov.courseRuleId != null && ov.courseRuleId in ruleIds
                }
                if (moveLinked) {
                    Log.w(
                        LOG_TAG,
                        "saveSingleDayOccurrenceEdit: $ruleIds on ${occ.date} 命中调课关联行，拒绝同日编辑覆盖"
                    )
                    return@withTransaction
                }
                val primaryRuleId = ruleIds.first()
                dao.deleteOccurrenceOverridesForRuleOnDate(primaryRuleId, occ.date.toString())
                upsertOccurrenceOverrideStamped(
                    OccurrenceOverride(
                        id = occ.overrideId ?: "ov_mod_${occ.date}_$primaryRuleId",
                        termId = termId,
                        courseId = occ.courseId,
                        courseRuleId = primaryRuleId,
                        date = occ.date,
                        startPeriod = sp,
                        endPeriod = ep,
                        room = cleanRoom,
                        buildingId = verifiedBuildingId,
                        canceled = false,
                        reason = reason
                    ).toEntity()
                )
                for (extraRuleId in ruleIds.drop(1)) {
                    dao.deleteOccurrenceOverridesForRuleOnDate(extraRuleId, occ.date.toString())
                    upsertOccurrenceOverrideStamped(
                        OccurrenceOverride(
                            id = "ov_merged_sup_${occ.date}_$extraRuleId",
                            termId = termId,
                            courseId = occ.courseId,
                            courseRuleId = extraRuleId,
                            date = occ.date,
                            canceled = true,
                            reason = "MERGED_INTO:$primaryRuleId"
                        ).toEntity()
                    )
                }
            } else {
                // 纯 TARGET/单日新增覆盖行：按 overrideId+termId 读取既有行，仅更新可编辑字段，
                // 保留 moveId/moveRole/稳定 id——否则 Room REPLACE 会抹掉调课关联，恢复源后目标残留。
                val existing = occ.overrideId?.let { oid ->
                    daySnapshot.firstOrNull { it.id == oid }
                }
                when {
                    existing != null && existing.termId != termId -> {
                        // 跨学期行不属于本次编辑范围：显式冲突，拒绝写入。
                        Log.w(
                            LOG_TAG,
                            "saveSingleDayOccurrenceEdit: override ${existing.id} 属于 ${existing.termId}，拒绝跨学期写入 $termId"
                        )
                    }
                    existing != null -> {
                        upsertOccurrenceOverrideStamped(
                            existing.copy(
                                startPeriod = sp,
                                endPeriod = ep,
                                // 清除手工时刻：节次编辑后以作息表/新节次解析为准
                                startAtIso = null,
                                endAtIso = null,
                                room = cleanRoom,
                                buildingId = verifiedBuildingId,
                                canceled = false,
                                reason = reason
                            )
                        )
                    }
                    !occ.overrideId.isNullOrBlank() -> {
                        // 目标行已过期/被删：绝不静默另建关联行，本次编辑显式 no-op。
                        Log.w(
                            LOG_TAG,
                            "saveSingleDayOccurrenceEdit: 目标覆盖 ${occ.overrideId} 已不存在，编辑被忽略"
                        )
                    }
                    else -> {
                        // 无任何 overrideId 的独立单日新增（非调课目标）：保留既有兜底建行为
                        upsertOccurrenceOverrideStamped(
                            OccurrenceOverride(
                                id = "ov_mod_${occ.date}_${UUID.randomUUID().toString().take(8)}",
                                termId = termId,
                                courseId = occ.courseId,
                                courseRuleId = null,
                                date = occ.date,
                                startPeriod = sp,
                                endPeriod = ep,
                                room = cleanRoom,
                                buildingId = verifiedBuildingId,
                                canceled = false,
                                reason = reason
                            ).toEntity()
                        )
                    }
                }
            }
        }
        notifyScheduleOrPolicyChanged("OCCURRENCE_OVERRIDE_SAVED")
    }

    suspend fun resetSingleDayOccurrenceEdit(occ: cn.edu.haut.campus.core.model.ClassOccurrence) {
        val dateIso = occ.date.toString()
        val ruleIds = occ.mergedRuleIds.distinct().filter { it.isNotBlank() }.toSet()
        val reset = database.withTransaction {
            val snapshot = dao.getOccurrenceOverridesSnapshot()
            // 本次重置将删除的全部行；其中任何一条带 moveId 的调课关联行都不允许经此通用入口抹掉，
            // 否则 TARGET 孤儿化。专用撤销入口 restoreCanceledOccurrence 是唯一合法路径。
            val targeted = snapshot.filter { ov ->
                (occ.overrideId != null && ov.id == occ.overrideId) ||
                    (ov.dateIso == dateIso && ov.courseRuleId != null && ov.courseRuleId in ruleIds) ||
                    (ov.dateIso == dateIso && ruleIds.any { rId -> ov.reason == "MERGED_INTO:$rId" })
            }
            if (targeted.any { !it.moveId.isNullOrBlank() }) {
                Log.w(
                    LOG_TAG,
                    "resetSingleDayOccurrenceEdit: ${occ.date} 命中调课关联行，拒绝绕过 moveId 撤销的单条重置"
                )
                return@withTransaction false
            }
            if (!occ.overrideId.isNullOrBlank()) {
                dao.deleteOccurrenceOverrideById(occ.overrideId!!)
            }
            for (rId in ruleIds) {
                dao.deleteOccurrenceOverridesForRuleOnDate(rId, dateIso)
            }
            for (comp in targeted.filter { it.reason?.startsWith("MERGED_INTO:") == true }) {
                dao.deleteOccurrenceOverrideById(comp.id)
            }
            true
        }
        if (reset) notifyScheduleOrPolicyChanged("OCCURRENCE_OVERRIDE_DELETED")
    }

    suspend fun cancelClassOccurrenceOnDate(
        occ: cn.edu.haut.campus.core.model.ClassOccurrence,
        termId: String,
        reason: String = "单次停课"
    ) {
        val explicitRuleIds = occ.mergedRuleIds.distinct().filter { it.isNotBlank() }
        database.withTransaction {
            val daySnapshot = dao.getOccurrenceOverridesSnapshot()
            val companionRuleIds = if (explicitRuleIds.isNotEmpty()) {
                daySnapshot
                    .filter { ov ->
                        ov.dateIso == occ.date.toString() &&
                            ov.courseRuleId != null &&
                            explicitRuleIds.any { rId -> ov.reason == "MERGED_INTO:$rId" }
                    }
                    .mapNotNull { it.courseRuleId }
            } else emptyList()
            val allRuleIds = (explicitRuleIds + companionRuleIds).distinct()
            if (allRuleIds.isNotEmpty()) {
                // 停课前会先删「规则+日期」旧覆盖：命中 moveId 关联行（如调课 SOURCE）时整体拒绝，
                // 防止 TARGET 孤儿化——撤销调课必须走 restoreCanceledOccurrence。
                val moveLinked = daySnapshot.any { ov ->
                    ov.dateIso == occ.date.toString() && !ov.moveId.isNullOrBlank() &&
                        ov.courseRuleId != null && ov.courseRuleId in allRuleIds
                }
                if (moveLinked) {
                    Log.w(
                        LOG_TAG,
                        "cancelClassOccurrenceOnDate: $allRuleIds on ${occ.date} 命中调课关联行，拒绝停课覆盖"
                    )
                    return@withTransaction
                }
                for (rId in allRuleIds) {
                    dao.deleteOccurrenceOverridesForRuleOnDate(rId, occ.date.toString())
                    upsertOccurrenceOverrideStamped(
                        OccurrenceOverride(
                            id = "ov_cancel_${occ.date}_$rId",
                            termId = termId,
                            courseId = occ.courseId,
                            courseRuleId = rId,
                            date = occ.date,
                            canceled = true,
                            reason = reason
                        ).toEntity()
                    )
                }
            } else if (!occ.overrideId.isNullOrBlank()) {
                // 纯 TARGET/单日新增覆盖行：按 overrideId+termId 读既有行，仅置 canceled/reason，
                // 保留 moveId/moveRole 与其余字段——「暂停调入课」不等于「撤销整次调课」。
                val existing = daySnapshot.firstOrNull { it.id == occ.overrideId }
                when {
                    existing == null -> {
                        Log.w(
                            LOG_TAG,
                            "cancelClassOccurrenceOnDate: 目标覆盖 ${occ.overrideId} 已不存在，停课被忽略"
                        )
                    }
                    existing.termId != termId -> {
                        Log.w(
                            LOG_TAG,
                            "cancelClassOccurrenceOnDate: override ${existing.id} 属于 ${existing.termId}，拒绝跨学期停课 $termId"
                        )
                    }
                    else -> {
                        upsertOccurrenceOverrideStamped(
                            existing.copy(canceled = true, reason = reason)
                        )
                    }
                }
            }
        }
        notifyScheduleOrPolicyChanged("OCCURRENCE_CANCELED")
    }

    suspend fun rescheduleSingleOccurrenceCrossDate(
        occ: cn.edu.haut.campus.core.model.ClassOccurrence,
        termId: String,
        targetDate: LocalDate,
        newStartPeriod: Int,
        newEndPeriod: Int,
        newRoom: String,
        reason: String? = null
    ) {
        val cleanRoom = newRoom.trim().ifBlank { occ.rawRoom }
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val resolved = ClassroomBuildingResolver.resolve(cleanRoom, buildings, aliases)
        val verifiedBuildingId = if (resolved.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED) {
            resolved.building?.id
        } else if (cleanRoom == occ.rawRoom) {
            occ.buildingId
        } else null

        val explicitRuleIds = occ.mergedRuleIds.distinct().filter { it.isNotBlank() }
        database.withTransaction {
            val companionIds = if (explicitRuleIds.isNotEmpty()) {
                dao.getOccurrenceOverridesSnapshot()
                    .filter { ov ->
                        ov.dateIso == occ.date.toString() &&
                            !ov.courseRuleId.isNullOrBlank() &&
                            explicitRuleIds.any { rId -> ov.reason == "MERGED_INTO:$rId" }
                    }
                    .mapNotNull { it.courseRuleId }
            } else emptyList()
            val ruleIds = (explicitRuleIds + companionIds).distinct()
            // 每一次跨日调课生成唯一 moveId：同一次调课的全部源停课行与调入目标行共享该关联，
            // 恢复时按 moveId 原子撤销，绝不再按 courseId+日期文本猜测配对。
            val moveId = UUID.randomUUID().toString()
            // 若被移动对象本身是上一次调课的调入目标（moveRole=TARGET），把旧调课的源停课行
            // 整体改挂到新 moveId，旧 moveId 随之消亡：恢复新调课即回到最初规则原时段，不残留双份排课。
            val movingTargetOverride = occ.overrideId?.let { oid ->
                dao.getOccurrenceOverridesSnapshot().firstOrNull { it.id == oid }
            }
            val chainedMoveId = movingTargetOverride
                ?.takeIf { it.moveRole == OccurrenceOverride.MOVE_ROLE_TARGET && it.courseRuleId == null }
                ?.moveId
                ?.takeIf { it.isNotBlank() }
            if (ruleIds.isNotEmpty()) {
                for (rId in ruleIds) {
                    dao.deleteOccurrenceOverridesForRuleOnDate(rId, occ.date.toString())
                    upsertOccurrenceOverrideStamped(
                        OccurrenceOverride(
                            id = "ov_cancel_${occ.date}_$rId",
                            termId = termId,
                            courseId = occ.courseId,
                            courseRuleId = rId,
                            date = occ.date,
                            canceled = true,
                            reason = "已调至 $targetDate",
                            moveId = moveId,
                            moveRole = OccurrenceOverride.MOVE_ROLE_SOURCE
                        ).toEntity()
                    )
                }
            } else if (!occ.overrideId.isNullOrBlank()) {
                dao.deleteOccurrenceOverrideById(occ.overrideId!!)
            }
            if (!chainedMoveId.isNullOrBlank() && chainedMoveId != moveId) {
                val oldMoveRows = dao.getOccurrenceOverridesForMove(termId, chainedMoveId)
                for (row in oldMoveRows) {
                    upsertOccurrenceOverrideStamped(row.copy(moveId = moveId))
                }
            }

            upsertOccurrenceOverrideStamped(
                OccurrenceOverride(
                    id = "ov_move_${targetDate}_${UUID.randomUUID().toString().take(8)}",
                    termId = termId,
                    courseId = occ.courseId,
                    courseRuleId = null,
                    date = targetDate,
                    startPeriod = newStartPeriod.coerceIn(1, 12),
                    endPeriod = newEndPeriod.coerceIn(newStartPeriod.coerceIn(1, 12), 12),
                    room = cleanRoom,
                    buildingId = verifiedBuildingId,
                    canceled = false,
                    reason = reason?.trim()?.takeIf { it.isNotBlank() } ?: "由 ${occ.date} 调入",
                    moveId = moveId,
                    moveRole = OccurrenceOverride.MOVE_ROLE_TARGET
                ).toEntity()
            )
        }
        notifyScheduleOrPolicyChanged("OCCURRENCE_MOVED_CROSS_DATE")
    }

    /**
     * 通用单条覆盖删除入口（旧版模糊调课待核对等场景）。
     * moveId 关联行（调课 SOURCE/TARGET）严禁经此路径删除——孤儿化对端会破坏原子恢复；
     * 撤销调课的合法入口只有 [undoMove]（或兼容适配器 [restoreCanceledOccurrence]）。命中即记日志并安全 no-op。
     */
    suspend fun deleteOccurrenceOverride(overrideId: String) {
        val deleted = database.withTransaction {
            val existing = dao.getOccurrenceOverrideById(overrideId) ?: return@withTransaction false
            if (!existing.moveId.isNullOrBlank()) {
                Log.w(LOG_TAG, "deleteOccurrenceOverride: 调课关联行拒绝单条删除")
                return@withTransaction false
            }
            dao.deleteOccurrenceOverrideById(overrideId)
            true
        }
        if (deleted) notifyScheduleOrPolicyChanged("OCCURRENCE_OVERRIDE_DELETED")
    }

}
