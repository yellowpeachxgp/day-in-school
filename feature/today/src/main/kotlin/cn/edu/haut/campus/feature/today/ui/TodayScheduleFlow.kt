package cn.edu.haut.campus.feature.today.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.decodeOnlineDevicesJson
import cn.edu.haut.campus.core.database.isPendingPickup
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachSwitch
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.AggregatedDishRating
import cn.edu.haut.campus.core.domain.CampusDiningRecommender
import cn.edu.haut.campus.core.domain.CampusStudyRoomCatalog
import cn.edu.haut.campus.core.domain.CanteenDishReview
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.ClassEndMealRecommendation
import cn.edu.haut.campus.core.domain.ReminderPlanner
import cn.edu.haut.campus.core.domain.ReminderPolicyEngine
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.HomeCardModule
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PresetCampusCommunityCatalog
import cn.edu.haut.campus.core.model.ReminderEventOverride
import cn.edu.haut.campus.core.model.ReminderKind
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.ReminderTaskStatus
import cn.edu.haut.campus.core.model.ScheduleBreakInterval
import cn.edu.haut.campus.core.model.SnoozeOptionEvaluation
import cn.edu.haut.campus.core.model.SnoozeOutcome
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.TermDateStatus
import cn.edu.haut.campus.core.model.TimelineOccurrenceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import cn.edu.haut.campus.feature.today.state.TodayScreenStatus
import cn.edu.haut.campus.feature.today.state.TodayUiAction
import cn.edu.haut.campus.feature.today.state.TodayUiState

internal fun formatOccurrenceReminderBadge(
    termId: String,
    item: TimelineOccurrenceState,
    globalReminderEnabled: Boolean,
    defaultLeadMinutes: Int,
    tasksByEventKey: Map<String, ReminderTaskDomain>,
    overridesByScope: Map<String, ReminderEventOverride>
): String? {
    if (item.status == ClassStatus.FINISHED) return null
    val occ = item.occurrence
    val eventKey = "CLASS:$termId:${occ.occurrenceId}"
    val occOverride = overridesByScope["OCCURRENCE:$eventKey"]
    val courseOverride = overridesByScope["COURSE:${occ.courseId}"]
    val enabled = occOverride?.enabledOverride
        ?: courseOverride?.enabledOverride
        ?: globalReminderEnabled
    if (!enabled) return "已静音"

    val activeTask = tasksByEventKey[eventKey]
    if (activeTask != null) {
        val triggerTime = Instant.ofEpochMilli(activeTask.scheduledAtEpochMs)
            .atZone(HAUT_ZONE_ID)
            .toLocalTime()
            .format(TIME_FMT)
        return when (activeTask.status) {
            ReminderTaskStatus.SNOOZED -> "$triggerTime 再提醒"
            ReminderTaskStatus.FIRED -> "已提醒"
            ReminderTaskStatus.PENDING -> "$triggerTime 提醒"
            else -> null
        }
    }

    if (item.status == ClassStatus.UPCOMING) {
        val leadMinutes = occOverride?.leadMinutesOverride
            ?: courseOverride?.leadMinutesOverride
            ?: defaultLeadMinutes
        return "课前${leadMinutes}分钟提醒"
    }
    return null
}

