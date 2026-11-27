package cn.edu.haut.campus.feature.settings.root.update.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.academic.GitHubReleaseInfo
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix
import java.io.File

/** 设置「版本更新」分区不可变渲染快照。 */
@Immutable
data class SettingsUpdateUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val prefs: AppPreferencesState = AppPreferencesState(),
    val currentInstalledVersion: String = "0.18.0",
    val isCheckingRelease: Boolean = false,
    val releaseInfo: GitHubReleaseInfo? = null,
    val isDownloadingApk: Boolean = false,
    val apkDownloadProgress: Float = 0f,
    val releaseStatusMessage: String? = null,
    val downloadedApkFile: File? = null,
    val showReleaseNotesDialog: Boolean = false
)
