package cn.edu.haut.campus.feature.settings.library.seat.state

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
import cn.edu.haut.campus.core.database.LibraryBookingPlanEntity
import java.time.LocalDate

/** 图书馆选座/云端抢座用户动作面；粒度与拆分前调用一致，确保纯搬移。 */
interface LibrarySeatUiAction {
    fun readCachedLibrarySeatSession(): LibrarySeatSessionSnapshot?
    suspend fun captureLibrarySeatWebToken(rawToken: String): LibrarySeatSessionSnapshot?
    suspend fun clearLibrarySeatSession()
    suspend fun fetchLibrarySeatOverview(): Result<LibrarySeatOverviewSnapshot>
    suspend fun ensureLibrarySeatSession(forceRefresh: Boolean = false): LibrarySeatSessionState
    suspend fun executeLibrarySeatAction(
        action: LibrarySeatActionType,
        bookingId: Long? = null
    ): Result<LibrarySeatActionOutcome>
    suspend fun saveLibrarySeatCredentialsAndLogin(
        studentId: String,
        passwordPlain: String
    ): LibrarySeatSessionState
    suspend fun snapshotBookingSubmissionIdentity(): BookingSubmissionIdentity
    suspend fun createDualEngineBookingPlan(
        executionMode: BookingExecutionMode,
        targetDate: LocalDate,
        targetTimeText: String,
        preferredAreaName: String,
        preferredSeatLabel: String,
        allowAlternativeSeat: Boolean = true,
        repeatDays: Int = 1,
        acceptedPriceMicropeach: Long? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
        expectedIdentity: BookingSubmissionIdentity? = null
    ): Result<LibraryBookingPlanEntity>
    suspend fun loadLibraryPlanningRegions(
        date: String,
        refresh: Boolean = false
    ): Result<List<LibraryStudyRegionItem>>
    suspend fun loadLibraryPlanningSeats(
        region: LibraryStudyRegionItem,
        date: String,
        refresh: Boolean = false
    ): Result<LibraryRegionSeatsSnapshot>
    suspend fun advanceBookingPlanStage(
        planId: String,
        stage: TripleScheduleStage,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Result<LibraryBookingPlanEntity>
    suspend fun executeCloudBookingPlanNow(
        planId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Result<LibraryBookingPlanEntity>
    suspend fun stopInFlightOrQueuedDelegation(planId: String): LibraryBookingPlanEntity?
    suspend fun cancelActiveLibrarySeatBooking(
        planId: String? = null,
        bookingId: String? = null
    ): Result<String>
    suspend fun rescheduleActiveLocalBookingPlans()
    suspend fun reconfirmAndRestoreCloudBookingPlans(): Result<CloudSyncSnapshotResult>
    suspend fun refreshXpeachWalletBalance(): Double?
    suspend fun syncWithCampusCloud(): Result<CloudSyncSnapshotResult>
}
