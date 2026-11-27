package cn.edu.haut.campus.feature.settings.root.home.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.campusservices.UtilityDailySample
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CloudIdentityBindingEntity
import cn.edu.haut.campus.core.database.InstalledExtensionPluginEntity
import cn.edu.haut.campus.core.database.LibraryBookingPlanEntity
import cn.edu.haut.campus.core.database.ParcelItemEntity
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.FocusSessionDomain
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.ImportAudit
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.UtilityMeterDomain
import cn.edu.haut.campus.core.model.UtilityOnlineRoomDomain
import cn.edu.haut.campus.core.model.UtilityReadingDomain
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 设置一级「我的」页不可变渲染快照；只属于首页分区，不聚合系统设置页。 */
@Immutable
data class SettingsHomeUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val studentProfile: StudentProfile? = null,
    val terms: List<Term> = emptyList(),
    val currentTerm: Term? = null,
    val courses: List<Course> = emptyList(),
    val rules: List<CourseScheduleRule> = emptyList(),
    val attempts: List<GradeAttempt> = emptyList(),
    val periodTimes: List<PeriodTime> = emptyList(),
    val calendarOverrides: List<CalendarOverride> = emptyList(),
    val occurrenceOverrides: List<OccurrenceOverride> = emptyList(),
    val buildings: List<Building> = emptyList(),
    val importAudits: List<ImportAudit> = emptyList(),
    val examSchedules: List<ExamScheduleItem> = emptyList(),
    val prefs: AppPreferencesState = AppPreferencesState(),
    val activeFocusSession: FocusSessionDomain? = null,
    val completedFocusSessions: List<FocusSessionDomain> = emptyList(),
    val onlineRooms: List<UtilityOnlineRoomDomain> = emptyList(),
    val utilityMeters: List<UtilityMeterDomain> = emptyList(),
    val utilityReadings: List<UtilityReadingDomain> = emptyList(),
    val libraryBookingPlans: List<LibraryBookingPlanEntity> = emptyList(),
    val cloudBinding: CloudIdentityBindingEntity? = null,
    val parcelItems: List<ParcelItemEntity> = emptyList(),
    val extensionPlugins: List<InstalledExtensionPluginEntity> = emptyList(),
    val isDynamicSyncing: Boolean = false,
    val showEditProfileDialog: Boolean = false,
    val showImportHistoryDialog: Boolean = false
)
