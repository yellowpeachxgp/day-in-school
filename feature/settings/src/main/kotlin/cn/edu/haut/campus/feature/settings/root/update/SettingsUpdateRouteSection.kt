package cn.edu.haut.campus.feature.settings.root.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.academic.GitHubReleaseUpdater
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.root.update.ui.SettingsUpdateSectionContent
import cn.edu.haut.campus.feature.settings.state.SettingsTransientResetRegistry
import cn.edu.haut.campus.feature.settings.state.settingsSessionKey

/** 设置根页「版本更新」分区 Route；只装配本分区的状态 holder 与平台下载/安装能力。 */
@Composable
fun SettingsUpdateRouteSection(
    repository: CampusRepository,
    currentInstalledVersion: String,
    transientResetRegistry: SettingsTransientResetRegistry,
    viewModelStoreOwner: ViewModelStoreOwner
) {
    val context = LocalContext.current
    val sessionKey = "settings-update-" + settingsSessionKey(repository.profileId, repository.sessionGeneration)
    val viewModel: SettingsUpdateViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = sessionKey,
        factory = SettingsUpdateViewModel.factory(
            repository = repository,
            currentInstalledVersion = currentInstalledVersion,
            apkDownloader = { info, proxy, onProgress ->
                GitHubReleaseUpdater.downloadReleaseApk(
                    context = context,
                    releaseInfo = info,
                    proxyConfig = proxy,
                    onProgress = onProgress
                )
            },
            apkInstaller = { file -> GitHubReleaseUpdater.triggerInstallApk(context, file) }
        )
    )
    // 与拆分前根级 remember 对齐：只在根设置页退出时重置/取消，不在“我的 ⇄ 设置”内部切换时重置。
    DisposableEffect(transientResetRegistry, viewModel) {
        transientResetRegistry.register(viewModel) { viewModel.resetTransientStateOnExit() }
        onDispose { }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsUpdateSectionContent(state = state, onAction = viewModel::onAction)
}
