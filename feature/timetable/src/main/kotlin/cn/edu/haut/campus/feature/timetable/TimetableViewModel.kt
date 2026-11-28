package cn.edu.haut.campus.feature.timetable

import androidx.lifecycle.ViewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.UnscheduledAcademicCourseEntity
import cn.edu.haut.campus.feature.timetable.state.TimetableUiAction
import cn.edu.haut.campus.feature.timetable.state.TimetableUiEffect
import cn.edu.haut.campus.feature.timetable.state.TimetableUiState
import cn.edu.haut.campus.feature.timetable.state.TimetableWriteCompletion
import cn.edu.haut.campus.feature.timetable.state.TimetableWriteKind
import cn.edu.haut.campus.feature.timetable.state.TimetableWriteOperation
import java.time.DayOfWeek
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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
class TimetableViewModel internal constructor(
    private val repository: CampusRepository,
    private val writeExecutor: TimetableWriteExecutor
) : ViewModel() {
    private class SessionMismatchCancellationException :
        CancellationException("课表页 ViewModel 所属账号会话已不是当前会话")

    private val expectedProfileId: String = repository.profileId
    private val expectedSessionGeneration: Long? = repository.sessionGeneration
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeSequence = AtomicLong(0L)

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
        collect(repository.termRepository.periodTimeSchemesFlow) { value -> _uiState.update { it.copy(periodTimeSchemes = value) } }
        collect(repository.calendarOverridesFlow) { value -> _uiState.update { it.copy(calendarOverrides = value) } }
        collect(repository.occurrenceOverridesFlow) { value -> _uiState.update { it.copy(occurrenceOverrides = value) } }
        collect(repository.buildingsFlow) { value -> _uiState.update { it.copy(buildings = value) } }
        collect(repository.unscheduledCoursesFlow) { value ->
            val uiRows = value.map { row ->
                cn.edu.haut.campus.feature.timetable.state.UnscheduledPracticalCourseUiModel(
                    termId = row.termId,
                    sourceKey = row.sourceKey,
                    sourceId = row.sourceId,
                    name = row.name,
                    teacher = row.teacher,
                    creditPlain = row.creditPlain,
                    rawWeeks = row.rawWeeks,
                    category = row.category,
                    syncedAtEpochMilli = row.syncedAtEpochMilli
                )
            }
            _uiState.update { it.copy(unscheduledCourses = uiRows) }
        }
    }

    fun onAction(action: TimetableUiAction) {
        if (!isCurrentSession()) return
        when (action) {
            TimetableUiAction.OpenImporter -> emit(TimetableUiEffect.NavigateToImporter)
            is TimetableUiAction.OpenMapRoom -> emit(TimetableUiEffect.NavigateToMapWithRoom(action.room))
            is TimetableUiAction.RestoreCanceledGroup -> restoreCanceledGroup(action)
            is TimetableUiAction.DeleteLegacyOccurrenceOverride -> launchDurableOperation(
                kind = TimetableWriteKind.DeleteLegacyOccurrenceOverride,
                operationToken = action.writeToken,
                write = {
                    repository.deleteOccurrenceOverride(action.overrideId)
                },
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(
                            pendingLegacyReviewTargets = state.pendingLegacyReviewTargets.filterNot { it.id == action.overrideId }
                        )
                    }
                }
            )
            TimetableUiAction.ClearLegacyReviewTargets -> _uiState.update { it.copy(pendingLegacyReviewTargets = emptyList()) }
            is TimetableUiAction.ConsumeWriteCompletion -> _uiState.update { state ->
                state.copy(writeCompletions = state.writeCompletions - action.token)
            }
            is TimetableUiAction.SelectCurrentTerm -> launchDurableOperation(
                kind = TimetableWriteKind.SelectCurrentTerm,
                operationToken = action.writeToken,
                write = { repository.selectCurrentTerm(action.termId) }
            )
            is TimetableUiAction.UpdateTermStartDate -> launchDurableOperation(
                kind = TimetableWriteKind.UpdateTermStartDate,
                operationToken = action.writeToken,
                write = { repository.updateTermStartDate(action.termId, action.startDate) }
            )
            is TimetableUiAction.ResetSingleDayOccurrenceEdit -> launchDurableOperation(
                kind = TimetableWriteKind.ResetSingleDayOccurrenceEdit,
                operationToken = action.writeToken,
                write = { repository.resetSingleDayOccurrenceEdit(action.occurrence) }
            )
            is TimetableUiAction.CancelClassOccurrence -> launchDurableOperation(
                kind = TimetableWriteKind.CancelClassOccurrence,
                operationToken = action.writeToken,
                write = {
                    repository.cancelClassOccurrenceOnDate(
                        occ = action.occurrence,
                        termId = action.termId,
                        reason = "单次停课"
                    )
                }
            )
            is TimetableUiAction.DeleteCourseScheduleRules -> launchDurableOperation(
                kind = TimetableWriteKind.DeleteCourseScheduleRules,
                operationToken = action.writeToken,
                write = {
                    repository.deleteCourseScheduleRules(
                        ruleIds = action.ruleIds,
                        contextDate = action.contextDate
                    )
                }
            )
            is TimetableUiAction.SaveSingleDayOccurrenceEdit -> launchDurableOperation(
                kind = TimetableWriteKind.SaveSingleDayOccurrenceEdit,
                operationToken = action.writeToken,
                write = {
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
            )
            is TimetableUiAction.SaveMergedCourseRuleEdit -> launchDurableOperation(
                kind = TimetableWriteKind.SaveMergedCourseRuleEdit,
                operationToken = action.writeToken,
                write = {
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
            )
            is TimetableUiAction.SaveMasterCourseRuleEdit -> launchDurableOperation(
                kind = TimetableWriteKind.SaveMasterCourseRuleEdit,
                operationToken = action.writeToken,
                write = {
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
            )
            is TimetableUiAction.DeleteCourseScheduleRule -> launchDurableOperation(
                kind = TimetableWriteKind.DeleteCourseScheduleRule,
                operationToken = action.writeToken,
                write = { repository.deleteCourseScheduleRule(action.ruleId) }
            )
            is TimetableUiAction.SaveBasePeriodTimes -> launchDurableOperation(
                kind = TimetableWriteKind.SaveBasePeriodTimes,
                operationToken = action.writeToken,
                write = {
                    repository.termRepository.saveBasePeriodTimes(action.timeTableId, action.periods)
                }
            )
            is TimetableUiAction.SavePeriodTimeScheme -> launchDurableOperation(
                kind = TimetableWriteKind.SavePeriodTimeScheme,
                operationToken = action.writeToken,
                write = {
                    repository.termRepository.savePeriodTimeScheme(
                        term = action.term,
                        name = action.name,
                        startDate = action.startDate,
                        endDate = action.endDate,
                        periods = action.periods,
                        schemeId = action.schemeId
                    )
                }
            )
            is TimetableUiAction.DeletePeriodTimeScheme -> launchDurableOperation(
                kind = TimetableWriteKind.DeletePeriodTimeScheme,
                operationToken = action.writeToken,
                write = { repository.termRepository.deletePeriodTimeScheme(action.schemeId) }
            )
            is TimetableUiAction.SchedulePracticalCourseMatrix -> launchDurableOperation(
                kind = TimetableWriteKind.SchedulePracticalCourseMatrix,
                operationToken = action.writeToken,
                write = {
                    repository.timetableRepository.scheduleUnscheduledPracticalCourseWithOccurrences(
                        unscheduled = action.unscheduled.toEntity(),
                        occurrences = action.occurrences
                    )
                }
            )
            is TimetableUiAction.AddManualCourse -> launchDurableOperation(
                kind = TimetableWriteKind.AddManualCourse,
                operationToken = action.writeToken,
                write = {
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
            )
        }
    }

    private fun cn.edu.haut.campus.feature.timetable.state.UnscheduledPracticalCourseUiModel.toEntity() =
        UnscheduledAcademicCourseEntity(
            termId = termId,
            sourceKey = sourceKey,
            sourceId = sourceId,
            name = name,
            teacher = teacher,
            creditPlain = creditPlain,
            rawWeeks = rawWeeks,
            category = category,
            syncedAtEpochMilli = syncedAtEpochMilli
        )

    private fun restoreCanceledGroup(action: TimetableUiAction.RestoreCanceledGroup) {
        val clickTermId = action.termId
        if (clickTermId == null) {
            rejectDurableOperation(
                kind = TimetableWriteKind.RestoreCanceledGroup,
                operationToken = action.writeToken,
                message = "当前学期尚未就绪，恢复未执行"
            )
            return
        }
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
        launchDurableOperation(
            kind = TimetableWriteKind.RestoreCanceledGroup,
            operationToken = action.writeToken,
            write = { repository.executeRestoreCommand(command) },
            onSuccess = { result ->
                when (result.status) {
                    CampusRepository.OccurrenceCommandStatus.APPLIED -> {
                        _uiState.update { it.copy(restoreNotice = null) }
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
        )
    }

    /**
     * 把用户写先交给不受组合生命周期限制的耐用执行器，再把完成/失败序列化回 UiState。
     * 成功后的 Android 小组件刷新由 Route 注入的执行器完成；即使此时页面已经离场也不会漏刷。
     */
    private fun <T : Any> launchDurableOperation(
        kind: TimetableWriteKind,
        operationToken: String?,
        write: suspend () -> T,
        onSuccess: (T) -> Unit = {}
    ) {
        val generation = expectedSessionGeneration
        if (generation == null || !isCurrentSession()) return
        val id = writeSequence.incrementAndGet()
        val operation = TimetableWriteOperation(
            id = id,
            kind = kind,
            token = operationToken?.takeIf { it.isNotBlank() } ?: "viewmodel-write-" + id
        )
        val alreadyKnown = _uiState.value.pendingWrites.any { it.token == operation.token } ||
            _uiState.value.writeCompletions.containsKey(operation.token)
        if (alreadyKnown) return
        _uiState.update { state ->
            state.copy(
                pendingWrites = state.pendingWrites + operation,
                writeCompletions = state.writeCompletions - operation.token
            )
        }
        val job = writeExecutor.enqueue(
            profileId = expectedProfileId,
            sessionGeneration = generation,
            write = write,
            onComplete = { result ->
                if (!isCurrentSession()) return@enqueue
                val failure = result.exceptionOrNull()
                val completion = TimetableWriteCompletion(
                    id = operation.id,
                    kind = kind,
                    token = operation.token,
                    errorMessage = failure?.let { writeErrorMessage(it) }
                )
                _uiState.update { state ->
                    state.copy(
                        pendingWrites = state.pendingWrites.filterNot { it.id == operation.id },
                        writeCompletions = state.writeCompletions + (operation.token to completion)
                    )
                }
                result.onSuccess { value -> onSuccess(value) }
            }
        )
        if (job == null) {
            publishDurableFailure(
                operation = operation,
                message = "账号会话尚未就绪，写入未执行"
            )
        }
    }

    private fun rejectDurableOperation(
        kind: TimetableWriteKind,
        operationToken: String?,
        message: String
    ) {
        val id = writeSequence.incrementAndGet()
        val operation = TimetableWriteOperation(
            id = id,
            kind = kind,
            token = operationToken?.takeIf { it.isNotBlank() } ?: "viewmodel-write-" + id
        )
        publishDurableFailure(operation, message)
    }

    private fun publishDurableFailure(
        operation: TimetableWriteOperation,
        message: String
    ) {
        val completion = TimetableWriteCompletion(
            id = operation.id,
            kind = operation.kind,
            token = operation.token,
            errorMessage = message
        )
        _uiState.update { state ->
            state.copy(
                pendingWrites = state.pendingWrites.filterNot { it.id == operation.id },
                writeCompletions = state.writeCompletions + (operation.token to completion)
            )
        }
    }

    private fun writeErrorMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: "写入失败，请重试"

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
