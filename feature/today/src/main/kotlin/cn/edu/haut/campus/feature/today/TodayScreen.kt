package cn.edu.haut.campus.feature.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.ScheduleBreakInterval
import cn.edu.haut.campus.core.model.TimelineOccurrenceState
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.UUID

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TITLE_FMT = DateTimeFormatter.ofPattern("MM月dd日")

@Composable
fun TodayScreen(
    repository: CampusRepository,
    onNavigateToMapWithRoom: (String) -> Unit,
    onNavigateToImporter: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val term by repository.currentTermFlow.collectAsState(initial = null)
    val courses by repository.coursesFlow.collectAsState(initial = emptyList())
    val rules by repository.rulesFlow.collectAsState(initial = emptyList())
    val periodTimes by repository.periodTimesFlow.collectAsState(initial = emptyList())
    val calendarOverrides by repository.calendarOverridesFlow.collectAsState(initial = emptyList())
    val occurrenceOverrides by repository.occurrenceOverridesFlow.collectAsState(initial = emptyList())
    val buildings by repository.buildingsFlow.collectAsState(initial = emptyList())
    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())

    var currentNow by remember { mutableStateOf(ZonedDateTime.now(HAUT_ZONE_ID)) }
    var selectedDate by remember { mutableStateOf(currentNow.toLocalDate()) }
    var selectedOccurrenceForDetail by remember { mutableStateOf<TimelineOccurrenceState?>(null) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val updatedNow = ZonedDateTime.now(HAUT_ZONE_ID)
                val previousDate = currentNow.toLocalDate()
                currentNow = updatedNow
                if (selectedDate == previousDate && updatedNow.toLocalDate() != previousDate) {
                    selectedDate = updatedNow.toLocalDate()
                }
                val secondsToNextMinute = (60 - updatedNow.second).coerceIn(5, 60)
                delay(secondsToNextMinute * 1000L)
            }
        }
    }

    val effectiveScale = prefs.timelineZoomScale.coerceIn(1.1f, 2.2f)
    val todayDate = currentNow.toLocalDate()

    val snapshot = remember(
        selectedDate,
        currentNow,
        term,
        courses,
        rules,
        periodTimes,
        calendarOverrides,
        occurrenceOverrides,
        buildings,
        effectiveScale
    ) {
        ScheduleEngine.buildDayTimelineSnapshot(
            date = selectedDate,
            now = currentNow,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides,
            buildings = buildings,
            pxPerMinute = effectiveScale
        )
    }

    // 计算当前选中日期所在周的周一至周日，以及每天是否有课
    val weekStartMonday = remember(selectedDate) {
        selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
    val weekDays = remember(weekStartMonday) {
        (0L..6L).map { weekStartMonday.plusDays(it) }
    }
    val weekHasClassesMap = remember(
        weekStartMonday,
         snapshot.teachingWeek,
        term,
        courses,
        rules,
        periodTimes,
        calendarOverrides,
        occurrenceOverrides,
        buildings
    ) {
        val activeTerm = term
        val weekNo = snapshot.teachingWeek
        if (activeTerm != null && weekNo != null) {
            val map = ScheduleEngine.resolveOccurrencesForTeachingWeek(
                teachingWeek = weekNo,
                term = activeTerm,
                courses = courses,
                rules = rules,
                periodTimes = periodTimes,
                calendarOverrides = calendarOverrides,
                occurrenceOverrides = occurrenceOverrides,
                buildings = buildings
            )
            map.mapValues { it.value.isNotEmpty() }
        } else {
            emptyMap()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = if (selectedDate == todayDate) "今天" else selectedDate.format(DATE_TITLE_FMT),
            subtitle = "${selectedDate.format(DATE_TITLE_FMT)} ${selectedDate.dayOfWeek.ChineseLabel} · ${term?.displayName ?: "河南工业大学"}",
            badgeText = snapshot.teachingWeek?.let { "第 ${it} 周" } ?: "非教学周",
            actions = {
                if (selectedDate != todayDate) {
                    XPeachFilterPill(
                        label = "回今天",
                        selected = true,
                        onClick = { selectedDate = todayDate }
                    )
                }
                XPeachIconButton(
                    icon = Icons.Default.CloudDownload,
                    contentDescription = "同步课表",
                    onClick = onNavigateToImporter
                )
                XPeachIconButton(
                    icon = Icons.Default.Settings,
                    contentDescription = "设置",
                    onClick = onNavigateToSettings
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. 轻量化 7 日横向周历条（取代繁琐的前一天/后一天/缩放按钮群）
            WeekDaySelectorStrip(
                weekDays = weekDays,
                selectedDate = selectedDate,
                todayDate = todayDate,
                hasClassesByDate = weekHasClassesMap,
                onSelectDate = { selectedDate = it },
                onPreviousWeek = { selectedDate = selectedDate.minusWeeks(1) },
                onNextWeek = { selectedDate = selectedDate.plusWeeks(1) }
            )

            if (!snapshot.calendarNotice.isNullOrBlank()) {
                XPeachCard(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    borderColor = Color.Transparent
                ) {
                    Text(
                        text = snapshot.calendarNotice!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // 2. 焦点卡片（当前/下一节课 或 同步引导）
            if (courses.isEmpty()) {
                XPeachCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "尚未导入课表",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "同步教务课表与成绩后自动生成每日日程",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = onNavigateToImporter,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("去同步", maxLines = 1, softWrap = false)
                        }
                    }
                }
            } else {
                NextClassHeroCard(
                    activeOrNext = snapshot.activeOrNextClass,
                    activeBreak = snapshot.activeBreakInterval,
                    nextDayHint = snapshot.nextClassDayHint,
                    totalTodayClasses = snapshot.items.size,
                    onNavigateBuilding = onNavigateToMapWithRoom,
                    onCardClick = { selectedOccurrenceForDetail = it }
                )
            }

            // 3. 流式日程列表（清新呼吸感卡片流，彻底告别生硬网格线与挤字）
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectedDate == todayDate) "今日课程" else "当日课程",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (snapshot.items.isEmpty()) "无排课" else "共 ${snapshot.items.size} 节",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (snapshot.items.isEmpty()) {
                    EmptyDayTimelineBox(
                        hasAnyCourses = courses.isNotEmpty(),
                        nextClassHint = snapshot.nextClassDayHint,
                        onJumpToDate = { selectedDate = it },
                        onOpenImporter = onNavigateToImporter
                    )
                } else {
                    MinimalistDayScheduleFlow(
                        items = snapshot.items,
                        breakIntervals = snapshot.breakIntervals,
                        onItemClick = { selectedOccurrenceForDetail = it },
                        onNavigateRoom = onNavigateToMapWithRoom
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    val detailState = selectedOccurrenceForDetail
    if (detailState != null) {
        val occ = detailState.occurrence
        AlertDialog(
            onDismissRequest = { selectedOccurrenceForDetail = null },
            title = { Text(occ.courseName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("状态：${detailState.statusText}", fontWeight = FontWeight.Medium)
                    Text("时间：${occ.startTime.format(TIME_FMT)}–${occ.endTime.format(TIME_FMT)} (${occ.periodRangeText})")
                    occ.internalBreaksSummary?.let { brkSummary ->
                        Text(
                            text = "课间：$brkSummary",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text("教室：${occ.rawRoom}")
                    if (!occ.teacher.isNullOrBlank()) {
                        Text("教师：${occ.teacher}")
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                selectedOccurrenceForDetail = null
                                onNavigateToMapWithRoom(occ.rawRoom)
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("路线导航", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    if (term != null) {
                                        repository.saveOccurrenceOverride(
                                            OccurrenceOverride(
                                                id = "ov_cancel_${occ.date}_${occ.ruleId ?: UUID.randomUUID()}",
                                                termId = term!!.id,
                                                courseRuleId = occ.ruleId,
                                                courseId = occ.courseId,
                                                date = occ.date,
                                                canceled = true,
                                                reason = "单次停课"
                                            )
                                        )
                                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                    }
                                    selectedOccurrenceForDetail = null
                                }
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("今日停课", maxLines = 1, softWrap = false)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedOccurrenceForDetail = null }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
private fun WeekDaySelectorStrip(
    weekDays: List<LocalDate>,
    selectedDate: LocalDate,
    todayDate: LocalDate,
    hasClassesByDate: Map<LocalDate, Boolean>,
    onSelectDate: (LocalDate) -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit
) {
    XPeachCard(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
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

            for (date in weekDays) {
                val isSelected = date == selectedDate
                val isToday = date == todayDate
                val hasClasses = hasClassesByDate[date] == true

                Column(
                    modifier = Modifier
                        .weight(1f)
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
                            isSelected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                            isToday -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = "${date.dayOfMonth}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Medium,
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimary
                            isToday -> MaterialTheme.colorScheme.primary
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
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
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

@Composable
private fun NextClassHeroCard(
    activeOrNext: TimelineOccurrenceState?,
    activeBreak: ScheduleBreakInterval?,
    nextDayHint: cn.edu.haut.campus.core.model.NextClassDayHint?,
    totalTodayClasses: Int,
    onNavigateBuilding: (String) -> Unit,
    onCardClick: (TimelineOccurrenceState) -> Unit
) {
    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (activeOrNext != null) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surface
        },
        borderColor = if (activeOrNext != null) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        },
        onClick = if (activeOrNext != null) {
            { onCardClick(activeOrNext) }
        } else null
    ) {
        if (activeBreak != null) {
            XPeachWell(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            ) {
                Text(
                    text = "${activeBreak.label} · ${activeBreak.startTime.format(TIME_FMT)}–${activeBreak.endTime.format(TIME_FMT)}" +
                        (activeBreak.minutesRemainingInBreak?.let { "（剩 ${it} 分钟）" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                activeBreak.roomTransferText?.let { transfer ->
                    Text(
                        text = transfer,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (activeOrNext != null) {
            val occ = activeOrNext.occurrence
            val isOngoing = activeOrNext.status == ClassStatus.IN_PROGRESS

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    XPeachStatusBadge(
                        text = if (isOngoing) "上课中" else "下一节"
                    )
                    Text(
                        text = activeOrNext.statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Text(
                    text = "${occ.startTime.format(TIME_FMT)}–${occ.endTime.format(TIME_FMT)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = occ.courseName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${occ.rawRoom}${occ.teacher?.let { " · $it" } ?: ""} (${occ.periodRangeText})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                )

                FilledTonalButton(
                    onClick = { onNavigateBuilding(occ.rawRoom) },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("导航", maxLines = 1, softWrap = false)
                }
            }
        } else {
            Text(
                text = if (totalTodayClasses == 0) "今日无课，好好休息" else "今日 $totalTodayClasses 节课已全部结束",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (nextDayHint != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "下次课：${nextDayHint.nextDate.format(DATE_TITLE_FMT)} ${nextDayHint.firstCourseStartTime.format(TIME_FMT)} · ${nextDayHint.firstCourseName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyDayTimelineBox(
    hasAnyCourses: Boolean,
    nextClassHint: cn.edu.haut.campus.core.model.NextClassDayHint?,
    onJumpToDate: (LocalDate) -> Unit,
    onOpenImporter: () -> Unit
) {
    XPeachWell(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (hasAnyCourses) "当日没有安排课程" else "暂无课程数据",
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
 * 极简流式日程列表：左列展示起止时间与节次，中列圆角彩条，右列呼吸感课程卡片，课间休息作为轻量连接条自然穿插。
 */
@Composable
private fun MinimalistDayScheduleFlow(
    items: List<TimelineOccurrenceState>,
    breakIntervals: List<ScheduleBreakInterval>,
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
            val baseColor = Color(occ.colorArgb)

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
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = baseColor,
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
                        .background(baseColor)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // 右侧课程内容柔和卡片
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onItemClick(item) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isOngoing) {
                        baseColor.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                    },
                    border = if (isOngoing) {
                        androidx.compose.foundation.BorderStroke(1.dp, baseColor.copy(alpha = 0.45f))
                    } else null
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
                                fontWeight = if (isOngoing) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isOngoing) baseColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Text(
                            text = "${occ.rawRoom}${occ.teacher?.let { " · $it" } ?: ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        occ.internalBreaksSummary?.let { brkText ->
                            Text(
                                text = brkText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item.activeInternalBreak != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
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
                            .padding(start = 64.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(18.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
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
                                    MaterialTheme.colorScheme.primary
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
