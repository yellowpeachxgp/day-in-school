package cn.edu.haut.campus.feature.today.state

import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.SnoozeChoice
import cn.edu.haut.campus.core.model.TimelineOccurrenceState
import java.time.LocalDate

/** 今日页用户意图。UI 只发送动作，不直接触碰 Repository、平台单例或导航回调。 */
sealed interface TodayUiAction {
    data object SyncAcademic : TodayUiAction
    data object DismissDynamicSyncBanner : TodayUiAction

    data object OpenImporter : TodayUiAction
    data object OpenSettings : TodayUiAction
    data class OpenMapRoom(val room: String) : TodayUiAction
    data object OpenFocus : TodayUiAction
    data object OpenElectricity : TodayUiAction
    data object OpenLibraryCatalog : TodayUiAction
    data object OpenLibrarySeats : TodayUiAction
    data object OpenEmptyClassrooms : TodayUiAction
    data object OpenCommunity : TodayUiAction
    data object OpenCompetitions : TodayUiAction
    data object OpenMoments : TodayUiAction
    data object OpenParcelHub : TodayUiAction
    data object OpenGrades : TodayUiAction
    data object OpenTimetable : TodayUiAction

    data class MarkParcelPickedUp(val parcelId: String) : TodayUiAction
    data class MarkStationParcelsPickedUp(val stationName: String) : TodayUiAction
    data object SyncParcels : TodayUiAction
    data class StopSeatDelegation(val planId: String) : TodayUiAction

    data class SaveHomeCardLayout(
        val order: List<String>,
        val hidden: Set<String>,
        val fullWidth: Set<String>
    ) : TodayUiAction
    data object ResetHomeCardLayout : TodayUiAction

    data class SetMealRecommendEnabled(val enabled: Boolean) : TodayUiAction
    data class SaveCanteenDishReview(
        val canteenBuildingId: String,
        val dishName: String,
        val stallLocation: String,
        val priceYuan: Int?,
        val stars: Int,
        val tags: List<String>,
        val comment: String
    ) : TodayUiAction
    data class DeleteCanteenDishReview(val canteenBuildingId: String, val dishName: String) : TodayUiAction
    data class TestMealRecommendation(val shuffleOffset: Int) : TodayUiAction

    /** 点击瞬间冻结的恢复输入；ViewModel 负责构造结构化命令，UI 不引用 Repository。 */
    data class RestoreCanceledOccurrence(
        val termId: String?,
        val date: LocalDate,
        val courseName: String,
        val overrideId: String?,
        val mergedRuleIds: List<String>,
        val occurrenceOverrides: List<OccurrenceOverride>,
        val writeToken: String? = null
    ) : TodayUiAction
    data class DeleteLegacyOccurrenceOverride(
        val overrideId: String,
        val writeToken: String? = null
    ) : TodayUiAction
    data object ClearLegacyRestoreTargets : TodayUiAction
    data class SelectCurrentTerm(
        val termId: String,
        val writeToken: String? = null
    ) : TodayUiAction
    data class ConsumeWriteCompletion(val token: String) : TodayUiAction

    data class LoadSnoozeOptions(
        val detailState: TimelineOccurrenceState,
        val termId: String,
        val freshDialogLoad: Boolean = false
    ) : TodayUiAction
    data class ToggleCourseReminder(
        val detailState: TimelineOccurrenceState,
        val termId: String,
        val nextEnabled: Boolean,
        val leadMinutesOverride: Int?,
        val maxSnoozesOverride: Int?
    ) : TodayUiAction
    data class SetCourseReminderLead(
        val detailState: TimelineOccurrenceState,
        val termId: String,
        val leadMinutes: Int,
        val maxSnoozesOverride: Int?
    ) : TodayUiAction
    data class SnoozeCourseReminder(
        val detailState: TimelineOccurrenceState,
        val termId: String,
        val choice: SnoozeChoice
    ) : TodayUiAction
    data class CancelClassOccurrence(
        val detailState: TimelineOccurrenceState,
        val termId: String,
        val writeToken: String? = null
    ) : TodayUiAction
}
