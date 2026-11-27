package cn.edu.haut.campus.feature.settings.library.catalog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.feature.settings.library.catalog.state.LibraryCatalogUiEffect
import cn.edu.haut.campus.feature.settings.library.catalog.ui.LibraryCatalogScreenContent

/**
 * 馆藏检索 Route：只装配 ViewModel 并处理 Toast / 剪贴板。
 *
 * 例外说明：本页不注入 Repository，也不读取任何 Profile/账号数据，只检索学校公共 OPAC；
 * 因此 ViewModel 有意不按 profileId/generation 加 key。其界面瞬态仍在退出时显式重置。
 */
@Composable
fun LibraryCatalogRoute(onBack: () -> Unit) {
    val viewModel: LibraryCatalogViewModel = viewModel(factory = LibraryCatalogViewModel.factory())
    DisposableEffect(viewModel) {
        onDispose { viewModel.resetTransientStateOnExit() }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is LibraryCatalogUiEffect.CopyCallNo -> {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("索书号", effect.callNo))
                    Toast.makeText(context, "已复制索书号：" + effect.callNo, Toast.LENGTH_SHORT).show()
                }
                is LibraryCatalogUiEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LibraryCatalogScreenContent(
        state = state,
        onBack = onBack,
        onAction = viewModel::onAction
    )
}
