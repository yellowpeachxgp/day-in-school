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

internal const val STRIP_TOTAL_DAYS = 2001
internal const val STRIP_CENTER_INDEX = 1000

internal fun doesDateHaveClasses(
    date: LocalDate,
    term: Term?,
    termRules: List<CourseScheduleRule>,
    calendarOverrides: List<CalendarOverride>,
    occurrenceOverrides: List<OccurrenceOverride>
): Boolean {
    if (term == null || termRules.isEmpty()) return false
    val resolved = TeachingWeekCalculator.resolveDate(date, term, calendarOverrides)
    if (resolved.status == TermDateStatus.HOLIDAY_NO_CLASS) return false
    val weekNo = resolved.teachingWeek ?: return false
    val canceledRulesOnDate = occurrenceOverrides
        .filter { it.date == date && it.canceled }
        .mapNotNull { it.courseRuleId }
        .toSet()
    return termRules.any { rule ->
        rule.id !in canceledRulesOnDate &&
            rule.weekday == resolved.weekdayUsed &&
            (rule.weeks.isEmpty() || weekNo in rule.weeks)
    }
}

@Composable
internal fun WeekDaySelectorStrip(
    selectedDate: LocalDate,
    todayDate: LocalDate,
    term: Term?,
    termRules: List<CourseScheduleRule>,
    calendarOverrides: List<CalendarOverride>,
    occurrenceOverrides: List<OccurrenceOverride>,
    onSelectDate: (LocalDate) -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit
) {
    val initialMonday = remember {
        selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
    val initialFirstIndex = remember(todayDate) {
        (STRIP_CENTER_INDEX + ChronoUnit.DAYS.between(todayDate, initialMonday).toInt())
            .coerceIn(0, STRIP_TOTAL_DAYS - 7)
    }
    val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = initialFirstIndex)

    LaunchedEffect(selectedDate, todayDate) {
        val selectedIndex = (STRIP_CENTER_INDEX + ChronoUnit.DAYS.between(todayDate, selectedDate).toInt())
            .coerceIn(0, STRIP_TOTAL_DAYS - 1)
        val visibleIndices = lazyListState.layoutInfo.visibleItemsInfo.map { it.index }
        if (visibleIndices.isNotEmpty() && selectedIndex !in visibleIndices) {
            val targetMonday = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val targetStart = (STRIP_CENTER_INDEX + ChronoUnit.DAYS.between(todayDate, targetMonday).toInt())
                .coerceIn(0, STRIP_TOTAL_DAYS - 7)
            lazyListState.animateScrollToItem(targetStart)
        }
    }

    XPeachCard(
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onPreviousWeek),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronLeft,
                    contentDescription = "上一周",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            BoxWithConstraints(
                modifier = Modifier.weight(1f)
            ) {
                val itemWidth = maxWidth / 7
                LazyRow(
                    state = lazyListState,
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(
                        count = STRIP_TOTAL_DAYS,
                        key = { index -> index }
                    ) { index ->
                        val date = remember(todayDate, index) {
                            todayDate.plusDays((index - STRIP_CENTER_INDEX).toLong())
                        }
                        val isSelected = date == selectedDate
                        val isToday = date == todayDate
                        val hasClasses = remember(date, term, termRules, calendarOverrides, occurrenceOverrides) {
                            doesDateHaveClasses(date, term, termRules, calendarOverrides, occurrenceOverrides)
                        }

                        Column(
                            modifier = Modifier
                                .width(itemWidth)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        isToday -> MaterialTheme.colorScheme.primaryContainer
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable { onSelectDate(date) }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = date.dayOfWeek.ChineseLabel.replace("周", ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.88f)
                                    isToday -> XPeachTheme.readablePrimary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                text = if (date.dayOfMonth == 1) "${date.monthValue}月" else "${date.dayOfMonth}",
                                style = if (date.dayOfMonth == 1 && date.monthValue >= 10) {
                                    MaterialTheme.typography.labelSmall
                                } else if (date.dayOfMonth == 1) {
                                    MaterialTheme.typography.labelMedium
                                } else {
                                    MaterialTheme.typography.titleMedium
                                },
                                fontWeight = if (isSelected || isToday || date.dayOfMonth == 1) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Medium
                                },
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                    isToday -> XPeachTheme.readablePrimary
                                    date.dayOfMonth == 1 -> XPeachTheme.readablePrimary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                softWrap = false
                            )
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            !hasClasses -> Color.Transparent
                                            isSelected -> MaterialTheme.colorScheme.onPrimary
                                            isToday -> XPeachTheme.readablePrimary
                                            else -> XPeachTheme.readablePrimary
                                        }
                                    )
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onNextWeek),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "下一周",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
