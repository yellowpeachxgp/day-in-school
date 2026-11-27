package cn.edu.haut.campus.feature.grades.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachEmptyState
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
import cn.edu.haut.campus.feature.grades.state.GradesScreenStatus
import cn.edu.haut.campus.feature.grades.state.GradesUiAction
import cn.edu.haut.campus.feature.grades.state.GradesUiState
import cn.edu.haut.campus.feature.grades.ui.components.AcademicPlanProgressSection
import cn.edu.haut.campus.feature.grades.ui.components.ExamScheduleRowItem
import cn.edu.haut.campus.feature.grades.ui.components.GpaCompactStat
import cn.edu.haut.campus.feature.grades.ui.components.GradesStatusBanner
import cn.edu.haut.campus.feature.grades.ui.components.GradesStatusScaffold
import cn.edu.haut.campus.feature.grades.ui.components.SelectionDraftSection
import cn.edu.haut.campus.feature.grades.ui.components.TermTrendChart
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.AcademicTermResolver
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import cn.edu.haut.campus.core.selection.SelectionDraft
import cn.edu.haut.campus.core.selection.SelectionScope
import java.time.Instant
import java.time.ZonedDateTime

@Composable
fun GradesScreen(
    state: GradesUiState,
    onAction: (GradesUiAction) -> Unit,
    modifier: Modifier = Modifier,
    resetTrigger: Int = 0
) {
    when (state.status) {
        GradesScreenStatus.LOADING,
        GradesScreenStatus.ERROR_RETRYABLE,
        GradesScreenStatus.ERROR_TERMINAL,
        GradesScreenStatus.NEEDS_PERMISSION,
        GradesScreenStatus.NEEDS_LOGIN,
        GradesScreenStatus.UNSUPPORTED -> GradesStatusScaffold(
            status = state.status,
            onAction = onAction,
            modifier = modifier
        )
        else -> GradesLegacyContent(
            state = state,
            onAction = onAction,
            modifier = modifier,
            resetTrigger = resetTrigger
        )
    }
}

