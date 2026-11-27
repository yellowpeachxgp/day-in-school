package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import cn.edu.haut.campus.core.academic.HautAcademicEndpoints
import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.domain.PeriodTimeEditor
import cn.edu.haut.campus.core.model.AcademicTermResolver
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.PeriodTimeScheme
import cn.edu.haut.campus.core.model.Term
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * 学期、当前学期、开学日期、节次时间、校历调休/停课、学期 rollover 与初始校历种子。
 * 实现从 CampusRepository 兼容门面逐字迁入；默认参数、事务与通知语义保持不变。
 */
class TermRepository internal constructor(
    private val environment: CampusRepositoryEnvironment,
    private val examRepository: ExamRepository
) {
    private val database = environment.database
    private val dao = environment.dao
    private val preferences = environment.preferences
    private val accountProfile = environment.accountProfile
    private val supportsHautServices: Boolean get() = environment.supportsHautServices
    private val timeZone get() = environment.timeZone

    val termsFlow: Flow<List<Term>> =
        dao.observeTerms().map { list -> list.map { it.toDomain() } }

    val currentTermFlow: Flow<Term?> =
        termsFlow.map { terms -> terms.firstOrNull { it.current } ?: terms.firstOrNull() }

    val periodTimesFlow: Flow<List<PeriodTime>> =
        dao.observePeriodTimes().map { list -> list.map { it.toDomain() } }

    val periodTimeSchemesFlow: Flow<List<PeriodTimeScheme>> =
        dao.observePeriodTimeSchemes().map { list -> list.map { it.toDomain() } }

    val calendarOverridesFlow: Flow<List<CalendarOverride>> =
        dao.observeCalendarOverrides().map { list -> list.map { it.toDomain() } }

    private suspend fun notifyScheduleOrPolicyChanged(reason: String) {
        environment.notifyScheduleOrPolicyChanged(reason)
    }

    private suspend fun ensureExamTermRepairV2IfNeeded() {
        examRepository.ensureExamTermRepairV2IfNeeded()
    }

    /**
     * 首次启动仅初始化公开的校历学期、1–10 节作息时间表与莲花街校区楼宇字典，
     * 绝不自动塞入模拟课程或模拟成绩，确保用户首次打开直接进入干净的欢迎引导页。
     */
    suspend fun ensureInitialSeedIfNeeded() {
        val prefs = preferences.data.first()
        if (!accountProfile.isLegacy) {
            if (prefs[CampusRepositoryPrefKeys.CLEAN_BASE_V2] != true) {
                database.withTransaction {
                    if (supportsHautServices) seedBaseCampusDictionaryInternal() else seedOfflineDefaultsInternal()
                }
                preferences.edit {
                    it[CampusRepositoryPrefKeys.CLEAN_BASE_V2] = true
                    it[CampusRepositoryPrefKeys.SEEDED] = true
                    it[CampusRepositoryPrefKeys.ONBOARDING_COMPLETED] = true
                    it[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED] = false
                    it[CampusRepositoryPrefKeys.TERM_MISMATCH_REPAIRED_V2] = true
                    it[CampusRepositoryPrefKeys.GIS_DICTIONARY_SYNCED_V18] = true
                }
            } else if (supportsHautServices && prefs[CampusRepositoryPrefKeys.GIS_DICTIONARY_SYNCED_V18] != true) {
                syncCampusDictionaryV18Internal()
            }
            ensureExtendedPeriodTimes11To12IfNeeded()
            // 不再运行 V1 日期猜测修复：无逐行来源证明的历史行一律交给 V2 记 REVIEW_REQUIRED。
            ensureExamTermRepairV2IfNeeded()
            ensureSeasonalTermRolloverIfNeeded()
            return
        }
        if (prefs[CampusRepositoryPrefKeys.CLEAN_BASE_V2] != true) {
            database.withTransaction {
                if (prefs[CampusRepositoryPrefKeys.ONBOARDING_COMPLETED] != true) {
                    dao.clearStudentProfile()
                    dao.clearCourses()
                    dao.clearRules()
                    dao.clearOccurrenceOverrides()
                    dao.clearGradeAttempts()
                }
                seedBaseCampusDictionaryInternal()
            }
            preferences.edit {
                it[CampusRepositoryPrefKeys.SEEDED] = true
                it[CampusRepositoryPrefKeys.CLEAN_BASE_V2] = true
                it[CampusRepositoryPrefKeys.GIS_DICTIONARY_SYNCED_V15] = true
                it[CampusRepositoryPrefKeys.GIS_DICTIONARY_SYNCED_V16] = true
                it[CampusRepositoryPrefKeys.GIS_DICTIONARY_SYNCED_V17] = true
                it[CampusRepositoryPrefKeys.GIS_DICTIONARY_SYNCED_V18] = true
            }
        } else if (
            prefs[CampusRepositoryPrefKeys.GIS_DICTIONARY_SYNCED_V15] != true ||
            prefs[CampusRepositoryPrefKeys.GIS_DICTIONARY_SYNCED_V16] != true ||
            prefs[CampusRepositoryPrefKeys.GIS_DICTIONARY_SYNCED_V17] != true ||
            prefs[CampusRepositoryPrefKeys.GIS_DICTIONARY_SYNCED_V18] != true
        ) {
            syncCampusDictionaryV18Internal()
        }
        if (prefs[CampusRepositoryPrefKeys.TERM_MISMATCH_REPAIRED_V2] != true) {
            database.withTransaction {
                val existingTerms = dao.observeTerms().first().map { it.toDomain() }
                val existingById = existingTerms.associateBy { it.id }
                val hasCurrent = existingTerms.any { it.current }
                val mergedTerms = HautMockSeedData.defaultTerms.map { seedTerm ->
                    val prev = existingById[seedTerm.id]
                    if (prev != null) {
                        val repairedStart = if (
                            prev.id == "2026-2027-1" &&
                            prev.startDate == LocalDate.of(2026, 9, 7)
                        ) {
                            LocalDate.of(2026, 8, 31)
                        } else {
                            prev.startDate
                        }
                        prev.copy(startDate = repairedStart).toEntity()
                    } else {
                        seedTerm.copy(current = if (hasCurrent) false else seedTerm.current).toEntity()
                    }
                }
                dao.upsertTerms(mergedTerms)

                // 修复旧版本因 currentTerm 写死 2026-2027-1 导致历史学期同步课表被错存到 2026-2027-1 的问题（仅当用户曾显式保存过旧同步学期配置时执行）
                val savedXnm = prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM]
                val savedXqm = prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM]
                if (!savedXnm.isNullOrBlank() && !savedXqm.isNullOrBlank()) {
                    val expectedTermId = HautAcademicEndpoints.toTermId(savedXnm, savedXqm)
                    val allRules = dao.getRulesSnapshot()
                    val rulesIn26 = allRules.filter { it.termId == "2026-2027-1" && it.id.startsWith("r_imp_") }
                    val rulesInExpected = allRules.filter { it.termId == expectedTermId }
                    if (expectedTermId != "2026-2027-1" && rulesIn26.isNotEmpty() && rulesInExpected.isEmpty()) {
                        dao.upsertRules(rulesIn26.map { it.copy(termId = expectedTermId) })
                        dao.setCurrentTerm(expectedTermId)
                    }
                }
            }
            preferences.edit {
                it[CampusRepositoryPrefKeys.TERM_MISMATCH_REPAIRED_V2] = true
                it.remove(CampusRepositoryPrefKeys.LAST_AUTO_SYNC_MS)
            }
        }
        ensureExtendedPeriodTimes11To12IfNeeded()
        // 不再运行 V1 日期猜测修复：无逐行来源证明的历史行一律交给 V2 记 REVIEW_REQUIRED。
        ensureExamTermRepairV2IfNeeded()
        ensureSeasonalTermRolloverIfNeeded()
    }


    /**
     * 为升级老用户补齐 11–12 节晚间默认作息时间（不覆盖用户已自定义编辑的 1–10 节作息）：
     * - 莲花街默认作息表（`haut_lianhuajie_default`）：21:15–22:00、22:05–22:50（若第 10 节被改晚则自动顺延）；
     * - 通用离线作息表（`manual_default` 或自定义表）：紧接已有最晚节次顺延 10 分钟，每节 45 分钟。
     */
    internal suspend fun ensureExtendedPeriodTimes11To12IfNeeded() {
        val prefs = preferences.data.first()
        val existingEntities = dao.observePeriodTimes().first()
        // 11–12 节自动补齐只作用于默认参考作息；用户日期方案是完整独立集合，不能被该种子逻辑混入。
        val byTimetable = existingEntities.map { it.toDomain() }
            .filter { it.schemeId == null }
            .groupBy { it.timeTableId }
        val toInsert = mutableListOf<PeriodTimeEntity>()

        for ((ttId, list) in byTimetable) {
            if (ttId.isBlank()) continue
            // 用户显式编辑/删除过的默认作息属于个人覆盖，不能再被升级种子悄悄补回 11–12 节；
            // 否则“删除节次”会在下次初始化时失效。v10 迁移行无法反证用户编辑，仍保持 false 原样。
            if (list.any { it.userModified }) continue
            val existingNos = list.map { it.periodNo }.toSet()
            if (11 in existingNos && 12 in existingNos) continue
            val p10End = list.filter { it.periodNo <= 10 }.maxByOrNull { it.periodNo }?.endLocalTime
            val defaultP11Start = if (ttId == HautMockSeedData.DEFAULT_TIMETABLE_ID) {
                val base = LocalTime.of(21, 15)
                if (p10End != null && !p10End.isBefore(base)) {
                    val mins = (p10End.hour * 60 + p10End.minute + 5).coerceAtMost(22 * 60 + 10)
                    LocalTime.of(mins / 60, mins % 60)
                } else base
            } else {
                val base = p10End ?: LocalTime.of(20, 40)
                val mins = (base.hour * 60 + base.minute + 10).coerceAtMost(22 * 60 + 10)
                LocalTime.of(mins / 60, mins % 60)
            }
            val p11EndMins = (defaultP11Start.hour * 60 + defaultP11Start.minute + 45).coerceAtMost(23 * 60 + 5)
            val p11End = LocalTime.of(p11EndMins / 60, p11EndMins % 60)
            val p12StartMins = (p11EndMins + 5).coerceAtMost(23 * 60 + 10)
            val p12Start = LocalTime.of(p12StartMins / 60, p12StartMins % 60)
            val p12EndMins = (p12StartMins + 45).coerceAtMost(23 * 60 + 55)
            val p12End = LocalTime.of(p12EndMins / 60, p12EndMins % 60)

            val idPrefix = if (ttId == HautMockSeedData.DEFAULT_TIMETABLE_ID) "pt" else "${ttId}_period"
            if (11 !in existingNos) {
                toInsert += PeriodTime("${idPrefix}_11", ttId, 11, defaultP11Start, p11End, "晚间").toEntity()
            }
            if (12 !in existingNos) {
                val actual11End = list.firstOrNull { it.periodNo == 11 }?.endLocalTime ?: p11End
                val s12Mins = (actual11End.hour * 60 + actual11End.minute + 5).coerceAtMost(23 * 60 + 10)
                val e12Mins = (s12Mins + 45).coerceAtMost(23 * 60 + 55)
                toInsert += PeriodTime(
                    "${idPrefix}_12",
                    ttId,
                    12,
                    LocalTime.of(s12Mins / 60, s12Mins % 60),
                    LocalTime.of(e12Mins / 60, e12Mins % 60),
                    "晚间"
                ).toEntity()
            }
        }

        if (toInsert.isNotEmpty()) {
            dao.upsertPeriodTimes(toInsert)
        }
        if (prefs[CampusRepositoryPrefKeys.PERIOD_TIMES_11_12_SYNCED_V1] != true) {
            preferences.edit { it[CampusRepositoryPrefKeys.PERIOD_TIMES_11_12_SYNCED_V1] = true }
        }
    }

    private fun parseTermSortKey(termId: String): Pair<Int, Int>? {
        val parts = termId.trim().split("-")
        if (parts.size < 3) return null
        val startYear = parts[0].toIntOrNull() ?: return null
        val semNo = parts[2].toIntOrNull() ?: return null
        return startYear to semNo
    }

    internal fun isTermOlderThan(candidateTermId: String, referenceTermId: String): Boolean {
        val cand = parseTermSortKey(candidateTermId) ?: return false
        val ref = parseTermSortKey(referenceTermId) ?: return false
        return if (cand.first != ref.first) cand.first < ref.first else cand.second < ref.second
    }

    /**
     * 自然学期跨季自动演进：
     * 当升级老用户仍停留在上一自然学期（如 2025-2026-2 + SAVED_SYNC_XNM=2025/XQM=12），
     * 且未在本学期主动手动锁定查看旧学期时，自动推进 currentTerm 与默认同步参数至当前自然学期（如 2026-2027-1）。
     */
    internal suspend fun ensureSeasonalTermRolloverIfNeeded(today: LocalDate = LocalDate.now(timeZone)) {
        val seasonalTermId = AcademicTermResolver.defaultTermId(today)
        val seasonalXnm = AcademicTermResolver.defaultAcademicYearCode(today)
        val seasonalXqm = AcademicTermResolver.defaultSemesterCode(today)
        val prefs = preferences.data.first()
        val manualSeason = prefs[CampusRepositoryPrefKeys.USER_MANUAL_TERM_SEASON]
        val lastRolled = prefs[CampusRepositoryPrefKeys.LAST_SEASONAL_ROLLOVER_TERM_ID]
        val currentTermEntity = dao.getCurrentTerm()
        val savedXnm = prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM]
        val savedXqm = prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM]
        val savedSyncTermId = if (!savedXnm.isNullOrBlank() && !savedXqm.isNullOrBlank()) {
            HautAcademicEndpoints.toTermId(savedXnm, savedXqm)
        } else null

        val needsTermAdvance = manualSeason != seasonalTermId &&
            (currentTermEntity == null || isTermOlderThan(currentTermEntity.id, seasonalTermId))
        val needsSyncParamAdvance = manualSeason != seasonalTermId &&
            (savedSyncTermId == null || isTermOlderThan(savedSyncTermId, seasonalTermId))

        if (lastRolled == seasonalTermId && !needsTermAdvance && !needsSyncParamAdvance) {
            return
        }

        val repairedStart = if (seasonalTermId == "2026-2027-1") {
            val existing = dao.observeTerms().first().firstOrNull { it.id == seasonalTermId }
            if (existing?.startDateIso == "2026-09-07") LocalDate.of(2026, 8, 31) else null
        } else null

        ensureTermExistsAndUpdateCalendar(
            termId = seasonalTermId,
            semesterStart = repairedStart,
            setCurrent = needsTermAdvance
        )
        preferences.edit { editPrefs ->
            editPrefs[CampusRepositoryPrefKeys.LAST_SEASONAL_ROLLOVER_TERM_ID] = seasonalTermId
            if (needsSyncParamAdvance || needsTermAdvance) {
                editPrefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM] = seasonalXnm
                editPrefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM] = seasonalXqm
                editPrefs.remove(CampusRepositoryPrefKeys.LAST_AUTO_SYNC_MS)
            }
        }
        if (needsTermAdvance) {
            notifyScheduleOrPolicyChanged("SEASONAL_TERM_ROLLOVER")
        }
    }


    private suspend fun syncCampusDictionaryV18Internal() {
        database.withTransaction {
            dao.deleteBuildingsByIds(listOf("b_canteen2", "b_canteen3"))
            dao.deleteBuildingAliasesByIds(
                listOf(
                    "al_4", "al_4b", "al_4c", "al_5",
                    "al_cant2_a", "al_cant2_b", "al_cant2_c",
                    "al_cant3_a", "al_cant3_b", "al_cant3_c"
                )
            )
            dao.upsertBuildings(
                (HautMockSeedData.lianhuajieBuildings + ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS)
                    .map { it.toEntity() }
            )
            dao.upsertBuildingAliases(
                (HautMockSeedData.defaultBuildingAliases + ClassroomBuildingResolver.HAUT_SONGSHANLU_ALIASES)
                    .map { it.toEntity() }
            )
        }
        preferences.edit {
            it[CampusRepositoryPrefKeys.GIS_DICTIONARY_SYNCED_V15] = true
            it[CampusRepositoryPrefKeys.GIS_DICTIONARY_SYNCED_V16] = true
            it[CampusRepositoryPrefKeys.GIS_DICTIONARY_SYNCED_V17] = true
            it[CampusRepositoryPrefKeys.GIS_DICTIONARY_SYNCED_V18] = true
        }
    }

    internal suspend fun seedBaseCampusDictionaryInternal() {
        dao.upsertTerms(HautMockSeedData.defaultTerms.map { it.toEntity() })
        dao.upsertPeriodTimes(HautMockSeedData.defaultPeriodTimes.map { it.toEntity() })
        dao.upsertBuildings(
            (HautMockSeedData.lianhuajieBuildings + ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS)
                .map { it.toEntity() }
        )
        dao.upsertBuildingAliases(
            (HautMockSeedData.defaultBuildingAliases + ClassroomBuildingResolver.HAUT_SONGSHANLU_ALIASES)
                .map { it.toEntity() }
        )
        dao.upsertCalendarOverrides(HautMockSeedData.defaultCalendarOverrides.map { it.toEntity() })
    }

    /** 通用离线模式只提供可编辑作息，不推定学校建筑、调休或官方开学日期。 */
    private suspend fun seedOfflineDefaultsInternal() {
        val today = LocalDate.now(timeZone)
        val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val year = if (today.monthValue >= 8) today.year else today.year - 1
        val termNo = if (today.monthValue in 2..7) 2 else 1
        val timetableId = "manual_default"
        dao.upsertTerms(listOf(Term(
            id = "$year-${year + 1}-$termNo", academicYear = "$year-${year + 1}", termNumber = termNo,
            startDate = monday, weekCount = 20, timeTableId = timetableId, current = true
        ).toEntity()))
        dao.upsertPeriodTimes(ReferencePeriodTimes.forTimeTableId(timetableId).map { it.toEntity() })
    }


    suspend fun ensureTermExistsAndUpdateCalendar(
        termId: String,
        semesterStart: LocalDate? = null,
        totalWeeks: Int? = null,
        setCurrent: Boolean = false
    ) {
        val existingTerms = dao.observeTerms().first().map { it.toDomain() }
        val existing = existingTerms.firstOrNull { it.id == termId }
        val seedDefault = HautMockSeedData.defaultTerms.firstOrNull { it.id == termId }
        val parts = termId.split("-")
        val academicYear = if (parts.size >= 2) "${parts[0]}-${parts[1]}" else "2026-2027"
        val termNumber = parts.getOrNull(2)?.toIntOrNull() ?: 1
        val defaultStart = seedDefault?.startDate ?: if (termNumber == 1) {
            LocalDate.of(parts.firstOrNull()?.toIntOrNull() ?: 2026, 9, 1)
        } else {
            LocalDate.of(parts.getOrNull(1)?.toIntOrNull() ?: 2026, 3, 2)
        }
        val updatedTerm = Term(
            id = termId,
            academicYear = existing?.academicYear ?: academicYear,
            termNumber = existing?.termNumber ?: termNumber,
            startDate = semesterStart ?: existing?.startDate ?: defaultStart,
            weekStartDay = existing?.weekStartDay ?: DayOfWeek.MONDAY,
            weekCount = (totalWeeks ?: existing?.weekCount ?: seedDefault?.weekCount ?: 20).coerceIn(1, 30),
            timeTableId = existing?.timeTableId ?: HautMockSeedData.DEFAULT_TIMETABLE_ID,
            current = if (setCurrent) true else (existing?.current ?: false)
        )
        dao.upsertTerms(listOf(updatedTerm.toEntity()))
        if (setCurrent) {
            dao.setCurrentTerm(termId)
        }
    }

    suspend fun selectCurrentTerm(termId: String) {
        ensureTermExistsAndUpdateCalendar(termId = termId, setCurrent = true)
        val (xnm, xqm) = HautAcademicEndpoints.fromTermId(termId)
        val seasonalTermId = AcademicTermResolver.defaultTermId(LocalDate.now(timeZone))
        preferences.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM] = xnm
            prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM] = xqm
            if (termId != seasonalTermId) {
                prefs[CampusRepositoryPrefKeys.USER_MANUAL_TERM_SEASON] = seasonalTermId
            } else {
                prefs.remove(CampusRepositoryPrefKeys.USER_MANUAL_TERM_SEASON)
            }
        }
        notifyScheduleOrPolicyChanged("TERM_CHANGED")
    }

    suspend fun updateTermStartDate(termId: String, newStartDate: LocalDate) {
        val terms = dao.observeTerms().first().map { it.toDomain() }
        val target = terms.firstOrNull { it.id == termId } ?: return
        dao.upsertTerms(listOf(target.copy(startDate = newStartDate).toEntity()))
        notifyScheduleOrPolicyChanged("TERM_START_DATE_CHANGED")
    }

    /**
     * 单节编辑沿用既有公开入口，但写入前按同一作用域完整校验，拒绝重叠/倒序后局部落库。
     */
    suspend fun updatePeriodTime(periodTime: PeriodTime) {
        val now = System.currentTimeMillis()
        val all = dao.getPeriodTimesSnapshot().map { it.toDomain() }
        val existing = all.firstOrNull { it.id == periodTime.id }
        val scoped = all.filter { row ->
            if (periodTime.schemeId == null) {
                row.timeTableId == periodTime.timeTableId && row.schemeId == null
            } else {
                row.schemeId == periodTime.schemeId
            }
        }
        val normalized = periodTime.copy(
            termId = periodTime.termId ?: existing?.termId,
            validStartDate = periodTime.validStartDate ?: existing?.validStartDate,
            validEndDate = periodTime.validEndDate ?: existing?.validEndDate,
            userModified = true,
            updatedAtEpochMillis = now
        )
        val candidate = (scoped.filterNot { it.id == normalized.id } + normalized)
        PeriodTimeEditor.requireValidPeriods(candidate)
        dao.upsertPeriodTimes(listOf(normalized.toEntity()))
        notifyScheduleOrPolicyChanged("PERIOD_TIME_CHANGED")
    }

    /**
     * 编辑器整组保存默认作息：增删节次与调整起止在同一事务内完成；
     * 全组必须先通过单调递增且不重叠校验，任何非法项都不会产生半写入。
     */
    suspend fun saveBasePeriodTimes(
        timeTableId: String,
        periods: List<PeriodTime>
    ) {
        require(timeTableId.isNotBlank()) { "作息表标识不能为空" }
        val now = System.currentTimeMillis()
        val normalized = PeriodTimeEditor.requireValidPeriods(periods).map { period ->
            period.copy(
                id = period.id.ifBlank { "pt_user_${timeTableId}_${period.periodNo}" },
                timeTableId = timeTableId,
                schemeId = null,
                termId = null,
                validStartDate = null,
                validEndDate = null,
                userModified = true,
                updatedAtEpochMillis = now
            )
        }
        database.withTransaction {
            dao.deleteBasePeriodTimesForTimeTable(timeTableId)
            dao.upsertPeriodTimes(normalized.map { it.toEntity() })
        }
        notifyScheduleOrPolicyChanged("PERIOD_TIME_CHANGED")
    }

    /** 删除默认作息或某方案内的单个节次；拒绝删除最后一节造成空作息。 */
    suspend fun deletePeriodTime(periodTimeId: String) {
        val all = dao.getPeriodTimesSnapshot().map { it.toDomain() }
        val target = all.firstOrNull { it.id == periodTimeId } ?: return
        val scoped = all.filter { row ->
            if (target.schemeId == null) {
                row.timeTableId == target.timeTableId && row.schemeId == null
            } else {
                row.schemeId == target.schemeId
            }
        }
        val remaining = scoped.filterNot { it.id == periodTimeId }
        PeriodTimeEditor.requireValidPeriods(remaining, emptyMessage = "不能删除最后一节")
        val deleted = dao.deletePeriodTimeById(periodTimeId)
        if (deleted > 0) notifyScheduleOrPolicyChanged("PERIOD_TIME_CHANGED")
    }

    /**
     * 保存一套按日期区间生效的作息方案。方案元数据与全部节次行同事务写入；
     * 日期区间与同学期其他方案重叠、节次倒序/重叠/为空时整体拒绝。
     */
    suspend fun savePeriodTimeScheme(
        term: Term,
        name: String,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<PeriodTime>,
        schemeId: String? = null
    ): String {
        require(term.id.isNotBlank()) { "学期不能为空" }
        require(term.timeTableId.isNotBlank()) { "学期作息表标识不能为空" }
        val existingSchemes = dao.getPeriodTimeSchemesSnapshot()
            .map { it.toDomain() }
            .filter { it.termId == term.id }
        PeriodTimeEditor.requireValidScheme(
            name = name,
            startDate = startDate,
            endDate = endDate,
            periods = periods,
            existingSchemes = existingSchemes,
            editingSchemeId = schemeId
        )
        val now = System.currentTimeMillis()
        val id = schemeId ?: "pts_${UUID.randomUUID().toString().take(12)}"
        val previous = existingSchemes.firstOrNull { it.id == id }
        val scheme = PeriodTimeScheme(
            id = id,
            termId = term.id,
            timeTableId = term.timeTableId,
            name = name.trim(),
            startDate = startDate,
            endDate = endDate,
            userModified = true,
            createdAtEpochMillis = previous?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = now
        )
        val rows = PeriodTimeEditor.requireValidPeriods(periods).map { period ->
            period.copy(
                id = "${id}_period_${period.periodNo}",
                timeTableId = term.timeTableId,
                schemeId = id,
                termId = term.id,
                validStartDate = startDate,
                validEndDate = endDate,
                userModified = true,
                updatedAtEpochMillis = now
            )
        }
        database.withTransaction {
            dao.upsertPeriodTimeSchemes(listOf(scheme.toEntity()))
            dao.deletePeriodTimesForScheme(id)
            dao.upsertPeriodTimes(rows.map { it.toEntity() })
        }
        notifyScheduleOrPolicyChanged("PERIOD_TIME_CHANGED")
        return id
    }

    suspend fun deletePeriodTimeScheme(schemeId: String) {
        require(schemeId.isNotBlank()) { "作息方案标识不能为空" }
        database.withTransaction {
            dao.deletePeriodTimesForScheme(schemeId)
            dao.deletePeriodTimeSchemeById(schemeId)
        }
        notifyScheduleOrPolicyChanged("PERIOD_TIME_CHANGED")
    }


    suspend fun saveCalendarOverride(override: CalendarOverride) {
        dao.upsertCalendarOverrides(listOf(override.toEntity()))
        notifyScheduleOrPolicyChanged("CALENDAR_OVERRIDE_SAVED")
    }

    suspend fun deleteCalendarOverride(id: String) {
        dao.deleteCalendarOverrideById(id)
        notifyScheduleOrPolicyChanged("CALENDAR_OVERRIDE_DELETED")
    }

    suspend fun applyInterchangeCalendarMetadata(
        termId: String,
        semesterStart: LocalDate?,
        totalWeeks: Int?,
        sectionTimes: List<PeriodTime>
    ) {
        database.withTransaction {
            if (semesterStart != null || totalWeeks != null) {
                val terms = dao.observeTerms().first().map { it.toDomain() }
                val target = terms.firstOrNull { it.id == termId }
                if (target != null) {
                    dao.upsertTerms(
                        listOf(
                            target.copy(
                                startDate = semesterStart ?: target.startDate,
                                weekCount = totalWeeks ?: target.weekCount
                            ).toEntity()
                        )
                    )
                }
            }
            if (sectionTimes.isNotEmpty()) {
                val normalized = PeriodTimeEditor.requireValidPeriods(sectionTimes).map { period ->
                    period.copy(
                        schemeId = null,
                        termId = null,
                        validStartDate = null,
                        validEndDate = null,
                        userModified = true,
                        updatedAtEpochMillis = System.currentTimeMillis()
                    )
                }
                dao.upsertPeriodTimes(normalized.map { it.toEntity() })
            }
        }
        notifyScheduleOrPolicyChanged("INTERCHANGE_CALENDAR_APPLIED")
    }

}
