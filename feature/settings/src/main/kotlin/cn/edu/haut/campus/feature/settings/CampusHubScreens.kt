package cn.edu.haut.campus.feature.settings

import androidx.compose.runtime.Composable
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.feature.settings.emptyclassroom.EmptyClassroomRoute
import cn.edu.haut.campus.feature.settings.hub.CampusCommunityRoute
import java.time.ZonedDateTime

/** 兼容入口：两个公开 Composable 的签名均与拆分前一致，调用方不变。 */
@Composable
fun EmptyClassroomScreen(
    repository: CampusRepository,
    onNavigateToMapWithRoom: (String) -> Unit,
    onBack: () -> Unit,
    nowProvider: () -> ZonedDateTime = { ZonedDateTime.now(HAUT_ZONE_ID) }
) {
    EmptyClassroomRoute(
        repository = repository,
        onNavigateToMapWithRoom = onNavigateToMapWithRoom,
        onBack = onBack,
        nowProvider = nowProvider
    )
}

@Composable
fun CampusCommunityScreen(
    repository: CampusRepository,
    initialTab: Int = 0,
    onOpenMoments: () -> Unit = {},
    onBack: () -> Unit
) {
    CampusCommunityRoute(
        repository = repository,
        initialTab = initialTab,
        onOpenMoments = onOpenMoments,
        onBack = onBack
    )
}
