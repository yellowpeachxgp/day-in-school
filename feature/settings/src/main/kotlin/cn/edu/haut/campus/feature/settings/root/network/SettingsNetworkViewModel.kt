package cn.edu.haut.campus.feature.settings.root.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.root.network.state.SettingsNetworkUiAction
import cn.edu.haut.campus.feature.settings.root.network.state.SettingsNetworkUiEffect
import cn.edu.haut.campus.feature.settings.root.network.state.SettingsNetworkUiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 网络代理分区状态 holder；不承担设置整页聚合。 */
class SettingsNetworkViewModel(
    private val repository: CampusRepository
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SettingsNetworkUiState())
    val uiState: StateFlow<SettingsNetworkUiState> = mutableUiState.asStateFlow()
    private val mutableEffects = MutableSharedFlow<SettingsNetworkUiEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<SettingsNetworkUiEffect> = mutableEffects.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.preferencesFlow.collectLatest { prefs ->
                mutableUiState.update { it.copy(prefs = prefs) }
            }
        }
    }

    fun onAction(action: SettingsNetworkUiAction) {
        when (action) {
            is SettingsNetworkUiAction.SaveProxyConfig -> viewModelScope.launch {
                repository.updateNetworkProxyConfig(action.config)
                mutableEffects.emit(
                    SettingsNetworkUiEffect.ShowFeedback(
                        "已更新代理与云服务配置：" + action.config.statusSummaryLabel()
                    )
                )
            }
            SettingsNetworkUiAction.ResetProxyConfig -> viewModelScope.launch {
                repository.resetNetworkProxyConfig()
                mutableEffects.emit(SettingsNetworkUiEffect.ShowFeedback("已恢复默认加速镜像配置"))
            }
        }
    }

    companion object {
        fun factory(repository: CampusRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsNetworkViewModel(repository) }
        }
    }
}
