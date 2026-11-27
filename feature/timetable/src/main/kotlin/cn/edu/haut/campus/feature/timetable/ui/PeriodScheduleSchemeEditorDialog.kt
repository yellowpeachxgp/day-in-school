package cn.edu.haut.campus.feature.timetable.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.PeriodTimeScheme
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.feature.timetable.state.TimetableUiAction
import cn.edu.haut.campus.feature.timetable.state.TimetableWriteCompletion
import cn.edu.haut.campus.feature.timetable.state.TimetableWriteKind
import java.time.LocalDate
import java.time.LocalTime

private data class PeriodDraftRow(
    val key: String,
    val sourceId: String,
    val periodNo: String,
    val start: String,
    val end: String
)

/**
 * W0-04 骨架 UI：默认作息整组编辑器 + 按日期区间生效的作息方案编辑器。
 * 精确校验在 core/domain 与 TermRepository 再执行；这里负责清晰呈现错误且不产生半写入。
 */
@Composable
fun PeriodScheduleSchemeEditorDialog(
    term: Term,
    periodTimes: List<PeriodTime>,
    schemes: List<PeriodTimeScheme>,
    writeCompletions: Map<String, TimetableWriteCompletion>,
    onDismiss: () -> Unit,
    onAction: (TimetableUiAction) -> Unit
) {
    val writeLifecycle = rememberTimetableWriteLifecycle(
        key = term.id,
        writeCompletions = writeCompletions,
        onWriteSuccess = onDismiss,
        onConsumeCompletion = { token -> onAction(TimetableUiAction.ConsumeWriteCompletion(token)) }
    )
    val writePending = writeLifecycle.pending
    val writeError = writeLifecycle.errorMessage
    val termSchemes = schemes.filter { it.termId == term.id }
    var target by remember(term.id) { mutableStateOf("base") }
    var error by remember(target) { mutableStateOf<String?>(null) }

    fun rowsForTarget(targetId: String): List<PeriodDraftRow> {
        val baseRows = periodTimes
            .filter { it.timeTableId == term.timeTableId && it.schemeId == null }
            .sortedBy { it.periodNo }
        val sourceRows = when {
            targetId == "base" -> baseRows
            targetId == "new" -> baseRows
            else -> periodTimes.filter { it.schemeId == targetId }.sortedBy { it.periodNo }.ifEmpty { baseRows }
        }
        return sourceRows.map {
            PeriodDraftRow(
                key = it.id.ifBlank { "row_" + it.periodNo },
                sourceId = it.id,
                periodNo = it.periodNo.toString(),
                start = it.startLocalTime.toString().take(5),
                end = it.endLocalTime.toString().take(5)
            )
        }
    }

    val selectedScheme = termSchemes.firstOrNull { it.id == target }
    val termEndDate = term.startDate.plusWeeks((term.weekCount - 1).coerceAtLeast(0).toLong()).plusDays(6)
    var schemeName by remember(target) { mutableStateOf(selectedScheme?.name ?: "新作息方案") }
    var schemeStart by remember(target) { mutableStateOf((selectedScheme?.startDate ?: term.startDate).toString()) }
    var schemeEnd by remember(target) { mutableStateOf((selectedScheme?.endDate ?: termEndDate).toString()) }
    var rows by remember(target) { mutableStateOf(rowsForTarget(target)) }

    fun updateRow(key: String, transform: (PeriodDraftRow) -> PeriodDraftRow) {
        rows = rows.map { if (it.key == key) transform(it) else it }
    }

    fun parseTime(raw: String, label: String): LocalTime =
        runCatching { LocalTime.parse(raw.trim()) }.getOrElse {
            throw IllegalArgumentException(label + "必须是 HH:mm")
        }

    fun parseRows(): List<PeriodTime> {
        if (rows.isEmpty()) throw IllegalArgumentException("作息方案至少需要一节")
        return rows.map { row ->
            val no = row.periodNo.trim().toIntOrNull()
                ?: throw IllegalArgumentException("节次号必须是数字")
            PeriodTime(
                id = row.sourceId,
                timeTableId = term.timeTableId,
                periodNo = no,
                startLocalTime = parseTime(row.start, "第 " + no + " 节开始时间"),
                endLocalTime = parseTime(row.end, "第 " + no + " 节结束时间")
            )
        }
    }

    XPeachAlertDialog(
        onDismissRequest = { if (!writePending) onDismiss() },
        title = { Text("节次与作息方案") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = term.displayName + " · 默认作息长期生效，日期方案在区间内自动替换整套节次时间。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    XPeachFilterPill(
                        label = "默认作息",
                        selected = target == "base",
                        onClick = { target = "base" }
                    )
                    for (scheme in termSchemes) {
                        XPeachFilterPill(
                            label = scheme.name,
                            selected = target == scheme.id,
                            onClick = { target = scheme.id }
                        )
                    }
                    XPeachFilterPill(
                        label = "+新方案",
                        selected = target == "new",
                        onClick = { target = "new" }
                    )
                }
                if (target != "base") {
                    OutlinedTextField(
                        value = schemeName,
                        onValueChange = { schemeName = it },
                        label = { Text("方案名称") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = schemeStart,
                            onValueChange = { schemeStart = it },
                            label = { Text("开始日期") },
                            placeholder = { Text("YYYY-MM-DD") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = schemeEnd,
                            onValueChange = { schemeEnd = it },
                            label = { Text("结束日期") },
                            placeholder = { Text("YYYY-MM-DD") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (selectedScheme != null) {
                        TextButton(
                            enabled = !writePending,
                            onClick = {
                                error = null
                                val writeToken = writeLifecycle.begin(TimetableWriteKind.DeletePeriodTimeScheme)
                                onAction(
                                    TimetableUiAction.DeletePeriodTimeScheme(
                                        schemeId = selectedScheme.id,
                                        writeToken = writeToken
                                    )
                                )
                            }
                        ) {
                            Text("删除该日期方案")
                        }
                    }
                }

                Text(
                    text = "节次时间（按节次号单调递增且不重叠）",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                for (row in rows) {
                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "第 " + row.periodNo + " 节",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                TextButton(
                                    enabled = rows.size > 1 && !writePending,
                                    onClick = { rows = rows.filterNot { it.key == row.key } }
                                ) {
                                    Text("删除")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = row.periodNo,
                                    onValueChange = { raw -> updateRow(row.key) { it.copy(periodNo = raw.filter(Char::isDigit).take(2)) } },
                                    label = { Text("节次") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(0.75f)
                                )
                                OutlinedTextField(
                                    value = row.start,
                                    onValueChange = { raw -> updateRow(row.key) { it.copy(start = raw) } },
                                    label = { Text("开始") },
                                    placeholder = { Text("HH:mm") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = row.end,
                                    onValueChange = { raw -> updateRow(row.key) { it.copy(end = raw) } },
                                    label = { Text("结束") },
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
                    enabled = !writePending,
                    onClick = {
                        val maxNo = rows.mapNotNull { it.periodNo.toIntOrNull() }.maxOrNull() ?: 0
                        if (maxNo >= 30) {
                            error = "最多支持 30 节"
                        } else {
                            val next = maxNo + 1
                            val lastEnd = rows.lastOrNull()?.end?.let { raw -> runCatching { LocalTime.parse(raw) }.getOrNull() }
                            val start = (lastEnd ?: LocalTime.of(8, 0)).plusMinutes(10)
                            val end = start.plusMinutes(45)
                            rows = rows + PeriodDraftRow(
                                key = "new_" + next + "_" + System.nanoTime(),
                                sourceId = "",
                                periodNo = next.toString(),
                                start = start.toString().take(5),
                                end = end.toString().take(5)
                            )
                        }
                    }
                ) {
                    Text("+ 添加节次")
                }
                val shownError = error ?: writeError
                shownError?.let {
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
                enabled = !writePending,
                onClick = {
                    error = null
                    val parsedPeriods = runCatching { parseRows() }.getOrElse {
                        error = it.message ?: "节次输入无效"
                        return@Button
                    }
                    if (target == "base") {
                        val writeToken = writeLifecycle.begin(TimetableWriteKind.SaveBasePeriodTimes)
                        onAction(
                            TimetableUiAction.SaveBasePeriodTimes(
                                timeTableId = term.timeTableId,
                                periods = parsedPeriods,
                                writeToken = writeToken
                            )
                        )
                    } else {
                        val start = runCatching { LocalDate.parse(schemeStart.trim()) }.getOrElse {
                            error = "开始日期必须是 YYYY-MM-DD"
                            return@Button
                        }
                        val end = runCatching { LocalDate.parse(schemeEnd.trim()) }.getOrElse {
                            error = "结束日期必须是 YYYY-MM-DD"
                            return@Button
                        }
                        val writeToken = writeLifecycle.begin(TimetableWriteKind.SavePeriodTimeScheme)
                        onAction(
                            TimetableUiAction.SavePeriodTimeScheme(
                                term = term,
                                schemeId = if (target == "new") null else target,
                                name = schemeName,
                                startDate = start,
                                endDate = end,
                                periods = parsedPeriods,
                                writeToken = writeToken
                            )
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (writePending) "保存中…" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!writePending) onDismiss() }) {
                Text("取消")
            }
        }
    )
}
