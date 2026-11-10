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
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachCoursePalette
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
import cn.edu.haut.campus.core.model.AcademicTermResolver
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

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
    var editingMasterRule by remember { mutableStateOf<CourseScheduleRule?>(null) }
    var schedulingPracticalCourse by remember {
        mutableStateOf<cn.edu.haut.campus.core.database.UnscheduledAcademicCourseEntity?>(null)
    }
    var showCanceledCoursesDialog by remember { mutableStateOf(false) }
    var showAddCourseDialog by remember { mutableStateOf(false) }
    var showTermSwitcherDialog by remember { mutableStateOf(false) }
    var viewMode by remember { mutableIntStateOf(0) } // 0: 周网格, 1: 周清单, 2: 总课表
    var manualSevenDayOverride by remember(selectedWeek) { mutableStateOf<Boolean?>(null) }

    val termCanceledOverrides = remember(occurrenceOverrides, term?.id) {
        occurrenceOverrides
            .filter {
                (term == null || it.termId == term?.id) &&
                    it.canceled &&
                    it.reason?.startsWith("MERGED_INTO:") != true
            }
            .sortedByDescending { it.date }
    }

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
            title = if (viewMode == 2) "总课表" else "第 $selectedWeek 周",
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
                    .then(
                        if (XPeachTheme.isLiquidGlass) {
                            Modifier.border(0.85.dp, XPeachTheme.specularRimBrush(), RoundedCornerShape(12.dp))
                        } else {
                            Modifier
                        }
                    )
                    .clickable { showUnscheduled = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${pendingCourses.size} 门实践课时间待定（可自定义排课时间）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "设置时间 ▸",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = XPeachTheme.readablePrimary
                )
            }
        }
        AnimatedVisibility(
            visible = termCanceledOverrides.isNotEmpty(),
            enter = expandVertically(tween(220)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                    .clickable { showCanceledCoursesDialog = true }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "本学期有 ${termCanceledOverrides.size} 节课已设为单日停课",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "恢复上课 ▸",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = XPeachTheme.readablePrimary
                )
            }
        }
        if (showUnscheduled) {
            XPeachAlertDialog(
                onDismissRequest = { showUnscheduled = false },
                title = {
                    Text(
                        text = "时间待定实践课程 (${pendingCourses.size})",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "以下实践/集中课程教务未安排固定节次，点击「设置时间」可将其排入课表与提醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        for (course in pendingCourses) {
                            XPeachWell(modifier = Modifier.fillMaxWidth()) {
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
                                        Text(
                                            text = course.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
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
                                    XPeachFilterPill(
                                        label = "设置时间",
                                        selected = true,
                                        compact = true,
                                        onClick = {
                                            showUnscheduled = false
                                            schedulingPracticalCourse = course
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showUnscheduled = false }) {
                        Text("关闭")
                    }
                }
            )
        }
        if (showCanceledCoursesDialog) {
            val courseById = remember(courses) { courses.associateBy { it.id } }
            val ruleById = remember(rules) { rules.associateBy { it.id } }
            XPeachAlertDialog(
                onDismissRequest = { showCanceledCoursesDialog = false },
                title = {
                    Text(
                        text = "单日停课记录 (${termCanceledOverrides.size})",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (termCanceledOverrides.isEmpty()) {
                            Text(
                                text = "当前没有已设单日停课的记录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "点击「恢复上课」可立即恢复该日排课、桌面小组件与课前提醒",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            for (ov in termCanceledOverrides) {
                                val r = ov.courseRuleId?.let { ruleById[it] }
                                val c = (ov.courseId ?: r?.courseId)?.let { courseById[it] }
                                XPeachWell(modifier = Modifier.fillMaxWidth()) {
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
                                            Text(
                                                text = c?.name ?: "课程记录",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = buildString {
                                                    append("${ov.date} (${ov.date.dayOfWeek.ChineseLabel})")
                                                    if (r != null) {
                                                        append(" · 第${r.startPeriod}-${r.endPeriod}节 · ${r.rawRoom}")
                                                    }
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        XPeachFilterPill(
                                            label = "恢复上课",
                                            selected = true,
                                            compact = true,
                                            onClick = {
                                                scope.launch {
                                                    repository.restoreCanceledOccurrence(
                                                        date = ov.date,
                                                        ruleId = ov.courseRuleId,
                                                        overrideId = ov.id
                                                    )
                                                    WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showCanceledCoursesDialog = false }) {
                        Text("关闭")
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
                        .then(
                            if (XPeachTheme.isLiquidGlass) {
                                Modifier.border(0.85.dp, XPeachTheme.specularRimBrush(), RoundedCornerShape(12.dp))
                            } else {
                                Modifier
                            }
                        )
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
                        text = "当前学期无课，可切至 ${targetOtherTerm.displayName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "切换 ▸",
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
                        canceledOverridesCount = termCanceledOverrides.size,
                        onAddManualCourse = { showAddCourseDialog = true },
                        onEditRule = { editingMasterRule = it },
                        onScheduleUnscheduledCourse = { schedulingPracticalCourse = it },
                        onOpenCanceledCourses = { showCanceledCoursesDialog = true },
                        onNavigateToImporter = onNavigateToImporter,
                        onNavigateToMapWithRoom = onNavigateToMapWithRoom
                    )
                }
                else -> {
                    val hasWeekendClasses = remember(activeWeekDates, activeWeekOccurrencesMap) {
                        activeWeekDates.drop(5).any { activeWeekOccurrencesMap[it].orEmpty().isNotEmpty() }
                    }
                    val showSevenDays = manualSevenDayOverride ?: hasWeekendClasses
                    val visibleDates = remember(activeWeekDates, showSevenDays) {
                        if (showSevenDays) activeWeekDates else activeWeekDates.take(5)
                    }

                    // 宽阔课表卡片容器（自适应 5日/7日 + 课程名与底部教室胶囊分层解耦 + Liquid Glass 磨砂折射支持）
                    val sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                    val sheetBgColor = if (XPeachTheme.isLiquidGlass || XPeachTheme.hasActiveBackdrop) {
                        MaterialTheme.colorScheme.surface.copy(
                            alpha = (XPeachTheme.glassCardAlpha * 0.90f).coerceIn(0.62f, 0.94f)
                        )
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clip(sheetShape)
                            .background(sheetBgColor)
                            .then(
                                if (XPeachTheme.isLiquidGlass) {
                                    Modifier.border(0.95.dp, XPeachTheme.specularRimBrush(intensity = 1.0f), sheetShape)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        // 顶部星期栏（左上角一键切换 5日/7日，无周末课时自动展开 5 列大宽格）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .width(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                    .clickable { manualSevenDayOverride = !showSevenDays }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = dateMonthLabel(activeWeekDates.firstOrNull()),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = XPeachTheme.readablePrimary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = if (showSevenDays) "7日▾" else "5日▾",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            for (date in visibleDates) {
                                val isToday = date == today
                                val dayCount = activeWeekOccurrencesMap[date].orEmpty().size
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 1.5.dp)
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
                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Text(
                                        text = date.format(SHORT_DATE_FMT),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isToday) {
                                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.88f)
                                        } else if (dayCount > 0) {
                                            XPeachTheme.readablePrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                        XPeachInsetDivider(verticalPadding = 0.dp)

                        // 1-10/12 节高透气网格（含午休/晚间视觉分段线 + 顶底分离式课程卡片）
                        val slotHeightDp = if (showSevenDays) 68.dp else 74.dp
                        val maxOccEndPeriod = remember(activeWeekOccurrencesMap) {
                            activeWeekOccurrencesMap.values.flatten().maxOfOrNull { it.endPeriod } ?: 10
                        }
                        val maxPeriods = maxOf(
                            10,
                            periodTimes.maxOfOrNull { it.periodNo } ?: 10,
                            maxOccEndPeriod
                        )
                        val periodByNo = periodTimes.associateBy { it.periodNo }

                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 92.dp)
                        ) {
                            Column(
                                modifier = Modifier.width(34.dp)
                            ) {
                                for (p in 1..maxPeriods) {
                                    val pt = periodByNo[p]
                                    val isSectionEnd = p == 4 || p == 8
                                    Box(
                                        modifier = Modifier
                                            .height(slotHeightDp)
                                            .fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = "$p",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
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
                                        if (isSectionEnd) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(1.2.dp)
                                                    .align(Alignment.BottomCenter)
                                                    .background(XPeachTheme.readablePrimary.copy(alpha = 0.22f))
                                            )
                                        }
                                    }
                                }
                            }

                            for (date in visibleDates) {
                                val dayOccurrences = activeWeekOccurrencesMap[date].orEmpty()
                                val isToday = date == today
                                val groupedBySlot = remember(dayOccurrences) {
                                    dayOccurrences
                                        .sortedBy { it.startPeriod }
                                        .groupBy { it.startPeriod to it.endPeriod }
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(slotHeightDp * maxPeriods)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
                                            else Color.Transparent
                                        )
                                ) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        for (p in 1..maxPeriods) {
                                            val isSectionEnd = p == 4 || p == 8
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(slotHeightDp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(if (isSectionEnd) 1.2.dp else 0.5.dp)
                                                        .align(Alignment.BottomCenter)
                                                        .background(
                                                            if (isSectionEnd) {
                                                                XPeachTheme.readablePrimary.copy(alpha = 0.22f)
                                                            } else {
                                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
                                                            }
                                                        )
                                                )
                                            }
                                        }
                                    }

                                    for ((_, slotGroup) in groupedBySlot) {
                                        val occ = slotGroup.first()
                                        val overlapExtra = slotGroup.size - 1
                                        val span = (occ.endPeriod - occ.startPeriod + 1).coerceAtLeast(1)
                                        val topOffset = slotHeightDp * (occ.startPeriod - 1)
                                        val blockHeight = slotHeightDp * span
                                        val accentColor = XPeachTheme.courseAccent(occ.colorArgb)
                                        val blockBg = XPeachTheme.courseContainer(occ.colorArgb, active = isToday)
                                        val blockBorder = XPeachTheme.courseBorder(occ.colorArgb, active = isToday)
                                        val compactRoom = formatCompactGridRoom(occ.rawRoom, compact = showSevenDays)

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .offset(y = topOffset)
                                                .height(blockHeight)
                                                .padding(
                                                    horizontal = if (showSevenDays) 1.5.dp else 2.5.dp,
                                                    vertical = 2.5.dp
                                                )
                                                .clip(RoundedCornerShape(if (showSevenDays) 10.dp else 12.dp))
                                                .background(blockBg)
                                                .border(
                                                    0.8.dp,
                                                    blockBorder,
                                                    RoundedCornerShape(if (showSevenDays) 10.dp else 12.dp)
                                                )
                                                .clickable { editingOccurrence = occ }
                                        ) {
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(3.5.dp)
                                                        .background(accentColor)
                                                )
                                                Column(
                                                    verticalArrangement = Arrangement.SpaceBetween,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(
                                                            horizontal = if (showSevenDays) 3.dp else 5.5.dp,
                                                            vertical = if (showSevenDays) 4.dp else 5.dp
                                                        )
                                                ) {
                                                    Column(verticalArrangement = Arrangement.spacedBy(2.5.dp)) {
                                                        Text(
                                                            text = occ.courseName,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontSize = if (showSevenDays) 11.5.sp else 12.5.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                lineHeight = if (showSevenDays) 14.sp else 15.5.sp
                                                            ),
                                                            maxLines = if (span >= 3) 6 else if (showSevenDays) 4 else 5,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (!showSevenDays && span >= 2 && !occ.teacher.isNullOrBlank() && overlapExtra == 0) {
                                                            Text(
                                                                text = occ.teacher!!,
                                                                style = MaterialTheme.typography.labelSmall.copy(
                                                                    fontSize = 10.5.sp,
                                                                    lineHeight = 12.5.sp
                                                                ),
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                        if (overlapExtra > 0) {
                                                            Text(
                                                                text = "+${overlapExtra}门重课",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = accentColor,
                                                                fontWeight = FontWeight.SemiBold,
                                                                maxLines = 1
                                                            )
                                                        }
                                                    }

                                                    if (compactRoom.isNotBlank()) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clip(RoundedCornerShape(if (showSevenDays) 5.dp else 6.dp))
                                                                .background(accentColor.copy(alpha = 0.13f))
                                                                .padding(
                                                                    horizontal = if (showSevenDays) 3.5.dp else 4.5.dp,
                                                                    vertical = if (showSevenDays) 2.dp else 2.5.dp
                                                                )
                                                        ) {
                                                            Text(
                                                                text = compactRoom,
                                                                color = accentColor,
                                                                style = MaterialTheme.typography.labelSmall.copy(
                                                                    fontSize = if (showSevenDays) 10.5.sp else 11.5.sp,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    lineHeight = if (showSevenDays) 12.sp else 13.5.sp
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
        }
    }

    val occToEdit = editingOccurrence
    if (occToEdit != null) {
        val targetCourse = courses.firstOrNull { it.id == occToEdit.courseId }
        val targetRule = rules.firstOrNull { it.id == occToEdit.ruleId }

        var editScopeMode by remember(occToEdit) {
            mutableIntStateOf(if (targetRule == null) 1 else 0)
        } // 0: 全学期规则, 1: 仅本次(单次改教室/节次/跨天调课)
        var editedName by remember(occToEdit) { mutableStateOf(occToEdit.courseName) }
        var editedRoom by remember(occToEdit) { mutableStateOf(occToEdit.rawRoom) }
        var editedTeacher by remember(occToEdit) { mutableStateOf(occToEdit.teacher.orEmpty()) }
        var editedWeekday by remember(occToEdit, targetRule) {
            mutableIntStateOf(targetRule?.weekday?.value ?: occToEdit.weekday.value)
        }
        var editedWeeksText by remember(targetRule) {
            mutableStateOf(targetRule?.let { WeeksSetParser.format(it.weeks) } ?: "1-16周")
        }
        var editedStartPeriod by remember(occToEdit) { mutableStateOf(occToEdit.startPeriod.toString()) }
        var editedEndPeriod by remember(occToEdit) { mutableStateOf(occToEdit.endPeriod.toString()) }
        var targetDateText by remember(occToEdit) { mutableStateOf(occToEdit.date.toString()) }
        var singleDateError by remember(occToEdit) { mutableStateOf<String?>(null) }
        var selectedColor by remember(occToEdit) { mutableLongStateOf(occToEdit.colorArgb) }

        val palette = XPeachCoursePalette.SwatchesArgb

        XPeachAlertDialog(
            onDismissRequest = { editingOccurrence = null },
            title = { Text("课程调整") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${occToEdit.date} (${occToEdit.weekday.ChineseLabel}) · ${occToEdit.formatTimeOrPeriodRange(PERIOD_TIME_FMT)} (${occToEdit.periodRangeText})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = XPeachTheme.readablePrimary
                    )
                    if (targetRule != null) {
                        XPeachSegmentedTabs(
                            items = listOf("修改全学期规则", "仅改本次 (${occToEdit.date.format(SHORT_DATE_FMT)})"),
                            selectedIndex = editScopeMode,
                            onSelect = { editScopeMode = it }
                        )
                    }
                    if (editScopeMode == 0 && targetRule != null) {
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
                        Text(
                            text = "星期与节次",
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
                                    selected = editedWeekday == d,
                                    onClick = { editedWeekday = d }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for ((sp, ep) in listOf("1" to "2", "3" to "4", "5" to "6", "7" to "8", "9" to "10", "11" to "12")) {
                                XPeachFilterPill(
                                    label = "${sp}-${ep}节",
                                    selected = editedStartPeriod == sp && editedEndPeriod == ep,
                                    onClick = {
                                        editedStartPeriod = sp
                                        editedEndPeriod = ep
                                    }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = editedStartPeriod,
                                onValueChange = { editedStartPeriod = it.filter(Char::isDigit).take(2) },
                                label = { Text("起始节(1-12)") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = editedEndPeriod,
                                onValueChange = { editedEndPeriod = it.filter(Char::isDigit).take(2) },
                                label = { Text("结束节(1-12)") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = editedWeeksText,
                            onValueChange = { editedWeeksText = it },
                            label = { Text("周次") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            for (colorHex in palette) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
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
                    } else {
                        Text(
                            text = "仅对本次课生效（可改教室、节次或跨日期调课），不影响其他教学周排课",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = targetDateText,
                            onValueChange = {
                                targetDateText = it
                                singleDateError = null
                            },
                            label = { Text("上课日期 (YYYY-MM-DD)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (singleDateError != null) {
                            Text(
                                text = singleDateError!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        OutlinedTextField(
                            value = editedRoom,
                            onValueChange = { editedRoom = it },
                            label = { Text("当日临时教室") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for ((sp, ep) in listOf("1" to "2", "3" to "4", "5" to "6", "7" to "8", "9" to "10", "11" to "12")) {
                                XPeachFilterPill(
                                    label = "${sp}-${ep}节",
                                    selected = editedStartPeriod == sp && editedEndPeriod == ep,
                                    onClick = {
                                        editedStartPeriod = sp
                                        editedEndPeriod = ep
                                    }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = editedStartPeriod,
                                onValueChange = { editedStartPeriod = it.filter(Char::isDigit).take(2) },
                                label = { Text("起始节(1-12)") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = editedEndPeriod,
                                onValueChange = { editedEndPeriod = it.filter(Char::isDigit).take(2) },
                                label = { Text("结束节(1-12)") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (!occToEdit.overrideId.isNullOrBlank() && occToEdit.ruleId != null) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        repository.resetSingleDayOccurrenceEdit(occToEdit)
                                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                        editingOccurrence = null
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "恢复原排课规则",
                                    color = XPeachTheme.readablePrimary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                editingOccurrence = null
                                onNavigateToMapWithRoom(editedRoom)
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("查看位置", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = {
                                val activeTermId = term?.id ?: AcademicTermResolver.defaultTermId()
                                scope.launch {
                                    repository.cancelClassOccurrenceOnDate(
                                        occ = occToEdit,
                                        termId = activeTermId,
                                        reason = "单次停课"
                                    )
                                    WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                    editingOccurrence = null
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("本节停课", maxLines = 1, softWrap = false)
                        }
                    }
                    if (targetRule != null && editScopeMode == 0) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    repository.deleteCourseScheduleRules(
                                        ruleIds = occToEdit.mergedRuleIds.ifEmpty { listOf(targetRule.id) },
                                        contextDate = occToEdit.date
                                    )
                                    WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                    editingOccurrence = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "删除该排课时段",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val sp = (editedStartPeriod.toIntOrNull() ?: occToEdit.startPeriod).coerceIn(1, 12)
                        val ep = (editedEndPeriod.toIntOrNull() ?: occToEdit.endPeriod).coerceIn(sp, 12)
                        if (editScopeMode != 0 || targetRule == null) {
                            val parsedTargetDate = runCatching { LocalDate.parse(targetDateText.trim()) }.getOrNull()
                            if (parsedTargetDate == null) {
                                singleDateError = "请输入有效日期 (YYYY-MM-DD)"
                                return@Button
                            }
                            scope.launch {
                                val activeTermId = term?.id ?: AcademicTermResolver.defaultTermId()
                                val cleanRoom = editedRoom.trim().ifEmpty { occToEdit.rawRoom }
                                if (parsedTargetDate != occToEdit.date) {
                                    repository.rescheduleSingleOccurrenceCrossDate(
                                        occ = occToEdit,
                                        termId = activeTermId,
                                        targetDate = parsedTargetDate,
                                        newStartPeriod = sp,
                                        newEndPeriod = ep,
                                        newRoom = cleanRoom
                                    )
                                } else {
                                    repository.saveSingleDayOccurrenceEdit(
                                        occ = occToEdit,
                                        termId = activeTermId,
                                        newStartPeriod = sp,
                                        newEndPeriod = ep,
                                        newRoom = cleanRoom
                                    )
                                }
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                editingOccurrence = null
                            }
                        } else {
                            scope.launch {
                                if (targetCourse != null) {
                                    val parsedWeeks = WeeksSetParser.parse(editedWeeksText).ifEmpty { targetRule.weeks }
                                    repository.saveUserMergedCourseRuleEdit(
                                        course = targetCourse.copy(
                                            name = editedName.trim().ifEmpty { targetCourse.name },
                                            teacher = editedTeacher.trim().ifEmpty { null },
                                            colorArgb = selectedColor
                                        ),
                                        primaryRule = targetRule.copy(
                                            weekday = DayOfWeek.of(editedWeekday.coerceIn(1, 7)),
                                            startPeriod = sp,
                                            endPeriod = ep,
                                            rawRoom = editedRoom.trim().ifEmpty { targetRule.rawRoom },
                                            weeks = parsedWeeks
                                        ),
                                        mergedRuleIds = occToEdit.mergedRuleIds.ifEmpty { listOf(targetRule.id) },
                                        contextDate = occToEdit.date
                                    )
                                    WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                }
                                editingOccurrence = null
                            }
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

    val masterRuleToEdit = editingMasterRule
    if (masterRuleToEdit != null) {
        val targetCourse = courses.firstOrNull { it.id == masterRuleToEdit.courseId }
        var editedName by remember(masterRuleToEdit, targetCourse) { mutableStateOf(targetCourse?.name ?: "") }
        var editedRoom by remember(masterRuleToEdit) { mutableStateOf(masterRuleToEdit.rawRoom) }
        var editedTeacher by remember(masterRuleToEdit, targetCourse) { mutableStateOf(targetCourse?.teacher.orEmpty()) }
        var editedWeekday by remember(masterRuleToEdit) { mutableIntStateOf(masterRuleToEdit.weekday.value) }
        var editedStartPeriod by remember(masterRuleToEdit) { mutableStateOf(masterRuleToEdit.startPeriod.toString()) }
        var editedEndPeriod by remember(masterRuleToEdit) { mutableStateOf(masterRuleToEdit.endPeriod.toString()) }
        var editedWeeksText by remember(masterRuleToEdit) {
            mutableStateOf(WeeksSetParser.format(masterRuleToEdit.weeks))
        }
        var selectedColor by remember(targetCourse) { mutableLongStateOf(targetCourse?.colorArgb ?: 0xFF0072BC) }
        val palette = XPeachCoursePalette.SwatchesArgb

        XPeachAlertDialog(
            onDismissRequest = { editingMasterRule = null },
            title = { Text("编辑排课规则") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                        label = { Text("上课教室") },
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
                    Text(
                        text = "星期",
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
                                selected = editedWeekday == d,
                                onClick = { editedWeekday = d }
                            )
                        }
                    }
                    Text(
                        text = "节次",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for ((sp, ep) in listOf("1" to "2", "3" to "4", "5" to "6", "7" to "8", "9" to "10", "11" to "12")) {
                            XPeachFilterPill(
                                label = "${sp}-${ep}节",
                                selected = editedStartPeriod == sp && editedEndPeriod == ep,
                                onClick = {
                                    editedStartPeriod = sp
                                    editedEndPeriod = ep
                                }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = editedStartPeriod,
                            onValueChange = { editedStartPeriod = it.filter(Char::isDigit).take(2) },
                            label = { Text("起始节(1-12)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = editedEndPeriod,
                            onValueChange = { editedEndPeriod = it.filter(Char::isDigit).take(2) },
                            label = { Text("结束节(1-12)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = editedWeeksText,
                        onValueChange = { editedWeeksText = it },
                        label = { Text("周次范围") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        for (colorHex in palette) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
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
                    TextButton(
                        onClick = {
                            scope.launch {
                                repository.deleteCourseScheduleRule(masterRuleToEdit.id)
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                editingMasterRule = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "删除该排课时段",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            if (targetCourse != null) {
                                val sp = (editedStartPeriod.toIntOrNull() ?: masterRuleToEdit.startPeriod).coerceIn(1, 12)
                                val ep = (editedEndPeriod.toIntOrNull() ?: masterRuleToEdit.endPeriod).coerceIn(sp, 12)
                                val parsedWeeks = WeeksSetParser.parse(editedWeeksText).ifEmpty { masterRuleToEdit.weeks }
                                repository.saveUserCourseEdit(
                                    course = targetCourse.copy(
                                        name = editedName.trim().ifEmpty { targetCourse.name },
                                        teacher = editedTeacher.trim().ifEmpty { null },
                                        colorArgb = selectedColor
                                    ),
                                    rule = masterRuleToEdit.copy(
                                        weekday = DayOfWeek.of(editedWeekday),
                                        startPeriod = sp,
                                        endPeriod = ep,
                                        rawRoom = editedRoom.trim().ifEmpty { masterRuleToEdit.rawRoom },
                                        weeks = parsedWeeks
                                    )
                                )
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                            }
                            editingMasterRule = null
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingMasterRule = null }) {
                    Text("取消")
                }
            }
        )
    }

    val pracToSchedule = schedulingPracticalCourse
    if (pracToSchedule != null) {
        var pracRoom by remember(pracToSchedule) { mutableStateOf("实践场地") }
        var pracWeekday by remember(pracToSchedule) { mutableIntStateOf(today.dayOfWeek.value) }
        var pracStartPeriod by remember(pracToSchedule) { mutableStateOf("1") }
        var pracEndPeriod by remember(pracToSchedule) { mutableStateOf("2") }
        var pracWeeks by remember(pracToSchedule) {
            mutableStateOf(
                pracToSchedule.rawWeeks.takeIf { it.isNotBlank() && WeeksSetParser.parse(it).isNotEmpty() }
                    ?: "1-16周"
            )
        }
        XPeachAlertDialog(
            onDismissRequest = { schedulingPracticalCourse = null },
            title = { Text("设置实践课时间") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = pracToSchedule.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = XPeachTheme.readablePrimary
                    )
                    Text(
                        text = listOfNotNull(
                            pracToSchedule.teacher?.takeIf { it.isNotBlank() }?.let { "教师：$it" },
                            pracToSchedule.creditPlain?.takeIf { it.isNotBlank() }?.let { "${it}学分" },
                            pracToSchedule.rawWeeks.takeIf { it.isNotBlank() }?.let { "教务周次：$it" }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = pracRoom,
                        onValueChange = { pracRoom = it },
                        label = { Text("上课教室 / 实践场地") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "星期",
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
                                selected = pracWeekday == d,
                                onClick = { pracWeekday = d }
                            )
                        }
                    }
                    Text(
                        text = "节次",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for ((sp, ep) in listOf("1" to "2", "3" to "4", "5" to "6", "7" to "8", "9" to "10", "11" to "12", "1" to "4", "5" to "8")) {
                            XPeachFilterPill(
                                label = "${sp}-${ep}节",
                                selected = pracStartPeriod == sp && pracEndPeriod == ep,
                                onClick = {
                                    pracStartPeriod = sp
                                    pracEndPeriod = ep
                                }
                            )
                        }
                    }
                    Text(
                        text = "周次",
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
                            "1-16周" to "1-16周",
                            "单周" to "1,3,5,7,9,11,13,15周",
                            "双周" to "2,4,6,8,10,12,14,16周",
                            "前8周" to "1-8周",
                            "后8周" to "9-16周"
                        )) {
                            XPeachFilterPill(
                                label = label,
                                selected = pracWeeks == expr,
                                onClick = { pracWeeks = expr }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = pracWeeks,
                        onValueChange = { pracWeeks = it },
                        label = { Text("周次范围（如 14-16周 或 10周）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val sp = (pracStartPeriod.toIntOrNull() ?: 1).coerceIn(1, 12)
                            val ep = (pracEndPeriod.toIntOrNull() ?: sp).coerceIn(sp, 12)
                            val weeks = WeeksSetParser.parse(pracWeeks).ifEmpty { setOf(selectedWeek) }
                            repository.scheduleUnscheduledPracticalCourse(
                                unscheduled = pracToSchedule,
                                rawRoom = pracRoom.trim().ifBlank { "实践场地" },
                                weekday = DayOfWeek.of(pracWeekday),
                                startPeriod = sp,
                                endPeriod = ep,
                                weeks = weeks
                            )
                            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                            schedulingPracticalCourse = null
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("排入课表")
                }
            },
            dismissButton = {
                TextButton(onClick = { schedulingPracticalCourse = null }) {
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

        XPeachAlertDialog(
            onDismissRequest = { showAddCourseDialog = false },
            title = { Text("添加课程") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("课程名称") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newRoom,
                        onValueChange = { newRoom = it },
                        label = { Text("上课教室") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newTeacher,
                        onValueChange = { newTeacher = it },
                        label = { Text("教师（选填）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "星期",
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
                        text = "节次",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for ((sp, ep) in listOf("1" to "2", "3" to "4", "5" to "6", "7" to "8", "9" to "10", "11" to "12")) {
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
                        text = "周次",
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
                            "1-16周" to "1-16周",
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
                        label = { Text("周次范围") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val activeTermId = term?.id ?: AcademicTermResolver.defaultTermId()
                        if (newName.isNotBlank()) {
                            scope.launch {
                                val sp = (newStartPeriod.toIntOrNull() ?: 1).coerceIn(1, 12)
                                val ep = (newEndPeriod.toIntOrNull() ?: sp).coerceIn(sp, 12)
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

        XPeachAlertDialog(
            onDismissRequest = { showTermSwitcherDialog = false },
            title = { Text("学期与校历") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "当前学期",
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
                                    text = "${t.startDate} 开学 · 共 ${t.weekCount} 周",
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
                        text = "第一周周一日期",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = startDateInput,
                        onValueChange = {
                            startDateInput = it
                            dateError = null
                        },
                        label = { Text("YYYY-MM-DD") },
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
                                    dateError = "日期格式无效 (YYYY-MM-DD)"
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
                            Text("保存日期", maxLines = 1, softWrap = false)
                        }
                        FilledTonalButton(
                            onClick = {
                                showTermSwitcherDialog = false
                                onNavigateToImporter()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("同步该学期", maxLines = 1, softWrap = false)
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

private val GRID_BUILDING_REGEX = Regex("""^(\d+)号教学楼(\d+[A-Za-z0-9\-]*)$""")

private fun formatCompactGridRoom(rawRoom: String, compact: Boolean = false): String {
    val cleaned = rawRoom
        .replace("河南工业大学", "")
        .replace("莲花街校区", "")
        .replace("莲花街", "")
        .trim()
    if (cleaned.isEmpty()) return ""
    val m = GRID_BUILDING_REGEX.matchEntire(cleaned)
    if (m != null) {
        return "${m.groupValues[1]}教${m.groupValues[2]}"
    }
    val formatted = cleaned
        .replace("号教学楼", "教")
        .replace("教学楼", "教")
        .replace("号楼", "楼")
    return if (compact) formatted.replace("报告厅", "") else formatted
}

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
                    text = "本周无课",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "可在「总课表」查看全学期课程",
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
                                    text = "${occ.periodRangeText} · ${occ.formatTimeOrPeriodRange(PERIOD_TIME_FMT)}",
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
    canceledOverridesCount: Int = 0,
    onAddManualCourse: () -> Unit = {},
    onEditRule: (CourseScheduleRule) -> Unit = {},
    onScheduleUnscheduledCourse: (cn.edu.haut.campus.core.database.UnscheduledAcademicCourseEntity) -> Unit = {},
    onOpenCanceledCourses: () -> Unit = {},
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
                        text = termDisplayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "共 $distinctCourseCount 门课 · ${sortedRules.size} 个时段",
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

        // 2. 教务原始 7×5 大节全周次总览表（支持横向宽列滑动，多周次课程独立成卡绝不糊字）
        XPeachCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "全学期课表",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "左右滑动 ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            val bigSlots = remember(sortedRules) {
                buildList {
                    add(1 to 2)
                    add(3 to 4)
                    add(5 to 6)
                    add(7 to 8)
                    add(9 to 10)
                    if (sortedRules.any { it.endPeriod > 10 }) {
                        add(11 to 12)
                    }
                }
            }
            val hasWeekendRules = remember(sortedRules) {
                sortedRules.any { it.weekday.value >= 6 }
            }
            val days = remember(hasWeekendRules) {
                if (hasWeekendRules) DayOfWeek.entries else DayOfWeek.entries.take(5)
            }
            val colWidth = if (days.size == 5) 86.dp else 70.dp

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.width(32.dp))
                    for (d in days) {
                        Box(
                            modifier = Modifier
                                .width(colWidth)
                                .padding(horizontal = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = d.ChineseLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }

                for ((sp, ep) in bigSlots) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .width(32.dp)
                                .padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "$sp-$ep",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = XPeachTheme.readablePrimary
                            )
                            Text(
                                text = "节",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        for (d in days) {
                            val slotRules = sortedRules.filter { r ->
                                r.weekday == d && r.startPeriod <= ep && r.endPeriod >= sp
                            }
                            Column(
                                modifier = Modifier
                                    .width(colWidth)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (slotRules.isNotEmpty()) {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                                        }
                                    )
                                    .padding(3.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (slotRules.isEmpty()) {
                                    Spacer(modifier = Modifier.height(36.dp))
                                } else {
                                    for (r in slotRules) {
                                        val c = courseMap[r.courseId]
                                        val rawArgb = c?.colorArgb ?: 0xFF0072BC
                                        val accentColor = XPeachTheme.courseAccent(rawArgb)
                                        val compactRoom = formatCompactGridRoom(r.rawRoom)
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(XPeachTheme.courseContainer(rawArgb, active = false))
                                                .border(
                                                    0.7.dp,
                                                    XPeachTheme.courseBorder(rawArgb, active = false),
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .padding(horizontal = 4.dp, vertical = 3.5.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = c?.name ?: "课程",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    lineHeight = 13.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = if (days.size == 5) 3 else 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (compactRoom.isNotBlank()) {
                                                Text(
                                                    text = compactRoom,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        lineHeight = 12.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    ),
                                                    color = accentColor,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                text = WeeksSetParser.format(r.weeks),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    lineHeight = 11.5.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        // 3. 原始排课完整明细清单（含周次、节次、教师、教室、来源、编辑与添加）
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "课程管理与排课明细 (${sortedRules.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (canceledOverridesCount > 0) {
                        XPeachFilterPill(
                            label = "已停课($canceledOverridesCount)",
                            selected = false,
                            compact = true,
                            onClick = onOpenCanceledCourses
                        )
                    }
                    XPeachFilterPill(
                        label = "+ 添加课程",
                        selected = true,
                        compact = true,
                        onClick = onAddManualCourse
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (sortedRules.isEmpty()) {
                Text(
                    text = "暂无课程，可从教务系统同步或点击右上角手动添加",
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
                        val sourceLabel = when {
                            course?.source == CourseSource.MANUAL -> "手动添加"
                            course?.userModified == true || rule.userModified -> "已手动调整"
                            else -> "教务同步"
                        }

                        XPeachInsetDivider(verticalPadding = if (idx == 0) 4.dp else 8.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onEditRule(rule) }
                                .padding(vertical = 2.dp),
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
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                XPeachFilterPill(
                                    label = "编辑",
                                    selected = false,
                                    compact = true,
                                    onClick = { onEditRule(rule) }
                                )
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
        }

        if (unscheduledCourses.isNotEmpty()) {
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "实践课程 (${unscheduledCourses.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "教务未指定固定节次，点击「设置时间」可排入周课表",
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
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    XPeachStatusDotText(
                                        text = item.category.takeIf { it.isNotBlank() } ?: "实践环节"
                                    )
                                }
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
                            XPeachFilterPill(
                                label = "设置时间",
                                selected = true,
                                compact = true,
                                onClick = { onScheduleUnscheduledCourse(item) }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(92.dp))
    }
}

