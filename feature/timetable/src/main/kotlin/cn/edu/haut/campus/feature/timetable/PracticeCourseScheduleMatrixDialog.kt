package cn.edu.haut.campus.feature.timetable

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.database.UnscheduledAcademicCourseEntity
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.PeriodTimeEditor
import cn.edu.haut.campus.core.domain.WeeksSetParser
import cn.edu.haut.campus.core.model.OccurrenceRule
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime

private data class PracticeRuleGroupDraft(
    val id: String,
    val weekdays: Set<Int> = emptySet(),
    val startPeriod: String = "1",
    val endPeriod: String = "2",
    val weeks: String = "1-16周",
    val room: String = "实践场地",
    val customStart: String = "",
    val customEnd: String = ""
)

/**
 * W0-04 骨架 UI：实践课「周次 × 星期」多日多选矩阵。
 * 每个时段组可跨多个星期复用同一周次/节次/地点；多个组可表达不同地点或不同时间段。
 */
@Composable
fun PracticeCourseScheduleMatrixDialog(
    unscheduled: UnscheduledAcademicCourseEntity,
    onDismiss: () -> Unit,
    onSave: suspend (List<OccurrenceRule>) -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var groups by remember(unscheduled.termId, unscheduled.sourceKey) {
        mutableStateOf(
            listOf(
                PracticeRuleGroupDraft(
                    id = "group_1",
                    weeks = unscheduled.rawWeeks.takeIf { it.isNotBlank() && WeeksSetParser.parse(it).isNotEmpty() } ?: "1-16周"
                )
            )
        )
    }
    var error by remember(unscheduled.termId, unscheduled.sourceKey) { mutableStateOf<String?>(null) }
    var saving by remember(unscheduled.termId, unscheduled.sourceKey) { mutableStateOf(false) }

    fun updateGroup(id: String, transform: (PracticeRuleGroupDraft) -> PracticeRuleGroupDraft) {
        groups = groups.map { if (it.id == id) transform(it) else it }
    }

    fun parseGroups(): List<OccurrenceRule> {
        val occurrences = mutableListOf<OccurrenceRule>()
        for (group in groups) {
            val start = group.startPeriod.trim().toIntOrNull() ?: -1
            val end = group.endPeriod.trim().toIntOrNull() ?: -1
            val weeks = WeeksSetParser.parse(group.weeks)
            val customStart = group.customStart.trim().takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { LocalTime.parse(raw) }.getOrNull() ?: throw IllegalArgumentException("自定义开始时间必须是 HH:mm")
            }
            val customEnd = group.customEnd.trim().takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { LocalTime.parse(raw) }.getOrNull() ?: throw IllegalArgumentException("自定义结束时间必须是 HH:mm")
            }
            for (weekday in group.weekdays.sorted()) {
                occurrences += OccurrenceRule(
                    weekday = DayOfWeek.of(weekday),
                    startPeriod = start,
                    endPeriod = end,
                    weeks = weeks,
                    rawRoom = group.room.trim(),
                    customStartTime = customStart,
                    customEndTime = customEnd
                )
            }
        }
        return PeriodTimeEditor.requireValidOccurrenceRules(occurrences)
    }

    XPeachAlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("设置实践课多日排课") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = unscheduled.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = XPeachTheme.readablePrimary
                )
                Text(
                    text = listOfNotNull(
                        unscheduled.teacher?.takeIf { it.isNotBlank() }?.let { "教师：" + it },
                        unscheduled.creditPlain?.takeIf { it.isNotBlank() }?.let { it + "学分" },
                        unscheduled.rawWeeks.takeIf { it.isNotBlank() }?.let { "教务周次：" + it }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "可为同一门实践课选择多个星期；增加时段组后可设置不同节次、地点或精确起止时间。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                for (group in groups) {
                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "时段组 " + (groups.indexOf(group) + 1),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (groups.size > 1) {
                                    TextButton(onClick = { groups = groups.filterNot { it.id == group.id } }) {
                                        Text("删除")
                                    }
                                }
                            }
                            Text(
                                text = "星期（可多选）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val dayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
                                for (day in 1..7) {
                                    XPeachFilterPill(
                                        label = dayLabels[day - 1],
                                        selected = day in group.weekdays,
                                        onClick = {
                                            updateGroup(group.id) { draft ->
                                                draft.copy(
                                                    weekdays = if (day in draft.weekdays) draft.weekdays - day else draft.weekdays + day
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                            Text(
                                text = "节次",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for ((sp, ep) in listOf("1" to "2", "3" to "4", "5" to "6", "7" to "8", "9" to "10", "11" to "12", "1" to "4", "5" to "8", "1" to "8")) {
                                    XPeachFilterPill(
                                        label = sp + "-" + ep + "节",
                                        selected = group.startPeriod == sp && group.endPeriod == ep,
                                        onClick = {
                                            updateGroup(group.id) { it.copy(startPeriod = sp, endPeriod = ep) }
                                        }
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = group.startPeriod,
                                    onValueChange = { raw -> updateGroup(group.id) { it.copy(startPeriod = raw.filter(Char::isDigit).take(2)) } },
                                    label = { Text("起始节") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = group.endPeriod,
                                    onValueChange = { raw -> updateGroup(group.id) { it.copy(endPeriod = raw.filter(Char::isDigit).take(2)) } },
                                    label = { Text("结束节") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for ((label, expr) in listOf(
                                    "1-16周" to "1-16周",
                                    "单周" to "1,3,5,7,9,11,13,15周",
                                    "双周" to "2,4,6,8,10,12,14,16周",
                                    "前8周" to "1-8周",
                                    "后8周" to "9-16周"
                                )) {
                                    XPeachFilterPill(
                                        label = label,
                                        selected = group.weeks == expr,
                                        onClick = { updateGroup(group.id) { it.copy(weeks = expr) } }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = group.weeks,
                                onValueChange = { raw -> updateGroup(group.id) { it.copy(weeks = raw) } },
                                label = { Text("周次范围（如 14-16周 或 10周）") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = group.room,
                                onValueChange = { raw -> updateGroup(group.id) { it.copy(room = raw) } },
                                label = { Text("上课教室 / 实践场地") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = group.customStart,
                                    onValueChange = { raw -> updateGroup(group.id) { it.copy(customStart = raw) } },
                                    label = { Text("精确开始（可空）") },
                                    placeholder = { Text("HH:mm") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = group.customEnd,
                                    onValueChange = { raw -> updateGroup(group.id) { it.copy(customEnd = raw) } },
                                    label = { Text("精确结束（可空）") },
                                    placeholder = { Text("HH:mm") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                TextButton(
                    onClick = {
                        val next = groups.size + 1
                        groups = groups + PracticeRuleGroupDraft(
                            id = "group_" + next + "_" + System.nanoTime(),
                            weeks = groups.lastOrNull()?.weeks ?: "1-16周",
                            room = groups.lastOrNull()?.room ?: "实践场地"
                        )
                    }
                ) {
                    Text("+ 增加时段组")
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    error = null
                    val parsed = runCatching { parseGroups() }.getOrElse {
                        error = it.message ?: "排课输入无效"
                        return@Button
                    }
                    saving = true
                    scope.launch {
                        val result = runCatching { onSave(parsed) }
                        saving = false
                        if (result.isSuccess) {
                            onSaved()
                            onDismiss()
                        } else {
                            error = result.exceptionOrNull()?.message ?: "保存实践课排课失败"
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (saving) "保存中…" else "排入课表")
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!saving) onDismiss() }) {
                Text("取消")
            }
        }
    )
}
