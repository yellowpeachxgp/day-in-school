package cn.edu.haut.campus.feature.settings.root.network.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 设置「网络与更新线路」分区不可变渲染快照。 */
@Immutable
data class SettingsNetworkUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val prefs: AppPreferencesState = AppPreferencesState()
)
