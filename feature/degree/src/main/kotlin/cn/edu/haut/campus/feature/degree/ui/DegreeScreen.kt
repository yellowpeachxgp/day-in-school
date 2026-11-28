package cn.edu.haut.campus.feature.degree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.plan.AuditReport
import cn.edu.haut.campus.core.plan.GroupProgress
import cn.edu.haut.campus.core.plan.Provenance
import cn.edu.haut.campus.feature.degree.state.DegreeStatus
import cn.edu.haut.campus.feature.degree.state.DegreeUiAction
import cn.edu.haut.campus.feature.degree.state.DegreeUiState
import java.math.BigDecimal
import java.math.RoundingMode

/** 毕业进度看板骨架 UI：只渲染 UiState、发送 UiAction，不持有任何业务逻辑。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DegreeScreen(
    state: DegreeUiState,
    onAction: (DegreeUiAction) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("毕业进度") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                actions = {
                    TextButton(onClick = { onAction(DegreeUiAction.OpenImportDialog) }) { Text("导入方案") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.notice?.let { notice ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(notice, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onAction(DegreeUiAction.DismissNotice) }) { Text("知道了") }
                    }
                }
            }
            when (state.status) {
                DegreeStatus.LOADING -> {
                    Spacer(modifier = Modifier.height(48.dp))
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                DegreeStatus.EMPTY -> EmptyContent(onAction)
                DegreeStatus.ERROR -> {
                    Spacer(modifier = Modifier.height(48.dp))
                    Text(state.errorMessage ?: "加载失败", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { onAction(DegreeUiAction.Refresh) }) { Text("重试") }
                }
                DegreeStatus.READY -> ReadyContent(state, onAction)
            }
        }
    }

    if (state.showImportDialog) {
        ImportDialog(state, onAction)
    }
    state.overrideDraft?.let { draft ->
        OverrideDialog(state, draft, onAction)
    }
}

@Composable
private fun EmptyContent(onAction: (DegreeUiAction) -> Unit) {
    Spacer(modifier = Modifier.height(32.dp))
    Text("还没有培养方案", style = MaterialTheme.typography.titleMedium)
    Text(
        "导入培养方案后，这里会按毕业要求组展示已修学分、缺口与依据。" +
            "支持 univ-training-plan/v1 JSON 与扁平 CSV 文本。",
        style = MaterialTheme.typography.bodyMedium
    )
    Button(onClick = { onAction(DegreeUiAction.OpenImportDialog) }) { Text("导入培养方案") }
}

@Composable
private fun ReadyContent(state: DegreeUiState, onAction: (DegreeUiAction) -> Unit) {
    val report = state.report
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(state.disclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (report != null) {
            item { SummaryCard(report) }
            if (report.trust == Provenance.ESTIMATED) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("结论为估算", fontWeight = FontWeight.Bold)
                            Text(
                                report.notes.firstOrNull { it.contains("确认") || it.contains("未知") }
                                    ?: "培养方案未确认适用于本人",
                                style = MaterialTheme.typography.bodySmall
                            )
                            state.activeImportId?.let { activeId ->
                                TextButton(onClick = { onAction(DegreeUiAction.ConfirmPlan(activeId)) }) {
                                    Text("确认方案适用于本人")
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text("要求组进度", style = MaterialTheme.typography.titleMedium)
            }
            items(report.groups, key = { it.groupId }) { group ->
                GroupCard(group, report, state, onAction)
            }
            if (report.risks.isNotEmpty()) {
                item { Text("风险提示", style = MaterialTheme.typography.titleMedium) }
                items(report.risks) { risk ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(risk.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text(risk.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (report.unclassified.isNotEmpty()) {
                item {
                    Text("未归类课程", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "以下已修课程未映射到任何要求组，可通过人工认定归组或排除",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                items(report.unclassified, key = { it.courseKey }) { course ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(course.courseName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    course.credits.stripTrailingZeros().toPlainString() + " 学分 · " + course.courseKey,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = {
                                onAction(
                                    DegreeUiAction.OpenOverride(
                                        course.representativeAttemptId ?: course.courseKey,
                                        course.courseName
                                    )
                                )
                            }) { Text("人工认定") }
                        }
                    }
                }
            }
            if (report.overrideApplications.isNotEmpty()) {
                item {
                    Text("人工认定", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "认定只绑定当前方案/导入；改派会追加新认定，撤销后课程回到自动匹配",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                items(report.overrideApplications, key = { it.override.id }) { application ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(application.courseName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            val target = application.targetGroupId?.let { groupId ->
                                report.groupById(groupId)?.title ?: groupId
                            } ?: "排除（不计入任何组）"
                            Text("当前处理：" + target + "；依据：" + application.override.reason, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    onAction(
                                        DegreeUiAction.OpenOverride(
                                            application.representativeAttemptId ?: application.override.attemptRef.id,
                                            application.courseName
                                        )
                                    )
                                }) { Text("改派") }
                                TextButton(onClick = {
                                    onAction(
                                        DegreeUiAction.RetractOverride(
                                            application.representativeAttemptId ?: application.override.attemptRef.id,
                                            application.courseName
                                        )
                                    )
                                }) { Text("撤销认定") }
                            }
                        }
                    }
                }
            }
            if (report.notes.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("审计说明", style = MaterialTheme.typography.titleSmall)
                        report.notes.forEach { note ->
                            Text("· " + note, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            item {
                Text("已导入方案但尚未激活", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.imports.size > 1) {
            item {
                Text("已导入的方案", style = MaterialTheme.typography.titleMedium)
            }
            items(state.imports, key = { it.importId }) { record ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(record.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                record.planVersion + " · " + record.format + if (record.active) " · 当前使用" else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (!record.active) {
                            TextButton(onClick = { onAction(DegreeUiAction.ActivateImport(record.importId)) }) {
                                Text("使用")
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun SummaryCard(report: AuditReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("总进度", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val trustLabel = when (report.trust) {
                    Provenance.OFFICIAL -> "官方数据"
                    Provenance.DERIVED -> "本地推导"
                    Provenance.ESTIMATED -> "估算"
                }
                Text(trustLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            val ratio = if (report.totalRequiredCredits > BigDecimal.ZERO) {
                report.totalEarnedCredits.divide(report.totalRequiredCredits, 4, RoundingMode.HALF_UP)
                    .coerceIn(BigDecimal.ZERO, BigDecimal.ONE).toFloat()
            } else {
                0f
            }
            LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
            Text(
                "已修 " + report.totalEarnedCredits.stripTrailingZeros().toPlainString() +
                    " / " + report.totalRequiredCredits.stripTrailingZeros().toPlainString() + " 学分" +
                    "，剩余缺口 " + report.totalRemainingCredits.stripTrailingZeros().toPlainString(),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(report.basis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GroupCard(
    group: GroupProgress,
    report: AuditReport,
    state: DegreeUiState,
    onAction: (DegreeUiAction) -> Unit
) {
    val expanded = group.groupId in state.expandedGroupIds
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.title + " · " + group.kind.label, fontWeight = FontWeight.Bold)
                    Text(
                        "已修 " + group.earnedCredits.stripTrailingZeros().toPlainString() +
                            " / " + group.requiredCredits.stripTrailingZeros().toPlainString() + " 学分" +
                            if (group.remainingCredits > BigDecimal.ZERO) {
                                "，缺口 " + group.remainingCredits.stripTrailingZeros().toPlainString()
                            } else {
                                "，已满足"
                            },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = { onAction(DegreeUiAction.ToggleGroupTrace(group.groupId)) }) {
                    Text(if (expanded) "收起依据" else "查看依据")
                }
            }
            if (group.pendingCourses.isNotEmpty()) {
                Text(
                    "待修：" + group.pendingCourses.joinToString("、") { it.name },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (expanded) {
                HorizontalDivider()
                Text("审计依据", style = MaterialTheme.typography.labelMedium)
                val steps = report.trace.filter { step ->
                    group.assignedCourses.any { it.course.courseKey == step.courseKey } ||
                        (step.rule == "group-total")
                }
                if (steps.isEmpty()) {
                    Text("本组暂无已入账课程，缺口按方案要求全额统计", style = MaterialTheme.typography.bodySmall)
                }
                steps.forEach { step ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            step.courseName?.plus(" · ").plus(step.decision),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        val evidence = buildList {
                            step.attemptId?.let { add("成绩 " + it) }
                            if (step.academicYear != null && step.termNumber != null) {
                                add(step.academicYear + " 第" + step.termNumber + "学期")
                            }
                            step.gradeSource?.let { add("来源 " + it.name) }
                        }
                        Text(
                            "依据：" + step.basis + if (evidence.isNotEmpty()) "（" + evidence.joinToString(" · ") + "）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                group.assignedCourses.forEach { assignment ->
                    Text(
                        "· " + assignment.course.courseName + " 入账 " +
                            assignment.course.credits.stripTrailingZeros().toPlainString() + " 学分（" +
                            (if (assignment.matchedBy.name == "CODE") "代码匹配" else "名称/认定匹配") + "）",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportDialog(state: DegreeUiState, onAction: (DegreeUiAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(DegreeUiAction.CloseImportDialog) },
        title = { Text("导入培养方案") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "粘贴 univ-training-plan/v1 JSON（以 { 开头）或扁平 CSV 文本。CSV 列：" +
                        "group_path,kind,required_credits,course_code,course_name,offering,prerequisites",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = state.importText,
                    onValueChange = { onAction(DegreeUiAction.ImportTextChanged(it)) },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    placeholder = { Text("{ 或 CSV 文本") }
                )
                state.importError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAction(DegreeUiAction.SubmitImport) },
                enabled = !state.busy
            ) { Text(if (state.busy) "导入中…" else "导入") }
        },
        dismissButton = {
            TextButton(onClick = { onAction(DegreeUiAction.CloseImportDialog) }) { Text("取消") }
        }
    )
}

@Composable
private fun OverrideDialog(
    state: DegreeUiState,
    draft: cn.edu.haut.campus.feature.degree.state.OverrideDraft,
    onAction: (DegreeUiAction) -> Unit
) {
    val leafGroups = state.report?.groups.orEmpty()
    AlertDialog(
        onDismissRequest = { onAction(DegreeUiAction.CloseOverride) },
        title = { Text("人工认定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("课程：" + draft.courseName, style = MaterialTheme.typography.bodyMedium)
                Text("选择归入的要求组，或选择排除（不计入任何组）。认定会写入变更历史并附依据。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !draft.exclude,
                        onClick = { onAction(DegreeUiAction.OverrideTargetChanged(draft.targetGroupId, false)) },
                        label = { Text("归组") }
                    )
                    FilterChip(
                        selected = draft.exclude,
                        onClick = { onAction(DegreeUiAction.OverrideTargetChanged(null, true)) },
                        label = { Text("排除") }
                    )
                }
                if (!draft.exclude) {
                    leafGroups.forEach { group ->
                        FilterChip(
                            selected = draft.targetGroupId == group.groupId,
                            onClick = { onAction(DegreeUiAction.OverrideTargetChanged(group.groupId, false)) },
                            label = { Text(group.title) }
                        )
                    }
                }
                OutlinedTextField(
                    value = draft.reason,
                    onValueChange = { onAction(DegreeUiAction.OverrideReasonChanged(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("认定依据（必填）") },
                    placeholder = { Text("例如：教务处认定可计入开放选修") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(DegreeUiAction.SubmitOverride) }) { Text("记录认定") }
        },
        dismissButton = {
            TextButton(onClick = { onAction(DegreeUiAction.CloseOverride) }) { Text("取消") }
        }
    )
}
