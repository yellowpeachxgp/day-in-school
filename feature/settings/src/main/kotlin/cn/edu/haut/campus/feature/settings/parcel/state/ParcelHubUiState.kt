package cn.edu.haut.campus.feature.settings.parcel.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.InstalledExtensionPluginEntity
import cn.edu.haut.campus.core.database.ParcelItemEntity
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 快递聚合页外部数据不可变渲染快照。 */
@Immutable
data class ParcelHubUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val parcels: List<ParcelItemEntity> = emptyList(),
    val plugins: List<InstalledExtensionPluginEntity> = emptyList()
)