@Composable
private fun GradesLegacyContent(
    state: GradesUiState,
    onAction: (GradesUiAction) -> Unit,
    modifier: Modifier = Modifier,
    resetTrigger: Int = 0
) {
    val scrollState = rememberScrollState()
    val navigateToImporter: () -> Unit = { onAction(GradesUiAction.OpenImporter) }
    val navigateToMapWithRoom: (String) -> Unit = { onAction(GradesUiAction.OpenMapRoom(it)) }
    val navigateToLibrarySeats: () -> Unit = { onAction(GradesUiAction.OpenLibrarySeats) }
    val navigateToLibraryCatalog: () -> Unit = { onAction(GradesUiAction.OpenLibraryCatalog) }
    val navigateToFocus: () -> Unit = { onAction(GradesUiAction.OpenFocus) }
    val navigateToEmptyClassrooms: () -> Unit = { onAction(GradesUiAction.OpenEmptyClassrooms) }
    val navigateToCompetitions: () -> Unit = { onAction(GradesUiAction.OpenCompetitions) }

    val currentTerm = state.currentTerm
    val attempts = state.attempts
    val examSchedules = state.examSchedules
    val examTermReviewAudits = state.examTermReviewAudits
    val courses = state.courses
    val scheduleRules = state.scheduleRules
    val prefs = state.prefs
    val studentProfile = state.studentProfile
    val activeFocusSession = state.activeFocusSession
    val completedFocusSessions = state.completedFocusSessions
    val todayDate = remember { ZonedDateTime.now(HAUT_ZONE_ID).toLocalDate() }
    val activeTermId = remember(currentTerm?.id) {
        currentTerm?.id?.takeIf { it.isNotBlank() } ?: AcademicTermResolver.defaultTermId()
    }

    // 纯界面交互状态随组合存活：底栏切换/账号切换离开组合即重置，与拆分前一致
    val interactionState = rememberGradesInteractionState()
    var selectedTermFilter by interactionState.selectedTermFilter
    var selectedCategoryFilter by interactionState.selectedCategoryFilter
    var activeSubTab by interactionState.activeSubTab
    var trendMetricIndex by interactionState.trendMetricIndex
    var showTrendSection by interactionState.showTrendSection
    var showSimulatorSection by interactionState.showSimulatorSection
    var simulatedNewCredits by interactionState.simulatedNewCredits
    var simulatedNewGpa by interactionState.simulatedNewGpa

    LaunchedEffect(resetTrigger) {
        if (resetTrigger > 0) {
            scrollState.animateScrollTo(0)
            interactionState.resetFilters()
        }
    }

    val availableTerms = remember(attempts, examSchedules, activeTermId) {
        (listOf(activeTermId) + attempts.map { it.termId } + examSchedules.map { it.termId })
            .filter { it.isNotBlank() }
            .distinct()
            .sortedDescending()
    }
    val latestTermId = selectedTermFilter ?: activeTermId.ifBlank { availableTerms.firstOrNull() ?: "" }
    val latestAcademicYear = attempts.firstOrNull { it.termId == latestTermId }?.academicYear
        ?: latestTermId.split("-").take(2).joinToString("-").takeIf { it.contains("-") }

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
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "学业",
            subtitle = selectedTermFilter ?: "当前学期 $activeTermId",
            badgeText = if (privacyMasked) "已隐藏" else null,
            actions = {
                XPeachIconButton(
                    icon = if (privacyMasked) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = "切换成绩可见性",
                    onClick = { onAction(GradesUiAction.ToggleGpaPrivacyShield) }
                )
                XPeachIconButton(
                    icon = Icons.Default.Share,
                    contentDescription = "导出CSV",
                    onClick = { onAction(GradesUiAction.ExportGradesCsv) }
                )
                XPeachIconButton(
                    icon = Icons.Default.CloudDownload,
                    contentDescription = "同步成绩",
                    onClick = navigateToImporter
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
            GradesStatusBanner(
                status = state.status,
                modifier = Modifier.fillMaxWidth()
            )

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
                                text = "暂无成绩",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "同步后可查看成绩与绩点",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = navigateToImporter,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("同步", maxLines = 1, softWrap = false)
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
                "剩余 $remMin 分钟"
            } else if (todayDoneMins > 0) {
                "今日已专注 $todayDoneMins 分钟"
            } else {
                "番茄钟倒计时"
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    XPeachCard(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        onClick = navigateToLibrarySeats
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "图书馆选座",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 4.dp)
                            )
                            XPeachStatusDotText(text = "预约")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "余位查询与选座",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    XPeachCard(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        onClick = navigateToLibraryCatalog
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "馆藏检索",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 4.dp)
                            )
                            XPeachStatusDotText(text = "馆藏")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "书目与索书号查询",
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
                        onClick = navigateToFocus
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "自习专注",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 4.dp)
                            )
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
                        onClick = navigateToEmptyClassrooms
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "空闲教室",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 4.dp)
                            )
                            XPeachStatusDotText(text = "教学楼")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "按教学楼与节次查询",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                XPeachCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = XPeachTheme.secondaryAccentCardColor,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    onClick = navigateToCompetitions
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "学科竞赛",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        XPeachStatusBadge(
                            text = "${prefs.campusCommunityCatalog.competitions.size} 项赛事",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = XPeachTheme.readablePrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "竞赛日程、官网与组队信息",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp)
                        )
                        XPeachFilterPill(
                            label = "查看",
                            selected = true,
                            compact = true,
                            onClick = navigateToCompetitions
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
                    if (selectedTermFilter == null && gpaReport.currentTermGpa == null && attempts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "当前学期 ($activeTermId) 暂未出分，下方展示历史已出分成绩",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            text = "新增学分：${simulatedNewCredits.toInt()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = simulatedNewCredits,
                            onValueChange = { simulatedNewCredits = it },
                            valueRange = 2f..60f
                        )
                        Text(
                            text = "预期绩点：${String.format(java.util.Locale.US, "%.2f", simulatedNewGpa)}",
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
            val examTabCount = remember(examSchedules, selectedTermFilter, activeTermId) {
                val targetTerm = selectedTermFilter ?: activeTermId
                examSchedules.count { it.termId == targetTerm }
            }
            XPeachSegmentedTabs(
                items = listOf(
                    "成绩(${gpaReport.auditItems.size})",
                    "考试($examTabCount)",
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

            val effectiveSelectionTermId = latestTermId ?: AcademicTermResolver.defaultTermId()
            val selectionScope = remember(effectiveSelectionTermId) {
                SelectionScope(
                    schoolId = SchoolDirectory.HAUT_SCHOOL_ID,
                    accountId = AccountId("active-local-profile"),
                    termId = effectiveSelectionTermId.ifBlank { "current" }
                )
            }
            var hoistedSelectionDraft by remember(selectionScope) {
                mutableStateOf(SelectionDraft.empty(selectionScope))
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
                                                    text = "${att.credits} 学分 · ${att.category.label} · ${att.termId}",
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
                            } else {
                                XPeachEmptyState(
                                    title = "无匹配成绩",
                                    subtitle = "可切换筛选条件或同步成绩",
                                    actionLabel = "同步成绩",
                                    onActionClick = navigateToImporter
                                )
                            }
                        }
                        1 -> {
                            // 3B. 考试安排列表：严格区分「当前学期」与「历史学期归档」，绝不将旧学期考试静默冒充本学期考试
                            if (examTermReviewAudits.isNotEmpty()) {
                                XPeachWell(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "有 ${examTermReviewAudits.size} 条考试记录所属学期无法自动确认（可能来自旧版本错标），已保留待核对——请到同步入口选择正确学期重新同步核对。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            val targetExamTerm = selectedTermFilter ?: activeTermId
                            val primaryTermExams = remember(examSchedules, targetExamTerm) {
                                examSchedules.filter { it.termId == targetExamTerm }
                            }
                            val historicalTermExams = remember(examSchedules, activeTermId, selectedTermFilter) {
                                if (selectedTermFilter == null) {
                                    examSchedules.filter { it.termId != activeTermId }
                                } else {
                                    emptyList()
                                }
                            }

                            if (primaryTermExams.isEmpty()) {
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
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = if (selectedTermFilter == null || selectedTermFilter == activeTermId) {
                                                    "当前学期（$targetExamTerm）暂无考试安排"
                                                } else {
                                                    "$targetExamTerm 暂无考试安排"
                                                },
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = if (historicalTermExams.isNotEmpty()) {
                                                    "本学期暂未发布或尚未同步考试安排；下方保留历史学期考试归档供查阅"
                                                } else {
                                                    "本学期暂未发布或尚未同步考试安排，教务公布后可点击同步获取"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        XPeachFilterPill(
                                            label = "去同步",
                                            selected = true,
                                            compact = true,
                                            onClick = navigateToImporter
                                        )
                                    }
                                }
                            } else {
                                XPeachCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp, bottom = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (targetExamTerm == activeTermId) {
                                                "当前学期考试安排 ($targetExamTerm)"
                                            } else {
                                                "$targetExamTerm 考试安排"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        XPeachStatusDotText(text = "${primaryTermExams.size} 场")
                                    }
                                    primaryTermExams.forEachIndexed { index, exam ->
                                        ExamScheduleRowItem(
                                            exam = exam,
                                            showTermBadge = false,
                                            navigateToMapWithRoom = navigateToMapWithRoom
                                        )
                                        if (index < primaryTermExams.lastIndex) {
                                            XPeachInsetDivider(verticalPadding = 0.dp)
                                        }
                                    }
                                }
                            }

                            if (historicalTermExams.isNotEmpty()) {
                                XPeachCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp, bottom = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "历史学期考试归档",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "共 ${historicalTermExams.size} 场",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    XPeachInsetDivider(verticalPadding = 2.dp)
                                    historicalTermExams.forEachIndexed { index, exam ->
                                        ExamScheduleRowItem(
                                            exam = exam,
                                            showTermBadge = true,
                                            navigateToMapWithRoom = navigateToMapWithRoom
                                        )
                                        if (index < historicalTermExams.lastIndex) {
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
                                privacyMasked = privacyMasked,
                                studentMajor = studentProfile?.major,
                                competitions = prefs.campusCommunityCatalog.competitions,
                                navigateToCompetitions = navigateToCompetitions
                            )
                        }
                        else -> {
                            SelectionDraftSection(
                                scope = selectionScope,
                                draft = hoistedSelectionDraft,
                                onDraftChange = { hoistedSelectionDraft = it },
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
