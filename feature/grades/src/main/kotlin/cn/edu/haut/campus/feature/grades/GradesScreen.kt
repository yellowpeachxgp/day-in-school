package cn.edu.haut.campus.feature.grades

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.domain.GpaCalculator
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import cn.edu.haut.campus.core.model.TermGpaTrendPoint
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradesScreen(
    repository: CampusRepository,
    onNavigateToImporter: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val attempts by repository.gradeAttemptsFlow.collectAsState(initial = emptyList())
    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())

    var selectedTermFilter by remember { mutableStateOf<String?>(null) }
    var selectedCategoryFilter by remember { mutableStateOf<CourseCategory?>(null) }
    var trendMetricIndex by remember { mutableIntStateOf(0) } // 0: GPA, 1: 平均分, 2: 获得学分

    val availableTerms = remember(attempts) {
        attempts.map { it.termId }.distinct().sortedDescending()
    }
    val latestTermId = selectedTermFilter ?: availableTerms.firstOrNull()
    val latestAcademicYear = attempts.firstOrNull { it.termId == latestTermId }?.academicYear

    val officialSnapshot = remember(prefs.officialGpaValuePlain, prefs.officialGpaNote) {
        val officialVal = prefs.officialGpaValuePlain?.toBigDecimalOrNull()
        if (officialVal != null) {
            OfficialGpaSnapshot(
                cumulativeGpa = officialVal,
                fetchedAt = Instant.now(),
                fieldDefinitionNote = prefs.officialGpaNote ?: "教务官方汇总"
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("我的学业与 GPA", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = latestTermId ?: "全部学期",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                repository.setGpaPrivacyShield(!privacyMasked)
                            }
                        }
                    ) {
                        Icon(
                            if (privacyMasked) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "切换成绩隐私与防截屏保护"
                        )
                    }
                    IconButton(
                        onClick = {
                            val csv = buildGradesCsv(attempts)
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_SUBJECT, "河南工业大学个人成绩明细导出.csv")
                                putExtra(Intent.EXTRA_TEXT, csv)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "导出成绩 CSV（仅本地最小化字段）"))
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "导出本地CSV")
                    }
                    IconButton(onClick = onNavigateToImporter) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "同步教务成绩")
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
            // 1. 顶部 GPA 总览看板（带明确来源标签）
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = gpaReport.primarySourceType.badgeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        if (privacyMasked) {
                            Text(
                                text = "已开启防截屏/隐私遮罩",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        GpaMetricColumn(
                            label = "累计 GPA",
                            value = if (privacyMasked) "∗.∗∗" else (gpaReport.cumulativeGpa?.toPlainString() ?: "—")
                        )
                        GpaMetricColumn(
                            label = "当前筛选学期 GPA",
                            value = if (privacyMasked) "∗.∗∗" else (gpaReport.currentTermGpa?.toPlainString() ?: "—")
                        )
                        GpaMetricColumn(
                            label = "学年 GPA",
                            value = if (privacyMasked) "∗.∗∗" else (gpaReport.currentAcademicYearGpa?.toPlainString() ?: "—")
                        )
                        GpaMetricColumn(
                            label = "已修学分",
                            value = if (privacyMasked) "∗∗.∗" else gpaReport.totalEarnedCredits.toPlainString()
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = gpaReport.sourceExplanation,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }
            }

            // 2. 学期 GPA / 平均分 / 学分 趋势折线图
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("学期学业走势", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = trendMetricIndex == 0,
                                onClick = { trendMetricIndex = 0 },
                                label = { Text("GPA") }
                            )
                            FilterChip(
                                selected = trendMetricIndex == 1,
                                onClick = { trendMetricIndex = 1 },
                                label = { Text("平均分") }
                            )
                            FilterChip(
                                selected = trendMetricIndex == 2,
                                onClick = { trendMetricIndex = 2 },
                                label = { Text("学分") }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (gpaReport.termTrends.isEmpty()) {
                        Text(
                            text = "暂无学期成绩数据，请点击右上角同步成绩。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        TermTrendChart(
                            points = gpaReport.termTrends,
                            metricIndex = trendMetricIndex,
                            privacyMasked = privacyMasked
                        )
                    }
                }
            }

            // 3. 课程成绩分布与绩点诊断说明
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("课程成绩分布与计算核查", style = MaterialTheme.typography.titleMedium)
                    val dist = gpaReport.distribution
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("优秀(90+): ${dist.excellentCount}门", style = MaterialTheme.typography.labelMedium)
                        Text("良好(80-89): ${dist.goodCount}门", style = MaterialTheme.typography.labelMedium)
                        Text("中等(70-79): ${dist.mediumCount}门", style = MaterialTheme.typography.labelMedium)
                        Text("及格/通过: ${dist.passCount}门", style = MaterialTheme.typography.labelMedium)
                    }

                    for (note in gpaReport.diagnosticNotes) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "• $note",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            // 4. 学期与课程类别筛选栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = { selectedTermFilter = null },
                    label = { Text(if (selectedTermFilter == null) "✓ 全部学期" else "全部学期") }
                )
                for (termId in availableTerms) {
                    AssistChip(
                        onClick = { selectedTermFilter = termId },
                        label = { Text(if (selectedTermFilter == termId) "✓ $termId" else termId) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedCategoryFilter == null,
                    onClick = { selectedCategoryFilter = null },
                    label = { Text("全部性质") }
                )
                for (cat in CourseCategory.entries) {
                    FilterChip(
                        selected = selectedCategoryFilter == cat,
                        onClick = { selectedCategoryFilter = cat },
                        label = { Text(cat.label) }
                    )
                }
            }

            // 5. 课程成绩与绩点计算核查明细列表
            val filteredAudit = remember(gpaReport.auditItems, selectedTermFilter, selectedCategoryFilter) {
                gpaReport.auditItems.filter { item ->
                    val att = item.selectedAttempt ?: return@filter false
                    val termMatch = selectedTermFilter == null || att.termId == selectedTermFilter
                    val catMatch = selectedCategoryFilter == null || att.category == selectedCategoryFilter
                    termMatch && catMatch
                }
            }

            for (audit in filteredAudit) {
                val att = audit.selectedAttempt ?: continue
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = att.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${att.termId} · ${att.category.label} · ${att.credits} 学分 · ${att.attemptType.label}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (privacyMasked) "成绩 ∗∗" else "成绩 ${att.rawGrade}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (privacyMasked) {
                                        "绩点 ∗.∗"
                                    } else {
                                        att.gradePoint?.let { "教务绩点 $it" } ?: "无课程绩点"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (audit.includedInGpaDenominator) {
                                "✓ 已纳入分母：${audit.exclusionOrChangeReason}"
                            } else {
                                "○ 未计入分母：${audit.exclusionOrChangeReason}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (audit.includedInGpaDenominator) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GpaMetricColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
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
                .height(110.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
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
                    radius = 6.dp.toPx(),
                    center = Offset(x, y)
                )
            }

            if (values.size > 1) {
                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
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
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = pt.termLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
