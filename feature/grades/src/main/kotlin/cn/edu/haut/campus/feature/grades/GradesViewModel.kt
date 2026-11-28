package cn.edu.haut.campus.feature.grades

import androidx.lifecycle.ViewModel
import cn.edu.haut.campus.core.database.CampusServicesRepository
import cn.edu.haut.campus.core.database.ExamRepository
import cn.edu.haut.campus.core.database.GradesRepository
import cn.edu.haut.campus.core.database.TermRepository
import cn.edu.haut.campus.core.database.TimetableRepository
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

/** 聚合学业页拆分 Repository Flow，并把全部写操作集中在一个可测试边界。 */
class GradesViewModel(
    private val gradesRepository: GradesRepository,
    private val examRepository: ExamRepository,
    private val termRepository: TermRepository,
    private val timetableRepository: TimetableRepository,
    private val campusServicesRepository: CampusServicesRepository
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(GradesUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<GradesUiEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    init {
        scope.launch { termRepository.currentTermFlow.collect { value -> _uiState.update { it.copy(currentTerm = value) } } }
        scope.launch { gradesRepository.gradeAttemptsFlow.collect { value -> _uiState.update { it.copy(attempts = value) } } }
        scope.launch { examRepository.examSchedulesFlow.collect { value -> _uiState.update { it.copy(examSchedules = value) } } }
        scope.launch { examRepository.examTermReviewAuditsFlow.collect { value -> _uiState.update { it.copy(examTermReviewAudits = value) } } }
        scope.launch { timetableRepository.coursesFlow.collect { value -> _uiState.update { it.copy(courses = value) } } }
        scope.launch { timetableRepository.rulesFlow.collect { value -> _uiState.update { it.copy(scheduleRules = value) } } }
        scope.launch { campusServicesRepository.preferencesFlow.collect { value -> _uiState.update { it.copy(prefs = value) } } }
        scope.launch { gradesRepository.studentProfileFlow.collect { value -> _uiState.update { it.copy(studentProfile = value) } } }
        scope.launch { campusServicesRepository.activeFocusSessionFlow.collect { value -> _uiState.update { it.copy(activeFocusSession = value) } } }
        scope.launch { campusServicesRepository.completedFocusSessionsFlow.collect { value -> _uiState.update { it.copy(completedFocusSessions = value) } } }
    }

    fun onAction(action: GradesUiAction) {
        when (action) {
            GradesUiAction.ToggleGpaPrivacyShield -> {
                val next = !_uiState.value.prefs.gpaPrivacyShieldEnabled
                scope.launch { gradesRepository.setGpaPrivacyShield(next) }
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
