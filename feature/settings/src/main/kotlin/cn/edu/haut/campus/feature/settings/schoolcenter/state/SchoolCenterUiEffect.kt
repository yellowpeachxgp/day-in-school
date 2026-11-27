package cn.edu.haut.campus.feature.settings.schoolcenter.state

/** 学校中心一次性平台事件。 */
sealed interface SchoolCenterUiEffect {
    data class CopyToClipboard(val label: String, val text: String) : SchoolCenterUiEffect
}
