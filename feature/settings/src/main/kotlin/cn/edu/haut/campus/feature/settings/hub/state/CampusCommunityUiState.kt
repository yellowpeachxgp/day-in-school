package cn.edu.haut.campus.feature.settings.hub.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 社团/竞赛/频道页外部数据不可变渲染快照。 */
@Immutable
data class CampusCommunityUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val prefs: AppPreferencesState = AppPreferencesState(),
    val studentProfile: StudentProfile? = null
)
