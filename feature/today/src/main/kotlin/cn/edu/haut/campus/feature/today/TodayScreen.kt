package cn.edu.haut.campus.feature.today

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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
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
import cn.edu.haut.campus.core.model.BreakType
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

    // 前台生命周期感知的每分钟时钟：仅当 App 在前台 STARTED 状态时更新
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "${selectedDate.format(DATE_TITLE_FMT)} · ${selectedDate.dayOfWeek.ChineseLabel}",
            subtitle = term?.displayName ?: "河南工业大学 · 莲花街校区",
            badgeText = snapshot.teachingWeek?.let { "第 $it 周" } ?: "非教学周",
            actions = {
                XPeachIconButton(onClick = onNavigateToImporter) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = "导入课表与成绩",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
                XPeachIconButton(onClick = onNavigateToSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "设置与小组件",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 日期快捷切换与时间轴缩放条（扁平化胶囊按钮）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    XPeachFilterPill(
                        label = "← 前一天",
                        selected = false,
                        onClick = { selectedDate = selectedDate.minusDays(1) }
                    )
                    XPeachFilterPill(
                        label = "今天",
                        selected = selectedDate == currentNow.toLocalDate(),
                        onClick = { selectedDate = currentNow.toLocalDate() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Today,
                                contentDescription = null,
                                tint = if (selectedDate == currentNow.toLocalDate()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                    XPeachFilterPill(
                        label = "后一天 →",
                        selected = false,
                        onClick = { selectedDate = selectedDate.plusDays(1) }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    XPeachFilterPill(
                        label = "缩小",
                        selected = false,
                        onClick = {
                            scope.launch {
                                repository.setTimelineZoomScale(prefs.timelineZoomScale - 0.3f)
                            }
                        }
                    )
                    XPeachFilterPill(
                        label = "放大",
                        selected = false,
                        onClick = {
                            scope.launch {
                                repository.setTimelineZoomScale(prefs.timelineZoomScale + 0.3f)
                            }
                        }
                    )
                }
            }

            // 节假日/调休提示横幅
            if (!snapshot.calendarNotice.isNullOrBlank()) {
                XPeachCard(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                ) {
                    Text(
                        text = "校历提醒：${snapshot.calendarNotice}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 若用户尚未导入任何课程，展示扁平化引导卡片而非空白或假数据
            if (courses.isEmpty()) {
                XPeachCard(
                    containerColor = MaterialTheme.colorScheme.surface,
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "尚未绑定个人课表",
                            style = MaterialTheme.typography.titleLarge
                        )
                        XPeachStatusBadge(text = "待初始化")
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "当前工作台为空白状态（无任何预设虚拟课程）。点击下方按钮登录河南工业大学统一身份认证，即可同步本学期真实课表与历史成绩。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onNavigateToImporter,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("同步河工大教务课表与成绩")
                    }
                }
            } else {
                // 核心卡片：下一节课 / 当前正在上课 / 当前课间提醒
                NextClassHeroCard(
                    activeOrNext = snapshot.activeOrNextClass,
                    activeBreak = snapshot.activeBreakInterval,
                    nextDayHint = snapshot.nextClassDayHint,
                    totalTodayClasses = snapshot.items.size,
                    onNavigateBuilding = { rawRoom ->
                        onNavigateToMapWithRoom(rawRoom)
                    },
                    onCardClick = { state ->
                        selectedOccurrenceForDetail = state
                    }
                )
            }

            // 日时间轴（真实时间比例绘制 y = (t - timelineStart) * pxPerMinute）
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "日时间轴（按真实时刻比例绘制）",
                        style = MaterialTheme.typography.titleMedium
                    )
                    XPeachStatusBadge(
                        text = "现在 ${currentNow.toLocalTime().format(TIME_FMT)}"
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (snapshot.items.isEmpty()) {
                    EmptyDayTimelineBox(
                        hasAnyCourses = courses.isNotEmpty(),
                        nextClassHint = snapshot.nextClassDayHint,
                        onJumpToDate = { nextDate -> selectedDate = nextDate },
                        onOpenImporter = onNavigateToImporter
                    )
                } else {
                    ProportionalDayTimelineCanvas(
                        timelineStart = snapshot.timelineStart,
                        timelineEnd = snapshot.timelineEnd,
                        dpPerMinute = snapshot.pxPerMinute,
                        currentTimeLineDp = snapshot.currentTimeLinePx,
                        nowTimeText = currentNow.toLocalTime().format(TIME_FMT),
                        items = snapshot.items,
                        breakIntervals = snapshot.breakIntervals,
                        onItemClick = { selectedOccurrenceForDetail = it },
                        onNavigateRoom = { rawRoom -> onNavigateToMapWithRoom(rawRoom) }
                    )
                }
            }
        }
    }

    // 课程详情与单日调课/停课弹窗
    val detailState = selectedOccurrenceForDetail
    if (detailState != null) {
        val occ = detailState.occurrence
        AlertDialog(
            onDismissRequest = { selectedOccurrenceForDetail = null },
            title = { Text(occ.courseName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("状态：${detailState.statusText}", fontWeight = FontWeight.SemiBold)
                    Text("时间：${occ.startTime.format(TIME_FMT)} — ${occ.endTime.format(TIME_FMT)} (${occ.periodRangeText})")
                    occ.internalBreaksSummary?.let { brkSummary ->
                        Text(
                            text = "课内间歇：$brkSummary",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
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
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("APP 内步行导航")
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
                            },
                            shape = RoundedCornerShape(8.dp)
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
    activeBreak: ScheduleBreakInterval?,
    nextDayHint: cn.edu.haut.campus.core.model.NextClassDayHint?,
    totalTodayClasses: Int,
    onNavigateBuilding: (String) -> Unit,
    onCardClick: (TimelineOccurrenceState) -> Unit
) {
    XPeachCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (activeOrNext != null) {
                    Modifier.clickable { onCardClick(activeOrNext) }
                } else Modifier
            ),
        containerColor = MaterialTheme.colorScheme.surface,
        borderColor = if (activeOrNext != null) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
        } else {
            MaterialTheme.colorScheme.outline
        }
    ) {
        // 若当前正处于两门课之间的跨课课间（如 10:05–10:25 大课间 / 午间休息），顶部高亮提示
        if (activeBreak != null) {
            XPeachWell(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Text(
                    text = "☕ 当前处于：${activeBreak.label} (${activeBreak.startTime.format(TIME_FMT)}–${activeBreak.endTime.format(TIME_FMT)}" +
                        (activeBreak.minutesRemainingInBreak?.let { " · 剩 ${it}分钟" } ?: "") + ")",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                activeBreak.roomTransferText?.let { transfer ->
                    Text(
                        text = "🚶 $transfer",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    XPeachStatusBadge(
                        text = if (isOngoing) "● 正在上课" else "下一节"
                    )
                    Text(
                        text = occ.courseName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${occ.startTime.format(TIME_FMT)}–${occ.endTime.format(TIME_FMT)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${occ.rawRoom} ${occ.teacher?.let { "· $it" } ?: ""} (${occ.periodRangeText})",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = activeOrNext.statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    occ.internalBreaksSummary?.let { brkSummary ->
                        Text(
                            text = "课内含：$brkSummary",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = { onNavigateBuilding(occ.rawRoom) },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("步行导航")
                }
            }
        } else {
            Text(
                text = if (totalTodayClasses == 0) "今天没有课程安排" else "今日 $totalTodayClasses 节课程已全部结束",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (nextDayHint != null) {
                Text(
                    text = "下一次上课：${nextDayHint.nextDate.format(DATE_TITLE_FMT)} (${nextDayHint.nextDate.dayOfWeek.ChineseLabel}) ${nextDayHint.firstCourseStartTime.format(TIME_FMT)} 《${nextDayHint.firstCourseName}》 @${nextDayHint.firstCourseRoom}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "近期没有待上课程，可前往图书馆或查看全学期周课表。",
                    style = MaterialTheme.typography.bodyMedium,
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
    XPeachWell(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (hasAnyCourses) "今天没有排课" else "课表为空",
                style = MaterialTheme.typography.titleMedium
            )
            if (nextClassHint != null) {
                Text(
                    text = "距离下次课还有 ${nextClassHint.daysUntil} 天（${nextClassHint.nextDate.format(DATE_TITLE_FMT)} ${nextClassHint.firstCourseName}）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { onJumpToDate(nextClassHint.nextDate) },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("预览下次上课日 (${nextClassHint.nextDate.format(DATE_TITLE_FMT)})")
                }
            } else if (!hasAnyCourses) {
                Text(
                    text = "请先通过教务系统同步或在「课表」页手动添加您的课程",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onOpenImporter,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("前往导入课表")
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
}

@Composable
private fun ProportionalDayTimelineCanvas(
    timelineStart: LocalTime,
    timelineEnd: LocalTime,
    dpPerMinute: Float,
    currentTimeLineDp: Float?,
    nowTimeText: String,
    items: List<TimelineOccurrenceState>,
    breakIntervals: List<ScheduleBreakInterval>,
    onItemClick: (TimelineOccurrenceState) -> Unit,
    onNavigateRoom: (String) -> Unit
) {
    val totalMinutes = Duration.between(timelineStart, timelineEnd).toMinutes().coerceAtLeast(60)
    val totalHeightDp = (totalMinutes * dpPerMinute).dp
    val axisColor = MaterialTheme.colorScheme.outline
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
                            .background(axisColor.copy(alpha = 0.6f))
                    )
                }
            }
        }

        // 2. 竖向时间主轴线
        Box(
            modifier = Modifier
                .offset(x = 50.dp)
                .width(1.dp)
                .fillMaxHeight()
                .background(axisColor)
        )

        // 3. 按真实时间比例放置每节课程卡片与跨课程课间提示条：y = (t - timelineStart) * pxPerMinute
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 60.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 3a. 跨课程课间区间条（自动显示大课间 20 分钟 / 午间休息 / 换教室提醒）
                for (interval in breakIntervals) {
                    val gapTopDp = interval.topPx.dp
                    val gapHeightDp = interval.heightPx.coerceAtLeast(22f).dp

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = gapTopDp)
                            .height(gapHeightDp)
                            .padding(end = 4.dp, top = 1.dp, bottom = 1.dp),
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
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .then(
                                    if (interval.requiresRoomChange) {
                                        Modifier.clickable { onNavigateRoom(interval.toRoom) }
                                    } else Modifier
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = buildString {
                                        when (interval.breakType) {
                                            BreakType.SMALL_BREAK -> append("☕ ")
                                            BreakType.BIG_BREAK -> append("🔔 ")
                                            BreakType.NOON_BREAK -> append("🍱 ")
                                            BreakType.EVENING_BREAK -> append("🌆 ")
                                            BreakType.FREE_PERIOD_GAP -> append("⏳ ")
                                        }
                                        append("${interval.label} (${interval.startTime.format(TIME_FMT)}–${interval.endTime.format(TIME_FMT)})")
                                        interval.roomTransferText?.let { transfer ->
                                            append(" · $transfer")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                                    color = if (interval.isActiveNow) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // 3b. 课程卡片（扁平化 1dp 边框 + 左侧色条）
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
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isOngoing) baseColor.copy(alpha = 0.16f)
                                else baseColor.copy(alpha = 0.09f)
                            )
                            .border(
                                width = if (isOngoing) 1.5.dp else 1.dp,
                                color = if (isOngoing) primaryColor else baseColor.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(8.dp)
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

                            occ.internalBreaksSummary?.let { brkText ->
                                Text(
                                    text = "含课内间歇：$brkText",
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                                    color = if (item.activeInternalBreak != null) {
                                        primaryColor
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Text(
                                text = "教室 ${occ.rawRoom}  ·  ${occ.startTime.format(TIME_FMT)} — ${occ.endTime.format(TIME_FMT)} (${occ.periodRangeText})",
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
                        .size(7.dp)
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
