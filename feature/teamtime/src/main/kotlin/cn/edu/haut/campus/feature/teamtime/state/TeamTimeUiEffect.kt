package cn.edu.haut.campus.feature.teamtime.state

/** 组队找时间一次性导航/平台事件。 */
sealed interface TeamTimeUiEffect {
    data object NavigateBack : TeamTimeUiEffect
    data object NavigateToCloudLogin : TeamTimeUiEffect
}