@Composable
internal fun EmptyDayTimelineBox(
    hasAnyCourses: Boolean,
    nextClassHint: cn.edu.haut.campus.core.model.NextClassDayHint?,
    onJumpToDate: (LocalDate) -> Unit,
    onOpenImporter: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        XPeachInsetDivider(verticalPadding = 4.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (hasAnyCourses) "当日无课" else "暂未导入课表",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (nextClassHint != null) {
                FilledTonalButton(
                    onClick = { onJumpToDate(nextClassHint.nextDate) },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "查看 ${nextClassHint.nextDate.format(DATE_TITLE_FMT)} (${nextClassHint.firstCourseName})",
                        maxLines = 1,
                        softWrap = false
                    )
                }
            } else if (!hasAnyCourses) {
                FilledTonalButton(
                    onClick = onOpenImporter,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("导入课表", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/**
 * 极简流式日程列表：左列展示起止时间与节次，中列圆角彩条，右列呼吸感课程卡片，行内高亮正在上课/下一节课并提供一键去教室。
 */
@Composable
internal fun MinimalistDayScheduleFlow(
    items: List<TimelineOccurrenceState>,
    breakIntervals: List<ScheduleBreakInterval>,
    highlightedOccurrenceId: String?,
    termId: String,
    globalReminderEnabled: Boolean,
    defaultLeadMinutes: Int,
    tasksByEventKey: Map<String, ReminderTaskDomain>,
    overridesByScope: Map<String, ReminderEventOverride>,
    onItemClick: (TimelineOccurrenceState) -> Unit,
    onNavigateRoom: (String) -> Unit
) {
    val sortedItems = remember(items) {
        items.sortedBy { it.occurrence.startTime }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        sortedItems.forEachIndexed { index, item ->
            val occ = item.occurrence
            val isFinished = item.status == ClassStatus.FINISHED
            val isOngoing = item.status == ClassStatus.IN_PROGRESS
            val isHighlighted = isOngoing || (highlightedOccurrenceId != null && occ.occurrenceId == highlightedOccurrenceId)
            val accentColor = XPeachTheme.courseAccent(occ.colorArgb)
            val reminderBadge = formatOccurrenceReminderBadge(
                termId = termId,
                item = item,
                globalReminderEnabled = globalReminderEnabled,
                defaultLeadMinutes = defaultLeadMinutes,
                tasksByEventKey = tasksByEventKey,
                overridesByScope = overridesByScope
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .alpha(if (isFinished) 0.62f else 1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧时间与节次列
                Column(
                    modifier = Modifier
                        .width(52.dp)
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = occ.startTime.format(TIME_FMT),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = occ.endTime.format(TIME_FMT),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = occ.periodRangeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = accentColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 竖向彩色圆角指示条
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(accentColor)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // 右侧课程内容柔和卡片
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onItemClick(item) },
                    shape = RoundedCornerShape(14.dp),
                    color = when {
                        isOngoing -> XPeachTheme.courseContainer(occ.colorArgb, active = true)
                        isHighlighted -> XPeachTheme.courseContainer(occ.colorArgb, active = false)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = when {
                        isOngoing -> androidx.compose.foundation.BorderStroke(
                            1.dp,
                            XPeachTheme.courseBorder(occ.colorArgb, active = true)
                        )
                        isHighlighted -> androidx.compose.foundation.BorderStroke(
                            0.9.dp,
                            XPeachTheme.courseBorder(occ.colorArgb, active = false)
                        )
                        XPeachTheme.isLiquidGlass -> androidx.compose.foundation.BorderStroke(
                            0.8.dp,
                            XPeachTheme.specularRimBrush(intensity = 0.72f)
                        )
                        XPeachTheme.isDark -> androidx.compose.foundation.BorderStroke(
                            0.8.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f)
                        )
                        else -> null
                    }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = occ.courseName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            Text(
                                text = item.statusText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isHighlighted) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${occ.rawRoom}${occ.teacher?.let { " · $it" } ?: ""}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            if (isHighlighted && occ.rawRoom.isNotBlank()) {
                                XPeachFilterPill(
                                    label = "去教室",
                                    selected = isOngoing,
                                    onClick = { onNavigateRoom(occ.rawRoom) }
                                )
                            }
                        }

                        if (!reminderBadge.isNullOrBlank()) {
                            Text(
                                text = reminderBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = XPeachTheme.readablePrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        occ.internalBreaksSummary?.let { brkText ->
                            Text(
                                text = brkText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item.activeInternalBreak != null) {
                                    XPeachTheme.readablePrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 两门课之间的课间连接条
            val nextItem = sortedItems.getOrNull(index + 1)
            if (nextItem != null) {
                val matchingBreak = breakIntervals.firstOrNull {
                    it.startTime == occ.endTime && it.endTime == nextItem.occurrence.startTime
                }
                if (matchingBreak != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 65.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(18.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                        Spacer(modifier = Modifier.width(13.dp))
                        Surface(
                            color = if (matchingBreak.isActiveNow) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.then(
                                if (matchingBreak.requiresRoomChange) {
                                    Modifier.clickable { onNavigateRoom(matchingBreak.toRoom) }
                                } else Modifier
                            )
                        ) {
                            Text(
                                text = buildString {
                                    append("${matchingBreak.label} ${matchingBreak.durationMinutes} 分钟")
                                    matchingBreak.roomTransferText?.let { append(" · $it") }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (matchingBreak.isActiveNow) {
                                    XPeachTheme.readablePrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    horizontal = if (matchingBreak.isActiveNow) 10.dp else 0.dp,
                                    vertical = 2.dp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
