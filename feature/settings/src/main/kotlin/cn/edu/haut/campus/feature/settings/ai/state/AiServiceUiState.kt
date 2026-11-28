package cn.edu.haut.campus.feature.settings.ai.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.ai.AiKeyStatus
import cn.edu.haut.campus.core.ai.AiSendPreview
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 连通性测试只在 PREVIEW_READY 后允许生成单次同意并发送。 */
enum class AiConnectivityStage {
    IDLE,
    PREPARING,
    PREVIEW_READY,
    SENDING,
    SUCCESS,
    FAILED
}

@Immutable
data class AiConnectivityUiState(
    val stage: AiConnectivityStage = AiConnectivityStage.IDLE,
    val preview: AiSendPreview? = null,
    val resultSummary: String? = null,
    val errorMessage: String? = null
)

/**
 * AI 服务配置页不可变快照。Key 明文、Key 输入框文本和 consent token 都不允许进入本状态；
 * endpoint/model/enabled 是非秘密配置元数据，可以按 Profile 展示。
 */
@Immutable
data class AiServiceUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Loading,
    val serviceAvailable: Boolean = true,
    val keyStatus: AiKeyStatus = AiKeyStatus.NONE,
    val enabled: Boolean = false,
    val endpointHost: String? = null,
    val endpointBaseUrl: String? = null,
    val model: String? = null,
    val supportsVision: Boolean = false,
    val editorVisible: Boolean = false,
    val confirmClearVisible: Boolean = false,
    val connectivity: AiConnectivityUiState = AiConnectivityUiState(),
    val statusMessage: String? = null
)
