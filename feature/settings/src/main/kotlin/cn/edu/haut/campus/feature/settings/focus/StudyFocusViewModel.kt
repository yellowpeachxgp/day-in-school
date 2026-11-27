package cn.edu.haut.campus.feature.settings.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.reminder.focus.FocusCoordinator
import cn.edu.haut.campus.feature.settings.focus.state.StudyFocusUiAction
import cn.edu.haut.campus.feature.settings.focus.state.StudyFocusUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 自习专注状态 holder：聚合课表/专注 Flow，执行 FocusCoordinator 动作并驱动秒级倒计时快照。 */
class StudyFocusViewModel(
    private val repository: CampusRepository,
    private val focusCoordinator: FocusCoordinator,
    private val diagnosticSummaryProvider: () -> String
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        StudyFocusUiState(
            nowMs = System.currentTimeMillis(),
            diagnosticSummary = diagnosticSummaryProvider()
        )
    )
    val uiState: StateFlow<StudyFocusUiState> = mutableUiState.asStateFlow()
    private var restoredTickerKey: String? = null

    init {
        collectRepositoryFlows()
        startTicker()
    }

    private fun collectRepositoryFlows() {
        viewModelScope.launch {
            repository.preferencesFlow.collectLatest { prefs ->
                mutableUiState.update { state ->
                    val minutesChanged = state.prefs.selectedFocusMinutes != prefs.selectedFocusMinutes
                    state.copy(
                        prefs = prefs,
                        customMinutesInput = if (minutesChanged) prefs.selectedFocusMinutes.toString() else state.customMinutesInput
                    )
                }
            }
        }
        viewModelScope.launch { repository.activeFocusSessionFlow.collectLatest { value -> mutableUiState.update { it.copy(activeSession = value) } } }
        viewModelScope.launch { repository.completedFocusSessionsFlow.collectLatest { value -> mutableUiState.update { it.copy(completedSessions = value) } } }
        viewModelScope.launch { repository.currentTermFlow.collectLatest { value -> mutableUiState.update { it.copy(currentTerm = value) } } }
        viewModelScope.launch { repository.coursesFlow.collectLatest { value -> mutableUiState.update { it.copy(courses = value) } } }
        viewModelScope.launch { repository.rulesFlow.collectLatest { value -> mutableUiState.update { it.copy(rules = value) } } }
        viewModelScope.launch { repository.periodTimesFlow.collectLatest { value -> mutableUiState.update { it.copy(periodTimes = value) } } }
        viewModelScope.launch { repository.calendarOverridesFlow.collectLatest { value -> mutableUiState.update { it.copy(calendarOverrides = value) } } }
        viewModelScope.launch { repository.occurrenceOverridesFlow.collectLatest { value -> mutableUiState.update { it.copy(occurrenceOverrides = value) } } }
        viewModelScope.launch { repository.buildingsFlow.collectLatest { value -> mutableUiState.update { it.copy(buildings = value) } } }
    }

    private fun startTicker() {
        viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                mutableUiState.update { it.copy(nowMs = now, diagnosticSummary = diagnosticSummaryProvider()) }
                val running = mutableUiState.value.activeSession
                val tickerKey = running?.let { it.id.toString() + ":" + it.expectedEndAtEpochMs }
                if (tickerKey != restoredTickerKey) {
                    restoredTickerKey = tickerKey
                    focusCoordinator.restoreOrCompleteActiveSession()
                }
                if (running != null && now >= running.expectedEndAtEpochMs) {
                    focusCoordinator.completeFocusSessionIfDue(
                        sessionId = running.id,
                        expectedGeneration = running.generation,
                        expectedEndAtEpochMs = running.expectedEndAtEpochMs,
                        nowEpochMs = now,
                        isRestorePath = false
                    )
                }
                delay(1_000L)
            }
        }
    }

    fun onAction(action: StudyFocusUiAction) {
        when (action) {
            is StudyFocusUiAction.UpdateCustomMinutesInput -> mutableUiState.update {
                it.copy(customMinutesInput = action.value)
            }
            is StudyFocusUiAction.SetSelectedFocusMinutes -> {
                mutableUiState.update { it.copy(customMinutesInput = action.minutes.toString()) }
                viewModelScope.launch { repository.setSelectedFocusMinutes(action.minutes) }
            }
            is StudyFocusUiAction.StartFocusSession -> viewModelScope.launch {
                focusCoordinator.startFocusSession(action.minutes)
                mutableUiState.update {
                    it.copy(statusBanner = "已开启 " + action.minutes + " 分钟自习专注，到点将发送系统通知。")
                }
            }
            StudyFocusUiAction.StopActiveFocusSession -> viewModelScope.launch {
                focusCoordinator.stopActiveFocusSession()
                mutableUiState.update { it.copy(statusBanner = "已结束本次自习专注。") }
            }
            StudyFocusUiAction.TriggerTenSecondTestAlarm -> {
                focusCoordinator.triggerTenSecondTestAlarm()
                mutableUiState.update {
                    it.copy(
                        diagnosticSummary = diagnosticSummaryProvider(),
                        statusBanner = "已注册 10 秒后测试闹钟，可立即切回桌面等待通知。"
                    )
                }
            }
            StudyFocusUiAction.ClearAllFocusData -> viewModelScope.launch {
                focusCoordinator.clearAllFocusData()
                mutableUiState.update { it.copy(statusBanner = "已清空专注历史记录。") }
            }
            StudyFocusUiAction.ShowInvalidMinutesError -> mutableUiState.update {
                it.copy(statusBanner = "请输入 1 至 180 之间的整数分钟数。")
            }
        }
    }

    companion object {
        fun factory(
            repository: CampusRepository,
            focusCoordinator: FocusCoordinator,
            diagnosticSummaryProvider: () -> String
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { StudyFocusViewModel(repository, focusCoordinator, diagnosticSummaryProvider) }
        }
    }
}
