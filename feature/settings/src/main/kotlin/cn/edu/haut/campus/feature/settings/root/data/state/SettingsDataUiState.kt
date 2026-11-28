package cn.edu.haut.campus.feature.settings.root.data.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 设置「外观、隐私与数据」分区不可变渲染快照。 */
@Immutable
data class SettingsDataUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val prefs: AppPreferencesState = AppPreferencesState()
)
