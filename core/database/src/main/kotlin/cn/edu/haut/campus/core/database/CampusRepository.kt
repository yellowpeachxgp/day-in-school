package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.edu.haut.campus.core.academic.HautLiveAcademicClient
import cn.edu.haut.campus.core.academic.HautLiveSyncResult
import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.domain.ImportDiffEngine
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingAlias
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.ImportAudit
import cn.edu.haut.campus.core.model.ImportChangeType
import cn.edu.haut.campus.core.model.ImportCourseCandidate
import cn.edu.haut.campus.core.model.ImportDiffPreview
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.WidgetInstanceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private val Context.appDataStore by preferencesDataStore(name = "haut_campus_prefs")

data class AppPreferencesState(
    val gpaPrivacyShieldEnabled: Boolean = false,
    val useDynamicColor: Boolean = false,
    val timelineZoomScale: Float = 2.0f,
    val officialGpaValuePlain: String? = null,
    val officialGpaNote: String? = null,
    val hasSeededInitialMockData: Boolean = false,
    val hasCompletedOnboarding: Boolean = false,
    val autoSyncEnabled: Boolean = true,
    val savedStudentId: String? = null,
    val hasSavedCredentials: Boolean = false,
    val syncXnm: String = "2025",
    val syncXqm: String = "12",
    val lastAutoSyncEpochMillis: Long? = null
)

