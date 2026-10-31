package cn.edu.haut.campus.feature.grades

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "我的学业与 GPA",
            subtitle = latestTermId ?: "全部学期汇总",
            badgeText = if (privacyMasked) "防截屏保护中" else null,
            actions = {
                XPeachIconButton(
                    onClick = {
                        scope.launch {
                            repository.setGpaPrivacyShield(!privacyMasked)
                        }
                    }
                ) {
                    Icon(
                        if (privacyMasked) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "切换成绩隐私与防截屏保护",
                        tint = if (privacyMasked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
                XPeachIconButton(
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
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "导出本地CSV",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
                XPeachIconButton(onClick = onNavigateToImporter) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = "同步教务成绩",
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
            if (attempts.isEmpty()) {
                XPeachCard(
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "尚未同步学业成绩",
                            style = MaterialTheme.typography.titleLarge
                        )
                        XPeachStatusBadge(text = "空白")
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "当前没有任何预设演示成绩。点击下方按钮连接河南工业大学统一身份认证，即可拉取您的全部历史课程成绩、课程绩点与修读明细。",
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
                        Text("同步河工大教务成绩")
                    }
                }
            }

            // 1. 顶部 GPA 总览看板（对标 xpeach-sso Bento Metric Tiles 风格）
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GPA 与学分概览",
                        style = MaterialTheme.typography.titleMedium
                    )
                    XPeachStatusBadge(text = gpaReport.primarySourceType.badgeLabel)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    XPeachWell(modifier = Modifier.weight(1f)) {
                        GpaMetricColumn(
                            label = "累计 GPA",
                            value = if (privacyMasked) "∗.∗∗" else (gpaReport.cumulativeGpa?.toPlainString() ?: "—")
                        )
                    }
                    XPeachWell(modifier = Modifier.weight(1f)) {
                        GpaMetricColumn(
                            label = "当前学期",
                            value = if (privacyMasked) "∗.∗∗" else (gpaReport.currentTermGpa?.toPlainString() ?: "—")
                        )
                    }
                    XPeachWell(modifier = Modifier.weight(1f)) {
                        GpaMetricColumn(
                            label = "学年 GPA",
                            value = if (privacyMasked) "∗.∗∗" else (gpaReport.currentAcademicYearGpa?.toPlainString() ?: "—")
                        )
                    }
                    XPeachWell(modifier = Modifier.weight(1f)) {
                        GpaMetricColumn(
                            label = "已修学分",
                            value = if (privacyMasked) "∗∗.∗" else gpaReport.totalEarnedCredits.toPlainString()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = gpaReport.sourceExplanation,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 2. 学期 GPA / 平均分 / 学分 趋势折线图
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("学期学业走势", style = MaterialTheme.typography.titleMedium)
                    XPeachSegmentedTabs(
                        items = listOf("GPA", "平均分", "学分"),
                        selectedIndex = trendMetricIndex,
                        onSelect = { trendMetricIndex = it },
                        modifier = Modifier.width(196.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (gpaReport.termTrends.isEmpty()) {
                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "暂无学期成绩数据，请点击右上角同步成绩。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    TermTrendChart(
                        points = gpaReport.termTrends,
                        metricIndex = trendMetricIndex,
                        privacyMasked = privacyMasked
                    )
                }
            }

            // 3. 课程成绩分布与绩点诊断说明
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("课程成绩分布与计算核查", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
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

                if (gpaReport.diagnosticNotes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (note in gpaReport.diagnosticNotes) {
                            XPeachWell(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "• $note",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            // 4. 学期与课程类别筛选栏（XPeachFilterPill）
            if (availableTerms.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
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
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GpaMetricColumn(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    radius = 5.dp.toPx(),
                    center = Offset(x, y)
                )
            }

            if (values.size > 1) {
                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(width = 2.5.dp.toPx())
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
