package cn.edu.haut.campus.feature.settings.focus.state

/** 自习专注用户意图。 */
sealed interface StudyFocusUiAction {
    data class UpdateCustomMinutesInput(val value: String) : StudyFocusUiAction
    data class SetSelectedFocusMinutes(val minutes: Int) : StudyFocusUiAction
    data class StartFocusSession(val minutes: Int) : StudyFocusUiAction
    data object StopActiveFocusSession : StudyFocusUiAction
    data object TriggerTenSecondTestAlarm : StudyFocusUiAction
    data object ClearAllFocusData : StudyFocusUiAction
    data object ShowInvalidMinutesError : StudyFocusUiAction
}
