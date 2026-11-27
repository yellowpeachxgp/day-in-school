package cn.edu.haut.campus.feature.grades.ui.components

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import cn.edu.haut.campus.feature.grades.state.GradesUiAction
import cn.edu.haut.campus.feature.grades.state.GradesUiState
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.AcademicTermResolver
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
import java.math.BigDecimal
import java.time.Instant
import java.time.ZonedDateTime


@Composable
internal fun ExamScheduleRowItem(
    exam: cn.edu.haut.campus.core.model.ExamScheduleItem,
    showTermBadge: Boolean,
    navigateToMapWithRoom: (String) -> Unit
) {
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
                XPeachStatusDotText(
                    text = if (showTermBadge) "${exam.termId} · ${exam.examStageLabel}" else exam.examStageLabel
                )
            }
            Text(
                text = exam.rawTimeText,
                style = MaterialTheme.typography.labelMedium,
                color = XPeachTheme.readablePrimary,
                maxLines = 2,
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        XPeachFilterPill(
            label = "去考场",
            selected = true,
            compact = true,
            onClick = { navigateToMapWithRoom(exam.rawRoom) }
        )
    }
}

@Composable
internal fun GpaCompactStat(
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
internal fun TermTrendChart(
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
