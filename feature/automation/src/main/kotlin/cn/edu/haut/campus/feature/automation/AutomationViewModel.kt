package cn.edu.haut.campus.feature.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.edu.haut.campus.core.automation.AutomationRule
import cn.edu.haut.campus.core.automation.android.AutomationRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 自动化骨架 UI 状态 holder；只调用当前账号已绑定的 AutomationRuntime，不跨 Profile 读取。 */
class AutomationViewModel(
    private val runtime: AutomationRuntime
) : ViewModel() {
    private val mutableState = MutableStateFlow(AutomationUiState())
    val uiState: StateFlow<AutomationUiState> = mutableState.asStateFlow()
    private var runsJob: Job? = null

    init {
        viewModelScope.launch {
            runtime.observeRules().collect { rules ->
                mutableState.update { it.copy(loading = false, rules = rules, error = null) }
            }
        }
    }

    fun openNewEditor() {
        mutableState.update {
            it.copy(
                mode = AutomationScreenMode.EDITOR,
                draft = AutomationEditorDraft(),
                selectedRuleId = null,
                message = null,
                error = null
            )
        }
    }

    fun openEditor(rule: AutomationRule) {
        mutableState.update {
            it.copy(
                mode = AutomationScreenMode.EDITOR,
                draft = draftFromRule(rule),
                selectedRuleId = rule.id,
                message = "编辑现有规则；高级 JSON 配置会以表单字段重建",
                error = null
            )
        }
    }

    fun updateDraft(transform: (AutomationEditorDraft) -> AutomationEditorDraft) {
        mutableState.update { it.copy(draft = transform(it.draft).copy(error = null), error = null) }
    }

    fun backToList() {
        runsJob?.cancel()
        mutableState.update { it.copy(mode = AutomationScreenMode.LIST, message = null, error = null) }
    }

    fun saveDraft() {
        val draft = mutableState.value.draft
        val validation = draft.validate()
        if (validation != null) {
            mutableState.update { it.copy(draft = draft.copy(error = validation), error = validation) }
            return
        }
        viewModelScope.launch {
            try {
                val rule = draft.toRule(runtime.scope)
                runtime.upsertRule(rule)
                mutableState.update {
                    it.copy(
                        mode = AutomationScreenMode.LIST,
                        selectedRuleId = rule.id,
                        message = "规则已保存并重新调度",
                        error = null
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = "保存失败：" + (error.message ?: error::class.simpleName)) }
            }
        }
    }

    fun dryRunDraft() {
        val draft = mutableState.value.draft
        val validation = draft.validate()
        if (validation != null) {
            mutableState.update { it.copy(draft = draft.copy(error = validation), error = validation) }
            return
        }
        viewModelScope.launch {
            try {
                val run = runtime.dryRun(draft.toRule(runtime.scope))
                mutableState.update {
                    it.copy(
                        message = "试运行：" + run.status + (run.statusReason?.let { reason -> " · $reason" } ?: ""),
                        error = null
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = "试运行失败：" + (error.message ?: error::class.simpleName)) }
            }
        }
    }

    fun setEnabled(rule: AutomationRule, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { runtime.setRuleEnabled(rule.id, enabled) }
                .onSuccess {
                    mutableState.update { state ->
                        state.copy(message = if (enabled) "规则已启用" else "规则已暂停", error = null)
                    }
                }
                .onFailure { error ->
                    mutableState.update { state -> state.copy(error = "状态更新失败：" + error.message) }
                }
        }
    }

    fun delete(rule: AutomationRule) {
        viewModelScope.launch {
            runCatching { runtime.deleteRule(rule.id) }
                .onSuccess {
                    mutableState.update { it.copy(message = "规则与本地运行记录已删除", error = null) }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(error = "删除失败：" + error.message) }
                }
        }
    }

    fun openRuns(rule: AutomationRule) {
        runsJob?.cancel()
        mutableState.update { it.copy(mode = AutomationScreenMode.RUNS, selectedRuleId = rule.id, runs = emptyList()) }
        runsJob = viewModelScope.launch {
            runtime.engine.runs(rule.id).collect { runs ->
                mutableState.update { it.copy(runs = runs) }
            }
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null, error = null) }
    }

    companion object {
        fun factory(runtime: AutomationRuntime): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AutomationViewModel::class.java))
                    return AutomationViewModel(runtime) as T
                }
            }
    }
}

