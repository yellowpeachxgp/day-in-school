package cn.edu.haut.campus.feature.settings.root.home

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.database.AccountSessionState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.feature.settings.root.home.state.SettingsHomeUiAction
import cn.edu.haut.campus.feature.settings.root.home.state.SettingsHomeUiEffect
import cn.edu.haut.campus.feature.settings.root.home.state.SettingsHomeUiState
import cn.edu.haut.campus.feature.settings.state.ProfileSessionScopedViewModel
import cn.edu.haut.campus.feature.settings.state.resetTransientForExit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 设置一级「我的」页分区状态 holder；不承接系统设置二级页。 */
class SettingsHomeViewModel(
    private val repository: CampusRepository,
    sessionStates: StateFlow<AccountSessionState>
) : ProfileSessionScopedViewModel(repository, sessionStates) {
    private val mutableUiState = MutableStateFlow(SettingsHomeUiState())
    val uiState: StateFlow<SettingsHomeUiState> = mutableUiState.asStateFlow()
    private val mutableEffects = MutableSharedFlow<SettingsHomeUiEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<SettingsHomeUiEffect> = mutableEffects.asSharedFlow()
    private var dynamicSyncJob: Job? = null

    init {
        collectFlows()
    }

    private fun collectFlows() {
        launchSessionCollect(repository.studentProfileFlow) { v -> mutableUiState.update { it.copy(studentProfile = v) } }
        launchSessionCollect(repository.termsFlow) { v -> mutableUiState.update { it.copy(terms = v) } }
        launchSessionCollect(repository.currentTermFlow) { v -> mutableUiState.update { it.copy(currentTerm = v) } }
        launchSessionCollect(repository.coursesFlow) { v -> mutableUiState.update { it.copy(courses = v) } }
        launchSessionCollect(repository.rulesFlow) { v -> mutableUiState.update { it.copy(rules = v) } }
        launchSessionCollect(repository.gradeAttemptsFlow) { v -> mutableUiState.update { it.copy(attempts = v) } }
        launchSessionCollect(repository.periodTimesFlow) { v -> mutableUiState.update { it.copy(periodTimes = v) } }
        launchSessionCollect(repository.calendarOverridesFlow) { v -> mutableUiState.update { it.copy(calendarOverrides = v) } }
        launchSessionCollect(repository.occurrenceOverridesFlow) { v -> mutableUiState.update { it.copy(occurrenceOverrides = v) } }
        launchSessionCollect(repository.buildingsFlow) { v -> mutableUiState.update { it.copy(buildings = v) } }
        launchSessionCollect(repository.importAuditsFlow) { v -> mutableUiState.update { it.copy(importAudits = v) } }
        launchSessionCollect(repository.examSchedulesFlow) { v -> mutableUiState.update { it.copy(examSchedules = v) } }
        launchSessionCollect(repository.preferencesFlow) { v -> mutableUiState.update { it.copy(prefs = v) } }
        launchSessionCollect(repository.activeFocusSessionFlow) { v -> mutableUiState.update { it.copy(activeFocusSession = v) } }
        launchSessionCollect(repository.completedFocusSessionsFlow) { v -> mutableUiState.update { it.copy(completedFocusSessions = v) } }
        launchSessionCollect(repository.utilityOnlineRoomsFlow) { v -> mutableUiState.update { it.copy(onlineRooms = v) } }
        launchSessionCollect(repository.utilityMetersFlow) { v -> mutableUiState.update { it.copy(utilityMeters = v) } }
        launchSessionCollect(repository.utilityReadingsFlow) { v -> mutableUiState.update { it.copy(utilityReadings = v) } }
        launchSessionCollect(repository.libraryBookingPlansFlow) { v -> mutableUiState.update { it.copy(libraryBookingPlans = v) } }
        launchSessionCollect(repository.cloudIdentityBindingFlow) { v -> mutableUiState.update { it.copy(cloudBinding = v) } }
        launchSessionCollect(repository.parcelItemsFlow) { v -> mutableUiState.update { it.copy(parcelItems = v) } }
        launchSessionCollect(repository.installedExtensionPluginsFlow) { v -> mutableUiState.update { it.copy(extensionPlugins = v) } }
    }

    fun onAction(action: SettingsHomeUiAction) {
        when (action) {
            SettingsHomeUiAction.PerformDynamicSync -> performDynamicSync()
            is SettingsHomeUiAction.SelectCurrentTerm -> selectCurrentTerm(action.termId)
            is SettingsHomeUiAction.SaveStudentProfile -> saveStudentProfile(action.profile)
            SettingsHomeUiAction.ShowEditProfileDialog -> mutableUiState.update { it.copy(showEditProfileDialog = true) }
            SettingsHomeUiAction.HideEditProfileDialog -> mutableUiState.update { it.copy(showEditProfileDialog = false) }
        }
    }

    private fun performDynamicSync() {
        if (mutableUiState.value.isDynamicSyncing) return
        dynamicSyncJob?.cancel()
        dynamicSyncJob = launchSessionJob {
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

    /** 根设置页随底栏离开时调用；只恢复拆分前 remember 级别的瞬态。 */
    fun resetTransientStateOnExit() {
        dynamicSyncJob?.cancel()
        dynamicSyncJob = null
        mutableUiState.update { it.resetTransientForExit() }
    }

    override fun resetStateForInvalidSession() {
        dynamicSyncJob?.cancel()
        dynamicSyncJob = null
        mutableUiState.value = SettingsHomeUiState()
    }

    companion object {
        fun factory(
            repository: CampusRepository,
            sessionStates: StateFlow<AccountSessionState>
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsHomeViewModel(repository, sessionStates) }
        }
    }
}
