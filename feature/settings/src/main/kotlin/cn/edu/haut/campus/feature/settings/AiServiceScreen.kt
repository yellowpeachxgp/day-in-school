package cn.edu.haut.campus.feature.settings

import androidx.compose.runtime.Composable
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.ai.AiServiceRoute

/** 兼容入口：我的 → AI 服务。 */
@Composable
fun AiServiceScreen(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    AiServiceRoute(repository = repository, onBack = onBack)
}
