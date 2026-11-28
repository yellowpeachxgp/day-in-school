package cn.edu.haut.campus.feature.settings.ai

import cn.edu.haut.campus.core.ai.AiConsentToken
import cn.edu.haut.campus.core.ai.AiEndpoint
import cn.edu.haut.campus.core.ai.AiProviderConfiguration
import cn.edu.haut.campus.core.ai.AiRequest
import cn.edu.haut.campus.core.ai.AiResult
import cn.edu.haut.campus.core.ai.AiSendPreview
import cn.edu.haut.campus.core.ai.android.AiAndroidService
import kotlinx.coroutines.flow.Flow

/** 设置页使用的窄化 BYOK 门面；没有任何方法能把 Key 明文返回给 UI 或 ViewModel。 */
interface AiServiceGateway {
    val configuration: Flow<AiProviderConfiguration>
    suspend fun saveEndpointAndKey(endpoint: AiEndpoint, key: CharArray)
    suspend fun setEnabled(enabled: Boolean)
    suspend fun clear()
    suspend fun preview(request: AiRequest): AiSendPreview
    fun issueConsent(endpoint: AiEndpoint, preview: AiSendPreview): AiConsentToken
    suspend fun send(request: AiRequest, consent: AiConsentToken): AiResult
}

class AndroidAiServiceGateway(
    private val scope: cn.edu.haut.campus.core.model.SchoolAccountScope,
    private val service: AiAndroidService
) : AiServiceGateway {
    override val configuration: Flow<AiProviderConfiguration> = service.configuration(scope)

    override suspend fun saveEndpointAndKey(endpoint: AiEndpoint, key: CharArray) =
        service.saveEndpointAndKey(scope, endpoint, key)

    override suspend fun setEnabled(enabled: Boolean) = service.setEnabled(scope, enabled)

    override suspend fun clear() = service.clear(scope)

    override suspend fun preview(request: AiRequest): AiSendPreview = service.preview(scope, request)

    override fun issueConsent(endpoint: AiEndpoint, preview: AiSendPreview): AiConsentToken =
        service.issueConsent(scope, endpoint, preview)

    override suspend fun send(request: AiRequest, consent: AiConsentToken): AiResult =
        service.send(scope, request, consent)
}
