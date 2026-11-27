package cn.edu.haut.campus.feature.settings.root.update.state

/** 设置「版本更新」分区用户意图。 */
sealed interface SettingsUpdateUiAction {
    data class SetAutoCheckUpdateEnabled(val enabled: Boolean) : SettingsUpdateUiAction
    data object CheckNow : SettingsUpdateUiAction
    data object DownloadOrInstall : SettingsUpdateUiAction
    data object ShowReleaseNotes : SettingsUpdateUiAction
    data object HideReleaseNotes : SettingsUpdateUiAction
}
