package cn.edu.haut.campus.feature.timetable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCoursePalette
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.WeeksSetParser
import cn.edu.haut.campus.core.model.AcademicTermResolver
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import java.time.LocalDate
import cn.edu.haut.campus.feature.timetable.state.TimetableUiAction
import cn.edu.haut.campus.feature.timetable.state.TimetableWriteCompletion
import cn.edu.haut.campus.feature.timetable.state.TimetableWriteKind
import cn.edu.haut.campus.feature.timetable.state.UnscheduledPracticalCourseUiModel

@Composable
internal fun TimetableEditingDialogs(
    editingOccurrence: ClassOccurrence?,
    onEditingOccurrenceDismiss: () -> Unit,
    editingMasterRule: CourseScheduleRule?,
    onEditingMasterRuleDismiss: () -> Unit,
    confirmDeleteMasterRule: CourseScheduleRule?,
    onConfirmDeleteMasterRuleChange: (CourseScheduleRule?) -> Unit,
    schedulingPracticalCourse: UnscheduledPracticalCourseUiModel?,
    onSchedulingPracticalCourseDismiss: () -> Unit,
    showAddCourseDialog: Boolean,
    onAddCourseDialogDismiss: () -> Unit,
    showTermSwitcherDialog: Boolean,
    onTermSwitcherDialogDismiss: () -> Unit,
    term: cn.edu.haut.campus.core.model.Term?,
    terms: List<cn.edu.haut.campus.core.model.Term>,
    courses: List<Course>,
    rules: List<CourseScheduleRule>,
    today: LocalDate,
    writeCompletions: Map<String, TimetableWriteCompletion>,
    onAction: (TimetableUiAction) -> Unit,
    onNavigateToImporter: () -> Unit,
    onNavigateToMapWithRoom: (String) -> Unit
) {
    val occToEdit = editingOccurrence
    if (occToEdit != null) {
        val targetCourse = courses.firstOrNull { it.id == occToEdit.courseId }
        val targetRule = rules.firstOrNull { it.id == occToEdit.ruleId }

        var editScopeMode by remember(occToEdit) {
            mutableIntStateOf(if (targetRule == null) 1 else 0)
        } // 0: 全学期规则, 1: 仅本次(单次改教室/节次/跨天调课)
        var editedName by remember(occToEdit) { mutableStateOf(occToEdit.courseName) }
        var editedRoom by remember(occToEdit) { mutableStateOf(occToEdit.rawRoom) }
        var editedTeacher by remember(occToEdit) { mutableStateOf(occToEdit.teacher.orEmpty()) }
        var editedWeekday by remember(occToEdit, targetRule) {
            mutableIntStateOf(targetRule?.weekday?.value ?: occToEdit.weekday.value)
        }
        var editedWeeksText by remember(targetRule) {
            mutableStateOf(targetRule?.let { WeeksSetParser.format(it.weeks) } ?: "1-16周")
        }
        var editedStartPeriod by remember(occToEdit) { mutableStateOf(occToEdit.startPeriod.toString()) }
        var editedEndPeriod by remember(occToEdit) { mutableStateOf(occToEdit.endPeriod.toString()) }
        var targetDateText by remember(occToEdit) { mutableStateOf(occToEdit.date.toString()) }
        var singleDateError by remember(occToEdit) { mutableStateOf<String?>(null) }
        var selectedColor by remember(occToEdit) { mutableLongStateOf(occToEdit.colorArgb) }
        val writeLifecycle = rememberTimetableWriteLifecycle(
            key = occToEdit,
            writeCompletions = writeCompletions,
            onConsumeCompletion = { token ->
                onAction(TimetableUiAction.ConsumeWriteCompletion(token))
            },
            onWriteSuccess = onEditingOccurrenceDismiss
        )
        val writePending = writeLifecycle.pending

        val palette = XPeachCoursePalette.SwatchesArgb

        XPeachAlertDialog(
            onDismissRequest = { if (!writePending) onEditingOccurrenceDismiss() },
            title = { Text("课程调整") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${occToEdit.date} (${occToEdit.weekday.ChineseLabel}) · ${occToEdit.formatTimeOrPeriodRange(PERIOD_TIME_FMT)} (${occToEdit.periodRangeText})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = XPeachTheme.readablePrimary
                    )
                    writeLifecycle.errorMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (targetRule != null) {
                        XPeachSegmentedTabs(
                            items = listOf("修改全学期规则", "仅改本次 (${occToEdit.date.format(SHORT_DATE_FMT)})"),
                            selectedIndex = editScopeMode,
                            onSelect = { editScopeMode = it }
                        )
                    }
                    if (editScopeMode == 0 && targetRule != null) {
                        OutlinedTextField(
                            value = editedName,
                            onValueChange = { editedName = it },
                            label = { Text("课程名称") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editedRoom,
                            onValueChange = { editedRoom = it },
                            label = { Text("教室") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editedTeacher,
                            onValueChange = { editedTeacher = it },
                            label = { Text("教师") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "星期与节次",
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
                            for (d in 1..7) {
                                XPeachFilterPill(
                                    label = dayLabels[d - 1],
                                    selected = editedWeekday == d,
                                    onClick = { editedWeekday = d }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for ((sp, ep) in listOf("1" to "2", "3" to "4", "5" to "6", "7" to "8", "9" to "10", "11" to "12")) {
                                XPeachFilterPill(
                                    label = "${sp}-${ep}节",
                                    selected = editedStartPeriod == sp && editedEndPeriod == ep,
                                    onClick = {
                                        editedStartPeriod = sp
                                        editedEndPeriod = ep
                                    }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = editedStartPeriod,
                                onValueChange = { editedStartPeriod = it.filter(Char::isDigit).take(2) },
                                label = { Text("起始节(1-12)") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = editedEndPeriod,
                                onValueChange = { editedEndPeriod = it.filter(Char::isDigit).take(2) },
                                label = { Text("结束节(1-12)") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = editedWeeksText,
                            onValueChange = { editedWeeksText = it },
                            label = { Text("周次") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            for (colorHex in palette) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(colorHex))
                                        .border(
                                            width = if (selectedColor == colorHex) 2.5.dp else 0.dp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            shape = CircleShape
                                        )
                                        .clickable { selectedColor = colorHex }
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "仅对本次课生效（可改教室、节次或跨日期调课），不影响其他教学周排课",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = targetDateText,
                            onValueChange = {
                                targetDateText = it
                                singleDateError = null
                            },
                            label = { Text("上课日期 (YYYY-MM-DD)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (singleDateError != null) {
                            Text(
                                text = singleDateError!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        OutlinedTextField(
                            value = editedRoom,
                            onValueChange = { editedRoom = it },
                            label = { Text("当日临时教室") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for ((sp, ep) in listOf("1" to "2", "3" to "4", "5" to "6", "7" to "8", "9" to "10", "11" to "12")) {
                                XPeachFilterPill(
                                    label = "${sp}-${ep}节",
                                    selected = editedStartPeriod == sp && editedEndPeriod == ep,
                                    onClick = {
                                        editedStartPeriod = sp
                                        editedEndPeriod = ep
                                    }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = editedStartPeriod,
                                onValueChange = { editedStartPeriod = it.filter(Char::isDigit).take(2) },
                                label = { Text("起始节(1-12)") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = editedEndPeriod,
                                onValueChange = { editedEndPeriod = it.filter(Char::isDigit).take(2) },
                                label = { Text("结束节(1-12)") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (!occToEdit.overrideId.isNullOrBlank() && occToEdit.ruleId != null) {
                            TextButton(
                                onClick = {
                                    val writeToken = writeLifecycle.begin(TimetableWriteKind.ResetSingleDayOccurrenceEdit)
                                    onAction(TimetableUiAction.ResetSingleDayOccurrenceEdit(occToEdit, writeToken = writeToken))
                                },
                                enabled = !writePending,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "恢复原排课规则",
                                    color = XPeachTheme.readablePrimary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                onEditingOccurrenceDismiss()
                                onNavigateToMapWithRoom(editedRoom)
                            },
                            enabled = !writePending,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("查看位置", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = {
                                val activeTermId = term?.id ?: AcademicTermResolver.defaultTermId()
                                val writeToken = writeLifecycle.begin(TimetableWriteKind.CancelClassOccurrence)
                                onAction(TimetableUiAction.CancelClassOccurrence(occToEdit, activeTermId, writeToken = writeToken))
                            },
                            enabled = !writePending,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("本节停课", maxLines = 1, softWrap = false)
                        }
                    }
                    if (targetRule != null && editScopeMode == 0) {
                        TextButton(
                            onClick = {
                                val writeToken = writeLifecycle.begin(TimetableWriteKind.DeleteCourseScheduleRules)
                                onAction(
                                    TimetableUiAction.DeleteCourseScheduleRules(
                                        ruleIds = occToEdit.mergedRuleIds.ifEmpty { listOf(targetRule.id) },
                                        contextDate = occToEdit.date,
                                        writeToken = writeToken
                                    )
                                )
                            },
                            enabled = !writePending,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "删除该排课时段",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // 节次校验：非法输入明确提示，不静默夹取
                        val spRaw = editedStartPeriod.toIntOrNull()
                        val epRaw = editedEndPeriod.toIntOrNull()
                        if (spRaw == null || spRaw !in 1..12) {
                            singleDateError = "开始节次必须是 1-12 的数字"
                            return@Button
                        }
                        if (epRaw == null || epRaw !in 1..12 || epRaw < spRaw) {
                            singleDateError = "结束节次必须是 1-12 且不小于开始节次"
                            return@Button
                        }
                        val sp = spRaw
                        val ep = epRaw
                        if (editScopeMode != 0 || targetRule == null) {
                            val parsedTargetDate = runCatching { LocalDate.parse(targetDateText.trim()) }.getOrNull()
                            if (parsedTargetDate == null) {
                                singleDateError = "请输入有效日期 (YYYY-MM-DD)"
                                return@Button
                            }
                            val activeTermId = term?.id ?: AcademicTermResolver.defaultTermId()
                            val cleanRoom = editedRoom.trim().ifEmpty { occToEdit.rawRoom }
                            val writeToken = writeLifecycle.begin(TimetableWriteKind.SaveSingleDayOccurrenceEdit)
                            onAction(
                                TimetableUiAction.SaveSingleDayOccurrenceEdit(
                                    occurrence = occToEdit,
                                    termId = activeTermId,
                                    targetDate = parsedTargetDate,
                                    newStartPeriod = sp,
                                    newEndPeriod = ep,
                                    newRoom = cleanRoom,
                                    writeToken = writeToken
                                )
                            )
                        } else {
                            val parsedWeeks = WeeksSetParser.parse(editedWeeksText)
                            if (parsedWeeks.isEmpty()) {
                                singleDateError = "请填写有效周次范围（如 1-16周 或 3,5,7周）"
                                return@Button
                            }
                            if (parsedWeeks.any { it !in 1..30 }) {
                                singleDateError = "周次必须在 1-30 之间"
                                return@Button
                            }
                            if (targetCourse != null) {
                                val writeToken = writeLifecycle.begin(TimetableWriteKind.SaveMergedCourseRuleEdit)
                                onAction(
                                    TimetableUiAction.SaveMergedCourseRuleEdit(
                                        course = targetCourse,
                                        primaryRule = targetRule,
                                        mergedRuleIds = occToEdit.mergedRuleIds.ifEmpty { listOf(targetRule.id) },
                                        contextDate = occToEdit.date,
                                        name = editedName,
                                        teacher = editedTeacher,
                                        room = editedRoom,
                                        weekday = editedWeekday,
                                        startPeriod = sp,
                                        endPeriod = ep,
                                        weeks = parsedWeeks,
                                        colorArgb = selectedColor,
                                        writeToken = writeToken
                                    )
                                )
                            } else {
                                onEditingOccurrenceDismiss()
                            }
                        }
                    },
                    enabled = !writePending,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (writePending) "写入中…" else "保存")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onEditingOccurrenceDismiss() },
                    enabled = !writePending
                ) {
                    Text("取消")
                }
            }
        )
    }

    val masterRuleToEdit = editingMasterRule
    if (masterRuleToEdit != null) {
        val targetCourse = courses.firstOrNull { it.id == masterRuleToEdit.courseId }
        var editedName by remember(masterRuleToEdit, targetCourse) { mutableStateOf(targetCourse?.name ?: "") }
        var editedRoom by remember(masterRuleToEdit) { mutableStateOf(masterRuleToEdit.rawRoom) }
        var editedTeacher by remember(masterRuleToEdit, targetCourse) { mutableStateOf(targetCourse?.teacher.orEmpty()) }
        var editedWeekday by remember(masterRuleToEdit) { mutableIntStateOf(masterRuleToEdit.weekday.value) }
        var editedStartPeriod by remember(masterRuleToEdit) { mutableStateOf(masterRuleToEdit.startPeriod.toString()) }
        var editedEndPeriod by remember(masterRuleToEdit) { mutableStateOf(masterRuleToEdit.endPeriod.toString()) }
        var editedWeeksText by remember(masterRuleToEdit) {
            mutableStateOf(WeeksSetParser.format(masterRuleToEdit.weeks))
        }
        var masterEditError by remember(masterRuleToEdit) { mutableStateOf<String?>(null) }
        var selectedColor by remember(targetCourse) { mutableLongStateOf(targetCourse?.colorArgb ?: 0xFF0072BC) }
        val writeLifecycle = rememberTimetableWriteLifecycle(
            key = masterRuleToEdit,
            writeCompletions = writeCompletions,
            onConsumeCompletion = { token ->
                onAction(TimetableUiAction.ConsumeWriteCompletion(token))
            },
            onWriteSuccess = onEditingMasterRuleDismiss
        )
        val writePending = writeLifecycle.pending
        val palette = XPeachCoursePalette.SwatchesArgb

        XPeachAlertDialog(
            onDismissRequest = { if (!writePending) onEditingMasterRuleDismiss() },
            title = { Text("编辑排课规则") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        label = { Text("课程名称") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedRoom,
                        onValueChange = { editedRoom = it },
                        label = { Text("上课教室") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedTeacher,
                        onValueChange = { editedTeacher = it },
                        label = { Text("教师") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "星期",
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
                        for (d in 1..7) {
                            XPeachFilterPill(
                                label = dayLabels[d - 1],
                                selected = editedWeekday == d,
                                onClick = { editedWeekday = d }
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
                        for ((sp, ep) in listOf("1" to "2", "3" to "4", "5" to "6", "7" to "8", "9" to "10", "11" to "12")) {
                            XPeachFilterPill(
                                label = "${sp}-${ep}节",
                                selected = editedStartPeriod == sp && editedEndPeriod == ep,
                                onClick = {
                                    editedStartPeriod = sp
                                    editedEndPeriod = ep
                                }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = editedStartPeriod,
                            onValueChange = { editedStartPeriod = it.filter(Char::isDigit).take(2) },
                            label = { Text("起始节(1-12)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = editedEndPeriod,
                            onValueChange = { editedEndPeriod = it.filter(Char::isDigit).take(2) },
                            label = { Text("结束节(1-12)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = editedWeeksText,
                        onValueChange = { editedWeeksText = it },
                        label = { Text("周次范围") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    (masterEditError ?: writeLifecycle.errorMessage)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        for (colorHex in palette) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorHex))
                                    .border(
                                        width = if (selectedColor == colorHex) 2.5.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = colorHex }
                            )
                        }
                    }
                    TextButton(
                        onClick = { onConfirmDeleteMasterRuleChange(masterRuleToEdit) },
                        enabled = !writePending,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "删除该排课时段",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // 输入校验：非法节次/空周次明确提示，不静默兜底
                        val spRaw = editedStartPeriod.toIntOrNull()
                        val epRaw = editedEndPeriod.toIntOrNull()
                        val parsedWeeks = WeeksSetParser.parse(editedWeeksText)
                        masterEditError = when {
                            spRaw == null || spRaw !in 1..12 -> "开始节次必须是 1-12 的数字"
                            epRaw == null || epRaw !in 1..12 -> "结束节次必须是 1-12 的数字"
                            epRaw < spRaw -> "结束节次不能小于开始节次"
                            parsedWeeks.isEmpty() -> "请填写有效周次范围（如 1-16周 或 3,5,7周）"
                            parsedWeeks.any { it !in 1..30 } -> "周次必须在 1-30 之间"
                            else -> null
                        }
                        if (masterEditError != null) return@Button
                        if (targetCourse != null) {
                            val writeToken = writeLifecycle.begin(TimetableWriteKind.SaveMasterCourseRuleEdit)
                            onAction(
                                TimetableUiAction.SaveMasterCourseRuleEdit(
                                    course = targetCourse,
                                    rule = masterRuleToEdit,
                                    name = editedName,
                                    teacher = editedTeacher,
                                    room = editedRoom,
                                    weekday = editedWeekday,
                                    startPeriod = checkNotNull(spRaw),
                                    endPeriod = checkNotNull(epRaw),
                                    weeks = parsedWeeks,
                                    colorArgb = selectedColor,
                                    writeToken = writeToken
                                )
                            )
                        } else {
                            onEditingMasterRuleDismiss()
                        }
                    },
                    enabled = !writePending,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (writePending) "写入中…" else "保存")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onEditingMasterRuleDismiss() },
                    enabled = !writePending
                ) {
                    Text("取消")
                }
            }
        )
    }

    val pendingDeleteMaster = confirmDeleteMasterRule
    if (pendingDeleteMaster != null) {
        val pendingCourse = courses.firstOrNull { it.id == pendingDeleteMaster.courseId }
        val isPractical = pendingCourse?.externalId?.startsWith("sjk:") == true
        val writeLifecycle = rememberTimetableWriteLifecycle(
            key = pendingDeleteMaster,
            writeCompletions = writeCompletions,
            onConsumeCompletion = { token ->
                onAction(TimetableUiAction.ConsumeWriteCompletion(token))
            },
            onWriteSuccess = {
                onConfirmDeleteMasterRuleChange(null)
                onEditingMasterRuleDismiss()
            }
        )
        val writePending = writeLifecycle.pending
        XPeachAlertDialog(
            onDismissRequest = { if (!writePending) onConfirmDeleteMasterRuleChange(null) },
            title = { Text("确认删除排课时段？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isPractical) {
                            "确认删除「${pendingCourse?.name ?: "实践课"}」的该自定义时段吗？删除全部时段后，课程将安全退回「时间待定实践课」列表，可随时重新设置时间。"
                        } else {
                            "确认删除「${pendingCourse?.name ?: "课程"}」（${pendingDeleteMaster.weekday.ChineseLabel} 第${pendingDeleteMaster.startPeriod}-${pendingDeleteMaster.endPeriod}节）的全学期排课时段吗？若仅需某一天停课，请在周课表点击对应卡片选择「单次停课」。"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    writeLifecycle.errorMessage?.let {
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
                    onClick = {
                        val writeToken = writeLifecycle.begin(TimetableWriteKind.DeleteCourseScheduleRule)
                        onAction(TimetableUiAction.DeleteCourseScheduleRule(pendingDeleteMaster.id, writeToken = writeToken))
                    },
                    enabled = !writePending,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (writePending) "删除中…" else "确认删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onConfirmDeleteMasterRuleChange(null) },
                    enabled = !writePending
                ) {
                    Text("取消")
                }
            }
        )
    }

    val pracToSchedule = schedulingPracticalCourse
    if (pracToSchedule != null) {
        PracticeCourseScheduleMatrixDialog(
            unscheduled = pracToSchedule,
            writeCompletions = writeCompletions,
            onDismiss = onSchedulingPracticalCourseDismiss,
            onAction = onAction
        )
    }

    if (showAddCourseDialog) {
        var newName by remember { mutableStateOf("") }
        var newRoom by remember { mutableStateOf("") }
        var newTeacher by remember { mutableStateOf("") }
        var newWeekday by remember { mutableIntStateOf(today.dayOfWeek.value) }
        var newStartPeriod by remember { mutableStateOf("1") }
        var newEndPeriod by remember { mutableStateOf("2") }
        var newWeeks by remember { mutableStateOf("1-16周") }
        var addError by remember { mutableStateOf<String?>(null) }
        val writeLifecycle = rememberTimetableWriteLifecycle(
            key = "add-course",
            writeCompletions = writeCompletions,
            onConsumeCompletion = { token ->
                onAction(TimetableUiAction.ConsumeWriteCompletion(token))
            },
            onWriteSuccess = onAddCourseDialogDismiss
        )
        val writePending = writeLifecycle.pending

        XPeachAlertDialog(
            onDismissRequest = { if (!writePending) onAddCourseDialogDismiss() },
            title = { Text("添加课程") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("课程名称") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newRoom,
                        onValueChange = { newRoom = it },
                        label = { Text("上课教室") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newTeacher,
                        onValueChange = { newTeacher = it },
                        label = { Text("教师（选填）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "星期",
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
                        for (d in 1..7) {
                            XPeachFilterPill(
                                label = dayLabels[d - 1],
                                selected = newWeekday == d,
                                onClick = { newWeekday = d }
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
                        for ((sp, ep) in listOf("1" to "2", "3" to "4", "5" to "6", "7" to "8", "9" to "10", "11" to "12")) {
                            XPeachFilterPill(
                                label = "${sp}-${ep}节",
                                selected = newStartPeriod == sp && newEndPeriod == ep,
                                onClick = {
                                    newStartPeriod = sp
                                    newEndPeriod = ep
                                }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newStartPeriod,
                            onValueChange = { newStartPeriod = it.filter(Char::isDigit).take(2) },
                            label = { Text("起始节(1-12)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = newEndPeriod,
                            onValueChange = { newEndPeriod = it.filter(Char::isDigit).take(2) },
                            label = { Text("结束节(1-12)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = "周次",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                                selected = newWeeks == expr,
                                onClick = { newWeeks = expr }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = newWeeks,
                        onValueChange = { newWeeks = it },
                        label = { Text("周次范围") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    (addError ?: writeLifecycle.errorMessage)?.let {
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
                    onClick = {
                        val activeTermId = term?.id ?: AcademicTermResolver.defaultTermId()
                        // 输入校验：空课程名/非法节次/空或无效周次明确提示，绝不静默兜底保存错误课程
                        val spRaw = newStartPeriod.trim().toIntOrNull()
                        val epRaw = newEndPeriod.trim().toIntOrNull()
                        val parsedWeeks = WeeksSetParser.parse(newWeeks)
                        addError = when {
                            newName.isBlank() -> "课程名称不能为空"
                            spRaw == null || spRaw !in 1..12 -> "开始节次必须是 1-12 的数字"
                            epRaw == null || epRaw !in 1..12 -> "结束节次必须是 1-12 的数字"
                            epRaw < spRaw -> "结束节次不能小于开始节次"
                            parsedWeeks.isEmpty() -> "请填写有效周次范围（如 1-16周 或 3,5,7周）"
                            parsedWeeks.any { it !in 1..30 } -> "周次必须在 1-30 之间"
                            else -> null
                        }
                        if (addError != null) return@Button
                        val writeToken = writeLifecycle.begin(TimetableWriteKind.AddManualCourse)
                        onAction(
                            TimetableUiAction.AddManualCourse(
                                termId = activeTermId,
                                name = newName.trim(),
                                teacher = newTeacher.trim().ifBlank { null },
                                rawRoom = newRoom.trim().ifBlank { "待定" },
                                weekday = newWeekday,
                                startPeriod = checkNotNull(spRaw),
                                endPeriod = checkNotNull(epRaw),
                                weeks = parsedWeeks,
                                colorArgb = 0xFF0072BC,
                                writeToken = writeToken
                            )
                        )
                    },
                    enabled = !writePending,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (writePending) "写入中…" else "添加")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAddCourseDialogDismiss() },
                    enabled = !writePending
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showTermSwitcherDialog) {
        var startDateInput by remember(term?.id, term?.startDate) {
            mutableStateOf(term?.startDate?.toString() ?: "2026-08-31")
        }
        var dateError by remember { mutableStateOf<String?>(null) }
        val writeLifecycle = rememberTimetableWriteLifecycle(
            key = "term-switcher",
            writeCompletions = writeCompletions,
            onConsumeCompletion = { token ->
                onAction(TimetableUiAction.ConsumeWriteCompletion(token))
            },
            onWriteSuccess = onTermSwitcherDialogDismiss
        )
        val writePending = writeLifecycle.pending

        XPeachAlertDialog(
            onDismissRequest = { if (!writePending) onTermSwitcherDialogDismiss() },
            title = { Text("学期与校历") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "当前学期",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    writeLifecycle.errorMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    for (t in terms) {
                        val isCurrent = t.id == term?.id
                        val count = rules.count { it.termId == t.id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isCurrent) XPeachTheme.primaryAccentCardColor
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                )
                                .clickable(enabled = !writePending) {
                                    val writeToken = writeLifecycle.begin(TimetableWriteKind.SelectCurrentTerm)
                                    onAction(TimetableUiAction.SelectCurrentTerm(t.id, writeToken = writeToken))
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = t.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${t.startDate} 开学 · 共 ${t.weekCount} 周",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = if (writePending) "写入中…" else if (isCurrent) "当前 ($count 门)" else "$count 门课",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrent) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    XPeachInsetDivider(verticalPadding = 4.dp)

                    Text(
                        text = "第一周周一日期",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = startDateInput,
                        onValueChange = {
                            startDateInput = it
                            dateError = null
                        },
                        label = { Text("YYYY-MM-DD") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    dateError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val parsedDate = runCatching { LocalDate.parse(startDateInput.trim()) }.getOrNull()
                                val activeTermId = term?.id
                                if (parsedDate == null || activeTermId == null) {
                                    dateError = "日期格式无效 (YYYY-MM-DD)"
                                } else {
                                    val writeToken = writeLifecycle.begin(TimetableWriteKind.UpdateTermStartDate)
                                    onAction(TimetableUiAction.UpdateTermStartDate(activeTermId, parsedDate, writeToken = writeToken))
                                }
                            },
                            enabled = !writePending,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (writePending) "写入中…" else "保存日期", maxLines = 1, softWrap = false)
                        }
                        FilledTonalButton(
                            onClick = {
                                onTermSwitcherDialogDismiss()
                                onNavigateToImporter()
                            },
                            enabled = !writePending,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("同步该学期", maxLines = 1, softWrap = false)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onTermSwitcherDialogDismiss() },
                    enabled = !writePending
                ) {
                    Text("关闭")
                }
            }
        )
    }
}
