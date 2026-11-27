package cn.edu.haut.campus.feature.settings.root.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.root.network.state.SettingsNetworkUiAction
import cn.edu.haut.campus.feature.settings.root.network.state.SettingsNetworkUiEffect
import cn.edu.haut.campus.feature.settings.root.ui.NetworkProxyAndCloudConfigSection

/** 设置根页「网络与更新线路」分区 Route。 */
@Composable
fun SettingsNetworkRouteSection(
    repository: CampusRepository,
    onFeedback: (String) -> Unit
) {
    val viewModel: SettingsNetworkViewModel = viewModel(
        key = "settings-network-" + repository.profileId + "-" + repository.sessionGeneration,
        factory = SettingsNetworkViewModel.factory(repository)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, onFeedback) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SettingsNetworkUiEffect.ShowFeedback -> onFeedback(effect.message)
            }
        }
    }
    NetworkProxyAndCloudConfigSection(
        proxyConfig = state.prefs.networkProxyConfig,
        onSaveProxyConfig = { viewModel.onAction(SettingsNetworkUiAction.SaveProxyConfig(it)) },
        onResetProxyConfig = { viewModel.onAction(SettingsNetworkUiAction.ResetProxyConfig) }
    )
}
