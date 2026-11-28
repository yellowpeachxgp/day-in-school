package cn.edu.haut.campus.feature.settings

import androidx.compose.runtime.Composable
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.parcel.ParcelHubRoute

/** 兼容入口：签名与拆分前一致，调用方不变。 */
@Composable
fun ParcelHubScreen(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    ParcelHubRoute(repository = repository, onBack = onBack)
}
