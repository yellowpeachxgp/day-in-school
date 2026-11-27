package cn.edu.haut.campus.feature.settings.library.seat.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CloudIdentityBindingEntity
import cn.edu.haut.campus.core.database.LibraryBookingPlanEntity
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 图书馆选座/抢座页外部数据不可变渲染快照。 */
@Immutable
data class LibrarySeatUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val prefs: AppPreferencesState? = null,
    val bookingPlans: List<LibraryBookingPlanEntity> = emptyList(),
    val cloudBinding: CloudIdentityBindingEntity? = null
)
