package cn.edu.haut.campus.feature.settings.root.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.feature.settings.root.home.state.SettingsHomeUiAction
import cn.edu.haut.campus.feature.settings.root.home.state.SettingsHomeUiEffect
import cn.edu.haut.campus.feature.settings.root.home.state.SettingsHomeUiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 设置一级「我的」页分区状态 holder；不承接系统设置二级页。 */
class SettingsHomeViewModel(
    private val repository: CampusRepository
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SettingsHomeUiState())
    val uiState: StateFlow<SettingsHomeUiState> = mutableUiState.asStateFlow()
    private val mutableEffects = MutableSharedFlow<SettingsHomeUiEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<SettingsHomeUiEffect> = mutableEffects.asSharedFlow()

    init {
        collectFlows()
    }

    private fun collectFlows() {
        viewModelScope.launch { repository.studentProfileFlow.collectLatest { v -> mutableUiState.update { it.copy(studentProfile = v) } } }
        viewModelScope.launch { repository.termsFlow.collectLatest { v -> mutableUiState.update { it.copy(terms = v) } } }
        viewModelScope.launch { repository.currentTermFlow.collectLatest { v -> mutableUiState.update { it.copy(currentTerm = v) } } }
        viewModelScope.launch { repository.coursesFlow.collectLatest { v -> mutableUiState.update { it.copy(courses = v) } } }
        viewModelScope.launch { repository.rulesFlow.collectLatest { v -> mutableUiState.update { it.copy(rules = v) } } }
        viewModelScope.launch { repository.gradeAttemptsFlow.collectLatest { v -> mutableUiState.update { it.copy(attempts = v) } } }
        viewModelScope.launch { repository.periodTimesFlow.collectLatest { v -> mutableUiState.update { it.copy(periodTimes = v) } } }
        viewModelScope.launch { repository.calendarOverridesFlow.collectLatest { v -> mutableUiState.update { it.copy(calendarOverrides = v) } } }
        viewModelScope.launch { repository.occurrenceOverridesFlow.collectLatest { v -> mutableUiState.update { it.copy(occurrenceOverrides = v) } } }
        viewModelScope.launch { repository.buildingsFlow.collectLatest { v -> mutableUiState.update { it.copy(buildings = v) } } }
        viewModelScope.launch { repository.importAuditsFlow.collectLatest { v -> mutableUiState.update { it.copy(importAudits = v) } } }
        viewModelScope.launch { repository.examSchedulesFlow.collectLatest { v -> mutableUiState.update { it.copy(examSchedules = v) } } }
        viewModelScope.launch { repository.preferencesFlow.collectLatest { v -> mutableUiState.update { it.copy(prefs = v) } } }
        viewModelScope.launch { repository.activeFocusSessionFlow.collectLatest { v -> mutableUiState.update { it.copy(activeFocusSession = v) } } }
        viewModelScope.launch { repository.completedFocusSessionsFlow.collectLatest { v -> mutableUiState.update { it.copy(completedFocusSessions = v) } } }
        viewModelScope.launch { repository.utilityOnlineRoomsFlow.collectLatest { v -> mutableUiState.update { it.copy(onlineRooms = v) } } }
        viewModelScope.launch { repository.utilityMetersFlow.collectLatest { v -> mutableUiState.update { it.copy(utilityMeters = v) } } }
        viewModelScope.launch { repository.utilityReadingsFlow.collectLatest { v -> mutableUiState.update { it.copy(utilityReadings = v) } } }
        viewModelScope.launch { repository.libraryBookingPlansFlow.collectLatest { v -> mutableUiState.update { it.copy(libraryBookingPlans = v) } } }
        viewModelScope.launch { repository.cloudIdentityBindingFlow.collectLatest { v -> mutableUiState.update { it.copy(cloudBinding = v) } } }
        viewModelScope.launch { repository.parcelItemsFlow.collectLatest { v -> mutableUiState.update { it.copy(parcelItems = v) } } }
        viewModelScope.launch { repository.installedExtensionPluginsFlow.collectLatest { v -> mutableUiState.update { it.copy(extensionPlugins = v) } } }
    }

    fun onAction(action: SettingsHomeUiAction) {
        when (action) {
            SettingsHomeUiAction.PerformDynamicSync -> performDynamicSync()
            is SettingsHomeUiAction.SelectCurrentTerm -> selectCurrentTerm(action.termId)
            is SettingsHomeUiAction.SaveStudentProfile -> saveStudentProfile(action.profile)
            SettingsHomeUiAction.ShowEditProfileDialog -> mutableUiState.update { it.copy(showEditProfileDialog = true) }
            SettingsHomeUiAction.HideEditProfileDialog -> mutableUiState.update { it.copy(showEditProfileDialog = false) }
            SettingsHomeUiAction.ShowImportHistoryDialog -> mutableUiState.update { it.copy(showImportHistoryDialog = true) }
            SettingsHomeUiAction.HideImportHistoryDialog -> mutableUiState.update { it.copy(showImportHistoryDialog = false) }
        }
    }

    private fun performDynamicSync() {
        if (mutableUiState.value.isDynamicSyncing) return
        viewModelScope.launch {
            mutableUiState.update { it.copy(isDynamicSyncing = true) }
            mutableEffects.emit(SettingsHomeUiEffect.ShowFeedback("正在同步课表、考试和成绩..."))
            val res = repository.performDynamicAcademicSync(force = true)
            mutableUiState.update { it.copy(isDynamicSyncing = false) }
            if (res == null) {
                mutableEffects.emit(SettingsHomeUiEffect.NavigateToImporter)
            } else {
                res.onSuccess { syncData ->
                    mutableEffects.emit(SettingsHomeUiEffect.RefreshWidgets)
                    mutableEffects.emit(
                        SettingsHomeUiEffect.ShowFeedback(
                            "已同步 " + syncData.timetableCandidates.size + " 门课程、" + syncData.examItems.size + " 场考试、" + syncData.gradeAttempts.size + " 条成绩"
                        )
                    )
                }.onFailure { err ->
                    mutableEffects.emit(SettingsHomeUiEffect.ShowFeedback("同步失败：" + (err.message ?: "请检查网络")))
                }
            }
        }
    }

    private fun selectCurrentTerm(termId: String) {
        viewModelScope.launch {
            repository.selectCurrentTerm(termId)
            mutableEffects.emit(SettingsHomeUiEffect.RefreshWidgets)
            val state = mutableUiState.value
            val term = state.terms.firstOrNull { it.id == termId }
            val count = state.rules.count { it.termId == termId }
            if (term != null) {
                mutableEffects.emit(SettingsHomeUiEffect.ShowFeedback("已切换至 " + term.displayName + "（共 " + count + " 门排课）"))
            }
        }
    }

    private fun saveStudentProfile(profile: StudentProfile) {
        viewModelScope.launch {
            repository.saveStudentProfile(profile)
            mutableUiState.update { it.copy(showEditProfileDialog = false) }
            mutableEffects.emit(SettingsHomeUiEffect.ShowFeedback("已保存个人信息。"))
        }
    }

    companion object {
        fun factory(repository: CampusRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsHomeViewModel(repository) }
        }
    }
}
