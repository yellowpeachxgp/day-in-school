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
internal fun SelectionDraftSection(
    scope: SelectionScope,
    draft: SelectionDraft,
    onDraftChange: (SelectionDraft) -> Unit,
    courses: List<cn.edu.haut.campus.core.model.Course>,
    scheduleRules: List<cn.edu.haut.campus.core.model.CourseScheduleRule>
) {
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
                    text = "选课预排",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                XPeachStatusDotText(text = "冲突检测")
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "检测候选课程与现有课表的时间冲突",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = courseNameInput,
                onValueChange = { courseNameInput = it.take(40) },
                label = { Text("课程名称") },
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
                    onDraftChange(
                        draft.add(
                            DraftEntry(
                                offeringId = "draft-$name",
                                courseName = name,
                                teachingClassId = cls,
                                slots = previewCandidate.slots
                            )
                        ).draft
                    )
                    courseNameInput = ""
                    submissionNotice = "已加入草稿"
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("加入草稿")
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
                    TextButton(onClick = { onDraftChange(draft.clear().draft) }) {
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
                        TextButton(onClick = { onDraftChange(draft.remove(entry.offeringId).draft) }) {
                            Text("移除")
                        }
                    }
                }
            }
        }
    }
}
