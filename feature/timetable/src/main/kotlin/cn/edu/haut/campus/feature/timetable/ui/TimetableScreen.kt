package cn.edu.haut.campus.feature.timetable.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import cn.edu.haut.campus.feature.timetable.state.TimetableScreenStatus
import cn.edu.haut.campus.feature.timetable.state.TimetableUiAction
import cn.edu.haut.campus.feature.timetable.state.TimetableUiState

@Composable
fun TimetableScreen(
    state: TimetableUiState,
    onAction: (TimetableUiAction) -> Unit,
    modifier: Modifier = Modifier,
    resetTrigger: Int = 0
) {
    when (state.status) {
        TimetableScreenStatus.LOADING,
        TimetableScreenStatus.ERROR_RETRYABLE,
        TimetableScreenStatus.ERROR_TERMINAL,
        TimetableScreenStatus.NEEDS_PERMISSION,
        TimetableScreenStatus.NEEDS_LOGIN,
        TimetableScreenStatus.UNSUPPORTED -> TimetableStatusScaffold(
            status = state.status,
            onAction = onAction,
            modifier = modifier
        )
        else -> TimetableLegacyContent(
            state = state,
            onAction = onAction,
            modifier = modifier,
            resetTrigger = resetTrigger
        )
    }
}

@Composable
private fun TimetableLegacyContent(
    state: TimetableUiState,
    onAction: (TimetableUiAction) -> Unit,
    modifier: Modifier = Modifier,
    resetTrigger: Int = 0
) {
    val terms = state.terms
    val term = state.currentTerm
    val courses = state.courses
    val rules = state.scheduleRules
    val periodTimes = state.periodTimes
    val calendarOverrides = state.calendarOverrides
    val occurrenceOverrides = state.occurrenceOverrides
    val buildings = state.buildings
    val unscheduledCourses = state.unscheduledCourses
    val pendingLegacyReviewTargets = state.pendingLegacyReviewTargets
    val restoreNotice = state.restoreNotice

    val onNavigateToImporter: () -> Unit = { onAction(TimetableUiAction.OpenImporter) }
    val onNavigateToMapWithRoom: (String) -> Unit = { room -> onAction(TimetableUiAction.OpenMapRoom(room)) }

    var showUnscheduled by remember { mutableStateOf(false) }

    val timetableClock = LocalTimetableClock.current
    val today = remember { timetableClock.today() }
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
    var confirmDeleteMasterRule by remember { mutableStateOf<CourseScheduleRule?>(null) }
    var viewMode by remember { mutableIntStateOf(0) } // 0: 周网格, 1: 周清单, 2: 总课表
    var manualSevenDayOverride by remember(selectedWeek) { mutableStateOf<Boolean?>(null) }

    // 已取消分组语义（与恢复一致）：
    // - 新版跨日调课（moveId != null）按 moveId 折叠为一个原子恢复动作；
    // - 普通停课（无 moveId、非「已调至」reason）按 日期+真实课程 折叠；
    // - 旧版模糊调课（无 moveId、reason 为「已调至 …」）逐项单列并标注「待核对」，
    //   避免同日同课程的独立调课被错误合并，也避免误删可能不相关的调入目标。
    val termCanceledGroups = remember(occurrenceOverrides, term?.id, rules) {
        val ruleMap = rules.associateBy { it.id }
        val canceledRows = occurrenceOverrides
            .filter {
                (term == null || it.termId == term?.id) &&
                    it.canceled &&
                    it.reason?.startsWith("MERGED_INTO:") != true
            }
        val movedGroups = canceledRows
            .filter { !it.moveId.isNullOrBlank() }
            .groupBy { it.moveId!! }
            .values
            .map { rows ->
                rows.sortedBy { ov ->
                    ov.courseRuleId?.let { rId -> ruleMap[rId]?.startPeriod } ?: ov.startPeriod ?: 99
                }
            }
        val legacyMoveRows = canceledRows
            .filter { it.moveId.isNullOrBlank() && it.reason?.startsWith("已调至 ") == true }
            .map { listOf(it) }
        val plainGroups = canceledRows
            .filter { it.moveId.isNullOrBlank() && it.reason?.startsWith("已调至 ") != true }
            .groupBy { ov ->
                val resolvedCourseId = ov.courseId ?: ov.courseRuleId?.let { ruleMap[it]?.courseId } ?: ov.id
                ov.date to resolvedCourseId
            }
            .values
            .map { it.sortedBy { ov -> ov.courseRuleId?.let { rId -> ruleMap[rId]?.startPeriod } ?: ov.startPeriod ?: 99 } }
        (movedGroups + legacyMoveRows + plainGroups)
            .sortedByDescending { it.first().date }
    }
    val termCanceledOverrides = remember(termCanceledGroups) {
        termCanceledGroups.map { it.first() }
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
        modifier = modifier
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

        TimetableStatusBanner(
            status = state.status,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )

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
                            restoreNotice?.let { notice ->
                                Text(
                                    text = notice,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            for (group in termCanceledGroups) {
                                val ov = group.first()
                                val groupRules = group.mapNotNull { g -> g.courseRuleId?.let { ruleById[it] } }
                                val r = groupRules.firstOrNull()
                                val minSp = groupRules.minOfOrNull { it.startPeriod } ?: ov.startPeriod
                                val maxEp = groupRules.maxOfOrNull { it.endPeriod } ?: ov.endPeriod
                                val c = (ov.courseId ?: r?.courseId)?.let { courseById[it] }
                                val isMoveGroup = !ov.moveId.isNullOrBlank()
                                val isLegacyMove = ov.moveId.isNullOrBlank() &&
                                    ov.reason?.startsWith("已调至 ") == true
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
                                                    if (minSp != null && maxEp != null) {
                                                        append(" · 第${minSp}-${maxEp}节")
                                                    }
                                                    val roomLabel = r?.rawRoom ?: ov.room
                                                    if (!roomLabel.isNullOrBlank()) {
                                                        append(" · $roomLabel")
                                                    }
                                                    if (isMoveGroup) {
                                                        append(" · 已调出，恢复将撤销本次调课")
                                                    } else if (isLegacyMove) {
                                                        append(" · 旧版调课记录·待核对")
                                                    }
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isLegacyMove) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                        XPeachFilterPill(
                                            label = "恢复上课",
                                            selected = true,
                                            compact = true,
                                            onClick = {
                                                // 点击瞬间冻结组内行与学期内覆盖快照；ViewModel 构造结构化命令并提交。
                                                onAction(
                                                    TimetableUiAction.RestoreCanceledGroup(
                                                        termId = term?.id,
                                                        groupRows = group.toList(),
                                                        occurrenceOverrides = occurrenceOverrides
                                                    )
                                                )
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

        // 旧版模糊调课恢复后的待核对目标：源停课已恢复，列出疑似调入目标由用户逐项决定删除或保留
        if (pendingLegacyReviewTargets.isNotEmpty()) {
            XPeachAlertDialog(
                onDismissRequest = { onAction(TimetableUiAction.ClearLegacyReviewTargets) },
                title = { Text("核对旧版调课调入记录") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "该停课记录来自旧版调课，源时段已恢复。以下调入记录可能与之关联，请逐条确认是否删除（删除后不再重复排课）；不确定时可保留待核对。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        for (target in pendingLegacyReviewTargets) {
                            val targetCourse = target.courseId?.let { cid -> courses.firstOrNull { it.id == cid } }
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
                                            text = targetCourse?.name ?: "课程记录",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = buildString {
                                                append("调入 ${target.date} (${target.date.dayOfWeek.ChineseLabel})")
                                                if (target.startPeriod != null && target.endPeriod != null) {
                                                    append(" · 第${target.startPeriod}-${target.endPeriod}节")
                                                }
                                                target.room?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    XPeachFilterPill(
                                        label = "删除",
                                        selected = false,
                                        compact = true,
                                        onClick = {
                                        onAction(TimetableUiAction.DeleteLegacyOccurrenceOverride(target.id))
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onAction(TimetableUiAction.ClearLegacyReviewTargets) }) {
                        Text("保留待核对")
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
                                onAction(TimetableUiAction.SelectCurrentTerm(targetOtherTerm.id))
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

    TimetableEditingDialogs(
        editingOccurrence = editingOccurrence,
        onEditingOccurrenceDismiss = { editingOccurrence = null },
        editingMasterRule = editingMasterRule,
        onEditingMasterRuleDismiss = { editingMasterRule = null },
        confirmDeleteMasterRule = confirmDeleteMasterRule,
        onConfirmDeleteMasterRuleChange = { confirmDeleteMasterRule = it },
        schedulingPracticalCourse = schedulingPracticalCourse,
        onSchedulingPracticalCourseDismiss = { schedulingPracticalCourse = null },
        showAddCourseDialog = showAddCourseDialog,
        onAddCourseDialogDismiss = { showAddCourseDialog = false },
        showTermSwitcherDialog = showTermSwitcherDialog,
        onTermSwitcherDialogDismiss = { showTermSwitcherDialog = false },
        term = term,
        terms = terms,
        courses = courses,
        rules = rules,
        today = today,
        onAction = onAction,
        onNavigateToImporter = onNavigateToImporter,
        onNavigateToMapWithRoom = onNavigateToMapWithRoom
    )
}
