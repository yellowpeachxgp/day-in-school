package cn.edu.haut.campus.feature.grades

import android.content.Intent
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.text.style.TextOverflow
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
    var trendMetricIndex by remember { mutableIntStateOf(0) }

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
            title = "学业成绩",
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
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp)
                        ) {
                            Text(
                                text = "暂无成绩数据",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "登录教务系统同步历史课程成绩与绩点",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = onNavigateToImporter,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("去同步", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // 1. 2×2 绩点与学分概览卡片（避免4列横向拥挤）
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("绩点与学分", style = MaterialTheme.typography.titleMedium)
                    XPeachStatusBadge(text = gpaReport.primarySourceType.badgeLabel)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    XPeachWell(modifier = Modifier.weight(1f)) {
                        GpaMetricTile(
                            label = "累计 GPA",
                            value = if (privacyMasked) "∗.∗∗" else (gpaReport.cumulativeGpa?.toPlainString() ?: "—")
                        )
                    }
                    XPeachWell(modifier = Modifier.weight(1f)) {
                        GpaMetricTile(
                            label = "当前学期 GPA",
                            value = if (privacyMasked) "∗.∗∗" else (gpaReport.currentTermGpa?.toPlainString() ?: "—")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    XPeachWell(modifier = Modifier.weight(1f)) {
                        GpaMetricTile(
                            label = "学年 GPA",
                            value = if (privacyMasked) "∗.∗∗" else (gpaReport.currentAcademicYearGpa?.toPlainString() ?: "—")
                        )
                    }
                    XPeachWell(modifier = Modifier.weight(1f)) {
                        GpaMetricTile(
                            label = "已修学分",
                            value = if (privacyMasked) "∗∗.∗" else gpaReport.totalEarnedCredits.toPlainString()
                        )
                    }
                }
            }

            // 2. 学期趋势与分布
            if (gpaReport.termTrends.isNotEmpty()) {
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("学期走势", style = MaterialTheme.typography.titleMedium)
                        XPeachSegmentedTabs(
                            items = listOf("GPA", "均分", "学分"),
                            selectedIndex = trendMetricIndex,
                            onSelect = { trendMetricIndex = it },
                            modifier = Modifier.width(168.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    TermTrendChart(
                        points = gpaReport.termTrends,
                        metricIndex = trendMetricIndex,
                        privacyMasked = privacyMasked
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    val dist = gpaReport.distribution
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        XPeachWell(modifier = Modifier.weight(1f)) {
                            Text("90+ 优秀", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            Text("${dist.excellentCount} 门", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                        }
                        XPeachWell(modifier = Modifier.weight(1f)) {
                            Text("80-89 良好", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            Text("${dist.goodCount} 门", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                        }
                        XPeachWell(modifier = Modifier.weight(1f)) {
                            Text("70-79 中等", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            Text("${dist.mediumCount} 门", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                        }
                        XPeachWell(modifier = Modifier.weight(1f)) {
                            Text("60+ 及格", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            Text("${dist.passCount} 门", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // 3. 学期与课程类别筛选（横向滚动，杜绝文字换行变形）
            if (availableTerms.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
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

            // 4. 成绩列表
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
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Text(
                                text = att.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
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
                }
            }
        }
    }
}

@Composable
private fun GpaMetricTile(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
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
                .height(96.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp)
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
                    radius = 4.5.dp.toPx(),
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
