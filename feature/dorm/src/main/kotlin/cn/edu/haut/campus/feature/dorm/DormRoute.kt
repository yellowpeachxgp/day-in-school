package cn.edu.haut.campus.feature.dorm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.core.database.ActiveSession
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.feature.dorm.state.DormScreenStatus
import cn.edu.haut.campus.feature.dorm.state.DormUiAction
import cn.edu.haut.campus.feature.dorm.state.DormUiState
import cn.edu.haut.campus.feature.dorm.ui.DormScreen

/** 宿舍协作骨架 Route：组合作用域内装配 client/VM；身份世代变化时整棵树重建。 */
@Composable
fun DormRoute(
    activeSession: ActiveSession,
    onBack: () -> Unit,
    onOpenElectricity: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val repository = activeSession.repository
    val sessionKey = dormSessionKey(activeSession.profileId, activeSession.generation)
    val viewModelStoreOwner = rememberDormViewModelStoreOwner(sessionKey)
    val prefs by repository.preferencesFlow.collectAsStateWithLifecycle(initialValue = null)
    val cloudBaseKey = NetworkProxyConfig.normalizeCloudApiBaseUrl(prefs?.networkProxyConfig?.customCloudApiBaseUrl)
    val runtimeResult by produceState<Result<DormCollaborationRuntime>?>(initialValue = null, sessionKey, cloudBaseKey) {
        value = runCatching {
            DormCollaborationAssembly.create(
                repository = repository,
                filesDir = context.filesDir,
            )
        }
    }

    val result = runtimeResult
    if (result == null) {
        DormScreen(
            state = DormUiState(status = DormScreenStatus.LOADING, loading = true),
            onAction = { action -> if (action == DormUiAction.Back) onBack() },
        )
    } else if (result.isSuccess) {
        val runtime = result.getOrThrow()
        val viewModel: DormViewModel = viewModel(
            viewModelStoreOwner = viewModelStoreOwner,
            key = sessionKey,
            factory = DormViewModel.factory(
                port = runtime.collaborationPort,
                utilityStatusPort = runtime.utilityStatusPort,
                schoolId = activeSession.school.id.value,
                currentCloudUserId = runtime.currentCloudUserId,
            ),
        )
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        DormScreen(
            state = state,
            onAction = { action ->
                when (action) {
                    DormUiAction.Back -> onBack()
                    DormUiAction.OpenElectricity -> onOpenElectricity()
                    else -> viewModel.dispatch(action)
                }
            },
        )
    } else {
        val error = result.exceptionOrNull()
        DormScreen(
            state = DormUiState(
                loading = false,
                status = DormScreenStatus.ERROR_NON_RETRYABLE,
                statusDetail = "宿舍协作装配失败：" + (error?.message ?: "请重新进入页面"),
            ),
            onAction = { action -> if (action == DormUiAction.Back) onBack() },
        )
    }
}
