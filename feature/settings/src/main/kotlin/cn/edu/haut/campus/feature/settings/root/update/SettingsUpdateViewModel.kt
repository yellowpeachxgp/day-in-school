package cn.edu.haut.campus.feature.settings.root.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.academic.GitHubReleaseInfo
import cn.edu.haut.campus.core.academic.GitHubReleaseUpdater
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.feature.settings.root.update.state.SettingsUpdateUiAction
import cn.edu.haut.campus.feature.settings.root.update.state.SettingsUpdateUiState
import cn.edu.haut.campus.feature.settings.state.resetTransientForExit
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 版本更新分区状态 holder；只属于设置根页的更新卡片，不承担整页聚合。 */
class SettingsUpdateViewModel(
    private val repository: CampusRepository,
    private val currentInstalledVersion: String,
    private val apkDownloader: suspend (GitHubReleaseInfo, NetworkProxyConfig, (Long, Long) -> Unit) -> Result<File>,
    private val apkInstaller: (File) -> Result<Boolean>
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        SettingsUpdateUiState(currentInstalledVersion = currentInstalledVersion)
    )
    val uiState: StateFlow<SettingsUpdateUiState> = mutableUiState.asStateFlow()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var transientEpoch: Long = 0L

    init {
        viewModelScope.launch {
            repository.preferencesFlow.collectLatest { prefs ->
                mutableUiState.update { it.copy(prefs = prefs) }
            }
        }
    }

    fun onAction(action: SettingsUpdateUiAction) {
        when (action) {
            is SettingsUpdateUiAction.SetAutoCheckUpdateEnabled -> viewModelScope.launch {
                repository.setAutoCheckUpdateEnabled(action.enabled)
            }
            SettingsUpdateUiAction.CheckNow -> checkLatestRelease(manual = true)
            SettingsUpdateUiAction.DownloadOrInstall -> downloadOrInstall()
            SettingsUpdateUiAction.ShowReleaseNotes -> mutableUiState.update { it.copy(showReleaseNotesDialog = true) }
            SettingsUpdateUiAction.HideReleaseNotes -> mutableUiState.update { it.copy(showReleaseNotesDialog = false) }
        }
    }

    private fun updateIfCurrent(
        epoch: Long,
        transform: (SettingsUpdateUiState) -> SettingsUpdateUiState
    ) {
        if (epoch == transientEpoch) {
            mutableUiState.update(transform)
        }
    }

    private fun checkLatestRelease(manual: Boolean) {
        if (mutableUiState.value.isCheckingRelease) return
        val epoch = transientEpoch
        checkJob = viewModelScope.launch {
            val proxyConfig = mutableUiState.value.prefs.networkProxyConfig
            updateIfCurrent(epoch) {
                it.copy(
                    isCheckingRelease = true,
                    releaseStatusMessage = if (manual) {
                        "正在通过「" + proxyConfig.statusSummaryLabel() + "」检查更新..."
                    } else {
                        it.releaseStatusMessage
                    }
                )
            }
            val checkRes = GitHubReleaseUpdater.checkLatestRelease(
                currentVersionName = currentInstalledVersion,
                proxyConfig = proxyConfig
            )
            updateIfCurrent(epoch) { it.copy(isCheckingRelease = false) }
            checkRes.onSuccess { info ->
                updateIfCurrent(epoch) {
                    it.copy(
                        releaseInfo = info,
                        showReleaseNotesDialog = if (manual) true else it.showReleaseNotesDialog,
                        releaseStatusMessage = if (info.hasNewerVersion) {
                            if (manual) {
                                "发现新版本 " + info.tagName + "（" + info.resolvedViaRoute + "），点击右侧按钮下载安装。"
                            } else {
                                "发现新版本 " + info.tagName
                            }
                        } else if (manual) {
                            "当前 v" + currentInstalledVersion + " 已是最新版本（" + info.resolvedViaRoute + "）。"
                        } else {
                            "当前已是最新版本（v" + currentInstalledVersion + "）"
                        }
                    )
                }
            }.onFailure { err ->
                if (manual) {
                    updateIfCurrent(epoch) {
                        it.copy(releaseStatusMessage = "检查失败：" + (err.message ?: "请尝试在上方切换加速镜像或代理"))
                    }
                }
            }
        }
    }

    private fun downloadOrInstall() {
        val readyFile = mutableUiState.value.downloadedApkFile
        if (readyFile != null && readyFile.exists()) {
            val result = apkInstaller(readyFile)
            result.onSuccess { installed ->
                mutableUiState.update {
                    it.copy(
                        releaseStatusMessage = if (installed) {
                            "已打开安装界面，请确认安装。"
                        } else {
                            "请先允许「安装未知应用」后再点击安装。"
                        }
                    )
                }
            }.onFailure { err ->
                mutableUiState.update { it.copy(releaseStatusMessage = "无法打开安装器：" + err.message) }
            }
            return
        }
        if (mutableUiState.value.isDownloadingApk) return
        val epoch = transientEpoch
        downloadJob = viewModelScope.launch {
            val proxyConfig = mutableUiState.value.prefs.networkProxyConfig
            updateIfCurrent(epoch) {
                it.copy(
                    isDownloadingApk = true,
                    apkDownloadProgress = 0f,
                    releaseStatusMessage = "正在通过「" + proxyConfig.statusSummaryLabel() + "」获取下载地址..."
                )
            }
            val targetInfo = mutableUiState.value.releaseInfo
                ?: GitHubReleaseUpdater.checkLatestRelease(
                    currentVersionName = currentInstalledVersion,
                    proxyConfig = proxyConfig
                ).getOrNull()
            if (targetInfo == null) {
                updateIfCurrent(epoch) {
                    it.copy(
                        isDownloadingApk = false,
                        releaseStatusMessage = "获取版本信息失败，请在上方切换代理线路后重试。"
                    )
                }
                return@launch
            }
            updateIfCurrent(epoch) { it.copy(releaseInfo = targetInfo) }
            val dlRes = apkDownloader(targetInfo, proxyConfig) { dl, total ->
                if (total > 0L) {
                    val progress = dl.toFloat() / total.toFloat()
                    val dlMb = String.format("%.1f", dl / (1024.0 * 1024.0))
                    val totalMb = String.format("%.1f", total / (1024.0 * 1024.0))
                    updateIfCurrent(epoch) {
                        it.copy(
                            apkDownloadProgress = progress,
                            releaseStatusMessage = "正在加速下载 " + targetInfo.tagName + "：" + (progress * 100).toInt() + "% (" + dlMb + " / " + totalMb + " MB)"
                        )
                    }
                }
            }
            updateIfCurrent(epoch) { it.copy(isDownloadingApk = false) }
            dlRes.onSuccess { apkFile ->
                updateIfCurrent(epoch) { it.copy(downloadedApkFile = apkFile) }
                if (epoch != transientEpoch) return@onSuccess
                apkInstaller(apkFile).onSuccess { installed ->
                    updateIfCurrent(epoch) {
                        it.copy(
                            releaseStatusMessage = if (installed) {
                                "下载完成，正在打开安装界面。"
                            } else {
                                "下载完成，请允许「安装未知应用」后点击安装。"
                            }
                        )
                    }
                }.onFailure { err ->
                    updateIfCurrent(epoch) { it.copy(releaseStatusMessage = "打开安装失败：" + err.message) }
                }
            }.onFailure { err ->
                updateIfCurrent(epoch) {
                    it.copy(releaseStatusMessage = "下载失败：" + (err.message ?: "请检查网络或切换代理线路"))
                }
            }
        }
    }

    /**
     * 根设置页随底栏离开时调用。拆分前下载协程由 rememberCoroutineScope 取消，
     * 没有任何 singleton service/coordinator 会让它跨组合存活；因此这里取消检查/下载并防止迟到回调复活旧状态。
     */
    fun resetTransientStateOnExit() {
        transientEpoch += 1L
        checkJob?.cancel()
        downloadJob?.cancel()
        checkJob = null
        downloadJob = null
        mutableUiState.update { it.resetTransientForExit() }
    }

    override fun onCleared() {
        checkJob?.cancel()
        downloadJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(
            repository: CampusRepository,
            currentInstalledVersion: String,
            apkDownloader: suspend (GitHubReleaseInfo, NetworkProxyConfig, (Long, Long) -> Unit) -> Result<File>,
            apkInstaller: (File) -> Result<Boolean>
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsUpdateViewModel(repository, currentInstalledVersion, apkDownloader, apkInstaller)
            }
        }
    }
}
