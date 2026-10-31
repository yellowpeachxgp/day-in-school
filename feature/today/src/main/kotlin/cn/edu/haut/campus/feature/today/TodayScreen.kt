package cn.edu.haut.campus.feature.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "${selectedDate.format(DATE_TITLE_FMT)} ${selectedDate.dayOfWeek.ChineseLabel}",
            subtitle = term?.displayName ?: "河南工业大学",
            badgeText = snapshot.teachingWeek?.let { "第${it}周" } ?: "非教学周",
            actions = {
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 日期切换与缩放
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    XPeachFilterPill(
                        label = "前一天",
                        selected = false,
                        onClick = { selectedDate = selectedDate.minusDays(1) }
                    )
                    XPeachFilterPill(
                        label = "今天",
                        selected = selectedDate == currentNow.toLocalDate(),
                        onClick = { selectedDate = currentNow.toLocalDate() }
                    )
                    XPeachFilterPill(
                        label = "后一天",
                        selected = false,
                        onClick = { selectedDate = selectedDate.plusDays(1) }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    XPeachFilterPill(
                        label = "紧凑",
                        selected = effectiveScale <= 1.4f,
                        onClick = {
                            scope.launch { repository.setTimelineZoomScale(1.3f) }
                        }
                    )
                    XPeachFilterPill(
                        label = "标准",
                        selected = effectiveScale > 1.4f,
                        onClick = {
                            scope.launch { repository.setTimelineZoomScale(1.7f) }
                        }
                    )
                }
            }

            if (!snapshot.calendarNotice.isNullOrBlank()) {
                XPeachCard(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                ) {
                    Text(
                        text = snapshot.calendarNotice!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (courses.isEmpty()) {
                XPeachCard(
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp)
                        ) {
                            Text(
                                text = "暂未导入课表",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "登录教务系统同步本学期课程与成绩",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = onNavigateToImporter,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
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

            // 今日日程卡片
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "今日日程",
                        style = MaterialTheme.typography.titleMedium
                    )
                    XPeachStatusBadge(
                        text = currentNow.toLocalTime().format(TIME_FMT)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (snapshot.items.isEmpty()) {
                    EmptyDayTimelineBox(
                        hasAnyCourses = courses.isNotEmpty(),
                        nextClassHint = snapshot.nextClassDayHint,
                        onJumpToDate = { selectedDate = it },
                        onOpenImporter = onNavigateToImporter
                    )
                } else {
                    ProportionalDayTimelineCanvas(
                        timelineStart = snapshot.timelineStart,
                        timelineEnd = snapshot.timelineEnd,
                        dpPerMinute = effectiveScale,
                        nowTime = if (selectedDate == currentNow.toLocalDate()) currentNow.toLocalTime() else null,
                        items = snapshot.items,
                        breakIntervals = snapshot.breakIntervals,
                        onItemClick = { selectedOccurrenceForDetail = it },
                        onNavigateRoom = onNavigateToMapWithRoom
                    )
                }
            }
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                selectedOccurrenceForDetail = null
                                onNavigateToMapWithRoom(occ.rawRoom)
                            },
                            shape = RoundedCornerShape(8.dp)
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
                            shape = RoundedCornerShape(8.dp)
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
        borderColor = if (activeOrNext != null) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        onClick = if (activeOrNext != null) {
            { onCardClick(activeOrNext) }
        } else null
    ) {
        if (activeBreak != null) {
            XPeachWell(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
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
                        style = MaterialTheme.typography.labelMedium,
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                XPeachStatusBadge(
                    text = if (isOngoing) "上课中" else "下一节"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = occ.courseName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${occ.startTime.format(TIME_FMT)}–${occ.endTime.format(TIME_FMT)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "${occ.rawRoom}${occ.teacher?.let { " · $it" } ?: ""} (${occ.periodRangeText})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = activeOrNext.statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Button(
                    onClick = { onNavigateBuilding(occ.rawRoom) },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("导航", maxLines = 1, softWrap = false)
                }
            }
        } else {
            Text(
                text = if (totalTodayClasses == 0) "今日无课" else "今日 $totalTodayClasses 节课已结束",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (nextDayHint != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "下次课：${nextDayHint.nextDate.format(DATE_TITLE_FMT)} ${nextDayHint.firstCourseStartTime.format(TIME_FMT)} ${nextDayHint.firstCourseName} (${nextDayHint.firstCourseRoom})",
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
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (hasAnyCourses) "当日无排课" else "暂无课程数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (nextClassHint != null) {
                OutlinedButton(
                    onClick = { onJumpToDate(nextClassHint.nextDate) },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "查看 ${nextClassHint.nextDate.format(DATE_TITLE_FMT)} (${nextClassHint.firstCourseName})",
                        maxLines = 1,
                        softWrap = false
                    )
                }
            } else if (!hasAnyCourses) {
                OutlinedButton(
                    onClick = onOpenImporter,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("导入课表", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun ProportionalDayTimelineCanvas(
    timelineStart: LocalTime,
    timelineEnd: LocalTime,
    dpPerMinute: Float,
    nowTime: LocalTime?,
    items: List<TimelineOccurrenceState>,
    breakIntervals: List<ScheduleBreakInterval>,
    onItemClick: (TimelineOccurrenceState) -> Unit,
    onNavigateRoom: (String) -> Unit
) {
    // 根据当天实际首末节课自适应裁剪时间轴范围，避免早晚无课区域出现大段空白
    val firstStart = items.minOfOrNull { it.occurrence.startTime } ?: timelineStart
    val lastEnd = items.maxOfOrNull { it.occurrence.endTime } ?: timelineEnd
    val effectiveStart = LocalTime.of((firstStart.hour - 1).coerceAtLeast(8), 0)
    val effectiveEnd = LocalTime.of((lastEnd.hour + 1).coerceAtMost(22), 0)

    val totalMinutes = Duration.between(effectiveStart, effectiveEnd).toMinutes().coerceAtLeast(60)
    val totalHeightDp = (totalMinutes * dpPerMinute).dp
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(totalHeightDp)
    ) {
        // 1. 左侧整点时间刻度与水平辅助线
        for (hour in effectiveStart.hour..effectiveEnd.hour) {
            val tickTime = LocalTime.of(hour, 0)
            val yDp = (Duration.between(effectiveStart, tickTime).toMinutes() * dpPerMinute).dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = yDp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format("%02d:00", hour),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.width(42.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(axisColor)
                )
            }
        }

        // 2. 竖向时间轴线
        Box(
            modifier = Modifier
                .offset(x = 46.dp)
                .width(1.dp)
                .fillMaxHeight()
                .background(axisColor)
        )

        // 3. 课程块与课间条
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 54.dp)
        ) {
            for (interval in breakIntervals) {
                val gapTopDp = (Duration.between(effectiveStart, interval.startTime).toMinutes() * dpPerMinute).dp
                val gapHeightDp = (interval.durationMinutes * dpPerMinute).coerceAtLeast(20f).dp

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = gapTopDp)
                        .height(gapHeightDp)
                        .padding(vertical = 2.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Surface(
                        color = if (interval.isActiveNow) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = if (interval.isActiveNow) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                } else {
                                    axisColor
                                },
                                shape = RoundedCornerShape(6.dp)
                            )
                            .then(
                                if (interval.requiresRoomChange) {
                                    Modifier.clickable { onNavigateRoom(interval.toRoom) }
                                } else Modifier
                            )
                    ) {
                        Text(
                            text = buildString {
                                append("${interval.label} ${interval.startTime.format(TIME_FMT)}–${interval.endTime.format(TIME_FMT)}")
                                interval.roomTransferText?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (interval.isActiveNow) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            for (item in items) {
                val occ = item.occurrence
                val yOffset = (Duration.between(effectiveStart, occ.startTime).toMinutes() * dpPerMinute).dp
                val durationMins = Duration.between(occ.startTime, occ.endTime).toMinutes().coerceAtLeast(30)
                val cardHeight = (durationMins * dpPerMinute).coerceAtLeast(48f).dp
                val isFinished = item.status == ClassStatus.FINISHED
                val isOngoing = item.status == ClassStatus.IN_PROGRESS
                val baseColor = Color(occ.colorArgb)

                val colCount = occ.conflictColumnCount.coerceAtLeast(1)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = yOffset)
                        .height(cardHeight)
                ) {
                    if (occ.conflictColumnIndex > 0) {
                        Spacer(modifier = Modifier.weight(occ.conflictColumnIndex.toFloat()))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(end = if (colCount > 1) 4.dp else 0.dp, bottom = 2.dp)
                            .alpha(if (isFinished) 0.6f else 1.0f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isOngoing) baseColor.copy(alpha = 0.16f)
                                else baseColor.copy(alpha = 0.10f)
                            )
                            .border(
                                width = if (isOngoing) 1.5.dp else 1.dp,
                                color = if (isOngoing) primaryColor else baseColor.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onItemClick(item) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        // 紧凑置顶排布，彻底消除卡片内部大段空白断层
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = occ.courseName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = item.statusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isOngoing) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            Text(
                                text = "${occ.rawRoom} · ${occ.startTime.format(TIME_FMT)}–${occ.endTime.format(TIME_FMT)} (${occ.periodRangeText})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            occ.internalBreaksSummary?.let { brkText ->
                                Text(
                                    text = brkText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (item.activeInternalBreak != null) {
                                        primaryColor
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    val remainingCols = colCount - occ.conflictColumnIndex - 1
                    if (remainingCols > 0) {
                        Spacer(modifier = Modifier.weight(remainingCols.toFloat()))
                    }
                }
            }
        }

        // 4. 当前时间线
        if (nowTime != null && !nowTime.isBefore(effectiveStart) && !nowTime.isAfter(effectiveEnd)) {
            val nowYDp = (Duration.between(effectiveStart, nowTime).toMinutes() * dpPerMinute).dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = nowYDp - 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = primaryColor,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = nowTime.format(TIME_FMT),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(primaryColor)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.5.dp)
                        .background(primaryColor)
                )
            }
        }
    }
}
