package cn.edu.haut.campus.feature.degree.state

/** 毕业进度看板的全部用户动作；UI 不得绕过动作直接改状态。 */
sealed interface DegreeUiAction {
    data object Refresh : DegreeUiAction
    data object OpenImportDialog : DegreeUiAction
    data object CloseImportDialog : DegreeUiAction
    data class ImportTextChanged(val text: String) : DegreeUiAction
    data object SubmitImport : DegreeUiAction
    data class ConfirmPlan(val importId: String) : DegreeUiAction
    data class ActivateImport(val importId: String) : DegreeUiAction
    data class ToggleGroupTrace(val groupId: String) : DegreeUiAction
    data class OpenOverride(val attemptId: String, val courseName: String) : DegreeUiAction
    data object CloseOverride : DegreeUiAction
    data class OverrideTargetChanged(val groupId: String?, val exclude: Boolean) : DegreeUiAction
    data class OverrideReasonChanged(val reason: String) : DegreeUiAction
    data object SubmitOverride : DegreeUiAction
    /** 对当前方案内已生效认定追加 RETRACT；attemptId 必须是成绩实体 ID，不是改名后的展示 courseKey。 */
    data class RetractOverride(val attemptId: String, val courseName: String) : DegreeUiAction
    data object DismissNotice : DegreeUiAction
}
