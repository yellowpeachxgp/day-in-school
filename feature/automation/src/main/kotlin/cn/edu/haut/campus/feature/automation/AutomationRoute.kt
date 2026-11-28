package cn.edu.haut.campus.feature.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.automation.AutomationRule
import cn.edu.haut.campus.core.automation.AutomationRun
import cn.edu.haut.campus.core.automation.android.AutomationRuntime
import cn.edu.haut.campus.core.database.ActiveSession
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 自动化骨架 Route：规则列表、表单式编辑器与运行记录共用当前账号 runtime。 */
@Composable
fun AutomationRoute(activeSession: ActiveSession, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val runtime = AutomationRuntime.current(activeSession) ?: AutomationRuntime.bind(context, activeSession)
    val viewModel: AutomationViewModel = viewModel(
        key = "automation-" + activeSession.profileId + "-" + activeSession.generation,
        factory = AutomationViewModel.factory(runtime)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AutomationScreen(
        state = state,
        onBack = onBack,
        onNewRule = viewModel::openNewEditor,
        onEditRule = viewModel::openEditor,
        onDeleteRule = viewModel::delete,
        onSetEnabled = viewModel::setEnabled,
        onOpenRuns = viewModel::openRuns,
        onDraftChange = viewModel::updateDraft,
        onSaveDraft = viewModel::saveDraft,
        onDryRunDraft = viewModel::dryRunDraft,
        onBackToList = viewModel::backToList,
        onClearMessage = viewModel::clearMessage
    )
}

@Composable
fun AutomationScreen(
    state: AutomationUiState,
    onBack: () -> Unit,
    onNewRule: () -> Unit,
    onEditRule: (AutomationRule) -> Unit,
    onDeleteRule: (AutomationRule) -> Unit,
    onSetEnabled: (AutomationRule, Boolean) -> Unit,
    onOpenRuns: (AutomationRule) -> Unit,
    onDraftChange: ((AutomationEditorDraft) -> AutomationEditorDraft) -> Unit,
    onSaveDraft: () -> Unit,
    onDryRunDraft: () -> Unit,
    onBackToList: () -> Unit,
    onClearMessage: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = if (state.mode == AutomationScreenMode.LIST) onBack else onBackToList) {
                Text("‹ 返回")
            }
            Text(
                text = when (state.mode) {
                    AutomationScreenMode.LIST -> "自动化"
                    AutomationScreenMode.EDITOR -> "编辑规则"
                    AutomationScreenMode.RUNS -> "运行记录"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (state.mode == AutomationScreenMode.LIST) {
                TextButton(onClick = onNewRule) { Text("新建") }
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }
        state.message?.let { message -> StatusBanner(message, false, onClearMessage) }
        state.error?.let { error -> StatusBanner(error, true, onClearMessage) }
        when (state.mode) {
            AutomationScreenMode.LIST -> AutomationRuleList(state, onNewRule, onEditRule, onDeleteRule, onSetEnabled, onOpenRuns)
            AutomationScreenMode.EDITOR -> AutomationEditor(state.draft, onDraftChange, onSaveDraft, onDryRunDraft)
            AutomationScreenMode.RUNS -> AutomationRunList(state.runs)
        }
    }
}

@Composable
private fun StatusBanner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun AutomationRuleList(
    state: AutomationUiState,
    onNewRule: () -> Unit,
    onEditRule: (AutomationRule) -> Unit,
    onDeleteRule: (AutomationRule) -> Unit,
    onSetEnabled: (AutomationRule, Boolean) -> Unit,
    onOpenRuns: (AutomationRule) -> Unit
) {
    if (state.loading) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在读取本地自动化规则…")
        }
        return
    }
    if (state.rules.isEmpty()) {
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("还没有自动化规则", style = MaterialTheme.typography.titleMedium)
                Text(
                    "可以创建定时、课前、水电阈值、校区围栏或领域事件规则；所有动作只在本机执行。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onNewRule) { Text("创建第一条规则") }
            }
        }
        return
    }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        state.rules.forEach { rule ->
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                triggerSummary(rule) + " · " + actionSummary(rule),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = rule.enabled, onCheckedChange = { enabled -> onSetEnabled(rule, enabled) })
                    }
                    Text(
                        "条件 " + rule.conditions.size + " 项 · 动作 " + rule.actions.size + " 项",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onOpenRuns(rule) }) { Text("运行记录") }
                        OutlinedButton(onClick = { onEditRule(rule) }) { Text("编辑") }
                        TextButton(onClick = { onDeleteRule(rule) }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationEditor(
    draft: AutomationEditorDraft,
    onDraftChange: ((AutomationEditorDraft) -> AutomationEditorDraft) -> Unit,
    onSave: () -> Unit,
    onDryRun: () -> Unit
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        AutomationTextField(draft.name, "规则名称") { value -> onDraftChange { it.copy(name = value) } }
        ToggleRow("启用规则", draft.enabled) { value -> onDraftChange { it.copy(enabled = value) } }

        Text("触发器", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AutomationTriggerChoice.entries.take(3).forEach { choice ->
                FilterChip(
                    selected = draft.triggerChoice == choice,
                    onClick = { onDraftChange { it.copy(triggerChoice = choice) } },
                    label = { Text(choice.label) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AutomationTriggerChoice.entries.drop(3).forEach { choice ->
                FilterChip(
                    selected = draft.triggerChoice == choice,
                    onClick = { onDraftChange { it.copy(triggerChoice = choice) } },
                    label = { Text(choice.label) }
                )
            }
        }
        TriggerEditorFields(draft, onDraftChange)

        HorizontalDivider()
        Text("条件（证据不足时一律不触发）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        ToggleRow("今天有课", draft.hasClassToday) { value -> onDraftChange { it.copy(hasClassToday = value) } }
        ToggleRow("在校（粗定位）", draft.onCampus) { value -> onDraftChange { it.copy(onCampus = value) } }
        ToggleRow("专注时段", draft.focusPeriod) { value -> onDraftChange { it.copy(focusPeriod = value) } }
        ToggleRow("通勤时长", draft.commuteTime) { value -> onDraftChange { it.copy(commuteTime = value) } }
        if (draft.commuteTime) {
            AutomationTextField(draft.commuteMaxMinutesText, "通勤上限（分钟）") { value ->
                onDraftChange { it.copy(commuteMaxMinutesText = value.filter(Char::isDigit).take(4)) }
            }
        }
        ToggleRow("数据新鲜度", draft.dataFreshness) { value -> onDraftChange { it.copy(dataFreshness = value) } }
        if (draft.dataFreshness) {
            AutomationTextField(draft.freshnessMaxAgeHoursText, "课表最多陈旧（小时）") { value ->
                onDraftChange { it.copy(freshnessMaxAgeHoursText = value.filter(Char::isDigit).take(3)) }
            }
        }
        if (draft.onCampus || draft.commuteTime || draft.triggerChoice == AutomationTriggerChoice.GEOFENCE) {
            AutomationTextField(draft.campusIdText, "校区 ID") { value ->
                onDraftChange { it.copy(campusIdText = value.filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(64)) }
            }
        }

        HorizontalDivider()
        Text("动作", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        ToggleRow("本地通知", draft.notify) { value -> onDraftChange { it.copy(notify = value) } }
        if (draft.notify) {
            AutomationTextField(draft.notificationTitle, "通知标题") { value ->
                onDraftChange { it.copy(notificationTitle = value.take(80)) }
            }
            AutomationTextField(draft.notificationBody, "通知内容") { value ->
                onDraftChange { it.copy(notificationBody = value.take(240)) }
            }
        }
        ToggleRow("创建待办", draft.createTask) { value -> onDraftChange { it.copy(createTask = value) } }
        if (draft.createTask) {
            AutomationTextField(draft.taskTitle, "待办标题") { value ->
                onDraftChange { it.copy(taskTitle = value.take(120)) }
            }
            AutomationTextField(draft.taskDueMinutesText, "多少分钟后截止") { value ->
                onDraftChange { it.copy(taskDueMinutesText = value.filter(Char::isDigit).take(5)) }
            }
        }
        ToggleRow("创建日程", draft.createEvent) { value -> onDraftChange { it.copy(createEvent = value) } }
        if (draft.createEvent) {
            AutomationTextField(draft.eventTitle, "日程标题") { value ->
                onDraftChange { it.copy(eventTitle = value.take(120)) }
            }
            AutomationTextField(draft.eventStartMinutesText, "多少分钟后开始") { value ->
                onDraftChange { it.copy(eventStartMinutesText = value.filter(Char::isDigit).take(5)) }
            }
            AutomationTextField(draft.eventDurationMinutesText, "时长（分钟）") { value ->
                onDraftChange { it.copy(eventDurationMinutesText = value.filter(Char::isDigit).take(4)) }
            }
        }
        ToggleRow("刷新首页卡片和小组件", draft.refreshHome) { value -> onDraftChange { it.copy(refreshHome = value) } }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onDryRun) { Text("试运行") }
            Button(onClick = onSave) { Text("保存规则") }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerEditorFields(
    draft: AutomationEditorDraft,
    onDraftChange: ((AutomationEditorDraft) -> AutomationEditorDraft) -> Unit
) {
    when (draft.triggerChoice) {
        AutomationTriggerChoice.AT_TIME -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimePartField(draft.atTime.hour, "小时", 23) { hour ->
                    onDraftChange { it.copy(atTime = java.time.LocalTime.of(hour, it.atTime.minute)) }
                }
                TimePartField(draft.atTime.minute, "分钟", 59) { minute ->
                    onDraftChange { it.copy(atTime = java.time.LocalTime.of(it.atTime.hour, minute)) }
                }
            }
            Text("重复星期", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = day in draft.days,
                        onClick = {
                            onDraftChange {
                                val next = if (day in it.days) it.days - day else it.days + day
                                it.copy(days = if (next.isEmpty()) setOf(day) else next)
                            }
                        },
                        label = { Text(dayLabel(day)) }
                    )
                }
            }
        }
        AutomationTriggerChoice.BEFORE_EVENT -> AutomationTextField(draft.beforeMinutesText, "课程/实践课前多少分钟") { value ->
            onDraftChange { it.copy(beforeMinutesText = value.filter(Char::isDigit).take(3)) }
        }
        AutomationTriggerChoice.ON_EVENT -> AutomationTextField(draft.eventTypeText, "DomainEvent 类型") { value ->
            onDraftChange { it.copy(eventTypeText = value.filter { ch -> !ch.isWhitespace() }.take(128)) }
        }
        AutomationTriggerChoice.THRESHOLD -> AutomationTextField(draft.thresholdYuanText, "水电余额低于（元）") { value ->
            onDraftChange { it.copy(thresholdYuanText = value.filter { ch -> ch.isDigit() || ch == '.' }.take(10)) }
        }
        AutomationTriggerChoice.GEOFENCE -> ToggleRow("进入校区时触发（关闭则离开时）", draft.geofenceEnter) { value ->
            onDraftChange { it.copy(geofenceEnter = value) }
        }
    }
}

@Composable
private fun TimePartField(value: Int, label: String, max: Int, onValue: (Int) -> Unit) {
    AutomationTextField(
        value = value.toString().padStart(2, '0'),
        label = label,
        modifier = Modifier.width(92.dp)
    ) { text ->
        onValue((text.filter(Char::isDigit).take(2).toIntOrNull() ?: 0).coerceIn(0, max))
    }
}

@Composable
private fun AutomationRunList(runs: List<AutomationRun>) {
    if (runs.isEmpty()) {
        Text("暂无运行记录。触发或试运行后会在这里保留可审计结果。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"))
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        runs.forEach { run ->
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(run.status.name + if (run.dryRun) " · 试运行" else "", fontWeight = FontWeight.SemiBold)
                    Text("触发：" + formatter.format(run.firedAt) + " · 记录：" + formatter.format(run.finishedAt), style = MaterialTheme.typography.bodySmall)
                    run.statusReason?.let { reason -> Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    run.outcomes.forEach { outcome ->
                        Text(
                            "动作 " + outcome.actionType + " → " + outcome.status + (outcome.detail?.let { detail -> " · " + detail } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomationTextField(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun dayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    DayOfWeek.SUNDAY -> "日"
}
