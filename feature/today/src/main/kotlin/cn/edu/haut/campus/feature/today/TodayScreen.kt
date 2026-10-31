package cn.edu.haut.campus.feature.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.geometry.Offset
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
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.OccurrenceOverride
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

@OptIn(ExperimentalMaterial3Api::class)
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

    // 前台生命周期感知的每分钟时钟：仅当 App 在前台 STARTED 状态时更新，切后台自动停止重绘
    var currentNow by remember { mutableStateOf(ZonedDateTime.now(HAUT_ZONE_ID)) }
    var selectedDate by remember { mutableStateOf(currentNow.toLocalDate()) }
    var selectedOccurrenceForDetail by remember { mutableStateOf<TimelineOccurrenceState?>(null) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val updatedNow = ZonedDateTime.now(HAUT_ZONE_ID)
                val previousDate = currentNow.toLocalDate()
                currentNow = updatedNow
                // 跨日自动切换到新的一天
                if (selectedDate == previousDate && updatedNow.toLocalDate() != previousDate) {
                    selectedDate = updatedNow.toLocalDate()
                }
                val secondsToNextMinute = (60 - updatedNow.second).coerceIn(5, 60)
                delay(secondsToNextMinute * 1000L)
            }
        }
    }

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
        prefs.timelineZoomScale
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
            pxPerMinute = prefs.timelineZoomScale
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${selectedDate.format(DATE_TITLE_FMT)}  ${selectedDate.dayOfWeek.ChineseLabel}",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = snapshot.teachingWeek?.let { "第 $it 教学周" } ?: "非教学周",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Text(
                            text = term?.displayName ?: "河南工业大学 · 莲花街校区",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToImporter) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "导入课表与成绩")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置与小组件")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 日期快捷切换与缩放条
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = { selectedDate = selectedDate.minusDays(1) },
                        label = { Text("前一天") }
                    )
                    AssistChip(
                        onClick = { selectedDate = currentNow.toLocalDate() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Today,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text("今天") }
                    )
                    AssistChip(
                        onClick = { selectedDate = selectedDate.plusDays(1) },
                        label = { Text("后一天") }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                repository.setTimelineZoomScale(prefs.timelineZoomScale - 0.3f)
                            }
                        }
                    ) {
                        Text("缩小")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                repository.setTimelineZoomScale(prefs.timelineZoomScale + 0.3f)
                            }
                        }
                    ) {
                        Text("放大")
                    }
                }
            }

            // 节假日/调休提示横幅
            if (!snapshot.calendarNotice.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "校历提醒：${snapshot.calendarNotice}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // 核心卡片：下一节课 / 当前正在上课
            NextClassHeroCard(
                activeOrNext = snapshot.activeOrNextClass,
                nextDayHint = snapshot.nextClassDayHint,
                totalTodayClasses = snapshot.items.size,
                onNavigateBuilding = { rawRoom ->
                    onNavigateToMapWithRoom(rawRoom)
                },
                onCardClick = { state ->
                    selectedOccurrenceForDetail = state
                }
            )

            // 日时间轴（真实时间比例绘制 y = (t - timelineStart) * pxPerMinute）
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "日时间轴（按真实时刻比例绘制）",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "现在 ${currentNow.toLocalTime().format(TIME_FMT)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (snapshot.items.isEmpty()) {
                        EmptyDayTimelineBox(
                            nextClassHint = snapshot.nextClassDayHint,
                            onJumpToDate = { nextDate -> selectedDate = nextDate }
                        )
                    } else {
                        ProportionalDayTimelineCanvas(
                            timelineStart = snapshot.timelineStart,
                            timelineEnd = snapshot.timelineEnd,
                            dpPerMinute = snapshot.pxPerMinute,
                            currentTimeLineDp = snapshot.currentTimeLinePx,
                            nowTimeText = currentNow.toLocalTime().format(TIME_FMT),
                            items = snapshot.items,
                            onItemClick = { selectedOccurrenceForDetail = it }
                        )
                    }
                }
            }
        }
    }

    // 课程详情与单日调课/停课弹窗
    val detailState = selectedOccurrenceForDetail
    if (detailState != null) {
        val occ = detailState.occurrence
        var editRoom by remember(occ) { mutableStateOf(occ.rawRoom) }
        AlertDialog(
            onDismissRequest = { selectedOccurrenceForDetail = null },
            title = { Text(occ.courseName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("状态：${detailState.statusText}", fontWeight = FontWeight.SemiBold)
                    Text("时间：${occ.startTime.format(TIME_FMT)} — ${occ.endTime.format(TIME_FMT)} (${occ.periodRangeText})")
                    Text("教室：${occ.rawRoom}")
                    if (!occ.teacher.isNullOrBlank()) {
                        Text("任课教师：${occ.teacher}")
                    }
                    if (occ.isMakeupOrOverridden) {
                        Text(
                            text = "调课/补课说明：${occ.overrideReason ?: "单日覆盖"}",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                selectedOccurrenceForDetail = null
                                onNavigateToMapWithRoom(occ.rawRoom)
                            }
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("去该教学楼")
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
                                                reason = "用户在今天页标记单次停课"
                                            )
                                        )
                                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                    }
                                    selectedOccurrenceForDetail = null
                                }
                            }
                        ) {
                            Text("仅今日停课")
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
    nextDayHint: cn.edu.haut.campus.core.model.NextClassDayHint?,
    totalTodayClasses: Int,
    onNavigateBuilding: (String) -> Unit,
    onCardClick: (TimelineOccurrenceState) -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (activeOrNext != null) {
                    Modifier.clickable { onCardClick(activeOrNext) }
                } else Modifier
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (activeOrNext != null) {
                val occ = activeOrNext.occurrence
                val isOngoing = activeOrNext.status == ClassStatus.IN_PROGRESS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isOngoing) "● 正在上课 · ${occ.courseName}" else "下一节 · ${occ.courseName}",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${occ.startTime.format(TIME_FMT)}–${occ.endTime.format(TIME_FMT)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${occ.rawRoom} ${occ.teacher?.let { "· $it" } ?: ""}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = activeOrNext.statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = { onNavigateBuilding(occ.rawRoom) }
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("教学楼导航")
                    }
                }
            } else {
                Text(
                    text = if (totalTodayClasses == 0) "今天没有课程安排" else "今日 $totalTodayClasses 节课程已全部结束",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (nextDayHint != null) {
                    Text(
                        text = "下一次上课：${nextDayHint.nextDate.format(DATE_TITLE_FMT)} (${nextDayHint.nextDate.dayOfWeek.ChineseLabel}) ${nextDayHint.firstCourseStartTime.format(TIME_FMT)} 《${nextDayHint.firstCourseName}》 @${nextDayHint.firstCourseRoom}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Text(
                        text = "近期没有待上课程，可前往图书馆或查看全学期周课表。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDayTimelineBox(
    nextClassHint: cn.edu.haut.campus.core.model.NextClassDayHint?,
    onJumpToDate: (LocalDate) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "今天没有排课",
            style = MaterialTheme.typography.titleMedium
        )
        if (nextClassHint != null) {
            Text(
                text = "距离下次课还有 ${nextClassHint.daysUntil} 天（${nextClassHint.nextDate.format(DATE_TITLE_FMT)} ${nextClassHint.firstCourseName}）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(onClick = { onJumpToDate(nextClassHint.nextDate) }) {
                Text("预览下次上课日 (${nextClassHint.nextDate.format(DATE_TITLE_FMT)})")
            }
        } else {
            Text(
                text = "享受自由安排的一天",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProportionalDayTimelineCanvas(
    timelineStart: LocalTime,
    timelineEnd: LocalTime,
    dpPerMinute: Float,
    currentTimeLineDp: Float?,
    nowTimeText: String,
    items: List<TimelineOccurrenceState>,
    onItemClick: (TimelineOccurrenceState) -> Unit
) {
    val totalMinutes = Duration.between(timelineStart, timelineEnd).toMinutes().coerceAtLeast(60)
    val totalHeightDp = (totalMinutes * dpPerMinute).dp
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(totalHeightDp)
    ) {
        // 1. 左侧整点时间刻度与水平辅助线
        val startHour = timelineStart.hour
        val endHour = timelineEnd.hour
        for (hour in startHour..endHour) {
            val tickTime = LocalTime.of(hour, 0)
            if (!tickTime.isBefore(timelineStart) && !tickTime.isAfter(timelineEnd)) {
                val yDp = (Duration.between(timelineStart, tickTime).toMinutes() * dpPerMinute).dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = yDp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = String.format("%02d:00", hour),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(46.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(axisColor.copy(alpha = 0.45f))
                    )
                }
            }
        }

        // 2. 竖向时间主轴线
        Box(
            modifier = Modifier
                .offset(x = 50.dp)
                .width(2.dp)
                .fillMaxHeight()
                .background(axisColor)
        )

        // 3. 按真实时间比例放置每节课程卡片：y = (t - timelineStart) * pxPerMinute
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 60.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                for (item in items) {
                    val occ = item.occurrence
                    val yOffset = item.topPx.dp
                    val cardHeight = item.heightPx.coerceAtLeast(44f).dp
                    val isFinished = item.status == ClassStatus.FINISHED
                    val isOngoing = item.status == ClassStatus.IN_PROGRESS
                    val baseColor = Color(occ.colorArgb)

                    val widthFraction = 1f / occ.conflictColumnCount.coerceAtLeast(1)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(widthFraction)
                            .offset(
                                x = (occ.conflictColumnIndex * 130).dp,
                                y = yOffset
                            )
                            .height(cardHeight)
                            .padding(end = 4.dp, bottom = 2.dp)
                            .alpha(if (isFinished) 0.58f else 1.0f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isOngoing) baseColor.copy(alpha = 0.22f)
                                else baseColor.copy(alpha = 0.13f)
                            )
                            .border(
                                width = if (isOngoing) 2.dp else 1.dp,
                                color = if (isOngoing) primaryColor else baseColor.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onItemClick(item) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Column(
                            verticalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = buildString {
                                        when (item.status) {
                                            ClassStatus.IN_PROGRESS -> append("● ")
                                            ClassStatus.UPCOMING -> append("▲ ")
                                            ClassStatus.FINISHED -> append("✓ ")
                                        }
                                        append(occ.courseName)
                                    },
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = item.statusText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isOngoing) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Text(
                                text = "教室 ${occ.rawRoom}  ·  ${occ.startTime.format(TIME_FMT)} — ${occ.endTime.format(TIME_FMT)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 4. 当前时间实时指示线（● 现在）
        if (currentTimeLineDp != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (currentTimeLineDp - 8f).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = primaryColor,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "● 现在 $nowTimeText",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(primaryColor)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(primaryColor)
                )
            }
        }
    }
}
