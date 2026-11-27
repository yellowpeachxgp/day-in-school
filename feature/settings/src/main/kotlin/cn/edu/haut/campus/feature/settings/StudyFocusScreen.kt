package cn.edu.haut.campus.feature.settings

import androidx.compose.runtime.Composable
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.focus.StudyFocusRoute

/** 兼容入口：签名与拆分前一致，调用方不变。 */
@Composable
fun StudyFocusScreen(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    StudyFocusRoute(repository = repository, onBack = onBack)
}
