package cn.edu.haut.campus.feature.settings.root.theme.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 设置「主题与外观」分区不可变渲染快照。 */
@Immutable
data class SettingsThemeUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val prefs: AppPreferencesState = AppPreferencesState()
)
