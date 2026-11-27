package cn.edu.haut.campus.feature.settings

import androidx.compose.runtime.Composable
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.dorm.DormElectricityRoute

/** 兼容入口：签名与拆分前一致，调用方不变。 */
@Composable
fun DormElectricityScreen(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    DormElectricityRoute(repository = repository, onBack = onBack)
}
