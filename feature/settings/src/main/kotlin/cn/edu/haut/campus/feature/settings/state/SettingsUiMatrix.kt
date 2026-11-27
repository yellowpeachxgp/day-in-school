package cn.edu.haut.campus.feature.settings.state

import androidx.compose.runtime.Immutable

/**
 * W0-06c 统一状态矩阵标记。
 *
 * 拆分首阶段只把既有外部数据聚合进不可变 UiState，不改变任何页面的既有渲染分支；
 * 该标记用于 PreviewFixtures 逐项固定 Loading / Empty / Content / Partial / Stale /
 * Error / NeedsPermission / NeedsLogin / Offline / Unsupported 十种契约状态。各页面
 * 仍按拆分前内容渲染，避免因行为不变卡夹带视觉或文案变化。
 */
@Immutable
data class SettingsUiMatrix(
    val status: Status,
    val message: String? = null,
    val retryable: Boolean = false
) {
    enum class Status {
        LOADING,
        EMPTY,
        CONTENT,
        PARTIAL,
        STALE,
        ERROR,
        NEEDS_PERMISSION,
        NEEDS_LOGIN,
        OFFLINE,
        UNSUPPORTED
    }

    companion object {
        val Loading = SettingsUiMatrix(Status.LOADING)
        val Empty = SettingsUiMatrix(Status.EMPTY)
        val Content = SettingsUiMatrix(Status.CONTENT)
        val Partial = SettingsUiMatrix(Status.PARTIAL)
        val Stale = SettingsUiMatrix(Status.STALE)
        fun error(retryable: Boolean, message: String? = null) =
            SettingsUiMatrix(Status.ERROR, message = message, retryable = retryable)
        val NeedsPermission = SettingsUiMatrix(Status.NEEDS_PERMISSION)
        val NeedsLogin = SettingsUiMatrix(Status.NEEDS_LOGIN)
        val Offline = SettingsUiMatrix(Status.OFFLINE)
        val Unsupported = SettingsUiMatrix(Status.UNSUPPORTED)
    }
}
