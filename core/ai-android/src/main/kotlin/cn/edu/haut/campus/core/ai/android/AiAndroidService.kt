package cn.edu.haut.campus.core.ai.android

import android.content.Context
import cn.edu.haut.campus.core.ai.AiConsentCenter
import cn.edu.haut.campus.core.ai.AiConsentToken
import cn.edu.haut.campus.core.ai.AiEndpoint
import cn.edu.haut.campus.core.ai.AiKeyStatus
import cn.edu.haut.campus.core.ai.AiProviderConfiguration
import cn.edu.haut.campus.core.ai.AiProviderPort
import cn.edu.haut.campus.core.ai.AiRequest
import cn.edu.haut.campus.core.ai.AiResult
import cn.edu.haut.campus.core.ai.AiSendPreview
import cn.edu.haut.campus.core.ai.HttpUrlConnectionAiTransport
import cn.edu.haut.campus.core.ai.OpenAiCompatibleProvider
import cn.edu.haut.campus.core.ai.AiTransport
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.SchoolAccountScope
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/** 面向 UI/领域卡的 BYOK 组合入口；ConsentCenter 与 Provider/KeyStore 必须同生命周期，避免 token 被跨实例伪造。 */
class AiAndroidService internal constructor(
    private val store: AndroidAiKeyStore,
    val consentCenter: AiConsentCenter,
    private val provider: AiProviderPort
) {
    fun configuration(scope: SchoolAccountScope): Flow<AiProviderConfiguration> = store.observeConfiguration(scope)
    fun keyStatus(scope: SchoolAccountScope): Flow<cn.edu.haut.campus.core.ai.AiKeyStatus> = store.status(scope)

    suspend fun saveEndpointAndKey(scope: SchoolAccountScope, endpoint: AiEndpoint, key: CharArray) =
        store.put(scope, endpoint, key)

    suspend fun setEnabled(scope: SchoolAccountScope, enabled: Boolean) = store.setEnabled(scope, enabled)
    suspend fun clear(scope: SchoolAccountScope) = store.wipeAll(scope)
    suspend fun preview(scope: SchoolAccountScope, request: AiRequest): AiSendPreview = provider.preview(scope, request)

    /** 只有预览页“发送”按钮回调应调用本方法；返回值立刻交给 provider.send，不写入日志、持久层或 saved state。 */
    fun issueConsent(scope: SchoolAccountScope, endpoint: AiEndpoint, preview: AiSendPreview): AiConsentToken =
        consentCenter.issue(scope, endpoint, preview)

    suspend fun send(scope: SchoolAccountScope, request: AiRequest, consent: AiConsentToken): AiResult =
        provider.send(scope, request, consent)

    companion object {
        internal fun create(
            context: Context,
            database: HautDatabase?,
            transport: AiTransport = HttpUrlConnectionAiTransport()
        ): AiAndroidService {
            val store = AndroidAiKeyStore(context)
            val consentCenter = AiConsentCenter()
            val provider = OpenAiCompatibleProvider(
                configurationAccess = store,
                keyMaterialAccess = store,
                consentCenter = consentCenter,
                attachmentReader = database?.let(::VaultAiAttachmentReader),
                transport = transport
            )
            return AiAndroidService(store, consentCenter, provider)
        }
    }
}

/** 按 Profile 保持 consent/provider 同生命周期；Profile ID 本身是一一映射，不携带学号或学校账号秘密。 */
object AiAndroidServiceRegistry {
    private val services = ConcurrentHashMap<String, AiAndroidService>()

    fun forProfile(
        context: Context,
        scope: SchoolAccountScope,
        database: HautDatabase?
    ): AiAndroidService = services.getOrPut(AiConsentCenter.scopeFingerprint(scope)) {
        AiAndroidService.create(context.applicationContext, database)
    }

    internal fun clearForTest() {
        services.clear()
    }
}
