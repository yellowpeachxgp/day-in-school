package cn.edu.haut.campus.feature.settings.root.network.state

import cn.edu.haut.campus.core.model.NetworkProxyConfig

/** 网络代理分区用户意图。 */
sealed interface SettingsNetworkUiAction {
    data class SaveProxyConfig(val config: NetworkProxyConfig) : SettingsNetworkUiAction
    data object ResetProxyConfig : SettingsNetworkUiAction
}
