package cn.edu.haut.campus.feature.settings.root.home.state

import cn.edu.haut.campus.core.model.StudentProfile

/** 设置一级「我的」页用户意图。 */
sealed interface SettingsHomeUiAction {
    data object PerformDynamicSync : SettingsHomeUiAction
    data class SelectCurrentTerm(val termId: String) : SettingsHomeUiAction
    data class SaveStudentProfile(val profile: StudentProfile) : SettingsHomeUiAction
    data object ShowEditProfileDialog : SettingsHomeUiAction
    data object HideEditProfileDialog : SettingsHomeUiAction
    data object ShowImportHistoryDialog : SettingsHomeUiAction
    data object HideImportHistoryDialog : SettingsHomeUiAction
}
