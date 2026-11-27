package cn.edu.haut.campus.feature.grades

import androidx.lifecycle.ViewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.grades.state.GradesUiAction
import cn.edu.haut.campus.feature.grades.state.GradesUiEffect
import cn.edu.haut.campus.feature.grades.state.GradesUiState
import cn.edu.haut.campus.feature.grades.state.buildGradesCsv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 聚合学业页 Repository Flow，并把全部仓库写操作集中在一个可测试边界。 */
class GradesViewModel(
    private val repository: CampusRepository
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(GradesUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<GradesUiEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    init {
        scope.launch { repository.currentTermFlow.collect { value -> _uiState.update { it.copy(currentTerm = value) } } }
        scope.launch { repository.gradeAttemptsFlow.collect { value -> _uiState.update { it.copy(attempts = value) } } }
        scope.launch { repository.examSchedulesFlow.collect { value -> _uiState.update { it.copy(examSchedules = value) } } }
        scope.launch { repository.examTermReviewAuditsFlow.collect { value -> _uiState.update { it.copy(examTermReviewAudits = value) } } }
        scope.launch { repository.coursesFlow.collect { value -> _uiState.update { it.copy(courses = value) } } }
        scope.launch { repository.rulesFlow.collect { value -> _uiState.update { it.copy(scheduleRules = value) } } }
        scope.launch { repository.preferencesFlow.collect { value -> _uiState.update { it.copy(prefs = value) } } }
        scope.launch { repository.studentProfileFlow.collect { value -> _uiState.update { it.copy(studentProfile = value) } } }
        scope.launch { repository.activeFocusSessionFlow.collect { value -> _uiState.update { it.copy(activeFocusSession = value) } } }
        scope.launch { repository.completedFocusSessionsFlow.collect { value -> _uiState.update { it.copy(completedFocusSessions = value) } } }
    }

    fun onAction(action: GradesUiAction) {
        when (action) {
            GradesUiAction.ToggleGpaPrivacyShield -> {
                val next = !_uiState.value.prefs.gpaPrivacyShieldEnabled
                scope.launch { repository.setGpaPrivacyShield(next) }
            }
            GradesUiAction.ExportGradesCsv -> emit(GradesUiEffect.ShareGradesCsv(buildGradesCsv(_uiState.value.attempts)))
            GradesUiAction.OpenImporter -> emit(GradesUiEffect.NavigateToImporter)
            is GradesUiAction.OpenMapRoom -> emit(GradesUiEffect.NavigateToMapWithRoom(action.room))
            GradesUiAction.OpenLibrarySeats -> emit(GradesUiEffect.NavigateToLibrarySeats)
            GradesUiAction.OpenLibraryCatalog -> emit(GradesUiEffect.NavigateToLibraryCatalog)
            GradesUiAction.OpenFocus -> emit(GradesUiEffect.NavigateToFocus)
            GradesUiAction.OpenEmptyClassrooms -> emit(GradesUiEffect.NavigateToEmptyClassrooms)
            GradesUiAction.OpenCompetitions -> emit(GradesUiEffect.NavigateToCompetitions)
        }
    }

    private fun emit(effect: GradesUiEffect) {
        check(_effects.tryEmit(effect)) { "学业页事件缓冲已满" }
    }

    override fun onCleared() {
        scope.cancel()
    }
}
