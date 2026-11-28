package cn.edu.haut.campus.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.ActiveSession
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.feature.settings.schoolcenter.SchoolCenterRoute

/**
 * 兼容入口：签名与拆分前完全一致，调用方不变；实现装配在 SchoolCenterRoute。
 */
@Composable
fun SchoolCenterScreen(
    onBack: () -> Unit,
    directory: SchoolDirectory = remember { SchoolDirectory.builtIn() },
    onOpenOfflineImporter: (() -> Unit)? = null,
    onSelectSchool: ((SchoolProfile) -> Unit)? = null,
    sessions: AccountSessionCoordinator? = null,
    activeSession: ActiveSession? = null,
    onSwitchProfile: ((String) -> Unit)? = null
) {
    SchoolCenterRoute(
        onBack = onBack,
        directory = directory,
        onOpenOfflineImporter = onOpenOfflineImporter,
        onSelectSchool = onSelectSchool,
        sessions = sessions,
        activeSession = activeSession,
        onSwitchProfile = onSwitchProfile
    )
}
