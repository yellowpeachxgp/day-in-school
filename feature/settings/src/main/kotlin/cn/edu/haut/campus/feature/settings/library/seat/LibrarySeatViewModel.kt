package cn.edu.haut.campus.feature.settings.library.seat

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.campusservices.BookingExecutionMode
import cn.edu.haut.campus.core.campusservices.CloudSyncSnapshotResult
import cn.edu.haut.campus.core.campusservices.LibraryRegionSeatsSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatActionOutcome
import cn.edu.haut.campus.core.campusservices.LibrarySeatActionType
import cn.edu.haut.campus.core.campusservices.LibrarySeatOverviewSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionState
import cn.edu.haut.campus.core.campusservices.LibraryStudyRegionItem
import cn.edu.haut.campus.core.campusservices.TripleScheduleStage
import cn.edu.haut.campus.core.database.AccountSessionState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.LibraryBookingPlanEntity
import cn.edu.haut.campus.feature.settings.library.seat.state.BookingSubmissionIdentity
import cn.edu.haut.campus.feature.settings.library.seat.state.LibrarySeatUiAction
import cn.edu.haut.campus.feature.settings.library.seat.state.LibrarySeatUiState
import cn.edu.haut.campus.feature.settings.state.ProfileSessionScopedViewModel
import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 图书馆选座状态 holder：聚合偏好/计划/云身份，并委托全部既有座位动作。 */
class LibrarySeatViewModel(
    private val repository: CampusRepository,
    sessionStates: StateFlow<AccountSessionState>
) : ProfileSessionScopedViewModel(repository, sessionStates), LibrarySeatUiAction {
    private val mutableUiState = MutableStateFlow(LibrarySeatUiState())
    val uiState: StateFlow<LibrarySeatUiState> = mutableUiState.asStateFlow()

    init {
        launchSessionCollect(repository.preferencesFlow) { value -> mutableUiState.update { it.copy(prefs = value) } }
        launchSessionCollect(repository.libraryBookingPlansFlow) { value -> mutableUiState.update { it.copy(bookingPlans = value) } }
        launchSessionCollect(repository.cloudIdentityBindingFlow) { value -> mutableUiState.update { it.copy(cloudBinding = value) } }
    }

    override fun readCachedLibrarySeatSession(): LibrarySeatSessionSnapshot? = repository.readCachedLibrarySeatSession()
    override suspend fun captureLibrarySeatWebToken(rawToken: String): LibrarySeatSessionSnapshot? = repository.captureLibrarySeatWebToken(rawToken)
    override suspend fun clearLibrarySeatSession() = repository.clearLibrarySeatSession()
    override suspend fun fetchLibrarySeatOverview(): Result<LibrarySeatOverviewSnapshot> = repository.fetchLibrarySeatOverview()
    override suspend fun ensureLibrarySeatSession(forceRefresh: Boolean): LibrarySeatSessionState = repository.ensureLibrarySeatSession(forceRefresh)
    override suspend fun executeLibrarySeatAction(action: LibrarySeatActionType, bookingId: Long?): Result<LibrarySeatActionOutcome> =
        repository.executeLibrarySeatAction(action, bookingId)
    override suspend fun saveLibrarySeatCredentialsAndLogin(studentId: String, passwordPlain: String): LibrarySeatSessionState =
        repository.saveLibrarySeatCredentialsAndLogin(studentId, passwordPlain)
    override suspend fun snapshotBookingSubmissionIdentity(): BookingSubmissionIdentity = repository.snapshotBookingSubmissionIdentity()
    override suspend fun createDualEngineBookingPlan(
        executionMode: BookingExecutionMode,
        targetDate: LocalDate,
        targetTimeText: String,
        preferredAreaName: String,
        preferredSeatLabel: String,
        allowAlternativeSeat: Boolean,
        repeatDays: Int,
        acceptedPriceMicropeach: Long?,
        nowEpochMillis: Long,
        expectedIdentity: BookingSubmissionIdentity?
    ): Result<LibraryBookingPlanEntity> = repository.createDualEngineBookingPlan(
        executionMode, targetDate, targetTimeText, preferredAreaName, preferredSeatLabel,
        allowAlternativeSeat, repeatDays, acceptedPriceMicropeach, nowEpochMillis, expectedIdentity
    )
    override suspend fun loadLibraryPlanningRegions(date: String, refresh: Boolean): Result<List<LibraryStudyRegionItem>> =
        repository.loadLibraryPlanningRegions(date, refresh)
    override suspend fun loadLibraryPlanningSeats(region: LibraryStudyRegionItem, date: String, refresh: Boolean): Result<LibraryRegionSeatsSnapshot> =
        repository.loadLibraryPlanningSeats(region, date, refresh)
    override suspend fun advanceBookingPlanStage(planId: String, stage: TripleScheduleStage, nowEpochMillis: Long): Result<LibraryBookingPlanEntity> =
        repository.advanceBookingPlanStage(planId, stage, nowEpochMillis)
    override suspend fun executeCloudBookingPlanNow(planId: String, nowEpochMillis: Long): Result<LibraryBookingPlanEntity> =
        repository.executeCloudBookingPlanNow(planId, nowEpochMillis)
    override suspend fun stopInFlightOrQueuedDelegation(planId: String): LibraryBookingPlanEntity? =
        repository.stopInFlightOrQueuedDelegation(planId)
    override suspend fun cancelActiveLibrarySeatBooking(planId: String?, bookingId: String?): Result<String> =
        repository.cancelActiveLibrarySeatBooking(planId, bookingId)
    override suspend fun rescheduleActiveLocalBookingPlans() = repository.rescheduleActiveLocalBookingPlans()
    override suspend fun reconfirmAndRestoreCloudBookingPlans(): Result<CloudSyncSnapshotResult> = repository.reconfirmAndRestoreCloudBookingPlans()
    override suspend fun refreshXpeachWalletBalance(): Double? = repository.refreshXpeachWalletBalance()
    override suspend fun syncWithCampusCloud(): Result<CloudSyncSnapshotResult> = repository.syncWithCampusCloud()

    override fun resetStateForInvalidSession() {
        mutableUiState.value = LibrarySeatUiState()
    }

    companion object {
        fun factory(
            repository: CampusRepository,
            sessionStates: StateFlow<AccountSessionState>
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { LibrarySeatViewModel(repository, sessionStates) }
        }
    }
}
