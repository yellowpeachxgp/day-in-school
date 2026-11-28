package cn.edu.haut.campus.feature.automation.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import cn.edu.haut.campus.core.automation.ActionSpec
import cn.edu.haut.campus.core.automation.AutomationRule
import cn.edu.haut.campus.core.automation.BuiltinAutomationTemplates
import cn.edu.haut.campus.core.automation.BuiltinAutomationTypes
import cn.edu.haut.campus.core.automation.ConditionSpec
import cn.edu.haut.campus.core.automation.TriggerSpec
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.feature.automation.state.AutomationEditorDraft
import cn.edu.haut.campus.feature.automation.state.AutomationScreenMode
import cn.edu.haut.campus.feature.automation.state.AutomationScreenStatus
import cn.edu.haut.campus.feature.automation.state.AutomationTemplateUiItem
import cn.edu.haut.campus.feature.automation.state.AutomationUiState
import cn.edu.haut.campus.feature.automation.ui.AutomationScreen
import java.time.DayOfWeek
import java.time.LocalTime

/** MASTER_PLAN §10.2 的 11 态矩阵 fixtures；编辑器与运行记录是额外骨架状态。 */
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
        name = "早八带伞",
        enabled = false,
        trigger = TriggerSpec.AtTime(LocalTime.of(7, 20), setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)),
        conditions = listOf(
            ConditionSpec(BuiltinAutomationTypes.CONDITION_HAS_CLASS_TODAY),
            ConditionSpec(BuiltinAutomationTypes.CONDITION_WEATHER_PRECIPITATION)
        ),
        actions = listOf(
            ActionSpec(
                BuiltinAutomationTypes.ACTION_LOCAL_NOTIFICATION,
                JsonValue.Obj(
                    mapOf(
                        "title" to JsonValue.Str("早八天气提醒"),
                        "body" to JsonValue.Str("未来 3 小时可能有降水，出门前记得带伞。")
                    )
                )
            ),
            ActionSpec(BuiltinAutomationTypes.ACTION_REFRESH_HOME)
        ),
        templateId = BuiltinAutomationTemplates.TEMPLATE_EARLY_RAIN_UMBRELLA
    )

    val templateItems: List<AutomationTemplateUiItem> = BuiltinAutomationTemplates.all.map {
        AutomationTemplateUiItem(
            id = it.id,
            title = it.title,
            description = it.description,
            requiredEvidence = it.requiredEvidence
        )
    }

    val loadingState = AutomationUiState(
        loading = true,
        status = AutomationScreenStatus.LOADING,
        templates = templateItems
    )
    val emptyState = AutomationUiState(
        loading = false,
        status = AutomationScreenStatus.EMPTY,
        rules = emptyList(),
        templates = templateItems
    )
    val contentState = AutomationUiState(
        loading = false,
        status = AutomationScreenStatus.CONTENT,
        rules = listOf(sampleRule),
        templates = templateItems
    )
    val partialState = contentState.copy(
        status = AutomationScreenStatus.PARTIAL,
        statusDetail = "部分通勤证据不可用；相关条件会按 UNKNOWN 处理"
    )
    val staleState = contentState.copy(
        status = AutomationScreenStatus.STALE,
        statusDetail = "课表或天气证据可能已过期；过期证据不会触发确定性结论"
    )
    val retryableErrorState = contentState.copy(
        status = AutomationScreenStatus.ERROR_RETRYABLE,
        statusDetail = "本地数据库暂时忙碌，可重试",
        statusRetryable = true
    )
    val nonRetryableErrorState = contentState.copy(
        status = AutomationScreenStatus.ERROR_NON_RETRYABLE,
        statusDetail = "规则存储版本不兼容，需升级或清除后重建"
    )
    val needsPermissionState = contentState.copy(
        status = AutomationScreenStatus.NEEDS_PERMISSION,
        statusDetail = "通知权限未授予；启用通知动作前需要用户授权"
    )
    val needsLoginState = contentState.copy(
        status = AutomationScreenStatus.NEEDS_LOGIN,
        statusDetail = "需要选择或登录学校账号后才能使用账号级自动化"
    )
    val offlineState = contentState.copy(
        status = AutomationScreenStatus.OFFLINE,
        statusDetail = "当前离线；天气等外部输入不可用时不会误触发"
    )
    val unsupportedState = contentState.copy(
        status = AutomationScreenStatus.UNSUPPORTED,
        statusDetail = "当前学校缺少校区参考坐标或水电读数能力"
    )

    /** §10.2 要求的 11 态矩阵，测试直接断言不缺项。 */
    val stateMatrix: Map<AutomationScreenStatus, AutomationUiState> = linkedMapOf(
        AutomationScreenStatus.LOADING to loadingState,
        AutomationScreenStatus.EMPTY to emptyState,
        AutomationScreenStatus.CONTENT to contentState,
        AutomationScreenStatus.PARTIAL to partialState,
        AutomationScreenStatus.STALE to staleState,
        AutomationScreenStatus.ERROR_RETRYABLE to retryableErrorState,
        AutomationScreenStatus.ERROR_NON_RETRYABLE to nonRetryableErrorState,
        AutomationScreenStatus.NEEDS_PERMISSION to needsPermissionState,
        AutomationScreenStatus.NEEDS_LOGIN to needsLoginState,
        AutomationScreenStatus.OFFLINE to offlineState,
        AutomationScreenStatus.UNSUPPORTED to unsupportedState
    )

    val editorState = contentState.copy(
        mode = AutomationScreenMode.EDITOR,
        draft = AutomationEditorDraft(
            name = "早八带伞",
            enabled = false,
            templateId = BuiltinAutomationTemplates.TEMPLATE_EARLY_RAIN_UMBRELLA,
            weatherPrecipitation = true,
            weatherTermsAccepted = false
        )
    )
    val runsState = contentState.copy(mode = AutomationScreenMode.RUNS)
}

