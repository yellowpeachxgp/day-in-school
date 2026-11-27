package cn.edu.haut.campus.feature.settings.emptyclassroom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.emptyclassroom.state.EmptyClassroomUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 空教室状态 holder：只聚合课表/作息/覆盖与偏好 Flow，不持有写动作。 */
class EmptyClassroomViewModel(
    private val repository: CampusRepository
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(EmptyClassroomUiState())
    val uiState: StateFlow<EmptyClassroomUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch { repository.currentTermFlow.collectLatest { value -> mutableUiState.update { it.copy(currentTerm = value) } } }
        viewModelScope.launch { repository.coursesFlow.collectLatest { value -> mutableUiState.update { it.copy(courses = value) } } }
        viewModelScope.launch { repository.rulesFlow.collectLatest { value -> mutableUiState.update { it.copy(rules = value) } } }
        viewModelScope.launch { repository.periodTimesFlow.collectLatest { value -> mutableUiState.update { it.copy(periodTimes = value) } } }
        viewModelScope.launch { repository.calendarOverridesFlow.collectLatest { value -> mutableUiState.update { it.copy(calendarOverrides = value) } } }
        viewModelScope.launch { repository.occurrenceOverridesFlow.collectLatest { value -> mutableUiState.update { it.copy(occurrenceOverrides = value) } } }
        viewModelScope.launch { repository.preferencesFlow.collectLatest { value -> mutableUiState.update { it.copy(prefs = value) } } }
    }

    companion object {
        fun factory(repository: CampusRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { EmptyClassroomViewModel(repository) }
        }
    }
}
