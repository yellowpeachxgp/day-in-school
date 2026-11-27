package cn.edu.haut.campus.feature.today

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

/** home.cards 宿主槽位绑定；只读取 W6-01a 验签安装状态，不走 CampusRepository 生命周期兼容外观。 */
@Composable
fun TodayExtensionSlot(
    repository: CampusRepository,
    onNavigateToParcelHub: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLibrarySeats: () -> Unit,
    onNavigateToLibraryCatalog: () -> Unit
) {
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
                        installer.verifiedStoredPackage(repository.schoolId, pluginId).artifactMap[artifactPath]
                            ?.let { String(it, StandardCharsets.UTF_8) }
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
    val commandHandler = remember(context, installer, showData, onNavigateToParcelHub, onNavigateToSettings) {
        androidDeclarativeCommandHandler(
            context = context,
            onOpenHostRoute = { route ->
                when (route) {
                    "parcel.hub" -> onNavigateToParcelHub()
                    "settings.extensions" -> onNavigateToSettings()
                    "library.seats" -> onNavigateToLibrarySeats()
                    "library.catalog" -> onNavigateToLibraryCatalog()
                }
            },
            onShowData = showData
        )
    }
    ExtensionSlotHost(
        slotId = "home.cards",
        contributions = contributions,
        onCommand = commandHandler
    )
}
