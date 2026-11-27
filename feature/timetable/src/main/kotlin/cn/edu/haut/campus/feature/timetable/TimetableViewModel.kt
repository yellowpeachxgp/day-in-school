package cn.edu.haut.campus.feature.timetable

import androidx.lifecycle.ViewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.timetable.state.TimetableUiAction
import cn.edu.haut.campus.feature.timetable.state.TimetableUiEffect
import cn.edu.haut.campus.feature.timetable.state.TimetableUiState
import java.time.DayOfWeek
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 聚合课表页 Repository Flow，并把全部仓库写操作集中在一个可测试边界。 */
class TimetableViewModel(
    private val repository: CampusRepository
) : ViewModel() {
    private class SessionMismatchCancellationException :
        CancellationException("课表页 ViewModel 所属账号会话已不是当前会话")

    private val expectedProfileId: String = repository.profileId
    private val expectedSessionGeneration: Long? = repository.sessionGeneration
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(TimetableUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<TimetableUiEffect>(extraBufferCapacity = 32)
    val effects = _effects.asSharedFlow()

    init {
        collect(repository.termsFlow) { value -> _uiState.update { it.copy(terms = value) } }
        collect(repository.currentTermFlow) { value -> _uiState.update { it.copy(currentTerm = value) } }
        collect(repository.coursesFlow) { value -> _uiState.update { it.copy(courses = value) } }
        collect(repository.rulesFlow) { value -> _uiState.update { it.copy(scheduleRules = value) } }
        collect(repository.periodTimesFlow) { value -> _uiState.update { it.copy(periodTimes = value) } }
        collect(repository.calendarOverridesFlow) { value -> _uiState.update { it.copy(calendarOverrides = value) } }
        collect(repository.occurrenceOverridesFlow) { value -> _uiState.update { it.copy(occurrenceOverrides = value) } }
        collect(repository.buildingsFlow) { value -> _uiState.update { it.copy(buildings = value) } }
        collect(repository.unscheduledCoursesFlow) { value -> _uiState.update { it.copy(unscheduledCourses = value) } }
    }

    fun onAction(action: TimetableUiAction) {
        if (!isCurrentSession()) return
        when (action) {
            TimetableUiAction.OpenImporter -> emit(TimetableUiEffect.NavigateToImporter)
            is TimetableUiAction.OpenMapRoom -> emit(TimetableUiEffect.NavigateToMapWithRoom(action.room))
            is TimetableUiAction.RestoreCanceledGroup -> restoreCanceledGroup(action)
            is TimetableUiAction.DeleteLegacyOccurrenceOverride -> launchOperation {
                repository.deleteOccurrenceOverride(action.overrideId)
                ensureSessionCurrent()
                _uiState.update { state ->
                    state.copy(
                        pendingLegacyReviewTargets = state.pendingLegacyReviewTargets.filterNot { it.id == action.overrideId }
                    )
                }
                emit(TimetableUiEffect.RefreshWidgets)
            }
            TimetableUiAction.ClearLegacyReviewTargets -> _uiState.update { it.copy(pendingLegacyReviewTargets = emptyList()) }
            is TimetableUiAction.SelectCurrentTerm -> launchRefreshOperation {
                repository.selectCurrentTerm(action.termId)
            }
            is TimetableUiAction.UpdateTermStartDate -> launchRefreshOperation {
                repository.updateTermStartDate(action.termId, action.startDate)
            }
            is TimetableUiAction.ResetSingleDayOccurrenceEdit -> launchRefreshOperation {
                repository.resetSingleDayOccurrenceEdit(action.occurrence)
            }
            is TimetableUiAction.CancelClassOccurrence -> launchRefreshOperation {
                repository.cancelClassOccurrenceOnDate(
                    occ = action.occurrence,
                    termId = action.termId,
                    reason = "单次停课"
                )
            }
            is TimetableUiAction.DeleteCourseScheduleRules -> launchRefreshOperation {
                repository.deleteCourseScheduleRules(
                    ruleIds = action.ruleIds,
                    contextDate = action.contextDate
                )
            }
            is TimetableUiAction.SaveSingleDayOccurrenceEdit -> launchRefreshOperation {
                if (action.targetDate != action.occurrence.date) {
                    repository.rescheduleSingleOccurrenceCrossDate(
                        occ = action.occurrence,
                        termId = action.termId,
                        targetDate = action.targetDate,
                        newStartPeriod = action.newStartPeriod,
                        newEndPeriod = action.newEndPeriod,
                        newRoom = action.newRoom
                    )
                } else {
                    repository.saveSingleDayOccurrenceEdit(
                        occ = action.occurrence,
                        termId = action.termId,
                        newStartPeriod = action.newStartPeriod,
                        newEndPeriod = action.newEndPeriod,
                        newRoom = action.newRoom
                    )
                }
            }
            is TimetableUiAction.SaveMergedCourseRuleEdit -> launchRefreshOperation {
                repository.saveUserMergedCourseRuleEdit(
                    course = action.course.copy(
                        name = action.name.trim().ifEmpty { action.course.name },
                        teacher = action.teacher.trim().ifEmpty { null },
                        colorArgb = action.colorArgb
                    ),
                    primaryRule = action.primaryRule.copy(
                        weekday = DayOfWeek.of(action.weekday.coerceIn(1, 7)),
                        startPeriod = action.startPeriod,
                        endPeriod = action.endPeriod,
                        rawRoom = action.room.trim().ifEmpty { action.primaryRule.rawRoom },
                        weeks = action.weeks
                    ),
                    mergedRuleIds = action.mergedRuleIds,
                    contextDate = action.contextDate
                )
            }
            is TimetableUiAction.SaveMasterCourseRuleEdit -> launchRefreshOperation {
                repository.saveUserCourseEdit(
                    course = action.course.copy(
                        name = action.name.trim().ifEmpty { action.course.name },
                        teacher = action.teacher.trim().ifEmpty { null },
                        colorArgb = action.colorArgb
                    ),
                    rule = action.rule.copy(
                        weekday = DayOfWeek.of(action.weekday),
                        startPeriod = action.startPeriod,
                        endPeriod = action.endPeriod,
                        rawRoom = action.room.trim().ifEmpty { action.rule.rawRoom },
                        weeks = action.weeks
                    )
                )
            }
            is TimetableUiAction.DeleteCourseScheduleRule -> launchRefreshOperation {
                repository.deleteCourseScheduleRule(action.ruleId)
            }
            is TimetableUiAction.SchedulePracticalCourse -> launchRefreshOperation {
                repository.scheduleUnscheduledPracticalCourse(
                    unscheduled = action.unscheduled,
                    rawRoom = action.rawRoom,
                    weekday = DayOfWeek.of(action.weekday),
                    startPeriod = action.startPeriod,
                    endPeriod = action.endPeriod,
                    weeks = action.weeks
                )
            }
            is TimetableUiAction.AddManualCourse -> launchRefreshOperation {
                repository.addManualCourseWithRule(
                    termId = action.termId,
                    name = action.name,
                    teacher = action.teacher,
                    rawRoom = action.rawRoom,
                    weekday = DayOfWeek.of(action.weekday),
                    startPeriod = action.startPeriod,
                    endPeriod = action.endPeriod,
                    weeks = action.weeks,
                    colorArgb = action.colorArgb
                )
            }
        }
    }

    private fun restoreCanceledGroup(action: TimetableUiAction.RestoreCanceledGroup) {
        launchOperation {
            val clickTermId = action.termId ?: return@launchOperation
            val snapshotOverrides = action.occurrenceOverrides
            val groupRows = action.groupRows
            val groupMoveIds = groupRows.mapNotNull { it.moveId }.distinct()
            val selectedMoves = groupMoveIds.map { moveId ->
                CampusRepository.SelectedMove(
                    moveId = moveId,
                    expectedMembers = snapshotOverrides
                        .filter { it.termId == clickTermId && it.moveId == moveId }
                        .map { CampusRepository.RowIdentity(it.id, it.mutationToken) }
                )
            }
            val selectedRows = groupRows.filter { it.moveId.isNullOrBlank() }.map { row ->
                CampusRepository.SelectedOverride(
                    id = row.id,
                    mutationToken = row.mutationToken,
                    ruleId = row.courseRuleId,
                    dateIso = row.date.toString(),
                    expectedMove = CampusRepository.ExpectedMove.NoMove,
                    role = row.moveRole
                )
            }
            val command = CampusRepository.RestoreCommand(
                commandId = UUID.randomUUID().toString(),
                accountProfileId = repository.profileId,
                termId = clickTermId,
                intent = if (selectedRows.isEmpty() && selectedMoves.isNotEmpty()) {
                    CampusRepository.RestoreIntent.UNDO_MOVES
                } else {
                    CampusRepository.RestoreIntent.RESTORE_SELECTION
                },
                selectedRows = selectedRows,
                selectedMoves = selectedMoves
            )
            val result = repository.executeRestoreCommand(command)
            ensureSessionCurrent()
            when (result.status) {
                CampusRepository.OccurrenceCommandStatus.APPLIED -> {
                    _uiState.update { it.copy(restoreNotice = null) }
                    emit(TimetableUiEffect.RefreshWidgets)
                    if (result.legacyAmbiguousTargets.isNotEmpty()) {
                        _uiState.update { it.copy(pendingLegacyReviewTargets = result.legacyAmbiguousTargets) }
                    }
                }
                CampusRepository.OccurrenceCommandStatus.CONFLICT -> {
                    _uiState.update { it.copy(restoreNotice = "记录已变化，未修改，请刷新后重新选择") }
                }
                CampusRepository.OccurrenceCommandStatus.NO_CHANGE -> Unit
            }
        }
    }

    private fun launchRefreshOperation(block: suspend () -> Unit) {
        launchOperation {
            block()
            ensureSessionCurrent()
            emit(TimetableUiEffect.RefreshWidgets)
        }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        scope.launch {
            ensureSessionCurrent()
            block()
            ensureSessionCurrent()
        }
    }

    private fun <T> collect(flow: Flow<T>, update: (T) -> Unit) {
        scope.launch {
            if (!isCurrentSession()) return@launch
            flow.collect { value ->
                ensureSessionCurrent()
                update(value)
                ensureSessionCurrent()
            }
        }
    }

    private fun isCurrentSession(): Boolean =
        expectedSessionGeneration != null &&
            repository.isSessionActive &&
            repository.profileId == expectedProfileId &&
            repository.sessionGeneration == expectedSessionGeneration

    private fun ensureSessionCurrent() {
        if (!isCurrentSession()) {
            val cancellation = SessionMismatchCancellationException()
            scope.cancel(cancellation)
            throw cancellation
        }
    }

    private fun emit(effect: TimetableUiEffect) {
        check(_effects.tryEmit(effect)) { "课表页事件缓冲已满" }
    }

    override fun onCleared() {
        scope.cancel()
    }
}
