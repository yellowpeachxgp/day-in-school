package cn.edu.haut.campus.core.uiruntime

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import cn.edu.haut.campus.core.packages.v2.ExtensionPluginInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Collect the shared installer revision and load verified declarative contributions off the UI thread. */
@Composable
fun rememberExtensionUiContributions(
    installer: ExtensionPluginInstaller,
    schoolId: String
): List<ExtensionUiContribution> {
    val revision by installer.revision.collectAsState()
    val loader = remember(installer, schoolId) { ExtensionUiPackageLoader(installer, schoolId) }
    val contributions by produceState<List<ExtensionUiContribution>>(initialValue = emptyList(), loader, revision) {
        value = withContext(Dispatchers.IO) { loader.loadContributions() }
    }
    return contributions
}

/** Android execution adapter for the three declarative command kinds; unknown types never reach this adapter. */
fun androidDeclarativeCommandHandler(
    context: Context,
    onOpenHostRoute: (String) -> Unit = {},
    onShowData: (ResolvedDeclarativeCommand) -> Unit = {}
): DeclarativeCommandHandler = DeclarativeCommandHandler { command ->
    when (command.type) {
        "open_url" -> runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(command.target)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
        "open_host_route" -> onOpenHostRoute(command.target)
        "show_data" -> onShowData(command)
    }
}
