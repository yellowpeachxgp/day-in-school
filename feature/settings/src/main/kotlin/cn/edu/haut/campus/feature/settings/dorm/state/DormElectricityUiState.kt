package cn.edu.haut.campus.feature.settings.dorm.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.campusservices.UtilityDailySample
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.model.UtilityMeterDomain
import cn.edu.haut.campus.core.model.UtilityOnlineRoomDomain
import cn.edu.haut.campus.core.model.UtilityReadingDomain
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 宿舍水电外部数据不可变渲染快照。 */
@Immutable
data class DormElectricityUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val prefs: AppPreferencesState = AppPreferencesState(),
    val onlineRooms: List<UtilityOnlineRoomDomain> = emptyList(),
    val dailySamples: List<UtilityDailySample> = emptyList(),
    val meters: List<UtilityMeterDomain> = emptyList(),
    val readings: List<UtilityReadingDomain> = emptyList()
)
