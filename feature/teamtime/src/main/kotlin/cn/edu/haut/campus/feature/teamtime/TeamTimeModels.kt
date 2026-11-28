package cn.edu.haut.campus.feature.teamtime

import java.time.Instant
import java.time.OffsetDateTime
import cn.edu.haut.campus.core.timeline.TimelineEventId

/** W4-07 组队找时间的 feature 内领域模型；云契约 JSON 只在本边界解析。 */

data class TeamTimeGroup(
    val groupId: String,
    val schoolId: String,
    val type: String,
    val name: String,
    val status: String,
    val memberCount: Int,
    val myRole: String,
)

data class TeamTimeMember(
    val memberId: String,
    val groupId: String,
    val cloudUserId: String,
    val displayName: String?,
    val role: String,
    val sharingLevel: String,
    val status: String,
) {
    val isActive: Boolean get() = status == "ACTIVE"
    val sharesBusyBlocks: Boolean get() = isActive && sharingLevel == "BUSY_BLOCKS_30MIN"
}

/** 自服务共享结果。刻意不保留 blocks 本体：UI 只需要数量、级别与过期时间。 */
data class TeamTimeShareSummary(
    val memberId: String,
    val sharingLevel: String,
    val blockCount: Int,
    val ttlSeconds: Int,
    val expiresAt: Instant,
    val updatedAt: Instant,
)

enum class TeamTimeAvailabilityStatus {
    AVAILABLE,
    BUSY,
    UNKNOWN,
}

data class TeamTimeMemberAvailability(
    val memberId: String,
    val status: TeamTimeAvailabilityStatus,
    val sharingLevel: String,
)

/** 云端交集候选。只允许成员状态计数/状态位，永远没有个体原始忙闲块。 */
data class TeamTimeCandidate(
    val startAt: Instant,
    val endAt: Instant,
    val availableMemberCount: Int,
    val busyMemberCount: Int,
    val unknownMemberCount: Int,
    val totalMemberCount: Int,
    val memberResults: List<TeamTimeMemberAvailability>,
) {
    val key: String get() = startAt.toString() + "/" + endAt.toString()
}

enum class TeamTimePollStatus {
    OPEN,
    CLOSED,
    CONFIRMED,
}

data class TeamTimePollOption(
    val optionId: String,
    val startAt: Instant,
    val endAt: Instant,
    val voteCount: Int,
    val voterMemberIds: List<String>,
)

data class TeamTimePoll(
    val pollId: String,
    val groupId: String,
    val schoolId: String,
    val title: String,
    val status: TeamTimePollStatus,
    val options: List<TeamTimePollOption>,
    val votingDeadlineAt: Instant,
    val createdByMemberId: String,
    val createdAt: Instant,
    val closedAt: Instant?,
    val confirmedAt: Instant?,
    val confirmedOptionId: String?,
    val confirmedEventId: String?,
    val myVoteOptionId: String?,
)

data class ConfirmedGroupEvent(
    val eventId: String,
    val pollId: String,
    val optionId: String,
    val groupId: String,
    val schoolId: String,
    val startAt: Instant,
    val endAt: Instant,
    val status: String,
    val confirmedByMemberId: String,
    val confirmedAt: Instant,
)

/** 云端确认与本地 Timeline 投影的诚实合成结果；本地失败绝不改写为云端未确认。 */
data class TeamTimeConfirmation(
    val poll: TeamTimePoll,
    val confirmedEvent: ConfirmedGroupEvent,
    val timelineEventId: TimelineEventId?,
    val timelineWriteError: String?,
)

internal fun parseTeamTimeInstant(raw: String, field: String): Instant = try {
    Instant.parse(raw)
} catch (_: Exception) {
    try {
        OffsetDateTime.parse(raw).toInstant()
    } catch (error: Exception) {
        throw IllegalArgumentException(field + " 不是合法 date-time", error)
    }
}

internal fun formatTeamTimeInstant(instant: Instant): String = instant.toString()
