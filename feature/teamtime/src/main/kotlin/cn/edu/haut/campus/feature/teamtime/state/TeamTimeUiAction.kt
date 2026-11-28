package cn.edu.haut.campus.feature.teamtime.state

/** 组队找时间用户意图；共享/撤回/投票/确认全部必须显式触发。 */
sealed interface TeamTimeUiAction {
    data object BackClicked : TeamTimeUiAction
    data object RetryClicked : TeamTimeUiAction
    data object LoginRequested : TeamTimeUiAction
    data object PermissionRequested : TeamTimeUiAction
    data class GroupSelected(val groupId: String) : TeamTimeUiAction
    data class GroupNameChanged(val value: String) : TeamTimeUiAction
    data class GroupTypeSelected(val type: String) : TeamTimeUiAction
    data object CreateGroupClicked : TeamTimeUiAction
    data object ShareBusyBlocksClicked : TeamTimeUiAction
    data object WithdrawSharingClicked : TeamTimeUiAction
    data class DurationSelected(val minutes: Int) : TeamTimeUiAction
    data class MinAttendeesChanged(val value: Int) : TeamTimeUiAction
    data object QueryAvailabilityClicked : TeamTimeUiAction
    data class CandidateToggled(val key: String) : TeamTimeUiAction
    data class PollTitleChanged(val value: String) : TeamTimeUiAction
    data object CreatePollClicked : TeamTimeUiAction
    data class VoteClicked(val optionId: String) : TeamTimeUiAction
    data class ConfirmClicked(val optionId: String) : TeamTimeUiAction
}
