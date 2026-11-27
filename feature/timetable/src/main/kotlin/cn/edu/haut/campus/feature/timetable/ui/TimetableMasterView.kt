package cn.edu.haut.campus.feature.timetable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.WeeksSetParser
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import java.time.DayOfWeek

@Composable
internal fun RawMasterTimetableView(
    termDisplayName: String,
    termId: String?,
    courses: List<Course>,
    rules: List<CourseScheduleRule>,
    unscheduledCourses: List<cn.edu.haut.campus.core.database.UnscheduledAcademicCourseEntity> = emptyList(),
    canceledOverridesCount: Int = 0,
    onAddManualCourse: () -> Unit = {},
    onEditRule: (CourseScheduleRule) -> Unit = {},
    onScheduleUnscheduledCourse: (cn.edu.haut.campus.core.database.UnscheduledAcademicCourseEntity) -> Unit = {},
    onOpenCanceledCourses: () -> Unit = {},
    onNavigateToImporter: () -> Unit,
    onNavigateToMapWithRoom: (String) -> Unit
) {
    val termRules = remember(rules, termId) {
        if (termId != null) rules.filter { it.termId == termId } else rules
    }
    val courseMap = remember(courses) { courses.associateBy { it.id } }
    val sortedRules = remember(termRules, courseMap) {
        termRules.sortedWith(
            compareBy<CourseScheduleRule> { it.weekday.value }
                .thenBy { it.startPeriod }
                .thenBy { it.weeks.minOrNull() ?: 1 }
        )
    }
    val distinctCourseCount = remember(termRules) {
        termRules.map { it.courseId }.distinct().size
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 原始全学期总表概览与课表互通入口
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
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = termDisplayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "共 $distinctCourseCount 门课 · ${sortedRules.size} 个时段",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = onNavigateToImporter,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("导入/导出", maxLines = 1, softWrap = false)
                }
            }
        }

        // 2. 教务原始 7×5 大节全周次总览表（支持横向宽列滑动，多周次课程独立成卡绝不糊字）
        XPeachCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "全学期课表",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "左右滑动 ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            val bigSlots = remember(sortedRules) {
                buildList {
                    add(1 to 2)
                    add(3 to 4)
                    add(5 to 6)
                    add(7 to 8)
                    add(9 to 10)
                    if (sortedRules.any { it.endPeriod > 10 }) {
                        add(11 to 12)
                    }
                }
            }
            val hasWeekendRules = remember(sortedRules) {
                sortedRules.any { it.weekday.value >= 6 }
            }
            val days = remember(hasWeekendRules) {
                if (hasWeekendRules) DayOfWeek.entries else DayOfWeek.entries.take(5)
            }
            val colWidth = if (days.size == 5) 86.dp else 70.dp

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.width(32.dp))
                    for (d in days) {
                        Box(
                            modifier = Modifier
                                .width(colWidth)
                                .padding(horizontal = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = d.ChineseLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }

                for ((sp, ep) in bigSlots) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .width(32.dp)
                                .padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "$sp-$ep",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = XPeachTheme.readablePrimary
                            )
                            Text(
                                text = "节",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        for (d in days) {
                            val slotRules = sortedRules.filter { r ->
                                r.weekday == d && r.startPeriod <= ep && r.endPeriod >= sp
                            }
                            Column(
                                modifier = Modifier
                                    .width(colWidth)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (slotRules.isNotEmpty()) {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                                        }
                                    )
                                    .padding(3.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (slotRules.isEmpty()) {
                                    Spacer(modifier = Modifier.height(36.dp))
                                } else {
                                    for (r in slotRules) {
                                        val c = courseMap[r.courseId]
                                        val rawArgb = c?.colorArgb ?: 0xFF0072BC
                                        val accentColor = XPeachTheme.courseAccent(rawArgb)
                                        val compactRoom = formatCompactGridRoom(r.rawRoom)
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(XPeachTheme.courseContainer(rawArgb, active = false))
                                                .border(
                                                    0.7.dp,
                                                    XPeachTheme.courseBorder(rawArgb, active = false),
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .clickable { onEditRule(r) }
                                                .padding(horizontal = 4.dp, vertical = 3.5.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = c?.name ?: "课程",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    lineHeight = 13.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = if (days.size == 5) 3 else 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (compactRoom.isNotBlank()) {
                                                Text(
                                                    text = compactRoom,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        lineHeight = 12.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    ),
                                                    color = accentColor,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                text = WeeksSetParser.format(r.weeks),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    lineHeight = 11.5.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
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

        // 3. 原始排课完整明细清单（含周次、节次、教师、教室、来源、编辑与添加）
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "课程管理与排课明细 (${sortedRules.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (canceledOverridesCount > 0) {
                        XPeachFilterPill(
                            label = "已停课($canceledOverridesCount)",
                            selected = false,
                            compact = true,
                            onClick = onOpenCanceledCourses
                        )
                    }
                    XPeachFilterPill(
                        label = "+ 添加课程",
                        selected = true,
                        compact = true,
                        onClick = onAddManualCourse
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (sortedRules.isEmpty()) {
                Text(
                    text = "暂无课程，可从教务系统同步或点击右上角手动添加",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column {
                    sortedRules.forEachIndexed { idx, rule ->
                        val course = courseMap[rule.courseId]
                        val rawArgb = course?.colorArgb ?: 0xFF0072BC
                        val accentColor = XPeachTheme.courseAccent(rawArgb)
                        val weeksText = WeeksSetParser.format(rule.weeks)
                        val sourceLabel = when {
                            course?.source == CourseSource.MANUAL -> "手动添加"
                            course?.userModified == true || rule.userModified -> "已手动调整"
                            else -> "教务同步"
                        }

                        XPeachInsetDivider(verticalPadding = if (idx == 0) 4.dp else 8.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onEditRule(rule) }
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = course?.name ?: "未知课程",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    XPeachStatusDotText(
                                        text = sourceLabel,
                                        dotColor = accentColor
                                    )
                                }
                                Text(
                                    text = "${rule.weekday.ChineseLabel} 第${rule.startPeriod}-${rule.endPeriod}节 · $weeksText",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = XPeachTheme.readablePrimary
                                )
                                Text(
                                    text = "教室：${rule.rawRoom}${course?.teacher?.let { " · 教师：$it" } ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                XPeachFilterPill(
                                    label = "编辑",
                                    selected = false,
                                    compact = true,
                                    onClick = { onEditRule(rule) }
                                )
                                if (rule.rawRoom.isNotBlank() && !rule.rawRoom.contains("待定")) {
                                    XPeachFilterPill(
                                        label = "定位",
                                        selected = false,
                                        compact = true,
                                        onClick = { onNavigateToMapWithRoom(rule.rawRoom) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (unscheduledCourses.isNotEmpty()) {
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "实践课程 (${unscheduledCourses.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "教务未指定固定节次，点击「设置时间」可排入周课表",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Column {
                    unscheduledCourses.forEachIndexed { idx, item ->
                        XPeachInsetDivider(verticalPadding = if (idx == 0) 4.dp else 6.dp)
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    XPeachStatusDotText(
                                        text = item.category.takeIf { it.isNotBlank() } ?: "实践环节"
                                    )
                                }
                                Text(
                                    text = listOfNotNull(
                                        item.teacher?.takeIf { it.isNotBlank() }?.let { "教师：$it" },
                                        item.rawWeeks.takeIf { it.isNotBlank() }?.let { "周次：$it" },
                                        item.creditPlain?.takeIf { it.isNotBlank() }?.let { "${it}学分" }
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            XPeachFilterPill(
                                label = "设置时间",
                                selected = true,
                                compact = true,
                                onClick = { onScheduleUnscheduledCourse(item) }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(92.dp))
    }
}
