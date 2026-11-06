package cn.edu.haut.campus.feature.grades

import android.content.Intent
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.haut.campus.core.database.AppPreferencesState
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
import cn.edu.haut.campus.core.domain.GpaCalculator
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import cn.edu.haut.campus.core.model.TermGpaTrendPoint
import cn.edu.haut.campus.core.plan.AttemptAggregator
import cn.edu.haut.campus.core.plan.CompletedCourse
import cn.edu.haut.campus.core.plan.DegreeProgressCalculator
import cn.edu.haut.campus.core.plan.PlanCourseRef
import cn.edu.haut.campus.core.plan.PlanSource
import cn.edu.haut.campus.core.plan.Provenance
import cn.edu.haut.campus.core.plan.RequirementGroup
import cn.edu.haut.campus.core.plan.RequirementKind
import cn.edu.haut.campus.core.plan.TrainingPlan
import cn.edu.haut.campus.core.selection.ClassSlot
import cn.edu.haut.campus.core.selection.DraftEntry
import cn.edu.haut.campus.core.selection.SelectionCandidate
import cn.edu.haut.campus.core.selection.SelectionConflictAnalyzer
import cn.edu.haut.campus.core.selection.SelectionDraft
import cn.edu.haut.campus.core.selection.SelectionRequest
import cn.edu.haut.campus.core.selection.SelectionRound
import cn.edu.haut.campus.core.selection.SelectionScope
import cn.edu.haut.campus.core.selection.SelectionSubmission
import cn.edu.haut.campus.core.selection.SelectionTransition
import cn.edu.haut.campus.core.selection.TeachingWeeks
import cn.edu.haut.campus.core.selection.TimetableOccupancy
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.ZonedDateTime

