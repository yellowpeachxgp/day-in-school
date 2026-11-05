package cn.edu.haut.campus.feature.timetable

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.domain.WeeksSetParser
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val SHORT_DATE_FMT = DateTimeFormatter.ofPattern("MM/dd")
private val PERIOD_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun TimetableScreen(
    repository: CampusRepository,
    onNavigateToImporter: () -> Unit,
    onNavigateToMapWithRoom: (String) -> Unit,
    resetTrigger: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val terms by repository.termsFlow.collectAsState(initial = emptyList())
    val term by repository.currentTermFlow.collectAsState(initial = null)
    val courses by repository.coursesFlow.collectAsState(initial = emptyList())
    val rules by repository.rulesFlow.collectAsState(initial = emptyList())
    val periodTimes by repository.periodTimesFlow.collectAsState(initial = emptyList())
    val calendarOverrides by repository.calendarOverridesFlow.collectAsState(initial = emptyList())
    val occurrenceOverrides by repository.occurrenceOverridesFlow.collectAsState(initial = emptyList())
    val buildings by repository.buildingsFlow.collectAsState(initial = emptyList())
    val unscheduledCourses by repository.unscheduledCoursesFlow.collectAsState(initial = emptyList())
    var showUnscheduled by remember { mutableStateOf(false) }

    val today = remember { ZonedDateTime.now(HAUT_ZONE_ID).toLocalDate() }
    val currentActualWeek = remember(term, today, calendarOverrides) {
        term?.let { TeachingWeekCalculator.resolveDate(today, it, calendarOverrides).teachingWeek } ?: 1
    }

    var selectedWeek by remember { mutableIntStateOf(currentActualWeek) }
    LaunchedEffect(currentActualWeek, term?.id) {
        selectedWeek = currentActualWeek
    }
    LaunchedEffect(resetTrigger) {
        if (resetTrigger > 0) {
            selectedWeek = currentActualWeek
        }
    }

    var editingOccurrence by remember { mutableStateOf<ClassOccurrence?>(null) }
    var showAddCourseDialog by remember { mutableStateOf(false) }
    var showTermSwitcherDialog by remember { mutableStateOf(false) }
    var viewMode by remember { mutableIntStateOf(0) } // 0: 周网格, 1: 周清单, 2: 原始课表

    val weekOccurrencesMap = remember(
        selectedWeek,
        term,
        courses,
        rules,
        periodTimes,
        calendarOverrides,
        occurrenceOverrides,
        buildings
    ) {
        val activeTerm = term ?: return@remember emptyMap<LocalDate, List<ClassOccurrence>>()
        ScheduleEngine.resolveOccurrencesForTeachingWeek(
            teachingWeek = selectedWeek,
            term = activeTerm,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides,
            buildings = buildings
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = if (viewMode == 2) "原始课表" else "第 $selectedWeek 周",
            subtitle = term?.displayName ?: "选择学期",
            badgeText = if (viewMode == 2) "全学期" else if (selectedWeek == currentActualWeek) "本周" else null,
            onSubtitleClick = { showTermSwitcherDialog = true },
            actions = {
                if (viewMode != 2 && selectedWeek != currentActualWeek) {
                    XPeachFilterPill(
                        label = "本周",
                        selected = true,
                        compact = true,
                        onClick = { selectedWeek = currentActualWeek }
                    )
                }
                if (viewMode != 2) {
                    XPeachIconButton(
                        icon = Icons.Default.ChevronLeft,
                        contentDescription = "上一周",
                        onClick = { if (selectedWeek > 1) selectedWeek-- }
                    )
                    XPeachIconButton(
                        icon = Icons.Default.ChevronRight,
                        contentDescription = "下一周",
                        onClick = {
                            val maxW = term?.weekCount ?: 20
                            if (selectedWeek < maxW) selectedWeek++
                        }
                    )
                }
                XPeachIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = "添加课程",
                    onClick = { showAddCourseDialog = true }
                )
                XPeachIconButton(
                    icon = Icons.Default.CloudDownload,
                    contentDescription = "导入",
                    onClick = onNavigateToImporter
                )
            }
        )

        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            XPeachSegmentedTabs(
                items = listOf("周网格", "周清单", "总课表"),
                selectedIndex = viewMode,
                onSelect = { viewMode = it }
            )
        }

        val pendingCourses = unscheduledCourses.filter { it.termId == term?.id }
        AnimatedVisibility(
            visible = pendingCourses.isNotEmpty(),
            enter = expandVertically(tween(220)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(XPeachTheme.secondaryAccentCardColor)
                    .clickable { showUnscheduled = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${pendingCourses.size} 门实践课时间待定",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "查看明细 ▸",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = XPeachTheme.readablePrimary
                )
            }
        }
        if (showUnscheduled) {
            AlertDialog(
                onDismissRequest = { showUnscheduled = false },
                title = {
                    Text(
                        text = "实践与时间待定课程 (${pendingCourses.size})",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "以下实践课暂未安排固定上课时间。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        for (course in pendingCourses) {
                            XPeachWell(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = course.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = listOfNotNull(
                                        course.teacher?.takeIf { it.isNotBlank() }?.let { "教师：$it" },
                                        course.rawWeeks.takeIf { it.isNotBlank() }?.let { "周次：$it" },
                                        course.creditPlain?.takeIf { it.isNotBlank() }?.let { "${it}学分" },
                                        course.category.takeIf { it.isNotBlank() }
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showUnscheduled = false }) {
                        Text("知道了")
                    }
                }
            )
        }

        val currentTermRuleCount = remember(term?.id, rules) {
            rules.count { it.termId == term?.id }
        }
        val otherTermsWithCourses = remember(terms, term?.id, rules) {
            terms.filter { t -> t.id != term?.id && rules.any { it.termId == t.id } }
        }

        AnimatedVisibility(
            visible = currentTermRuleCount == 0 && otherTermsWithCourses.isNotEmpty(),
            enter = expandVertically(tween(220)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
        ) {
            val targetOtherTerm = otherTermsWithCourses.firstOrNull()
            if (targetOtherTerm != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(XPeachTheme.primaryAccentCardColor)
                        .clickable {
                            if (otherTermsWithCourses.size == 1) {
                                scope.launch {
                                    repository.selectCurrentTerm(targetOtherTerm.id)
                                    WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                }
                            } else {
                                showTermSwitcherDialog = true
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${term?.displayName ?: "当前学期"}暂无排课，可切换至 ${targetOtherTerm.displayName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (otherTermsWithCourses.size == 1) "一键切换 ▸" else "切换学期 ▸",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = XPeachTheme.readablePrimary
                    )
                }
            }
        }

        AnimatedContent(
            targetState = viewMode to selectedWeek,
            transitionSpec = {
                val (initMode, initWeek) = initialState
                val (targetMode, targetWeek) = targetState
                val forward = if (initMode != targetMode) {
                    targetMode >= initMode
                } else {
                    targetWeek >= initWeek
                }
                val direction = if (forward) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                }
                (slideIntoContainer(
                    towards = direction,
                    animationSpec = tween(240, easing = FastOutSlowInEasing)
                ) { it / 10 } + fadeIn(tween(190))) togetherWith
                    (slideOutOfContainer(
                        towards = direction,
                        animationSpec = tween(190, easing = FastOutSlowInEasing)
                    ) { it / 12 } + fadeOut(tween(140)))
            },
            label = "timetableViewWeekTransition",
            modifier = Modifier.fillMaxSize()
        ) { (activeMode, activeWeek) ->
            val activeWeekDates = remember(term, activeWeek) {
                term?.let { TeachingWeekCalculator.datesOfTeachingWeek(it, activeWeek) }
                    ?: (0L..6L).map { today.plusDays(it) }
            }
            val activeWeekOccurrencesMap = remember(
                activeWeek,
                term,
                courses,
                rules,
                periodTimes,
                calendarOverrides,
                occurrenceOverrides,
                buildings
            ) {
                val activeTerm = term ?: return@remember emptyMap<LocalDate, List<ClassOccurrence>>()
                ScheduleEngine.resolveOccurrencesForTeachingWeek(
                    teachingWeek = activeWeek,
                    term = activeTerm,
                    courses = courses,
                    rules = rules,
                    periodTimes = periodTimes,
                    calendarOverrides = calendarOverrides,
                    occurrenceOverrides = occurrenceOverrides,
                    buildings = buildings
                )
            }

            when (activeMode) {
                1 -> {
                    TimetableWeekListView(
                        weekDates = activeWeekDates,
                        today = today,
                        weekOccurrencesMap = activeWeekOccurrencesMap,
                        onEditOccurrence = { editingOccurrence = it },
                        onNavigateToMapWithRoom = onNavigateToMapWithRoom
                    )
                }
                2 -> {
                    RawMasterTimetableView(
                        termDisplayName = term?.displayName ?: "当前学期",
                        termId = term?.id,
                        courses = courses,
                        rules = rules,
                        unscheduledCourses = pendingCourses,
                        onNavigateToImporter = onNavigateToImporter,
                        onNavigateToMapWithRoom = onNavigateToMapWithRoom
                    )
                }
                else -> {
                    // 极简课表卡片容器（去重框线，柔和圆角色块）
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        // 顶部星期栏
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.width(38.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${dateMonthLabel(activeWeekDates.firstOrNull())}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = XPeachTheme.readablePrimary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            for (date in activeWeekDates) {
                                val isToday = date == today
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent
                                        )
                                        .padding(vertical = 5.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = date.dayOfWeek.ChineseLabel.replace("周", ""),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Text(
                                        text = date.format(SHORT_DATE_FMT),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isToday) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                        HorizontalDivider(
                            thickness = 0.6.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )

                        // 1-10 节无竖框轻盈网格
                        val slotHeightDp = 58.dp
                        val maxPeriods = periodTimes.maxOfOrNull { it.periodNo }?.coerceAtLeast(10) ?: 10
                        val periodByNo = periodTimes.associateBy { it.periodNo }

                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 92.dp)
                        ) {
                            Column(
                                modifier = Modifier.width(38.dp)
                            ) {
                                for (p in 1..maxPeriods) {
                                    val pt = periodByNo[p]
                                    Column(
                                        modifier = Modifier
                                            .height(slotHeightDp)
                                            .fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "$p",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        if (pt != null) {
                                            Text(
                                                text = pt.startLocalTime.format(PERIOD_TIME_FMT),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }

                            for (date in activeWeekDates) {
                                val dayOccurrences = activeWeekOccurrencesMap[date].orEmpty()
                                val isToday = date == today

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(slotHeightDp * maxPeriods)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
                                            else Color.Transparent
                                        )
                                ) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        repeat(maxPeriods) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(slotHeightDp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(0.5.dp)
                                                        .align(Alignment.BottomCenter)
                                                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                                )
                                            }
                                        }
                                    }

                                    for (occ in dayOccurrences) {
                                        val span = (occ.endPeriod - occ.startPeriod + 1).coerceAtLeast(1)
                                        val topOffset = slotHeightDp * (occ.startPeriod - 1)
                                        val blockHeight = slotHeightDp * span
                                        val accentColor = XPeachTheme.courseAccent(occ.colorArgb)
                                        val blockBg = XPeachTheme.courseContainer(occ.colorArgb, active = false)
                                        val blockBorder = XPeachTheme.courseBorder(occ.colorArgb, active = false)

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .offset(y = topOffset)
                                                .height(blockHeight)
                                                .padding(horizontal = 2.dp, vertical = 2.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(blockBg)
                                                .border(0.7.dp, blockBorder, RoundedCornerShape(8.dp))
                                                .clickable { editingOccurrence = occ }
                                        ) {
                                            // 顶部柔和色条 + 紧凑文字排布
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(3.dp)
                                                        .background(accentColor)
                                                )
                                                Column(
                                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 4.dp, vertical = 4.dp)
                                                ) {
                                                    Text(
                                                        text = occ.courseName,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.SemiBold,
                                                            lineHeight = 14.sp
                                                        ),
                                                        maxLines = 3,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = occ.rawRoom,
                                                        color = accentColor,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.Medium,
                                                            lineHeight = 13.sp
                                                        ),
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val occToEdit = editingOccurrence
    if (occToEdit != null) {
        val targetCourse = courses.firstOrNull { it.id == occToEdit.courseId }
        val targetRule = rules.firstOrNull { it.id == occToEdit.ruleId }

        var editedName by remember(occToEdit) { mutableStateOf(occToEdit.courseName) }
        var editedRoom by remember(occToEdit) { mutableStateOf(occToEdit.rawRoom) }
        var editedTeacher by remember(occToEdit) { mutableStateOf(occToEdit.teacher.orEmpty()) }
        var editedWeeksText by remember(targetRule) {
            mutableStateOf(targetRule?.let { WeeksSetParser.format(it.weeks) } ?: "1-16周")
        }
        var selectedColor by remember(occToEdit) { mutableLongStateOf(occToEdit.colorArgb) }

        val palette = listOf(
            0xFF0072BC, 0xFF0284C7, 0xFF0D9488, 0xFF16A34A,
            0xFF4F46E5, 0xFF7C3AED, 0xFFD97706, 0xFFE11D48
        )

        AlertDialog(
            onDismissRequest = { editingOccurrence = null },
            title = { Text("课程编辑") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${occToEdit.startTime.format(PERIOD_TIME_FMT)}–${occToEdit.endTime.format(PERIOD_TIME_FMT)} (${occToEdit.periodRangeText})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = XPeachTheme.readablePrimary
                    )
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        label = { Text("课程名称") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedRoom,
                        onValueChange = { editedRoom = it },
                        label = { Text("教室") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedTeacher,
                        onValueChange = { editedTeacher = it },
                        label = { Text("教师") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedWeeksText,
                        onValueChange = { editedWeeksText = it },
                        label = { Text("周次（如 1-16周）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        for (colorHex in palette) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorHex))
                                    .border(
                                        width = if (selectedColor == colorHex) 2.5.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = colorHex }
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            editingOccurrence = null
                            onNavigateToMapWithRoom(editedRoom)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("在地图中查看教室位置", maxLines = 1, softWrap = false)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            if (targetCourse != null && targetRule != null) {
                                val parsedWeeks = WeeksSetParser.parse(editedWeeksText).ifEmpty { targetRule.weeks }
                                repository.saveUserCourseEdit(
                                    course = targetCourse.copy(
                                        name = editedName.trim().ifEmpty { targetCourse.name },
                                        teacher = editedTeacher.trim().ifEmpty { null },
                                        colorArgb = selectedColor
                                    ),
                                    rule = targetRule.copy(
                                        rawRoom = editedRoom.trim().ifEmpty { targetRule.rawRoom },
                                        weeks = parsedWeeks
                                    )
                                )
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                            }
                            editingOccurrence = null
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingOccurrence = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showAddCourseDialog) {
        var newName by remember { mutableStateOf("") }
        var newRoom by remember { mutableStateOf("") }
        var newTeacher by remember { mutableStateOf("") }
        var newWeekday by remember { mutableIntStateOf(today.dayOfWeek.value) }
        var newStartPeriod by remember { mutableStateOf("1") }
        var newEndPeriod by remember { mutableStateOf("2") }
        var newWeeks by remember { mutableStateOf("1-16周") }

        AlertDialog(
            onDismissRequest = { showAddCourseDialog = false },
            title = { Text("添加自定义课程") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("课程名称") },
                        placeholder = { Text("例如：高等数学 / 英语口语") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newRoom,
                        onValueChange = { newRoom = it },
                        label = { Text("上课教室") },
                        placeholder = { Text("例如：5号教学楼5204") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newTeacher,
                        onValueChange = { newTeacher = it },
                        label = { Text("任课教师（选填）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "点选星期：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val dayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
                        for (d in 1..7) {
                            XPeachFilterPill(
                                label = dayLabels[d - 1],
                                selected = newWeekday == d,
                                onClick = { newWeekday = d }
                            )
                        }
                    }
                    Text(
                        text = "点选节次：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for ((sp, ep) in listOf("1" to "2", "3" to "4", "5" to "6", "7" to "8", "9" to "10")) {
                            XPeachFilterPill(
                                label = "${sp}-${ep}节",
                                selected = newStartPeriod == sp && newEndPeriod == ep,
                                onClick = {
                                    newStartPeriod = sp
                                    newEndPeriod = ep
                                }
                            )
                        }
                    }
                    Text(
                        text = "上课周次：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for ((label, expr) in listOf(
                            "全周 1-16周" to "1-16周",
                            "单周" to "1,3,5,7,9,11,13,15周",
                            "双周" to "2,4,6,8,10,12,14,16周",
                            "前8周" to "1-8周",
                            "后8周" to "9-16周"
                        )) {
                            XPeachFilterPill(
                                label = label,
                                selected = newWeeks == expr,
                                onClick = { newWeeks = expr }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = newWeeks,
                        onValueChange = { newWeeks = it },
                        label = { Text("自定义周次范围") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val activeTermId = term?.id ?: "2025-2026-2"
                        if (newName.isNotBlank()) {
                            scope.launch {
                                val sp = (newStartPeriod.toIntOrNull() ?: 1).coerceIn(1, 10)
                                val ep = (newEndPeriod.toIntOrNull() ?: sp).coerceIn(sp, 10)
                                val weeks = WeeksSetParser.parse(newWeeks).ifEmpty { setOf(selectedWeek) }
                                repository.addManualCourseWithRule(
                                    termId = activeTermId,
                                    name = newName.trim(),
                                    teacher = newTeacher.trim().ifBlank { null },
                                    rawRoom = newRoom.trim().ifBlank { "待定" },
                                    weekday = DayOfWeek.of(newWeekday),
                                    startPeriod = sp,
                                    endPeriod = ep,
                                    weeks = weeks,
                                    colorArgb = 0xFF0072BC
                                )
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                showAddCourseDialog = false
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCourseDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showTermSwitcherDialog) {
        var startDateInput by remember(term?.id, term?.startDate) {
            mutableStateOf(term?.startDate?.toString() ?: "2026-08-31")
        }
        var dateError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showTermSwitcherDialog = false },
            title = { Text("切换学期与校历设置") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "选择当前显示学期",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    for (t in terms) {
                        val isCurrent = t.id == term?.id
                        val count = rules.count { it.termId == t.id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isCurrent) XPeachTheme.primaryAccentCardColor
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                )
                                .clickable {
                                    scope.launch {
                                        repository.selectCurrentTerm(t.id)
                                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                        showTermSwitcherDialog = false
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = t.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "第1周周一：${t.startDate} · 共 ${t.weekCount} 周",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = if (isCurrent) "当前 ($count 门)" else "$count 门课",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrent) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    XPeachInsetDivider(verticalPadding = 4.dp)

                    Text(
                        text = "校准当前学期（${term?.displayName ?: ""}）第1周周一日期",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = startDateInput,
                        onValueChange = {
                            startDateInput = it
                            dateError = null
                        },
                        label = { Text("格式 YYYY-MM-DD") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (dateError != null) {
                        Text(
                            text = dateError!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val parsedDate = runCatching { LocalDate.parse(startDateInput.trim()) }.getOrNull()
                                val activeTermId = term?.id
                                if (parsedDate == null || activeTermId == null) {
                                    dateError = "请输入有效日期，如 2026-03-02 或 2026-08-31"
                                } else {
                                    scope.launch {
                                        repository.updateTermStartDate(activeTermId, parsedDate)
                                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                        showTermSwitcherDialog = false
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("保存开学日期", maxLines = 1, softWrap = false)
                        }
                        FilledTonalButton(
                            onClick = {
                                showTermSwitcherDialog = false
                                onNavigateToImporter()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("去同步该学期", maxLines = 1, softWrap = false)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTermSwitcherDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

private fun dateMonthLabel(date: LocalDate?): String =
    date?.let { "${it.monthValue}月" } ?: ""

@Composable
private fun TimetableWeekListView(
    weekDates: List<LocalDate>,
    today: LocalDate,
    weekOccurrencesMap: Map<LocalDate, List<ClassOccurrence>>,
    onEditOccurrence: (ClassOccurrence) -> Unit,
    onNavigateToMapWithRoom: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val totalClasses = weekOccurrencesMap.values.sumOf { it.size }
        if (totalClasses == 0) {
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "本周暂无排课安排",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "可切换至「总课表」查看全学期全部周次课程，或点击右上角切换周次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        for (date in weekDates) {
            val dayList = weekOccurrencesMap[date].orEmpty().sortedBy { it.startPeriod }
            if (dayList.isEmpty()) continue
            val isToday = date == today

            XPeachCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = if (isToday) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${date.dayOfWeek.ChineseLabel} (${date.format(SHORT_DATE_FMT)})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isToday) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurface
                        )
                        if (isToday) {
                            XPeachStatusDotText(
                                text = "今天",
                                dotColor = XPeachTheme.readablePrimary,
                                textColor = XPeachTheme.readablePrimary
                            )
                        }
                    }
                    Text(
                        text = "${dayList.size} 节课",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Column {
                    dayList.forEachIndexed { idx, occ ->
                        val accentColor = XPeachTheme.courseAccent(occ.colorArgb)
                        XPeachInsetDivider(verticalPadding = if (idx == 0) 4.dp else 8.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onEditOccurrence(occ) }
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.5.dp)
                                    .fillMaxHeight()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(accentColor)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = occ.courseName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${occ.periodRangeText} · ${occ.startTime.format(PERIOD_TIME_FMT)}–${occ.endTime.format(PERIOD_TIME_FMT)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = accentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${occ.rawRoom}${occ.teacher?.let { " · $it" } ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (occ.rawRoom.isNotBlank()) {
                                XPeachFilterPill(
                                    label = "去教室",
                                    selected = false,
                                    compact = true,
                                    onClick = { onNavigateToMapWithRoom(occ.rawRoom) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(92.dp))
    }
}

@Composable
private fun RawMasterTimetableView(
    termDisplayName: String,
    termId: String?,
    courses: List<Course>,
    rules: List<CourseScheduleRule>,
    unscheduledCourses: List<cn.edu.haut.campus.core.database.UnscheduledAcademicCourseEntity> = emptyList(),
    onNavigateToImporter: () -> Unit,
    onNavigateToMapWithRoom: (String) -> Unit
) {
    val termRules = remember(rules, termId) {
        if (termId != null) rules.filter { it.termId == termId } else rules
    }
    val courseMap = remember(courses) { courses.associateBy { it.id } }
    val sortedRules = remember(termRules, courseMap) {
        termRules.sortedWith(
            compareBy<CourseScheduleRule> { it.weekday.value }
                .thenBy { it.startPeriod }
                .thenBy { it.weeks.minOrNull() ?: 1 }
        )
    }
    val distinctCourseCount = remember(termRules) {
        termRules.map { it.courseId }.distinct().size
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 原始全学期总表概览与课表互通入口
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
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
                        text = "$termDisplayName · 全学期总课表",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "共 $distinctCourseCount 门课程，${sortedRules.size} 个上课时段",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = onNavigateToImporter,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("导入/导出", maxLines = 1, softWrap = false)
                }
            }
        }

        // 2. 教务原始 7×5 大节全周次总览表（不论当前第几周，展示所有周次排课叠加）
        XPeachCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 14.dp)
        ) {
            Text(
                text = "全学期课表速览",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            val bigSlots = listOf(
                1 to 2,
                3 to 4,
                5 to 6,
                7 to 8,
                9 to 10
            )
            val days = DayOfWeek.entries

            Row(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.width(24.dp))
                for (d in days) {
                    Text(
                        text = d.ChineseLabel.replace("周", ""),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((sp, ep) in bigSlots) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .width(24.dp)
                                .padding(top = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "$sp-$ep",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = XPeachTheme.readablePrimary
                            )
                        }
                        for (d in days) {
                            val slotRules = sortedRules.filter { r ->
                                r.weekday == d && r.startPeriod <= ep && r.endPeriod >= sp
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (slotRules.isNotEmpty()) {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                        }
                                    )
                                    .padding(2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (slotRules.isEmpty()) {
                                    Spacer(modifier = Modifier.height(28.dp))
                                } else {
                                    for (r in slotRules) {
                                        val c = courseMap[r.courseId]
                                        val rawArgb = c?.colorArgb ?: 0xFF0072BC
                                        val accentColor = XPeachTheme.courseAccent(rawArgb)
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(XPeachTheme.courseContainer(rawArgb, active = false))
                                                .border(
                                                    0.5.dp,
                                                    XPeachTheme.courseBorder(rawArgb, active = false),
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 2.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = c?.name ?: "课程",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    lineHeight = 13.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = WeeksSetParser.format(r.weeks),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    lineHeight = 12.sp
                                                ),
                                                color = accentColor,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. 原始排课完整明细清单（含周次、节次、教师、教室、来源）
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "全部课程明细 (${sortedRules.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (sortedRules.isEmpty()) {
                Text(
                    text = "当前学期暂无课程记录，点击上方「导入/导出」可从教务系统同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column {
                    sortedRules.forEachIndexed { idx, rule ->
                        val course = courseMap[rule.courseId]
                        val rawArgb = course?.colorArgb ?: 0xFF0072BC
                        val accentColor = XPeachTheme.courseAccent(rawArgb)
                        val weeksText = WeeksSetParser.format(rule.weeks)
                        val sourceLabel = when (course?.source) {
                            CourseSource.MANUAL -> "手动添加"
                            else -> "教务同步"
                        }

                        XPeachInsetDivider(verticalPadding = if (idx == 0) 4.dp else 8.dp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = course?.name ?: "未知课程",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    XPeachStatusDotText(
                                        text = sourceLabel,
                                        dotColor = accentColor
                                    )
                                }
                                Text(
                                    text = "${rule.weekday.ChineseLabel} 第${rule.startPeriod}-${rule.endPeriod}节 · $weeksText",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = XPeachTheme.readablePrimary
                                )
                                Text(
                                    text = "教室：${rule.rawRoom}${course?.teacher?.let { " · 教师：$it" } ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (rule.rawRoom.isNotBlank() && !rule.rawRoom.contains("待定")) {
                                XPeachFilterPill(
                                    label = "定位",
                                    selected = false,
                                    compact = true,
                                    onClick = { onNavigateToMapWithRoom(rule.rawRoom) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (unscheduledCourses.isNotEmpty()) {
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "本学期实践课程 (${unscheduledCourses.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "以下实践课暂未安排固定上课时间。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Column {
                    unscheduledCourses.forEachIndexed { idx, item ->
                        XPeachInsetDivider(verticalPadding = if (idx == 0) 4.dp else 6.dp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            XPeachStatusDotText(
                                text = item.category.takeIf { it.isNotBlank() } ?: "实践环节"
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = listOfNotNull(
                                item.teacher?.takeIf { it.isNotBlank() }?.let { "教师：$it" },
                                item.rawWeeks.takeIf { it.isNotBlank() }?.let { "周次：$it" },
                                item.creditPlain?.takeIf { it.isNotBlank() }?.let { "${it}学分" }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(92.dp))
    }
}

