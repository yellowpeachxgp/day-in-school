package cn.edu.haut.campus.feature.settings.root.home.state

/** 设置一级「我的」页一次性事件。 */
sealed interface SettingsHomeUiEffect {
    data object NavigateToImporter : SettingsHomeUiEffect
    data object RefreshWidgets : SettingsHomeUiEffect
    data class ShowFeedback(val message: String) : SettingsHomeUiEffect
}
