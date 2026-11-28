package cn.edu.haut.campus.feature.automation

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import cn.edu.haut.campus.core.automation.ActionSpec
import cn.edu.haut.campus.core.automation.AutomationRule
import cn.edu.haut.campus.core.automation.BuiltinAutomationTypes
import cn.edu.haut.campus.core.automation.ConditionSpec
import cn.edu.haut.campus.core.automation.TriggerSpec
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import java.time.DayOfWeek
import java.time.LocalTime

/** 状态矩阵 PreviewFixtures：loading / empty / list / editor / error / runs 均由纯 UI 状态驱动。 */
object AutomationPreviewFixtures {
    val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("preview-account"),
        profileId = ProfileId("preview-profile"),
        campusId = CampusId("haut-lianhuajie")
    )

    val sampleRule = AutomationRule(
        id = "preview-rule",
        scope = scope,
        name = "课前提醒",
        enabled = true,
        trigger = TriggerSpec.AtTime(LocalTime.of(7, 50), setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)),
        conditions = listOf(ConditionSpec(BuiltinAutomationTypes.CONDITION_HAS_CLASS_TODAY)),
        actions = listOf(
            ActionSpec(BuiltinAutomationTypes.ACTION_LOCAL_NOTIFICATION),
            ActionSpec(BuiltinAutomationTypes.ACTION_REFRESH_HOME)
        )
    )

    val listState = AutomationUiState(loading = false, rules = listOf(sampleRule))
    val emptyState = AutomationUiState(loading = false, rules = emptyList())
    val loadingState = AutomationUiState(loading = true)
    val editorState = AutomationUiState(
        loading = false,
        rules = listOf(sampleRule),
        mode = AutomationScreenMode.EDITOR,
        draft = AutomationEditorDraft()
    )
    val errorState = AutomationUiState(
        loading = false,
        rules = listOf(sampleRule),
        error = "示例错误：证据不足，未触发"
    )
    val runsState = AutomationUiState(
        loading = false,
        rules = listOf(sampleRule),
        mode = AutomationScreenMode.RUNS
    )
}

@Preview(showBackground = true, name = "自动化-加载")
@Composable
private fun AutomationLoadingPreview() = AutomationPreviewContainer(AutomationPreviewFixtures.loadingState)

@Preview(showBackground = true, name = "自动化-空态")
@Composable
private fun AutomationEmptyPreview() = AutomationPreviewContainer(AutomationPreviewFixtures.emptyState)

@Preview(showBackground = true, name = "自动化-规则列表")
@Composable
private fun AutomationListPreview() = AutomationPreviewContainer(AutomationPreviewFixtures.listState)

@Preview(showBackground = true, name = "自动化-编辑器")
@Composable
private fun AutomationEditorPreview() = AutomationPreviewContainer(AutomationPreviewFixtures.editorState)

@Preview(showBackground = true, name = "自动化-错误")
@Composable
private fun AutomationErrorPreview() = AutomationPreviewContainer(AutomationPreviewFixtures.errorState)

@Preview(showBackground = true, name = "自动化-运行记录空态")
@Composable
private fun AutomationRunsPreview() = AutomationPreviewContainer(AutomationPreviewFixtures.runsState)

@Composable
private fun AutomationPreviewContainer(state: AutomationUiState) {
    AutomationScreen(
        state = state,
        onBack = {},
        onNewRule = {},
        onEditRule = {},
        onDeleteRule = {},
        onSetEnabled = { _, _ -> },
        onOpenRuns = {},
        onDraftChange = {},
        onSaveDraft = {},
        onDryRunDraft = {},
        onBackToList = {},
        onClearMessage = {}
    )
}