@Preview(showBackground = true, name = "自动化-Loading")
@Composable
private fun AutomationLoadingPreview() = AutomationScreen(AutomationPreviewFixtures.loadingState) {}

@Preview(showBackground = true, name = "自动化-Empty")
@Composable
private fun AutomationEmptyPreview() = AutomationScreen(AutomationPreviewFixtures.emptyState) {}

@Preview(showBackground = true, name = "自动化-Content")
@Composable
private fun AutomationContentPreview() = AutomationScreen(AutomationPreviewFixtures.contentState) {}

@Preview(showBackground = true, name = "自动化-Partial")
@Composable
private fun AutomationPartialPreview() = AutomationScreen(AutomationPreviewFixtures.partialState) {}

@Preview(showBackground = true, name = "自动化-Stale")
@Composable
private fun AutomationStalePreview() = AutomationScreen(AutomationPreviewFixtures.staleState) {}

@Preview(showBackground = true, name = "自动化-ErrorRetryable")
@Composable
private fun AutomationRetryableErrorPreview() = AutomationScreen(AutomationPreviewFixtures.retryableErrorState) {}

@Preview(showBackground = true, name = "自动化-ErrorNonRetryable")
@Composable
private fun AutomationNonRetryableErrorPreview() = AutomationScreen(AutomationPreviewFixtures.nonRetryableErrorState) {}

@Preview(showBackground = true, name = "自动化-NeedsPermission")
@Composable
private fun AutomationNeedsPermissionPreview() = AutomationScreen(AutomationPreviewFixtures.needsPermissionState) {}

@Preview(showBackground = true, name = "自动化-NeedsLogin")
@Composable
private fun AutomationNeedsLoginPreview() = AutomationScreen(AutomationPreviewFixtures.needsLoginState) {}

@Preview(showBackground = true, name = "自动化-Offline")
@Composable
private fun AutomationOfflinePreview() = AutomationScreen(AutomationPreviewFixtures.offlineState) {}

@Preview(showBackground = true, name = "自动化-Unsupported")
@Composable
private fun AutomationUnsupportedPreview() = AutomationScreen(AutomationPreviewFixtures.unsupportedState) {}

@Preview(showBackground = true, name = "自动化-编辑器")
@Composable
private fun AutomationEditorPreview() = AutomationScreen(AutomationPreviewFixtures.editorState) {}

@Preview(showBackground = true, name = "自动化-运行记录")
@Composable
private fun AutomationRunsPreview() = AutomationScreen(AutomationPreviewFixtures.runsState) {}
