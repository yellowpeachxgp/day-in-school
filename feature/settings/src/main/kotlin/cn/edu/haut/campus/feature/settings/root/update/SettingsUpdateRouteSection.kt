package cn.edu.haut.campus.feature.settings.root.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.edu.haut.campus.feature.settings.root.update.ui.SettingsUpdateSectionContent
import cn.edu.haut.campus.feature.settings.state.SettingsTransientResetRegistry

/** 设置根页「版本更新」分区 Route；根壳创建 ViewModel，这里只登记根级退出重置并装配纯 UI。 */
@Composable
fun SettingsUpdateRouteSection(
    transientResetRegistry: SettingsTransientResetRegistry,
    viewModel: SettingsUpdateViewModel
) {
    // 与拆分前根级 remember 对齐：只在根设置页退出时重置/取消，不在“我的 ⇄ 设置”内部切换时重置。
    DisposableEffect(transientResetRegistry, viewModel) {
        transientResetRegistry.register(viewModel) { viewModel.resetTransientStateOnExit() }
        onDispose { }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsUpdateSectionContent(state = state, onAction = viewModel::onAction)
}
