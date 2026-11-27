package cn.edu.haut.campus.feature.grades.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import cn.edu.haut.campus.core.plan.AttemptAggregator
import cn.edu.haut.campus.core.plan.CompletedCourse
import cn.edu.haut.campus.core.plan.DegreeProgressCalculator
import cn.edu.haut.campus.core.plan.PlanCourseRef
import cn.edu.haut.campus.core.plan.PlanSource
import cn.edu.haut.campus.core.plan.Provenance
import cn.edu.haut.campus.core.plan.RequirementGroup
import cn.edu.haut.campus.core.plan.RequirementKind
import cn.edu.haut.campus.core.plan.TrainingPlan
import java.math.BigDecimal

@Composable
internal fun AcademicPlanProgressSection(
    attempts: List<cn.edu.haut.campus.core.model.GradeAttempt>,
    retakeStrategy: cn.edu.haut.campus.core.model.RetakeInclusionStrategy,
    officialSnapshot: OfficialGpaSnapshot?,
    privacyMasked: Boolean,
    studentMajor: String? = null,
    competitions: List<cn.edu.haut.campus.core.model.CampusCompetitionItem> = emptyList(),
    navigateToCompetitions: () -> Unit = {}
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
            title = "培养方案",
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
                    text = "学分汇总",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                XPeachStatusDotText(
                    text = if (officialSnapshot != null) "教务数据" else "本地计算"
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("已获学分：")
                    append(if (privacyMasked) "∗∗.∗" else "${aggregation.totalCreditedCredits.toPlainString()} 学分")
                    append("\n重修/补考：${aggregation.multiAttemptCourseCount} 门")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (aggregation.multiAttemptCourseCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "重修或补考课程已按最高成绩计入",
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
                        text = "学分进度",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "按课程性质统计",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                XPeachStatusDotText(
                    text = when (degreeProgress.provenance) {
                        Provenance.OFFICIAL -> "教务数据"
                        Provenance.DERIVED -> "本地计算"
                        Provenance.ESTIMATED -> "参考值"
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
                            text = "已修 ${if (privacyMasked) "∗∗" else grp.earnedCredits.toPlainString()} / ${grp.requiredCredits.toPlainString()} 学分",
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
                text = "仅供参考，以本专业培养方案为准",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (competitions.isNotEmpty()) {
            val inferredTrack = remember(studentMajor, attempts) {
                cn.edu.haut.campus.core.model.CampusMajorTrack.inferFromMajorText(studentMajor)
                    ?: cn.edu.haut.campus.core.model.CampusMajorTrack.inferFromMajorText(
                        attempts.take(12).joinToString(" ") { it.name }
                    )
            }
            val topRecommended = remember(competitions, inferredTrack) {
                competitions
                    .filter { it.matchesMajorTrack(inferredTrack) }
                    .sortedWith(
                        compareByDescending<cn.edu.haut.campus.core.model.CampusCompetitionItem> {
                            it.isCoreForMajorTrack(inferredTrack)
                        }.thenByDescending { it.pinned }.thenBy { it.sortOrder }
                    )
                    .take(3)
            }

            XPeachCard(modifier = Modifier.fillMaxWidth()) {
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
                            text = "推荐赛事",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "可申请创新学分与综测加分",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    XPeachStatusDotText(
                        text = inferredTrack?.shortLabel ?: "通用",
                        dotColor = XPeachTheme.readablePrimary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                topRecommended.forEachIndexed { idx, comp ->
                    if (idx > 0) {
                        XPeachInsetDivider(verticalPadding = 4.dp)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
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
                                text = comp.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${comp.tierBadge} · ${comp.registrationWindow}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        XPeachFilterPill(
                            label = "查看",
                            selected = true,
                            compact = true,
                            onClick = navigateToCompetitions
                        )
                    }
                }
            }
        }
    }
}
