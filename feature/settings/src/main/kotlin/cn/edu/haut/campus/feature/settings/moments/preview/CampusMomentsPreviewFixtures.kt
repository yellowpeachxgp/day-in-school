package cn.edu.haut.campus.feature.settings.moments.preview

import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix
import cn.edu.haut.campus.feature.settings.moments.state.CampusMomentsUiState

/**
 * W0-06c 状态矩阵 fixtures。
 *
 * 覆盖统一矩阵：Loading、Empty、Content、Partial、Stale、可重试/不可重试 Error、
 * NeedsPermission、NeedsLogin、Offline、Unsupported。拆分首阶段保留原渲染分支，
 * 状态标记用于截图与后续 neon 状态语义核对。
 */
object CampusMomentsPreviewFixtures {
    val loading = CampusMomentsUiState(matrix = SettingsUiMatrix.Loading)
    val empty = CampusMomentsUiState(matrix = SettingsUiMatrix.Empty)
    val content = CampusMomentsUiState(matrix = SettingsUiMatrix.Content)
    val partial = CampusMomentsUiState(matrix = SettingsUiMatrix.Partial)
    val stale = CampusMomentsUiState(matrix = SettingsUiMatrix.Stale)
    val errorRetryable = CampusMomentsUiState(matrix = SettingsUiMatrix.error(retryable = true, message = "预览错误（可重试）"))
    val errorTerminal = CampusMomentsUiState(matrix = SettingsUiMatrix.error(retryable = false, message = "预览错误（不可重试）"))
    val needsPermission = CampusMomentsUiState(matrix = SettingsUiMatrix.NeedsPermission)
    val needsLogin = CampusMomentsUiState(matrix = SettingsUiMatrix.NeedsLogin)
    val offline = CampusMomentsUiState(matrix = SettingsUiMatrix.Offline)
    val unsupported = CampusMomentsUiState(matrix = SettingsUiMatrix.Unsupported)

    val matrixStates: Map<String, CampusMomentsUiState> = linkedMapOf(
        "loading" to loading,
        "empty" to empty,
        "content" to content,
        "partial" to partial,
        "stale" to stale,
        "error_retryable" to errorRetryable,
        "error_terminal" to errorTerminal,
        "needs_permission" to needsPermission,
        "needs_login" to needsLogin,
        "offline" to offline,
        "unsupported" to unsupported
    )
}
