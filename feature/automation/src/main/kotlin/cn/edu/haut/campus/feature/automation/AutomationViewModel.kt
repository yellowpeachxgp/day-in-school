package cn.edu.haut.campus.feature.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.edu.haut.campus.core.automation.AutomationRule
import cn.edu.haut.campus.core.automation.BuiltinAutomationTemplates
import cn.edu.haut.campus.core.automation.BuiltinAutomationTypes
import cn.edu.haut.campus.core.automation.TriggerSpec
import cn.edu.haut.campus.core.automation.android.AutomationRuntime
import cn.edu.haut.campus.feature.automation.state.AutomationEditorDraft
import cn.edu.haut.campus.feature.automation.state.AutomationScreenMode
import cn.edu.haut.campus.feature.automation.state.AutomationScreenStatus
import cn.edu.haut.campus.feature.automation.state.AutomationTemplateUiItem
import cn.edu.haut.campus.feature.automation.state.AutomationUiAction
import cn.edu.haut.campus.feature.automation.state.AutomationUiEffect
import cn.edu.haut.campus.feature.automation.state.AutomationUiState
import cn.edu.haut.campus.feature.automation.state.AutomationTriggerChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 自动化骨架 UI 状态 holder；只调用当前账号已绑定的 AutomationRuntime，不跨 Profile 读取。 */
class AutomationViewModel(
    private val runtime: AutomationRuntime
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        AutomationUiState(templates = BuiltinAutomationTemplates.all.map(::toUiItem))
    )
    val uiState: StateFlow<AutomationUiState> = mutableState.asStateFlow()
    private val mutableEffects = MutableSharedFlow<AutomationUiEffect>(extraBufferCapacity = 16)
    val effects = mutableEffects.asSharedFlow()
    private var runsJob: Job? = null

    init {
        viewModelScope.launch {
            runtime.observeRules().collect { rules ->
                mutableState.update {
                    it.copy(
                        loading = false,
                        status = if (rules.isEmpty()) AutomationScreenStatus.EMPTY else AutomationScreenStatus.CONTENT,
                        statusDetail = null,
                        rules = rules,
                        error = null
                    )
                }
            }
        }
    }

    fun dispatch(action: AutomationUiAction) {
        when (action) {
            AutomationUiAction.Back -> emit(AutomationUiEffect.NavigateBack)
            AutomationUiAction.BackToList -> backToList()
            AutomationUiAction.NewRule -> openNewEditor()
            AutomationUiAction.SaveDraft -> saveDraft()
            AutomationUiAction.DryRunDraft -> dryRunDraft()
            AutomationUiAction.ClearMessage -> clearMessage()
            AutomationUiAction.Retry -> retry()
            is AutomationUiAction.UseTemplate -> useTemplate(action.templateId)
            is AutomationUiAction.EditRule -> openEditor(action.rule)
            is AutomationUiAction.DeleteRule -> delete(action.rule)
            is AutomationUiAction.OpenRuns -> openRuns(action.rule)
            is AutomationUiAction.SetEnabled -> setEnabled(action.rule, action.enabled)
            is AutomationUiAction.UpdateDraft -> updateDraft(action.transform)
        }
    }

    private fun emit(effect: AutomationUiEffect) {
        viewModelScope.launch { mutableEffects.emit(effect) }
    }

    private fun openNewEditor() {
        mutableState.update {
            it.copy(
                mode = AutomationScreenMode.EDITOR,
                draft = AutomationEditorDraft(),
                selectedRuleId = null,
                message = "新规则默认关闭；保存后仍需显式启用",
                error = null
            )
        }
    }

    private fun useTemplate(templateId: String) {
        val template = BuiltinAutomationTemplates.byId(templateId)
        if (template == null) {
            mutableState.update { it.copy(error = "模板不存在或已下线") }
            return
        }
        val rule = template.instantiate(runtime.scope)
        mutableState.update { state ->
            val alreadyExists = state.rules.any { it.id == rule.id }
            state.copy(
                mode = AutomationScreenMode.EDITOR,
                draft = draftFromRule(rule),
                selectedRuleId = null,
                message = if (alreadyExists) {
                    "模板已载入；同模板规则已存在，保存会覆盖该规则。规则仍默认关闭"
                } else {
                    "模板已载入为草稿；请检查证据条件并显式启用"
                },
                error = null
            )
        }
    }

    private fun openEditor(rule: AutomationRule) {
        mutableState.update {
            it.copy(
                mode = AutomationScreenMode.EDITOR,
                draft = draftFromRule(rule),
                selectedRuleId = rule.id,
                message = "编辑现有规则；高级 JSON 配置会保留，不能编辑的部分不会静默丢弃",
                error = null
            )
        }
    }

    private fun updateDraft(transform: (AutomationEditorDraft) -> AutomationEditorDraft) {
        mutableState.update { it.copy(draft = transform(it.draft).copy(error = null), error = null) }
    }

    private fun backToList() {
        runsJob?.cancel()
        mutableState.update { it.copy(mode = AutomationScreenMode.LIST, message = null, error = null) }
    }

    private fun retry() {
        mutableState.update {
            it.copy(
                status = if (it.rules.isEmpty()) AutomationScreenStatus.EMPTY else AutomationScreenStatus.CONTENT,
                statusDetail = null,
                statusRetryable = false,
                error = null,
                message = "已重新读取本地规则"
            )
        }
    }

    private fun saveDraft() {
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
                        message = if (rule.enabled) "规则已保存并重新调度" else "规则已保存；当前仍为暂停状态",
                        error = null
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = "保存失败：" + (error.message ?: error::class.simpleName)) }
            }
        }
    }

    private fun dryRunDraft() {
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

    private fun setEnabled(rule: AutomationRule, enabled: Boolean) {
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

    private fun delete(rule: AutomationRule) {
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

    private fun openRuns(rule: AutomationRule) {
        runsJob?.cancel()
        mutableState.update { it.copy(mode = AutomationScreenMode.RUNS, selectedRuleId = rule.id, runs = emptyList()) }
        runsJob = viewModelScope.launch {
            runtime.engine.runs(rule.id).collect { runs ->
                mutableState.update { it.copy(runs = runs) }
            }
        }
    }

    private fun clearMessage() {
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

        private fun toUiItem(template: cn.edu.haut.campus.core.automation.AutomationRuleTemplate): AutomationTemplateUiItem =
            AutomationTemplateUiItem(
                id = template.id,
                title = template.title,
                description = template.description,
                requiredEvidence = template.requiredEvidence
            )
    }
}

/** 表单可识别字段尽量回填；无法表达的高级条件/动作放入 preserved*，保存时原样保留。 */
fun draftFromRule(rule: AutomationRule): AutomationEditorDraft {
    val base = AutomationEditorDraft(
        id = rule.id,
        name = rule.name,
        enabled = rule.enabled,
        templateId = rule.templateId
    )
    val triggered = when (val trigger = rule.trigger) {
        is TriggerSpec.AtTime -> base.copy(
            triggerChoice = AutomationTriggerChoice.AT_TIME,
            atTime = trigger.local,
            days = trigger.days
        )
        is TriggerSpec.BeforeEvent -> base.copy(
            triggerChoice = AutomationTriggerChoice.BEFORE_EVENT,
            beforeMinutesText = trigger.offset.toMinutes().toString()
        )
        is TriggerSpec.OnEvent -> base.copy(
            triggerChoice = AutomationTriggerChoice.ON_EVENT,
            eventTypeText = trigger.eventType,
            eventFilter = trigger.filter
        )
        is TriggerSpec.Threshold -> base.copy(
            triggerChoice = AutomationTriggerChoice.THRESHOLD,
            thresholdValueText = trigger.value.movePointLeft(2).stripTrailingZeros().toPlainString(),
            thresholdWater = trigger.metric.value == BuiltinAutomationTypes.METRIC_UTILITY_WATER_CENTIUNITS,
            thresholdHysteresisText = trigger.hysteresis.movePointLeft(2).stripTrailingZeros().toPlainString(),
            thresholdCooldownMinutesText = trigger.cooldown.toMinutes().toString()
        )
        is TriggerSpec.Geofence -> base.copy(
            triggerChoice = AutomationTriggerChoice.GEOFENCE,
            campusIdText = trigger.campusId.value,
            geofenceEnter = trigger.enter
        )
        is TriggerSpec.Plugin -> base
    }
    val conditionTypes = rule.conditions.map { it.type }.toSet()
    val onCampusSpec = rule.conditions.firstOrNull { it.type == BuiltinAutomationTypes.CONDITION_ON_CAMPUS }
    val commuteSpec = rule.conditions.firstOrNull { it.type == BuiltinAutomationTypes.CONDITION_COMMUTE_TIME }
    val freshnessSpec = rule.conditions.firstOrNull { it.type == BuiltinAutomationTypes.CONDITION_DATA_FRESHNESS }
    val weatherSpec = rule.conditions.firstOrNull { it.type == BuiltinAutomationTypes.CONDITION_WEATHER_PRECIPITATION }
    val utilitySpec = rule.conditions.firstOrNull { it.type == BuiltinAutomationTypes.CONDITION_UTILITY_BALANCE }
    val notificationSpec = rule.actions.firstOrNull { it.type == BuiltinAutomationTypes.ACTION_LOCAL_NOTIFICATION }
    val createSpecs = rule.actions.filter { it.type == BuiltinAutomationTypes.ACTION_CREATE_PERSONAL_EVENT }
    val taskSpec = createSpecs.firstOrNull { it.config.str("kind") != "PERSONAL" }
    val eventSpec = createSpecs.firstOrNull { it.config.str("kind") == "PERSONAL" }
    val formConditionTypes = setOf(
        BuiltinAutomationTypes.CONDITION_HAS_CLASS_TODAY,
        BuiltinAutomationTypes.CONDITION_ON_CAMPUS,
        BuiltinAutomationTypes.CONDITION_FOCUS_PERIOD,
        BuiltinAutomationTypes.CONDITION_COMMUTE_TIME,
        BuiltinAutomationTypes.CONDITION_DATA_FRESHNESS,
        BuiltinAutomationTypes.CONDITION_WEATHER_PRECIPITATION,
        BuiltinAutomationTypes.CONDITION_UTILITY_BALANCE,
        BuiltinAutomationTypes.CONDITION_COURSE_CANCELLATION
    )
    val formActionTypes = setOf(
        BuiltinAutomationTypes.ACTION_LOCAL_NOTIFICATION,
        BuiltinAutomationTypes.ACTION_CREATE_PERSONAL_EVENT,
        BuiltinAutomationTypes.ACTION_REFRESH_HOME
    )
    return triggered.copy(
        hasClassToday = BuiltinAutomationTypes.CONDITION_HAS_CLASS_TODAY in conditionTypes,
        onCampus = onCampusSpec != null,
        campusIdText = onCampusSpec?.config?.str("campusId")
            ?: commuteSpec?.config?.str("toCampus")
            ?: triggered.campusIdText,
        focusPeriod = BuiltinAutomationTypes.CONDITION_FOCUS_PERIOD in conditionTypes,
        commuteTime = commuteSpec != null,
        commuteFromCampusText = commuteSpec?.config?.str("fromCampus").orEmpty(),
        commuteMinMinutesText = commuteSpec?.config?.obj("minMinutes")?.intOrNull()?.toString() ?: "0",
        commuteMaxMinutesText = commuteSpec?.config?.obj("maxMinutes")?.intOrNull()?.toString() ?: triggered.commuteMaxMinutesText,
        dataFreshness = freshnessSpec != null,
        freshnessMaxAgeHoursText = freshnessSpec?.config?.obj("maxAgeSeconds")?.intOrNull()?.let { (it / 3600).coerceAtLeast(1).toString() }
            ?: triggered.freshnessMaxAgeHoursText,
        weatherPrecipitation = weatherSpec != null,
        weatherTermsAccepted = weatherSpec?.config?.obj("termsAccepted")?.boolOrNull() == true,
        weatherHorizonMinutesText = weatherSpec?.config?.obj("horizonMinutes")?.intOrNull()?.toString()
            ?: triggered.weatherHorizonMinutesText,
        weatherMinProbabilityText = weatherSpec?.config?.obj("minProbabilityPercent")?.intOrNull()?.toString()
            ?: triggered.weatherMinProbabilityText,
        utilityBalance = utilitySpec != null,
        utilityBalanceWater = utilitySpec?.config?.str("metric") == BuiltinAutomationTypes.METRIC_UTILITY_WATER_CENTIUNITS,
        utilityBalanceValueText = utilitySpec?.config?.obj("value")?.decimalTextOrNull()?.let { raw ->
            raw.toBigDecimalOrNull()?.movePointLeft(2)?.stripTrailingZeros()?.toPlainString()
        } ?: triggered.utilityBalanceValueText,
        courseCancellation = BuiltinAutomationTypes.CONDITION_COURSE_CANCELLATION in conditionTypes,
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
        refreshHome = rule.actions.any { it.type == BuiltinAutomationTypes.ACTION_REFRESH_HOME },
        preservedConditions = rule.conditions.filter { it.type !in formConditionTypes },
        preservedActions = rule.actions.filter { it.type !in formActionTypes }
    )
}

private fun cn.edu.haut.campus.core.model.json.JsonValue.decimalTextOrNull(): String? =
    (this as? cn.edu.haut.campus.core.model.json.JsonValue.Num)?.literal
