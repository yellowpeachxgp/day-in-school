package cn.edu.haut.campus.feature.timetable

import androidx.compose.runtime.Composable
import cn.edu.haut.campus.core.database.CampusRepository

/**
 * 公开兼容入口。签名必须与拆分前逐字一致；调用方仍按 Repository + 导航回调使用，
 * 实际装配已迁移到 [TimetableRoute] → [TimetableViewModel] → state/ui。
 */
@Composable
fun TimetableScreen(
    repository: CampusRepository,
    onNavigateToImporter: () -> Unit,
    onNavigateToMapWithRoom: (String) -> Unit,
    resetTrigger: Int = 0
) {
    TimetableRoute(
        repository = repository,
        onNavigateToImporter = onNavigateToImporter,
        onNavigateToMapWithRoom = onNavigateToMapWithRoom,
        resetTrigger = resetTrigger
    )
}
