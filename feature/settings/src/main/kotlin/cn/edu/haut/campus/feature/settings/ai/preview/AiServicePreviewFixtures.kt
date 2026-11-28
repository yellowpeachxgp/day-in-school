package cn.edu.haut.campus.feature.settings.ai.preview

import cn.edu.haut.campus.core.ai.AiKeyStatus
import cn.edu.haut.campus.core.ai.AiMessage
import cn.edu.haut.campus.core.ai.AiMessageRole
import cn.edu.haut.campus.core.ai.AiPurpose
import cn.edu.haut.campus.core.ai.AiSendPreview
import cn.edu.haut.campus.core.ai.Redaction
import cn.edu.haut.campus.core.ai.RedactionKind
import cn.edu.haut.campus.feature.settings.ai.state.AiConnectivityStage
import cn.edu.haut.campus.feature.settings.ai.state.AiConnectivityUiState
import cn.edu.haut.campus.feature.settings.ai.state.AiServiceUiState
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** W2-06 AI 服务完整状态矩阵 fixtures；不得包含任何真实或可误用的 Key。 */
object AiServicePreviewFixtures {
    val connectivityPreview = AiSendPreview(
        endpointHost = "provider.example",
        model = "chat-vision-test",
        textChars = 14,
        attachmentCount = 0,
        redactions = listOf(Redaction(RedactionKind.PHONE_NUMBER, 1)),
        outboundMessages = listOf(
            AiMessage(AiMessageRole.USER, "连通性测试：请只回复 ok。[已脱敏:手机号]")
        ),
        estimatedRequestBytes = 640,
        purpose = AiPurpose.CONNECTIVITY_TEST,
        maxTokens = 8,
        attachmentSha256 = emptyList()
    )

    val loading = AiServiceUiState(matrix = SettingsUiMatrix.Loading)
    val empty = AiServiceUiState(matrix = SettingsUiMatrix.Empty, keyStatus = AiKeyStatus.NONE)
    val content = AiServiceUiState(
        matrix = SettingsUiMatrix.Content,
        keyStatus = AiKeyStatus.CONFIGURED,
        enabled = true,
        endpointHost = "provider.example",
        endpointBaseUrl = "https://provider.example/v1",
        model = "chat-vision-test",
        supportsVision = true
    )
    val partial = content.copy(matrix = SettingsUiMatrix.Partial)
    val stale = content.copy(matrix = SettingsUiMatrix.Stale)
    val errorRetryable = content.copy(matrix = SettingsUiMatrix.error(retryable = true, message = "AI 配置错误（可重试）"))
    val errorTerminal = content.copy(matrix = SettingsUiMatrix.error(retryable = false, message = "AI 配置错误（不可重试）"))
    val needsPermission = AiServiceUiState(matrix = SettingsUiMatrix.NeedsPermission)
    val needsLogin = AiServiceUiState(matrix = SettingsUiMatrix.NeedsLogin)
    val offline = content.copy(matrix = SettingsUiMatrix.Offline)
    val unsupported = AiServiceUiState(matrix = SettingsUiMatrix.Unsupported, serviceAvailable = false)

    val editor = content.copy(editorVisible = true)
    val confirmClear = content.copy(confirmClearVisible = true)
    val previewReady = content.copy(
        connectivity = AiConnectivityUiState(
            stage = AiConnectivityStage.PREVIEW_READY,
            preview = connectivityPreview
        )
    )
    val connectivitySuccess = content.copy(
        connectivity = AiConnectivityUiState(
            stage = AiConnectivityStage.SUCCESS,
            resultSummary = "连通性测试成功 · 模型 chat-vision-test · tokens 7"
        )
    )

    val matrixStates: Map<String, AiServiceUiState> = linkedMapOf(
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
