package cn.edu.haut.campus.feature.settings.root.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.academic.GitHubReleaseUpdater
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.root.update.ui.SettingsUpdateSectionContent

/** 设置根页「版本更新」分区 Route；只装配本分区的状态 holder 与平台下载/安装能力。 */
@Composable
fun SettingsUpdateRouteSection(
    repository: CampusRepository,
    currentInstalledVersion: String
) {
    val context = LocalContext.current
    val viewModel: SettingsUpdateViewModel = viewModel(
        key = "settings-update-" + repository.profileId + "-" + repository.sessionGeneration,
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
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsUpdateSectionContent(state = state, onAction = viewModel::onAction)
}
