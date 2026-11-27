package cn.edu.haut.campus.feature.settings.root.network.state

/** 网络代理分区一次性事件。 */
sealed interface SettingsNetworkUiEffect {
    data class ShowFeedback(val message: String) : SettingsNetworkUiEffect
}
