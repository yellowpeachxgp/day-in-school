package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import cn.edu.haut.campus.core.academic.HautAcademicEndpoints
import cn.edu.haut.campus.core.domain.CampusDiningRecommender
import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.domain.ImportDiffEngine
import cn.edu.haut.campus.core.domain.PeriodTimeEditor
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingAlias
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.ImportAudit
import cn.edu.haut.campus.core.model.ImportChangeType
import cn.edu.haut.campus.core.model.ImportCourseCandidate
import cn.edu.haut.campus.core.model.ImportDiffPreview
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.OccurrenceRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 课程、排课规则、楼宇别名、课表导入差异落库与排课快照。
 * 实现从 CampusRepository 兼容门面逐字迁入；事务、默认值与通知语义保持不变。
 * occurrence 编辑/恢复命令在后续小步迁入独立内部服务。
 */
class TimetableRepository internal constructor(
    private val environment: CampusRepositoryEnvironment,
    private val termRepository: TermRepository
) {
    private val database = environment.database
    private val dao = environment.dao
    private val preferences = environment.preferences
    private val supportsHautServices: Boolean get() = environment.supportsHautServices

    internal val occurrenceWriteContext = TimetableOccurrenceWriteContext(environment)
    private val occurrenceStore = TimetableOccurrenceStore(environment, occurrenceWriteContext)
    private val restoreCommandService = TimetableRestoreCommandService(environment, occurrenceWriteContext)
    private val legacyRestoreReviewJournalStore = LegacyRestoreReviewJournalStore(environment)

    val coursesFlow: Flow<List<Course>> =
        dao.observeCourses().map { list -> list.map { it.toDomain() } }

    val rulesFlow: Flow<List<CourseScheduleRule>> =
        dao.observeRules().map { list -> list.map { it.toDomain() } }

    val occurrenceOverridesFlow: Flow<List<OccurrenceOverride>> =
        dao.observeOccurrenceOverrides().map { list -> list.map { it.toDomain() } }

    /** 旧版调入核对完成 journal：跨 ViewModel 重建恢复，Room 行删除/改令牌后自动失效。 */
    val legacyRestoreReviewTargetsFlow: Flow<List<OccurrenceOverride>> =
        legacyRestoreReviewJournalStore.targetsFlow

    val buildingsFlow: Flow<List<Building>> =
        combine(dao.observeBuildings(), preferences.data) { list, prefs ->
            val base = list.map { it.toDomain() }
            val songshan = if (supportsHautServices) ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS else emptyList()
            val customZones = cn.edu.haut.campus.core.model.CustomCampusZoneCodec.decodeList(
                prefs[CampusRepositoryPrefKeys.CUSTOM_CAMPUS_ZONES_JSON] ?: "[]"
            )
            val customBuildings = customZones.flatMap { it.buildings }
            (songshan + base.filter { it.campusId != "songshanlu" && it.id !in CampusDiningRecommender.CLOSED_CANTEEN_BUILDING_IDS } + customBuildings)
                .distinctBy { it.id }
        }

    val buildingAliasesFlow: Flow<List<BuildingAlias>> =
        dao.observeBuildingAliases().map { list ->
            val removedAliasIds = setOf(
                "al_4", "al_4b", "al_4c", "al_5",
                "al_cant2_a", "al_cant2_b", "al_cant2_c",
                "al_cant3_a", "al_cant3_b", "al_cant3_c"
            )
            val base = list.map { it.toDomain() }.filter { it.id !in removedAliasIds }
            val songshan = if (supportsHautServices) ClassroomBuildingResolver.HAUT_SONGSHANLU_ALIASES else emptyList()
            (songshan + base).distinctBy { it.id }
        }


    val unscheduledCoursesFlow: Flow<List<UnscheduledAcademicCourseEntity>> =
        dao.observeUnscheduledCourses()

    private suspend fun notifyScheduleOrPolicyChanged(reason: String) {
        environment.notifyScheduleOrPolicyChanged(reason)
    }

    /**
     * 课程聚合写入边界：refsProvider 在 Vault/Room 事务内求值，before/after 与业务行同事务。
     * 调用方应尽量给精确课程集合；批量导入/清空可给当前全量加计划新增 ID 的安全超集。
     */
    private suspend fun <T> writeCourseAggregates(
        courseIdsProvider: suspend () -> Collection<String>,
        actor: Actor,
        actorDetail: String,
        writeGroupId: String? = null,
        block: suspend () -> T
    ): T = environment.academicEntityWritePort.writeMany(
        refsProvider = {
            courseIdsProvider().distinct().map { environment.academicRef(EntityType.COURSE, it) }
        },
        actor = actor,
        actorDetail = actorDetail,
        writeGroupId = writeGroupId,
        block = block
    )

    private suspend fun courseIdsForRules(ruleIds: Collection<String>): Set<String> =
        dao.getRulesSnapshot().filter { it.id in ruleIds }.mapTo(mutableSetOf()) { it.courseId }

    private fun absorbedCompanionRuleIds(
        allOverrides: List<OccurrenceOverrideEntity>,
        seedIds: Set<String>,
        contextDateIso: String?
    ): List<String> = if (contextDateIso != null) {
        allOverrides
            .filter { ov ->
                ov.dateIso == contextDateIso &&
                    !ov.courseRuleId.isNullOrBlank() &&
                    seedIds.any { id -> ov.reason == "MERGED_INTO:$id" }
            }
            .mapNotNull { it.courseRuleId }
    } else {
        emptyList()
    }

    private suspend fun courseIdsForOverrides(overrideIds: Collection<String>): Set<String> {
        val rules = dao.getRulesSnapshot().associateBy { it.id }
        return dao.getOccurrenceOverridesSnapshot()
            .filter { it.id in overrideIds }
            .mapNotNullTo(mutableSetOf()) { row ->
                row.courseId ?: row.courseRuleId?.let(rules::get)?.courseId
            }
    }

    private suspend fun courseIdsForMoveIds(moveIds: Collection<String>): Set<String> {
        val rules = dao.getRulesSnapshot().associateBy { it.id }
        return dao.getOccurrenceOverridesSnapshot()
            .filter { it.moveId in moveIds }
            .mapNotNullTo(mutableSetOf()) { row ->
                row.courseId ?: row.courseRuleId?.let(rules::get)?.courseId
            }
    }

    suspend fun resetToDemoSeedData() {
        check(supportsHautServices) { "当前学校不提供工大示例数据" }
        environment.academicEntityWritePort.writeMany(
            refsProvider = {
                val courseIds = (dao.getCoursesSnapshot().map { it.id } + HautMockSeedData.defaultCourses.map { it.id }).distinct()
                val gradeIds = (dao.getGradeAttemptsSnapshot().map { it.id } + HautMockSeedData.defaultGradeAttempts.map { it.id }).distinct()
                courseIds.map { environment.academicRef(EntityType.COURSE, it) } +
                    gradeIds.map { environment.academicRef(EntityType.GRADE_ATTEMPT, it) }
            },
            actor = Actor.USER,
            actorDetail = "reset-demo-seed-data",
            writeGroupId = "reset-demo-seed-data:" + UUID.randomUUID().toString()
        ) {
            database.withTransaction {
                dao.clearCourses()
                dao.clearRules()
                dao.clearOccurrenceOverrides()
                dao.clearGradeAttempts()
                seedDemoDataInternal()
            }
        }
        preferences.edit {
            it[CampusRepositoryPrefKeys.SEEDED] = true
            it[CampusRepositoryPrefKeys.CLEAN_BASE_V2] = true
            it[CampusRepositoryPrefKeys.ONBOARDING_COMPLETED] = true
            it.remove(CampusRepositoryPrefKeys.OFFICIAL_GPA)
            it.remove(CampusRepositoryPrefKeys.OFFICIAL_GPA_NOTE)
            it.remove(CampusRepositoryPrefKeys.LEGACY_RESTORE_REVIEW_JOURNAL_JSON)
        }
        notifyScheduleOrPolicyChanged("RESET_DEMO_SEED_DATA")
    }

    private suspend fun seedDemoDataInternal() {
        termRepository.seedBaseCampusDictionaryInternal()
        dao.upsertStudentProfile(HautMockSeedData.defaultStudent.toEntity())
        dao.upsertCourses(HautMockSeedData.defaultCourses.map { it.toEntity() })
        dao.upsertRules(HautMockSeedData.defaultRules.map { it.toEntity() })
        dao.upsertOccurrenceOverrides(
            HautMockSeedData.defaultOccurrenceOverrides.map {
                it.toEntity().copy(mutationToken = UUID.randomUUID().toString())
            }
        )
        dao.upsertGradeAttempts(HautMockSeedData.defaultGradeAttempts.map { it.toEntity() })
    }


    /**
     * 用户手动编辑课程属性与排课规则：自动标记 userModified = true，确保下次教务同步时不会静默覆盖。
     */
    suspend fun saveUserCourseEdit(
        course: Course,
        rule: CourseScheduleRule
    ) {
        saveUserMergedCourseRuleEdit(
            course = course,
            primaryRule = rule,
            mergedRuleIds = listOf(rule.id)
        )
    }

    /**
     * 编辑可能由多条相邻规则纵向合并（如 1-2节 + 3-4节 合并显示为 1-4节）的全学期排课规则：
     * - 更新主规则为目标星期、起止节次、教室与周次；
     * - 对其余被合并的子规则，减去本次编辑覆盖的周次集合（若剩余周次为空则移除子规则及其孤立单日覆盖），彻底消除残余 3-4节 重叠！
     */
    @JvmOverloads
    suspend fun saveUserMergedCourseRuleEdit(
        course: Course,
        primaryRule: CourseScheduleRule,
        mergedRuleIds: List<String>,
        contextDate: LocalDate? = null
    ) {
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val resolvedBuilding = ClassroomBuildingResolver.resolve(primaryRule.rawRoom, buildings, aliases)
        val verifiedBuildingId = if (resolvedBuilding.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED) {
            resolvedBuilding.building?.id
        } else {
            primaryRule.buildingId
        }
        val explicitExtraIds = mergedRuleIds.distinct().filter { it.isNotBlank() && it != primaryRule.id }
        val contextDateIso = contextDate?.toString()

        writeCourseAggregates(
            courseIdsProvider = {
                setOf(course.id, primaryRule.courseId) + courseIdsForRules((mergedRuleIds + primaryRule.id).toSet())
            },
            actor = Actor.USER,
            actorDetail = "course-user-edit"
        ) {
            database.withTransaction {
            val allRulesById = dao.getRulesSnapshot().associateBy { it.id }
            val existingPrimaryDomain = allRulesById[primaryRule.id]?.toDomain()
            val allOverrides = dao.getOccurrenceOverridesSnapshot()
            // 仅限定在当前打开卡片所在日期（contextDateIso）恢复同日吸收成员，严禁将历史其他日期的单日 MERGED_INTO 永久扩散到全学期误删未选中排课！
            val sameContextAbsorbedIds = if (contextDateIso != null) {
                allOverrides
                    .filter { ov ->
                        ov.dateIso == contextDateIso &&
                            ov.reason == "MERGED_INTO:${primaryRule.id}" &&
                            !ov.courseRuleId.isNullOrBlank()
                    }
                    .mapNotNull { it.courseRuleId }
                    .filter { it != primaryRule.id }
            } else {
                emptyList()
            }
            val extraRuleIds = (explicitExtraIds + sameContextAbsorbedIds).distinct()

            dao.upsertCourse(course.copy(userModified = true).toEntity())
            dao.upsertRule(
                primaryRule.copy(
                    buildingId = verifiedBuildingId,
                    userModified = true
                ).toEntity()
            )
            val originalPrimaryWeeks = existingPrimaryDomain?.weeks.orEmpty()
            for (extraId in extraRuleIds) {
                val extraEntity = allRulesById[extraId] ?: continue
                val extraDomain = extraEntity.toDomain()
                // 原合并成员在原编辑范围（existingPrimary.weeks）内的周次随本次全学期修改一并移除/替换；
                // 若新规则与子规则同星期且节次重叠，新周次也一并吸收；子规则在原合并范围之外独立存在的周次予以保留！
                val newOverlappingWeeks = if (
                    primaryRule.weekday == extraDomain.weekday &&
                    primaryRule.startPeriod <= extraDomain.endPeriod &&
                    primaryRule.endPeriod >= extraDomain.startPeriod
                ) {
                    primaryRule.weeks
                } else {
                    emptySet()
                }
                val weeksCoveredByMergedEdit = if (originalPrimaryWeeks.isNotEmpty()) {
                    originalPrimaryWeeks + newOverlappingWeeks
                } else {
                    extraDomain.weeks + newOverlappingWeeks
                }
                val remainingWeeks = extraDomain.weeks - weeksCoveredByMergedEdit
                if (remainingWeeks.isEmpty()) {
                    dao.deleteRuleById(extraId)
                    dao.deleteOccurrenceOverridesByRuleId(extraId)
                } else {
                    dao.upsertRule(
                        extraDomain.copy(
                            weeks = remainingWeeks,
                            userModified = true
                        ).toEntity()
                    )
                    for (ov in allOverrides) {
                        if (ov.courseRuleId == extraId && ov.reason == "MERGED_INTO:${primaryRule.id}") {
                            dao.deleteOccurrenceOverrideById(ov.id)
                        }
                    }
                }
            }
            }
        }
        notifyScheduleOrPolicyChanged("USER_COURSE_EDIT")
    }

    suspend fun addManualCourseWithRule(
        termId: String,
        name: String,
        teacher: String?,
        rawRoom: String,
        weekday: DayOfWeek,
        startPeriod: Int,
        endPeriod: Int,
        weeks: Set<Int>,
        colorArgb: Long
    ) {
        // 输入校验：拒绝静默兜底——空周次/越界节次/空课程名一律明确报错，
        // 不把用户的空输入悄悄变成「第 1 节全学期」的错误排课。
        require(name.isNotBlank()) { "课程名称不能为空" }
        require(termId.isNotBlank()) { "学期不能为空" }
        require(startPeriod in 1..12) { "开始节次必须在 1-12 节之间" }
        require(endPeriod in startPeriod..12) { "结束节次必须不小于开始节次且不超过第 12 节" }
        require(weeks.isNotEmpty()) { "周次不能为空，请填写有效周次范围" }
        require(weeks.all { it in 1..30 }) { "周次超出有效范围(1-30)" }
        val courseId = "c_manual_${UUID.randomUUID().toString().take(8)}"
        val ruleId = "r_manual_${UUID.randomUUID().toString().take(8)}"
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val resolved = ClassroomBuildingResolver.resolve(rawRoom, buildings, aliases)

        val course = Course(
            id = courseId,
            name = name,
            teacher = teacher,
            colorArgb = colorArgb,
            source = CourseSource.MANUAL,
            userModified = true,
            lastSyncAt = Instant.now()
        )
        val rule = CourseScheduleRule(
            id = ruleId,
            courseId = courseId,
            termId = termId,
            weekday = weekday,
            startPeriod = startPeriod,
            endPeriod = endPeriod,
            weeks = weeks,
            rawRoom = rawRoom,
            buildingId = if (resolved.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED) {
                resolved.building?.id
            } else null,
            userModified = true
        )
        writeCourseAggregates(
            courseIdsProvider = { setOf(courseId) },
            actor = Actor.USER,
            actorDetail = "manual-course-add"
        ) {
            database.withTransaction {
                dao.upsertCourse(course.toEntity())
                dao.upsertRule(rule.toEntity())
            }
        }
        notifyScheduleOrPolicyChanged("MANUAL_COURSE_ADDED")
    }


    suspend fun deleteCourseScheduleRule(ruleId: String) {
        deleteCourseScheduleRules(listOf(ruleId))
    }

    @JvmOverloads
    suspend fun deleteCourseScheduleRules(
        ruleIds: List<String>,
        contextDate: LocalDate? = null
    ) {
        val seedIds = ruleIds.distinct().filter { it.isNotBlank() }.toSet()
        if (seedIds.isEmpty()) return
        val contextDateIso = contextDate?.toString()
        writeCourseAggregates(
            courseIdsProvider = {
                val overrides = dao.getOccurrenceOverridesSnapshot()
                val cleanIds = (seedIds + absorbedCompanionRuleIds(overrides, seedIds, contextDateIso)).toSet()
                courseIdsForRules(cleanIds)
            },
            actor = Actor.USER,
            actorDetail = "course-rule-delete"
        ) {
            database.withTransaction {
            val allOverrides = dao.getOccurrenceOverridesSnapshot()
            // 仅当存在明确卡片日期上下文（contextDateIso）时才扩展该日被吸收的合并成员，严禁跨日期全局扩展误删其他周次独立排课！
            val absorbedCompanionIds = absorbedCompanionRuleIds(allOverrides, seedIds, contextDateIso)
            val cleanIds = (seedIds + absorbedCompanionIds).toSet()
            val rules = dao.getRulesSnapshot()
            val targetRules = rules.filter { it.id in cleanIds }
            if (targetRules.isEmpty()) return@withTransaction
            val affectedCourseIds = targetRules.map { it.courseId }.toSet()
            // 记录将被规则删除波及的调课源行 moveId：若删除后该调课不再有任何 SOURCE，
            // 其 TARGET 沦为无源孤儿行（会凭空复活一节课），必须同事务内一并清理。
            val moveIdsLosingSources = allOverrides
                .filter { ov ->
                    !ov.moveId.isNullOrBlank() &&
                        ov.moveRole == OccurrenceOverride.MOVE_ROLE_SOURCE &&
                        ov.courseRuleId != null && ov.courseRuleId in cleanIds
                }
                .associate { it.moveId!! to it.termId }
            for (rId in cleanIds) {
                dao.deleteRuleById(rId)
                dao.deleteOccurrenceOverridesByRuleId(rId)
            }
            for ((mid, termOfMove) in moveIdsLosingSources) {
                val remaining = dao.getOccurrenceOverridesForMove(termOfMove, mid)
                val hasSurvivingSource = remaining.any {
                    it.moveRole == OccurrenceOverride.MOVE_ROLE_SOURCE && it.courseRuleId != null
                }
                if (!hasSurvivingSource) {
                    for (orphan in remaining) {
                        dao.deleteOccurrenceOverrideById(orphan.id)
                    }
                }
            }
            for (ov in allOverrides) {
                if (cleanIds.any { id -> ov.reason == "MERGED_INTO:$id" }) {
                    dao.deleteOccurrenceOverrideById(ov.id)
                }
            }
            val coursesById = dao.getCoursesSnapshot().associateBy { it.id }
            for (cId in affectedCourseIds) {
                val remainingForCourse = rules.count { it.courseId == cId && it.id !in cleanIds }
                if (remainingForCourse == 0) {
                    val course = coursesById[cId]
                    if (course != null && (
                            course.source == CourseSource.MANUAL.name ||
                                course.externalId?.startsWith("sjk:") == true
                            )
                    ) {
                        val extId = course.externalId.orEmpty()
                        if (extId.startsWith("sjk:")) {
                            val rest = extId.removePrefix("sjk:")
                            val colonIdx = rest.indexOf(':')
                            if (colonIdx > 0 && colonIdx < rest.lastIndex) {
                                val pracTermId = rest.substring(0, colonIdx)
                                val pracSourceKey = rest.substring(colonIdx + 1)
                                val lastRuleWeeks = targetRules
                                    .firstOrNull { it.courseId == cId }
                                    ?.toDomain()
                                    ?.weeks
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { wks ->
                                        val sorted = wks.sorted()
                                        if (sorted.last() - sorted.first() + 1 == sorted.size && sorted.size > 1) {
                                            "${sorted.first()}-${sorted.last()}周"
                                        } else {
                                            "${sorted.joinToString(",")}周"
                                        }
                                    }
                                    .orEmpty()
                                dao.upsertUnscheduledCourses(
                                    listOf(
                                        UnscheduledAcademicCourseEntity(
                                            termId = pracTermId,
                                            sourceKey = pracSourceKey,
                                            sourceId = null,
                                            name = course.name,
                                            teacher = course.teacher,
                                            creditPlain = course.creditPlain,
                                            rawWeeks = lastRuleWeeks,
                                            category = "实践/集中课",
                                            syncedAtEpochMilli = System.currentTimeMillis()
                                        )
                                    )
                                )
                            }
                        }
                        dao.deleteCourseById(course.id)
                    }
                }
            }
            }
        }
        notifyScheduleOrPolicyChanged("COURSE_RULE_DELETED")
    }

    /**
     * 为「时间待定」的实践环节课程设置排课时间与地点：
     * - 绑定原课程标识（`sourceKey` / `name`），避免产生无关联的重复课程；
     * - 标记 `userModified = true`，确保后续教务同步不会静默覆盖或再次退回待定列表。
     */
    suspend fun scheduleUnscheduledPracticalCourse(
        unscheduled: UnscheduledAcademicCourseEntity,
        rawRoom: String,
        weekday: DayOfWeek,
        startPeriod: Int,
        endPeriod: Int,
        weeks: Set<Int>,
        colorArgb: Long = 0xFF0D9488
    ) {
        require(unscheduled.termId.isNotBlank()) { "实践课学期不能为空" }
        require(unscheduled.sourceKey.isNotBlank()) { "实践课来源标识不能为空" }
        require(startPeriod in 1..12) { "开始节次必须在 1-12 节之间" }
        require(endPeriod in startPeriod..12) { "结束节次必须不小于开始节次且不超过第 12 节" }
        require(weeks.isNotEmpty()) { "周次不能为空，请填写有效周次范围" }
        require(weeks.all { it in 1..30 }) { "周次超出有效范围(1-30)" }
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val cleanRoom = rawRoom.trim().ifBlank { "实践场地" }
        val resolved = ClassroomBuildingResolver.resolve(cleanRoom, buildings, aliases)
        val now = Instant.now()
        val stablePracticalExternalId = "sjk:${unscheduled.termId}:${unscheduled.sourceKey}"
        val fallbackCourseId = "c_prac_${UUID.randomUUID().toString().take(8)}"

        writeCourseAggregates(
            courseIdsProvider = {
                val existingId = dao.getCoursesSnapshot().firstOrNull { row ->
                    row.source == CourseSource.OFFICIAL_JW.name && row.externalId == stablePracticalExternalId
                }?.id
                setOf(existingId ?: fallbackCourseId)
            },
            actor = Actor.USER,
            actorDetail = "practical-course-schedule"
        ) {
            database.withTransaction {
            val existingCourse = dao.getCoursesSnapshot()
                .map { it.toDomain() }
                .firstOrNull { c ->
                    c.source == CourseSource.OFFICIAL_JW && c.externalId == stablePracticalExternalId
                }
            val courseId = existingCourse?.id ?: fallbackCourseId
            val course = existingCourse?.copy(
                teacher = unscheduled.teacher?.takeIf { it.isNotBlank() } ?: existingCourse.teacher,
                credit = unscheduled.creditPlain?.toBigDecimalOrNull() ?: existingCourse.credit,
                userModified = true,
                lastSyncAt = now
            ) ?: Course(
                id = courseId,
                externalId = stablePracticalExternalId,
                name = unscheduled.name,
                teacher = unscheduled.teacher?.takeIf { it.isNotBlank() },
                colorArgb = colorArgb,
                credit = unscheduled.creditPlain?.toBigDecimalOrNull(),
                source = CourseSource.OFFICIAL_JW,
                userModified = true,
                lastSyncAt = now
            )
            val rule = CourseScheduleRule(
                id = "r_prac_${UUID.randomUUID().toString().take(8)}",
                courseId = courseId,
                termId = unscheduled.termId,
                weekday = weekday,
                startPeriod = startPeriod,
                endPeriod = endPeriod,
                weeks = weeks,
                rawRoom = cleanRoom,
                buildingId = resolved.building?.id,
                userModified = true
            )
            dao.upsertCourse(course.toEntity())
            // 幂等保护：完全相同的目标时段已存在时不重复插入（防止双击/重复提交产生重复排课），
            // 不同时段的另一次排课仍正常追加。
            val identicalSlotExists = dao.getRulesSnapshot().any { r ->
                r.courseId == courseId && r.termId == unscheduled.termId &&
                    r.weekdayIso == weekday.value &&
                    r.startPeriod == startPeriod && r.endPeriod == endPeriod &&
                    r.weeksCsv == weeks.sorted().joinToString(",")
            }
            if (!identicalSlotExists) {
                dao.upsertRule(rule.toEntity())
            }
            dao.deleteUnscheduledCourseByKey(unscheduled.termId, unscheduled.sourceKey)
            }
        }
        notifyScheduleOrPolicyChanged("PRACTICAL_COURSE_SCHEDULED")
    }

    /**
     * 以「周次 × 星期 × 节次/自定义时间 × 地点」矩阵一次性设置实践课多日排课。
     * 与 legacy 单条追加入口不同，本入口把该实践课视作一个完整用户编辑矩阵：
     * 未变化的规则保留原 ruleId（保留单日覆盖关联），移除的规则连同其规则级覆盖删除，
     * 全部校验、课程行、规则增删与待定项清除都在同一 Room 事务内完成。
     */
    suspend fun scheduleUnscheduledPracticalCourseWithOccurrences(
        unscheduled: UnscheduledAcademicCourseEntity,
        occurrences: List<OccurrenceRule>,
        colorArgb: Long = 0xFF0D9488
    ) {
        require(unscheduled.termId.isNotBlank()) { "实践课学期不能为空" }
        require(unscheduled.sourceKey.isNotBlank()) { "实践课来源标识不能为空" }
        val validOccurrences = PeriodTimeEditor.requireValidOccurrenceRules(occurrences)
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val now = Instant.now()
        val stablePracticalExternalId = "sjk:${unscheduled.termId}:${unscheduled.sourceKey}"
        val fallbackCourseId = "c_prac_${UUID.randomUUID().toString().take(8)}"

        fun fingerprint(
            weekdayIso: Int,
            startPeriod: Int,
            endPeriod: Int,
            weeksCsv: String,
            rawRoom: String,
            customStartTimeIso: String?,
            customEndTimeIso: String?
        ): String = listOf(
            weekdayIso,
            startPeriod,
            endPeriod,
            weeksCsv,
            rawRoom.trim(),
            customStartTimeIso.orEmpty(),
            customEndTimeIso.orEmpty()
        ).joinToString("|")

        writeCourseAggregates(
            courseIdsProvider = {
                val existingId = dao.getCoursesSnapshot().firstOrNull { row ->
                    row.source == CourseSource.OFFICIAL_JW.name && row.externalId == stablePracticalExternalId
                }?.id
                setOf(existingId ?: fallbackCourseId)
            },
            actor = Actor.USER,
            actorDetail = "practical-course-matrix-schedule"
        ) {
            database.withTransaction {
            val existingCourse = dao.getCoursesSnapshot()
                .map { it.toDomain() }
                .firstOrNull { c ->
                    c.source == CourseSource.OFFICIAL_JW && c.externalId == stablePracticalExternalId
                }
            val courseId = existingCourse?.id ?: fallbackCourseId
            val course = existingCourse?.copy(
                teacher = unscheduled.teacher?.takeIf { it.isNotBlank() } ?: existingCourse.teacher,
                credit = unscheduled.creditPlain?.toBigDecimalOrNull() ?: existingCourse.credit,
                userModified = true,
                lastSyncAt = now
            ) ?: Course(
                id = courseId,
                externalId = stablePracticalExternalId,
                name = unscheduled.name,
                teacher = unscheduled.teacher?.takeIf { it.isNotBlank() },
                colorArgb = colorArgb,
                credit = unscheduled.creditPlain?.toBigDecimalOrNull(),
                source = CourseSource.OFFICIAL_JW,
                userModified = true,
                lastSyncAt = now
            )

            val existingRuleEntities = dao.getRulesSnapshot()
                .filter { it.courseId == courseId && it.termId == unscheduled.termId }
            val existingByFingerprint = existingRuleEntities.associateBy { entity ->
                fingerprint(
                    entity.weekdayIso,
                    entity.startPeriod,
                    entity.endPeriod,
                    entity.weeksCsv,
                    entity.rawRoom,
                    entity.customStartTimeIso,
                    entity.customEndTimeIso
                )
            }

            val desired = validOccurrences.map { occurrence ->
                val cleanRoom = occurrence.rawRoom.trim()
                val resolved = ClassroomBuildingResolver.resolve(cleanRoom, buildings, aliases)
                val weeksCsv = occurrence.weeks.sorted().joinToString(",")
                val key = fingerprint(
                    occurrence.weekday.value,
                    occurrence.startPeriod,
                    occurrence.endPeriod,
                    weeksCsv,
                    cleanRoom,
                    occurrence.customStartTime?.toString(),
                    occurrence.customEndTime?.toString()
                )
                val preservedId = existingByFingerprint[key]?.id
                key to CourseScheduleRule(
                    id = preservedId ?: "r_prac_${UUID.randomUUID().toString().take(8)}",
                    courseId = courseId,
                    termId = unscheduled.termId,
                    weekday = occurrence.weekday,
                    startPeriod = occurrence.startPeriod,
                    endPeriod = occurrence.endPeriod,
                    weeks = occurrence.weeks,
                    rawRoom = cleanRoom,
                    buildingId = resolved.building?.id,
                    customStartTime = occurrence.customStartTime,
                    customEndTime = occurrence.customEndTime,
                    userModified = true
                )
            }
            val desiredIds = desired.map { it.second.id }.toSet()
            val removedRuleIds = existingRuleEntities.mapNotNull { old ->
                old.id.takeIf { it !in desiredIds }
            }.toSet()
            val allOverrides = dao.getOccurrenceOverridesSnapshot()
            // 与通用规则删除入口同一安全口径：若矩阵编辑移除调课 SOURCE 所属规则，
            // 同事务清理失去全部 SOURCE 的 TARGET，避免旧调入课在新矩阵外凭空复活。
            val moveIdsLosingSources = allOverrides
                .filter { ov ->
                    !ov.moveId.isNullOrBlank() &&
                        ov.moveRole == OccurrenceOverride.MOVE_ROLE_SOURCE &&
                        ov.courseRuleId != null && ov.courseRuleId in removedRuleIds
                }
                .associate { it.moveId!! to it.termId }
            for (old in existingRuleEntities) {
                if (old.id in removedRuleIds) {
                    dao.deleteRuleById(old.id)
                    dao.deleteOccurrenceOverridesByRuleId(old.id)
                }
            }
            for ((moveId, termOfMove) in moveIdsLosingSources) {
                val remaining = dao.getOccurrenceOverridesForMove(termOfMove, moveId)
                val hasSurvivingSource = remaining.any {
                    it.moveRole == OccurrenceOverride.MOVE_ROLE_SOURCE && it.courseRuleId != null
                }
                if (!hasSurvivingSource) {
                    for (orphan in remaining) {
                        dao.deleteOccurrenceOverrideById(orphan.id)
                    }
                }
            }

            dao.upsertCourse(course.toEntity())
            for ((_, rule) in desired) {
                dao.upsertRule(rule.toEntity())
            }
            dao.deleteUnscheduledCourseByKey(unscheduled.termId, unscheduled.sourceKey)
            }
        }
        notifyScheduleOrPolicyChanged("PRACTICAL_COURSE_SCHEDULED")
    }


    @JvmOverloads
    suspend fun confirmBuildingAliasForRoom(
        rawRoom: String,
        buildingId: String,
        activeCampusId: String? = null
    ) {
        val cleanRoom = rawRoom.trim()
        if (cleanRoom.isEmpty() || buildingId.isBlank()) return
        val allBuildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val targetBuilding = allBuildings.firstOrNull { it.id == buildingId } ?: return

        // 1) 跨校区保护：若教室文本或当前上下文具备明确校区，禁止将别名跨校区绑定到另一校区楼宇
        val inlineCampus = ClassroomBuildingResolver.detectCampusHint(listOf(cleanRoom))?.value
        val effectiveCampus = inlineCampus ?: activeCampusId?.trim()?.takeIf { it.isNotEmpty() }
        if (effectiveCampus != null) {
            val contextIsSongshan = effectiveCampus.contains("songshan", ignoreCase = true) ||
                effectiveCampus.contains("嵩山") ||
                effectiveCampus.contains("齐礼阎")
            val targetIsSongshan = targetBuilding.campusId.contains("songshan", ignoreCase = true)
            if (contextIsSongshan != targetIsSongshan) {
                return
            }
        }

        // 2) 宿舍片区保护：公寓片区通用点（DORMITORY，如学生公寓E/F区）只能保存为近似参考（verified=false），严禁升级为精确已核验楼栋！
        val isDormitoryZone = targetBuilding.category == cn.edu.haut.campus.core.model.CampusCategory.DORMITORY
        val alias = BuildingAlias(
            id = "al_user_${cleanRoom.hashCode()}",
            buildingId = buildingId,
            rawNameOrPattern = cleanRoom,
            confidence = if (isDormitoryZone) 0.74f else 1.0f,
            verified = !isDormitoryZone
        )
        dao.upsertBuildingAliases(listOf(alias.toEntity()))
    }

    suspend fun previewCourseImportDiff(
        termId: String,
        source: CourseSource,
        candidates: List<ImportCourseCandidate>
    ): ImportDiffPreview {
        val existingCourses = dao.getCoursesSnapshot().map { it.toDomain() }
        val existingRules = dao.getRulesSnapshot().map { it.toDomain() }
        return ImportDiffEngine.computeDiff(
            termId = termId,
            source = source,
            existingCourses = existingCourses,
            existingRules = existingRules,
            incomingCandidates = candidates
        )
    }

    /**
     * 确认提交课表导入结果。
     * @param overwriteUserConflicts 若为 false（默认），所有 CONFLICT_USER_MODIFIED 项均保留用户本地修改。
     * @param setAsCurrentTerm 若为 true（默认），同时将目标学期切换为当前激活学期。
     */
    suspend fun commitCourseImportDiff(
        preview: ImportDiffPreview,
        overwriteUserConflicts: Boolean = false,
        setAsCurrentTerm: Boolean = true
    ) = commitCourseImportDiffWithActor(
        preview = preview,
        overwriteUserConflicts = overwriteUserConflicts,
        setAsCurrentTerm = setAsCurrentTerm,
        actor = Actor.SYNC
    )

    /** 与兼容签名分离的显式 actor 入口；自动同步使用 SYNC，用户确认的导入 UI 使用 USER。 */
    suspend fun commitCourseImportDiffWithActor(
        preview: ImportDiffPreview,
        overwriteUserConflicts: Boolean = false,
        setAsCurrentTerm: Boolean = true,
        actor: Actor
    ) {
        val palette = listOf(
            0xFF0072BC, 0xFF0284C7, 0xFF0D9488, 0xFF16A34A,
            0xFF4F46E5, 0xFF7C3AED, 0xFFD97706, 0xFFE11D48
        )
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val now = Instant.now()
        val addedIds = preview.entries.mapIndexedNotNull { index, entry ->
            if (entry.changeType == ImportChangeType.ADDED && entry.incoming != null) {
                index to (
                    "c_imp_${UUID.randomUUID().toString().take(8)}" to
                        "r_imp_${UUID.randomUUID().toString().take(8)}"
                    )
            } else {
                null
            }
        }.toMap()

        termRepository.ensureTermExistsAndUpdateCalendar(
            termId = preview.termId,
            setCurrent = setAsCurrentTerm
        )

        writeCourseAggregates(
            courseIdsProvider = {
                (preview.entries.mapNotNull { it.existingCourse?.id } + addedIds.values.map { it.first }).distinct()
            },
            actor = actor,
            actorDetail = "course-import-commit",
            writeGroupId = "course-import:" + UUID.randomUUID().toString()
        ) {
            database.withTransaction {
            var colorIdx = 0
            for ((entryIndex, entry) in preview.entries.withIndex()) {
                val candidate = entry.incoming
                when (entry.changeType) {
                    ImportChangeType.ADDED -> {
                        if (candidate != null) {
                            val plannedIds = checkNotNull(addedIds[entryIndex]) { "ADDED 导入项缺少预分配 ID" }
                            val courseId = plannedIds.first
                            val ruleId = plannedIds.second
                            val resolved = ClassroomBuildingResolver.resolve(candidate.rawRoom, buildings, aliases)
                            val course = Course(
                                id = courseId,
                                externalId = candidate.externalId,
                                name = candidate.courseName,
                                teacher = candidate.teacher,
                                colorArgb = palette[colorIdx++ % palette.size],
                                credit = candidate.credit,
                                source = preview.source,
                                userModified = false,
                                lastSyncAt = now
                            )
                            val rule = CourseScheduleRule(
                                id = ruleId,
                                courseId = courseId,
                                termId = preview.termId,
                                weekday = candidate.weekday,
                                startPeriod = candidate.startPeriod,
                                endPeriod = candidate.endPeriod,
                                weeks = candidate.weeks,
                                rawRoom = candidate.rawRoom,
                                buildingId = if (resolved.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED) {
                                    resolved.building?.id
                                } else null,
                                userModified = false
                            )
                            dao.upsertCourse(course.toEntity())
                            dao.upsertRule(rule.toEntity())
                        }
                    }
                    ImportChangeType.MODIFIED -> {
                        val existingCourse = entry.existingCourse
                        val existingRule = entry.existingRule
                        if (candidate != null && existingCourse != null && existingRule != null) {
                            val resolved = ClassroomBuildingResolver.resolve(candidate.rawRoom, buildings, aliases)
                            dao.upsertCourse(
                                existingCourse.copy(
                                    name = candidate.courseName,
                                    teacher = candidate.teacher,
                                    credit = candidate.credit ?: existingCourse.credit,
                                    lastSyncAt = now
                                ).toEntity()
                            )
                            dao.upsertRule(
                                existingRule.copy(
                                    weeks = candidate.weeks,
                                    rawRoom = candidate.rawRoom,
                                    buildingId = when {
                                        resolved.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED ->
                                            resolved.building?.id
                                        candidate.rawRoom == existingRule.rawRoom ->
                                            existingRule.buildingId
                                        else -> null
                                    }
                                ).toEntity()
                            )
                        }
                    }
                    ImportChangeType.CONFLICT_USER_MODIFIED -> {
                        val existingCourse = entry.existingCourse
                        val existingRule = entry.existingRule
                        if (overwriteUserConflicts && candidate != null && existingCourse != null && existingRule != null) {
                            dao.upsertCourse(
                                existingCourse.copy(
                                    name = candidate.courseName,
                                    teacher = candidate.teacher,
                                    userModified = false,
                                    lastSyncAt = now
                                ).toEntity()
                            )
                            dao.upsertRule(
                                existingRule.copy(
                                    weeks = candidate.weeks,
                                    rawRoom = candidate.rawRoom,
                                    userModified = false
                                ).toEntity()
                            )
                        }
                        // 默认保留用户本地修改，不做覆盖
                    }
                    ImportChangeType.REMOVED_FROM_REMOTE -> {
                        val existingRule = entry.existingRule
                        if (existingRule != null && (!existingRule.userModified || overwriteUserConflicts)) {
                            dao.deleteRuleById(existingRule.id)
                        }
                    }
                    ImportChangeType.UNCHANGED -> Unit
                }
            }

            val removedCount = preview.entries.count { it.changeType == ImportChangeType.REMOVED_FROM_REMOTE }
            dao.insertImportAudit(
                ImportAudit(
                    id = "aud_${UUID.randomUUID().toString().take(8)}",
                    termId = preview.termId,
                    importSource = preview.source,
                    importedAt = now,
                    rowCount = preview.entries.size,
                    conflictCount = preview.conflictUserEditCount,
                    outcome = "新增${preview.addedCount}/更新${preview.modifiedCount}/移除${removedCount}/保留用户修改${preview.conflictUserEditCount}"
                ).toEntity()
            )
            }
        }
        preferences.edit {
            it[CampusRepositoryPrefKeys.ONBOARDING_COMPLETED] = true
            it[CampusRepositoryPrefKeys.CLEAN_BASE_V2] = true
            if (setAsCurrentTerm) {
                it[CampusRepositoryPrefKeys.LAST_ACADEMIC_DATA_SYNC_MS] = System.currentTimeMillis()
                it[CampusRepositoryPrefKeys.LAST_ACADEMIC_DATA_SYNC_TERM_ID] = preview.termId
                val (xnm, xqm) = HautAcademicEndpoints.fromTermId(preview.termId)
                it[CampusRepositoryPrefKeys.SAVED_SYNC_XNM] = xnm
                it[CampusRepositoryPrefKeys.SAVED_SYNC_XQM] = xqm
            }
        }
        notifyScheduleOrPolicyChanged("COURSE_IMPORT_COMMITTED")
    }


    suspend fun getDirectScheduleDataSnapshot(): DirectScheduleData {
        termRepository.ensureInitialSeedIfNeeded()
        val term = dao.getCurrentTerm()?.toDomain()
        val courses = dao.getCoursesSnapshot().map { it.toDomain() }
        val rules = dao.getRulesSnapshot().map { it.toDomain() }
        val periods = dao.getPeriodTimesSnapshot().map { it.toDomain() }
        val calOverrides = dao.getCalendarOverridesSnapshot().map { it.toDomain() }
        val occOverrides = dao.getOccurrenceOverridesSnapshot().map { it.toDomain() }
        val buildings = (dao.getBuildingsSnapshot().map { it.toDomain() }
            .filter { it.id !in CampusDiningRecommender.CLOSED_CANTEEN_BUILDING_IDS } +
            (if (supportsHautServices) ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS else emptyList()))
            .distinctBy { it.id }
        return DirectScheduleData(
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periods,
            calendarOverrides = calOverrides,
            occurrenceOverrides = occOverrides,
            buildings = buildings
        )
    }

    suspend fun saveOccurrenceOverride(override: OccurrenceOverride) = writeCourseAggregates(
        courseIdsProvider = {
            override.courseId?.let(::setOf)
                ?: override.courseRuleId?.let { courseIdsForRules(setOf(it)) }.orEmpty()
        },
        actor = Actor.USER,
        actorDetail = "occurrence-override-save"
    ) {
        occurrenceStore.saveOccurrenceOverride(override)
    }

    suspend fun saveSingleDayOccurrenceEdit(
        occ: cn.edu.haut.campus.core.model.ClassOccurrence,
        termId: String,
        newStartPeriod: Int,
        newEndPeriod: Int,
        newRoom: String,
        reason: String = "单次调课"
    ) = writeCourseAggregates(
        courseIdsProvider = { setOf(occ.courseId) },
        actor = Actor.USER,
        actorDetail = "occurrence-single-day-edit"
    ) {
        occurrenceStore.saveSingleDayOccurrenceEdit(
            occ = occ,
            termId = termId,
            newStartPeriod = newStartPeriod,
            newEndPeriod = newEndPeriod,
            newRoom = newRoom,
            reason = reason
        )
    }

    suspend fun resetSingleDayOccurrenceEdit(occ: cn.edu.haut.campus.core.model.ClassOccurrence) = writeCourseAggregates(
        courseIdsProvider = { setOf(occ.courseId) },
        actor = Actor.USER,
        actorDetail = "occurrence-single-day-reset"
    ) {
        occurrenceStore.resetSingleDayOccurrenceEdit(occ)
    }

    suspend fun cancelClassOccurrenceOnDate(
        occ: cn.edu.haut.campus.core.model.ClassOccurrence,
        termId: String,
        reason: String = "单次停课"
    ) = writeCourseAggregates(
        courseIdsProvider = { setOf(occ.courseId) },
        actor = Actor.USER,
        actorDetail = "occurrence-cancel"
    ) {
        occurrenceStore.cancelClassOccurrenceOnDate(
            occ = occ,
            termId = termId,
            reason = reason
        )
    }

    suspend fun rescheduleSingleOccurrenceCrossDate(
        occ: cn.edu.haut.campus.core.model.ClassOccurrence,
        termId: String,
        targetDate: LocalDate,
        newStartPeriod: Int,
        newEndPeriod: Int,
        newRoom: String,
        reason: String? = null
    ) = writeCourseAggregates(
        courseIdsProvider = { setOf(occ.courseId) },
        actor = Actor.USER,
        actorDetail = "occurrence-cross-date-reschedule"
    ) {
        occurrenceStore.rescheduleSingleOccurrenceCrossDate(
            occ = occ,
            termId = termId,
            targetDate = targetDate,
            newStartPeriod = newStartPeriod,
            newEndPeriod = newEndPeriod,
            newRoom = newRoom,
            reason = reason
        )
    }

    suspend fun deleteOccurrenceOverride(overrideId: String) = writeCourseAggregates(
        courseIdsProvider = { courseIdsForOverrides(setOf(overrideId)) },
        actor = Actor.USER,
        actorDetail = "occurrence-override-delete"
    ) {
        occurrenceStore.deleteOccurrenceOverride(overrideId)
    }

    suspend fun unpauseOccurrence(
        termId: String,
        overrideId: String,
        expectedMoveId: String? = null
    ): CampusRepository.OccurrenceCommandResult = writeCourseAggregates(
        courseIdsProvider = { courseIdsForOverrides(setOf(overrideId)) },
        actor = Actor.USER,
        actorDetail = "occurrence-unpause"
    ) {
        restoreCommandService.unpauseOccurrence(
            termId = termId,
            overrideId = overrideId,
            expectedMoveId = expectedMoveId
        )
    }

    suspend fun undoMove(
        termId: String,
        moveId: String
    ): CampusRepository.OccurrenceCommandResult = writeCourseAggregates(
        courseIdsProvider = { courseIdsForMoveIds(setOf(moveId)) },
        actor = Actor.USER,
        actorDetail = "occurrence-move-undo"
    ) {
        restoreCommandService.undoMove(termId, moveId)
    }

    suspend fun restoreRuleCancellation(
        termId: String,
        ruleIds: List<String>,
        date: LocalDate,
        expectedMoveId: String? = null
    ): CampusRepository.OccurrenceCommandResult = writeCourseAggregates(
        courseIdsProvider = {
            val directCourseIds = dao.getOccurrenceOverridesSnapshot()
                .filter { it.courseRuleId in ruleIds && it.dateIso == date.toString() }
                .mapNotNullTo(mutableSetOf()) { it.courseId }
            courseIdsForRules(ruleIds) + directCourseIds
        },
        actor = Actor.USER,
        actorDetail = "occurrence-rule-cancellation-restore"
    ) {
        restoreCommandService.restoreRuleCancellation(
            termId = termId,
            ruleIds = ruleIds,
            date = date,
            expectedMoveId = expectedMoveId
        )
    }

    suspend fun executeRestoreCommand(command: CampusRepository.RestoreCommand): CampusRepository.OccurrenceCommandResult =
        writeCourseAggregates(
            courseIdsProvider = {
                courseIdsForOverrides(command.selectedRows.map { it.id }.toSet()) +
                    courseIdsForMoveIds(command.selectedMoves.map { it.moveId }.toSet())
            },
            actor = Actor.USER,
            actorDetail = "occurrence-restore-command:" + command.intent.name,
            writeGroupId = "occurrence-restore-command:" + command.commandId
        ) {
            restoreCommandService.executeRestoreCommand(command)
        }

    suspend fun appendLegacyRestoreReviewTargets(targets: List<OccurrenceOverride>) =
        legacyRestoreReviewJournalStore.append(targets)

    suspend fun removeLegacyRestoreReviewTarget(overrideId: String) =
        legacyRestoreReviewJournalStore.remove(overrideId)

    suspend fun clearLegacyRestoreReviewTargets() =
        legacyRestoreReviewJournalStore.clear()

    @Deprecated(
        "模糊选择已于 R5 废止：改用 executeRestoreCommand(RestoreCommand) 提交点击时冻结的完整不可变命令"
    )
    suspend fun restoreCanceledOccurrence(
        date: LocalDate,
        ruleId: String?,
        overrideId: String? = null,
        mergedRuleIds: List<String> = emptyList()
    ): CampusRepository.OccurrenceRestoreResult = writeCourseAggregates(
        courseIdsProvider = {
            val ruleCourseIds = courseIdsForRules((listOfNotNull(ruleId) + mergedRuleIds).toSet())
            val overrideCourseIds = overrideId?.let { courseIdsForOverrides(setOf(it)) }.orEmpty()
            ruleCourseIds + overrideCourseIds
        },
        actor = Actor.USER,
        actorDetail = "occurrence-restore-legacy-adapter"
    ) {
        restoreCommandService.restoreCanceledOccurrence(
            date = date,
            ruleId = ruleId,
            overrideId = overrideId,
            mergedRuleIds = mergedRuleIds
        )
    }
}
