package cn.edu.haut.campus.feature.settings.schoolcenter.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.AccountProfile
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 学校中心不可变渲染快照；所有可变用户意图都经 SchoolCenterUiAction 回传。 */
@Immutable
data class SchoolCenterUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val query: String = "",
    val selectedSchoolId: String? = null,
    val showContribution: Boolean = false,
    val showCreateProfile: Boolean = false,
    val creatingProfile: Boolean = false,
    val profileFeedback: String? = null,
    val copyFeedback: String? = null,
    val profiles: List<AccountProfile> = emptyList(),
    val sessionsAvailable: Boolean = false
)
