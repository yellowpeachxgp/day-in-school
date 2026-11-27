package cn.edu.haut.campus.feature.settings

import androidx.compose.runtime.Composable
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.cloud.XPeachCloudRoute

/** 兼容入口：签名与拆分前一致，调用方不变。 */
@Composable
fun XPeachCloudAccountScreen(
    repository: CampusRepository,
    onBack: () -> Unit,
    onNavigateToCloudSeatBooking: () -> Unit = {}
) {
    XPeachCloudRoute(
        repository = repository,
        onBack = onBack,
        onNavigateToCloudSeatBooking = onNavigateToCloudSeatBooking
    )
}
