package cn.edu.haut.campus.feature.automation.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.automation.AutomationRule
import cn.edu.haut.campus.core.automation.AutomationRun
import cn.edu.haut.campus.feature.automation.state.AutomationEditorDraft
import cn.edu.haut.campus.feature.automation.state.AutomationScreenMode
import cn.edu.haut.campus.feature.automation.state.AutomationScreenStatus
import cn.edu.haut.campus.feature.automation.state.AutomationTemplateUiItem
import cn.edu.haut.campus.feature.automation.state.AutomationTriggerChoice
import cn.edu.haut.campus.feature.automation.state.AutomationUiAction
import cn.edu.haut.campus.feature.automation.state.AutomationUiState
import cn.edu.haut.campus.feature.automation.state.actionSummary
import cn.edu.haut.campus.feature.automation.state.triggerSummary
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 纯 UI：只渲染 UiState 并发送 UiAction；不接触 Repository、网络或调度副作用。 */
@Composable
fun AutomationScreen(
    state: AutomationUiState,
    onAction: (AutomationUiAction) -> Unit
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
            TextButton(onClick = {
                onAction(if (state.mode == AutomationScreenMode.LIST) AutomationUiAction.Back else AutomationUiAction.BackToList)
            }) { Text("‹ 返回") }
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
                TextButton(onClick = { onAction(AutomationUiAction.NewRule) }) { Text("新建") }
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }
        state.message?.let { message -> StatusBanner(message, false) { onAction(AutomationUiAction.ClearMessage) } }
        state.error?.let { error -> StatusBanner(error, true) { onAction(AutomationUiAction.ClearMessage) } }
        when (state.mode) {
            AutomationScreenMode.LIST -> AutomationRuleList(state, onAction)
            AutomationScreenMode.EDITOR -> AutomationEditor(state.draft, onAction)
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
    onAction: (AutomationUiAction) -> Unit
) {
    when (state.effectiveStatus) {
        AutomationScreenStatus.LOADING -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在读取本地自动化规则…")
            }
        }
        AutomationScreenStatus.EMPTY -> Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("还没有自动化规则", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "可以从模板开始，也可以创建定时、课前、水电阈值、校区围栏或领域事件规则；所有动作只在本机执行。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { onAction(AutomationUiAction.NewRule) }) { Text("创建第一条规则") }
                }
            }
            TemplateCatalog(state.templates, onAction)
        }
        AutomationScreenStatus.CONTENT,
        AutomationScreenStatus.PARTIAL,
        AutomationScreenStatus.STALE -> Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.effectiveStatus != AutomationScreenStatus.CONTENT) {
                Text(
                    text = state.statusDetail ?: if (state.effectiveStatus == AutomationScreenStatus.PARTIAL) "部分数据不可用" else "数据可能已过期",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TemplateCatalog(state.templates, onAction)
            if (state.rules.isEmpty()) {
                Text(
                    "暂无已保存规则。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.rules.forEach { rule -> RuleCard(rule, onAction) }
        }
        else -> AutomationStateCard(state, onAction)
    }
}

@Composable
private fun TemplateCatalog(
    templates: List<AutomationTemplateUiItem>,
    onAction: (AutomationUiAction) -> Unit
) {
    if (templates.isEmpty()) return
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("规则模板", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "模板只会载入为默认关闭的草稿；检查触发器、证据条件和天气条款后，由你显式启用。证据不足或数据过期时不会触发。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            templates.forEach { template ->
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(template.title, style = MaterialTheme.typography.titleSmall)
                    Text(template.description, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "需要证据：" + template.requiredEvidence.joinToString("、"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { onAction(AutomationUiAction.UseTemplate(template.id)) }) {
                        Text("使用模板")
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleCard(
    rule: AutomationRule,
    onAction: (AutomationUiAction) -> Unit
) {
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
                    rule.templateId?.let {
                        Text("来自模板：" + it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { enabled -> onAction(AutomationUiAction.SetEnabled(rule, enabled)) }
                )
            }
            Text(
                "条件 " + rule.conditions.size + " 项 · 动作 " + rule.actions.size + " 项",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAction(AutomationUiAction.OpenRuns(rule)) }) { Text("运行记录") }
                OutlinedButton(onClick = { onAction(AutomationUiAction.EditRule(rule)) }) { Text("编辑") }
                TextButton(onClick = { onAction(AutomationUiAction.DeleteRule(rule)) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AutomationStateCard(
    state: AutomationUiState,
    onAction: (AutomationUiAction) -> Unit
) {
    val (title, body) = when (state.effectiveStatus) {
        AutomationScreenStatus.ERROR_RETRYABLE -> "暂时无法读取" to (state.statusDetail ?: "本地规则读取失败，可以重试。")
        AutomationScreenStatus.ERROR_NON_RETRYABLE -> "无法读取自动化" to (state.statusDetail ?: "发生不可自动恢复的错误。")
        AutomationScreenStatus.NEEDS_PERMISSION -> "需要通知权限" to (state.statusDetail ?: "本地通知动作需要用户授权；未授权时规则不会静默失败。")
        AutomationScreenStatus.NEEDS_LOGIN -> "需要登录" to (state.statusDetail ?: "当前缺少可用学校账号上下文。")
        AutomationScreenStatus.OFFLINE -> "当前离线" to (state.statusDetail ?: "本地规则仍可查看，天气等外部证据不可用时会按 UNKNOWN 处理。")
        AutomationScreenStatus.UNSUPPORTED -> "本校暂不支持" to (state.statusDetail ?: "当前学校/校区缺少该自动化所需能力。")
        else -> "状态未知" to (state.statusDetail ?: "无法确定当前自动化状态。")
    }
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.effectiveStatus == AutomationScreenStatus.ERROR_RETRYABLE || state.statusRetryable) {
                OutlinedButton(onClick = { onAction(AutomationUiAction.Retry) }) { Text("重试") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationEditor(
    draft: AutomationEditorDraft,
    onAction: (AutomationUiAction) -> Unit
) {
    fun update(transform: (AutomationEditorDraft) -> AutomationEditorDraft) {
        onAction(AutomationUiAction.UpdateDraft(transform))
    }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        draft.templateId?.let {
            Text("模板：" + it + "。模板规则默认关闭，启用前请检查证据条件。", style = MaterialTheme.typography.bodySmall)
        }
        if (draft.preservedConditions.isNotEmpty() || draft.preservedActions.isNotEmpty()) {
            Text(
                "包含骨架表单暂不能编辑的高级条件/动作；保存时会原样保留，不会静默丢弃。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AutomationTextField(draft.name, "规则名称") { value -> update { it.copy(name = value) } }
        ToggleRow("启用规则", draft.enabled) { value -> update { it.copy(enabled = value) } }

        Text("触发器", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AutomationTriggerChoice.entries.take(3).forEach { choice ->
                FilterChip(
                    selected = draft.triggerChoice == choice,
                    onClick = { update { it.copy(triggerChoice = choice) } },
                    label = { Text(choice.label) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AutomationTriggerChoice.entries.drop(3).forEach { choice ->
                FilterChip(
                    selected = draft.triggerChoice == choice,
                    onClick = { update { it.copy(triggerChoice = choice) } },
                    label = { Text(choice.label) }
                )
            }
        }
        TriggerEditorFields(draft) { transform -> update(transform) }

        HorizontalDivider()
        Text("条件（证据不足时一律不触发）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        ToggleRow("今天有课", draft.hasClassToday) { value -> update { it.copy(hasClassToday = value) } }
        ToggleRow("在校（粗定位）", draft.onCampus) { value -> update { it.copy(onCampus = value) } }
        ToggleRow("专注时段", draft.focusPeriod) { value -> update { it.copy(focusPeriod = value) } }
        ToggleRow("通勤时长", draft.commuteTime) { value -> update { it.copy(commuteTime = value) } }
        if (draft.commuteTime) {
            AutomationTextField(draft.commuteFromCampusText, "通勤起点校区 ID（可留空）") { value ->
                update { it.copy(commuteFromCampusText = value.filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(64)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AutomationTextField(draft.commuteMinMinutesText, "下限", modifier = Modifier.width(92.dp)) { value ->
                    update { it.copy(commuteMinMinutesText = value.filter(Char::isDigit).take(4)) }
                }
                AutomationTextField(draft.commuteMaxMinutesText, "上限（分钟）", modifier = Modifier.width(128.dp)) { value ->
                    update { it.copy(commuteMaxMinutesText = value.filter(Char::isDigit).take(4)) }
                }
            }
            Text(
                "跨校区路线证据不足会按 UNKNOWN 不触发；模板使用非零下限表达“需要通勤”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ToggleRow("数据新鲜度", draft.dataFreshness) { value -> update { it.copy(dataFreshness = value) } }
        if (draft.dataFreshness) {
            AutomationTextField(draft.freshnessMaxAgeHoursText, "课表最多陈旧（小时）") { value ->
                update { it.copy(freshnessMaxAgeHoursText = value.filter(Char::isDigit).take(3)) }
            }
        }
        if (draft.onCampus || draft.commuteTime || draft.triggerChoice == AutomationTriggerChoice.GEOFENCE) {
            AutomationTextField(draft.campusIdText, "校区 ID") { value ->
                update { it.copy(campusIdText = value.filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(64)) }
            }
        }

        HorizontalDivider()
        ToggleRow("天气降水条件", draft.weatherPrecipitation) { value -> update { it.copy(weatherPrecipitation = value) } }
        if (draft.weatherPrecipitation) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AutomationTextField(draft.weatherHorizonMinutesText, "窗口（分钟）", modifier = Modifier.width(118.dp)) { value ->
                    update { it.copy(weatherHorizonMinutesText = value.filter(Char::isDigit).take(3)) }
                }
                AutomationTextField(draft.weatherMinProbabilityText, "概率阈值%", modifier = Modifier.width(112.dp)) { value ->
                    update { it.copy(weatherMinProbabilityText = value.filter(Char::isDigit).take(3)) }
                }
            }
            ToggleRow(
                "我已阅读 MET Norway 条款，并接受在结果旁显示署名",
                draft.weatherTermsAccepted
            ) { value -> update { it.copy(weatherTermsAccepted = value) } }
            Text(
                "天气只使用校区级参考坐标，不使用精确 GPS。条款：https://api.met.no/doc/TermsOfService；署名：Data from MET Norway。网络失败、字段缺失或数据过期时不会触发。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ToggleRow("水电低余额状态", draft.utilityBalance) { value -> update { it.copy(utilityBalance = value) } }
        if (draft.utilityBalance) {
            AutomationTextField(draft.utilityBalanceValueText, "余额低于（度/吨）") { value ->
                update { it.copy(utilityBalanceValueText = value.filter { ch -> ch.isDigit() || ch == '.' }.take(10)) }
            }
            ToggleRow("按水表统计（关闭则电表）", draft.utilityBalanceWater) { value ->
                update { it.copy(utilityBalanceWater = value) }
            }
        }
        ToggleRow("明确临时停课证据", draft.courseCancellation) { value -> update { it.copy(courseCancellation = value) } }
        if (draft.courseCancellation) {
            Text(
                "只有 CourseChanged 差异明确出现取消语义才通过；普通调课或缺少事件上下文不会触发。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()
        Text("动作", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        ToggleRow("本地通知", draft.notify) { value -> update { it.copy(notify = value) } }
        if (draft.notify) {
            AutomationTextField(draft.notificationTitle, "通知标题") { value ->
                update { it.copy(notificationTitle = value.take(80)) }
            }
            AutomationTextField(draft.notificationBody, "通知内容") { value ->
                update { it.copy(notificationBody = value.take(240)) }
            }
        }
        ToggleRow("创建待办", draft.createTask) { value -> update { it.copy(createTask = value) } }
        if (draft.createTask) {
            AutomationTextField(draft.taskTitle, "待办标题") { value ->
                update { it.copy(taskTitle = value.take(120)) }
            }
            AutomationTextField(draft.taskDueMinutesText, "多少分钟后截止") { value ->
                update { it.copy(taskDueMinutesText = value.filter(Char::isDigit).take(5)) }
            }
        }
        ToggleRow("创建日程", draft.createEvent) { value -> update { it.copy(createEvent = value) } }
        if (draft.createEvent) {
            AutomationTextField(draft.eventTitle, "日程标题") { value ->
                update { it.copy(eventTitle = value.take(120)) }
            }
            AutomationTextField(draft.eventStartMinutesText, "多少分钟后开始") { value ->
                update { it.copy(eventStartMinutesText = value.filter(Char::isDigit).take(5)) }
            }
            AutomationTextField(draft.eventDurationMinutesText, "时长（分钟）") { value ->
                update { it.copy(eventDurationMinutesText = value.filter(Char::isDigit).take(4)) }
            }
        }
        ToggleRow("刷新首页卡片和小组件", draft.refreshHome) { value -> update { it.copy(refreshHome = value) } }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onAction(AutomationUiAction.DryRunDraft) }) { Text("试运行") }
            Button(onClick = { onAction(AutomationUiAction.SaveDraft) }) { Text("保存规则") }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerEditorFields(
    draft: AutomationEditorDraft,
    update: ((AutomationEditorDraft) -> AutomationEditorDraft) -> Unit
) {
    when (draft.triggerChoice) {
        AutomationTriggerChoice.AT_TIME -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimePartField(draft.atTime.hour, "小时", 23) { hour ->
                    update { it.copy(atTime = java.time.LocalTime.of(hour, it.atTime.minute)) }
                }
                TimePartField(draft.atTime.minute, "分钟", 59) { minute ->
                    update { it.copy(atTime = java.time.LocalTime.of(it.atTime.hour, minute)) }
                }
            }
            Text("重复星期", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = day in draft.days,
                        onClick = {
                            update {
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
            update { it.copy(beforeMinutesText = value.filter(Char::isDigit).take(3)) }
        }
        AutomationTriggerChoice.ON_EVENT -> {
            AutomationTextField(draft.eventTypeText, "DomainEvent 类型") { value ->
                update { it.copy(eventTypeText = value.filter { ch -> !ch.isWhitespace() }.take(128)) }
            }
            if (draft.eventFilter != null) {
                Text(
                    "该规则包含模板事件过滤器；骨架表单会原样保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AutomationTriggerChoice.THRESHOLD -> {
            AutomationTextField(draft.thresholdValueText, "水电剩余量低于（表计单位）") { value ->
                update { it.copy(thresholdValueText = value.filter { ch -> ch.isDigit() || ch == '.' }.take(10)) }
            }
            ToggleRow("按水表统计（关闭则电表）", draft.thresholdWater) { value ->
                update { it.copy(thresholdWater = value) }
            }
            AutomationTextField(draft.thresholdHysteresisText, "重新进入滞后带（表计单位）") { value ->
                update { it.copy(thresholdHysteresisText = value.filter { ch -> ch.isDigit() || ch == '.' }.take(6)) }
            }
            AutomationTextField(draft.thresholdCooldownMinutesText, "冷却期（分钟）") { value ->
                update { it.copy(thresholdCooldownMinutesText = value.filter(Char::isDigit).take(5)) }
            }
        }
        AutomationTriggerChoice.GEOFENCE -> ToggleRow("进入校区时触发（关闭则离开时）", draft.geofenceEnter) { value ->
            update { it.copy(geofenceEnter = value) }
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
                    run.evaluations.forEach { evaluation ->
                        Text(
                            "条件 " + evaluation.conditionType + " → " + if (evaluation.effectivePassed) "通过" else "不通过" +
                                (evaluation.reason?.let { " · " + it } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                        evaluation.evidence.take(3).forEach { evidence ->
                            Text(
                                evidence.source + "：" + evidence.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
