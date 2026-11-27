package cn.edu.haut.campus.feature.settings.extension

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.ExtensionPluginRuntimeRegistry
import cn.edu.haut.campus.core.uiruntime.ExtensionSlotHost
import cn.edu.haut.campus.core.uiruntime.androidDeclarativeCommandHandler
import cn.edu.haut.campus.core.uiruntime.rememberExtensionUiContributions
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * feature/settings 内六个宿主槽位共用的窄绑定。它直接使用 W6-01a 安装器 DI，
 * 不经过 CampusRepository 兼容外观执行插件生命周期或读取插件数据。
 */
@Composable
fun ExtensionPluginSlot(
    repository: CampusRepository?,
    slotId: String,
    modifier: Modifier = Modifier,
    showEmptyState: Boolean = false,
    onOpenHostRoute: (String) -> Unit = {}
) {
    if (repository == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = remember(context, repository.profileId) {
        ExtensionPluginRuntimeRegistry.installerForContext(context, repository.profileId)
    }
    val contributions = rememberExtensionUiContributions(installer, repository.schoolId)
    val showData: (String, String) -> Unit = remember(installer, repository.profileId, repository.schoolId, repository.sessionGeneration) {
        { pluginId, artifactPath ->
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        val verified = installer.verifiedStoredPackage(repository.schoolId, pluginId)
                        verified.artifactMap[artifactPath]?.let { String(it, StandardCharsets.UTF_8) }
                    }.getOrNull()
                }
                Toast.makeText(
                    context,
                    if (text == null) "签名数据读取失败，已拒绝展示" else text.take(600),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    val commandHandler = remember(context, installer, onOpenHostRoute, showData) {
        androidDeclarativeCommandHandler(
            context = context,
            onOpenHostRoute = onOpenHostRoute,
            onShowData = showData
        )
    }
    ExtensionSlotHost(
        slotId = slotId,
        contributions = contributions,
        modifier = modifier,
        showEmptyState = showEmptyState,
        onCommand = commandHandler
    )
}