/** 首期表单只覆盖内置类型；已有规则按可识别字段回填，无法识别的配置使用安全默认值。 */
private fun draftFromRule(rule: AutomationRule): AutomationEditorDraft {
    val base = AutomationEditorDraft(
        id = rule.id,
        name = rule.name,
        enabled = rule.enabled
    )
    val triggered = when (val trigger = rule.trigger) {
        is cn.edu.haut.campus.core.automation.TriggerSpec.AtTime -> base.copy(
            triggerChoice = AutomationTriggerChoice.AT_TIME,
            atTime = trigger.local,
            days = trigger.days
        )
        is cn.edu.haut.campus.core.automation.TriggerSpec.BeforeEvent -> base.copy(
            triggerChoice = AutomationTriggerChoice.BEFORE_EVENT,
            beforeMinutesText = trigger.offset.toMinutes().toString()
        )
        is cn.edu.haut.campus.core.automation.TriggerSpec.OnEvent -> base.copy(
            triggerChoice = AutomationTriggerChoice.ON_EVENT,
            eventTypeText = trigger.eventType
        )
        is cn.edu.haut.campus.core.automation.TriggerSpec.Threshold -> base.copy(
            triggerChoice = AutomationTriggerChoice.THRESHOLD,
            thresholdValueText = trigger.value.movePointLeft(2).stripTrailingZeros().toPlainString(),
            thresholdWater = trigger.metric.value == cn.edu.haut.campus.core.automation.BuiltinAutomationTypes.METRIC_UTILITY_WATER_CENTIUNITS,
            thresholdHysteresisText = trigger.hysteresis.movePointLeft(2).stripTrailingZeros().toPlainString(),
            thresholdCooldownMinutesText = trigger.cooldown.toMinutes().toString()
        )
        is cn.edu.haut.campus.core.automation.TriggerSpec.Geofence -> base.copy(
            triggerChoice = AutomationTriggerChoice.GEOFENCE,
            campusIdText = trigger.campusId.value,
            geofenceEnter = trigger.enter
        )
        is cn.edu.haut.campus.core.automation.TriggerSpec.Plugin -> base
    }
    val conditionTypes = rule.conditions.map { it.type }.toSet()
    val onCampusSpec = rule.conditions.firstOrNull { it.type == cn.edu.haut.campus.core.automation.BuiltinAutomationTypes.CONDITION_ON_CAMPUS }
    val commuteSpec = rule.conditions.firstOrNull { it.type == cn.edu.haut.campus.core.automation.BuiltinAutomationTypes.CONDITION_COMMUTE_TIME }
    val freshnessSpec = rule.conditions.firstOrNull { it.type == cn.edu.haut.campus.core.automation.BuiltinAutomationTypes.CONDITION_DATA_FRESHNESS }
    val notificationSpec = rule.actions.firstOrNull { it.type == cn.edu.haut.campus.core.automation.BuiltinAutomationTypes.ACTION_LOCAL_NOTIFICATION }
    val createSpecs = rule.actions.filter { it.type == cn.edu.haut.campus.core.automation.BuiltinAutomationTypes.ACTION_CREATE_PERSONAL_EVENT }
    val taskSpec = createSpecs.firstOrNull { it.config.str("kind") != "PERSONAL" }
    val eventSpec = createSpecs.firstOrNull { it.config.str("kind") == "PERSONAL" }
    return triggered.copy(
        hasClassToday = cn.edu.haut.campus.core.automation.BuiltinAutomationTypes.CONDITION_HAS_CLASS_TODAY in conditionTypes,
        onCampus = onCampusSpec != null,
        campusIdText = onCampusSpec?.config?.str("campusId")
            ?: commuteSpec?.config?.str("toCampus")
            ?: triggered.campusIdText,
        focusPeriod = cn.edu.haut.campus.core.automation.BuiltinAutomationTypes.CONDITION_FOCUS_PERIOD in conditionTypes,
        commuteTime = commuteSpec != null,
        commuteMaxMinutesText = commuteSpec?.config?.obj("maxMinutes")?.intOrNull()?.toString() ?: triggered.commuteMaxMinutesText,
        dataFreshness = freshnessSpec != null,
        freshnessMaxAgeHoursText = freshnessSpec?.config?.obj("maxAgeSeconds")?.intOrNull()?.let { (it / 3600).coerceAtLeast(1).toString() }
            ?: triggered.freshnessMaxAgeHoursText,
        notify = notificationSpec != null,
        notificationTitle = notificationSpec?.config?.str("title") ?: triggered.notificationTitle,
        notificationBody = notificationSpec?.config?.str("body") ?: triggered.notificationBody,
        createTask = taskSpec != null,
        taskTitle = taskSpec?.config?.str("title") ?: triggered.taskTitle,
        taskDueMinutesText = taskSpec?.config?.obj("dueInMinutes")?.intOrNull()?.toString() ?: triggered.taskDueMinutesText,
        createEvent = eventSpec != null,
        eventTitle = eventSpec?.config?.str("title") ?: triggered.eventTitle,
        eventStartMinutesText = eventSpec?.config?.obj("startInMinutes")?.intOrNull()?.toString() ?: triggered.eventStartMinutesText,
        eventDurationMinutesText = eventSpec?.config?.obj("durationMinutes")?.intOrNull()?.toString() ?: triggered.eventDurationMinutesText,
        refreshHome = rule.actions.any { it.type == cn.edu.haut.campus.core.automation.BuiltinAutomationTypes.ACTION_REFRESH_HOME }
    )
}
