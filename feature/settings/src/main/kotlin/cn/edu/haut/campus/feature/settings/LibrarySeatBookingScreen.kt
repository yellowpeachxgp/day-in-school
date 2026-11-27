package cn.edu.haut.campus.feature.settings

import androidx.compose.runtime.Composable
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.library.seat.LibrarySeatRoute

/** 兼容入口：签名与拆分前一致，调用方不变。 */
@Composable
fun LibrarySeatBookingScreen(
    onBack: () -> Unit,
    repository: CampusRepository? = null,
    initialViewMode: Int = 1,
    onNavigateToXpeachCloud: (() -> Unit)? = null
) {
    LibrarySeatRoute(
        onBack = onBack,
        repository = repository,
        initialViewMode = initialViewMode,
        onNavigateToXpeachCloud = onNavigateToXpeachCloud
    )
}
