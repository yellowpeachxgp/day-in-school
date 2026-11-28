package cn.edu.haut.campus.feature.settings.cloud.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.CloudIdentityBindingEntity
import cn.edu.haut.campus.core.database.InstalledExtensionPluginEntity
import cn.edu.haut.campus.core.database.LibraryBookingPlanEntity
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 云账号与扩展页外部数据不可变渲染快照。 */
@Immutable
data class XPeachCloudUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val cloudBinding: CloudIdentityBindingEntity? = null,
    val bookingPlans: List<LibraryBookingPlanEntity> = emptyList(),
    val plugins: List<InstalledExtensionPluginEntity> = emptyList()
)
