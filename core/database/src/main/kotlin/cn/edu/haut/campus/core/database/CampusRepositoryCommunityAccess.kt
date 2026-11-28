package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.community.CommunityCacheIdentity
import cn.edu.haut.campus.core.community.CommunityClient
import cn.edu.haut.campus.core.community.CommunityClientFactory
import cn.edu.haut.campus.core.community.CommunityFeatureGate
import cn.edu.haut.campus.core.community.CommunitySessionSnapshot
import cn.edu.haut.campus.core.community.CommunityTransport
import cn.edu.haut.campus.core.community.OkHttpCommunityTransport
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import java.time.Clock
import kotlinx.coroutines.flow.first

/**
 * W4-07 群组协作客户端组装结果。access token 只留在 session provider 的闭包调用期内，
 * 不向 feature 层暴露；feature 只能拿到已装配 client 与当前 cloudUserId 读取器。
 */
class CampusCommunityClientRuntime(
    val client: CommunityClient,
    val currentCloudUserId: suspend () -> String?,
)

/**
 * 为当前 Profile 装配 community contract client。
 *
 * baseUrl 在组装时取身份锁内快照；每次请求重新解析令牌并复核身份世代。云端地址或云身份变更后，
 * 调用方应重新进入页面/重建 runtime，而不是复用旧 client 跨身份发送请求。
 */
suspend fun CampusRepository.createCommunityClientRuntime(
    featureGate: CommunityFeatureGate = CommunityFeatureGate.ENABLED,
    transport: CommunityTransport = OkHttpCommunityTransport(),
    clock: Clock = Clock.systemUTC(),
): CampusCommunityClientRuntime {
    val initialIdentity = snapshotCloudIdentitySession()
    val baseUrl = initialIdentity?.apiBaseUrl ?: NetworkProxyConfig.normalizeCloudApiBaseUrl(
        preferencesFlow.first().networkProxyConfig.customCloudApiBaseUrl,
    )
    val sessionSnapshotProvider: suspend () -> CommunitySessionSnapshot = {
        val identity = snapshotCloudIdentitySession()
        if (identity == null) {
            CommunitySessionSnapshot(accessToken = null, cacheIdentity = null)
        } else {
            val token = resolveCommunityAccessToken(identity)
            CommunitySessionSnapshot(
                accessToken = token.takeIf { it.isNotBlank() },
                cacheIdentity = CommunityCacheIdentity(
                    cloudUserId = identity.cloudUserId,
                    identityGeneration = identity.generation,
                ),
            )
        }
    }
    val assembly = CommunityClientFactory.createFileBacked(
        filesDir = communityFilesDir(),
        baseUrl = baseUrl,
        scope = automationScope,
        sessionSnapshotProvider = sessionSnapshotProvider,
        featureGate = featureGate,
        transport = transport,
        clock = clock,
    )
    return CampusCommunityClientRuntime(
        client = assembly.client,
        currentCloudUserId = { snapshotCloudIdentitySession()?.cloudUserId },
    )
}
