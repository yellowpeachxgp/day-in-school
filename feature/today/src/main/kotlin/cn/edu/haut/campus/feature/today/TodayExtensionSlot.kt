package cn.edu.haut.campus.feature.today

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import cn.edu.haut.campus.core.database.ExtensionPluginRuntimeRegistry
import cn.edu.haut.campus.core.uiruntime.ExtensionSlotHost
import cn.edu.haut.campus.core.uiruntime.ResolvedDeclarativeCommand
import cn.edu.haut.campus.core.uiruntime.VerifiedShowDataExecutor
import cn.edu.haut.campus.core.uiruntime.androidDeclarativeCommandHandler
import cn.edu.haut.campus.core.uiruntime.rememberExtensionUiContributions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** home.cards 宿主槽位绑定；只读取 W6-01a 验签安装状态，不走 CampusRepository 生命周期兼容外观。 */
@Composable
fun TodayExtensionSlot(
    profileId: String,
    schoolId: String,
    sessionGeneration: Long?,
    onNavigateToParcelHub: () -> Unit,
    onNavigateToLibrarySeats: () -> Unit,
    onNavigateToLibraryCatalog: () -> Unit
) {
    if (profileId.isBlank() || schoolId.isBlank() || sessionGeneration == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = remember(context, profileId) {
        ExtensionPluginRuntimeRegistry.installerForContext(context, profileId)
    }
    val contributions = rememberExtensionUiContributions(installer, schoolId)
    val showData: (ResolvedDeclarativeCommand) -> Unit = remember(installer, profileId, schoolId, sessionGeneration) {
        { command ->
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    VerifiedShowDataExecutor.readUtf8(installer, schoolId, command)
                }
                Toast.makeText(
                    context,
                    if (text == null) "签名数据读取失败，已拒绝展示" else text.take(600),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    val commandHandler = remember(context, installer, showData, onNavigateToParcelHub) {
        androidDeclarativeCommandHandler(
            context = context,
            onOpenHostRoute = { route ->
                when (route) {
                    "parcel.hub" -> onNavigateToParcelHub()
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
        allowedHostRoutes = setOf("parcel.hub", "library.seats", "library.catalog"),
        onCommand = commandHandler
    )
}
