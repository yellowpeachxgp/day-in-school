package cn.edu.haut.campus.feature.teamtime.state

import androidx.compose.runtime.Immutable

/** 阶段 S 统一 11 态矩阵。 */
enum class TeamTimeStatus {
    LOADING,
    EMPTY,
    CONTENT,
    PARTIAL,
    STALE,
    ERROR_RETRYABLE,
    ERROR_TERMINAL,
    NEEDS_PERMISSION,
    NEEDS_LOGIN,
    OFFLINE,
    UNSUPPORTED,
}

@Immutable
data class TeamTimeGroupUi(
    val id: String,
    val name: String,
    val typeLabel: String,
    val memberCount: Int,
    val roleLabel: String,
)

@Immutable
data class TeamTimeShareUi(
    val shared: Boolean = false,
    val statusLabel: String = "未共享忙闲",
    val detailLabel: String = "只有你显式点击共享后，才会上传 30 分钟粒度时间块",
    val blockCount: Int = 0,
    val expiresLabel: String? = null,
)

@Immutable
data class TeamTimeCandidateUi(
    val key: String,
    val timeLabel: String,
    val quorumLabel: String,
    val unknownLabel: String?,
    val personalLabel: String,
    val personalConflicts: Boolean,
    val selected: Boolean,
)

@Immutable
data class TeamTimePollOptionUi(
    val optionId: String,
    val timeLabel: String,
    val voteLabel: String,
    val mine: Boolean,
    val confirmed: Boolean,
)

@Immutable
data class TeamTimePollUi(
    val pollId: String,
    val title: String,
    val statusLabel: String,
    val deadlineLabel: String,
    val options: List<TeamTimePollOptionUi>,
    val confirmedEventId: String?,
)

/** 组队找时间不可变渲染快照；只包含聚合计数与个人本机对照，不包含他人原始忙闲块。 */
@Immutable
data class TeamTimeUiState(
    val status: TeamTimeStatus,
    val groups: List<TeamTimeGroupUi> = emptyList(),
    val selectedGroupId: String? = null,
    val groupNameInput: String = "",
    val pollTitleInput: String = "",
    val durationMinutes: Int = 60,
    val minAttendees: Int = 1,
    val share: TeamTimeShareUi = TeamTimeShareUi(),
    val localBusyBlockCount: Int = 0,
    val queryWindowLabel: String = "",
    val candidates: List<TeamTimeCandidateUi> = emptyList(),
    val poll: TeamTimePollUi? = null,
    val working: Boolean = false,
    val errorMessage: String? = null,
    val notice: String? = null,
) {
    val selectedCandidateCount: Int get() = candidates.count { it.selected }
    val canCreatePoll: Boolean get() = selectedCandidateCount >= 2 && !working
}
