package cn.edu.haut.campus.feature.settings.schoolcenter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.ActiveSession
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.feature.settings.schoolcenter.state.SchoolCenterUiAction
import cn.edu.haut.campus.feature.settings.schoolcenter.state.SchoolCenterUiEffect
import cn.edu.haut.campus.feature.settings.extension.ExtensionPluginSlot
import cn.edu.haut.campus.feature.settings.schoolcenter.ui.SchoolCenterScreenContent
import cn.edu.haut.campus.feature.settings.state.rememberSettingsSessionViewModelStoreOwner

/**
 * 学校中心 Route：只负责装配 ViewModel、导航回调与剪贴板平台事件。
 *
 * 例外说明：这里有意使用静态 key，因为本页是跨账号管理面，必须持续展示全局身份列表；
 * 普通账号数据页不得复用此例外。静态 key 只存在于本次组合作用域 Owner 内，
 * 页面退出即清空；纯界面瞬态仍显式重置作为双保险。
 */
@Composable
fun SchoolCenterRoute(
    onBack: () -> Unit,
    directory: SchoolDirectory,
    onOpenOfflineImporter: (() -> Unit)? = null,
    onSelectSchool: ((SchoolProfile) -> Unit)? = null,
    sessions: AccountSessionCoordinator? = null,
    activeSession: ActiveSession? = null,
    onSwitchProfile: ((String) -> Unit)? = null
) {
    val sessionKey = "school-center"
    val viewModelStoreOwner = rememberSettingsSessionViewModelStoreOwner(sessionKey)
    val viewModel: SchoolCenterViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = sessionKey,
        factory = SchoolCenterViewModel.factory(sessions)
    )
    DisposableEffect(viewModel) {
        onDispose { viewModel.resetTransientStateOnExit() }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SchoolCenterUiEffect.CopyToClipboard -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val succeeded = clipboard != null
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(effect.label, effect.text))
                    }
                    viewModel.onAction(SchoolCenterUiAction.CopyContributionFeedback(succeeded))
                }
            }
        }
    }

    val extensionSlotContent: @Composable () -> Unit = {
        ExtensionPluginSlot(
            profileId = activeSession?.repository?.profileId.orEmpty(),
            schoolId = activeSession?.repository?.schoolId.orEmpty(),
            sessionGeneration = activeSession?.repository?.sessionGeneration,
            slotId = "campus.services"
        )
    }
    SchoolCenterScreenContent(
        state = state,
        directory = directory,
        onBack = onBack,
        onOpenOfflineImporter = onOpenOfflineImporter,
        onSelectSchool = onSelectSchool,
        activeSession = activeSession,
        onSwitchProfile = onSwitchProfile,
        onAction = viewModel::onAction,
        extensionSlotContent = extensionSlotContent
    )
}