class CampusRepository private constructor(
    private val appContext: Context,
    private val database: HautDatabase
) {
    private val dao = database.campusDao()
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object PrefKeys {
        val SEEDED = booleanPreferencesKey("seeded_initial_mock_data")
        val CLEAN_BASE_V2 = booleanPreferencesKey("clean_base_dictionary_initialized_v3")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("completed_onboarding_guide_v1")
        val GPA_PRIVACY = booleanPreferencesKey("gpa_privacy_shield_enabled")
        val DYNAMIC_COLOR = booleanPreferencesKey("use_material_you_dynamic_color")
        val TIMELINE_SCALE = floatPreferencesKey("timeline_zoom_scale")
        val OFFICIAL_GPA = stringPreferencesKey("official_gpa_value")
        val OFFICIAL_GPA_NOTE = stringPreferencesKey("official_gpa_note")
        val AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_jw_enabled")
        val SAVED_STUDENT_ID = stringPreferencesKey("saved_jw_student_id")
        val SAVED_PASSWORD_OBF = stringPreferencesKey("saved_jw_password_local")
        val SAVED_SYNC_XNM = stringPreferencesKey("saved_jw_sync_xnm")
        val SAVED_SYNC_XQM = stringPreferencesKey("saved_jw_sync_xqm")
        val LAST_AUTO_SYNC_MS = longPreferencesKey("last_auto_sync_epoch_millis")
    }

    init {
        repoScope.launch {
            ensureInitialSeedIfNeeded()
        }
    }

    val preferencesFlow: Flow<AppPreferencesState> = appContext.appDataStore.data.map { prefs ->
        AppPreferencesState(
            gpaPrivacyShieldEnabled = prefs[PrefKeys.GPA_PRIVACY] ?: false,
            useDynamicColor = prefs[PrefKeys.DYNAMIC_COLOR] ?: false,
            timelineZoomScale = prefs[PrefKeys.TIMELINE_SCALE] ?: 2.0f,
            officialGpaValuePlain = prefs[PrefKeys.OFFICIAL_GPA],
            officialGpaNote = prefs[PrefKeys.OFFICIAL_GPA_NOTE],
            hasSeededInitialMockData = prefs[PrefKeys.SEEDED] ?: false,
            hasCompletedOnboarding = prefs[PrefKeys.ONBOARDING_COMPLETED] ?: false,
            autoSyncEnabled = prefs[PrefKeys.AUTO_SYNC_ENABLED] ?: true,
            savedStudentId = prefs[PrefKeys.SAVED_STUDENT_ID],
            hasSavedCredentials = !prefs[PrefKeys.SAVED_STUDENT_ID].isNullOrBlank() &&
                !prefs[PrefKeys.SAVED_PASSWORD_OBF].isNullOrBlank(),
            syncXnm = prefs[PrefKeys.SAVED_SYNC_XNM] ?: "2025",
            syncXqm = prefs[PrefKeys.SAVED_SYNC_XQM] ?: "12",
            lastAutoSyncEpochMillis = prefs[PrefKeys.LAST_AUTO_SYNC_MS]
        )
    }

    val studentProfileFlow: Flow<StudentProfile?> =
        dao.observeStudentProfile().map { it?.toDomain() }

    val termsFlow: Flow<List<Term>> =
        dao.observeTerms().map { list -> list.map { it.toDomain() } }

    val currentTermFlow: Flow<Term?> =
        termsFlow.map { terms -> terms.firstOrNull { it.current } ?: terms.firstOrNull() }

    val periodTimesFlow: Flow<List<PeriodTime>> =
        dao.observePeriodTimes().map { list -> list.map { it.toDomain() } }

    val calendarOverridesFlow: Flow<List<CalendarOverride>> =
        dao.observeCalendarOverrides().map { list -> list.map { it.toDomain() } }

    val coursesFlow: Flow<List<Course>> =
        dao.observeCourses().map { list -> list.map { it.toDomain() } }

    val rulesFlow: Flow<List<CourseScheduleRule>> =
        dao.observeRules().map { list -> list.map { it.toDomain() } }

    val occurrenceOverridesFlow: Flow<List<OccurrenceOverride>> =
        dao.observeOccurrenceOverrides().map { list -> list.map { it.toDomain() } }

    val gradeAttemptsFlow: Flow<List<GradeAttempt>> =
        dao.observeGradeAttempts().map { list -> list.map { it.toDomain() } }

    val buildingsFlow: Flow<List<Building>> =
        dao.observeBuildings().map { list -> list.map { it.toDomain() } }

    val buildingAliasesFlow: Flow<List<BuildingAlias>> =
        dao.observeBuildingAliases().map { list -> list.map { it.toDomain() } }

    val widgetConfigsFlow: Flow<List<WidgetInstanceConfig>> =
        dao.observeWidgetConfigs().map { list -> list.map { it.toDomain() } }

    val importAuditsFlow: Flow<List<ImportAudit>> =
        dao.observeImportAudits().map { list -> list.map { it.toDomain() } }

    val examSchedulesFlow: Flow<List<ExamScheduleItem>> =
        dao.observeExamSchedules().map { list -> list.map { it.toDomain() } }

    /**
     * 首次启动仅初始化公开的校历学期、1–10 节作息时间表与莲花街校区楼宇字典，
     * 绝不自动塞入模拟课程或模拟成绩，确保用户首次打开直接进入干净的欢迎引导页。
     */
    suspend fun ensureInitialSeedIfNeeded() {
        val prefs = appContext.appDataStore.data.first()
        if (prefs[PrefKeys.CLEAN_BASE_V2] == true) return
        database.withTransaction {
            if (prefs[PrefKeys.ONBOARDING_COMPLETED] != true) {
                dao.clearStudentProfile()
                dao.clearCourses()
                dao.clearRules()
                dao.clearOccurrenceOverrides()
                dao.clearGradeAttempts()
            }
            seedBaseCampusDictionaryInternal()
        }
        appContext.appDataStore.edit {
            it[PrefKeys.SEEDED] = true
            it[PrefKeys.CLEAN_BASE_V2] = true
        }
    }

    suspend fun completeOnboarding() {
        appContext.appDataStore.edit {
            it[PrefKeys.ONBOARDING_COMPLETED] = true
            it[PrefKeys.CLEAN_BASE_V2] = true
        }
    }

    suspend fun resetOnboardingForGuide() {
        appContext.appDataStore.edit {
            it[PrefKeys.ONBOARDING_COMPLETED] = false
        }
    }

    suspend fun resetToDemoSeedData() {
        database.withTransaction {
            dao.clearCourses()
            dao.clearRules()
            dao.clearOccurrenceOverrides()
            dao.clearGradeAttempts()
            seedDemoDataInternal()
        }
        appContext.appDataStore.edit {
            it[PrefKeys.SEEDED] = true
            it[PrefKeys.CLEAN_BASE_V2] = true
            it[PrefKeys.ONBOARDING_COMPLETED] = true
            it.remove(PrefKeys.OFFICIAL_GPA)
            it.remove(PrefKeys.OFFICIAL_GPA_NOTE)
        }
    }

    private suspend fun seedBaseCampusDictionaryInternal() {
        dao.upsertTerms(HautMockSeedData.defaultTerms.map { it.toEntity() })
        dao.upsertPeriodTimes(HautMockSeedData.defaultPeriodTimes.map { it.toEntity() })
        dao.upsertBuildings(HautMockSeedData.lianhuajieBuildings.map { it.toEntity() })
        dao.upsertBuildingAliases(HautMockSeedData.defaultBuildingAliases.map { it.toEntity() })
        dao.upsertCalendarOverrides(HautMockSeedData.defaultCalendarOverrides.map { it.toEntity() })
    }

    private suspend fun seedDemoDataInternal() {
        seedBaseCampusDictionaryInternal()
        dao.upsertStudentProfile(HautMockSeedData.defaultStudent.toEntity())
        dao.upsertCourses(HautMockSeedData.defaultCourses.map { it.toEntity() })
        dao.upsertRules(HautMockSeedData.defaultRules.map { it.toEntity() })
        dao.upsertOccurrenceOverrides(HautMockSeedData.defaultOccurrenceOverrides.map { it.toEntity() })
        dao.upsertGradeAttempts(HautMockSeedData.defaultGradeAttempts.map { it.toEntity() })
    }

    suspend fun selectCurrentTerm(termId: String) {
        dao.setCurrentTerm(termId)
    }

    suspend fun updateTermStartDate(termId: String, newStartDate: LocalDate) {
        val terms = dao.observeTerms().first().map { it.toDomain() }
        val target = terms.firstOrNull { it.id == termId } ?: return
        dao.upsertTerms(listOf(target.copy(startDate = newStartDate).toEntity()))
    }

    suspend fun updatePeriodTime(periodTime: PeriodTime) {
        dao.upsertPeriodTimes(listOf(periodTime.toEntity()))
    }

    /**
     * 用户手动编辑课程属性与排课规则：自动标记 userModified = true，确保下次教务同步时不会静默覆盖。
     */
    suspend fun saveUserCourseEdit(
        course: Course,
        rule: CourseScheduleRule
    ) {
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val resolvedBuilding = ClassroomBuildingResolver.resolve(rule.rawRoom, buildings, aliases)
        val verifiedBuildingId = if (resolvedBuilding.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED) {
            resolvedBuilding.building?.id
        } else {
            rule.buildingId
        }

        database.withTransaction {
            dao.upsertCourse(course.copy(userModified = true).toEntity())
            dao.upsertRule(
                rule.copy(
                    buildingId = verifiedBuildingId,
                    userModified = true
                ).toEntity()
            )
        }
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
            buildingId = resolved.building?.id,
            userModified = true
        )
        database.withTransaction {
            dao.upsertCourse(course.toEntity())
            dao.upsertRule(rule.toEntity())
        }
    }

    suspend fun saveOccurrenceOverride(override: OccurrenceOverride) {
        dao.upsertOccurrenceOverride(override.toEntity())
    }

    suspend fun saveCalendarOverride(override: CalendarOverride) {
        dao.upsertCalendarOverrides(listOf(override.toEntity()))
    }

    suspend fun deleteCalendarOverride(id: String) {
        dao.deleteCalendarOverrideById(id)
    }

    suspend fun confirmBuildingAliasForRoom(rawRoom: String, buildingId: String) {
        val alias = BuildingAlias(
            id = "al_user_${rawRoom.hashCode()}",
            buildingId = buildingId,
            rawNameOrPattern = rawRoom.trim(),
            confidence = 1.0f,
            verified = true
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
     */
    suspend fun commitCourseImportDiff(
        preview: ImportDiffPreview,
        overwriteUserConflicts: Boolean = false
    ) {
        val palette = listOf(
            0xFF0072BC, 0xFF0284C7, 0xFF0D9488, 0xFF16A34A,
            0xFF4F46E5, 0xFF7C3AED, 0xFFD97706, 0xFFE11D48
        )
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
        val aliases = dao.getBuildingAliasesSnapshot().map { it.toDomain() }
        val now = Instant.now()

        database.withTransaction {
            var colorIdx = 0
            for (entry in preview.entries) {
                val candidate = entry.incoming
                when (entry.changeType) {
                    ImportChangeType.ADDED -> {
                        if (candidate != null) {
                            val courseId = "c_imp_${UUID.randomUUID().toString().take(8)}"
                            val ruleId = "r_imp_${UUID.randomUUID().toString().take(8)}"
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
                                    buildingId = resolved.building?.id ?: existingRule.buildingId
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
                    ImportChangeType.REMOVED_FROM_REMOTE, ImportChangeType.UNCHANGED -> Unit
                }
            }

            dao.insertImportAudit(
                ImportAudit(
                    id = "aud_${UUID.randomUUID().toString().take(8)}",
                    termId = preview.termId,
                    importSource = preview.source,
                    importedAt = now,
                    rowCount = preview.entries.size,
                    conflictCount = preview.conflictUserEditCount,
                    outcome = "新增${preview.addedCount}/更新${preview.modifiedCount}/保留用户修改${preview.conflictUserEditCount}"
                ).toEntity()
            )
        }
        appContext.appDataStore.edit {
            it[PrefKeys.ONBOARDING_COMPLETED] = true
            it[PrefKeys.CLEAN_BASE_V2] = true
        }
    }

    suspend fun saveImportedGrades(
        attempts: List<GradeAttempt>,
        officialGpa: BigDecimal? = null,
        officialNote: String? = null,
        replaceExisting: Boolean = false
    ) {
        database.withTransaction {
            if (replaceExisting) {
                dao.clearGradeAttempts()
            }
            dao.upsertGradeAttempts(attempts.map { it.toEntity() })
        }
        appContext.appDataStore.edit { prefs ->
            prefs[PrefKeys.ONBOARDING_COMPLETED] = true
            prefs[PrefKeys.CLEAN_BASE_V2] = true
            if (officialGpa != null) {
                prefs[PrefKeys.OFFICIAL_GPA] = officialGpa.toPlainString()
                prefs[PrefKeys.OFFICIAL_GPA_NOTE] = officialNote ?: "正方教务系统返回平均学分绩点"
            } else {
                prefs.remove(PrefKeys.OFFICIAL_GPA)
                prefs.remove(PrefKeys.OFFICIAL_GPA_NOTE)
            }
        }
    }

    suspend fun saveStudentProfile(profile: StudentProfile) {
        dao.upsertStudentProfile(profile.toEntity())
    }

    suspend fun saveImportedExams(
        exams: List<ExamScheduleItem>,
        replaceExisting: Boolean = true
    ) {
        database.withTransaction {
            if (replaceExisting) {
                dao.clearExamSchedules()
            }
            dao.upsertExamSchedules(exams.map { it.toEntity() })
        }
    }

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        appContext.appDataStore.edit { it[PrefKeys.AUTO_SYNC_ENABLED] = enabled }
    }

    suspend fun saveAuthorizedSyncCredentials(
        studentId: String,
        passwordPlain: String,
        xnm: String,
        xqm: String
    ) {
        val encoded = java.util.Base64.getEncoder().encodeToString(
            passwordPlain.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        )
        appContext.appDataStore.edit { prefs ->
            prefs[PrefKeys.SAVED_STUDENT_ID] = studentId.trim()
            prefs[PrefKeys.SAVED_PASSWORD_OBF] = encoded
            prefs[PrefKeys.SAVED_SYNC_XNM] = xnm
            prefs[PrefKeys.SAVED_SYNC_XQM] = xqm
            prefs[PrefKeys.LAST_AUTO_SYNC_MS] = System.currentTimeMillis()
        }
    }

    /**
     * 应用全部实时教务同步数据（学籍名片 + 课表差异合并 + 成绩明细 + 期初/期中/期末全部考试安排）。
     */
    suspend fun applyLiveSyncData(
        syncData: HautLiveSyncResult,
        overwriteUserConflicts: Boolean = false
    ) {
        syncData.studentProfile?.let { profile ->
            saveStudentProfile(profile)
        }
        if (syncData.gradeAttempts.isNotEmpty()) {
            saveImportedGrades(
                attempts = syncData.gradeAttempts,
                replaceExisting = true
            )
        }
        if (syncData.examItems.isNotEmpty()) {
            saveImportedExams(
                exams = syncData.examItems,
                replaceExisting = true
            )
        }
        val targetTermId = dao.getCurrentTerm()?.id ?: syncData.termId
        val diff = previewCourseImportDiff(
            termId = targetTermId,
            source = CourseSource.OFFICIAL_JW,
            candidates = syncData.timetableCandidates
        )
        commitCourseImportDiff(
            preview = diff,
            overwriteUserConflicts = overwriteUserConflicts
        )
        appContext.appDataStore.edit { prefs ->
            prefs[PrefKeys.LAST_AUTO_SYNC_MS] = System.currentTimeMillis()
        }
    }

    /**
     * 后台或一键动态刷新：使用本地已授权保存的教务凭据，从正方教务系统拉取最新课表、期初/期中/期末考试安排与成绩。
     * @param force 若为 false，则距离上次自动同步不足 15 分钟时跳过，避免频繁请求。
     */
    suspend fun performDynamicAcademicSync(force: Boolean = false): Result<HautLiveSyncResult>? {
        val prefs = appContext.appDataStore.data.first()
        val autoEnabled = prefs[PrefKeys.AUTO_SYNC_ENABLED] ?: true
        if (!force && !autoEnabled) return null

        val studentId = prefs[PrefKeys.SAVED_STUDENT_ID]?.takeIf { it.isNotBlank() } ?: return null
        val encodedPwd = prefs[PrefKeys.SAVED_PASSWORD_OBF]?.takeIf { it.isNotBlank() } ?: return null
        val passwordPlain = runCatching {
            String(java.util.Base64.getDecoder().decode(encodedPwd), java.nio.charset.StandardCharsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        val lastSyncMs = prefs[PrefKeys.LAST_AUTO_SYNC_MS] ?: 0L
        val nowMs = System.currentTimeMillis()
        if (!force && (nowMs - lastSyncMs) < 15 * 60 * 1000L) {
            return null
        }

        val xnm = prefs[PrefKeys.SAVED_SYNC_XNM] ?: "2025"
        val xqm = prefs[PrefKeys.SAVED_SYNC_XQM] ?: "12"

        val result = HautLiveAcademicClient.fetchLiveAcademicData(
            studentId = studentId,
            passwordPlain = passwordPlain,
            xnm = xnm,
            xqm = xqm
        )
        result.onSuccess { syncData ->
            applyLiveSyncData(syncData, overwriteUserConflicts = false)
        }
        return result
    }

    suspend fun setGpaPrivacyShield(enabled: Boolean) {
        appContext.appDataStore.edit { it[PrefKeys.GPA_PRIVACY] = enabled }
    }

    suspend fun setUseDynamicColor(enabled: Boolean) {
        appContext.appDataStore.edit { it[PrefKeys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setTimelineZoomScale(scale: Float) {
        appContext.appDataStore.edit { it[PrefKeys.TIMELINE_SCALE] = scale.coerceIn(1.2f, 3.6f) }
    }

    suspend fun saveWidgetConfig(config: WidgetInstanceConfig) {
        dao.upsertWidgetConfig(config.toEntity())
    }

    suspend fun getWidgetConfigOrDefault(appWidgetId: Int, defaultType: cn.edu.haut.campus.core.model.WidgetType): WidgetInstanceConfig {
        return dao.getWidgetConfig(appWidgetId)?.toDomain()
            ?: WidgetInstanceConfig(appWidgetId = appWidgetId, widgetType = defaultType)
    }

    /**
     * 完全清除本地个人课程、成绩、导入审计与小组件个性化缓存（保留公开的校区建筑字典与节次模板）。
     */
    suspend fun wipeAllPersonalData() {
        database.withTransaction {
            dao.clearStudentProfile()
            dao.clearCourses()
            dao.clearRules()
            dao.clearOccurrenceOverrides()
            dao.clearGradeAttempts()
            dao.clearExamSchedules()
            dao.clearImportAudits()
            dao.clearWidgetConfigs()
        }
        appContext.appDataStore.edit { prefs ->
            prefs.remove(PrefKeys.OFFICIAL_GPA)
            prefs.remove(PrefKeys.OFFICIAL_GPA_NOTE)
            prefs.remove(PrefKeys.SAVED_STUDENT_ID)
            prefs.remove(PrefKeys.SAVED_PASSWORD_OBF)
            prefs.remove(PrefKeys.LAST_AUTO_SYNC_MS)
            prefs[PrefKeys.SEEDED] = true
            prefs[PrefKeys.CLEAN_BASE_V2] = true
            prefs[PrefKeys.ONBOARDING_COMPLETED] = false
        }
    }

    suspend fun getDirectScheduleDataSnapshot(): DirectScheduleData {
        ensureInitialSeedIfNeeded()
        val term = dao.getCurrentTerm()?.toDomain()
        val courses = dao.getCoursesSnapshot().map { it.toDomain() }
        val rules = dao.getRulesSnapshot().map { it.toDomain() }
        val periods = dao.getPeriodTimesSnapshot().map { it.toDomain() }
        val calOverrides = dao.getCalendarOverridesSnapshot().map { it.toDomain() }
        val occOverrides = dao.getOccurrenceOverridesSnapshot().map { it.toDomain() }
        val buildings = dao.getBuildingsSnapshot().map { it.toDomain() }
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

    companion object {
        @Volatile
        private var INSTANCE: CampusRepository? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `exam_schedules` (
                        `id` TEXT NOT NULL,
                        `termId` TEXT NOT NULL,
                        `courseCode` TEXT NOT NULL,
                        `courseName` TEXT NOT NULL,
                        `examBatchName` TEXT NOT NULL,
                        `examStageLabel` TEXT NOT NULL,
                        `examFormat` TEXT NOT NULL,
                        `rawTimeText` TEXT NOT NULL,
                        `examDateIso` TEXT,
                        `startTimeIso` TEXT,
                        `endTimeIso` TEXT,
                        `rawRoom` TEXT NOT NULL,
                        `seatNumber` TEXT,
                        `campusName` TEXT NOT NULL,
                        `isRetake` INTEGER NOT NULL,
                        `syncedAtEpochMilli` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): CampusRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appCtx = context.applicationContext
                    val db = Room.databaseBuilder(
                        appCtx,
                        HautDatabase::class.java,
                        "haut_campus_assistant.db"
                    )
                        .addMigrations(MIGRATION_1_2)
                        .fallbackToDestructiveMigration()
                        .build()
                    CampusRepository(appCtx, db).also { INSTANCE = it }
                }
            }
        }
    }
}

data class DirectScheduleData(
    val term: Term?,
    val courses: List<Course>,
    val rules: List<CourseScheduleRule>,
    val periodTimes: List<PeriodTime>,
    val calendarOverrides: List<CalendarOverride>,
    val occurrenceOverrides: List<OccurrenceOverride>,
    val buildings: List<Building>
)
