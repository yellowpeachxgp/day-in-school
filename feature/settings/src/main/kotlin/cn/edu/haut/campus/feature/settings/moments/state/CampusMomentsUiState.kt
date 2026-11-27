package cn.edu.haut.campus.feature.settings.moments.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CloudIdentityBindingEntity
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 失物招领/二手教材/防丢页外部数据不可变渲染快照。 */
@Immutable
data class CampusMomentsUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val prefs: AppPreferencesState = AppPreferencesState(),
    val courses: List<Course> = emptyList(),
    val studentProfile: StudentProfile? = null,
    val cloudBinding: CloudIdentityBindingEntity? = null
)
