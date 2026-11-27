package cn.edu.haut.campus.feature.settings

import androidx.compose.runtime.Composable
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.moments.CampusMomentsRoute

/** 兼容入口：签名与拆分前一致，调用方不变。 */
@Composable
fun CampusMomentsScreen(
    repository: CampusRepository,
    initialTab: Int = 0,
    onBack: () -> Unit
) {
    CampusMomentsRoute(repository = repository, initialTab = initialTab, onBack = onBack)
}
