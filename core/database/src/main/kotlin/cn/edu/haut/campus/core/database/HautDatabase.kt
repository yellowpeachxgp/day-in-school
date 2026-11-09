package cn.edu.haut.campus.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface CampusDao {

    @Query("SELECT * FROM student_profile LIMIT 1")
    fun observeStudentProfile(): Flow<StudentProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStudentProfile(profile: StudentProfileEntity)

    @Query("SELECT * FROM terms ORDER BY academicYear DESC, termNumber DESC")
    fun observeTerms(): Flow<List<TermEntity>>

    @Query("SELECT * FROM terms WHERE current = 1 LIMIT 1")
    suspend fun getCurrentTerm(): TermEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTerms(terms: List<TermEntity>)

    @Query("UPDATE terms SET current = CASE WHEN id = :termId THEN 1 ELSE 0 END")
    suspend fun setCurrentTerm(termId: String)

    @Query("SELECT * FROM period_times ORDER BY periodNo ASC")
    fun observePeriodTimes(): Flow<List<PeriodTimeEntity>>

    @Query("SELECT * FROM period_times ORDER BY periodNo ASC")
    suspend fun getPeriodTimesSnapshot(): List<PeriodTimeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeriodTimes(items: List<PeriodTimeEntity>)

    @Query("SELECT * FROM calendar_overrides")
    fun observeCalendarOverrides(): Flow<List<CalendarOverrideEntity>>

    @Query("SELECT * FROM calendar_overrides")
    suspend fun getCalendarOverridesSnapshot(): List<CalendarOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCalendarOverrides(items: List<CalendarOverrideEntity>)

    @Query("DELETE FROM calendar_overrides WHERE id = :id")
    suspend fun deleteCalendarOverrideById(id: String)

    @Query("SELECT * FROM courses")
    fun observeCourses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses")
    suspend fun getCoursesSnapshot(): List<CourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCourses(items: List<CourseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCourse(item: CourseEntity)

    @Query("SELECT * FROM course_schedule_rules")
    fun observeRules(): Flow<List<CourseScheduleRuleEntity>>

    @Query("SELECT * FROM course_schedule_rules")
    suspend fun getRulesSnapshot(): List<CourseScheduleRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRules(items: List<CourseScheduleRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(item: CourseScheduleRuleEntity)

    @Query("DELETE FROM course_schedule_rules WHERE id = :ruleId")
    suspend fun deleteRuleById(ruleId: String)

    @Query("SELECT * FROM occurrence_overrides")
    fun observeOccurrenceOverrides(): Flow<List<OccurrenceOverrideEntity>>

    @Query("SELECT * FROM occurrence_overrides")
    suspend fun getOccurrenceOverridesSnapshot(): List<OccurrenceOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOccurrenceOverrides(items: List<OccurrenceOverrideEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOccurrenceOverride(item: OccurrenceOverrideEntity)

    @Query("DELETE FROM occurrence_overrides WHERE id = :id")
    suspend fun deleteOccurrenceOverrideById(id: String)

    @Query("DELETE FROM occurrence_overrides WHERE courseRuleId = :ruleId AND dateIso = :dateIso AND canceled = 1")
    suspend fun deleteCanceledOccurrenceOverridesForRuleOnDate(ruleId: String, dateIso: String)

    @Query("DELETE FROM courses WHERE id = :courseId")
    suspend fun deleteCourseById(courseId: String)

    @Query("DELETE FROM course_schedule_rules WHERE courseId = :courseId")
    suspend fun deleteRulesByCourseId(courseId: String)

    @Query("SELECT * FROM grade_attempts ORDER BY academicYear DESC, termNumber DESC, courseKey ASC")
    fun observeGradeAttempts(): Flow<List<GradeAttemptEntity>>

    @Query("SELECT * FROM grade_attempts ORDER BY academicYear DESC, termNumber DESC, courseKey ASC")
    suspend fun getGradeAttemptsSnapshot(): List<GradeAttemptEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGradeAttempts(items: List<GradeAttemptEntity>)

    @Query("SELECT * FROM buildings ORDER BY id ASC")
    fun observeBuildings(): Flow<List<BuildingEntity>>

    @Query("SELECT * FROM buildings ORDER BY id ASC")
    suspend fun getBuildingsSnapshot(): List<BuildingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBuildings(items: List<BuildingEntity>)

    @Query("SELECT * FROM building_aliases")
    fun observeBuildingAliases(): Flow<List<BuildingAliasEntity>>

    @Query("SELECT * FROM building_aliases")
    suspend fun getBuildingAliasesSnapshot(): List<BuildingAliasEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBuildingAliases(items: List<BuildingAliasEntity>)

    @Query("DELETE FROM building_aliases WHERE id IN (:ids)")
    suspend fun deleteBuildingAliasesByIds(ids: List<String>)

    @Query("SELECT * FROM widget_instance_configs")
    fun observeWidgetConfigs(): Flow<List<WidgetInstanceConfigEntity>>

    @Query("SELECT * FROM widget_instance_configs WHERE appWidgetId = :appWidgetId LIMIT 1")
    suspend fun getWidgetConfig(appWidgetId: Int): WidgetInstanceConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWidgetConfig(config: WidgetInstanceConfigEntity)

    @Query("UPDATE widget_instance_configs SET compact = :compact, privacyMode = :privacyMode")
    suspend fun updateAllWidgetConfigsDefaults(compact: Boolean, privacyMode: Boolean)

    @Query("UPDATE widget_instance_configs SET compact = :compact")
    suspend fun updateAllWidgetConfigsCompact(compact: Boolean)

    @Query("UPDATE widget_instance_configs SET privacyMode = :privacyMode")
    suspend fun updateAllWidgetConfigsPrivacyMode(privacyMode: Boolean)

    @Query("DELETE FROM widget_instance_configs WHERE appWidgetId = :appWidgetId")
    suspend fun deleteWidgetConfig(appWidgetId: Int)

    @Query("SELECT * FROM import_audits ORDER BY importedAtEpochMilli DESC")
    fun observeImportAudits(): Flow<List<ImportAuditEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImportAudit(audit: ImportAuditEntity)

    @Query("SELECT * FROM exam_schedules ORDER BY termId DESC, examDateIso ASC, startTimeIso ASC")
    fun observeExamSchedules(): Flow<List<ExamScheduleEntity>>

    @Query("SELECT * FROM exam_schedules ORDER BY termId DESC, examDateIso ASC, startTimeIso ASC")
    suspend fun getExamSchedulesSnapshot(): List<ExamScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExamSchedules(items: List<ExamScheduleEntity>)

    @Query("DELETE FROM exam_schedules")
    suspend fun clearExamSchedules()

    @Query("DELETE FROM exam_schedules WHERE termId = :termId")
    suspend fun clearExamSchedulesForTerm(termId: String)

    @Query("SELECT * FROM unscheduled_academic_courses ORDER BY termId DESC, name ASC, sourceKey ASC")
    fun observeUnscheduledCourses(): Flow<List<UnscheduledAcademicCourseEntity>>

    @Query("SELECT * FROM unscheduled_academic_courses ORDER BY termId DESC, name ASC, sourceKey ASC")
    suspend fun getUnscheduledCoursesSnapshot(): List<UnscheduledAcademicCourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUnscheduledCourses(items: List<UnscheduledAcademicCourseEntity>)

    @Query("DELETE FROM unscheduled_academic_courses WHERE termId = :termId AND sourceKey = :sourceKey")
    suspend fun deleteUnscheduledCourseByKey(termId: String, sourceKey: String)

    @Query("DELETE FROM unscheduled_academic_courses WHERE termId = :termId")
    suspend fun clearUnscheduledCoursesForTerm(termId: String)

    @Query("DELETE FROM unscheduled_academic_courses")
    suspend fun clearUnscheduledCourses()

    @Query("DELETE FROM student_profile")
    suspend fun clearStudentProfile()

    @Query("DELETE FROM courses")
    suspend fun clearCourses()

    @Query("DELETE FROM course_schedule_rules")
    suspend fun clearRules()

    @Query("DELETE FROM occurrence_overrides")
    suspend fun clearOccurrenceOverrides()

    @Query("DELETE FROM grade_attempts")
    suspend fun clearGradeAttempts()

    @Query("DELETE FROM import_audits")
    suspend fun clearImportAudits()

    @Query("DELETE FROM widget_instance_configs")
    suspend fun clearWidgetConfigs()
}

@Database(
    entities = [
        StudentProfileEntity::class,
        TermEntity::class,
        PeriodTimeEntity::class,
        CalendarOverrideEntity::class,
        CourseEntity::class,
        CourseScheduleRuleEntity::class,
        OccurrenceOverrideEntity::class,
        GradeAttemptEntity::class,
        GpaPolicyEntity::class,
        BuildingEntity::class,
        BuildingAliasEntity::class,
        WidgetInstanceConfigEntity::class,
        ImportAuditEntity::class,
        ExamScheduleEntity::class,
        ReminderTaskEntity::class,
        ReminderEventPrefEntity::class,
        UtilityDailySampleEntity::class,
        UtilityOnlineRoomEntity::class,
        UtilityMeterEntity::class,
        UtilityReadingEntity::class,
        FocusSessionEntity::class,
        UnscheduledAcademicCourseEntity::class,
        CloudIdentityBindingEntity::class,
        LibraryBookingPlanEntity::class,
        ParcelItemEntity::class,
        ParcelSourceConnectionEntity::class,
        InstalledExtensionPluginEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class HautDatabase : RoomDatabase() {
    abstract fun campusDao(): CampusDao
    abstract fun reminderDao(): ReminderDao
    abstract fun utilityAndFocusDao(): UtilityAndFocusDao
    abstract fun cloudAndParcelDao(): CloudAndParcelDao
}
