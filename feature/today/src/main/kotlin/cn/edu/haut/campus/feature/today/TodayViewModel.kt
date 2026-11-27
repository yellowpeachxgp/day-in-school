package cn.edu.haut.campus.feature.today

import androidx.lifecycle.ViewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.domain.ReminderPlanner
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.ReminderEventOverride
import cn.edu.haut.campus.core.model.ReminderKind
import cn.edu.haut.campus.core.model.SnoozeOutcome
import cn.edu.haut.campus.core.model.TimelineOccurrenceState
import cn.edu.haut.campus.core.reminder.ReminderCoordinator
import cn.edu.haut.campus.feature.today.state.TodayUiAction
import cn.edu.haut.campus.feature.today.state.TodayUiEffect
import cn.edu.haut.campus.feature.today.state.TodayUiState
import cn.edu.haut.campus.feature.today.state.TodayWriteCompletion
import cn.edu.haut.campus.feature.today.state.TodayWriteKind
import cn.edu.haut.campus.feature.today.state.TodayWriteOperation
import java.time.Instant
import java.time.format.DateTimeFormatter
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

private val TODAY_FEEDBACK_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun todayClassEventKey(termId: String, occurrenceId: String): String =
    "CLASS:" + termId + ":" + occurrenceId

/** 聚合今日页 Repository Flow，并把仓库/提醒写操作集中在一个可测试边界。 */
class TodayViewModel internal constructor(
    private val repository: CampusRepository,
    private val reminderCoordinator: ReminderCoordinator,
    private val writeExecutor: TodayWriteExecutor
) : ViewModel() {
    private class SessionMismatchCancellationException :
        CancellationException("今日页 ViewModel 所属账号会话已不是当前会话")

    private val expectedProfileId: String = repository.profileId
    private val expectedSessionGeneration: Long? = repository.sessionGeneration
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeSequence = AtomicLong(0L)

    private val _uiState = MutableStateFlow(TodayUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<TodayUiEffect>(extraBufferCapacity = 32)
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
        collect(repository.examSchedulesFlow) { value -> _uiState.update { it.copy(examSchedules = value) } }
        collect(repository.unscheduledCoursesFlow) { value -> _uiState.update { it.copy(unscheduledCourses = value) } }
        collect(repository.preferencesFlow) { value -> _uiState.update { it.copy(prefs = value) } }
        collect(repository.activeFocusSessionFlow) { value -> _uiState.update { it.copy(activeFocusSession = value) } }
        collect(repository.completedFocusSessionsFlow) { value -> _uiState.update { it.copy(completedFocusSessions = value) } }
        collect(repository.utilityOnlineRoomsFlow) { value -> _uiState.update { it.copy(onlineRooms = value) } }
        collect(repository.utilityMetersFlow) { value -> _uiState.update { it.copy(utilityMeters = value) } }
        collect(repository.utilityReadingsFlow) { value -> _uiState.update { it.copy(utilityReadings = value) } }
        collect(repository.parcelItemsFlow) { value -> _uiState.update { it.copy(parcelItems = value) } }
        collect(repository.libraryBookingPlansFlow) { value -> _uiState.update { it.copy(bookingPlans = value) } }
        collect(repository.activeReminderTasksFlow) { value -> _uiState.update { it.copy(activeReminderTasks = value) } }
        collect(repository.reminderEventOverridesFlow) { value -> _uiState.update { it.copy(reminderOverrides = value) } }
    }

    fun onAction(action: TodayUiAction) {
        if (!isCurrentSession()) return
        when (action) {
            TodayUiAction.SyncAcademic -> syncAcademic()
            TodayUiAction.DismissDynamicSyncBanner -> _uiState.update { it.copy(dynamicSyncBanner = null) }
            TodayUiAction.OpenImporter -> emit(TodayUiEffect.NavigateToImporter)
            TodayUiAction.OpenSettings -> emit(TodayUiEffect.NavigateToSettings)
            is TodayUiAction.OpenMapRoom -> emit(TodayUiEffect.NavigateToMapWithRoom(action.room))
            TodayUiAction.OpenFocus -> emit(TodayUiEffect.NavigateToFocus)
            TodayUiAction.OpenElectricity -> emit(TodayUiEffect.NavigateToElectricity)
            TodayUiAction.OpenLibraryCatalog -> emit(TodayUiEffect.NavigateToLibraryCatalog)
            TodayUiAction.OpenLibrarySeats -> emit(TodayUiEffect.NavigateToLibrarySeats)
            TodayUiAction.OpenEmptyClassrooms -> emit(TodayUiEffect.NavigateToEmptyClassrooms)
            TodayUiAction.OpenCommunity -> emit(TodayUiEffect.NavigateToCommunity)
            TodayUiAction.OpenCompetitions -> emit(TodayUiEffect.NavigateToCompetitions)
            TodayUiAction.OpenMoments -> emit(TodayUiEffect.NavigateToMoments)
            TodayUiAction.OpenParcelHub -> emit(TodayUiEffect.NavigateToParcelHub)
            TodayUiAction.OpenGrades -> emit(TodayUiEffect.NavigateToGrades)
            TodayUiAction.OpenTimetable -> emit(TodayUiEffect.NavigateToTimetable)
            is TodayUiAction.MarkParcelPickedUp -> launchOperation {
                repository.markParcelPickedUp(action.parcelId)
                ensureSessionCurrent()
                emit(TodayUiEffect.RefreshWidgets)
            }
            is TodayUiAction.MarkStationParcelsPickedUp -> launchOperation {
                repository.markAllStationParcelsPickedUp(action.stationName)
                ensureSessionCurrent()
                emit(TodayUiEffect.RefreshWidgets)
            }
            TodayUiAction.SyncParcels -> launchOperation {
                _uiState.update { it.copy(dynamicSyncBanner = "正在同步快递…") }
                runCatching { repository.syncXiaomiExpressPackages() }
                runCatching { repository.syncWithCampusCloud() }
                ensureSessionCurrent()
                emit(TodayUiEffect.RefreshWidgets)
                _uiState.update { it.copy(dynamicSyncBanner = "快递已更新") }
            }
            is TodayUiAction.StopSeatDelegation -> launchOperation {
                repository.stopInFlightOrQueuedDelegation(action.planId)
            }
            is TodayUiAction.SaveHomeCardLayout -> launchOperation {
                repository.updateHomeCardLayout(action.order, action.hidden, action.fullWidth)
            }
            TodayUiAction.ResetHomeCardLayout -> launchOperation {
                repository.resetHomeCardLayout()
            }
            is TodayUiAction.SetMealRecommendEnabled -> launchOperation {
                repository.setMealRecommendEnabled(action.enabled)
            }
            is TodayUiAction.SaveCanteenDishReview -> launchOperation {
                runCatching {
                    repository.saveCanteenDishReview(
                        canteenBuildingId = action.canteenBuildingId,
                        dishName = action.dishName,
                        stallLocation = action.stallLocation,
                        priceYuan = action.priceYuan,
                        stars = action.stars,
                        tags = action.tags,
                        comment = action.comment
                    )
                }
            }
            is TodayUiAction.DeleteCanteenDishReview -> launchOperation {
                runCatching {
                    repository.deleteCanteenDishReview(action.canteenBuildingId, action.dishName)
                }
            }
            is TodayUiAction.TestMealRecommendation -> launchOperation {
                val ok = runCatching {
                    reminderCoordinator.triggerTestMealRecommendation(action.shuffleOffset)
                }.getOrDefault(false)
                ensureSessionCurrent()
                _uiState.update {
                    it.copy(
                        dynamicSyncBanner = if (ok) "已发送下课食堂推荐通知" else "请先在系统设置中开启通知权限"
                    )
                }
            }
            is TodayUiAction.RestoreCanceledOccurrence -> restoreCanceledOccurrence(action)
            is TodayUiAction.DeleteLegacyOccurrenceOverride -> launchDurableOperation(
                kind = TodayWriteKind.DeleteLegacyOccurrenceOverride,
                write = { repository.deleteOccurrenceOverride(action.overrideId) },
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(
                            pendingLegacyRestoreTargets = state.pendingLegacyRestoreTargets.filterNot { it.id == action.overrideId }
                        )
                    }
                }
            )
            TodayUiAction.ClearLegacyRestoreTargets -> _uiState.update { it.copy(pendingLegacyRestoreTargets = emptyList()) }
            is TodayUiAction.SelectCurrentTerm -> launchDurableOperation(
                kind = TodayWriteKind.SelectCurrentTerm,
                write = { repository.selectCurrentTerm(action.termId) }
            )
            is TodayUiAction.LoadSnoozeOptions -> launchOperation {
                loadSnoozeOptions(action.detailState, action.termId, freshDialogLoad = action.freshDialogLoad)
            }
            is TodayUiAction.ToggleCourseReminder -> launchOperation {
                val occ = action.detailState.occurrence
                val courseScopeKey = "COURSE:" + occ.courseId
                if (action.nextEnabled && !_uiState.value.prefs.reminderConfig.enabled) {
                    repository.updateReminderGlobalConfig { it.copy(enabled = true) }
                }
                repository.upsertReminderEventOverride(
                    ReminderEventOverride(
                        eventScopeKey = courseScopeKey,
                        enabledOverride = action.nextEnabled,
                        leadMinutesOverride = action.leadMinutesOverride,
                        maxSnoozesOverride = action.maxSnoozesOverride,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
                ensureSessionCurrent()
                updateCourseReminderFeedback(
                    detailState = action.detailState,
                    termId = action.termId,
                    feedback = if (action.nextEnabled) "已开启提醒" else "已关闭提醒"
                )
                loadSnoozeOptions(action.detailState, action.termId, freshDialogLoad = false)
            }
            is TodayUiAction.SetCourseReminderLead -> launchOperation {
                val occ = action.detailState.occurrence
                repository.upsertReminderEventOverride(
                    ReminderEventOverride(
                        eventScopeKey = "COURSE:" + occ.courseId,
                        enabledOverride = true,
                        leadMinutesOverride = action.leadMinutes,
                        maxSnoozesOverride = action.maxSnoozesOverride,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
                ensureSessionCurrent()
                updateCourseReminderFeedback(
                    detailState = action.detailState,
                    termId = action.termId,
                    feedback = "已设为课前 " + action.leadMinutes + " 分钟提醒"
                )
                loadSnoozeOptions(action.detailState, action.termId, freshDialogLoad = false)
            }
            is TodayUiAction.SnoozeCourseReminder -> launchOperation {
                val event = ReminderPlanner.projectClassOccurrenceToEvent(action.termId, action.detailState.occurrence)
                val outcome = reminderCoordinator.snoozeEventFromUi(
                    event = event,
                    kind = ReminderKind.CLASS_BEFORE,
                    choice = action.choice,
                    now = Instant.now()
                )
                ensureSessionCurrent()
                val feedback = when (outcome) {
                    is SnoozeOutcome.Accepted -> {
                        val t = outcome.triggerAt.atZone(HAUT_ZONE_ID).toLocalTime().format(TODAY_FEEDBACK_TIME_FMT)
                        "将在 " + t + " 再次提醒"
                    }
                    is SnoozeOutcome.AcceptedPendingRepair -> "已设置稍后提醒"
                    is SnoozeOutcome.Rejected -> outcome.reason.userMessage
                    SnoozeOutcome.EventChanged -> "课程时间已变更，请刷新后重试"
                    SnoozeOutcome.ConcurrentlyHandled -> "提醒已更新"
                    SnoozeOutcome.NotFound -> "未找到提醒"
                    SnoozeOutcome.InternalError -> "设置失败"
                }
                updateCourseReminderFeedback(action.detailState, action.termId, feedback)
                loadSnoozeOptions(action.detailState, action.termId, freshDialogLoad = false)
            }
            is TodayUiAction.CancelClassOccurrence -> launchDurableOperation(
                kind = TodayWriteKind.CancelClassOccurrence,
                write = {
                    repository.cancelClassOccurrenceOnDate(
                        occ = action.detailState.occurrence,
                        termId = action.termId,
                        reason = "单次停课"
                    )
                },
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            dynamicSyncBanner = "已将「" + action.detailState.occurrence.courseName +
                                "」设为单次停课，可在课表卡底部点击「恢复上课」撤销"
                        )
                    }
                }
            )
        }
    }

    private fun syncAcademic() {
        if (_uiState.value.isDynamicSyncing) return
        launchOperation {
            _uiState.update { it.copy(isDynamicSyncing = true, dynamicSyncBanner = "正在同步…") }
            val result = repository.performDynamicAcademicSync(force = true)
            ensureSessionCurrent()
            _uiState.update { it.copy(isDynamicSyncing = false) }
            if (result == null) {
                emit(TodayUiEffect.NavigateToImporter)
            } else {
                result.onSuccess { syncData ->
                    emit(TodayUiEffect.RefreshWidgets)
                    _uiState.update {
                        it.copy(
                            dynamicSyncBanner = "已同步 " + syncData.timetableCandidates.size +
                                " 门课程、" + syncData.examItems.size + " 场考试"
                        )
                    }
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(dynamicSyncBanner = "同步失败：" + (error.message ?: "请检查网络"))
                    }
                }
            }
        }
    }

    private fun restoreCanceledOccurrence(action: TodayUiAction.RestoreCanceledOccurrence) {
        val clickTermId = action.termId ?: return
        val snapshotOverrides = action.occurrenceOverrides
        val clickedRow = action.overrideId?.let { oid ->
            snapshotOverrides.firstOrNull { it.id == oid }
        }
        val command = when {
            action.overrideId != null && clickedRow == null -> CampusRepository.RestoreCommand(
                commandId = UUID.randomUUID().toString(),
                accountProfileId = repository.profileId,
                termId = clickTermId,
                intent = CampusRepository.RestoreIntent.RESTORE_SELECTION
            )
            clickedRow != null && clickedRow.courseRuleId == null -> CampusRepository.RestoreCommand(
                commandId = UUID.randomUUID().toString(),
                accountProfileId = repository.profileId,
                termId = clickTermId,
                intent = CampusRepository.RestoreIntent.UNPAUSE,
                selectedRows = listOf(
                    CampusRepository.SelectedOverride(
                        id = clickedRow.id,
                        mutationToken = clickedRow.mutationToken,
                        ruleId = null,
                        dateIso = clickedRow.date.toString(),
                        expectedMove = clickedRow.moveId
                            ?.takeIf { it.isNotBlank() }
                            ?.let { CampusRepository.ExpectedMove.Move(it) }
                            ?: CampusRepository.ExpectedMove.NoMove,
                        role = clickedRow.moveRole
                    )
                )
            )
            else -> {
                val hitRows = snapshotOverrides.filter {
                    it.termId == clickTermId &&
                        it.courseRuleId != null &&
                        it.courseRuleId in action.mergedRuleIds &&
                        it.date == action.date && it.canceled
                }
                CampusRepository.RestoreCommand(
                    commandId = UUID.randomUUID().toString(),
                    accountProfileId = repository.profileId,
                    termId = clickTermId,
                    intent = CampusRepository.RestoreIntent.RESTORE_SELECTION,
                    selectedRows = hitRows
                        .filter { it.moveId.isNullOrBlank() }
                        .map {
                            CampusRepository.SelectedOverride(
                                id = it.id,
                                mutationToken = it.mutationToken,
                                ruleId = it.courseRuleId,
                                dateIso = it.date.toString(),
                                expectedMove = CampusRepository.ExpectedMove.NoMove,
                                role = it.moveRole
                            )
                        },
                    selectedMoves = hitRows
                        .mapNotNull { it.moveId?.takeIf { move -> move.isNotBlank() } }
                        .distinct()
                        .map { moveId ->
                            CampusRepository.SelectedMove(
                                moveId = moveId,
                                expectedMembers = snapshotOverrides
                                    .filter { it.termId == clickTermId && it.moveId == moveId }
                                    .map { CampusRepository.RowIdentity(it.id, it.mutationToken) }
                            )
                        }
                )
            }
        }
        launchDurableOperation(
            kind = TodayWriteKind.RestoreCanceledOccurrence,
            write = { repository.executeRestoreCommand(command) },
            onSuccess = { restoreResult ->
                if (restoreResult.status == CampusRepository.OccurrenceCommandStatus.APPLIED &&
                    restoreResult.legacyReviewRequired
                ) {
                    _uiState.update { it.copy(pendingLegacyRestoreTargets = restoreResult.legacyAmbiguousTargets) }
                }
                val banner = when (restoreResult.status) {
                    CampusRepository.OccurrenceCommandStatus.APPLIED ->
                        if (restoreResult.legacyReviewRequired) {
                            "已恢复「" + action.courseName + "」，检测到可能关联的旧版调入记录，请核对"
                        } else {
                            "已恢复「" + action.courseName + "」当日排课与提醒"
                        }
                    CampusRepository.OccurrenceCommandStatus.CONFLICT ->
                        "「" + action.courseName + "」记录已变化，未修改，请刷新后重新选择"
                    CampusRepository.OccurrenceCommandStatus.NO_CHANGE ->
                        "「" + action.courseName + "」已是上课状态"
                }
                _uiState.update { it.copy(dynamicSyncBanner = banner) }
            }
        )
    }

    private suspend fun loadSnoozeOptions(
        detailState: TimelineOccurrenceState,
        termId: String,
        freshDialogLoad: Boolean
    ) {
        val event = ReminderPlanner.projectClassOccurrenceToEvent(termId, detailState.occurrence)
        if (freshDialogLoad) {
            _uiState.update {
                it.copy(
                    snoozeOptionsEventKey = null,
                    snoozeOptions = emptyList(),
                    courseReminderFeedbackEventKey = null,
                    courseReminderFeedback = null
                )
            }
        }
        val options = reminderCoordinator.evaluateSnoozeOptionsForEvent(
            event = event,
            kind = ReminderKind.CLASS_BEFORE,
            now = Instant.now()
        )
        ensureSessionCurrent()
        val eventKey = todayClassEventKey(termId, detailState.occurrence.occurrenceId)
        _uiState.update { state ->
            state.copy(
                snoozeOptionsEventKey = eventKey,
                snoozeOptions = options,
                courseReminderFeedbackEventKey = if (freshDialogLoad) {
                    null
                } else {
                    state.courseReminderFeedbackEventKey
                },
                courseReminderFeedback = if (freshDialogLoad) {
                    null
                } else {
                    state.courseReminderFeedback
                }
            )
        }
    }

    private fun updateCourseReminderFeedback(
        detailState: TimelineOccurrenceState,
        termId: String,
        feedback: String
    ) {
        val eventKey = todayClassEventKey(termId, detailState.occurrence.occurrenceId)
        _uiState.update {
            it.copy(
                courseReminderFeedbackEventKey = eventKey,
                courseReminderFeedback = feedback
            )
        }
    }

    /**
     * 把用户写先交给不受组合生命周期限制的耐用执行器，再把完成/失败序列化回 UiState。
     * 成功后的 Android 小组件刷新由 Route 注入的执行器完成；即使此时页面已经离场也不会漏刷。
     */
    private fun <T : Any> launchDurableOperation(
        kind: TodayWriteKind,
        write: suspend () -> T,
        onSuccess: (T) -> Unit = {}
    ) {
        val generation = expectedSessionGeneration
        if (generation == null || !isCurrentSession()) return
        val operation = TodayWriteOperation(
            id = writeSequence.incrementAndGet(),
            kind = kind
        )
        _uiState.update { state ->
            state.copy(
                pendingWrites = state.pendingWrites + operation,
                writeCompletions = state.writeCompletions - kind
            )
        }
        val job = writeExecutor.enqueue(
            profileId = expectedProfileId,
            sessionGeneration = generation,
            write = write,
            onComplete = { result ->
                if (!isCurrentSession()) return@enqueue
                val failure = result.exceptionOrNull()
                val completion = TodayWriteCompletion(
                    id = operation.id,
                    kind = kind,
                    errorMessage = failure?.let { writeErrorMessage(it) }
                )
                _uiState.update { state ->
                    state.copy(
                        pendingWrites = state.pendingWrites.filterNot { it.id == operation.id },
                        writeCompletions = state.writeCompletions + (kind to completion)
                    )
                }
                result.onSuccess { value -> onSuccess(value) }
            }
        )
        if (job == null) {
            val completion = TodayWriteCompletion(
                id = operation.id,
                kind = kind,
                errorMessage = "账号会话尚未就绪，写入未执行"
            )
            _uiState.update { state ->
                state.copy(
                    pendingWrites = state.pendingWrites.filterNot { it.id == operation.id },
                    writeCompletions = state.writeCompletions + (kind to completion)
                )
            }
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

    private fun launchOperation(block: suspend () -> Unit) {
        scope.launch {
            ensureSessionCurrent()
            block()
            ensureSessionCurrent()
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

    private fun emit(effect: TodayUiEffect) {
        check(_effects.tryEmit(effect)) { "今日页事件缓冲已满" }
    }

    override fun onCleared() {
        scope.cancel()
    }
}
