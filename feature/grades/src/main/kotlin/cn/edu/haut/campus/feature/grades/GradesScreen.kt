package cn.edu.haut.campus.feature.grades

import android.content.Intent
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.GpaCalculator
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import cn.edu.haut.campus.core.model.TermGpaTrendPoint
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun GradesScreen(
    repository: CampusRepository,
    onNavigateToImporter: () -> Unit,
    onNavigateToMapWithRoom: (String) -> Unit = {},
    resetTrigger: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val attempts by repository.gradeAttemptsFlow.collectAsState(initial = emptyList())
    val examSchedules by repository.examSchedulesFlow.collectAsState(initial = emptyList())
    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())

    var selectedTermFilter by remember { mutableStateOf<String?>(null) }
    var selectedCategoryFilter by remember { mutableStateOf<CourseCategory?>(null) }
    var activeSubTab by remember { mutableIntStateOf(0) } // 0: 成绩明细, 1: 考试安排
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
                                text = "同步教务系统成绩后自动计算累计绩点与学分",
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

            // 1. 聚合式绩点焦点卡片
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "累计平均绩点 (GPA)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (privacyMasked) "∗.∗∗" else (gpaReport.cumulativeGpa?.toPlainString() ?: "—"),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        XPeachFilterPill(
                            label = if (showSimulatorSection) "收起模拟" else "绩点模拟",
                            selected = showSimulatorSection,
                            onClick = { showSimulatorSection = !showSimulatorSection }
                        )
                        if (gpaReport.termTrends.isNotEmpty()) {
                            XPeachFilterPill(
                                label = if (showTrendSection) "收起走势" else "走势分析",
                                selected = showTrendSection,
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
                            label = "本学期 GPA",
                            value = if (privacyMasked) "∗.∗∗" else (gpaReport.currentTermGpa?.toPlainString() ?: "—"),
                            modifier = Modifier.weight(1f)
                        )
                        GpaCompactStat(
                            label = "本学年 GPA",
                            value = if (privacyMasked) "∗.∗∗" else (gpaReport.currentAcademicYearGpa?.toPlainString() ?: "—"),
                            modifier = Modifier.weight(1f)
                        )
                        GpaCompactStat(
                            label = "已修总学分",
                            value = if (privacyMasked) "∗∗.∗" else gpaReport.totalEarnedCredits.toPlainString(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 目标绩点与新修学分模拟器
                if (showSimulatorSection) {
                    Spacer(modifier = Modifier.height(12.dp))
                    val baseCredits = gpaReport.gpaIncludedCredits.toFloat().coerceAtLeast(0f)
                    val baseGpa = gpaReport.cumulativeGpa?.toFloat() ?: 3.2f
                    val totalSimCredits = (baseCredits + simulatedNewCredits).coerceAtLeast(1f)
                    val projectedGpa = ((baseCredits * baseGpa) + (simulatedNewCredits * simulatedNewGpa)) / totalSimCredits

                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "预计新修后总 GPA",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = String.format(java.util.Locale.US, "%.2f", projectedGpa),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "未来计划新修学分：${simulatedNewCredits.toInt()} 学分",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = simulatedNewCredits,
                            onValueChange = { simulatedNewCredits = it },
                            valueRange = 2f..60f
                        )
                        Text(
                            text = "预计新修课程均绩：${String.format(java.util.Locale.US, "%.2f", simulatedNewGpa)}",
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
                if (showTrendSection && gpaReport.termTrends.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
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
                            modifier = Modifier.width(164.dp)
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

            // 2. 成绩明细 / 考试安排 分段切换 + 学期筛选条
            XPeachSegmentedTabs(
                items = listOf("成绩明细 (${gpaReport.auditItems.size})", "考试安排 (${examSchedules.size})"),
                selectedIndex = activeSubTab,
                onSelect = { activeSubTab = it }
            )

            if (availableTerms.isNotEmpty()) {
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

            if (activeSubTab == 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    XPeachFilterPill(
                        label = "全部性质",
                        selected = selectedCategoryFilter == null,
                        onClick = { selectedCategoryFilter = null }
                    )
                    for (cat in CourseCategory.entries) {
                        XPeachFilterPill(
                            label = cat.label,
                            selected = selectedCategoryFilter == cat,
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
                                        color = MaterialTheme.colorScheme.primary,
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
                                HorizontalDivider(
                                    thickness = 0.6.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                                )
                            }
                        }
                    }
                }
            } else {
                // 3B. 考试安排列表（涵盖期初补缓考、期中随堂/分散考试、期末集中考试）
                val filteredExams = remember(examSchedules, selectedTermFilter) {
                    examSchedules.filter { selectedTermFilter == null || it.termId == selectedTermFilter }
                }

                if (filteredExams.isEmpty()) {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "当前筛选下暂无考试安排记录。点击右上角同步按钮可从正方教务系统拉取最新考试日程。",
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
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = exam.courseName,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        XPeachStatusBadge(
                                            text = exam.examStageLabel,
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = "${exam.termId} · ${exam.rawTimeText}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
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
                                    onClick = { onNavigateToMapWithRoom(exam.rawRoom) }
                                )
                            }

                            if (index < filteredExams.lastIndex) {
                                HorizontalDivider(
                                    thickness = 0.6.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                                )
                            }
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
    val lineColor = MaterialTheme.colorScheme.primary
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
                        color = MaterialTheme.colorScheme.primary,
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
