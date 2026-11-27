package cn.edu.haut.campus.feature.settings.root.academic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.ReminderGlobalConfig
import cn.edu.haut.campus.core.reminder.ReconcileReport
import cn.edu.haut.campus.core.reminder.ReminderCoordinator
import cn.edu.haut.campus.feature.settings.root.academic.state.SettingsAcademicUiAction
import cn.edu.haut.campus.feature.settings.root.academic.state.SettingsAcademicUiState
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 学期/小组件/提醒分区状态 holder；不承担设置整页或一级主页聚合。 */
class SettingsAcademicViewModel(
    private val repository: CampusRepository,
    private val reminderCoordinator: ReminderCoordinator
) : ViewModel(), SettingsAcademicUiAction {
    private val mutableUiState = MutableStateFlow(SettingsAcademicUiState())
    val uiState: StateFlow<SettingsAcademicUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch { repository.termsFlow.collectLatest { v -> mutableUiState.update { it.copy(terms = v) } } }
        viewModelScope.launch { repository.currentTermFlow.collectLatest { v -> mutableUiState.update { it.copy(currentTerm = v) } } }
        viewModelScope.launch { repository.rulesFlow.collectLatest { v -> mutableUiState.update { it.copy(rules = v) } } }
        viewModelScope.launch { repository.periodTimesFlow.collectLatest { v -> mutableUiState.update { it.copy(periodTimes = v) } } }
        viewModelScope.launch { repository.calendarOverridesFlow.collectLatest { v -> mutableUiState.update { it.copy(calendarOverrides = v) } } }
        viewModelScope.launch { repository.preferencesFlow.collectLatest { v -> mutableUiState.update { it.copy(prefs = v) } } }
        viewModelScope.launch { repository.activeReminderTasksFlow.collectLatest { v -> mutableUiState.update { it.copy(activeReminderTasks = v) } } }
    }

    override suspend fun selectCurrentTerm(termId: String) = repository.selectCurrentTerm(termId)
    override suspend fun deleteCalendarOverride(overrideId: String) = repository.deleteCalendarOverride(overrideId)
    override suspend fun saveCalendarOverride(override: CalendarOverride) = repository.saveCalendarOverride(override)
    override suspend fun updatePeriodTime(periodTime: PeriodTime) = repository.updatePeriodTime(periodTime)
    override suspend fun updateAllWidgetDefaults(compact: Boolean?, privacyMode: Boolean?) =
        repository.updateAllWidgetDefaults(compact, privacyMode)
    override suspend fun updateReminderGlobalConfig(transform: (ReminderGlobalConfig) -> ReminderGlobalConfig) =
        repository.updateReminderGlobalConfig(transform)
    override suspend fun reconcileNow(reason: String, forceRescheduleAlarms: Boolean, now: Instant): ReconcileReport =
        reminderCoordinator.reconcileNow(reason, forceRescheduleAlarms, now)
    override suspend fun triggerTestReminder(): Boolean = reminderCoordinator.triggerTestReminder()

    companion object {
        fun factory(
            repository: CampusRepository,
            reminderCoordinator: ReminderCoordinator
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsAcademicViewModel(repository, reminderCoordinator) }
        }
    }
}