@Composable
fun GradesScreen(
    repository: CampusRepository,
    onNavigateToImporter: () -> Unit,
    onNavigateToMapWithRoom: (String) -> Unit = {},
    onNavigateToLibrarySeats: () -> Unit = {},
    onNavigateToLibraryCatalog: () -> Unit = {},
    onNavigateToFocus: () -> Unit = {},
    onNavigateToEmptyClassrooms: () -> Unit = {},
    resetTrigger: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val attempts by repository.gradeAttemptsFlow.collectAsState(initial = emptyList())
    val examSchedules by repository.examSchedulesFlow.collectAsState(initial = emptyList())
    val courses by repository.coursesFlow.collectAsState(initial = emptyList())
    val scheduleRules by repository.rulesFlow.collectAsState(initial = emptyList())
    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())
    val activeFocusSession by repository.activeFocusSessionFlow.collectAsState(initial = null)
    val completedFocusSessions by repository.completedFocusSessionsFlow.collectAsState(initial = emptyList())
    val todayDate = remember { ZonedDateTime.now(HAUT_ZONE_ID).toLocalDate() }

    var selectedTermFilter by remember { mutableStateOf<String?>(null) }
    var selectedCategoryFilter by remember { mutableStateOf<CourseCategory?>(null) }
    var activeSubTab by remember { mutableIntStateOf(0) } // 0: 成绩明细, 1: 考试安排, 2: 培养进度, 3: 选课草稿
    var trendMetricIndex by remember { mutableIntStateOf(0) }
    var showTrendSection by remember { mutableStateOf(false) }
    var showSimulatorSection by remember { mutableStateOf(false) }
    var simulatedNewCredits by remember { mutableFloatStateOf(22f) }
    var simulatedNewGpa by remember { mutableFloatStateOf(3.8f) }

    LaunchedEffect(resetTrigger) {
        if (resetTrigger > 0) {
            scrollState.animateScrollTo(0)
            selectedTermFilter = null
            selectedCategoryFilter = null
        }
    }

    val availableTerms = remember(attempts, examSchedules) {
        (attempts.map { it.termId } + examSchedules.map { it.termId }).distinct().sortedDescending()
    }
    val latestTermId = selectedTermFilter ?: availableTerms.firstOrNull()
    val latestAcademicYear = attempts.firstOrNull { it.termId == latestTermId }?.academicYear

    val officialSnapshot = remember(prefs.officialGpaValuePlain, prefs.officialGpaNote) {
        val officialVal = prefs.officialGpaValuePlain?.toBigDecimalOrNull()
        if (officialVal != null) {
            OfficialGpaSnapshot(
                cumulativeGpa = officialVal,
                fetchedAt = Instant.now(),
                fieldDefinitionNote = prefs.officialGpaNote ?: "教务汇总"
            )
        } else null
    }

    val gpaReport = remember(attempts, latestTermId, latestAcademicYear, officialSnapshot) {
        GpaCalculator.calculateReport(
            attempts = attempts,
            currentTermId = latestTermId,
            currentAcademicYear = latestAcademicYear,
            officialSnapshot = officialSnapshot
        )
    }

    val privacyMasked = prefs.gpaPrivacyShieldEnabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "学业",
            subtitle = latestTermId ?: "全部学期",
            badgeText = if (privacyMasked) "已隐藏" else null,
            actions = {
                XPeachIconButton(
                    icon = if (privacyMasked) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = "切换成绩可见性",
                    onClick = {
                        scope.launch {
                            repository.setGpaPrivacyShield(!privacyMasked)
                        }
                    }
                )
                XPeachIconButton(
                    icon = Icons.Default.Share,
                    contentDescription = "导出CSV",
                    onClick = {
                        val csv = buildGradesCsv(attempts)
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_SUBJECT, "成绩明细.csv")
                            putExtra(Intent.EXTRA_TEXT, csv)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "导出 CSV"))
                    }
                )
                XPeachIconButton(
                    icon = Icons.Default.CloudDownload,
                    contentDescription = "同步成绩",
                    onClick = onNavigateToImporter
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (attempts.isEmpty()) {
                XPeachCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "暂无成绩数据",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "同步教务后自动计算绩点与学分",
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
            }

            // 0. 学习与图书馆安排 Bento 矩阵（座位预约 / 馆藏检索 / 自习专注 / 空教室查询）
            val todayDoneMins = remember(completedFocusSessions, todayDate) {
                completedFocusSessions.sumOf { s ->
                    val doneMs = s.completedAtEpochMs ?: s.expectedEndAtEpochMs
                    val d = Instant.ofEpochMilli(doneMs).atZone(HAUT_ZONE_ID).toLocalDate()
                    if (d == todayDate) s.durationMinutes else 0
                }
            }
            val runningFocus = activeFocusSession
            val focusSub = if (runningFocus != null) {
                val remMin = ((runningFocus.remainingMillis(System.currentTimeMillis()) + 59_999L) / 60_000L)
                    .coerceAtLeast(1L)
                "进行中 · 约剩 $remMin 分钟"
            } else if (todayDoneMins > 0) {
                "今日已专注 $todayDoneMins 分钟"
            } else {
                "番茄钟倒计时与课前提醒"
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    XPeachCard(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        onClick = onNavigateToLibrarySeats
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("图书馆选座", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            XPeachStatusDotText(text = "预约")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "实时余位查询与在线选座",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    XPeachCard(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        onClick = onNavigateToLibraryCatalog
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("馆藏检索", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            XPeachStatusDotText(text = "馆藏")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "书目搜索与在馆索书号",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    XPeachCard(
                        modifier = Modifier.weight(1f),
                        containerColor = if (runningFocus != null) XPeachTheme.primaryAccentCardColor else MaterialTheme.colorScheme.surface,
                        borderColor = if (runningFocus != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        onClick = onNavigateToFocus
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("自习专注", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            if (runningFocus != null) {
                                XPeachStatusBadge(
                                    text = "专注中",
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                XPeachStatusDotText(text = "番茄钟")
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = focusSub,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    XPeachCard(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        onClick = onNavigateToEmptyClassrooms
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("空教室自习", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            XPeachStatusDotText(text = "教学楼")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "按节次查 5–11 号楼无课教室",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 1. 绩点卡片
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "平均学分绩点",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (privacyMasked) "∗.∗∗" else (gpaReport.cumulativeGpa?.toPlainString() ?: "—"),
                            style = MaterialTheme.typography.displaySmall,
                            color = XPeachTheme.readablePrimary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        XPeachFilterPill(
                            label = if (showSimulatorSection) "收起测算" else "绩点测算",
                            selected = showSimulatorSection,
                            compact = true,
                            onClick = { showSimulatorSection = !showSimulatorSection }
                        )
                        if (gpaReport.termTrends.isNotEmpty()) {
                            XPeachFilterPill(
                                label = if (showTrendSection) "收起趋势" else "学期趋势",
                                selected = showTrendSection,
                                compact = true,
                                onClick = { showTrendSection = !showTrendSection }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                XPeachWell(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        GpaCompactStat(
                            label = "本学期",
                            value = if (privacyMasked) "∗.∗∗" else (gpaReport.currentTermGpa?.toPlainString() ?: "—"),
                            modifier = Modifier.weight(1f)
                        )
                        GpaCompactStat(
                            label = "本学年",
                            value = if (privacyMasked) "∗.∗∗" else (gpaReport.currentAcademicYearGpa?.toPlainString() ?: "—"),
                            modifier = Modifier.weight(1f)
                        )
                        GpaCompactStat(
                            label = "已修学分",
                            value = if (privacyMasked) "∗∗.∗" else gpaReport.totalEarnedCredits.toPlainString(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 目标绩点与新修学分测算
                AnimatedVisibility(
                    visible = showSimulatorSection,
                    enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
                ) {
                    Column {
                        XPeachInsetDivider(verticalPadding = 10.dp)
                        val baseCredits = gpaReport.gpaIncludedCredits.toFloat().coerceAtLeast(0f)
                        val baseGpa = gpaReport.cumulativeGpa?.toFloat() ?: 3.2f
                        val totalSimCredits = (baseCredits + simulatedNewCredits).coerceAtLeast(1f)
                        val projectedGpa = ((baseCredits * baseGpa) + (simulatedNewCredits * simulatedNewGpa)) / totalSimCredits

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "预计总绩点",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = String.format(java.util.Locale.US, "%.2f", projectedGpa),
                                style = MaterialTheme.typography.titleLarge,
                                color = XPeachTheme.readablePrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "计划新修学分：${simulatedNewCredits.toInt()} 学分",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = simulatedNewCredits,
                            onValueChange = { simulatedNewCredits = it },
                            valueRange = 2f..60f
                        )
                        Text(
                            text = "新修课程平均绩点：${String.format(java.util.Locale.US, "%.2f", simulatedNewGpa)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = simulatedNewGpa,
                            onValueChange = { simulatedNewGpa = it },
                            valueRange = 2.0f..4.5f
                        )
                    }
                }

                // 可折叠趋势与分布，避免首屏视觉过载
                AnimatedVisibility(
                    visible = showTrendSection && gpaReport.termTrends.isNotEmpty(),
                    enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
                ) {
                    Column {
                        XPeachInsetDivider(verticalPadding = 10.dp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("学期走势", style = MaterialTheme.typography.labelLarge)
                            XPeachSegmentedTabs(
                                items = listOf("GPA", "均分", "学分"),
                                selectedIndex = trendMetricIndex,
                                onSelect = { trendMetricIndex = it },
                                modifier = Modifier.width(184.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        TermTrendChart(
                            points = gpaReport.termTrends,
                            metricIndex = trendMetricIndex,
                            privacyMasked = privacyMasked
                        )
                    }
                }
            }

            // 2. 成绩明细 / 考试安排 / 培养进度 / 选课草稿 分段切换 + 学期筛选条
            XPeachSegmentedTabs(
                items = listOf(
                    "成绩 (${gpaReport.auditItems.size})",
                    "考试 (${examSchedules.size})",
                    "培养进度",
                    "选课草稿"
                ),
                selectedIndex = activeSubTab,
                onSelect = { activeSubTab = it }
            )

            AnimatedVisibility(
                visible = availableTerms.isNotEmpty() && (activeSubTab == 0 || activeSubTab == 1),
                enter = expandVertically(tween(180)) + fadeIn(tween(140)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(100))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    XPeachFilterPill(
                        label = "全部学期",
                        selected = selectedTermFilter == null,
                        onClick = { selectedTermFilter = null }
                    )
                    for (termId in availableTerms) {
                        XPeachFilterPill(
                            label = termId,
                            selected = selectedTermFilter == termId,
                            onClick = { selectedTermFilter = termId }
                        )
                    }
                }
            }

            AnimatedContent(
                targetState = activeSubTab,
                transitionSpec = {
                    val direction = if (targetState >= initialState) {
                        AnimatedContentTransitionScope.SlideDirection.Start
                    } else {
                        AnimatedContentTransitionScope.SlideDirection.End
                    }
                    (slideIntoContainer(
                        towards = direction,
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ) { it / 10 } + fadeIn(tween(180))) togetherWith
                        (slideOutOfContainer(
                            towards = direction,
                            animationSpec = tween(180, easing = FastOutSlowInEasing)
                        ) { it / 12 } + fadeOut(tween(130)))
                },
                label = "gradesSubTabTransition",
                modifier = Modifier.fillMaxWidth()
            ) { currentSubTab ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (currentSubTab) {
                        0 -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                XPeachFilterPill(
                                    label = "全部性质",
                                    selected = selectedCategoryFilter == null,
                                    compact = true,
                                    onClick = { selectedCategoryFilter = null }
                                )
                                for (cat in CourseCategory.entries) {
                                    XPeachFilterPill(
                                        label = cat.label,
                                        selected = selectedCategoryFilter == cat,
                                        compact = true,
                                        onClick = { selectedCategoryFilter = cat }
                                    )
                                }
                            }

                            // 3A. 聚合式课程成绩列表卡片
                            val filteredAudit = remember(gpaReport.auditItems, selectedTermFilter, selectedCategoryFilter) {
                                gpaReport.auditItems.filter { item ->
                                    val att = item.selectedAttempt ?: return@filter false
                                    val termMatch = selectedTermFilter == null || att.termId == selectedTermFilter
                                    val catMatch = selectedCategoryFilter == null || att.category == selectedCategoryFilter
                                    termMatch && catMatch
                                }
                            }

                            if (filteredAudit.isNotEmpty()) {
                                XPeachCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    filteredAudit.forEachIndexed { index, audit ->
                                        val att = audit.selectedAttempt ?: return@forEachIndexed
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(end = 12.dp),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = att.name,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${att.termId} · ${att.category.label} · ${att.credits} 学分",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = if (privacyMasked) "∗∗" else att.rawGrade,
                                                    style = MaterialTheme.typography.titleLarge,
                                                    color = XPeachTheme.readablePrimary,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                                Text(
                                                    text = if (privacyMasked) {
                                                        "绩点 ∗.∗"
                                                    } else {
                                                        att.gradePoint?.let { "绩点 $it" } ?: att.attemptType.label
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }

                                        if (index < filteredAudit.lastIndex) {
                                            XPeachInsetDivider(verticalPadding = 0.dp)
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            // 3B. 考试安排列表（涵盖期初补缓考、期中随堂/分散考试、期末集中考试）
                            val filteredExams = remember(examSchedules, selectedTermFilter) {
                                examSchedules.filter { selectedTermFilter == null || it.termId == selectedTermFilter }
                            }

                            if (filteredExams.isEmpty()) {
                                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "暂无考试安排，点击右上角按钮可从教务系统同步。",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                XPeachCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    filteredExams.forEachIndexed { index, exam ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(end = 10.dp),
                                                verticalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Text(
                                                        text = exam.courseName,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    )
                                                    XPeachStatusDotText(text = exam.examStageLabel)
                                                }
                                                Text(
                                                    text = "${exam.termId} · ${exam.rawTimeText}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = XPeachTheme.readablePrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = buildString {
                                                        append(exam.rawRoom)
                                                        if (!exam.seatNumber.isNullOrBlank()) {
                                                            append(" · 座位 ${exam.seatNumber}号")
                                                        }
                                                        append(" · ${exam.examFormat}")
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            XPeachFilterPill(
                                                label = "去考场",
                                                selected = true,
                                                compact = true,
                                                onClick = { onNavigateToMapWithRoom(exam.rawRoom) }
                                            )
                                        }

                                        if (index < filteredExams.lastIndex) {
                                            XPeachInsetDivider(verticalPadding = 0.dp)
                                        }
                                    }
                                }
                            }
                        }
                        2 -> {
                            AcademicPlanProgressSection(
                                attempts = attempts,
                                retakeStrategy = cn.edu.haut.campus.core.model.RetakeInclusionStrategy.HIGHEST_ATTEMPT,
                                officialSnapshot = officialSnapshot,
                                privacyMasked = privacyMasked
                            )
                        }
                        else -> {
                            SelectionDraftSection(
                                termId = latestTermId ?: "2025-2026-2",
                                courses = courses,
                                scheduleRules = scheduleRules
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(92.dp))
        }
    }
}

@Composable
private fun GpaCompactStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun TermTrendChart(
    points: List<TermGpaTrendPoint>,
    metricIndex: Int,
    privacyMasked: Boolean
) {
    val lineColor = XPeachTheme.readablePrimary
    val values = points.map { pt ->
        when (metricIndex) {
            0 -> pt.gpa?.toFloat() ?: 0f
            1 -> pt.weightedAverageScore?.toFloat() ?: 0f
            else -> pt.earnedCredits.toFloat()
        }
    }
    val maxVal = (values.maxOrNull() ?: 4.0f).coerceAtLeast(1f)
    val minVal = (values.minOrNull() ?: 0f).coerceAtLeast(0f)
    val span = (maxVal - minVal).coerceAtLeast(0.5f)

    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (values.isEmpty()) return@Canvas
            val stepX = if (values.size > 1) size.width / (values.size - 1) else size.width / 2f
            val path = Path()

            values.forEachIndexed { idx, v ->
                val x = if (values.size > 1) idx * stepX else size.width / 2f
                val normalized = ((v - minVal) / span).coerceIn(0f, 1f)
                val y = size.height * (1f - normalized)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(
                    color = lineColor,
                    radius = 4.dp.toPx(),
                    center = Offset(x, y)
                )
            }

            if (values.size > 1) {
                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            for (pt in points) {
                val valText = if (privacyMasked) {
                    "∗∗"
                } else {
                    when (metricIndex) {
                        0 -> pt.gpa?.toPlainString() ?: "—"
                        1 -> pt.weightedAverageScore?.toPlainString() ?: "—"
                        else -> "${pt.earnedCredits}学分"
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = valText,
                        style = MaterialTheme.typography.labelMedium,
                        color = XPeachTheme.readablePrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = pt.termLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

private fun buildGradesCsv(attempts: List<cn.edu.haut.campus.core.model.GradeAttempt>): String {
    val sb = StringBuilder()
    sb.appendLine("学期,课程代码,课程名称,课程性质,学分,原始成绩,教务课程绩点,修读方式")
    for (a in attempts) {
        sb.appendLine(
            "${a.termId},${a.courseKey},${a.name},${a.category.label},${a.credits},${a.rawGrade},${a.gradePoint ?: ""},${a.attemptType.label}"
        )
    }
    return sb.toString()
}

@Composable
private fun AcademicPlanProgressSection(
    attempts: List<cn.edu.haut.campus.core.model.GradeAttempt>,
    retakeStrategy: cn.edu.haut.campus.core.model.RetakeInclusionStrategy,
    officialSnapshot: OfficialGpaSnapshot?,
    privacyMasked: Boolean
) {
    val aggregation = remember(attempts, retakeStrategy) {
        AttemptAggregator.aggregate(attempts, retakeStrategy)
    }
    val completedCourses = remember(aggregation) {
        aggregation.decisions.mapNotNull { decision ->
            if (decision.creditedCredits > BigDecimal.ZERO) {
                CompletedCourse(
                    courseKey = decision.courseKey,
                    courseName = decision.courseName,
                    credits = decision.creditedCredits
                )
            } else null
        }
    }
    val estimatedPlan = remember(attempts) {
        val groups = CourseCategory.entries.map { cat ->
            val refs = attempts.filter { it.category == cat }
                .distinctBy { it.courseKey }
                .map { PlanCourseRef(code = it.courseKey, name = it.name) }
            val kind = when (cat) {
                CourseCategory.COMPULSORY -> RequirementKind.REQUIRED
                CourseCategory.ELECTIVE -> RequirementKind.ELECTIVE
                CourseCategory.PUBLIC_ELECTIVE -> RequirementKind.GENERAL
                CourseCategory.PRACTICE -> RequirementKind.PRACTICE
            }
            val defaultTarget = when (cat) {
                CourseCategory.COMPULSORY -> BigDecimal("90.0")
                CourseCategory.ELECTIVE -> BigDecimal("30.0")
                CourseCategory.PRACTICE -> BigDecimal("24.0")
                CourseCategory.PUBLIC_ELECTIVE -> BigDecimal("14.0")
            }
            RequirementGroup(
                id = "group-${cat.name.lowercase()}",
                title = cat.label,
                kind = kind,
                requiredCredits = defaultTarget,
                courses = refs
            )
        }
        TrainingPlan(
            id = "local-reference-plan",
            title = "本科参考培养方案",
            version = "参考方案",
            applicableGradeYears = setOf(1, 2, 3, 4),
            source = PlanSource.UNKNOWN,
            confirmedForStudent = false,
            groups = groups
        )
    }
    val degreeProgress = remember(estimatedPlan, completedCourses) {
        DegreeProgressCalculator.evaluate(estimatedPlan, completedCourses)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "已修学分汇总",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                XPeachStatusDotText(
                    text = if (officialSnapshot != null) "教务公布" else "按课程学分计算"
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("• 已获得总学分：")
                    append(if (privacyMasked) "∗∗.∗" else "${aggregation.totalCreditedCredits.toPlainString()} 学分")
                    append("（重修或补考不重复计分）\n")
                    append("• 重修或补考课程：${aggregation.multiAttemptCourseCount} 门\n")
                    append("• 平时分与期末分明细以教务系统公布为准。")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (aggregation.multiAttemptCourseCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "注：存在 ${aggregation.multiAttemptCourseCount} 门重修或补考记录，已按最高成绩去重统计。",
                    style = MaterialTheme.typography.labelSmall,
                    color = XPeachTheme.readablePrimary
                )
            }
        }

        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "毕业学分分组进度",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "按课程性质统计已修学分与毕业参考目标",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                XPeachStatusDotText(
                    text = when (degreeProgress.provenance) {
                        Provenance.OFFICIAL -> "教务公布"
                        Provenance.DERIVED -> "按成绩单计算"
                        Provenance.ESTIMATED -> "参考测算"
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            degreeProgress.groups.forEachIndexed { idx, grp ->
                if (idx > 0) {
                    XPeachInsetDivider(verticalPadding = 2.dp)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${grp.title}（${grp.kind.label}）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "已修 ${if (privacyMasked) "∗∗" else grp.earnedCredits.toPlainString()} / 参考目标 ${grp.requiredCredits.toPlainString()} 学分",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = if (privacyMasked) "缺 ∗∗" else "差 ${grp.remainingCredits.toPlainString()} 学分",
                        style = MaterialTheme.typography.labelMedium,
                        color = XPeachTheme.readablePrimary
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "注：以上为参考学分进度，具体毕业要求请以本专业培养方案为准。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SelectionDraftSection(
    termId: String,
    courses: List<cn.edu.haut.campus.core.model.Course>,
    scheduleRules: List<cn.edu.haut.campus.core.model.CourseScheduleRule>
) {
    val scope = remember(termId) {
        SelectionScope(
            schoolId = SchoolDirectory.HAUT_SCHOOL_ID,
            accountId = AccountId("active-local-profile"),
            termId = termId.ifBlank { "current" }
        )
    }
    var draft by remember(scope) { mutableStateOf(SelectionDraft.empty(scope)) }
    var courseNameInput by remember { mutableStateOf("") }
    var classIdInput by remember { mutableStateOf("01班") }
    var selectedWeekday by remember { mutableIntStateOf(1) }
    var startSection by remember { mutableIntStateOf(3) }
    var endSection by remember { mutableIntStateOf(4) }
    var submissionNotice by remember { mutableStateOf<String?>(null) }

    val timetableOccupied = remember(scope.termId, courses, scheduleRules) {
        TimetableOccupancy.fromScheduleRules(scope.termId, courses, scheduleRules)
    }

    val previewCandidate = remember(courseNameInput, classIdInput, selectedWeekday, startSection, endSection, scope.termId) {
        val name = courseNameInput.trim().ifBlank { "候选课程" }
        val cls = classIdInput.trim().ifBlank { "01班" }
        SelectionCandidate(
            offeringId = "draft-$name",
            courseName = name,
            teachingClassId = cls,
            termId = scope.termId,
            slots = listOf(
                ClassSlot(
                    weekday = selectedWeekday,
                    startSection = minOf(startSection, endSection),
                    endSection = maxOf(startSection, endSection),
                    weeks = TeachingWeeks.range(1, 16)
                )
            )
        )
    }

    val conflictReport = remember(previewCandidate, timetableOccupied, draft) {
        val otherDraftSlots = draft.occupiedSlots()
            .filterNot { it.courseName == previewCandidate.courseName }
        SelectionConflictAnalyzer.analyze(previewCandidate, timetableOccupied + otherDraftSlots)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选课预排与撞课检测",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                XPeachStatusDotText(text = "撞课检测")
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "添加想选的课程，自动检测与本学期已排课表及草稿的时间冲突。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = courseNameInput,
                onValueChange = { courseNameInput = it.take(40) },
                label = { Text("候选课程名称") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = classIdInput,
                onValueChange = { classIdInput = it.take(24) },
                label = { Text("教学班号") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (day in 1..7) {
                    XPeachFilterPill(
                        label = ClassSlot.weekdayLabelOf(day),
                        selected = selectedWeekday == day,
                        onClick = { selectedWeekday = day }
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val pairs = listOf(1 to 2, 3 to 4, 5 to 6, 7 to 8, 9 to 10)
                for ((s, e) in pairs) {
                    XPeachFilterPill(
                        label = "第${s}-${e}节",
                        selected = startSection == s && endSection == e,
                        onClick = {
                            startSection = s
                            endSection = e
                        }
                    )
                }
            }

            XPeachInsetDivider(verticalPadding = 8.dp)
            XPeachStatusDotText(
                text = conflictReport.summary,
                dotColor = if (conflictReport.hasConflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                textColor = if (conflictReport.hasConflict) MaterialTheme.colorScheme.error else XPeachTheme.readablePrimary
            )
            conflictReport.details.forEach { detail ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "• ${detail.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Button(
                enabled = courseNameInput.isNotBlank(),
                onClick = {
                    val name = courseNameInput.trim()
                    val cls = classIdInput.trim().ifBlank { "01班" }
                    draft = draft.add(
                        DraftEntry(
                            offeringId = "draft-$name",
                            courseName = name,
                            teachingClassId = cls,
                            slots = previewCandidate.slots
                        )
                    ).draft
                    courseNameInput = ""
                    submissionNotice = "已加入选课预排草稿"
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("加入选课草稿")
            }
            submissionNotice?.let { notice ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = notice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!draft.isEmpty) {
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "已选草稿 (${draft.entries.size} 门)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = { draft = draft.clear().draft }) {
                        Text("清空")
                    }
                }
                draft.entries.forEachIndexed { idx, entry ->
                    if (idx > 0) {
                        XPeachInsetDivider(verticalPadding = 2.dp)
                    }
                    val slotText = entry.slots.joinToString("；") { "${it.weekdayLabel} ${it.sectionText}" }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.courseName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                text = "${entry.teachingClassId} · $slotText",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { draft = draft.remove(entry.offeringId).draft }) {
                            Text("移除")
                        }
                    }
                }
            }
        }
    }
}
