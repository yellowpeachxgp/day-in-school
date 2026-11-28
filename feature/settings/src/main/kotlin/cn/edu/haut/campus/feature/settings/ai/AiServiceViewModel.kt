package cn.edu.haut.campus.feature.settings.ai

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.ai.AiEndpoint
import cn.edu.haut.campus.core.ai.AiError
import cn.edu.haut.campus.core.ai.AiKeyStatus
import cn.edu.haut.campus.core.ai.AiMessage
import cn.edu.haut.campus.core.ai.AiMessageRole
import cn.edu.haut.campus.core.ai.AiProviderException
import cn.edu.haut.campus.core.ai.AiPurpose
import cn.edu.haut.campus.core.ai.AiRequest
import cn.edu.haut.campus.core.ai.AiResult
import cn.edu.haut.campus.core.ai.HttpsUrl
import cn.edu.haut.campus.core.database.AccountSessionState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.feature.settings.ai.state.AiConnectivityStage
import cn.edu.haut.campus.feature.settings.ai.state.AiConnectivityUiState
import cn.edu.haut.campus.feature.settings.ai.state.AiServiceUiAction
import cn.edu.haut.campus.feature.settings.ai.state.AiServiceUiState
import cn.edu.haut.campus.feature.settings.state.ProfileSessionScopedViewModel
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** AI 服务配置 ViewModel：保存/开关/擦除配置，并把连通性发送严格切成预览→用户点击→单次 consent。 */
class AiServiceViewModel(
    private val gateway: AiServiceGateway,
    profileId: String,
    sessionGeneration: Long?,
    isSessionActive: () -> Boolean,
    sessionStates: StateFlow<AccountSessionState>
) : ProfileSessionScopedViewModel(
    profileId = profileId,
    sessionGeneration = sessionGeneration,
    isSessionActive = isSessionActive,
    sessionStates = sessionStates
), AiServiceUiAction {
    private val mutableUiState = MutableStateFlow(AiServiceUiState())
    val uiState: StateFlow<AiServiceUiState> = mutableUiState.asStateFlow()

    @Volatile
    private var currentEndpoint: AiEndpoint? = null
    @Volatile
    private var pendingConnectivityRequest: AiRequest? = null

    init {
        launchSessionCollect(gateway.configuration) { configuration ->
            currentEndpoint = configuration.endpoint
            mutableUiState.update { state ->
                val nextMatrix = if (configuration.endpoint == null) SettingsUiMatrix.Empty else SettingsUiMatrix.Content
                val endpointChanged = state.endpointBaseUrl != configuration.endpoint?.baseUrl?.withoutTrailingSlash ||
                    state.model != configuration.endpoint?.model
                if (endpointChanged) pendingConnectivityRequest = null
                state.copy(
                    matrix = nextMatrix,
                    serviceAvailable = true,
                    keyStatus = configuration.keyStatus,
                    enabled = configuration.enabled,
                    endpointHost = configuration.endpoint?.host,
                    endpointBaseUrl = configuration.endpoint?.baseUrl?.withoutTrailingSlash,
                    model = configuration.endpoint?.model,
                    supportsVision = configuration.endpoint?.supportsVision == true,
                    connectivity = if (endpointChanged) AiConnectivityUiState() else state.connectivity
                )
            }
        }
    }

    override fun openEditor() {
        mutableUiState.update { it.copy(editorVisible = true, statusMessage = null) }
    }

    override fun closeEditor() {
        mutableUiState.update { it.copy(editorVisible = false) }
    }

    override fun saveConfiguration(baseUrl: String, model: String, supportsVision: Boolean, key: CharArray) {
        val keyCopy = key.copyOf()
        key.fill('\u0000')
        mutableUiState.update { it.copy(matrix = SettingsUiMatrix.Loading, statusMessage = null) }
        launchSessionJob {
            try {
                val endpoint = AiEndpoint(
                    baseUrl = HttpsUrl(baseUrl.trim()),
                    model = model.trim(),
                    supportsVision = supportsVision
                )
                gateway.saveEndpointAndKey(endpoint, keyCopy)
                mutableUiState.update {
                    it.copy(
                        matrix = SettingsUiMatrix.Content,
                        editorVisible = false,
                        statusMessage = "已在本机安全保存 AI 配置；默认开关仍需手动开启。"
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableUiState.update {
                    it.copy(
                        matrix = SettingsUiMatrix.error(retryable = true, message = "保存 AI 配置失败"),
                        statusMessage = userSafeMessage(error, "保存 AI 配置失败")
                    )
                }
            } finally {
                keyCopy.fill('\u0000')
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        launchSessionJob {
            try {
                gateway.setEnabled(enabled)
                mutableUiState.update {
                    it.copy(statusMessage = if (enabled) "BYOK AI 已开启；每次发送仍会先预览。" else "BYOK AI 已关闭。")
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableUiState.update {
                    it.copy(statusMessage = userSafeMessage(error, "更新 AI 开关失败"))
                }
            }
        }
    }

    override fun openClearConfirmation() {
        mutableUiState.update { it.copy(confirmClearVisible = true) }
    }

    override fun closeClearConfirmation() {
        mutableUiState.update { it.copy(confirmClearVisible = false) }
    }

    override fun clearConfiguration() {
        launchSessionJob {
            gateway.clear()
            pendingConnectivityRequest = null
            mutableUiState.update {
                it.copy(
                    confirmClearVisible = false,
                    connectivity = AiConnectivityUiState(),
                    statusMessage = "已清除本 Profile 的 AI endpoint、Key 与开关状态。"
                )
            }
        }
    }

    override fun startConnectivityTest() {
        if (mutableUiState.value.connectivity.stage in setOf(AiConnectivityStage.PREPARING, AiConnectivityStage.SENDING)) return
        pendingConnectivityRequest = null
        mutableUiState.update {
            it.copy(
                connectivity = AiConnectivityUiState(stage = AiConnectivityStage.PREPARING),
                statusMessage = null
            )
        }
        launchSessionJob {
            try {
                val request = connectivityRequest()
                val preview = gateway.preview(request)
                pendingConnectivityRequest = request
                mutableUiState.update {
                    it.copy(
                        connectivity = AiConnectivityUiState(
                            stage = AiConnectivityStage.PREVIEW_READY,
                            preview = preview
                        )
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                pendingConnectivityRequest = null
                mutableUiState.update {
                    it.copy(
                        connectivity = AiConnectivityUiState(
                            stage = AiConnectivityStage.FAILED,
                            errorMessage = userSafeMessage(error, "无法生成发送预览")
                        )
                    )
                }
            }
        }
    }

    override fun cancelConnectivityTest() {
        pendingConnectivityRequest = null
        mutableUiState.update { it.copy(connectivity = AiConnectivityUiState()) }
    }

    override fun confirmConnectivitySend() {
        val preview = mutableUiState.value.connectivity.preview ?: return
        val request = pendingConnectivityRequest ?: return
        val endpoint = currentEndpoint ?: return
        mutableUiState.update {
            it.copy(connectivity = it.connectivity.copy(stage = AiConnectivityStage.SENDING, errorMessage = null))
        }
        launchSessionJob {
            try {
                val consent = gateway.issueConsent(endpoint, preview)
                when (val result = gateway.send(request, consent)) {
                    is AiResult.Success -> mutableUiState.update {
                        it.copy(
                            connectivity = AiConnectivityUiState(
                                stage = AiConnectivityStage.SUCCESS,
                                resultSummary = "连通性测试成功 · 模型 " + result.model +
                                    (result.usage?.totalTokens?.let { total -> " · tokens " + total } ?: "")
                            ),
                            statusMessage = "连通性测试成功；结果未写入任何日志或诊断。"
                        )
                    }
                    is AiResult.Failure -> mutableUiState.update {
                        it.copy(
                            connectivity = AiConnectivityUiState(
                                stage = AiConnectivityStage.FAILED,
                                errorMessage = failureMessage(result)
                            )
                        )
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableUiState.update {
                    it.copy(
                        connectivity = AiConnectivityUiState(
                            stage = AiConnectivityStage.FAILED,
                            errorMessage = userSafeMessage(error, "连通性测试发送失败")
                        )
                    )
                }
            } finally {
                pendingConnectivityRequest = null
            }
        }
    }

    override fun resetStateForInvalidSession() {
        pendingConnectivityRequest = null
        currentEndpoint = null
        mutableUiState.value = AiServiceUiState(matrix = SettingsUiMatrix.Empty, serviceAvailable = false)
    }

    private fun connectivityRequest(): AiRequest = AiRequest(
        purpose = AiPurpose.CONNECTIVITY_TEST,
        messages = listOf(
            AiMessage(
                role = AiMessageRole.USER,
                text = "连通性测试：请只回复 ok。"
            )
        ),
        attachments = emptyList(),
        maxTokens = 8
    )

    private fun failureMessage(result: AiResult.Failure): String {
        val base = when (result.error) {
            AiError.AUTH_REJECTED -> "Provider 拒绝了 Key"
            AiError.RATE_LIMITED -> "Provider 限流，请稍后重试"
            AiError.TIMEOUT -> "连接超时"
            AiError.NETWORK_OFFLINE -> "网络不可达"
            AiError.TLS_ERROR -> "TLS 校验失败"
            AiError.ENDPOINT_NOT_HTTPS -> "endpoint 不是允许的 HTTPS"
            AiError.FEATURE_DISABLED -> "BYOK AI 仍处于关闭状态"
            AiError.NOT_CONFIGURED -> "尚未配置 endpoint 或 Key"
            else -> "连通性测试失败"
        }
        return result.detail?.let { base + "（" + it + "）" } ?: base
    }

    private fun userSafeMessage(error: Throwable, fallback: String): String = when (error) {
        is AiProviderException -> error.message ?: fallback
        is IllegalArgumentException -> error.message?.take(160) ?: fallback
        is IllegalStateException -> error.message?.take(160) ?: fallback
        else -> fallback
    }

    companion object {
        fun factory(
            repository: CampusRepository,
            gateway: AiServiceGateway,
            sessionStates: StateFlow<AccountSessionState>
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AiServiceViewModel(
                    gateway = gateway,
                    profileId = repository.profileId,
                    sessionGeneration = repository.sessionGeneration,
                    isSessionActive = { repository.isSessionActive },
                    sessionStates = sessionStates
                )
            }
        }
    }
}
